//! Links on terminal rows, for tap-to-open: http(s) URLs and file paths in
//! the text, including ones the terminal or the program wrapped onto further
//! rows. OSC 8 hyperlinks are the grid's own and only classified here.
use rio_vt::crosswords::{
    Crosswords,
    pos::{Column, Line, Pos},
};
use rio_vt::event::EventListener;
use std::ops::Range;

const SCHEMES: [&str; 2] = ["https://", "http://"];
/// Rows a wrapped link may span on either side of the tapped one.
const MAX_ROWS: usize = 12;
/// Border glyphs a program draws at a box's edges; never part of a link.
const BORDERS: [char; 4] = ['\u{2502}', '\u{2503}', '\u{2551}', '|'];
/// Extensions that make a bare `name.ext` a file, without a slash to say so.
const EXTENSIONS: &[&str] = &[
    "c",
    "cc",
    "cfg",
    "conf",
    "cpp",
    "css",
    "csv",
    "diff",
    "ex",
    "exs",
    "go",
    "gradle",
    "h",
    "hpp",
    "hs",
    "html",
    "ini",
    "java",
    "js",
    "json",
    "jsx",
    "kt",
    "kts",
    "lock",
    "log",
    "lua",
    "md",
    "mk",
    "ml",
    "nix",
    "patch",
    "properties",
    "proto",
    "py",
    "rb",
    "rs",
    "scss",
    "sh",
    "sql",
    "svelte",
    "swift",
    "toml",
    "ts",
    "tsx",
    "txt",
    "vue",
    "xml",
    "yaml",
    "yml",
    "zig",
    "zsh",
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
        Some(Self::File {
            path: percent_decode(path)?,
            line,
        })
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
        Self {
            chars: text.chars().collect(),
            wrapped,
        }
    }
}

/// A link found under a tap: what it opens and the columns it covers on each
/// row (indices into the rows given), for the pressed highlight.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Found {
    pub link: Link,
    pub spans: Vec<(usize, Range<usize>)>,
}

