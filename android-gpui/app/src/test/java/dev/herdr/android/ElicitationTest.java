package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * The phone side of answering agent dialogs (factory-setup.33): the native dialog JSON, the
 * answers held until Submit, the goal handed to the native planner, and the run that sends
 * only what the planner chose.
 */
public final class ElicitationTest {
    /** What {@code elicitation.rs} serialises for Claude's toppings tab (see its tests). */
    static final String TOPPINGS = "{\"harness\":\"claude\",\"kind\":\"question\","
            + "\"tabs\":[{\"label\":\"Color\",\"answered\":false,\"submit\":false},"
            + "{\"label\":\"Toppings\",\"answered\":true,\"submit\":false},"
            + "{\"label\":\"Submit\",\"answered\":null,\"submit\":true}],"
            + "\"active_tab\":1,\"question\":\"Which toppings?\",\"detail\":[],\"multi\":true,"
            + "\"choices\":[{\"number\":1,\"label\":\"Cheese\",\"description\":\"Cheese topping\",\"checked\":true,\"role\":\"answer\"},"
            + "{\"number\":2,\"label\":\"Ham\",\"description\":\"\",\"checked\":false,\"role\":\"answer\"},"
            + "{\"number\":3,\"label\":\"pepper\",\"description\":\"\",\"checked\":true,\"role\":\"custom\"},"
            + "{\"number\":null,\"label\":\"Next\",\"description\":\"\",\"checked\":null,\"role\":\"next\"},"
            + "{\"number\":4,\"label\":\"Chat about this\",\"description\":\"\",\"checked\":null,\"role\":\"chat\"}],"
            + "\"cursor\":0,\"note\":null}";

    static final String CODEX = "{\"harness\":\"codex\",\"kind\":\"question\","
            + "\"tabs\":[{\"label\":\"Question 1\",\"answered\":null,\"submit\":false},"
            + "{\"label\":\"Question 2\",\"answered\":null,\"submit\":false}],"
            + "\"active_tab\":0,\"question\":\"Which color?\",\"detail\":[],\"multi\":false,"
            + "\"choices\":[{\"number\":1,\"label\":\"Red\",\"description\":\"Choose red.\",\"checked\":null,\"role\":\"answer\"},"
            + "{\"number\":2,\"label\":\"Green\",\"description\":\"\",\"checked\":null,\"role\":\"answer\"}],"
            + "\"cursor\":0,\"note\":null}";

    @Test public void theNativeDialogJsonReadsBack() throws Exception {
        Elicitation.Dialog dialog = Elicitation.parse(TOPPINGS);
        assertEquals(Elicitation.Harness.CLAUDE, dialog.harness);
        assertEquals(Elicitation.Kind.QUESTION, dialog.kind);
        assertEquals(1, dialog.activeTab);
        assertEquals(2, dialog.questionCount());
        assertTrue(dialog.multi);
        assertEquals("Cheese topping", dialog.choices.get(0).description);
        assertEquals(Boolean.TRUE, dialog.choices.get(0).checked);
        assertNull(dialog.choices.get(3).checked);
        assertEquals(Elicitation.Role.NEXT, dialog.choices.get(3).role);
        assertEquals(0, dialog.choices.get(3).number);
        assertTrue(dialog.has(Elicitation.Role.CHAT));
        assertEquals("pepper", dialog.typedCustom());
        assertNull(dialog.note);
        assertNull(Elicitation.parse(null));
    }

    @Test public void answersStartFromWhatTheAgentShowsAndChangeOnlyHere() throws Exception {
        Elicitation answers = new Elicitation();
        Elicitation.Dialog dialog = Elicitation.parse(TOPPINGS);
        Elicitation.Answer answer = answers.answerFor(dialog);
        assertEquals(Arrays.asList(1), new ArrayList<>(answer.chosen));
        assertTrue(answer.useCustom);
        assertEquals("pepper", answer.text);
        answers.choose(dialog, 2);
        answers.choose(dialog, 1);
        assertEquals(Arrays.asList(2), new ArrayList<>(answer.chosen));
        JSONObject goal = new JSONObject(answers.goal(Elicitation.Finish.SUBMIT));
        assertEquals("submit", goal.getString("finish"));
        JSONObject first = goal.getJSONArray("answers").getJSONObject(0);
        assertEquals(1, first.getInt("tab"));
        assertEquals("Which toppings?", first.getString("question"));
        assertEquals(2, first.getJSONArray("choose").getInt(0));
        assertEquals("pepper", first.getString("text"));
        assertFalse(first.has("note"));
    }

