//! Where grid rows and a multi-line draft's lines sit vertically.
//!
//! The draft is painted over Claude's prompt box as ghost text, one line per grid row,
//! at the font's own line height. Each extra line now adds a fixed `leading`, up to
//! [`MAX_GROWN_LINES`] lines (then the same total is shared out and the draft scrolls
//! as before). The room comes first from the blank rows below the draft inside the box;
//! only the rest scrolls Claude's output up, by whole rows, so no row is ever half hidden
//! and the box grows upward. Every grid row below the prompt row (the box's lower rule
//! with the host's controls, Claude's status line) never moves, whatever the draft's
//! length.

use crate::ghost::{self, Anchor, Layout};

/// The leading added per extra draft line, as a fraction of the cell height.
pub const LEADING_RATIO: f32 = 0.25;
/// Extra lines that each add a full leading; the box stops growing after these.
pub const MAX_GROWN_LINES: usize = 4;

/// The leading one extra draft line adds, in whole pixels so lines stay crisp.
pub fn leading(cell_height: f32) -> f32 {
    (cell_height * LEADING_RATIO).round().max(1.)
}

/// Row tops for a pane: uniform, or with a spread multi-line draft.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RowGeometry {
    pub cell_height: f32,
    /// The draft's first grid row (meaningless when `rows` is 0).
    pub first: usize,
    /// Lines in the draft; 0 when nothing is spread.
    pub rows: usize,
    /// Extra space under each draft line but the last.
    pub gap: f32,
    /// How far the grid rows up to and including `first` are scrolled up: whole rows.
    pub shift: f32,
}

impl RowGeometry {
    /// Every row `cell_height` apart.
    pub fn uniform(cell_height: f32) -> Self {
        Self { cell_height, first: 0, rows: 0, gap: 0., shift: 0. }
    }

    /// The geometry for a draft drawn on grid rows `first..=last` of a prompt box whose
    /// free rows end before row `limit` (the first row of the program's own text below).
    pub fn for_draft(cell_height: f32, block: Option<(usize, usize)>, limit: usize) -> Self {
        let Some((first, last)) = block else {
            return Self::uniform(cell_height);
        };
        let rows = last.saturating_sub(first) + 1;
        if rows < 2 || !cell_height.is_finite() || cell_height <= 0. {
            return Self::uniform(cell_height);
        }
        let grown = (rows - 1).min(MAX_GROWN_LINES) as f32 * leading(cell_height);
        let room = limit.saturating_sub(first).saturating_sub(rows) as f32 * cell_height;
        let shift = ((grown - room).max(0.) / cell_height).ceil() * cell_height;
        Self { cell_height, first, rows, gap: grown / (rows - 1) as f32, shift }
    }

    pub fn for_layout(
        cell_height: f32,
        layout: &Layout,
        anchor: Option<Anchor>,
        screen: &[Vec<char>],
    ) -> Self {
        let block = ghost::draft_block(&layout.placed);
        match (anchor, block) {
            (Some(anchor), Some((first, _)))
                if layout.spread_safe
                    && layout.controls.iter().all(|control| control.row > first) =>
            {
                Self::for_draft(cell_height, block, ghost::draft_limit(screen, anchor))
            }
            _ => Self::uniform(cell_height),
        }
    }

    fn spread(&self) -> bool {
        self.rows >= 2
    }

    /// Top of grid row `row`, from the pane's top: rows up to the draft's first row are
    /// scrolled up by `shift`, every row below stays where it always was.
    pub fn grid_top(&self, row: usize) -> f32 {
        let plain = row as f32 * self.cell_height;
        if self.spread() && row <= self.first { plain - self.shift } else { plain }
    }

    /// Top of the draft line drawn on grid row `row` (within the draft's rows).
    pub fn draft_top(&self, row: usize) -> f32 {
        if !self.spread() || row < self.first {
            return self.grid_top(row);
        }
        self.grid_top(self.first) + (row - self.first) as f32 * (self.cell_height + self.gap)
    }

    /// Whether grid row `row` is drawn inside the pane. Scrolled-off rows are whole rows.
    pub fn visible(&self, row: usize) -> bool {
        self.grid_top(row) >= 0.
    }

    /// The grid row at `y` from the pane's top, or None above the pane. The band between
    /// the (scrolled) first draft row and the next belongs to the first one; a scrolled-off
    /// row is never returned.
    pub fn grid_row_at(&self, y: f32) -> Option<usize> {
        if y.is_nan() || y < 0. {
            return None;
        }
        let h = self.cell_height;
        if !self.spread() {
            return Some((y / h).floor() as usize);
        }
        let edge = (self.first + 1) as f32 * h;
        if y < edge - self.shift {
            Some(((y + self.shift) / h).floor() as usize)
        } else if y < edge {
            Some(self.first)
        } else {
            Some((y / h).floor() as usize)
        }
    }

