//! t1ws's twelve review probes for the tap stamp queue (three rounds on
//! herdr-gpui-android #16), ported to how downs are matched now: the host's
//! render thread reports each touch Java posted as it hands it to GPUI
//! (`DownStamps::observed`, from gpui-mobile's touch observer). A "start" in
//! the original probes is the observed down; the fling guard's synthetic
//! contact, a cancel's silence and dispatch pauses are invisible here, so
//! each probe reduces to the order of posted touches.
use gpui_rio::tap::{DownStamps, Gesture, NO_STAMP, Tapped, Touch, plain_view_tap};

const DOWN: u32 = 0;
const UP: u32 = 1;
const MOVE: u32 = 2;
const CANCEL: u32 = 3;

/// One posted gesture: its down (returning the gesture it began), then `end`.
fn down(downs: &mut DownStamps) -> Gesture {
    downs.observed(DOWN);
    downs.now()
}

fn end(downs: &mut DownStamps, action: u32) {
    downs.observed(MOVE);
    downs.observed(action);
}

// Round 1 (5e98a5c).

#[test]
fn reviewer_two_completed_taps_drained_together_are_two_downs() {
    let mut downs = DownStamps::new();
    downs.queued(10);
    downs.queued(20);
    let first = down(&mut downs);
    let mut touch = Touch::default();
    touch.began(first);
    assert_eq!(
        plain_view_tap(touch.lifted(), downs.now()),
        (Tapped::Plain, 10)
    );
    end(&mut downs, UP);
    let second = down(&mut downs);
    assert_eq!(
        second.stamp, 20,
        "a distinct completed tap inherited the previous pane's stamp"
    );
    assert_ne!(second.id, first.id);
}

#[test]
fn reviewer_merging_taps_does_not_poison_the_next_panes_stamp() {
    let mut downs = DownStamps::new();
    downs.queued(10);
    downs.queued(20);
    down(&mut downs);
    end(&mut downs, UP);
    down(&mut downs);
    end(&mut downs, UP);
    downs.queued(30);
    assert_eq!(
        down(&mut downs).stamp,
        30,
        "the unconsumed second stamp leaked onto a later down"
    );
}

#[test]
fn reviewer_unstamped_start_cannot_swallow_a_new_stamped_down() {
    let mut downs = DownStamps::new();
    assert_eq!(down(&mut downs).stamp, NO_STAMP);
    end(&mut downs, UP);
    downs.queued(20);
    assert_eq!(down(&mut downs).stamp, 20);
}

#[test]
fn reviewer_one_synthetic_pair_survives_a_dispatch_pause() {
    let mut downs = DownStamps::new();
    downs.queued(10);
    downs.queued(20);
    let a = down(&mut downs);
    // GPUI's synthetic and real starts, any time apart, both read `now`.
    assert_eq!(downs.now(), a);
    assert_eq!(
        downs.now().stamp,
        10,
        "the real contact stole the next queued down's stamp"
    );
}

#[test]
fn reviewer_b_and_c_immediate_pairs_preserve_ownership_and_hold() {
    for held in [false, true] {
        let mut downs = DownStamps::new();
        downs.queued(10);
        let id = down(&mut downs);
        let mut touch = Touch::default();
        touch.began(downs.now()); // synthetic start
        touch.began(downs.now()); // real start
        if held {
            touch.held();
        }
        assert_eq!(downs.now(), id);
        assert_eq!(
            plain_view_tap(touch.lifted(), downs.now()),
            (if held { Tapped::Ignored } else { Tapped::Plain }, 10)
        );
    }
}

#[test]
fn reviewer_exact_copied_position_works_without_fuzzy_matching() {
    // Positions play no part: two downs on one exact point are two downs.
    let mut downs = DownStamps::new();
    downs.queued(10);
    downs.queued(20);
    assert_eq!(down(&mut downs).stamp, 10);
    assert_eq!(downs.now().stamp, 10);
    end(&mut downs, UP);
    assert_eq!(down(&mut downs).stamp, 20);
}

// Round 2 (e6af370).

#[test]
fn reviewer_prequeued_host_cancel_keeps_a_synthetic_pair_together() {
    let mut downs = DownStamps::new();
    downs.queued(10); // Java: DOWN A, then CANCEL A, then DOWN B, before GPUI drains
    downs.queued(20);
    let a = down(&mut downs);
    assert_eq!(a.stamp, 10); // synthetic start reads it
    assert_eq!(
        downs.now(),
        a,
        "prequeued cancel split one raw down and stole B's stamp"
    );
}

#[test]
fn reviewer_the_next_tap_keeps_its_stamp_after_a_cancelled_synthetic_pair() {
    let mut downs = DownStamps::new();
    downs.queued(10);
    downs.queued(20);
    down(&mut downs);
    end(&mut downs, CANCEL);
    assert_eq!(
        down(&mut downs).stamp,
        20,
        "B lost its queued stamp to A's real start"
    );
}

#[test]
fn reviewer_overflow_keeps_the_cancelled_placeholders_boundary() {
    let mut downs = DownStamps::new();
    downs.queued(10);
    for stamp in 20..20 + DownStamps::CAPACITY as u64 {
        downs.queued(stamp);
    }
    assert_eq!(down(&mut downs).stamp, NO_STAMP);
    end(&mut downs, CANCEL);
    assert_eq!(
        down(&mut downs).stamp,
        20,
        "a dropped cancelled down swallowed the next stamp"
    );
}

// Round 3 (a68adf0).

#[test]
fn reviewer_prequeued_cancel_without_a_guard_keeps_the_next_down() {
    let mut downs = DownStamps::new();
    downs.queued(10);
    downs.queued(20);
    assert_eq!(down(&mut downs).stamp, 10);
    end(&mut downs, CANCEL);
    assert_eq!(
        down(&mut downs).stamp,
        20,
        "an ordinary cancelled down swallowed B"
    );
}

#[test]
fn reviewer_the_two_start_cap_does_not_leave_subsequent_stamps_shifted() {
    let mut downs = DownStamps::new();
    downs.queued(10);
    downs.queued(20);
    down(&mut downs);
    end(&mut downs, CANCEL);
    down(&mut downs);
    end(&mut downs, UP);
    downs.queued(30);
    assert_eq!(
        down(&mut downs).stamp,
        30,
        "B's stamp was left queued for C"
    );
}

#[test]
fn reviewer_a_dropped_downs_synthetic_pair_cannot_steal_a_surviving_stamp() {
    let mut downs = DownStamps::new();
    downs.queued(10);
    downs.queued(11);
    for stamp in 20..20 + DownStamps::CAPACITY as u64 {
        downs.queued(stamp);
    }
    assert_eq!(down(&mut downs).stamp, NO_STAMP); // A
    end(&mut downs, UP);
    let b = down(&mut downs);
    assert_eq!(b.stamp, NO_STAMP); // B: its synthetic and real starts both read this
    assert_eq!(downs.now(), b, "B's real start stole C's surviving stamp");
    end(&mut downs, UP);
    assert_eq!(down(&mut downs).stamp, 20);
}
