package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Enters the user's answers into the agent's dialog after they pressed Submit, Chat or Cancel:
 * asks the native planner for one step, sends its keys or text through the pane's ordered input,
 * and asks again once the agent has redrawn. Every key is chosen from the screen as it is, so a
 * dropped key or a dialog that changed under the user stops the run instead of answering wrong.
 * The planner reads the pane's grid, so it runs on {@code worker}; its reply is applied and
 * the keys are sent through {@code scheduler} (the UI thread in the app, the caller in tests).
 */
final class ElicitationRun {
    /** {@link ElicitationNative#step} for one pane. */
    interface Planner { String step(String goal, String progress); }
    /** Sends one input command ({"type":"keys",...} / {"type":"text",...}); false if refused. */
    interface Input { boolean send(String commandJson); }
    interface Scheduler { void after(long millis, Runnable task); }
    interface Outcome {
        void done();
        /** {@code failure} is a {@link Elicitation#failureText} code. */
        void failed(String failure);
    }

    /** Between a sent key and reading the screen again; the planner waits for a redraw too. */
    static final long STEP_MILLIS = 60;
    /** Polls while the planner waits for a redraw or the dialog to close. */
    static final long WAIT_MILLIS = 80;
    /** Consecutive waits before giving up (about four seconds). */
    static final int MAX_WAITS = 50;

    private final Planner planner;
    private final java.util.concurrent.Executor worker;
    private final Input input;
    private final Scheduler scheduler;
    private final Outcome outcome;
    private final String goal;
    private String progress = "";
    private int waits;
    private boolean stopped;
    private boolean finishedOk;

    ElicitationRun(Planner planner, java.util.concurrent.Executor worker, Input input, Scheduler scheduler,
            Outcome outcome, String goal) {
        this.planner = planner;
        this.worker = worker;
        this.input = input;
        this.scheduler = scheduler;
        this.outcome = outcome;
        this.goal = goal;
    }

    void start() {
        scheduler.after(0, this::next);
    }

    /** Stops before the next step; keys already sent stay sent. */
    void stop() {
        stopped = true;
    }

    boolean running() {
        return !stopped;
    }

    private void next() {
        if (stopped) return;
        String asked = progress;
        try {
            worker.execute(() -> {
                String reply;
                try {
                    reply = planner.step(goal, asked);
                } catch (RuntimeException error) {
                    scheduler.after(0, () -> finish(false, ""));
                    return;
                }
                scheduler.after(0, () -> apply(reply));
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            finish(false, "");
        }
    }

    private void apply(String reply) {
        if (stopped) return;
        Elicitation.Step step;
        try {
            if (reply == null) {
                finish(false, "gone");
                return;
            }
            step = Elicitation.Step.parse(reply);
        } catch (JSONException | RuntimeException error) {
            finish(false, "");
            return;
        }
        progress = step.progress;
        switch (step.type) {
            case KEYS:
            case TEXT:
                waits = 0;
                if (!input.send(command(step))) {
                    finish(false, "");
                    return;
                }
                scheduler.after(STEP_MILLIS, this::next);
                return;
            case WAIT:
                if (++waits > MAX_WAITS) {
                    finish(false, "timeout");
                    return;
                }
                scheduler.after(WAIT_MILLIS, this::next);
                return;
            case DONE:
                finish(true, null);
                return;
            case FAIL:
            default:
                finish(false, step.failure);
        }
    }

    /** The run entered everything and the dialog closed. */
    boolean finishedOk() {
        return finishedOk;
    }

    private void finish(boolean ok, String failure) {
        if (stopped) return;
        stopped = true;
        finishedOk = ok;
        if (ok) outcome.done();
        else outcome.failed(failure);
    }

    static String command(Elicitation.Step step) {
        return step.type == Elicitation.Step.Type.TEXT ? text(step.text) : keys(step.keys);
    }

    /** {"type":"keys","keys":[…]}: the pane's input command for named keys. */
    static String keys(java.util.List<String> keys) {
        try {
            return new JSONObject().put("type", "keys").put("keys", new JSONArray(keys)).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String text(String text) {
        try {
            return new JSONObject().put("type", "text").put("text", text).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