    /// The draft line's grid row at `y` (a line includes the gap under it), if `y` is on one.
    pub fn draft_row_at(&self, y: f32) -> Option<usize> {
        if !self.spread() {
            return self.grid_row_at(y);
        }
        let top = self.draft_top(self.first);
        if y.is_nan() || y < top {
            return None;
        }
        let i = ((y - top) / (self.cell_height + self.gap)).floor() as usize;
        (i < self.rows).then_some(self.first + i)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const H: f32 = 40.;

    /// A draft of `lines` lines starting at row 20 in a box with `free` free rows below
    /// its first row (the controls' rule is the first of them).
    fn draft(lines: usize, free: usize) -> RowGeometry {
        RowGeometry::for_draft(H, Some((20, 20 + lines - 1)), 21 + free)
    }

    #[test]
    fn one_line_or_no_draft_is_uniform() {
        for g in [RowGeometry::for_draft(H, None, 0), draft(1, 1)] {
            assert_eq!(g.shift, 0.);
            assert_eq!(g.grid_top(7), 7. * H);
            assert_eq!(g.grid_row_at(7. * H + 1.), Some(7));
        }
    }

    #[test]
    fn each_extra_line_adds_one_leading_up_to_the_cap() {
        let lead = leading(H);
        assert_eq!(lead, 10.);
        for lines in 2..=5usize {
            let g = draft(lines, 8);
            assert_eq!(g.draft_top(20 + 1) - g.draft_top(20), H + lead, "{lines} lines");
            assert_eq!(g.gap * (lines - 1) as f32, (lines - 1) as f32 * lead);
        }
        let capped = draft(5, 12);
        let more = draft(11, 20);
        assert_eq!(capped.gap * 4., more.gap * 10.);
        assert!(more.gap < capped.gap && more.gap > 0.);
    }

    #[test]
    fn the_rows_below_the_prompt_row_never_move_for_any_length() {
        for lines in 1..=9usize {
            for free in [1, 2, 4, 8] {
                let g = draft(lines, free);
                for row in 21..45 {
                    assert_eq!(g.grid_top(row), row as f32 * H, "{lines} lines, {free} free, row {row}");
                }
            }
        }
    }

    #[test]
    fn blank_rows_below_take_the_growth_before_anything_scrolls() {
        // Four lines need 30 px; two free rows below the draft's rows have 80.
        let g = draft(4, 6);
        assert_eq!(g.shift, 0.);
        assert_eq!(g.grid_top(20), 20. * H);
        assert_eq!(g.grid_top(3), 3. * H);
    }

    #[test]
    fn without_room_the_output_scrolls_by_whole_rows_only() {
        for lines in 2..=9usize {
            let g = draft(lines, lines - 1);
            assert!(g.shift > 0., "{lines} lines");
            assert_eq!(g.shift % H, 0.);
            for row in 0..=20usize {
                assert_eq!(g.grid_top(row), row as f32 * H - g.shift);
                // A row is wholly on screen or wholly scrolled off.
                assert!(g.grid_top(row) >= 0. || g.grid_top(row) + H <= 0., "row {row}");
            }
        }
    }

    #[test]
    fn the_draft_always_ends_inside_the_boxs_free_rows() {
        for lines in 2..=9usize {
            for free in 1..=9usize {
                let g = RowGeometry::for_draft(H, Some((20, 20 + lines - 1)), 20 + free.max(lines));
                let end = g.draft_top(20 + lines - 1) + H;
                assert!(end <= (20 + free.max(lines)) as f32 * H + 1e-3, "{lines} lines, {free} free");
            }
        }
    }

    #[test]
    fn a_tap_reaches_the_row_that_was_painted_there() {
        // The review's probe: 40 px cells, five draft lines, no room: output scrolled a row.
        let g = draft(5, 4);
        assert_eq!(g.shift, H);
        assert_eq!(g.grid_row_at(g.grid_top(18) + 5.), Some(18));
        for row in 1..45usize {
            if g.visible(row) {
                assert_eq!(g.grid_row_at(g.grid_top(row) + 1.), Some(row), "top of {row}");
                assert_eq!(g.grid_row_at(g.grid_top(row) + H - 1.), Some(row), "bottom of {row}");
            }
        }
    }

    #[test]
    fn a_scrolled_off_row_is_never_returned() {
        let g = draft(5, 4);
        // Row 0 is above the pane; the pane's top is row 1.
        assert!(!g.visible(0));
        assert_eq!(g.grid_row_at(0.), Some(1));
        assert_eq!(g.grid_row_at(-1.), None);
        assert_eq!(g.grid_row_at(f32::NAN), None);
    }

    #[test]
    fn draft_lines_are_found_by_their_own_tops_gaps_included() {
        let g = draft(3, 8);
        for k in 0..3usize {
            let top = g.draft_top(20 + k);
            assert_eq!(g.draft_row_at(top + 1.), Some(20 + k));
            assert_eq!(g.draft_row_at(top + H + g.gap - 1.), Some(20 + k));
        }
        assert_eq!(g.draft_row_at(g.draft_top(20) - 1.), None);
        assert_eq!(g.draft_row_at(g.draft_top(22) + H + g.gap + 1.), None);
    }
}
