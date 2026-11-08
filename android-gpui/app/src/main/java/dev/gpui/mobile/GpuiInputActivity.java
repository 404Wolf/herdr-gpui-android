package dev.gpui.mobile;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/** Activity IME proxy, adapted from gpui-mobile with an overridable ordered-input hook. */
public class GpuiInputActivity extends Activity {
    private InputProxy input;

    @Override protected void onCreate(Bundle state) {
        // Load JNI before the host-driven Activity initializes its renderer.
        try {
            String library = getPackageManager().getActivityInfo(getComponentName(),
                    PackageManager.GET_META_DATA).metaData.getString("android.app.lib_name");
            if (library != null) System.loadLibrary(library);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException(error);
        }
        super.onCreate(state);
    }

    public void gpuiShowKeyboard(int keyboardType, long session) {
        runOnUiThread(() -> {
            if (input == null) {
                input = new InputProxy();
                input.setAlpha(0f);
                input.setSaveEnabled(false);
                input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                input.setPadding(0, 0, 0, 0);
                addContentView(input, new ViewGroup.LayoutParams(1, 1));
            }
            input.reset(session);
            int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            switch (keyboardType) {
                case 1: type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; break;
                case 2: type = InputType.TYPE_CLASS_PHONE; break;
                case 3: type = InputType.TYPE_CLASS_NUMBER; break;
                case 4: type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI; break;
                case 5: type = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL; break;
            }
            input.setInputType(type);
            input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.restartInput(input);
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            // A proxy added in this same frame has no window focus yet, and an
            // implicit request is then dropped. Ask again once it is laid out,
            // through the window's insets controller where there is one.
            input.post(() -> {
                if (!input.hasFocus()) input.requestFocus();
                if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
                    getWindow().getInsetsController().show(WindowInsets.Type.ime());
                } else {
                    imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        });
    }

    public void gpuiHideKeyboard(long session) {
        runOnUiThread(() -> {
            if (input == null) return;
            input.reset(session);
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus();
        });
    }

    public void gpuiResetComposition(long session) {
        runOnUiThread(() -> {
            if (input == null) return;
            input.reset(session);
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).restartInput(input);
        });
    }

    private final class InputProxy extends EditText {
        private long session;
        private long epoch;
        /** Sequence of the newest InputConnection handed to the keyboard. */
        private long newestConnection;
        private int depth;
        private boolean marked;
        private boolean keyboardVisible;

        InputProxy() {
            super(GpuiInputActivity.this);
            addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                public void afterTextChanged(Editable text) {
                    // Hardware keyboards edit the widget directly, outside its
                    // InputConnection. IME mutations are batched by depth below.
                    if (depth == 0) { depth++; endEdit(); }
                }
            });
        }

        @Override public boolean onKeyDown(int code, KeyEvent event) {
            if (code == KeyEvent.KEYCODE_DEL && getText().length() == 0 && !marked) {
                gpuiIme(session, 3, "", 1, 0);
                return true;
            }
            return super.onKeyDown(code, event);
        }

        void reset(long nextSession) {
            epoch++;
            depth++;
            getText().clear();
            marked = false;
            session = nextSession;
            depth = 0;
        }

        private void endEdit() {
            if (--depth != 0) return;
            Editable text = getText();
            boolean composing = BaseInputConnection.getComposingSpanStart(text) >= 0;
            if (composing || marked || text.length() > 0) {
                gpuiIme(session, composing ? 0 : 1, text.toString(),
                        Math.max(0, Selection.getSelectionStart(text)),
                        Math.max(0, Selection.getSelectionEnd(text)));
                marked = composing;
                if (!composing) {
                    depth++;
                    text.clear();
                    depth--;
                }
            }
        }

        @Override public boolean onKeyPreIme(int code, KeyEvent event) {
            if (code == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                gpuiIme(session, 4, "", 0, 0);
            }
            return super.onKeyPreIme(code, event);
        }