/// Whether the cell drawn at `screen_col` of row `line` lies in a pressed
/// link's `spans`. Spans count grid columns; a window panned or following the
/// caret starts `origin_col` columns into the grid, so screen and grid columns
/// differ by that much.
pub fn pressed<L: PartialEq>(
    spans: &[(L, Range<usize>)],
    line: &L,
    screen_col: usize,
    origin_col: usize,
) -> bool {
    let col = screen_col + origin_col;
    spans
        .iter()
        .any(|(at, columns)| at == line && columns.contains(&col))
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
    while last + 1 < rows.len()
        && last - row < MAX_ROWS
        && joins(&rows[last], &rows[last + 1]).is_some()
    {
        last += 1;
    }
    // The logical line: each row's part, and where in it each char came from.
    let mut text = Vec::new();
    let mut origin = Vec::new();
    let mut tapped = None;
    for r in first..=last {
        let start = if r > first {
            joins(&rows[r - 1], &rows[r]).map_or(0, |j| j.next_start)
        } else {
            0
        };
        let end = if r < last {
            joins(&rows[r], &rows[r + 1]).map_or(0, |j| j.end)
        } else {
            rows[r].chars.len()
        };
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
    let (range, link) = markdown_in(text, at)
        .or_else(|| link_in(text, at).map(|found| cut_at_closing_edge(text, origin, found)))?;
    if !range.contains(&at) {
        return None;
    }
    let mut spans: Vec<(usize, Range<usize>)> = Vec::new();
    for &(r, c) in &origin[range] {
        match spans.last_mut() {
            Some((row, cols)) if *row == r && cols.end == c => cols.end = c + 1,
            _ => spans.push((r, c..c + 1)),
        }
    }
    Some(Found { link, spans })
}

/// A URL read across rows ends at a row's last character when that is a
/// bracket closing one the URL never opened: a markdown link's `)` (or a
/// parenthesised URL's) at the edge, with prose going on below. Brackets the
/// URL opened itself (`…_(b)` at the edge, `/tail` below) are its own.
fn cut_at_closing_edge(
    text: &[char],
    origin: &[(usize, usize)],
    (range, link): (Range<usize>, Link),
) -> (Range<usize>, Link) {
    if !matches!(link, Link::Web(_)) {
        return (range, link);
    }
    let mut depth = [0usize; 3];
    for i in range.clone() {
        let kind = match text[i] {
            '(' | ')' => 0,
            '[' | ']' => 1,
            '{' | '}' => 2,
            _ => continue,
        };
        if matches!(text[i], '(' | '[' | '{') {
            depth[kind] += 1;
        } else if depth[kind] > 0 {
            depth[kind] -= 1;
        } else if i + 1 < range.end && origin[i].0 != origin[i + 1].0 {
            let head: String = text[range.start..i].iter().collect();
            let url = trim_url(&head);
            let end = range.start + url.chars().count();
            return (range.start..end, Link::Web(url.to_owned()));
        }
    }
    (range, link)
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
        return Some(Join {
            end: a.chars.len(),
            next_start: 0,
        });
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
    let word_start = chars[..end]
        .iter()
        .rposition(|c| !part_of_link(*c))
        .map_or(0, |i| i + 1);
    if word_start == end {
        return None;
    }
    let word: String = chars[word_start..end].iter().collect();
    let margin = chars[..word_start]
        .iter()
        .all(|c| c.is_whitespace() || BORDERS.contains(c));
    let next_start = b
        .chars
        .iter()
        .position(|c| !(c.is_whitespace() || BORDERS.contains(c)))?;
    let next_end = b.chars[next_start..]
        .iter()
        .position(|c| !part_of_link(*c))
        .map_or(b.chars.len(), |i| next_start + i);
    if next_end == next_start {
        return None;
    }
    // A row that is one word (a continuation itself), or a word that starts a
    // URL or a path, or one the break split inside a scheme (`[text](http`
    // then `s://…`) or a markdown link's `](` (`[the docs]` then `(https…`);
    // prose that happens to reach the edge never joins.
    let split = || {
        let joined: String = word
            .chars()
            .chain(b.chars[next_start..next_end].iter().copied())
            .collect();
        SCHEMES.iter().any(|scheme| {
            [scheme.to_string(), format!("]({scheme}")]
                .iter()
                .any(|marker| {
                    joined
                        .match_indices(marker.as_str())
                        .any(|(at, _)| at < word.len() && at + marker.len() > word.len())
                })
        })
    };
    if !(margin || word.contains("://") || word.contains('/') || split()) {
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
    let offsets: Vec<usize> = word
        .char_indices()
        .map(|(i, _)| i)
        .chain([word.len()])
        .collect();
    let index_of = |byte: usize| {
        offsets
            .iter()
            .position(|&b| b == byte)
            .unwrap_or(offsets.len() - 1)
    };
    let tapped = at - start;
    // URLs first: a word may hold one after other text ("(see:https://…").
    let mut search = 0;
    while let Some(found) = SCHEMES
        .iter()
        .filter_map(|s| word[search..].find(s).map(|i| search + i))
        .min()
    {
        let url = trim_url(&word[found..]);
        let (first, last) = (index_of(found), index_of(found + url.len()));
        if url.len()
            > SCHEMES
                .iter()
                .find(|s| url.starts_with(**s))
                .map_or(0, |s| s.len())
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
    let lead = word
        .find(|c: char| !matches!(c, '(' | '[' | '{' | ',' | ':' | ';'))
        .unwrap_or(word.len());
    let body = trim_url(&word[lead..]);
    let (path, line) = split_line(body);
    if !looks_like_file(path) {
        return None;
    }
    let (first, last) = (index_of(lead), index_of(lead + body.len()));
    (first..last).contains(&tapped).then(|| {
        (
            start + first..start + last,
            Link::File {
                path: path.to_owned(),
                line,
            },
        )
    })
}

/// The markdown link `[text](http…)` covering index `at` of `text`: a tap
/// on its text or its URL opens the URL. The whole link is washed.
fn markdown_in(text: &[char], at: usize) -> Option<(Range<usize>, Link)> {
    /// A link's text this long or longer is not looked back over.
    const MAX_TEXT: usize = 512;
    let mut from = 0;
    while let Some(close) =
        (from..text.len().saturating_sub(1)).find(|&i| text[i] == ']' && text[i + 1] == '(')
    {
        from = close + 1;
        let url_start = close + 2;
        let rest: String = text[url_start..text.len().min(url_start + 8)]
            .iter()
            .collect();
        if !SCHEMES
            .iter()
            .any(|scheme| rest.to_ascii_lowercase().starts_with(scheme))
        {
            continue;
        }
        // The `[` that opens the text, on this logical line: brackets inside
        // the text are balanced (`[outer [inner] label]`, `[[docs]]`).
        let floor = close.saturating_sub(MAX_TEXT);
        let mut depth = 0usize;
        let mut open = None;
        for i in (floor..close).rev() {
            match text[i] {
                ']' => depth += 1,
                '[' if depth == 0 => {
                    open = Some(i);
                    break;
                }
                '[' => depth -= 1,
                _ => {}
            }
        }
        let Some(open) = open else { continue };
        // The `)` that closes the URL, brackets inside it balanced.
        let mut depth = 0usize;
        let mut end = None;
        for (i, c) in text.iter().enumerate().skip(url_start) {
            match c {
                '(' => depth += 1,
                ')' if depth == 0 => {
                    end = Some(i);
                    break;
                }
                ')' => depth -= 1,
                c if !part_of_link(*c) => break,
                _ => {}
            }
        }
        let Some(end) = end else { continue };
        if !(open..=end).contains(&at) {
            continue;
        }
        let url: String = text[url_start..end].iter().collect();
        return Some((open..end + 1, Link::Web(url)));
    }
    None
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
    (
        path,
        numbers
            .last()
            .and_then(|line| line.parse().ok())
            .filter(|l| *l > 0),
    )
}

fn looks_like_file(path: &str) -> bool {
    if path.is_empty() || path.len() > 4096 || !path.chars().any(char::is_alphabetic) {
        return false;
    }
    if path.contains("//") || path.ends_with('/') {
        return false;
    }
    let name = path.rsplit('/').next().unwrap_or(path);
    let extension = name.rsplit_once('.').filter(|(stem, ext)| {
        !stem.is_empty()
            && !ext.is_empty()
            && ext.chars().all(|c| c.is_ascii_alphanumeric())
            && ext.chars().any(|c| c.is_ascii_alphabetic())
    });
    let rooted = path.starts_with('/')
        || path.starts_with("~/")
        || path.starts_with("./")
        || path.starts_with("../");
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
    String::from_utf8(out)
        .ok()
        .filter(|s| !s.chars().any(char::is_control))
}

/// A link found on the grid: the columns it covers on each line, and its target.
pub type GridLink = (Vec<(Line, Range<usize>)>, Link);

/// What a press on a link does when it lifts.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LinkAction {
    /// A tap: open it (the browser, or the file page).
    Open,
    /// A long press: copy its target (the URL, or the file's path).
    Copy,
}

/// The link under `pos` on a terminal grid, with the columns it covers on
/// each line, for the pressed highlight. An OSC 8 hyperlink (Claude Code
/// prints every link and every row of a wrapped URL as one) wins and is
/// washed on every neighbouring row that carries the same target; otherwise
/// the text is read: an http(s) URL, a file path or a markdown
/// `[text](url)`, joined across the rows the terminal or the program wrapped
/// it onto.
pub fn grid_link_at<U: EventListener>(grid: &Crosswords<U>, pos: Pos) -> Option<GridLink> {
    let line = pos.row;
    let columns = grid.columns();
    let top = Line(-(grid.history_size() as i32)).max(line - MAX_ROWS as i32);
    let bottom = Line(grid.screen_lines() as i32 - 1).min(line + MAX_ROWS as i32);
    if pos.col.0 >= columns || line < top || line > bottom {
        return None;
    }
    if let Some(link) = grid.cell_hyperlink(line, pos.col) {
        let uri = link.uri().to_owned();
        let target = Link::from_uri(&uri)?;
        let same = |row: Line, col: usize| {
            grid.cell_hyperlink(row, Column(col))
                .is_some_and(|other| other.uri() == uri)
        };
        // The run under the press on its own row.
        let mut start = pos.col.0;
        while start > 0 && same(line, start - 1) {
            start -= 1;
        }
        let mut end = pos.col.0 + 1;
        while end < columns && same(line, end) {
            end += 1;
        }
        let mut spans = vec![(line, start..end)];
        // The rows above and below a wrapped link: each holds one run of the
        // same target, and the run continues where the one before ended.
        let run_on = |row: Line| -> Option<Range<usize>> {
            let first = (0..columns).find(|c| same(row, *c))?;
            let last = (first..columns).take_while(|c| same(row, *c)).last()?;
            Some(first..last + 1)
        };
        let mut row = line;
        let mut reaches_start = start;
        while row > top && reaches_start <= first_text(grid, row, columns) {
            let Some(run) = run_on(row - 1) else { break };
            if run.end < last_text(grid, row - 1, columns) {
                break;
            }
            row -= 1;
            reaches_start = run.start;
            spans.insert(0, (row, run));
        }
        let mut row = line;
        let mut reaches_end = end;
        while row < bottom && reaches_end >= last_text(grid, row, columns) {
            let Some(run) = run_on(row + 1) else { break };
            if run.start > first_text(grid, row + 1, columns) {
                break;
            }
            row += 1;
            reaches_end = run.end;
            spans.push((row, run));
        }
        return Some((spans, target));
    }
    // The tapped row and the rows a wrapped link could span.
    let rows: Vec<Row> = (top.0..=bottom.0)
        .map(|row| {
            let cells = &grid.grid[Line(row)];
            Row {
                chars: (0..columns)
                    .map(|col| cells[Column(col)].c())
                    .map(|c| if c == '\0' { ' ' } else { c })
                    .collect(),
                wrapped: columns > 0 && cells[Column(columns - 1)].wrapline(),
            }
        })
        .collect();
    let found = link_at(&rows, (line.0 - top.0) as usize, pos.col.0)?;
    let spans = found
        .spans
        .into_iter()
        .map(|(row, columns)| (Line(top.0 + row as i32), columns))
        .collect();
    Some((spans, found.link))
}

/// The first column of `row` holding text (not blank, not a box border).
fn first_text<U: EventListener>(grid: &Crosswords<U>, row: Line, columns: usize) -> usize {
    let cells = &grid.grid[row];
    (0..columns)
        .find(|c| {
            let ch = cells[Column(*c)].c();
            !(ch == '\0' || ch.is_whitespace() || BORDERS.contains(&ch))
        })
        .unwrap_or(columns)
}

/// One past the last column of `row` holding text (not blank, not a border).
fn last_text<U: EventListener>(grid: &Crosswords<U>, row: Line, columns: usize) -> usize {
    let cells = &grid.grid[row];
    (0..columns)
        .rev()
        .find(|c| {
            let ch = cells[Column(*c)].c();
            !(ch == '\0' || ch.is_whitespace() || BORDERS.contains(&ch))
        })
        .map_or(0, |c| c + 1)
}

/// The http(s) URL covering column `col` of a single row, with its columns.
#[doc(hidden)]
pub fn url_at(row: &[char], col: usize) -> Option<(Range<usize>, String)> {
    let rows = [Row {
        chars: row.to_vec(),
        wrapped: false,
    }];
    match link_at(&rows, 0, col)? {
        Found {
            link: Link::Web(url),
            spans,
        } => Some((spans.first()?.1.clone(), url)),
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
        Link::File {
            path: path.into(),
            line,
        }
    }

    fn link(rows: &[Row], row: usize, col: usize) -> Option<Link> {
        link_at(rows, row, col).map(|found| found.link)
    }

    #[test]
    fn finds_the_url_under_a_column_and_drops_trailing_punctuation() {
        let row = "see https://github.com/o/r/pull/1315. done";
        assert_eq!(
            at(row, 10),
            Some((4..36, "https://github.com/o/r/pull/1315".into()))
        );
        assert_eq!(at(row, 2), None);
        assert_eq!(at(row, 37), None);
    }

    #[test]
    fn keeps_balanced_brackets_and_drops_a_closing_one() {
        assert_eq!(
            at("(https://en.wikipedia.org/wiki/A_(b))", 3).map(|(_, url)| url),
            Some("https://en.wikipedia.org/wiki/A_(b)".into())
        );
        assert_eq!(
            at("[http://x.dev/a]", 2).map(|(_, url)| url),
            Some("http://x.dev/a".into())
        );
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
        let rows = [
            Row::new("a sentence ending here", false),
            Row::new("https://x.io next", false),
        ];
        assert_eq!(link(&rows, 1, 3), Some(web("https://x.io")));
        // A URL that stops short of the edge does not take the next row's word.
        let short = [
            Row::new("https://x.io/a      ", false),
            Row::new("words", false),
        ];
        assert_eq!(link(&short, 0, 3), Some(web("https://x.io/a")));
        assert_eq!(link(&short, 1, 1), None);
    }

    #[test]
    fn file_paths_with_lines_open_as_files() {
        let rows = [Row::new(
            "error at src/links.rs:42:7 and ./run.sh, see /etc/hosts.",
            false,
        )];
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
        for text in [
            "and/or", "e.g.", "v1.2.3", "3.14", "12:30", "/", "a//b", "TODO.", "src/",
        ] {
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
        assert_eq!(
            link(&rows, 1, 4),
            Some(file("/home/wolf/Code/proj/src/main.rs", Some(10)))
        );
    }

    #[test]
    fn osc8_targets_classify_by_scheme() {
        assert_eq!(
            Link::from_uri("https://a.io/x"),
            Some(web("https://a.io/x"))
        );
        assert_eq!(Link::from_uri("HTTP://a.io"), Some(web("HTTP://a.io")));
        assert_eq!(
            Link::from_uri("file://host/home/w/a%20b.rs"),
            Some(file("/home/w/a b.rs", None))
        );
        assert_eq!(
            Link::from_uri("file:///tmp/x.rs#L9"),
            Some(file("/tmp/x.rs", Some(9)))
        );
        assert_eq!(Link::from_uri("mailto:a@b"), None);
        assert_eq!(Link::from_uri("file:///a%0a"), None);
        assert_eq!(Link::from_uri("https://a.io/\n"), None);
    }

    #[test]
    fn a_pressed_link_is_washed_where_it_is_drawn_in_a_panned_window() {
        // A link at grid columns 40..50 of row 3, with the window 30 columns in:
        // it is drawn at screen columns 10..20.
        let spans = vec![(3, 40..50)];
        assert!(pressed(&spans, &3, 10, 30));
        assert!(pressed(&spans, &3, 19, 30));
        assert!(!pressed(&spans, &3, 20, 30));
        assert!(!pressed(&spans, &3, 9, 30));
        // Screen columns are not grid columns: 40..50 on screen is past it.
        assert!(!pressed(&spans, &3, 40, 30));
        // Other rows, and the unpanned window, as before.
        assert!(!pressed(&spans, &4, 10, 30));
        assert!(pressed(&spans, &3, 45, 0));
    }
}

/// Wolf's ask (11-18): links in an agent's output open on a tap, from any
/// column of any row they cover, wrapped or not, and copy on a long press.
/// The bytes are Claude Code 2.1's own, as captured at 48 columns: live (each
/// link and every row of a wrapped URL in OSC 8) and as herdr-core repaints
/// them for a viewer that attaches later (plain text, no OSC 8).
#[cfg(test)]
mod wolf_ask_links {
    #![allow(clippy::unwrap_used)]
    use super::*;
    use crate::{Session, TerminalColor, TerminalTheme};
    use std::{
        io::Write,
        os::unix::net::UnixStream,
        sync::Arc,
        time::{Duration, Instant},
    };

    const COLUMNS: u16 = 48;
    /// A long Linear issue URL like the one Wolf tapped: three rows at 48 columns.
    const ISSUE: &str = "https://linear.app/herdr/issue/INS-3115/make-links-tappable-in-the-agent-session-view-on-the-phone";
    const DOCS: &str = "https://docs.example.com/herdr/links";

    /// A grid fed `bytes`, once the parser has drawn `until` on it.
    fn grid(bytes: &[u8], until: &str) -> (Arc<Session>, UnixStream) {
        let fg = TerminalColor {
            r: 230,
            g: 230,
            b: 230,
        };
        let theme = TerminalTheme::new(
            TerminalColor {
                r: 20,
                g: 20,
                b: 20,
            },
            fg,
            [fg; 16],
        );
        let (local, mut remote) = UnixStream::pair().unwrap();
        local
            .set_read_timeout(Some(Duration::from_millis(20)))
            .unwrap();
        let session = Session::start(
            local.try_clone().unwrap(),
            local,
            |_, _| {},
            theme,
            COLUMNS,
            12,
        )
        .unwrap();
        remote.write_all(bytes).unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        while !session.screen_text().contains(until) {
            assert!(
                Instant::now() < deadline,
                "the bytes never reached the grid"
            );
            std::thread::sleep(Duration::from_millis(5));
        }
        (session, remote)
    }

    /// The URL's rows as Claude Code breaks it: the rest of the width, after
    /// its two-column indent.
    fn chunks(url: &str) -> Vec<String> {
        let chars: Vec<char> = url.chars().collect();
        chars
            .chunks(usize::from(COLUMNS) - 2)
            .map(|chunk| chunk.iter().collect())
            .collect()
    }

    /// Claude Code's own output for "Read [the docs](DOCS) now. Also see ISSUE",
    /// live: the markdown link's text in OSC 8, the URL broken at the edge with
    /// every row in OSC 8 of the same id (bytes as captured, URLs swapped).
    fn live() -> Vec<u8> {
        let mut out = format!(
            "\x1b[H\x1b[2J\x1b[2;1H\x1b[38;2;255;255;255m\u{25cf}\x1b[3G\x1b[39mRead\x1b[8G\x1b]8;id=sg4g0w;{DOCS}\x07\x1b[94mthe docs\x1b[17G\x1b]8;;\x07\x1b[39mnow.\x1b[22GAlso\x1b[27Gsee"
        );
        for chunk in chunks(ISSUE) {
            out += &format!(
                "\r\x1b[2C\x1b[1B\x1b]8;id=1ckm1tu;{ISSUE}\x07\x1b[94m{chunk}\x1b]8;;\x07"
            );
        }
        out += "\r\n\r\n\x1b[39m\u{273b} Baked for 5s";
        out.into_bytes()
    }

    /// The same screen as herdr-core repaints it for a viewer that attaches
    /// after it was printed: text and colours only.
    fn repainted() -> Vec<u8> {
        let mut out = String::from(
            "\x18\x1b[!p\x1b[H\x1b[2J\x1b[1;12r\r\n\x1b[0m\x1b[38;2;255;255;255m\u{25cf} \x1b[0mRead \x1b[0m\x1b[38;5;12mthe docs \x1b[0mnow. Also see\r\n",
        );
        for chunk in chunks(ISSUE) {
            out += &format!("  \x1b[0m\x1b[38;5;12m{chunk}\x1b[0m\r\n");
        }
        out += "\r\n\x1b[0m\u{273b} Baked for 5s";
        out.into_bytes()
    }

    fn at(session: &Session, row: i32, col: usize) -> Option<GridLink> {
        grid_link_at(&session.grid().lock(), Pos::new(Line(row), Column(col)))
    }

    /// Every column the URL is drawn in, on its rows 2.. (below the line
    /// that introduces it), opens the whole URL and washes all its rows.
    fn assert_issue_opens_everywhere(session: &Session) {
        let rows = chunks(ISSUE);
        let washed: Vec<(Line, Range<usize>)> = rows
            .iter()
            .enumerate()
            .map(|(i, chunk)| (Line(2 + i as i32), 2..2 + chunk.chars().count()))
            .collect();
        assert!(rows.len() >= 3, "the URL wraps onto continuation rows");
        for (i, chunk) in rows.iter().enumerate() {
            let row = 2 + i as i32;
            for col in 2..2 + chunk.chars().count() {
                let (spans, link) = at(session, row, col)
                    .unwrap_or_else(|| panic!("no link at row {row} column {col}"));
                assert_eq!(link, Link::Web(ISSUE.into()), "row {row} column {col}");
                assert_eq!(spans, washed, "row {row} column {col}");
            }
            // The indent is not the link.
            assert_eq!(at(session, row, 0), None);
            assert_eq!(at(session, row, 1), None);
        }
    }

    #[test]
    fn wolf_ask_links_a_long_wrapped_url_opens_from_its_first_and_continuation_rows_live() {
        let (session, _remote) = grid(&live(), "Baked");
        assert_issue_opens_everywhere(&session);
    }

    #[test]
    fn wolf_ask_links_a_long_wrapped_url_opens_from_its_first_and_continuation_rows_repainted() {
        let (session, _remote) = grid(&repainted(), "Baked");
        assert_issue_opens_everywhere(&session);
    }

    #[test]
    fn wolf_ask_links_a_markdown_link_opens_from_any_column_of_its_text() {
        let (session, _remote) = grid(&live(), "Baked");
        // "the docs" is drawn at columns 7..15 of row 1.
        for col in 7..15 {
            let (spans, link) = at(&session, 1, col).unwrap();
            assert_eq!(link, Link::Web(DOCS.into()));
            assert_eq!(spans, vec![(Line(1), 7..15)]);
        }
        // Prose beside it is not a link.
        assert_eq!(at(&session, 1, 2), None);
        assert_eq!(at(&session, 1, 16), None);
    }

    /// `text` padded with spaces to the 48 columns of the captured grid.
    fn row48(text: &str) -> Row {
        Row::new(&format!("{text:<48}"), false)
    }

    #[test]
    fn wolf_ask_links_a_url_whose_own_brackets_end_a_row_goes_on_below() {
        // Review of #39: a row-final `)` the URL opened itself is not its end
        // (phone-feedback read this right; the first cut of #39 dropped /tail).
        let first = format!("  https://x.dev/{}_(b)", "a".repeat(28));
        assert_eq!(first.chars().count(), 48);
        let rows = [row48(&first), row48("  /tail next")];
        let url = Link::Web(format!("https://x.dev/{}_(b)/tail", "a".repeat(28)));
        for (row, col) in [(0, 2), (0, 30), (0, 47), (1, 2), (1, 6)] {
            assert_eq!(
                link_at(&rows, row, col).map(|f| f.link),
                Some(url.clone()),
                "row {row} column {col}"
            );
        }
        assert_eq!(link_at(&rows, 1, 9), None, "the prose after it");
    }

    #[test]
    fn wolf_ask_links_a_markdown_link_split_at_its_brackets_opens_from_both_rows() {
        let url = Link::Web("https://x.dev/a_(b)".into());
        // `[the docs]` ends a row, `(https://…)` starts the next.
        let label = format!("{}see [the docs]", " ".repeat(34));
        let rows = [row48(&label), row48("  (https://x.dev/a_(b)) next")];
        for (row, col) in [(0, 38), (0, 39), (0, 43), (0, 47), (1, 2), (1, 10), (1, 22)] {
            assert_eq!(
                link_at(&rows, row, col).map(|f| f.link),
                Some(url.clone()),
                "row {row} column {col}"
            );
        }
        assert_eq!(link_at(&rows, 0, 35), None, "the prose before it");
        assert_eq!(link_at(&rows, 1, 25), None, "the prose after it");
        // `[the docs](` ends a row, the URL starts the next.
        let label = format!("{}see [the docs](", " ".repeat(33));
        let rows = [row48(&label), row48("  https://x.dev/a_(b)) next")];
        for (row, col) in [(0, 38), (0, 47), (1, 2), (1, 21)] {
            assert_eq!(
                link_at(&rows, row, col).map(|f| f.link),
                Some(url.clone()),
                "row {row} column {col}"
            );
        }
        assert_eq!(link_at(&rows, 1, 24), None);
    }

    #[test]
    fn wolf_ask_links_a_markdown_label_with_its_own_brackets_opens_from_all_of_it() {
        // Review of #39 (round 2): the nearest `[` is not the label's opener.
        let url = Some(Link::Web("https://x.dev/y".into()));
        let one = [Row::new(
            "see [outer [inner] label](https://x.dev/y) and [[docs]](https://x.dev/y) end",
            false,
        )];
        for col in [4, 5, 11, 17, 20, 24, 30, 41, 47, 48, 49, 52, 54, 55, 60, 71] {
            assert_eq!(link_at(&one, 0, col).map(|f| f.link), url, "column {col}");
        }
        assert_eq!(link_at(&one, 0, 1), None);
        assert_eq!(link_at(&one, 0, 44), None, "the prose between them");
        assert_eq!(link_at(&one, 0, 74), None);
        // Split at `](`, both ways.
        let label = format!("{}see [outer [inner] label]", " ".repeat(23));
        let rows = [row48(&label), row48("  (https://x.dev/y) next")];
        for (row, col) in [(0, 27), (0, 34), (0, 41), (0, 47), (1, 2), (1, 18)] {
            assert_eq!(
                link_at(&rows, row, col).map(|f| f.link),
                url,
                "row {row} column {col}"
            );
        }
        assert_eq!(link_at(&rows, 1, 21), None);
        let label = format!("{}see [[docs]](", " ".repeat(35));
        let rows = [row48(&label), row48("  https://x.dev/y) next")];
        for (row, col) in [(0, 39), (0, 40), (0, 44), (0, 47), (1, 2), (1, 17)] {
            assert_eq!(
                link_at(&rows, row, col).map(|f| f.link),
                url,
                "row {row} column {col}"
            );
        }
        assert_eq!(link_at(&rows, 1, 20), None);
    }

    #[test]
    fn wolf_ask_links_a_raw_markdown_link_opens_from_its_text_and_its_url_even_split_in_its_scheme()
    {
        // As Claude Code echoes a prompt holding a markdown link: broken at
        // the edge inside "https", the rest indented.
        let rows = [
            Row::new("  not use tools: Read [the INS-3115 issue](http", false),
            Row::new("  s://linear.app/acme/issue/INS-3115/make-markd", false),
            Row::new("  own-links-tappable-in-the-agent-session-view)", false),
            Row::new("  now. Also see", false),
        ];
        let url = Link::Web(
            "https://linear.app/acme/issue/INS-3115/make-markdown-links-tappable-in-the-agent-session-view"
                .into(),
        );
        // The text ("the INS-3115 issue"), the brackets and every URL row.
        for (row, col) in [
            (0, 22),
            (0, 23),
            (0, 30),
            (0, 41),
            (0, 44),
            (1, 2),
            (1, 30),
            (2, 2),
            (2, 46),
        ] {
            assert_eq!(
                link_at(&rows, row, col).map(|f| f.link),
                Some(url.clone()),
                "row {row} column {col}"
            );
        }
        // Prose before it, and the row after its closing bracket, are not.
        assert_eq!(link_at(&rows, 0, 5), None);
        assert_eq!(link_at(&rows, 3, 3), None);
        // On one row: the text opens the URL too.
        let one = [Row::new("See [the docs](https://x.dev/a_(b)) now.", false)];
        for col in [4, 6, 12, 20] {
            assert_eq!(
                link_at(&one, 0, col).map(|f| f.link),
                Some(Link::Web("https://x.dev/a_(b)".into()))
            );
        }
        assert_eq!(link_at(&one, 0, 37), None);
    }
}
