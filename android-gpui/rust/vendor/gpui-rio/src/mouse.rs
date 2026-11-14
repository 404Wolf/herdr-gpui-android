//! The mouse reports a terminal sends a program that asked for mouse events
//! (DECSET 9, 1000, 1002 or 1003), in the encoding it chose: SGR (1006), or
//! the classic `CSI M` bytes. A program that asked for none gets nothing.
//! Pure: the session writes what [`report`] returns.

use rio_vt::crosswords::Mode;

/// What the report says happened.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Gesture {
    /// A press and release of the left button: a click, or a tap.
    Click,
    /// One notch of the wheel, away from or towards the reader.
    WheelUp,
    WheelDown,
}

/// The bytes reporting `gesture` at the 1-based cell (`column`, `row`), or
/// `None` when the program asked for no mouse events.
///
/// X10 mode (DECSET 9) reports presses only, so a click there is its press.
/// The classic encoding carries coordinates up to 223.
pub fn report(mode: Mode, gesture: Gesture, column: usize, row: usize) -> Option<Vec<u8>> {
    if !mode.intersects(Mode::MOUSE_MODE) {
        return None;
    }
    let sgr = mode.contains(Mode::SGR_MOUSE);
    let encode = |button: u8, pressed: bool| {
        if sgr {
            let end = if pressed { 'M' } else { 'm' };
            format!("\x1b[<{button};{column};{row}{end}").into_bytes()
        } else {
            // The classic release names no button: 3.
            let button = if pressed { button } else { 3 };
            let at = |value: usize| u8::try_from(32 + value.min(223)).unwrap_or(u8::MAX);
            vec![0x1b, b'[', b'M', 32 + button, at(column), at(row)]
        }
    };
    Some(match gesture {
        Gesture::Click if mode.contains(Mode::MOUSE_REPORT_X10) => encode(0, true),
        Gesture::Click => [encode(0, true), encode(0, false)].concat(),
        Gesture::WheelUp => encode(64, true),
        Gesture::WheelDown => encode(65, true),
    })
}

#[cfg(test)]
mod tests {
    use super::{Gesture, report};
    use rio_vt::crosswords::Mode;

    #[test]
    fn nothing_is_reported_to_a_program_that_asked_for_no_mouse_events() {
        for mode in [
            Mode::empty(),
            Mode::SGR_MOUSE,
            Mode::ALT_SCREEN | Mode::SGR_MOUSE,
        ] {
            for gesture in [Gesture::Click, Gesture::WheelUp, Gesture::WheelDown] {
                assert_eq!(report(mode, gesture, 3, 4), None, "{mode:?} {gesture:?}");
            }
        }
    }

    #[test]
    fn a_click_is_a_press_and_a_release_in_every_reporting_mode() {
        // 1000 (clicks), 1002 (drags) and 1003 (all motion) all report clicks.
        for reporting in [
            Mode::MOUSE_REPORT_CLICK,
            Mode::MOUSE_DRAG,
            Mode::MOUSE_MOTION,
        ] {
            assert_eq!(
                report(reporting | Mode::SGR_MOUSE, Gesture::Click, 5, 12).as_deref(),
                Some(&b"\x1b[<0;5;12M\x1b[<0;5;12m"[..]),
                "{reporting:?} with SGR",
            );
            assert_eq!(
                report(reporting, Gesture::Click, 5, 12).as_deref(),
                Some(&[0x1b, b'[', b'M', 32, 37, 44, 0x1b, b'[', b'M', 35, 37, 44][..]),
                "{reporting:?} classic",
            );
        }
    }

    #[test]
    fn x10_mode_reports_the_press_alone() {
        assert_eq!(
            report(Mode::MOUSE_REPORT_X10, Gesture::Click, 1, 1).as_deref(),
            Some(&[0x1b, b'[', b'M', 32, 33, 33][..]),
        );
    }

    #[test]
    fn the_wheel_is_one_press_of_button_64_or_65() {
        let mode = Mode::MOUSE_REPORT_CLICK | Mode::SGR_MOUSE;
        assert_eq!(
            report(mode, Gesture::WheelUp, 41, 13).as_deref(),
            Some(&b"\x1b[<64;41;13M"[..])
        );
        assert_eq!(
            report(mode, Gesture::WheelDown, 41, 13).as_deref(),
            Some(&b"\x1b[<65;41;13M"[..])
        );
        assert_eq!(
            report(Mode::MOUSE_REPORT_CLICK, Gesture::WheelUp, 41, 13).as_deref(),
            Some(&[0x1b, b'[', b'M', 96, 73, 45][..]),
        );
    }

    #[test]
    fn classic_coordinates_stop_at_223() {
        let bytes = report(Mode::MOUSE_REPORT_X10, Gesture::Click, 500, 300).unwrap_or_default();
        assert_eq!(&bytes[4..], &[255, 255]);
    }
}