    @Test public void singleSelectReplacesAndCustomTextClearsThePick() throws Exception {
        Elicitation answers = new Elicitation();
        Elicitation.Dialog dialog = Elicitation.parse(CODEX);
        answers.choose(dialog, 1);
        answers.choose(dialog, 2);
        assertEquals(Arrays.asList(2), new ArrayList<>(answers.answerFor(dialog).chosen));
        answers.note(dialog, "  light ");
        JSONObject item = new JSONObject(answers.goal(Elicitation.Finish.SUBMIT)).getJSONArray("answers").getJSONObject(0);
        assertEquals("light", item.getString("note"));
        answers.custom(dialog, "teal", true);
        assertTrue(answers.answerFor(dialog).chosen.isEmpty());
    }

    @Test public void typedAnswersGoInAsOneLine() throws Exception {
        assertEquals("two lines and a tab", Elicitation.oneLine("  two\nlines\r\nand a\ttab "));
        assertEquals("", Elicitation.oneLine(null));
        Elicitation answers = new Elicitation();
        Elicitation.Dialog dialog = Elicitation.parse(CODEX);
        answers.choose(dialog, 1);
        answers.note(dialog, "first\nsecond");
        JSONObject item = new JSONObject(answers.goal(Elicitation.Finish.SUBMIT)).getJSONArray("answers").getJSONObject(0);
        assertEquals("first second", item.getString("note"));
    }

    @Test public void codexNeedsEveryQuestionClaudeAtLeastOne() throws Exception {
        Elicitation codex = new Elicitation();
        Elicitation.Dialog dialog = Elicitation.parse(CODEX);
        assertFalse(codex.canSubmit(dialog));
        codex.choose(dialog, 1);
        assertFalse("question 2 is unanswered", codex.canSubmit(dialog));
        Elicitation claude = new Elicitation();
        Elicitation.Dialog toppings = Elicitation.parse(TOPPINGS.replace("\"checked\":true", "\"checked\":false")
                .replace("\"label\":\"pepper\"", "\"label\":\"Type something\""));
        assertFalse(claude.canSubmit(toppings));
        claude.choose(toppings, 2);
        assertTrue(claude.canSubmit(toppings));
    }

    @Test public void chatAndCancelCarryNoAnswers() throws Exception {
        Elicitation answers = new Elicitation();
        Elicitation.Dialog dialog = Elicitation.parse(CODEX);
        answers.choose(dialog, 1);
        JSONObject goal = new JSONObject(answers.goal(Elicitation.Finish.CANCEL));
        assertEquals("cancel", goal.getString("finish"));
        assertEquals(0, goal.getJSONArray("answers").length());
        assertEquals("chat", new JSONObject(answers.goal(Elicitation.Finish.CHAT)).getString("finish"));
    }

    @Test public void stepRepliesReadBack() throws Exception {
        Elicitation.Step keys = Elicitation.Step.parse("{\"step\":{\"keys\":[\"down\",\"enter\"]},\"progress\":{\"next\":1},\"dialog\":" + CODEX + "}");
        assertEquals(Elicitation.Step.Type.KEYS, keys.type);
        assertEquals(Arrays.asList("down", "enter"), keys.keys);
        assertEquals(1, new JSONObject(keys.progress).getInt("next"));
        assertEquals("Which color?", keys.dialog.question);
        // The native fingerprint (below 2^53) comes back to the planner digit for digit.
        Elicitation.Step big = Elicitation.Step.parse("{\"step\":\"wait\",\"progress\":{\"sent_on\":9007199254740991},\"dialog\":null}");
        assertTrue(big.progress, big.progress.contains("9007199254740991"));
        Elicitation.Step text = Elicitation.Step.parse("{\"step\":{\"text\":\"navy\"},\"progress\":{},\"dialog\":null}");
        assertEquals("navy", text.text);
        assertEquals(Elicitation.Step.Type.DONE, Elicitation.Step.parse("{\"step\":\"done\",\"progress\":{},\"dialog\":null}").type);
        assertEquals(Elicitation.Step.Type.WAIT, Elicitation.Step.parse("{\"step\":\"wait\",\"progress\":{},\"dialog\":null}").type);
        Elicitation.Step fail = Elicitation.Step.parse("{\"step\":{\"fail\":\"question_changed\"},\"progress\":{},\"dialog\":null}");
        assertEquals("question_changed", fail.failure);
        assertEquals("The agent's dialog changed. Check it on the terminal.", Elicitation.failureText(fail.failure));
        assertEquals("keys:[\"down\",\"enter\"]", shape(ElicitationRun.command(keys)));
        assertEquals("text:navy", shape(ElicitationRun.command(text)));
    }

