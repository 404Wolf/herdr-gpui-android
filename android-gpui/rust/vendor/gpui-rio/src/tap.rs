//! What a press on the terminal does when it lifts. A press is a mouse click
//! or a finger's tap. GPUI turns a tap into a synthesized press and release
//! at the moment the finger lifts, and turns a drag into scrolling, so no
//! press is ever seen for it. A finger held in place is still a tap to GPUI
//! when no element claims the long press. [`Touch`] remembers that from the
//! gesture events GPUI reports, so a held finger is never mistaken for a
//! click. Every lifted press is reported to the host ([`Report`]), so the
//! host learns what a tap did from the one place that decided it.

/// What lifting a press does.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Lift {
    /// Open the link under it.
    OpenLink,
    /// Offer the program a click on the cell under it. The session sends one
    /// only when the program asked for mouse events.
    Click,
    /// Nothing: it travelled (a selection, a scroll), or the finger was held.
    Nothing,
}

/// One press, as it stands when it lifts.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Press {
    /// It went down on a link.
    pub on_link: bool,
    /// It travelled past the slop, becoming a selection or a scroll.
    pub travelled: bool,
    /// A finger held long enough for a long press.
    pub held: bool,
}

/// What lifting `press` does. A link keeps opening as it always has, held or
/// not. Otherwise a press that stayed in place and was not held is a click.
pub fn lift(press: Press) -> Lift {
    if press.travelled {
        Lift::Nothing
    } else if press.on_link {
        Lift::OpenLink
    } else if press.held {
        Lift::Nothing
    } else {
        Lift::Click
    }
}

/// What a tap did, as the host is told once it was done.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tapped {
    /// The program was sent a click: the host opens nothing for it.
    Clicked,
    /// A tap the program did not take (no mouse reporting, a link, outside
    /// the grid): the host does what it does for a tap.
    Plain,
    /// Not a tap: the press travelled, or the finger was held.
    Ignored,
}

/// One lifted press, reported to the host.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Report {
    /// Unique in the process, increasing: a host can tell reports apart.
    pub id: u64,
    /// What the host's stamp said when the press began (see
    /// `set_tap_listener`), so it can drop a report for a view it left.
    pub stamp: u64,
    pub tapped: Tapped,
}

/// The next report id.
pub fn next_id() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(1);
    NEXT.fetch_add(1, Ordering::Relaxed)
}

/// The screen cell (column, row) under the point `(x, y)` in the pane, in
/// the units of `cell` (its width and height), counted as the program
/// counts them from 0. `origin` is the grid cell drawn at the pane's
/// top-left, as painting uses it: a negative row when a grid shorter than
/// the pane is drawn against its bottom, a positive one when a larger grid's
/// window follows the caret. `None` above, left of or past the grid (the
/// empty rows over a bottom-anchored grid, the letterbox beside a narrow one).
pub fn cell_at(
    (x, y): (f32, f32),
    (width, height): (f32, f32),
    (row_off, col_off): (i32, usize),
    (columns, rows): (usize, usize),
) -> Option<(usize, usize)> {
    if !(x >= 0. && y >= 0. && width > 0. && height > 0.) {
        return None;
    }
    let row = (y / height).floor() as i64 + i64::from(row_off);
    let col = (x / width).floor() as i64 + i64::try_from(col_off).ok()?;
    let (row, col) = (usize::try_from(row).ok()?, usize::try_from(col).ok()?);
    (row < rows && col < columns).then_some((col, row))
}

/// What a host view with no program to click reports for a lifted press,
/// from its own [`Touch`]: a held finger is no tap, any other is a tap no
/// program took. A press whose touch began on another view (the view was
/// replaced mid-tap) carries no stamp of this one: `u64::MAX`, which a host
/// never hands out, so the report is dropped and nothing opens twice.
pub fn plain_view_tap(lifted: Option<(bool, u64)>) -> (Tapped, u64) {
    match lifted {
        Some((true, stamp)) => (Tapped::Ignored, stamp),
        Some((false, stamp)) => (Tapped::Plain, stamp),
        None => (Tapped::Plain, u64::MAX),
    }
}

/// The stamps a host sends along with each touch-down, matched to the
/// touches GPUI starts from them. A host's own state (which view it shows)
/// changes at once, while the touch waits in GPUI's queue. So the stamp
/// travels with the down, queued in the same order, and is taken back when
/// GPUI starts that touch: by position, since GPUI may start no touch for a
/// down (one that catches a fling), and those downs are passed over.
#[derive(Debug)]
pub struct DownStamps {
    /// (x, y) in the host's physical pixels, and the stamp, oldest first.
    queue: std::collections::VecDeque<(f32, f32, u64)>,
}

impl Default for DownStamps {
    fn default() -> Self {
        Self::new()
    }
}

impl DownStamps {
    /// Downs kept waiting at most; the oldest is dropped past it.
    pub const CAPACITY: usize = 64;

