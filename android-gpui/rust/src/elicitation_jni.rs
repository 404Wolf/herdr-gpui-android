//! JNI for `dev.herdr.android.ElicitationNative`: reads the dialog on a pane's
//! live grid and the next step of a submission. Read-only: the host sends the
//! keys it is given through its own ordered input path.
use crate::elicitation::{self, Row};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jlong, jstring},
};
use std::sync::{
    Arc,
    atomic::{AtomicU64, Ordering},
};

/// The last attach logged as having no renderer, so a pane gone offline is logged once,
/// not on every read; cleared when a renderer is found again.
static MISSING: AtomicU64 = AtomicU64::new(0);

fn fail(env: &mut JNIEnv<'_>, message: &str) {
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/IllegalStateException", message);
    }
}

fn read(env: &mut JNIEnv<'_>, value: &JString<'_>, limit: usize) -> Option<String> {
    if value.is_null() {
        fail(env, "Missing elicitation argument");
        return None;
    }
    match env.get_string(value).map(String::from) {
        Ok(value) if value.len() <= limit => Some(value),
        _ => {
            fail(env, "Invalid or oversized elicitation argument");
            None
        }
    }
}

fn reply(env: &mut JNIEnv<'_>, result: crate::Result<Option<String>>) -> jstring {
    match result {
        Ok(Some(value)) => match env.new_string(value) {
            Ok(value) => value.into_raw(),
            Err(_) => {
                fail(env, "Cannot return elicitation result");
                std::ptr::null_mut()
            }
        },
        Ok(None) => std::ptr::null_mut(),
        Err(error) => {
            fail(env, &error.to_string());
            std::ptr::null_mut()
        }
    }
}

/// A raw shell's or a Herdr pane's renderer, by the handle the activity attaches.
fn renderer(handle: jlong) -> Option<Arc<gpui_rio::Session>> {
    let handle = u64::try_from(handle).ok()?;
    let session = crate::shell::session(handle).or_else(|| crate::remote::renderer(handle));
    match &session {
        Some(_) => {
            let _ = MISSING.compare_exchange(handle, 0, Ordering::Relaxed, Ordering::Relaxed);
        }
        None if MISSING.swap(handle, Ordering::Relaxed) != handle => {
            #[cfg(feature = "android-ui")]
            log::warn!("elicitation: no renderer for attach {handle}");
        }
        None => {}
    }
    session
}

/// The pane's rows now.
fn rows(handle: jlong) -> Option<Vec<Row>> {
    let session = renderer(handle)?;
    let grid = session.grid().lock();
    elicitation::screen_rows(&grid)
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_ElicitationNative_attached(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jboolean {
    if renderer(handle).is_some() { JNI_TRUE } else { JNI_FALSE }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_ElicitationNative_parse(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    kind: JString<'_>,
) -> jstring {
    let Some(kind) = read(&mut env, &kind, 16) else {
        return std::ptr::null_mut();
    };
    let result = elicitation::prompt_kind(&kind)
        .and_then(|kind| rows(handle).map_or(Ok(None), |rows| elicitation::dialog_json(&rows, kind)));
    reply(&mut env, result)
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_ElicitationNative_step(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    kind: JString<'_>,
    goal: JString<'_>,
    progress: JString<'_>,
) -> jstring {
    let Some(kind) = read(&mut env, &kind, 16) else {
        return std::ptr::null_mut();
    };
    let Some(goal) = read(&mut env, &goal, elicitation::MAX_GOAL_JSON) else {
        return std::ptr::null_mut();
    };
    let Some(progress) = read(&mut env, &progress, elicitation::MAX_GOAL_JSON) else {
        return std::ptr::null_mut();
    };
    let result = elicitation::prompt_kind(&kind).and_then(|kind| {
        let rows = rows(handle);
        elicitation::step_json(rows.as_deref(), kind, &goal, &progress).map(Some)
    });
    reply(&mut env, result)
}
