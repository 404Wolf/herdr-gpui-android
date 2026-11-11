//! Links on terminal rows, for tap-to-open: http(s) URLs and file paths in
//! the text, including ones the terminal or the program wrapped onto further
//! rows. OSC 8 hyperlinks are the grid's own and only classified here.
use std::ops::Range;

const SCHEMES: [&str; 2] = ["https://", "http://"];
/// Rows a wrapped link may span on either side of the tapped one.
const MAX_ROWS: usize = 12;
/// Border glyphs a program draws at a box's edges; never part of a link.
const BORDERS: [char; 4] = ['\u{2502}', '\u{2503}', '\u{2551}', '|'];
/// Extensions that make a bare `name.ext` a file, without a slash to say so.
const EXTENSIONS: &[&str] = &[
    "c", "cc", "cfg", "conf", "cpp", "css", "csv", "diff", "ex", "exs", "go", "gradle", "h", "hpp",
    "hs", "html", "ini", "java", "js", "json", "jsx", "kt", "kts", "lock", "log", "lua", "md", "mk",
    "ml", "nix", "patch", "properties", "proto", "py", "rb", "rs", "scss", "sh", "sql", "svelte",
    "swift", "toml", "ts", "tsx", "txt", "vue", "xml", "yaml", "yml", "zig", "zsh",
];

/// What a tap on a link opens.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Link {
    /// An http(s) URL, for the browser.
    Web(String),
    /// A file on the terminal's machine: absolute, `~/`, or relative to the
    /// terminal's folder, with the line to show when one was written.
    File { path: String, line: Option<u32> },
}

impl Link {
    /// An OSC 8 target: http(s) as is, `file://host/path` as its path.
    /// Anything else (custom schemes, mail) is not opened.
    pub fn from_uri(uri: &str) -> Option<Self> {
        if uri.len() > 4096 || uri.chars().any(char::is_control) {
            return None;
        }
        let lower = uri.get(..8).unwrap_or(uri).to_ascii_lowercase();
        if SCHEMES.iter().any(|scheme| lower.starts_with(scheme)) {
            return Some(Self::Web(uri.to_owned()));
        }
        let rest = uri.strip_prefix("file://")?;
        let path = &rest[rest.find('/')?..];
        let (path, line) = match path.rsplit_once("#L") {
            Some((path, line)) => (path, line.parse().ok().filter(|l| *l > 0)),
            None => (path, None),
        };
        Some(Self::File { path: percent_decode(path)?, line })
    }
}

/// One terminal row: one char per column, and whether the terminal wrapped
/// it onto the next row (its last cell's WRAPLINE).
#[derive(Clone, Debug, Default)]
pub struct Row {
    pub chars: Vec<char>,
    pub wrapped: bool,
}

impl Row {
    pub fn new(text: &str, wrapped: bool) -> Self {
        Self { chars: text.chars().collect(), wrapped }
    }
}

/// A link found under a tap: what it opens and the columns it covers on each
/// row (indices into the rows given), for the pressed highlight.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Found {
    pub link: Link,
    pub spans: Vec<(usize, Range<usize>)>,
}

/// The link covering column `col` of `rows[row]`. A link continues onto the
/// next row when the terminal wrapped it there, or when the program broke it
/// itself (as Claude Code and other TUIs do): the row's text runs to its right
/// edge (or a box border) and the next row goes on with no space.
pub fn link_at(rows: &[Row], row: usize, col: usize) -> Option<Found> {
    if col >= rows.get(row)?.chars.len() {
        return None;
    }
    let mut first = row;
    while first > 0 && row - first < MAX_ROWS && joins(&rows[first - 1], &rows[first]).is_some() {
        first -= 1;
    }
    let mut last = row;
    while last + 1 < rows.len() && last - row < MAX_ROWS && joins(&rows[last], &rows[last + 1]).is_some() {
        last += 1;
    }
    // The logical line: each row's part, and where in it each char came from.
    let mut text = Vec::new();
    let mut origin = Vec::new();
    let mut tapped = None;
    for r in first..=last {
        let start = if r > first { joins(&rows[r - 1], &rows[r]).map_or(0, |j| j.next_start) } else { 0 };
        let end = if r < last { joins(&rows[r], &rows[r + 1]).map_or(0, |j| j.end) } else { rows[r].chars.len() };
        if r == row {
            if !(start..end).contains(&col) {
                // Indentation or a border outside the joined text: only this row.
                let origin: Vec<_> = (0..rows[r].chars.len()).map(|c| (r, c)).collect();
                return found_in(&rows[r].chars, &origin, col);
            }
            tapped = Some(text.len() + col - start);
        }
        for c in start..end {
            text.push(rows[r].chars[c]);
            origin.push((r, c));
        }
    }
    found_in(&text, &origin, tapped?)
}