    pub const fn new() -> Self {
        Self { queue: std::collections::VecDeque::new() }
    }

    /// A down at physical pixel (`x`, `y`) went into GPUI's queue with `stamp`.
    pub fn queued(&mut self, x: f32, y: f32, stamp: u64) {
        if self.queue.len() == Self::CAPACITY {
            self.queue.pop_front();
        }
        self.queue.push_back((x, y, stamp));
    }

    /// GPUI started a touch at logical (`x`, `y`) under `scale`: the stamp
    /// its down was queued with, or `None` when none matches. Older downs
    /// GPUI started no touch for are dropped; later ones stay queued.
    pub fn started(&mut self, (x, y): (f32, f32), scale: f32) -> Option<u64> {
        let scale = if scale > 0. { scale } else { 1. };
        let at = self.queue.iter().position(|&(qx, qy, _)| {
            (qx / scale - x).abs() < 0.5 && (qy / scale - y).abs() < 0.5
        })?;
        let (_, _, stamp) = self.queue.drain(..=at).last()?;
        Some(stamp)
    }
}

/// The finger on the terminal, from GPUI's gesture events: every touch is
/// offered as a touch drag when it starts, and as a long press once held. A
/// mouse has neither, so a mouse press is never held.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Touch {
    /// While a finger is down: whether it has been held, and the host's
    /// stamp when it went down.
    down: Option<(bool, u64)>,
}

impl Touch {
    /// A finger went down, with the host's stamp then. Whatever an earlier
    /// touch left is forgotten.
    pub fn began(&mut self, stamp: u64) {
        self.down = Some((false, stamp));
    }

    /// The finger was held in place for a long press.
    pub fn held(&mut self) {
        if let Some((held, _)) = &mut self.down {
            *held = true;
        }
    }

    /// At the press GPUI synthesizes when the finger lifts: whether that
    /// finger was held, and its stamp. `None` for a mouse press (no touch
    /// began). The touch is over either way.
    pub fn lifted(&mut self) -> Option<(bool, u64)> {
        self.down.take()
    }
}

#[cfg(test)]
mod tests {
    use super::{DownStamps, Lift, Press, Tapped, Touch, cell_at, lift, next_id, plain_view_tap};

    #[test]
    fn a_press_that_stays_in_place_is_a_click() {
        assert_eq!(lift(Press::default()), Lift::Click);
    }

    #[test]
    fn a_link_opens_as_before_held_or_not() {
        for held in [false, true] {
            let press = Press {
                on_link: true,
                held,
                ..Press::default()
            };
            assert_eq!(lift(press), Lift::OpenLink);
        }
    }

    #[test]
    fn a_press_that_travelled_or_was_held_does_nothing() {
        for press in [
            Press {
                travelled: true,
                ..Press::default()
            },
            Press {
                travelled: true,
                on_link: true,
                ..Press::default()
            },
            Press {
                held: true,
                ..Press::default()
            },
        ] {
            assert_eq!(lift(press), Lift::Nothing, "{press:?}");
        }
    }

    #[test]
    fn a_held_finger_is_remembered_until_it_lifts() {
        let mut touch = Touch::default();
        touch.began(7);
        touch.held();
        assert_eq!(touch.lifted(), Some((true, 7)));
        // The next tap starts afresh, with its own stamp.
        touch.began(8);
        assert_eq!(touch.lifted(), Some((false, 8)));
    }

    #[test]
    fn a_new_touch_forgets_one_that_never_lifted_as_a_tap() {
        // Held, then dragged into a scroll: no press ever comes for it.
        let mut touch = Touch::default();
        touch.began(1);
        touch.held();
        touch.began(2);
        assert_eq!(touch.lifted(), Some((false, 2)));
    }

    #[test]
    fn a_mouse_press_is_never_held() {
        let mut touch = Touch::default();
        assert_eq!(touch.lifted(), None);
        // A long press reported with no touch begun changes nothing.
        touch.held();
        assert_eq!(touch.lifted(), None);
    }

    #[test]
    fn report_ids_only_increase() {
        let (a, b) = (next_id(), next_id());
        assert!(b > a);
    }

    // Cells of 10 x 20 logical pixels on an 80 x 24 grid.
    const CELL: (f32, f32) = (10., 20.);
    const GRID: (usize, usize) = (80, 24);

    #[test]
    fn a_point_maps_to_the_cell_painted_under_it() {
        assert_eq!(cell_at((0., 0.), CELL, (0, 0), GRID), Some((0, 0)));
        assert_eq!(cell_at((9.99, 19.99), CELL, (0, 0), GRID), Some((0, 0)));
        // Edges belong to the next cell, as painting starts it there.
        assert_eq!(cell_at((10., 20.), CELL, (0, 0), GRID), Some((1, 1)));
        assert_eq!(cell_at((799.9, 479.9), CELL, (0, 0), GRID), Some((79, 23)));
    }

