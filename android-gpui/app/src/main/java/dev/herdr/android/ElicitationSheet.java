package dev.herdr.android;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

/**
 * An agent's question dialog (Claude Code's AskUserQuestion, Codex's request_user_input,
 * Gemini's ask_user, and their permission prompts) answered with native controls: tabs,
 * options, a custom answer, notes, Submit (factory-setup.33).
 *
 * <p>Picking options only changes this sheet. The agent's dialog is touched in two cases,
 * both the user's own taps: switching tabs (the agent shows one question at a time, so its
 * tab is switched to read that question's options), and Submit, Chat about this or Cancel,
 * which run {@link ElicitationRun}. Nothing is answered on its own.
 */
final class ElicitationSheet {
    /** The pane the dialog is on. */
    interface Pane {
        /** {@link ElicitationNative#parse} for this pane, or null. */
        String parse();
        /** {@link ElicitationNative#step} for this pane. */
        String step(String goal, String progress);
        /** One input command into this pane's ordered input; false if refused. */
        boolean send(String commandJson);
    }

    /** Re-read the dialog while the sheet is open, to follow the agent's redraws. */
    static final long REFRESH_MILLIS = 250;

    private final Activity activity;
    private final GpuiChrome chrome;
    private final Pane pane;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** What the user picked; kept by the offer across a reconnect to the same dialog. */
    private final Elicitation answers;
    /** Reads in a row that found no dialog: one is a redraw in flight, a few mean it closed. */
    private int missing;
    private Dialog window;
    private LinearLayout body;
    private TextView status;
    private TextView submit;
    private Elicitation.Dialog shown;
    private ElicitationRun run;
    private final Runnable refresh = this::refresh;
    /** Reads the pane's grid (parse, step): never on the UI thread. */
    private final java.util.concurrent.Executor worker;
    private Runnable onClosed = () -> { };
    private boolean reading;
    /** Off: the sheet shows the agent's words, so screenshots and recents get nothing, like the terminal. */
    private final boolean capturable;
    private ScrollView scroll;
    /** Each option's (and the custom row's, number 0) mark, redrawn in place as answers change. */
    private final java.util.Map<Integer, TextView> marks = new java.util.HashMap<>();
    private final java.util.List<TextView> chips = new java.util.ArrayList<>();

    /** Reads in a row with no dialog before the sheet says it closed (about 0.75 s). */
    static final int MISSING_READS = 3;

    ElicitationSheet(Activity activity, GpuiChrome chrome, Pane pane, java.util.concurrent.Executor worker,
            boolean capturable, Elicitation answers) {
        this.answers = answers;
        this.capturable = capturable;
        this.activity = activity;
        this.chrome = chrome;
        this.pane = pane;
        this.worker = worker;
    }

    /** Runs on the main thread once the sheet has closed, however it closed. */
    void setOnClosed(Runnable onClosed) {
        this.onClosed = onClosed;
    }

    boolean isShowing() {
        return window != null && window.isShowing();
    }

    /** Opens on {@code dialog}, as just read from the pane. */
    void show(Elicitation.Dialog dialog) {
        if (activity.isFinishing() || isShowing() || dialog == null) return;
        window = new Dialog(activity);
        window.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = ActionSheet.card(activity, chrome);
        card.setPadding(dp(16), dp(12), dp(16), dp(10));
        // Status first: a long dialog scrolls, and what happened must stay in view.
        status = ActionSheet.label(activity, "", 13, chrome.onSurfaceVariant);
        status.setVisibility(View.GONE);
        card.addView(status);
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(activity);
        scroll.addView(body);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        window.setContentView(card);
        ActionSheet.size(window);
        Window w = window.getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (!capturable) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        window.setOnDismissListener(d -> {
            main.removeCallbacks(refresh);
            // Closing the sheet mid-run stops before the next key; the agent keeps what it got.
            if (run != null && run.running()) android.util.Log.i(ElicitationOffer.TAG, "sheet closed during a run");
            if (run != null) run.stop();
            onClosed.run();
        });
        render(dialog);
        window.show();
        main.postDelayed(refresh, REFRESH_MILLIS);
    }

    void dismiss() {
        if (window != null) window.dismiss();
    }

