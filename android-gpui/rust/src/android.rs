//! Process-lived SurfaceView host and bounded JNI bridge. No networking here.
use crate::model::{self, Snapshot};
use gpui::{App, AppContext, WindowOptions};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jboolean, jfloat, jint, jintArray, jlong, jstring},
};

// The transitive android-activity crate references this symbol. The actual host
// is a plain Activity + SurfaceView; NativeActivity must never initialize GPUI.
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
fn android_main(_app: android_activity::AndroidApp) {
    log::error!("Use the SurfaceView host entry point, not NativeActivity");
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeStart<'local>(
    mut env: jni_mobile::EnvUnowned<'local>,
    _class: jni_mobile::objects::JClass<'local>,
    activity: jni_mobile::objects::JObject<'local>,
) {
    env.with_env(|env| -> jni_mobile::errors::Result<()> {
        gpui_mobile::android::jni::set_host_activity(env, &activity).map_err(|_| {
            jni_mobile::errors::Error::JniCall(jni_mobile::errors::JniError::Unknown)
        })?;
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Warn)
                .with_tag("herdr-gpui"),
        );
        gpui_mobile::android::jni::install_panic_hook();
        gpui_mobile::android::host::start(|cx: &mut App| {
            surface_fence::install(cx);
            // Android does not promise desktop generic aliases or GPUI's
            // fallback families. Install our fonts before any window can paint.
            if let Err(error) = crate::fonts::install(cx.text_system()) {
                log::error!("GPUI Android font initialization failed: {error}");
                return;
            }
            if cx
                .open_window(WindowOptions::default(), |window, cx| {
                    cx.new(|cx| crate::ui::TerminalView::new(window, cx))
                })
                .is_err()
            {
                log::error!("GPUI Android window initialization failed");
            }
        });
        Ok(())
    })
    .resolve::<jni_mobile::errors::ThrowRuntimeExAndDefault>();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeSurfaceCreated(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    surface: jni::objects::JObject<'_>,
    scale: jfloat,
) {
    if surface.is_null() || !scale.is_finite() || scale <= 0.0 {
        return;
    }
    // SAFETY: JVM supplies a live Surface local ref and attached JNIEnv for this
    // call. NDK acquires its own ANativeWindow reference; NativeWindow owns it.
    let window =
        unsafe { ndk::native_window::NativeWindow::from_surface(env.get_raw(), surface.as_raw()) };
    if let Some(window) = window {
        gpui_mobile::android::host::surface_created(window, scale);
        surface_fence::posted();
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeSurfaceDestroyed(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    // The upstream release wait trusts a flag that only the render thread
    // clears when it takes a surface. A surface handed over moments ago (the
    // unlock that immediately re-shows the keyguard) left it stale: the wait
    // returned at once, Android abandoned the buffers, and the render thread
    // then built a Vulkan surface on them and aborted inside wgpu-hal
    // (ERROR_NATIVE_WINDOW_IN_USE_KHR). Let it take the surface first.
    surface_fence::wait_until_taken();
    gpui_mobile::android::host::surface_destroyed();
}

/// Proof that the render thread has consumed every surface handed to it.
mod surface_fence {
    use gpui::App;
    use std::sync::OnceLock;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{Duration, Instant};

    static POSTED: AtomicU64 = AtomicU64::new(0);
    static TAKEN: AtomicU64 = AtomicU64::new(0);
    static PROBE: OnceLock<async_channel::Sender<u64>> = OnceLock::new();

    /// Runs on the render thread once the first surface is attached.
    pub(super) fn install(cx: &mut App) {
        let (sender, receiver) = async_channel::unbounded::<u64>();
        if PROBE.set(sender).is_err() {
            return;
        }
        // The first surface was consumed by the time this runs.
        TAKEN.fetch_max(POSTED.load(Ordering::SeqCst), Ordering::SeqCst);
        let timers = cx.background_executor().clone();
        cx.foreground_executor()
            .spawn(async move {
                while let Ok(seq) = receiver.recv().await {
                    // The render loop drains surface commands, then promotes due
                    // timers, then runs tasks. A timer armed from a task only
                    // fires in a later pass, after a later drain, so every
                    // surface posted before the probe has been taken by then.
                    timers.timer(Duration::from_millis(1)).await;
                    TAKEN.fetch_max(seq, Ordering::SeqCst);
                }
            })
            .detach();
    }

    pub(super) fn posted() {
        POSTED.fetch_add(1, Ordering::SeqCst);
    }

    /// Bounded: a stuck render thread must not turn surface teardown into an ANR.
    pub(super) fn wait_until_taken() {
        let posted = POSTED.load(Ordering::SeqCst);
        if TAKEN.load(Ordering::SeqCst) >= posted {
            return;
        }
        let deadline = Instant::now() + Duration::from_millis(1500);
        let mut probed = false;
        while TAKEN.load(Ordering::SeqCst) < posted {
            if !probed {
                if let Some(probe) = PROBE.get() {
                    probed = probe.try_send(posted).is_ok();
                }
            }
            if Instant::now() >= deadline {
                log::error!("surface fence: render thread did not take the surface in time");
                return;
            }
            std::thread::sleep(Duration::from_micros(250));
        }
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeResumed(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    gpui_mobile::android::host::resumed();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativePaused(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    gpui_mobile::android::host::paused();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeTouch(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    action: jint,
    action_index: jint,
    ids: jni::objects::JIntArray<'_>,
    xs: jni::objects::JFloatArray<'_>,
    ys: jni::objects::JFloatArray<'_>,
) -> jboolean {
    if !matches!(action, 0 | 1 | 2 | 3 | 5 | 6) || ids.is_null() || xs.is_null() || ys.is_null() {
        return 0;
    }
    let Ok(length) = env.get_array_length(&ids) else {
        return 0;
    };
    if !(1..=16).contains(&length)
        || action_index < 0
        || action_index >= length
        || env.get_array_length(&xs).ok() != Some(length)
        || env.get_array_length(&ys).ok() != Some(length)
    {
        return 0;
    }
    let mut pointer_ids = [0; 16];
    let mut x = [0.0; 16];
    let mut y = [0.0; 16];
    let length = length as usize;
    if env
        .get_int_array_region(&ids, 0, &mut pointer_ids[..length])
        .is_err()
        || env
            .get_float_array_region(&xs, 0, &mut x[..length])
            .is_err()
        || env
            .get_float_array_region(&ys, 0, &mut y[..length])
            .is_err()
    {
        return 0;
    }
    let mut pointers = Vec::with_capacity(length);
    for i in 0..length {
        if !x[i].is_finite() || !y[i].is_finite() {
            return 0;
        }
        pointers.push(gpui_mobile::android::host::Pointer {
            id: pointer_ids[i],
            x: x[i],
            y: y[i],
        });
    }
    u8::from(gpui_mobile::android::host::motion_event(
        action as u32,
        action_index as usize,
        &pointers,
    ))
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeKey(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
    key_code: jint,
    action: jint,
    meta_state: jint,
    unicode_codepoint: jint,
) -> jboolean {
    if action == 1 {
        return 1;
    }
    if action != 0 {
        return 0;
    }
    let state = model::mailbox().state();
    if !state.active || state.generation != generation {
        return 0;
    }
    // Preserve the original physical device/layout result from Java. A negative
    // value carries Android's COMBINING_ACCENT marker and is never stripped.
    let unicode = unicode_codepoint as u32;
    let Some(stroke) =
        gpui_mobile::android::keyboard::android_key_to_keystroke(key_code, meta_state, unicode)
    else {
        return 0;
    };
    let m = stroke.modifiers;
    // SYM is not represented in GPUI Modifiers; never lose it during conversion.
    if m.function || m.platform || meta_state & 0x04 != 0 {
        return 0;
    }
    let command =
        model::physical_key(&stroke.key, m.control, m.alt, m.shift, m.platform).or_else(|| {
            if m.control || m.alt {
                return None;
            }
            // Respect Android's actual layout/dead-key result. Do not fall back
            // to the backend's US key-name guess when no Unicode was produced.
            char::from_u32(unicode)
                .filter(|c| !c.is_control())
                .map(|c| model::Command::Text {
                    text: c.to_string(),
                })
        });
    let Some(command) = command else {
        return 0;
    };
    // Capture generation on the Java callback thread, not later on the render
    // thread: a paused/reopened terminal can never consume this physical key.
    u8::from(model::mailbox().enqueue(generation, command).is_ok())
}

pub(crate) fn request_keyboard(generation: i64) -> bool {
    let state = model::mailbox().state();
    if state.generation != generation || !state.active {
        return false;
    }
    // Both this grant and the subsequent upstream show callback are posted to
    // Android's UI queue in order. Java rechecks the generation before granting.
    gpui_mobile::android::jni::with_env(|env| {
        let activity = gpui_mobile::android::jni::activity(env)?;
        let name = jni_mobile::strings::JNIString::from("gpuiRequestKeyboard");
        let signature = jni_mobile::signature::RuntimeMethodSignature::from_str("(J)V")
            .map_err(|e| e.to_string())?;
        env.call_method(
            &activity,
            name,
            signature.method_signature(),
            &[jni_mobile::objects::JValue::Long(generation)],
        )
        .map_err(|e| e.to_string())?;
        Ok(())
    })
    .is_ok()
}

/// Opens a browser for a terminal link. Only http(s) targets within bounds are
/// forwarded; Java rechecks the scheme before starting an intent.
pub(crate) fn open_link(generation: i64, url: &str) -> bool {
    let state = model::mailbox().state();
    if state.generation != generation || !state.active {
        return false;
    }
    if url.len() > 2048
        || !(url.starts_with("https://") || url.starts_with("http://"))
        || url.chars().any(char::is_control)
    {
        return false;
    }
    gpui_mobile::android::jni::with_env(|env| {
        let activity = gpui_mobile::android::jni::activity(env)?;
        let name = jni_mobile::strings::JNIString::from("gpuiOpenLink");
        let signature =
            jni_mobile::signature::RuntimeMethodSignature::from_str("(JLjava/lang/String;)V")
                .map_err(|e| e.to_string())?;
        let value = env.new_string(url).map_err(|e| e.to_string())?;
        env.call_method(
            &activity,
            name,
            signature.method_signature(),
            &[
                jni_mobile::objects::JValue::Long(generation),
                jni_mobile::objects::JValue::Object(&value),
            ],
        )
        .map_err(|e| e.to_string())?;
        Ok(())
    })
    .is_ok()
}

pub(crate) fn close_activity(generation: i64) -> bool {
    if model::mailbox().state().generation != generation {
        return false;
    }
    gpui_mobile::android::jni::with_env(|env| {
        let activity = gpui_mobile::android::jni::activity(env)?;
        let name = jni_mobile::strings::JNIString::from("gpuiClose");
        let signature = jni_mobile::signature::RuntimeMethodSignature::from_str("(J)V")
            .map_err(|e| e.to_string())?;
        env.call_method(
            &activity,
            name,
            signature.method_signature(),
            &[jni_mobile::objects::JValue::Long(generation)],
        )
        .map_err(|e| e.to_string())?;
        Ok(())
    })
    .is_ok()
}

fn bounded_string(
    env: &mut JNIEnv<'_>,
    value: &JString<'_>,
    limit: usize,
) -> jni::errors::Result<Option<String>> {
    if value.is_null() {
        return Ok(None);
    }
    let length = env.call_method(value, "length", "()I", &[])?.i()?;
    if length < 0 || length as usize > limit {
        return Ok(None);
    }
    let value: String = env.get_string(value)?.into();
    Ok((value.len() <= limit).then_some(value))
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeSetActive(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
    active: jboolean,
) {
    let changed = model::mailbox().set_active(generation, active != 0);
    if changed && active == 0 {
        // Release the mailbox before JNI callbacks, and invalidate old IME events.
        gpui_mobile::android::jni::hide_keyboard_android();
    }
}

/// A reconnect: input closes but the frame and the keyboard stay (no hide).
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeHold(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
) {
    model::mailbox().hold(generation);
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeRequestKeyboard(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
) {
    request_keyboard(generation);
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeShowKeyboard(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
) {
    // Grant first, then ask the platform to raise the IME: the grant alone only
    // records intent and never shows a keyboard.
    if request_keyboard(generation) {
        gpui_mobile::show_keyboard();
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeSetFollow(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
    follow: jboolean,
) {
    model::mailbox().set_follow(generation, follow != 0);
}

/// Draws the raw shell `handle` (0 for Herdr's pane) for this generation.
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeAttachShell(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
    handle: jlong,
) -> jboolean {
    u8::from(handle >= 0 && model::mailbox().set_shell(generation, handle as u64))
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeResetPan(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    model::mailbox().reset_pan();
}

/// Horizontal pan for a pane wider than the surface; client-local only.
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativePan(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    delta: jfloat,
) {
    model::mailbox().pan(delta);
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeSetDisplayPreferences(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    font_size: jfloat,
    compact: jboolean,
    scale: jfloat,
) {
    let result = model::mailbox().set_display_preferences_scaled(font_size, compact != 0, scale);
    if result.is_err() {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            "GPUI display font size must be 0 (auto) or finite 11-36 logical pixels, with a font scale of 0.5-3",
        );
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeSetTheme(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    index: i32,
) {
    // Out-of-range values keep the current palette instead of guessing.
    if (0..=i32::from(u8::MAX)).contains(&index) {
        model::apply_palette(index as u8);
    }
}

/// Replaces the Default palette with a provisioned one (see
/// `model::set_default_palette_json`). Call before any chrome or frame uses it.
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiChrome_nativeSetDefaultPalette(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    json: JString<'_>,
) -> jboolean {
    let Ok(json) = env.get_string(&json) else {
        return 0;
    };
    u8::from(model::set_default_palette_json(&String::from(json)))
}

/// Chrome colors for one built-in palette, in `GpuiChrome`'s order. The host
/// list and the terminal's toolbar, rail and system bars use these so they
/// follow the same theme as the painted header; an unknown index returns null.
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiChrome_nativeThemeColors(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    index: jint,
) -> jintArray {
    let Some(palette) = usize::try_from(index).ok().and_then(model::palette_at) else {
        return std::ptr::null_mut();
    };
    let roles = model::ChromeRoles::of(&palette).to_argb();
    let Ok(array) = env.new_int_array(roles.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_int_array_region(&array, 0, &roles).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeSetSnapshot(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
    title: JString<'_>,
    text: JString<'_>,
    status: JString<'_>,
    shortcuts: JString<'_>,
) {
    if model::mailbox().state().generation != generation {
        return;
    }
    let snapshot = (|| -> jni::errors::Result<Option<Snapshot>> {
        let Some(title) = bounded_string(&mut env, &title, 1024)? else {
            return Ok(None);
        };
        let Some(text) = bounded_string(&mut env, &text, model::MAX_SNAPSHOT_BYTES)? else {
            return Ok(None);
        };
        let Some(status) = bounded_string(&mut env, &status, 4096)? else {
            return Ok(None);
        };
        let Some(shortcuts) = bounded_string(&mut env, &shortcuts, model::MAX_SHORTCUT_JSON)?
        else {
            return Ok(None);
        };
        Ok(Snapshot::new(&title, &text, &status, &shortcuts).ok())
    })();
    match snapshot {
        Ok(Some(snapshot)) => {
            model::mailbox().set_snapshot(generation, snapshot);
        }
        Ok(None) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                "Invalid or oversized GPUI snapshot",
            );
        }
        Err(_) => {
            // Preserve an existing Java exception; never log terminal contents.
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new(
                    "java/lang/IllegalStateException",
                    "Could not read GPUI snapshot",
                );
            }
        }
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeSetSnapshotChrome(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
    title: JString<'_>,
    status: JString<'_>,
    shortcuts: JString<'_>,
) {
    if model::mailbox().state().generation != generation {
        return;
    }
    let chrome = (|| -> jni::errors::Result<Option<(String, String, String)>> {
        let Some(title) = bounded_string(&mut env, &title, 1024)? else {
            return Ok(None);
        };
        let Some(status) = bounded_string(&mut env, &status, 4096)? else {
            return Ok(None);
        };
        let Some(shortcuts) = bounded_string(&mut env, &shortcuts, model::MAX_SHORTCUT_JSON)?
        else {
            return Ok(None);
        };
        Ok(Some((title, status, shortcuts)))
    })();
    match chrome {
        Ok(Some((title, status, shortcuts))) => {
            match model::mailbox().set_snapshot_chrome(generation, &title, &status, &shortcuts) {
                Ok(_) => {}
                Err(_) => {
                    let _ = env.throw_new(
                        "java/lang/IllegalArgumentException",
                        "Invalid or oversized GPUI snapshot chrome",
                    );
                }
            }
        }
        Ok(None) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                "Invalid or oversized GPUI snapshot chrome",
            );
        }
        Err(_) => {
            // Preserve an existing Java exception; never log terminal contents.
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new(
                    "java/lang/IllegalStateException",
                    "Could not read GPUI snapshot chrome",
                );
            }
        }
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeCopyText(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
) -> jstring {
    let Ok(snapshot) = model::mailbox().snapshot_for_copy(generation) else {
        return std::ptr::null_mut();
    };
    // Copy runs on the Android worker; neither joining nor JNI allocation holds
    // the mailbox mutex or performs a daemon round trip.
    let text = snapshot.lines.join("\n");
    match env.new_string(text) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeTakeCommand(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
) -> jstring {
    let Some(command) = model::mailbox().take_command(generation) else {
        return std::ptr::null_mut();
    };
    // Allocation failure never causes an old terminal command to be replayed.
    match serde_json::to_string(&command)
        .ok()
        .and_then(|json| env.new_string(json).ok())
    {
        Some(value) => value.into_raw(),
        None => std::ptr::null_mut(),
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeSendKey(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
    key: JString<'_>,
) -> jboolean {
    let Ok(Some(key)) = bounded_string(&mut env, &key, 64) else {
        return 0;
    };
    let command = model::Command::Keys { keys: vec![key] };
    u8::from(model::mailbox().enqueue(generation, command).is_ok())
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeSetImeSession(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
    session: jlong,
) {
    model::mailbox().set_ime_session(generation, session);
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeImeEvent(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
    session: jlong,
    kind: jint,
    text: JString<'_>,
    start: jint,
    end: jint,
) -> jboolean {
    if start < 0 || end < 0 {
        return 0;
    }
    let Ok(Some(text)) = bounded_string(&mut env, &text, model::MAX_TEXT_BYTES) else {
        return 0;
    };
    let accepted = model::mailbox()
        .ime_event(
            generation,
            session,
            kind,
            &text,
            start as usize,
            end as usize,
        )
        .is_ok();
    if accepted && kind == 4 {
        // Dismiss locally only. The model lock is released before Java callbacks.
        gpui_mobile::android::jni::hide_keyboard_android();
    }
    u8::from(accepted)
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_GpuiTerminalActivity_nativeSendCommand(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jlong,
    json: JString<'_>,
) -> jboolean {
    let Ok(Some(json)) = bounded_string(&mut env, &json, model::MAX_COMMAND_JSON) else {
        return 0;
    };
    let Ok(command) = model::parse_command(&json) else {
        return 0;
    };
    u8::from(model::mailbox().enqueue(generation, command).is_ok())
}
