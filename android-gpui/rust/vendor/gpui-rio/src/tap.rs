//! What a press on the terminal does when it lifts. A press is a mouse click
//! or a finger's tap. GPUI turns a tap into a synthesized press and release
//! at the moment the finger lifts, and turns a drag into scrolling, so no
//! press is ever seen for it. A finger held in place is still a tap to GPUI
//! when no element claims the long press. [`Touch`] remembers that from the
//! gesture events GPUI reports, so a held finger is never mistaken for a
//! click.

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

/// The finger on the terminal, from GPUI's gesture events: every touch is
/// offered as a touch drag when it starts, and as a long press once held. A
/// mouse has neither, so a mouse press is never held.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Touch {
    /// While a finger is down: whether it has been held.
    held: Option<bool>,
}

impl Touch {
    /// A finger went down. Whatever an earlier touch left is forgotten.
    pub fn began(&mut self) {
        self.held = Some(false);
    }

    /// The finger was held in place for a long press.
    pub fn held(&mut self) {
        if let Some(held) = &mut self.held {
            *held = true;
        }
    }

    /// At the press GPUI synthesizes when the finger lifts (or a mouse
    /// press): whether that finger was held. The touch is over either way.
    pub fn lifted(&mut self) -> bool {
        self.held.take().unwrap_or(false)
    }
}

#[cfg(test)]
mod tests {
    use super::{Lift, Press, Touch, lift};

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
        touch.began();
        touch.held();
        assert!(touch.lifted());
        // The next tap starts afresh.
        touch.began();
        assert!(!touch.lifted());
    }

    #[test]
    fn a_new_touch_forgets_one_that_never_lifted_as_a_tap() {
        // Held, then dragged into a scroll: no press ever comes for it.
        let mut touch = Touch::default();
        touch.began();
        touch.held();
        touch.began();
        assert!(!touch.lifted());
    }

    #[test]
    fn a_mouse_press_is_never_held() {
        let mut touch = Touch::default();
        assert!(!touch.lifted());
        // A long press reported with no touch begun changes nothing.
        touch.held();
        assert!(!touch.lifted());
    }
}
