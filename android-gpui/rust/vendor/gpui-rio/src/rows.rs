//! Where each grid row sits vertically when a multi-line draft is shown.
//!
//! The draft's rows are grid rows painted at the font's own line height, which leaves
//! tall glyphs touching the next line. Rather than add leading to every row (it would
//! take rows from a small screen and move all of Claude's output), only the rows the
//! draft occupies are spread: each extra line adds a fixed `leading`, up to
//! [`MAX_GROWN_LINES`] lines, and past that the same total is shared out so the box
//! stops growing and the draft scrolls as before. The growth is taken from the rows
//! above, so the rows below the draft (Claude's status line and the host's bottom
//! bar) never move.

/// The leading added per extra draft line, as a fraction of the cell height.
pub const LEADING_RATIO: f32 = 0.25;
/// Extra lines that each add a full leading; the box stops growing after these.
pub const MAX_GROWN_LINES: usize = 4;

/// The leading one extra draft line adds, in whole pixels so rows stay crisp.
pub fn leading(cell_height: f32) -> f32 {
    (cell_height * LEADING_RATIO).round().max(1.)
}

/// Row tops for a pane: uniform, or with a spread block of draft rows.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RowGeometry {
    pub cell_height: f32,
    /// First row of the spread block (meaningless when `rows` is 0).
    pub first: usize,
    /// Rows in the block; 0 when nothing is spread.
    pub rows: usize,
    /// Extra space under each block row but the last.
    pub gap: f32,
}

impl RowGeometry {
    /// Every row `cell_height` apart.
    pub fn uniform(cell_height: f32) -> Self {
        Self { cell_height, first: 0, rows: 0, gap: 0. }
    }

    /// The geometry for a draft drawn on rows `first..=last` (`None`: no draft shown).
    pub fn for_draft(cell_height: f32, block: Option<(usize, usize)>) -> Self {
        let Some((first, last)) = block else {
            return Self::uniform(cell_height);
        };
        let rows = last.saturating_sub(first) + 1;
        if rows < 2 || !cell_height.is_finite() || cell_height <= 0. {
            return Self::uniform(cell_height);
        }
        let grown = (rows - 1).min(MAX_GROWN_LINES) as f32 * leading(cell_height);
        Self { cell_height, first, rows, gap: grown / (rows - 1) as f32 }
    }

    /// How much taller the block is than its rows alone.
    pub fn growth(&self) -> f32 {
        if self.rows < 2 { 0. } else { self.gap * (self.rows - 1) as f32 }
    }

    /// Top of `row`, from the pane's top. Rows above the block move up by its growth.
    pub fn top(&self, row: usize) -> f32 {
        let plain = row as f32 * self.cell_height;
        if self.rows < 2 || row < self.first {
            plain - self.growth()
        } else if row < self.first + self.rows {
            plain - self.growth() + (row - self.first) as f32 * self.gap
        } else {
            plain
        }
    }

    /// The row at `y` from the pane's top; a gap belongs to the row above it. May be
    /// negative or past the last row.
    pub fn row_at(&self, y: f32) -> i32 {
        let h = self.cell_height;
        if self.rows < 2 {
            return (y / h).floor() as i32;
        }
        let block_top = self.top(self.first);
        let block_end = self.top(self.first + self.rows - 1) + h;
        if y < block_top {
            ((y + self.growth()) / h).floor() as i32
        } else if y < block_end {
            let i = ((y - block_top) / (h + self.gap)).floor() as i32;
            self.first as i32 + i.min(self.rows as i32 - 1)
        } else {
            (y / h).floor() as i32
        }
    }

    /// Whether `row` is drawn inside the pane (its top not above the pane's).
    pub fn visible(&self, row: usize) -> bool {
        self.top(row) >= 0.
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const H: f32 = 40.;

    fn block(first: usize, last: usize) -> RowGeometry {
        RowGeometry::for_draft(H, Some((first, last)))
    }

    #[test]
    fn one_line_or_no_draft_is_uniform() {
        for g in [RowGeometry::for_draft(H, None), block(20, 20)] {
            assert_eq!(g.growth(), 0.);
            assert_eq!(g.top(7), 7. * H);
            assert_eq!(g.row_at(7. * H + 1.), 7);
        }
    }

    #[test]
    fn each_extra_line_adds_one_leading() {
        let lead = leading(H);
        assert_eq!(lead, 10.);
        for lines in 1..=5usize {
            let g = block(20, 20 + lines - 1);
            assert_eq!(g.growth(), (lines - 1) as f32 * lead, "{lines} lines");
        }
    }

    #[test]
    fn lines_are_spaced_by_the_leading_not_touching() {
        let g = block(20, 22);
        assert_eq!(g.top(21) - g.top(20), H + leading(H));
        assert_eq!(g.top(22) - g.top(21), H + leading(H));
    }

    #[test]
    fn growth_stops_at_the_cap_and_lines_close_up() {
        let capped = block(20, 20 + MAX_GROWN_LINES);
        let more = block(20, 20 + MAX_GROWN_LINES + 6);
        assert_eq!(capped.growth(), MAX_GROWN_LINES as f32 * leading(H));
        assert_eq!(more.growth(), capped.growth());
        assert!(more.gap < capped.gap && more.gap > 0.);
    }

    #[test]
    fn the_rows_below_the_draft_and_its_last_row_never_move() {
        for lines in 1..=9usize {
            let g = block(20, 20 + lines - 1);
            assert!((g.top(20 + lines - 1) - (20 + lines - 1) as f32 * H).abs() < 1e-3);
            assert_eq!(g.top(30), 30. * H);
        }
    }

    #[test]
    fn rows_above_move_up_together_by_the_growth() {
        let g = block(20, 23);
        for row in [0, 5, 19] {
            assert_eq!(g.top(row), row as f32 * H - g.growth());
        }
    }

    #[test]
    fn row_at_inverts_top_including_gaps() {
        let g = block(20, 24);
        for row in 0..40usize {
            if !g.visible(row) && row < 20 {
                continue;
            }
            assert_eq!(g.row_at(g.top(row) + 1.), row as i32, "top of {row}");
            assert_eq!(g.row_at(g.top(row) + H - 1.), row as i32, "bottom of {row}");
        }
        // The gap under a draft line belongs to that line.
        assert_eq!(g.row_at(g.top(21) - 1.), 20);
    }

    #[test]
    fn a_first_row_pushed_above_the_pane_is_not_visible() {
        let g = block(1, 5);
        assert!(!g.visible(0));
        assert!(g.visible(5));
    }
}
