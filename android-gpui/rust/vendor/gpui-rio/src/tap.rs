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

/// What lifting a press did on a view, for the view to finish: open the
/// link, or report it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Lifted {
    /// The press was on a link: the view opens it.
    OpenLink,
    /// The program was sent a click on the cell.
    Clicked,
    /// A tap the program did not take.
    Plain,
    /// Nothing: not this view's press, or no tap.
    Ignored,
}

/// Lifts `press` on the screen of `session`: the one place a press reaches
/// the program. A press the view does not own ([`owns`]) does nothing at all:
/// no click, no link. Its touch began on a view this one replaced, and what
/// is sent to a program cannot be taken back. An owned tap is offered to the
/// program as a click on `cell` (the session sends one only when the program
/// asked for mouse events).
pub fn lift_on(session: &crate::Session, press: Press, owned: bool, cell: Option<(usize, usize)>) -> Lifted {
    if !owned {
        return Lifted::Ignored;
    }
    match lift(press) {
        Lift::OpenLink => Lifted::OpenLink,
        Lift::Click => match cell {
            Some((column, row)) if session.click(column, row) => Lifted::Clicked,
            _ => Lifted::Plain,
        },
        Lift::Nothing => Lifted::Ignored,
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

/// The stamp no touch-down carries: the host found none queued for the
/// touch, so nothing may act on it.
pub const NO_STAMP: u64 = u64::MAX;

/// Whether a view owns a lifted press, and so may act on it (send a click,
/// open a link): it saw that press's touch begin, with a stamp the host
/// queued for it. When presses come from touch (`touch_presses`), a press
/// with no touch begun here began on another view, one this view replaced
/// mid-tap, and is not this view's to act on. A mouse press (no touch at all)
/// is owned by the view it lands on.
pub fn owns(touch_presses: bool, lifted: Option<(bool, u64)>) -> bool {
    match lifted {
        Some((_, stamp)) => stamp != NO_STAMP,
        None => !touch_presses,
    }
}

/// What a host view with no program to click reports for a lifted press,
/// from its own [`Touch`]: a tap no program took, for a press it owns; no
/// tap for a held finger, or a press whose touch began on another view.
pub fn plain_view_tap(lifted: Option<(bool, u64)>) -> (Tapped, u64) {
    match lifted {
        Some((false, stamp)) if stamp != NO_STAMP => (Tapped::Plain, stamp),
        Some((_, stamp)) => (Tapped::Ignored, stamp),
        None => (Tapped::Ignored, NO_STAMP),
    }
}

/// The stamps a host sends along with each touch-down, taken back in the
/// order GPUI handles the downs. The host's own state (which pane it shows)
/// changes at once, while a touch waits in GPUI's queue, so the stamp
/// travels with the down instead, in a queue kept in the same order.
///
/// Every down GPUI handles is matched: by identity, one entry per down in
/// dispatch order, never by position. A down that starts a touch, and one
/// GPUI takes to stop a fling (no touch starts), each take their entry.
#[derive(Debug)]
pub struct DownStamps {
    /// Downs queued and not yet handled whose stamps were dropped to keep
    /// the bound: the oldest of all, so they come first and take NO_STAMP.
    dropped: usize,
    /// The stamps of the other downs queued and not yet handled, oldest first.
    queue: std::collections::VecDeque<u64>,
    /// The stamp of the down GPUI handled last: the touch in progress.
    current: u64,
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
        Self { dropped: 0, queue: std::collections::VecDeque::new(), current: NO_STAMP }
    }

    /// A down went into GPUI's queue with `stamp`. Past the bound the oldest
    /// stamp is dropped, but its down still holds its place: it is handled
    /// with NO_STAMP, so no later down's stamp shifts onto it.
    pub fn queued(&mut self, stamp: u64) {
        if self.queue.len() == Self::CAPACITY {
            self.queue.pop_front();
            self.dropped = self.dropped.saturating_add(1);
        }
        self.queue.push_back(stamp);
    }

    /// The down queued last never reached GPUI (the platform kept it).
    pub fn withdrawn(&mut self) {
        self.queue.pop_back();
    }

    /// GPUI handled the next queued down: its stamp is the touch in progress
    /// now, and is returned. [`NO_STAMP`] when none was queued.
    pub fn handled(&mut self) -> u64 {
        self.current = if self.dropped > 0 {
            self.dropped -= 1;
            NO_STAMP
        } else {
            self.queue.pop_front().unwrap_or(NO_STAMP)
        };
        self.current
    }

    /// The stamp of the touch in progress: the down GPUI handled last.
    pub fn current(&self) -> u64 {
        self.current
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

    /// Whether the finger down now began on this view with a stamp: its
    /// press is this view's to act on (see [`owns`]), before it lifts.
    pub fn owned_now(&self) -> bool {
        self.down.is_some_and(|(_, stamp)| stamp != NO_STAMP)
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
    use super::{
        DownStamps, Lift, NO_STAMP, Press, Tapped, Touch, cell_at, lift, next_id, owns, plain_view_tap,
    };

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
        // pane 2 before GPUI handles that down: the touch is still pane 1's.
        let mut downs = DownStamps::default();
        downs.queued(1);
        downs.queued(2);
        assert_eq!(downs.handled(), 1);
        assert_eq!(downs.current(), 1);
        assert_eq!(downs.handled(), 2);
        assert_eq!(downs.handled(), NO_STAMP);
    }

    #[test]
    fn downs_at_one_position_are_told_apart_by_order() {
        // Two downs at the same spot with a fling-stopping down between them:
        // each handled down takes its own entry, never another at that spot.
        let mut downs = DownStamps::default();
        downs.queued(10); // a tap at (10, 10) on pane A
        downs.queued(11); // a down that stops a fling: no touch starts
        downs.queued(12); // a tap at (10, 10) on pane B
        assert_eq!(downs.handled(), 10);
        assert_eq!(downs.handled(), 11);
        assert_eq!(downs.handled(), 12);
    }

    #[test]
    fn a_down_the_platform_kept_takes_no_entry() {
        let mut downs = DownStamps::default();
        downs.queued(1);
        downs.queued(2);
        downs.withdrawn();
        downs.queued(3);
        assert_eq!(downs.handled(), 1);
        assert_eq!(downs.handled(), 3);
    }

    #[test]
    fn past_the_bound_the_oldest_downs_keep_their_place_without_a_stamp() {
        let mut downs = DownStamps::default();
        // Pane A's down, then CAPACITY downs on pane B: A's stamp is dropped.
        downs.queued(1);
        for _ in 0..DownStamps::CAPACITY {
            downs.queued(2);
        }
        // GPUI handles A's down first: it gets no stamp, never B's.
        assert_eq!(downs.handled(), NO_STAMP);
        for _ in 0..DownStamps::CAPACITY {
            assert_eq!(downs.handled(), 2);
        }
        assert_eq!(downs.handled(), NO_STAMP);
    }

    #[test]
    fn overflow_counts_every_dropped_down_in_order() {
        let mut downs = DownStamps::default();
        for i in 0..(DownStamps::CAPACITY as u64 + 5) {
            downs.queued(i);
        }
        // The five oldest downs lost their stamps; each is still handled, first.
        for _ in 0..5 {
            assert_eq!(downs.handled(), NO_STAMP);
        }
        assert_eq!(downs.handled(), 5);
    }

    #[test]
    fn a_withdrawn_down_after_an_overflow_leaves_the_order_intact() {
        let mut downs = DownStamps::default();
        for i in 0..(DownStamps::CAPACITY as u64 + 1) {
            downs.queued(i);
        }
        // The newest down never reached GPUI: only it leaves.
        downs.withdrawn();
        assert_eq!(downs.handled(), NO_STAMP); // down 0, its stamp dropped
        assert_eq!(downs.handled(), 1);
        for i in 2..(DownStamps::CAPACITY as u64) {
            assert_eq!(downs.handled(), i);
        }
        assert_eq!(downs.handled(), NO_STAMP);
    }

    #[test]
    fn only_a_press_whose_touch_began_on_this_view_is_acted_on() {
        // Touch presses (Android): this view saw the touch begin, with a stamp.
        assert!(owns(true, Some((false, 4))));
        // A touch begun on the view this one replaced: nothing to act on.
        assert!(!owns(true, None));
        // The host had no stamp for the touch.
        assert!(!owns(true, Some((false, NO_STAMP))));
        // A mouse press, where presses do not come from touch.
        assert!(owns(false, None));
    }

    #[test]
    fn a_view_with_no_program_reports_its_own_taps_as_plain() {
        let mut touch = Touch::default();
        touch.began(4);
        assert_eq!(plain_view_tap(touch.lifted()), (Tapped::Plain, 4));
        touch.began(4);
        touch.held();
        assert_eq!(plain_view_tap(touch.lifted()), (Tapped::Ignored, 4));
    }

    #[test]
    fn a_view_replaced_mid_tap_acts_on_nothing() {
        // The touch began on the other view; this one saw no start.
        assert_eq!(plain_view_tap(Touch::default().lifted()), (Tapped::Ignored, NO_STAMP));
        let mut touch = Touch::default();
        touch.began(NO_STAMP);
        assert!(!touch.owned_now());
        assert_eq!(plain_view_tap(touch.lifted()), (Tapped::Ignored, NO_STAMP));
        touch.began(3);
        assert!(touch.owned_now());
    }
}