    /** An input command as "type:payload", whatever order its JSON keys come in. */
    private static String shape(String command) {
        try {
            JSONObject object = new JSONObject(command);
            String type = object.getString("type");
            assertEquals(2, object.length());
            return type + ":" + ("keys".equals(type) ? object.getJSONArray("keys").toString() : object.getString("text"));
        } catch (org.json.JSONException error) {
            throw new AssertionError(error);
        }
    }

    /** Runs scheduled tasks in order, synchronously, recording the delays asked for. */
    static final class Clock implements ElicitationRun.Scheduler {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        final List<Long> delays = new ArrayList<>();

        @Override public void after(long millis, Runnable task) {
            delays.add(millis);
            tasks.add(task);
        }

        void drain() {
            for (int guard = 0; !tasks.isEmpty() && guard < 1000; guard++) tasks.poll().run();
        }
    }

    static final class Result implements ElicitationRun.Outcome {
        boolean done;
        String failure;

        @Override public void done() { done = true; }
        @Override public void failed(String failure) { this.failure = failure; }
    }

    private static String reply(String step, int next) {
        return "{\"step\":" + step + ",\"progress\":{\"next\":" + next + "},\"dialog\":null}";
    }

    @Test public void aRunSendsWhatThePlannerChoseAndPassesItsProgressBack() {
        Deque<String> replies = new ArrayDeque<>(Arrays.asList(
                reply("{\"keys\":[\"down\"]}", 0), reply("\"wait\"", 0),
                reply("{\"text\":\"navy\"}", 0), reply("{\"keys\":[\"enter\"]}", 1), reply("\"done\"", 1)));
        List<String> progressSeen = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        Clock clock = new Clock();
        Result result = new Result();
        ElicitationRun run = new ElicitationRun((goal, progress) -> {
            assertEquals("{\"answers\":[],\"finish\":\"submit\"}", goal);
            progressSeen.add(progress);
            return replies.poll();
        }, Runnable::run, command -> sent.add(command), clock, result, "{\"answers\":[],\"finish\":\"submit\"}");
        run.start();
        clock.drain();
        assertTrue(result.done);
        assertTrue(run.finishedOk());
        assertNull(result.failure);
        List<String> shapes = new ArrayList<>();
        for (String command : sent) shapes.add(shape(command));
        assertEquals(Arrays.asList("keys:[\"down\"]", "text:navy", "keys:[\"enter\"]"), shapes);
        assertEquals("", progressSeen.get(0));
        assertEquals("{\"next\":1}", progressSeen.get(4));
        assertFalse(run.running());
    }

    @Test public void aRunStopsOnAFailureARefusedKeyOrSilence() {
        Clock clock = new Clock();
        Result failed = new Result();
        new ElicitationRun((g, p) -> reply("{\"fail\":\"question_changed\"}", 0), Runnable::run, c -> true, clock, failed, "{}").start();
        clock.drain();
        assertEquals("question_changed", failed.failure);
        assertFalse(new ElicitationRun((g, p) -> "", Runnable::run, c -> true, clock, new Result(), "{}").finishedOk());

        Result refused = new Result();
        List<String> sent = new ArrayList<>();
        new ElicitationRun((g, p) -> reply("{\"keys\":[\"down\"]}", 0), Runnable::run, c -> { sent.add(c); return false; }, clock, refused, "{}").start();
        clock.drain();
        assertEquals("", refused.failure);
        assertEquals(1, sent.size());

        Result silent = new Result();
        new ElicitationRun((g, p) -> reply("\"wait\"", 0), Runnable::run, c -> true, clock, silent, "{}").start();
        clock.drain();
        assertEquals("timeout", silent.failure);

        Result gone = new Result();
        new ElicitationRun((g, p) -> null, Runnable::run, c -> true, clock, gone, "{}").start();
        clock.drain();
        assertEquals("gone", gone.failure);
    }