        /**
         * From Android 13 a Back gesture goes to the keyboard itself and never
         * reaches {@link #onKeyPreIme}, and the keyboard's own hide button never
         * did. Both show up here as the keyboard leaving the window's insets.
         * Repeating the dismissal after the key path is harmless.
         */
        @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            if (Build.VERSION.SDK_INT >= 30) {
                boolean visible = insets.isVisible(WindowInsets.Type.ime());
                if (keyboardVisible && !visible) gpuiIme(session, 4, "", 0, 0);
                keyboardVisible = visible;
            }
            return super.onApplyWindowInsets(insets);
        }

        @Override public InputConnection onCreateInputConnection(EditorInfo info) {
            InputConnection connection = super.onCreateInputConnection(info);
            if (connection == null) return null;
            final long connectionSession = session;
            final long connectionEpoch = epoch;
            // Android can create the next connection (restartInput after
            // requestFocus) before it closes the previous one. Only the newest
            // connection's close means the keyboard went away; a superseded one
            // must not dismiss or reset the live connection.
            final long connectionId = ++newestConnection;
            return new InputConnectionWrapper(connection, false) {
                @Override public boolean setComposingText(CharSequence text, int cursor, android.view.inputmethod.TextAttribute attributes) {
                    return setComposingText(text, cursor);
                }
                @Override public boolean setComposingRegion(int start, int end, android.view.inputmethod.TextAttribute attributes) {
                    return setComposingRegion(start, end);
                }
                @Override public boolean commitText(CharSequence text, int cursor, android.view.inputmethod.TextAttribute attributes) {
                    return commitText(text, cursor);
                }
                // The terminal is not a document editor. Never forward hidden mutation APIs
                // around the epoch checks, including newer Android handwriting/replacement paths.
                @Override public boolean replaceText(int start, int end, CharSequence text, int cursor,
                        android.view.inputmethod.TextAttribute attributes) { return false; }
                @Override public boolean commitCompletion(android.view.inputmethod.CompletionInfo value) { return false; }
                @Override public boolean commitCorrection(android.view.inputmethod.CorrectionInfo value) { return false; }
                @Override public boolean performContextMenuAction(int action) { return false; }
                @Override public boolean performPrivateCommand(String action, Bundle data) { return false; }
                @Override public boolean commitContent(android.view.inputmethod.InputContentInfo content, int flags, Bundle opts) { return false; }
                @Override public boolean performSpellCheck() { return false; }
                @Override public boolean previewHandwritingGesture(android.view.inputmethod.PreviewableHandwritingGesture gesture,
                        android.os.CancellationSignal signal) { return false; }
                @Override public void performHandwritingGesture(android.view.inputmethod.HandwritingGesture gesture,
                        java.util.concurrent.Executor executor, java.util.function.IntConsumer result) {
                    if (executor != null && result != null)
                        executor.execute(() -> result.accept(InputConnection.HANDWRITING_GESTURE_RESULT_UNSUPPORTED));
                }
                @Override public void closeConnection() {
                    if (connectionSession != session || connectionEpoch != epoch
                            || connectionId != newestConnection) return;
                    gpuiIme(session, 4, "", 0, 0);
                    reset(session);
                    super.closeConnection();
                }
                @Override public boolean beginBatchEdit() {
                    if (connectionSession != session || connectionEpoch != epoch || depth >= 64) return false;
                    depth++;
                    return super.beginBatchEdit();
                }
                @Override public boolean endBatchEdit() {
                    if (connectionSession != session || connectionEpoch != epoch) return false;
                    boolean result = super.endBatchEdit();
                    if (depth > 0) endEdit();
                    return result;
                }
                @Override public boolean setComposingText(CharSequence text, int cursor) {
                    if (connectionSession != session || connectionEpoch != epoch || text == null || text.length() > 65536
                            || getText().length() > 65536 - text.length()) return false;
                    depth++;
                    try { return super.setComposingText(text, cursor); }
                    finally { endEdit(); }
                }
                @Override public boolean setComposingRegion(int start, int end) {
                    if (connectionSession != session || connectionEpoch != epoch) return false;
                    depth++;
                    try { return super.setComposingRegion(start, end); }
                    finally { endEdit(); }
                }
                @Override public boolean finishComposingText() {
                    if (connectionSession != session || connectionEpoch != epoch) return false;
                    depth++;
                    try { return super.finishComposingText(); }
                    finally { endEdit(); }
                }
                @Override public boolean commitText(CharSequence text, int cursor) {
                    if (connectionSession != session || connectionEpoch != epoch || text == null || text.length() > 65536
                            || getText().length() > 65536 - text.length()) return false;
                    depth++;
                    try { return super.commitText(text, cursor); }
                    finally { endEdit(); }
                }
                @Override public boolean setSelection(int start, int end) {
                    if (connectionSession != session || connectionEpoch != epoch) return false;
                    depth++;
                    try { return super.setSelection(start, end); }
                    finally { endEdit(); }
                }
                @Override public boolean deleteSurroundingText(int before, int after) {
                    if (connectionSession != session || connectionEpoch != epoch) return false;
                    if (getText().length() == 0 && !marked) {
                        gpuiIme(session, 2, "", before, after);
                        return true;
                    }
                    depth++;
                    try { return super.deleteSurroundingText(before, after); }
                    finally { endEdit(); }
                }
                @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                    if (connectionSession != session || connectionEpoch != epoch) return false;
                    if (getText().length() == 0 && !marked) {
                        gpuiIme(session, 3, "", before, after);
                        return true;
                    }
                    depth++;
                    try { return super.deleteSurroundingTextInCodePoints(before, after); }
                    finally { endEdit(); }
                }
                @Override public boolean sendKeyEvent(KeyEvent event) {
                    if (connectionSession != session || connectionEpoch != epoch || event == null) return false;
                    // Terminal keys from the keyboard (a sticky Ctrl, Shift+Enter, Esc,
                    // Tab, arrows) mean what the same physical key means, so they go
                    // to the host's key path, in order with committed text.
                    if (terminalKey(event)) return gpuiImeKey(event);
                    if (event.isMetaPressed() || event.isSymPressed() || event.isFunctionPressed()) return false;
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) deleteSurroundingText(1, 0);
                        return true;
                    }
                    if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) commitText("\n", 1);
                        return true;
                    }
                    return super.sendKeyEvent(event);
                }
                @Override public boolean performEditorAction(int action) {
                    if (connectionSession != session || connectionEpoch != epoch) return false;
                    if (action == EditorInfo.IME_ACTION_DONE) {
                        if (!finishComposingText()) return false;
                        gpuiIme(session, 4, "", 0, 0);
                        return true;
                    }
                    if (!ImeEditorAction.submits(action)) return false;
                    return commitText("\n", 1);
                }
            };
        }
    }

    /** A key event an IME sends that has no text meaning: modified, or a navigation key. */
    static boolean terminalKey(KeyEvent event) {
        if (event.isCtrlPressed() || event.isAltPressed()) return true;
        int code = event.getKeyCode();
        if (event.isShiftPressed() && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DEL)) return true;
        switch (code) {
            case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MOVE_HOME: case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_FORWARD_DEL: case KeyEvent.KEYCODE_INSERT:
                return true;
            default:
                return false;
        }
    }

    /**
     * A terminal key from the on-screen keyboard. Hosts route it like a physical
     * key; without a host override it is refused, as before.
     */
    protected boolean gpuiImeKey(KeyEvent event) { return false; }

    /** Hosts can keep commits, hardware keys, and extra keys in one synchronous ordered queue. */
    protected void gpuiIme(long session, int kind, String text, int start, int end) {
        nativeIme(session, kind, text, start, end);
    }

    private static native void nativeIme(long session, int kind, String text, int start, int end);
}