/// The link at index `at` of a logical line, mapped back to row spans.
fn found_in(text: &[char], origin: &[(usize, usize)], at: usize) -> Option<Found> {
    let (range, link) = link_in(text, at)?;
    let mut spans: Vec<(usize, Range<usize>)> = Vec::new();
    for &(r, c) in &origin[range] {
        match spans.last_mut() {
            Some((row, cols)) if *row == r && cols.end == c => cols.end = c + 1,
            _ => spans.push((r, c..c + 1)),
        }
    }
    Some(Found { link, spans })
}

/// How row `a` continues onto row `b`, if it does.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Join {
    /// Where `a`'s part ends.
    end: usize,
    /// Where `b`'s part starts.
    next_start: usize,
}

fn joins(a: &Row, b: &Row) -> Option<Join> {
    if a.wrapped {
        return Some(Join { end: a.chars.len(), next_start: 0 });
    }
    // A program's own break: text to the right edge, its last word link-like,
    // and the next row's first word (after the same kind of margin) goes on.
    let chars = &a.chars;
    let mut edge = chars.len();
    let mut end = trim_end(chars, edge);
    if end > 0 && BORDERS.contains(&chars[end - 1]) {
        edge = end - 1;
        end = trim_end(chars, edge);
    }
    // One column of padding before a border, none at the screen's edge.
    if end == 0 || end + 1 < edge {
        return None;
    }
    let word_start = chars[..end].iter().rposition(|c| !part_of_link(*c)).map_or(0, |i| i + 1);
    if word_start == end {
        return None;
    }
    let word: String = chars[word_start..end].iter().collect();
    let margin = chars[..word_start].iter().all(|c| c.is_whitespace() || BORDERS.contains(c));
    // A row that is one word (a continuation itself), or a word that starts a
    // URL or a path; prose that happens to reach the edge never joins.
    if !(margin || word.contains("://") || word.contains('/')) {
        return None;
    }
    let next_start = b.chars.iter().position(|c| !(c.is_whitespace() || BORDERS.contains(c)))?;
    let next_end = b.chars[next_start..].iter().position(|c| !part_of_link(*c)).map_or(b.chars.len(), |i| next_start + i);
    if next_end == next_start {
        return None;
    }
    Some(Join { end, next_start })
}

fn trim_end(chars: &[char], mut end: usize) -> usize {
    while end > 0 && (chars[end - 1].is_whitespace() || chars[end - 1] == '\0') {
        end -= 1;
    }
    end
}

/// Characters a URL or path may hold here; quotes, brackets of other kinds
/// and box drawing end one.
fn part_of_link(c: char) -> bool {
    !(c.is_whitespace()
        || c.is_control()
        || c == '\0'
        || matches!(c, '"' | '\'' | '<' | '>' | '`' | '|')
        || ('\u{2500}'..='\u{257f}').contains(&c))
}

/// The link covering index `at` of `text`, with the indices it spans.
fn link_in(text: &[char], at: usize) -> Option<(Range<usize>, Link)> {
    if at >= text.len() || !part_of_link(text[at]) {
        return None;
    }
    let mut start = at;
    while start > 0 && part_of_link(text[start - 1]) {
        start -= 1;
    }
    let mut end = at + 1;
    while end < text.len() && part_of_link(text[end]) {
        end += 1;
    }
    let word: String = text[start..end].iter().collect();
    // Byte offset of each char of the word, to map matches back.
    let offsets: Vec<usize> = word.char_indices().map(|(i, _)| i).chain([word.len()]).collect();
    let index_of = |byte: usize| offsets.iter().position(|&b| b == byte).unwrap_or(offsets.len() - 1);
    let tapped = at - start;
    // URLs first: a word may hold one after other text ("(see:https://…").
    let mut search = 0;
    while let Some(found) = SCHEMES.iter().filter_map(|s| word[search..].find(s).map(|i| search + i)).min() {
        let url = trim_url(&word[found..]);
        let (first, last) = (index_of(found), index_of(found + url.len()));
        if url.len() > SCHEMES.iter().find(|s| url.starts_with(**s)).map_or(0, |s| s.len())
            && (first..last).contains(&tapped)
        {
            return Some((start + first..start + last, Link::Web(url.to_owned())));
        }
        search = found + 1;
    }
    if word.contains("://") {
        return None;
    }
    // A path: drop wrapping punctuation and brackets, then a `:line:col`.
    let lead = word.find(|c: char| !matches!(c, '(' | '[' | '{' | ',' | ':' | ';')).unwrap_or(word.len());
    let body = trim_url(&word[lead..]);
    let (path, line) = split_line(body);
    if !looks_like_file(path) {
        return None;
    }
    let (first, last) = (index_of(lead), index_of(lead + body.len()));
    (first..last).contains(&tapped).then(|| (start + first..start + last, Link::File { path: path.to_owned(), line }))
}

