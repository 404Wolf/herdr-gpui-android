//! Rio VT parsing over a caller's byte stream, with a GPUI canvas adapter.
//! Bind to your application's GPUI version with [`bind_gpui!`].
//!
//! Unlike upstream gpui-rio, this copy owns no PTY: a [`Session`] reads terminal
//! output from any reader (an SSH channel here) and writes input to any writer,
//! so it also builds and runs on Android.

mod adapter;
mod error;
pub mod ghost;
pub mod glyphs;
mod keys;
#[doc(hidden)]
pub mod links;
mod mouse;
mod options;
mod session;
#[doc(hidden)]
pub mod tap;

pub use error::{Error, Result};
pub use keys::{KeyPress, key_bytes};
pub use options::{TerminalColor, TerminalStyle, TerminalTheme};
pub use session::{Session, viewport_origin};

#[doc(hidden)]
pub mod __private {
    pub use crate::session::{Listener, resolve_color, resolve_index};
    pub use rio_vt;
}