    #[test]
    fn zoom_and_font_scale_change_only_the_cell_size() {
        // Pinch zoom or a larger font: the same point is an earlier cell.
        assert_eq!(cell_at((105., 105.), (10., 20.), (0, 0), GRID), Some((10, 5)));
        assert_eq!(cell_at((105., 105.), (15., 30.), (0, 0), GRID), Some((7, 3)));
        assert_eq!(cell_at((105., 105.), (7.5, 15.), (0, 0), GRID), Some((14, 7)));
    }

    #[test]
    fn a_grid_drawn_against_the_pane_bottom_counts_from_its_own_first_row() {
        // A 24-row grid at the bottom of a 52-row pane: drawn from pane row 28.
        let origin = (-28, 0);
        assert_eq!(cell_at((5., 28. * 20.), CELL, origin, GRID), Some((0, 0)));
        assert_eq!(cell_at((5., 51. * 20. + 1.), CELL, origin, GRID), Some((0, 23)));
        // The empty rows above it are no cell.
        assert_eq!(cell_at((5., 27. * 20.), CELL, origin, GRID), None);
        assert_eq!(cell_at((5., 0.), CELL, origin, GRID), None);
    }

    #[test]
    fn a_window_into_a_larger_grid_is_offset_by_its_origin() {
        assert_eq!(cell_at((5., 5.), CELL, (16, 40), (120, 40)), Some((40, 16)));
        assert_eq!(cell_at((795., 465.), CELL, (16, 40), (120, 40)), Some((119, 39)));
    }

    #[test]
    fn outside_the_grid_is_no_cell() {
        // The letterbox right of a narrow grid, and below a short one.
        assert_eq!(cell_at((800., 5.), CELL, (0, 0), GRID), None);
        assert_eq!(cell_at((5., 480.), CELL, (0, 0), GRID), None);
        assert_eq!(cell_at((-0.5, 5.), CELL, (0, 0), GRID), None);
        assert_eq!(cell_at((5., f32::NAN), CELL, (0, 0), GRID), None);
        assert_eq!(cell_at((5., 5.), (0., 20.), (0, 0), GRID), None);
    }

    #[test]
    fn a_touch_keeps_the_stamp_it_was_queued_with() {
        // The host shows pane 1, a finger goes down, then the host switches to
        // pane 2 before GPUI processes that down: the touch is still pane 1's.
        let mut downs = DownStamps::default();
        downs.queued(300., 600., 1);
        downs.queued(450., 900., 2);
        assert_eq!(downs.started((100., 200.), 3.), Some(1));
        assert_eq!(downs.started((150., 300.), 3.), Some(2));
        assert_eq!(downs.started((150., 300.), 3.), None);
    }

    #[test]
    fn a_down_gpui_started_no_touch_for_is_passed_over() {
        let mut downs = DownStamps::default();
        downs.queued(10., 10., 1); // caught a fling: no touch started for it
        downs.queued(20., 20., 2);
        downs.queued(30., 30., 3);
        assert_eq!(downs.started((20., 20.), 1.), Some(2));
        // The later down is still waiting for its own touch.
        assert_eq!(downs.started((30., 30.), 1.), Some(3));
    }

    #[test]
    fn an_unmatched_touch_takes_nothing_from_the_queue() {
        let mut downs = DownStamps::default();
        downs.queued(10., 10., 1);
        assert_eq!(downs.started((500., 500.), 1.), None);
        assert_eq!(downs.started((10., 10.), 1.), Some(1));
    }

    #[test]
    fn the_queue_is_bounded() {
        let mut downs = DownStamps::default();
        for i in 0..(DownStamps::CAPACITY as u64 + 5) {
            downs.queued(i as f32, 0., i);
        }
        // The first five were dropped to keep the bound.
        assert_eq!(downs.started((0., 0.), 1.), None);
        assert_eq!(downs.started((5., 0.), 1.), Some(5));
    }

    #[test]
    fn a_view_with_no_program_reports_its_taps_as_plain() {
        let mut touch = Touch::default();
        touch.began(4);
        assert_eq!(plain_view_tap(touch.lifted()), (Tapped::Plain, 4));
        touch.began(4);
        touch.held();
        assert_eq!(plain_view_tap(touch.lifted()), (Tapped::Ignored, 4));
    }

    #[test]
    fn a_view_replaced_mid_tap_reports_nothing_to_act_on() {
        // The touch began on the shell's view; the pane's own view replaced it
        // before the lift. The new view saw no start: no stamp, so the host
        // drops the report, and the old view, gone, sends no click.
        let replacing = Touch::default().lifted();
        assert_eq!(plain_view_tap(replacing), (Tapped::Plain, u64::MAX));
        // The other way round: the down's stamp was taken by the view that
        // started the touch, so the view that lifts it finds none.
        let mut downs = DownStamps::default();
        downs.queued(40., 40., 9);
        assert_eq!(downs.started((40., 40.), 1.), Some(9));
        assert_eq!(downs.started((40., 40.), 1.), None);
    }
}
