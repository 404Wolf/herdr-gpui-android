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
pub fn lift_on(
    session: &crate::Session,
    press: Press,
    owned: bool,
    cell: Option<(usize, usize)>,
) -> Lifted {
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

/// One touch-down as GPUI handled it: its place in the order GPUI handles
/// downs (unique, increasing), and the stamp the host queued with it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Gesture {
    pub id: u64,
    pub stamp: u64,
}

impl Gesture {
    /// Before any down: matches no touch a view saw begin.
    pub const NONE: Self = Self {
        id: 0,
        stamp: NO_STAMP,
    };
}

/// Whether a view owns a lifted press, and so may act on it (send a click,
/// open a link). When presses come from touch (`touch_presses`), it must have
/// seen this very gesture begin: the touch it holds is the down GPUI handled
/// last (`now`), with a stamp the host queued for it. A touch it holds from
/// an earlier gesture (cancelled by the host, or one that lifted elsewhere) or
/// none at all (it replaced the view the touch began on) is not its to act on.
/// Where presses do not come from touch, a press is the view's it lands on.
pub fn owns(touch_presses: bool, lifted: Option<(bool, Gesture)>, now: Gesture) -> bool {
    if !touch_presses {
        return true;
    }
    lifted.is_some_and(|(_, began)| began.id == now.id && began.stamp != NO_STAMP)
}

/// What a host view with no program to click reports for a lifted press,
/// from its own [`Touch`]: a tap no program took, for a press it owns (see
/// [`owns`]); no tap for a held finger, or a press that is not its own.
pub fn plain_view_tap(lifted: Option<(bool, Gesture)>, now: Gesture) -> (Tapped, u64) {
    match lifted {
        Some((held, began)) if owns(true, lifted, now) => (
            if held { Tapped::Ignored } else { Tapped::Plain },
            began.stamp,
        ),
        _ => (Tapped::Ignored, NO_STAMP),
    }
}

/// The stamps a host sends along with each touch-down, taken back as GPUI
/// handles the downs. The host's own state (which pane it shows) changes at
/// once, while a touch waits in GPUI's queue, so the stamp travels with the
/// down instead.
///
/// Every down is matched by its identity, carried by the touch itself: the
/// host posts each down at a point marked with a code of its own ([`mark`]:
/// a few units in the last place of the coordinates, far below a pixel), and
/// GPUI reports a down starting at exactly that point (its touch-drag offer,
/// or the still scroll start of a down that stopped a fling). No order,
/// position heuristic or clock is involved.
///
/// One down can reach GPUI as two contacts. Within a few seconds of a pan,
/// the Android platform layer (gpui-mobile's `FlingGuard`) relays a
/// synthetic contact, begun and cancelled at the real one's point, before
/// the real one, to stop any fling still coasting. GPUI reports its start
/// too. Both starts are at the down's marked point, so both are that one
/// down: the first takes its entry, the second finds it is the down in
/// progress.
#[derive(Debug)]
pub struct DownStamps {
    /// The downs queued and not yet started, oldest first: each one's stamp
    /// and the marked point it was posted at (physical pixels).
    queue: std::collections::VecDeque<(u64, (f32, f32))>,
    /// The code the next down is marked with.
    next_code: u8,
    /// The down GPUI started last: the touch in progress.
    current: Gesture,
    /// Where the touch in progress started (logical pixels, as GPUI reports it).
    current_at: Option<(f32, f32)>,
}

/// `point` with `code` written into the low bits of its coordinates'
/// mantissas, in steps of 8 units in the last place: 16 values in each
/// coordinate, 256 in all, at most 120 units in the last place from the
/// original (under 0.03 px below 4096 px). Steps of 8 stay distinct
/// after GPUI divides by any display scale up to 4.
pub fn mark(point: (f32, f32), code: u8) -> (f32, f32) {
    fn put(value: f32, bits: u8) -> f32 {
        if !value.is_finite() {
            return value;
        }
        f32::from_bits((value.to_bits() & !(0xF << 3)) | (u32::from(bits & 0xF) << 3))
    }
    (put(point.0, code & 0xF), put(point.1, code >> 4))
}