    @Test public void thePlannerRunsOnTheWorkerAndKeysAreSentOnTheScheduler() {
        Clock clock = new Clock();
        Result result = new Result();
        List<Runnable> worker = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        int[] asked = {0};
        new ElicitationRun((g, p) -> { asked[0]++; return reply("\"done\"", 0); }, worker::add,
                c -> sent.add(c), clock, result, "{}").start();
        clock.drain();
        assertEquals("nothing reads the pane until the worker runs", 0, asked[0]);
        worker.remove(0).run();
        assertEquals(1, asked[0]);
        assertFalse("the reply is applied on the scheduler, not the worker", result.done);
        clock.drain();
        assertTrue(result.done);
        // A closed worker ends the run instead of hanging it.
        Result closed = new Result();
        new ElicitationRun((g, p) -> "", task -> { throw new java.util.concurrent.RejectedExecutionException(); },
                c -> true, clock, closed, "{}").start();
        clock.drain();
        assertEquals("", closed.failure);
    }

    @Test public void stoppingARunSendsNothingMore() {
        Clock clock = new Clock();
        Result result = new Result();
        List<String> sent = new ArrayList<>();
        ElicitationRun run = new ElicitationRun((g, p) -> reply("{\"keys\":[\"down\"]}", 0),
                Runnable::run, c -> { sent.add(c); return true; }, clock, result, "{}");
        run.start();
        clock.tasks.poll().run();   // asks the planner (on the worker)
        clock.tasks.poll().run();   // applies its reply: one key sent
        assertEquals(1, sent.size());
        run.stop();
        clock.drain();
        assertEquals(1, sent.size());
        assertFalse(result.done);
        assertNull(result.failure);
    }

