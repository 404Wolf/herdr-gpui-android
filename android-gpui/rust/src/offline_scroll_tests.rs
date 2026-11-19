//! Wolf's ask: offline is a small corner indicator, never a grayed screen, and
//! scrolling up and down keeps working offline. These guard the scroll half on
//! the held frame (the pane kept on screen while the link is down) and on a
//! cached pane read back from the phone's copy. The Java activity turns a drag
//! or a fling into `scroll_held` steps (`requestScroll`); nothing is sent.
#![allow(clippy::unwrap_used)]

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::Value;

/// A finished grid of `lines` numbered rows on a 20×6 pane, as a held frame
/// or a cached pane is: its stream has ended, its grid stays.
fn offline_grid(lines: usize) -> Arc<gpui_rio::Session> {
    let bytes: String = (0..lines).map(|n| format!("line {n}\r\n")).collect();
    let session = crate::snapshot::restore(crate::snapshot::Saved {
        cols: 20,
        rows: 6,
        pane: "t1".into(),
        bytes: bytes.into_bytes(),
    })
    .unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    while session.is_alive() {
        assert!(Instant::now() < deadline, "the rows were never parsed");
        std::thread::sleep(Duration::from_millis(5));
    }
    session
}

fn offset(reply: String) -> u64 {
    serde_json::from_str::<Value>(&reply).unwrap()["local_offset"].as_u64().unwrap()
}

/// The finger's steps, as `requestScroll` sends them: positive is back in time.
fn drag(steps: &[i64]) -> u64 {
    let mut last = 0;
    for step in steps {
        last = offset(crate::remote::scroll_held(*step, false));
    }
    last
}

#[test]
fn wolf_ask_offline_scrolling_a_drag_and_a_fling_move_the_held_frame_and_redraw_it() {
    let held = offline_grid(40);
    crate::remote::hold(held.clone());
    let history = held.grid().lock().history_size() as u64;
    assert!(history >= 30, "the grid keeps its history offline: {history}");
    let newest = held.screen_text();
    held.take_dirty();

    // A slow drag up: one row per step, each a new screen.
    let mut seen = vec![newest.clone()];
    for want in 1..=3 {
        assert_eq!(drag(&[1]), want, "a drag step scrolls one row back");
        assert!(held.take_dirty(), "an offline scroll redraws the held frame");
        let now = held.screen_text();
        assert!(!seen.contains(&now), "row {want}: the screen moved");
        seen.push(now);
    }
    // The fling that follows the lift: decaying steps, still with no link.
    assert_eq!(drag(&[6, 4, 3, 2, 1]), 3 + 16);
    assert!(held.screen_text().contains("line"));
    // Back down, by drag, then all the way to the newest line.
    assert_eq!(drag(&[-5, -4]), 10);
    assert_eq!(offset(crate::remote::scroll_held(0, true)), 0);
    assert_eq!(held.screen_text(), newest);
    // Its ends hold: never past the oldest row, never below the newest.
    assert_eq!(drag(&[1000]), history);
    assert_eq!(drag(&[-1000]), 0);
    crate::remote::release_held();
}

#[test]
fn wolf_ask_offline_scrolling_a_cached_pane_scrolls_like_the_held_frame() {
    // Switched to offline (item 77): the pane's saved copy is the held frame.
    let path = std::env::temp_dir().join(format!("herdr-ask3-{}.snap", std::process::id()));
    let source = offline_grid(30);
    crate::snapshot::save(&source.grid().lock(), "t7", &path).unwrap();
    let id = crate::remote::open_saved(path.to_str().unwrap()).unwrap();
    std::fs::remove_file(&path).unwrap();
    // Read back on its own reader thread; scroll once it is all parsed.
    let cached = crate::remote::renderer(id).unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    while cached.is_alive() {
        assert!(Instant::now() < deadline, "the copy was never parsed");
        std::thread::sleep(Duration::from_millis(5));
    }
    assert_eq!(drag(&[2, 2, 1]), 5);
    assert_eq!(drag(&[-3]), 2);
    assert_eq!(source.grid().lock().display_offset(), 0, "the copy scrolls, not the pane it came from");
    crate::remote::release_held();
}

#[test]
fn wolf_ask_offline_scrolling_online_offline_online_keeps_the_position_sane() {
    // Online: the live grid, at its newest line.
    let live = offline_grid(40);
    // The link drops: the same grid is held, where it was.
    crate::remote::hold(live.clone());
    assert_eq!(live.grid().lock().display_offset(), 0);
    // Read back offline.
    assert_eq!(drag(&[4, 4]), 8);
    let reading = live.screen_text();
    // Still offline: another drag starts where the last one ended.
    assert_eq!(drag(&[1]), 9);
    assert_eq!(drag(&[-1]), 8);
    assert_eq!(live.screen_text(), reading);
    // Back online: the new pane attaches and nothing is held. An offline
    // scroll step after that (a fling still running) moves nothing and says
    // so (offset 0, so "Latest" hides); the live pane scrolls its own grid.
    let attached = offline_grid(40);
    crate::remote::release_held();
    assert_eq!(drag(&[3]), 0);
    assert_eq!(attached.grid().lock().display_offset(), 0, "the live pane opens at its newest line");
    assert_eq!(live.grid().lock().display_offset(), 8, "the old frame is no longer scrolled");
}