impl Default for DownStamps {
    fn default() -> Self {
        Self::new()
    }
}

impl DownStamps {
    /// Downs kept waiting at most; the oldest is dropped past it (it is then
    /// started with no stamp). Fewer than the 256 codes, so no two waiting
    /// downs, nor a waiting down and the one in progress, share a code.
    pub const CAPACITY: usize = 64;

    pub const fn new() -> Self {
        Self {
            queue: std::collections::VecDeque::new(),
            next_code: 0,
            current: Gesture::NONE,
            current_at: None,
        }
    }

    /// A down at `point` (physical pixels) goes into GPUI's queue with
    /// `stamp`. Returns the marked point to post it at.
    pub fn queued(&mut self, stamp: u64, point: (f32, f32)) -> (f32, f32) {
        let at = mark(point, self.next_code);
        self.next_code = self.next_code.wrapping_add(1);
        if self.queue.len() == Self::CAPACITY {
            self.queue.pop_front();
        }
        self.queue.push_back((stamp, at));
        at
    }

    /// The down queued last never reached GPUI (the platform kept it).
    pub fn withdrawn(&mut self) {
        self.queue.pop_back();
    }

    /// GPUI reported a down starting at `at` (logical pixels: GPUI's point is
    /// the posted one divided by `scale`). Returns its stamp.
    ///
    /// The queued down posted at that point is now the touch in progress;
    /// downs queued before it that never started (none should) are dropped
    /// with it, so nothing left over can reach a later down. A start at the
    /// point of the touch in progress is that down again: the real contact
    /// after the platform's synthetic one. A start of no queued down is a
    /// touch of its own with no stamp.
    pub fn started(&mut self, at: (f32, f32), scale: f32) -> u64 {
        let posted_at = |(x, y): (f32, f32)| (x / scale, y / scale);
        if let Some(index) = self
            .queue
            .iter()
            .position(|(_, point)| posted_at(*point) == at)
        {
            let stamp = self
                .queue
                .drain(..=index)
                .last()
                .map_or(NO_STAMP, |(stamp, _)| stamp);
            return self.begin(at, stamp);
        }
        if self.current_at == Some(at) {
            return self.current.stamp;
        }
        self.begin(at, NO_STAMP)
    }

    fn begin(&mut self, at: (f32, f32), stamp: u64) -> u64 {
        self.current = Gesture {
            id: self.current.id + 1,
            stamp,
        };
        self.current_at = Some(at);
        stamp
    }

    /// The stamp of the touch in progress: the down GPUI started last.
    pub fn current(&self) -> u64 {
        self.current.stamp
    }

    /// The gesture in progress: the down GPUI started last.
    pub fn now(&self) -> Gesture {
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
    down: Option<(bool, Gesture)>,
}

impl Touch {
    /// A finger went down, with the host's stamp then. Whatever an earlier
    /// touch left is forgotten.
    pub fn began(&mut self, gesture: Gesture) {
        self.down = Some((false, gesture));
    }

    /// The finger was held in place for a long press.
    pub fn held(&mut self) {
        if let Some((held, _)) = &mut self.down {
            *held = true;
        }
    }

    /// Whether the finger down now began on this view with a stamp: its
    /// press is this view's to act on (see [`owns`]), before it lifts.
    pub fn owned_now(&self, now: Gesture) -> bool {
        owns(true, self.down, now)
    }

    /// Forgets the touch: the view was swapped out or deactivated.
    pub fn clear(&mut self) {
        self.down = None;
    }

    /// At the press GPUI synthesizes when the finger lifts: whether that
    /// finger was held, and its stamp. `None` for a mouse press (no touch
    /// began). The touch is over either way.
    pub fn lifted(&mut self) -> Option<(bool, Gesture)> {
        self.down.take()
    }
}

#[cfg(test)]
mod tests {
    use super::{
        DownStamps, Gesture, Lift, NO_STAMP, Press, Tapped, Touch, cell_at, lift, mark, next_id,
        owns, plain_view_tap,
    };