/// Drops sentence punctuation and an unbalanced closing bracket from the end.
fn trim_url(mut url: &str) -> &str {
    loop {
        let trimmed = url.trim_end_matches(['.', ',', ';', ':', '!', '?']);
        let trimmed = match trimmed.chars().last() {
            Some(close @ (')' | ']' | '}')) => {
                let open = match close {
                    ')' => '(',
                    ']' => '[',
                    _ => '{',
                };
                if trimmed.matches(close).count() > trimmed.matches(open).count() {
                    &trimmed[..trimmed.len() - 1]
                } else {
                    trimmed
                }
            }
            _ => trimmed,
        };
        if trimmed.len() == url.len() {
            return url;
        }
        url = trimmed;
    }
}

/// `path:12`, `path:12:5` and `path#L12` name a line.
fn split_line(text: &str) -> (&str, Option<u32>) {
    if let Some((path, line)) = text.rsplit_once("#L") {
        return (path, line.parse().ok().filter(|l| *l > 0));
    }
    let mut path = text;
    let mut numbers = Vec::new();
    while let Some((head, tail)) = path.rsplit_once(':') {
        if numbers.len() == 2 || tail.is_empty() || !tail.bytes().all(|b| b.is_ascii_digit()) {
            break;
        }
        numbers.push(tail);
        path = head;
    }
    (path, numbers.last().and_then(|line| line.parse().ok()).filter(|l| *l > 0))
}

fn looks_like_file(path: &str) -> bool {
    if path.is_empty() || path.len() > 4096 || !path.chars().any(char::is_alphabetic) {
        return false;
    }
    if path.contains("//") || path.ends_with('/') {
        return false;
    }
    let name = path.rsplit('/').next().unwrap_or(path);
    let extension = name
        .rsplit_once('.')
        .filter(|(stem, ext)| !stem.is_empty() && !ext.is_empty() && ext.chars().all(|c| c.is_ascii_alphanumeric()) && ext.chars().any(|c| c.is_ascii_alphabetic()));
    let rooted = path.starts_with('/') || path.starts_with("~/") || path.starts_with("./") || path.starts_with("../");
    if path.contains('/') {
        // `and/or` is prose; a rooted path or one ending in a file name is not.
        return (rooted && path.len() > 1) || extension.is_some();
    }
    // Lone file names only with an extension code is written in.
    extension.is_some_and(|(_, ext)| EXTENSIONS.contains(&ext.to_ascii_lowercase().as_str()))
}

fn percent_decode(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = text.get(i + 1..i + 3)?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok().filter(|s| !s.chars().any(char::is_control))
}