    /** Reads the pane on the worker and hands the dialog (or null) to {@code then} on the main thread. */
    private void read(java.util.function.Consumer<Elicitation.Dialog> then) {
        if (reading) return;
        reading = true;
        try {
            worker.execute(() -> {
                Elicitation.Dialog dialog;
                try {
                    dialog = Elicitation.parse(pane.parse());
                } catch (JSONException | RuntimeException error) {
                    dialog = null;
                }
                Elicitation.Dialog read = dialog;
                main.post(() -> {
                    reading = false;
                    if (isShowing()) then.accept(read);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            reading = false;
        }
    }

    private void refresh() {
        if (!isShowing() || finished()) return;
        if (run == null || !run.running()) {
            read(dialog -> {
                if ((run != null && run.running()) || finished()) return;
                if (dialog == null && ++missing < MISSING_READS) return;   // a redraw in flight
                if (dialog == null) {
                    android.util.Log.i(ElicitationOffer.TAG, "dialog gone from the pane");
                    say("The agent's dialog closed.", Tone.PROGRESS);
                    dismissSoon();
                    return;
                }
                missing = 0;
                if (shown == null || !dialog.identity().equals(shown.identity())) render(dialog);
            });
        }
        main.postDelayed(refresh, REFRESH_MILLIS);
    }

    /** A run finished its dialog: the sheet only says so and closes. */
    private boolean finished() {
        return run != null && run.finishedOk();
    }

    private void dismissSoon() {
        main.postDelayed(this::dismiss, 900);
    }

    private enum Tone { PROGRESS, DONE, PROBLEM }

    private void say(String text, Tone tone) {
        status.setText(text);
        status.setTextColor(tone == Tone.DONE ? chrome.success : tone == Tone.PROBLEM ? chrome.danger : chrome.onSurfaceVariant);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void render(Elicitation.Dialog dialog) {
        boolean same = shown != null && shown.identity().equals(dialog.identity());
        int scrolled = same ? scroll.getScrollY() : 0;
        shown = dialog;
        body.removeAllViews();
        marks.clear();
        chips.clear();
        TextView heading = ActionSheet.label(activity, heading(dialog), 12, chrome.onSurfaceVariant);
        body.addView(heading);
        if (dialog.tabs.size() > 1) body.addView(tabs(dialog));
        TextView question = ActionSheet.label(activity, dialog.question, 17, chrome.onSurface);
        question.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        question.setPadding(0, dp(8), 0, dp(6));
        body.addView(question);
        for (String line : dialog.detail) body.addView(detail(line));
        if (dialog.kind != Elicitation.Kind.REVIEW) options(dialog);
        body.addView(buttons(dialog));
        // A redraw of the same question keeps the user's place in a long list.
        scroll.post(() -> scroll.scrollTo(0, scrolled));
    }

    /** Redraws marks, tab ticks and Submit from the answers, without rebuilding the sheet. */
    private void refreshMarks(Elicitation.Dialog dialog) {
        Elicitation.Answer answer = answers.answerFor(dialog);
        for (java.util.Map.Entry<Integer, TextView> entry : marks.entrySet()) {
            boolean picked = entry.getKey() == 0 ? answer.useCustom : answer.chosen.contains(entry.getKey());
            TextView mark = entry.getValue();
            mark.setText(mark(dialog.multi, picked));
            mark.setTextColor(picked ? chrome.primary : chrome.onSurfaceVariant);
            View row = (View) mark.getParent();
            if (entry.getKey() != 0) row.setContentDescription((picked ? "Selected: " : "") + labelOf(dialog, entry.getKey()));
        }
        for (int i = 0; i < chips.size(); i++) chips.get(i).setText(chipText(dialog, i));
        updateSubmit(dialog);
        if (submit != null) submit.setText(submitLabel(dialog));
    }

    private static String labelOf(Elicitation.Dialog dialog, int number) {
        for (Elicitation.Choice choice : dialog.choices) if (choice.number == number) return choice.label;
        return "";
    }

    private String chipText(Elicitation.Dialog dialog, int i) {
        Elicitation.Tab tab = dialog.tabs.get(i);
        if (tab.submit) return tab.label;
        boolean done = answers.answered(i) || Boolean.TRUE.equals(tab.answered);
        return (done ? "✓ " : "") + tab.label;
    }

    private String submitLabel(Elicitation.Dialog dialog) {
        String label = dialog.kind == Elicitation.Kind.CONFIRM ? "Send" : "Submit";
        if (dialog.tabs.size() > 1 && dialog.kind != Elicitation.Kind.REVIEW) {
            label += " (" + answers.answeredCount() + "/" + dialog.questionCount() + ")";
        }
        return label;
    }

    private static String heading(Elicitation.Dialog dialog) {
        String who = dialog.harness == Elicitation.Harness.CLAUDE ? "Claude"
                : dialog.harness == Elicitation.Harness.CODEX ? "Codex" : "Gemini";
        switch (dialog.kind) {
            case CONFIRM: return who + " asks for permission";
            case REVIEW: return who + " · review your answers";
            default: return dialog.multi ? who + " asks · pick any" : who + " asks";
        }
    }

    private View tabs(Elicitation.Dialog dialog) {
        LinearLayout row = new LinearLayout(activity);
        row.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < dialog.tabs.size(); i++) {
            Elicitation.Tab tab = dialog.tabs.get(i);
            boolean active = i == dialog.activeTab;
            TextView chip = ActionSheet.label(activity, chipText(dialog, i), 13, active ? chrome.primary : chrome.onSurfaceVariant);
            chips.add(chip);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(dp(36));
            chip.setPadding(dp(12), 0, dp(12), 0);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(18));
            shape.setStroke(dp(1), active ? chrome.primary : chrome.outline);
            shape.setColor(active ? chrome.selected : chrome.surface);
            chip.setBackground(shape);
            chip.setContentDescription("Question tab " + tab.label + (active ? ", shown" : ""));
            final int target = i;
            // Switching tabs only moves the agent's own tab: it answers nothing.
            chip.setOnClickListener(v -> switchTab(target));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.rightMargin = dp(6);
            row.addView(chip, params);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private void switchTab(int target) {
        Elicitation.Dialog dialog = shown;
        if (dialog == null || (run != null && run.running()) || dialog.activeTab < 0 || target == dialog.activeTab) return;
        int delta = target - dialog.activeTab;
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(Math.abs(delta), 16); i++) keys.add(delta > 0 ? "right" : "left");
        pane.send(ElicitationRun.keys(keys));
    }

    private View detail(String line) {
        TextView view = ActionSheet.label(activity, line, 13, chrome.onSurfaceVariant);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(8), dp(2), dp(8), dp(2));
        view.setBackgroundColor(chrome.control);
        return view;
    }

    private void options(Elicitation.Dialog dialog) {
        Elicitation.Answer answer = answers.answerFor(dialog);
        for (Elicitation.Choice choice : dialog.choices) {
            if (choice.role == Elicitation.Role.ANSWER) body.addView(option(dialog, answer, choice));
            else if (choice.role == Elicitation.Role.CUSTOM) body.addView(custom(dialog, answer));
        }
        if (dialog.harness == Elicitation.Harness.CODEX && dialog.kind == Elicitation.Kind.QUESTION) {
            EditText note = field("Notes (optional)", answer.note);
            note.addTextChangedListener(watcher(text -> {
                answers.note(dialog, text);
                refreshMarks(dialog);
            }));
            body.addView(note, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View option(Elicitation.Dialog dialog, Elicitation.Answer answer, Elicitation.Choice choice) {
        boolean picked = answer.chosen.contains(choice.number);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        row.setBackground(ActionSheet.ripple(chrome));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = ActionSheet.label(activity, mark(dialog.multi, picked), 18, picked ? chrome.primary : chrome.onSurfaceVariant);
        mark.setPadding(0, 0, dp(10), 0);
        row.addView(mark);
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(ActionSheet.label(activity, choice.label, 15, chrome.onSurface));
        if (!choice.description.isEmpty()) words.addView(ActionSheet.label(activity, choice.description, 13, chrome.onSurfaceVariant));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setContentDescription((picked ? "Selected: " : "") + choice.label);
        marks.put(choice.number, mark);
        row.setOnClickListener(v -> {
            answers.choose(dialog, choice.number);
            refreshMarks(dialog);
        });
        return row;
    }

    private View custom(Elicitation.Dialog dialog, Elicitation.Answer answer) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        TextView mark = ActionSheet.label(activity, mark(dialog.multi, answer.useCustom), 18,
                answer.useCustom ? chrome.primary : chrome.onSurfaceVariant);
        mark.setPadding(0, 0, dp(10), 0);
        row.addView(mark);
        EditText field = field("Type your own answer", answer.text);
        marks.put(0, mark);
        // Starting to type picks the custom answer (and, single-select, drops the option picked).
        field.setOnFocusChangeListener((v, focused) -> {
            if (focused && !answer.useCustom) {
                answers.custom(dialog, field.getText().toString(), true);
                refreshMarks(dialog);
            }
        });
        field.addTextChangedListener(watcher(text -> {
            answers.custom(dialog, text, !text.trim().isEmpty() || answer.useCustom);
            refreshMarks(dialog);
        }));
        mark.setContentDescription("Use your own answer");
        mark.setOnClickListener(v -> {
            answers.custom(dialog, field.getText().toString(), !answer.useCustom);
            refreshMarks(dialog);
        });
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private EditText field(String hint, String text) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setText(text);
        field.setTextSize(15);
        field.setTextColor(chrome.onSurface);
        field.setHintTextColor(chrome.onSurfaceVariant);
        // One line: the agent's own answer row ends at Enter, so the phone's answer does too.
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((v, action, event) -> {
            v.clearFocus();
            android.view.inputmethod.InputMethodManager ime = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (ime != null) ime.hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        return field;
    }

    private static String mark(boolean multi, boolean picked) {
        if (multi) return picked ? "☑" : "☐";
        return picked ? "◉" : "○";
    }

    private View buttons(Elicitation.Dialog dialog) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        TextView cancel = ActionSheet.button(activity, chrome, "Cancel", chrome.danger);
        // Esc dismisses the agent's dialog: a second tap, like every destructive row.
        ConfirmInPlace.bind(cancel, "Cancel", ConfirmInPlace.again("Cancel"), () -> start(Elicitation.Finish.CANCEL));
        row.addView(cancel);
        if (dialog.has(Elicitation.Role.CHAT)) {
            TextView chat = ActionSheet.button(activity, chrome, "Chat about this", chrome.onSurfaceVariant);
            chat.setOnClickListener(v -> start(Elicitation.Finish.CHAT));
            row.addView(chat);
        }
        submit = ActionSheet.button(activity, chrome, submitLabel(dialog), chrome.primary);
        submit.setContentDescription(dialog.kind == Elicitation.Kind.CONFIRM ? "Send the choice to the agent" : "Submit the answers to the agent");
        submit.setOnClickListener(v -> start(Elicitation.Finish.SUBMIT));
        updateSubmit(dialog);
        row.addView(submit);
        return row;
    }

    private void updateSubmit(Elicitation.Dialog dialog) {
        if (submit == null) return;
        boolean ready = answers.canSubmit(dialog) && (run == null || !run.running());
        submit.setEnabled(ready);
        submit.setAlpha(ready ? 1f : 0.4f);
    }

    private void start(Elicitation.Finish finish) {
        if (run != null && run.running()) return;
        String goal;
        try {
            goal = answers.goal(finish);
        } catch (JSONException error) {
            say(Elicitation.failureText(""), Tone.PROBLEM);
            return;
        }
        say(finish == Elicitation.Finish.CANCEL ? "Cancelling…" : "Answering…", Tone.PROGRESS);
        if (submit != null) submit.setEnabled(false);
        run = new ElicitationRun(pane::step, worker, pane::send, (millis, task) -> main.postDelayed(task, millis),
                new ElicitationRun.Outcome() {
                    @Override public void done() {
                        android.util.Log.i(ElicitationOffer.TAG, "run " + finish + " done");
                        // Done is the last word: the dialog closing now is the answer landing.
                        main.removeCallbacks(refresh);
                        answers.clear();
                        say(finish == Elicitation.Finish.CANCEL ? "Cancelled." : "Answered.", Tone.DONE);
                        dismissSoon();
                    }

                    @Override public void failed(String failure) {
                        android.util.Log.i(ElicitationOffer.TAG, "run " + finish + " stopped: " + failure);
                        say(Elicitation.failureText(failure), Tone.PROBLEM);
                        read(now -> {
                            if (now != null) render(now);
                            else if (shown != null) render(shown);
                        });
                    }
                }, goal);
        run.start();
    }

    private interface Text { void changed(String text); }

    private static TextWatcher watcher(Text text) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { text.changed(s.toString()); }
        };
    }

    private int dp(float value) {
        return ActionSheet.dp(activity, value);
    }
}