    fn g(id: u64, stamp: u64) -> Gesture {
        Gesture { id, stamp }
    }

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
        touch.began(g(1, 7));
        touch.held();
        assert_eq!(touch.lifted(), Some((true, g(1, 7))));
        // The next tap starts afresh, with its own stamp.
        touch.began(g(2, 8));
        assert_eq!(touch.lifted(), Some((false, g(2, 8))));
    }

    #[test]
    fn a_new_touch_forgets_one_that_never_lifted_as_a_tap() {
        // Held, then dragged into a scroll: no press ever comes for it.
        let mut touch = Touch::default();
        touch.began(g(1, 1));
        touch.held();
        touch.began(g(2, 2));
        assert_eq!(touch.lifted(), Some((false, g(2, 2))));
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
        assert_eq!(
            cell_at((105., 105.), (10., 20.), (0, 0), GRID),
            Some((10, 5))
        );
        assert_eq!(
            cell_at((105., 105.), (15., 30.), (0, 0), GRID),
            Some((7, 3))
        );
        assert_eq!(
            cell_at((105., 105.), (7.5, 15.), (0, 0), GRID),
            Some((14, 7))
        );
    }

    #[test]
    fn a_grid_drawn_against_the_pane_bottom_counts_from_its_own_first_row() {
        // A 24-row grid at the bottom of a 52-row pane: drawn from pane row 28.
        let origin = (-28, 0);
        assert_eq!(cell_at((5., 28. * 20.), CELL, origin, GRID), Some((0, 0)));
        assert_eq!(
            cell_at((5., 51. * 20. + 1.), CELL, origin, GRID),
            Some((0, 23))
        );
        // The empty rows above it are no cell.
        assert_eq!(cell_at((5., 27. * 20.), CELL, origin, GRID), None);
        assert_eq!(cell_at((5., 0.), CELL, origin, GRID), None);
    }

    #[test]
    fn a_panned_window_maps_taps_to_the_grid_columns_drawn() {
        use crate::view_origin;
        // A 200-column grid on a 50-column pane, panned 120 columns in.
        let grid = (200, 24);
        let origin = view_origin((200, 24), (50, 24), (23, 2), false, Some(120));
        assert_eq!(origin, (0, 120));
        assert_eq!(cell_at((5., 5.), CELL, origin, grid), Some((120, 0)));
        assert_eq!(cell_at((499., 5.), CELL, origin, grid), Some((169, 0)));
        // Panned to the end: the last screen column is the grid's last.
        let origin = view_origin((200, 24), (50, 24), (23, 2), false, Some(500));
        assert_eq!(cell_at((499., 479.), CELL, origin, grid), Some((199, 23)));
    }