/// The http(s) URL covering column `col` of a single row, with its columns.
#[doc(hidden)]
pub fn url_at(row: &[char], col: usize) -> Option<(Range<usize>, String)> {
    let rows = [Row { chars: row.to_vec(), wrapped: false }];
    match link_at(&rows, 0, col)? {
        Found { link: Link::Web(url), spans } => Some((spans.first()?.1.clone(), url)),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(text: &str, col: usize) -> Option<(Range<usize>, String)> {
        url_at(&text.chars().collect::<Vec<_>>(), col)
    }

    fn web(url: &str) -> Link {
        Link::Web(url.into())
    }

    fn file(path: &str, line: Option<u32>) -> Link {
        Link::File { path: path.into(), line }
    }

    fn link(rows: &[Row], row: usize, col: usize) -> Option<Link> {
        link_at(rows, row, col).map(|found| found.link)
    }

    #[test]
    fn finds_the_url_under_a_column_and_drops_trailing_punctuation() {
        let row = "see https://github.com/o/r/pull/1315. done";
        assert_eq!(at(row, 10), Some((4..36, "https://github.com/o/r/pull/1315".into())));
        assert_eq!(at(row, 2), None);
        assert_eq!(at(row, 37), None);
    }

    #[test]
    fn keeps_balanced_brackets_and_drops_a_closing_one() {
        assert_eq!(
            at("(https://en.wikipedia.org/wiki/A_(b))", 3).map(|(_, url)| url),
            Some("https://en.wikipedia.org/wiki/A_(b)".into())
        );
        assert_eq!(at("[http://x.dev/a]", 2).map(|(_, url)| url), Some("http://x.dev/a".into()));
    }

    #[test]
    fn maps_wide_text_to_columns_and_picks_the_right_of_two() {
        let row = "界 http://a.io and https://b.io";
        assert_eq!(at(row, 4).map(|(_, url)| url), Some("http://a.io".into()));
        assert_eq!(at(row, 20), Some((18..30, "https://b.io".into())));
        assert_eq!(at("https:// x", 2), None);
    }

    #[test]
    fn a_url_the_terminal_wrapped_opens_from_any_of_its_rows() {
        let rows = [
            Row::new("open https://github.com/o", true),
            Row::new("rg/repo/pull/12 now", false),
        ];
        let url = web("https://github.com/org/repo/pull/12");
        assert_eq!(link(&rows, 0, 8), Some(url.clone()));
        assert_eq!(link(&rows, 1, 3), Some(url));
        assert_eq!(link(&rows, 1, 17), None);
        assert_eq!(
            link_at(&rows, 1, 0).map(|found| found.spans),
            Some(vec![(0, 5..25), (1, 0..15)])
        );
    }

    #[test]
    fn a_url_the_program_broke_at_its_edge_joins_its_indented_continuation() {
        // Claude Code wraps a long URL itself, then indents the rest.
        let rows = [
            Row::new("  See https://example.com/a/very", false),
            Row::new("  /long/path?q=1 for details", false),
        ];
        let url = web("https://example.com/a/very/long/path?q=1");
        assert_eq!(link(&rows, 0, 10), Some(url.clone()));
        assert_eq!(link(&rows, 1, 5), Some(url));
        // Inside a box: text runs to the padding before the border.
        let boxed = [
            Row::new("│ https://x.dev/abc │", false),
            Row::new("│ def/g             │", false),
        ];
        assert_eq!(link(&boxed, 1, 3), Some(web("https://x.dev/abcdef/g")));
    }

    #[test]
    fn prose_that_reaches_the_edge_never_joins_the_next_row() {
        let rows = [Row::new("a sentence ending here", false), Row::new("https://x.io next", false)];
        assert_eq!(link(&rows, 1, 3), Some(web("https://x.io")));
        // A URL that stops short of the edge does not take the next row's word.
        let short = [Row::new("https://x.io/a      ", false), Row::new("words", false)];
        assert_eq!(link(&short, 0, 3), Some(web("https://x.io/a")));
        assert_eq!(link(&short, 1, 1), None);
    }

    #[test]
    fn file_paths_with_lines_open_as_files() {
        let rows = [Row::new("error at src/links.rs:42:7 and ./run.sh, see /etc/hosts.", false)];
        assert_eq!(link(&rows, 0, 12), Some(file("src/links.rs", Some(42))));
        assert_eq!(link(&rows, 0, 33), Some(file("./run.sh", None)));
        assert_eq!(link(&rows, 0, 50), Some(file("/etc/hosts", None)));
        let rows = [Row::new("(README.md#L12) and Cargo.toml: ~/x/y", false)];
        assert_eq!(link(&rows, 0, 3), Some(file("README.md", Some(12))));
        assert_eq!(link(&rows, 0, 22), Some(file("Cargo.toml", None)));
        assert_eq!(link(&rows, 0, 34), Some(file("~/x/y", None)));
    }

    #[test]
    fn prose_numbers_and_abbreviations_are_not_files() {
        for text in ["and/or", "e.g.", "v1.2.3", "3.14", "12:30", "/", "a//b", "TODO.", "src/"] {
            let rows = [Row::new(text, false)];
            assert_eq!(link(&rows, 0, 1), None, "{text}");
        }
    }

    #[test]
    fn a_path_the_program_wrapped_joins_too() {
        let rows = [
            Row::new("  edited /home/wolf/Code/proj", false),
            Row::new("  /src/main.rs:10", false),
        ];
        assert_eq!(link(&rows, 1, 4), Some(file("/home/wolf/Code/proj/src/main.rs", Some(10))));
    }

    #[test]
    fn osc8_targets_classify_by_scheme() {
        assert_eq!(Link::from_uri("https://a.io/x"), Some(web("https://a.io/x")));
        assert_eq!(Link::from_uri("HTTP://a.io"), Some(web("HTTP://a.io")));
        assert_eq!(Link::from_uri("file://host/home/w/a%20b.rs"), Some(file("/home/w/a b.rs", None)));
        assert_eq!(Link::from_uri("file:///tmp/x.rs#L9"), Some(file("/tmp/x.rs", Some(9))));
        assert_eq!(Link::from_uri("mailto:a@b"), None);
        assert_eq!(Link::from_uri("file:///a%0a"), None);
        assert_eq!(Link::from_uri("https://a.io/\n"), None);
    }
}
