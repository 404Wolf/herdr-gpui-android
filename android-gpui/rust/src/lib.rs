//! Android GPU-rendered companion terminal. The Java activity owns credentials,
//! HTTPS companion lifecycle; Rust owns GPU display, bounded semantic input, and
//! remote generation-1 SSH sessions projected into the same text display.

mod error;
pub use error::{Error, Result};
pub mod model;

#[cfg(all(feature = "remote", unix))]
mod core_session;
#[cfg(all(feature = "remote", unix))]
pub mod remote;
#[cfg(all(feature = "remote", target_os = "android"))]
mod remote_jni;
#[cfg(all(feature = "remote", unix))]
pub mod shell;

#[cfg(all(feature = "android-ui", target_os = "android"))]
mod android;
#[cfg(any(feature = "android-ui", test))]
mod cell_shapes;
#[cfg(feature = "android-ui")]
mod fonts;
#[cfg(feature = "android-ui")]
pub mod ui;
/// The vendored Rio terminal entity, bound to this crate's GPUI.
#[cfg(all(feature = "android-ui", unix))]
mod rio {
    gpui_rio::bind_gpui!(gpui);
}