    #[test]
    fn a_window_into_a_larger_grid_is_offset_by_its_origin() {
        assert_eq!(cell_at((5., 5.), CELL, (16, 40), (120, 40)), Some((40, 16)));
        assert_eq!(
            cell_at((795., 465.), CELL, (16, 40), (120, 40)),
            Some((119, 39))
        );
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

    /// The display scale of the emulator and the Fold (420 dpi).
    const SCALE: f32 = 2.625;

    /// Java queues a down at `point` (physical pixels) and posts it at the
    /// marked point; GPUI reports its start there, divided by the scale.
    fn down(downs: &mut DownStamps, stamp: u64, point: (f32, f32)) -> (f32, f32) {
        let (x, y) = downs.queued(stamp, point);
        (x / SCALE, y / SCALE)
    }

    /// The platform's synthetic contact, then the real one: two starts at
    /// the one down's point.
    fn pair(downs: &mut DownStamps, at: (f32, f32)) -> u64 {
        let synthetic = downs.started(at, SCALE);
        let gesture = downs.now();
        let real = downs.started(at, SCALE);
        assert_eq!(synthetic, real, "one down, one stamp");
        assert_eq!(downs.now(), gesture, "one down, one gesture");
        real
    }

    const P: (f32, f32) = (105., 210.);

    #[test]
    fn a_touch_keeps_the_stamp_it_was_queued_with() {
        // The host shows pane 1, a finger goes down, then the host switches to
        // pane 2 before GPUI handles that down: the touch is still pane 1's.
        let mut downs = DownStamps::default();
        let a = down(&mut downs, 1, P);
        let b = down(&mut downs, 2, (300., 400.));
        assert_eq!(downs.started(a, SCALE), 1);
        assert_eq!(downs.current(), 1);
        assert_eq!(downs.started(b, SCALE), 2);
        assert_eq!(downs.started((1., 1.), SCALE), NO_STAMP);
    }

    #[test]
    fn downs_at_one_position_are_told_apart_by_their_marks() {
        // Three downs on the very same pixel, the middle one a down that stops
        // a fling (a still scroll start at its point): each its own entry.
        let mut downs = DownStamps::default();
        let a = down(&mut downs, 10, P);
        let b = down(&mut downs, 11, P);
        let c = down(&mut downs, 12, P);
        assert!(a != b && b != c && a != c);
        assert_eq!(downs.started(a, SCALE), 10);
        assert_eq!(downs.started(b, SCALE), 11);
        assert_eq!(downs.started(c, SCALE), 12);
    }

    #[test]
    fn a_down_the_platform_kept_takes_no_entry() {
        let mut downs = DownStamps::default();
        let a = down(&mut downs, 1, P);
        down(&mut downs, 2, P);
        downs.withdrawn();
        let c = down(&mut downs, 3, P);
        assert_eq!(downs.started(a, SCALE), 1);
        assert_eq!(downs.started(c, SCALE), 3);
    }

    #[test]
    fn past_the_bound_the_oldest_down_starts_without_a_stamp() {
        let mut downs = DownStamps::default();
        // Pane A's down, then CAPACITY downs on pane B: A's entry is dropped.
        let a = down(&mut downs, 1, P);
        let bs: Vec<_> = (0..DownStamps::CAPACITY)
            .map(|_| down(&mut downs, 2, P))
            .collect();
        // GPUI starts A's down first: it gets no stamp, never B's.
        assert_eq!(downs.started(a, SCALE), NO_STAMP);
        for b in bs {
            assert_eq!(downs.started(b, SCALE), 2);
        }
    }

    #[test]
    fn a_down_that_never_started_is_dropped_by_the_next_that_does() {
        // Nothing left over reaches a later down: a down GPUI skipped is
        // dropped once a down queued after it starts.
        let mut downs = DownStamps::default();
        let a = down(&mut downs, 1, P);
        let b = down(&mut downs, 2, (300., 400.));
        assert_eq!(downs.started(b, SCALE), 2);
        assert_eq!(downs.started(a, SCALE), NO_STAMP);
        let c = down(&mut downs, 3, P);
        assert_eq!(downs.started(c, SCALE), 3);
    }

    #[test]
    fn marks_are_distinct_after_any_display_scale_and_far_below_a_pixel() {
        for scale in [1., 1.5, 2., 2.625, 3., 3.5, 4.] {
            for point in [
                (0., 0.),
                (0.5, 7.25),
                (105.3, 2399.9),
                (1855.75, 2159.5),
                (-3., 12.),
            ] {
                let marked: Vec<_> = (0..=255u8)
                    .map(|code| mark(point, code))
                    .map(|(x, y)| ((x / scale).to_bits(), (y / scale).to_bits()))
                    .collect();
                let mut unique = marked.clone();
                unique.sort_unstable();
                unique.dedup();
                assert_eq!(unique.len(), 256, "codes collide at {point:?} / {scale}");
                for code in 0..=255u8 {
                    let (x, y) = mark(point, code);
                    assert!((x - point.0).abs() < 0.03 && (y - point.1).abs() < 0.03);
                }
            }
        }
        assert!(mark((f32::NAN, 1.), 9).0.is_nan());
    }

    #[test]
    fn a_tap_right_after_a_fling_keeps_its_stamp() {
        // The emulator case (4 of 4 taps dropped): a fling, then a tap 0.7 s
        // later while it still coasts. The synthetic contact stops the fling
        // (a still scroll start at the tap's point), then the tap is offered.
        let mut downs = DownStamps::default();
        let fling = down(&mut downs, 3, (500., 300.));
        assert_eq!(downs.started(fling, SCALE), 3);
        let tap = down(&mut downs, 3, (10., 150.));
        assert_eq!(pair(&mut downs, tap), 3);
    }

    #[test]
    fn a_pair_keeps_its_gesture_held_or_not() {
        for held in [false, true] {
            let mut downs = DownStamps::default();
            let at = down(&mut downs, 10, P);
            downs.started(at, SCALE);
            let id = downs.now();
            let mut touch = Touch::default();
            touch.began(id);
            downs.started(at, SCALE);
            touch.began(downs.now());
            if held {
                touch.held();
            }
            assert_eq!(downs.now(), id);
            let expected = if held { Tapped::Ignored } else { Tapped::Plain };
            assert_eq!(plain_view_tap(touch.lifted(), downs.now()), (expected, 10));
        }
    }

    // t1ws's review probes (/tmp/gpui-pr16-review), each with its downs at
    // their marked points. None needs a clock, a lifecycle signal or a cancel.

    #[test]
    fn two_taps_on_one_point_drained_together_are_two_downs() {
        let mut downs = DownStamps::default();
        let a = down(&mut downs, 10, P);
        let b = down(&mut downs, 20, P);
        assert_eq!(downs.started(a, SCALE), 10);
        let id = downs.now().id;
        assert_eq!(downs.started(b, SCALE), 20);
        assert_ne!(downs.now().id, id);
        // Nothing is left over for the next pane's down.
        let c = down(&mut downs, 30, (90., 90.));
        assert_eq!(downs.started(c, SCALE), 30);
    }

    #[test]
    fn a_start_of_no_queued_down_never_swallows_the_next() {
        let mut downs = DownStamps::default();
        assert_eq!(downs.started((40., 80.), SCALE), NO_STAMP);
        let b = down(&mut downs, 20, (40. * SCALE, 80. * SCALE));
        assert_eq!(downs.started(b, SCALE), 20);
    }

    #[test]
    fn a_pair_is_one_down_however_long_gpui_pauses_and_whatever_follows() {
        let mut downs = DownStamps::default();
        let a = down(&mut downs, 10, P);
        let b = down(&mut downs, 20, P);
        assert_eq!(pair(&mut downs, a), 10);
        assert_eq!(downs.started(b, SCALE), 20);
        // A start one float step off a down's point is no down of the queue.
        let c = down(&mut downs, 30, P);
        let off = (f32::from_bits(c.0.to_bits() + 1), c.1);
        assert_eq!(downs.started(off, SCALE), NO_STAMP);
        assert_eq!(downs.started(c, SCALE), 30);
    }

    #[test]
    fn a_cancelled_down_with_or_without_a_pair_keeps_the_next_downs_stamp() {
        // GPUI is behind: Java queues down A, cancels it (a claimed scroll or
        // pinch: GPUI reports nothing for the cancel), queues down B on the
        // same pixel. With the platform's pair, or without, B keeps its stamp.
        for with_pair in [true, false] {
            let mut downs = DownStamps::default();
            let a = down(&mut downs, 10, P);
            let b = down(&mut downs, 20, P);
            let first = if with_pair {
                pair(&mut downs, a)
            } else {
                downs.started(a, SCALE)
            };
            assert_eq!(first, 10);
            assert_eq!(downs.started(b, SCALE), 20);
            let c = down(&mut downs, 30, (90., 90.));
            assert_eq!(downs.started(c, SCALE), 30);
        }
    }

    #[test]
    fn past_the_bound_a_dropped_downs_pair_takes_no_surviving_stamp() {
        let mut downs = DownStamps::default();
        let a = down(&mut downs, 10, (200., 200.));
        let b = down(&mut downs, 11, P);
        let rest: Vec<_> = (20..20 + DownStamps::CAPACITY as u64)
            .map(|stamp| (stamp, down(&mut downs, stamp, P)))
            .collect();
        // A and B lost their entries: their starts have no stamp, B's pair too.
        assert_eq!(downs.started(a, SCALE), NO_STAMP);
        assert_eq!(pair(&mut downs, b), NO_STAMP);
        for (stamp, at) in rest {
            assert_eq!(downs.started(at, SCALE), stamp);
        }
    }

    #[test]
    fn only_a_press_whose_touch_began_on_this_view_in_this_gesture_is_acted_on() {
        let now = g(5, 4);
        // Touch presses (Android): this view saw this gesture begin, with a stamp.
        assert!(owns(true, Some((false, now)), now));
        // A touch begun on the view this one replaced: nothing to act on.
        assert!(!owns(true, None, now));
        // The host had no stamp for the touch.
        assert!(!owns(true, Some((false, g(5, NO_STAMP))), g(5, NO_STAMP)));
        // A touch this view saw in an earlier gesture (cancelled, or lifted
        // elsewhere) never stands in for the gesture lifting now.
        assert!(!owns(true, Some((false, g(4, 4))), now));
        // A mouse press, where presses do not come from touch.
        assert!(owns(false, None, now));
    }

    #[test]
    fn a_view_with_no_program_reports_its_own_taps_as_plain() {
        let mut touch = Touch::default();
        touch.began(g(1, 4));
        assert_eq!(plain_view_tap(touch.lifted(), g(1, 4)), (Tapped::Plain, 4));
        touch.began(g(2, 4));
        touch.held();
        assert_eq!(
            plain_view_tap(touch.lifted(), g(2, 4)),
            (Tapped::Ignored, 4)
        );
    }

    #[test]
    fn a_view_replaced_mid_tap_acts_on_nothing() {
        // The touch began on the other view; this one saw no start.
        assert_eq!(
            plain_view_tap(Touch::default().lifted(), g(1, 4)),
            (Tapped::Ignored, NO_STAMP)
        );
        let mut touch = Touch::default();
        touch.began(g(1, NO_STAMP));
        assert!(!touch.owned_now(g(1, NO_STAMP)));
        assert_eq!(
            plain_view_tap(touch.lifted(), g(1, NO_STAMP)),
            (Tapped::Ignored, NO_STAMP)
        );
        touch.began(g(2, 3));
        assert!(touch.owned_now(g(2, 3)));
    }

    #[test]
    fn a_cancelled_touch_is_not_the_next_gestures() {
        // A down on the view, cancelled by the host (a pinch, a scroll): the
        // view keeps its touch. The next down starts off the view (the header)
        // and lifts just inside it: the view saw no new start.
        let mut downs = DownStamps::default();
        let mut touch = Touch::default();
        let at = downs.queued(1, P);
        downs.started(at, 1.);
        touch.began(downs.now());
        let at = downs.queued(1, P);
        downs.started(at, 1.); // off the view: no began
        assert!(!touch.owned_now(downs.now()));
        assert_eq!(
            plain_view_tap(touch.lifted(), downs.now()),
            (Tapped::Ignored, NO_STAMP)
        );
    }

    #[test]
    fn gesture_ids_rise_with_every_down_handled() {
        let mut downs = DownStamps::default();
        assert_eq!(downs.now(), Gesture::NONE);
        let at = downs.queued(3, P);
        downs.started(at, 1.);
        assert_eq!(downs.now(), g(1, 3));
        downs.started((1., 1.), 1.); // no down queued there: a new gesture, no stamp
        assert_eq!(downs.now(), g(2, NO_STAMP));
    }
}