    private static java.util.TreeSet<String> names(JSONObject object) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (java.util.Iterator<String> keys = object.keys(); keys.hasNext(); ) names.add(keys.next());
        return names;
    }

    @Test public void goalsMatchWhatThePlannerAccepts() throws Exception {
        // Keys and fields of the native Goal (deny_unknown_fields): answers[{tab, question, choose, text?, note?}], finish.
        Elicitation answers = new Elicitation();
        Elicitation.Dialog dialog = Elicitation.parse(CODEX);
        answers.choose(dialog, 2);
        JSONObject goal = new JSONObject(answers.goal(Elicitation.Finish.SUBMIT));
        assertEquals(new java.util.TreeSet<>(Arrays.asList("answers", "finish")), names(goal));
        JSONArray list = goal.getJSONArray("answers");
        assertEquals(new java.util.TreeSet<>(Arrays.asList("tab", "question", "choose")), names(list.getJSONObject(0)));
    }

    @Test public void theSheetIsOfferedOncePerDialogAndOnlyWhereAnAgentMayAsk() throws Exception {
        assertEquals("claude", ElicitationPolicy.kind("claude"));
        assertEquals("other", ElicitationPolicy.kind("shell"));
        assertEquals("other", ElicitationPolicy.kind(null));
        assertTrue(ElicitationPolicy.mayAsk("blocked", "claude"));
        assertFalse("Claude reports its questions", ElicitationPolicy.mayAsk("idle", "claude"));
        assertTrue(ElicitationPolicy.mayAsk("", "codex"));
        assertFalse(ElicitationPolicy.mayAsk("working", "codex"));
        assertFalse("a pane that cannot take input is not read", ElicitationPolicy.mayAsk(null, "codex"));
        assertFalse(ElicitationPolicy.mayAsk(null, "claude"));
        Elicitation.Dialog claude = Elicitation.parse(TOPPINGS);
        assertTrue(ElicitationPolicy.offers(claude, "claude"));
        assertFalse("any program may draw a list with a cursor", ElicitationPolicy.offers(claude, "shell"));
        assertTrue(ElicitationPolicy.offers(Elicitation.parse(TOPPINGS.replace("\"claude\"", "\"gemini\"")), "shell"));
        assertFalse(ElicitationPolicy.offers(null, "claude"));
        // Moving between tabs is the same dialog; another question alone is another.
        Elicitation.Dialog color = Elicitation.parse(TOPPINGS.replace("\"active_tab\":1,\"question\":\"Which toppings?\"",
                "\"active_tab\":0,\"question\":\"Which color?\""));
        assertEquals(ElicitationPolicy.identity(claude), ElicitationPolicy.identity(color));
        Elicitation.Dialog codex = Elicitation.parse(CODEX.replace(",{\"label\":\"Question 2\",\"answered\":null,\"submit\":false}", ""));
        assertEquals("CODEX|Which color?", ElicitationPolicy.identity(codex));
    }

    // ---- ElicitationPolicy: when the sheet reads, opens and closes ----

    private static final String HERE = "1/t1/7/true";

    private static ElicitationPolicy blockedClaude() {
        ElicitationPolicy policy = new ElicitationPolicy();
        assertFalse(policy.observe(HERE, "blocked", "claude"));
        return policy;
    }

    @Test public void aBlockedClaudePaneIsReadAndItsDialogOpensOnce() throws Exception {
        ElicitationPolicy policy = blockedClaude();
        Elicitation.Dialog dialog = Elicitation.parse(TOPPINGS);
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, false));
        assertEquals(ElicitationPolicy.Landed.OPEN, policy.landed(HERE, dialog, true, false));
        assertTrue(policy.sheetOpen());
        assertEquals("the open sheet follows the pane itself", ElicitationPolicy.Next.IDLE, policy.check(true, false));
        policy.sheetClosed();
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, false));
        assertEquals("closed by the user: not offered again", ElicitationPolicy.Landed.LATER, policy.landed(HERE, dialog, true, false));
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, false));
        Elicitation.Dialog other = Elicitation.parse(CODEX.replace("\"codex\"", "\"claude\""));
        assertEquals("another dialog is offered", ElicitationPolicy.Landed.OPEN, policy.landed(HERE, other, true, false));
    }

    @Test public void theSheetNeverOpensOverTypingOrDictation() throws Exception {
        ElicitationPolicy policy = blockedClaude();
        Elicitation.Dialog dialog = Elicitation.parse(TOPPINGS);
        assertEquals("typing: not even read", ElicitationPolicy.Next.LATER, policy.check(true, true));
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, false));
        // The user started typing while the read was out.
        assertEquals(ElicitationPolicy.Landed.LATER, policy.landed(HERE, dialog, true, true));
        assertFalse(policy.sheetOpen());
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, false));
        assertEquals("waited, not dropped: it opens once they stop", ElicitationPolicy.Landed.OPEN, policy.landed(HERE, dialog, true, false));
    }

    @Test public void theComposerIsBusyWithTextAnOfferedDraftOrADictationInFlight() {
        dev.herdr.android.dictation.DictationEngine.State idle = dev.herdr.android.dictation.DictationEngine.State.IDLE;
        assertNull(ComposerBar.busy("", false, idle));
        assertNull(ComposerBar.busy("  \n", false, dev.herdr.android.dictation.DictationEngine.State.PENDING));
        assertNull(ComposerBar.busy(null, false, idle));
        assertEquals("a draft", ComposerBar.busy("draft", false, idle));
        assertEquals("an offered draft", ComposerBar.busy("", true, idle));
        assertEquals("dictation", ComposerBar.busy("", false, dev.herdr.android.dictation.DictationEngine.State.LISTENING));
        assertEquals("transcribing is still a dictation in flight", "dictation",
                ComposerBar.busy("", false, dev.herdr.android.dictation.DictationEngine.State.TRANSCRIBING));
    }

    @Test public void anotherPaneAttachOrConnectionClosesTheSheet() throws Exception {
        ElicitationPolicy policy = blockedClaude();
        Elicitation.Dialog dialog = Elicitation.parse(TOPPINGS);
        policy.check(true, false);
        policy.landed(HERE, dialog, true, false);
        assertFalse("the same pane redrawn keeps it", policy.observe(HERE, "blocked", "claude"));
        assertTrue("a re-attach closes it", policy.observe("1/t1/8/true", "blocked", "claude"));
        policy.sheetClosed();
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, false));
        assertEquals("on the new attach the dialog is new", ElicitationPolicy.Landed.OPEN,
                policy.landed("1/t1/8/true", dialog, true, false));
    }

    @Test public void aReadThatRacedAChangeIsFollowedAtOnceAndNeverOpensTheOldPane() throws Exception {
        ElicitationPolicy policy = blockedClaude();
        Elicitation.Dialog dialog = Elicitation.parse(TOPPINGS);
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, false));
        policy.observe("2/t1/9/true", "blocked", "claude");
        assertEquals("a read is out: remembered, not doubled", ElicitationPolicy.Next.IDLE, policy.check(true, false));
        assertEquals(ElicitationPolicy.Landed.CHECK, policy.landed(HERE, dialog, true, false));
        assertFalse(policy.sheetOpen());
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, false));
        assertEquals(ElicitationPolicy.Landed.OPEN, policy.landed("2/t1/9/true", dialog, true, false));
    }

    @Test public void onlyAPaneThatMayAskAndCanTakeInputIsRead() throws Exception {
        ElicitationPolicy policy = new ElicitationPolicy();
        policy.observe(HERE, null, "claude");
        assertEquals(ElicitationPolicy.Next.IDLE, policy.check(true, false));
        policy.observe(HERE, "working", "claude");
        assertEquals(ElicitationPolicy.Next.IDLE, policy.check(true, false));
        policy.observe(HERE, "blocked", "claude");
        assertEquals("no theme yet: look again soon", ElicitationPolicy.Next.LATER, policy.check(false, false));
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, false));
        assertEquals("the theme went away mid-read", ElicitationPolicy.Landed.LATER,
                policy.landed(HERE, Elicitation.parse(TOPPINGS), false, false));
        policy.observe(HERE, "", "shell");
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, false));
        assertEquals("a plain shell's numbered list is not a dialog", ElicitationPolicy.Landed.LATER,
                policy.landed(HERE, Elicitation.parse(TOPPINGS), true, false));
        policy.readFailed();
    }

    @Test public void theSameQuestionAskedAgainLaterOpensAgain() throws Exception {
        ElicitationPolicy policy = blockedClaude();
        Elicitation.Dialog dialog = Elicitation.parse(TOPPINGS);
        policy.check(true, false);
        policy.landed(HERE, dialog, true, false);
        policy.sheetClosed();
        policy.observe(HERE, "working", "claude");   // answered on the terminal; the agent works
        policy.observe(HERE, "blocked", "claude");   // and asks the same again
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, false));
        assertEquals(ElicitationPolicy.Landed.OPEN, policy.landed(HERE, dialog, true, false));
    }

    @Test public void answersFollowTheSameDialogOnTheSamePaneAcrossAReconnect() throws Exception {
        Elicitation.Dialog dialog = Elicitation.parse(TOPPINGS);
        assertEquals(ElicitationPolicy.answersKey("1/t1/7/true", dialog), ElicitationPolicy.answersKey("2/t1/9/true", dialog));
        assertFalse(ElicitationPolicy.answersKey("1/t1/7/true", dialog).equals(ElicitationPolicy.answersKey("1/t2/7/true", dialog)));
        Elicitation.Dialog other = Elicitation.parse(CODEX);
        assertFalse(ElicitationPolicy.answersKey("1/t1/7/true", dialog).equals(ElicitationPolicy.answersKey("1/t1/7/true", other)));
        Elicitation answers = new Elicitation();
        answers.choose(dialog, 2);
        assertEquals(1, answers.answeredCount());
        answers.clear();
        assertEquals("answered dialogs leave nothing behind", 0, answers.answeredCount());
    }

    @Test public void aKeyboardTheAppRaisedForTheQuestionDoesNotHoldTheSheetBack() {
        assertNull("raised by the app on opening a waiting agent", ElicitationPolicy.busy(true, true, null));
        assertTrue("and the sheet puts it away", ElicitationPolicy.hidesKeyboard(true, true));
        assertEquals("opened by the user", "the keyboard", ElicitationPolicy.busy(true, false, null));
        assertFalse(ElicitationPolicy.hidesKeyboard(true, false));
        assertEquals("a draft always counts", "a draft", ElicitationPolicy.busy(true, true, "a draft"));
        assertEquals("dictation", ElicitationPolicy.busy(false, false, "dictation"));
        assertNull(ElicitationPolicy.busy(false, false, null));
        assertFalse("no keyboard, nothing to hide", ElicitationPolicy.hidesKeyboard(false, true));
        // The policy then reads and opens as for an idle user.
        ElicitationPolicy policy = blockedClaude();
        assertEquals(ElicitationPolicy.Next.READ, policy.check(true, ElicitationPolicy.busy(true, true, null) != null));
    }
}
