//! Plain-text URLs on a terminal row, for tap-to-open.
use std::ops::Range;

const SCHEMES: [&str; 2] = ["https://", "http://"];

/// The http(s) URL covering column `col` of a row, with the columns it spans.
/// `row` holds one char per column. Trailing sentence punctuation and an
/// unbalanced closing bracket are not part of the URL.
#[doc(hidden)]
pub fn url_at(row: &[char], col: usize) -> Option<(Range<usize>, String)> {
    if col >= row.len() {
        return None;
    }
    let text: String = row.iter().collect();
    // Byte offset of each column, to map matches back to columns.
    let mut columns = Vec::with_capacity(row.len() + 1);
    let mut offset = 0;
    for c in row {
        columns.push(offset);
        offset += c.len_utf8();
    }
    columns.push(offset);
    let column_of = |byte: usize| columns.iter().position(|&b| b == byte).unwrap_or(row.len());
    let mut search = 0;
    while search < text.len() {
        let start = SCHEMES
            .iter()
            .filter_map(|scheme| text[search..].find(scheme).map(|at| search + at))
            .min()?;
        let mut end = start;
        for (at, c) in text[start..].char_indices() {
            if c.is_whitespace() || matches!(c, '"' | '\'' | '<' | '>' | '`' | '|' | '\u{2502}') || c.is_control() {
                break;
            }
            end = start + at + c.len_utf8();
        }
        let mut url = &text[start..end];
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
                break;
            }
            url = trimmed;
        }
        let (first, last) = (column_of(start), column_of(start + url.len()));
        let scheme_only = SCHEMES.iter().any(|scheme| url.len() <= scheme.len());
        if !scheme_only && (first..last).contains(&col) {
            return Some((first..last, url.to_owned()));
        }
        search = end.max(start + 1);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::url_at;

    fn at(text: &str, col: usize) -> Option<(std::ops::Range<usize>, String)> {
        url_at(&text.chars().collect::<Vec<_>>(), col)
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
}
