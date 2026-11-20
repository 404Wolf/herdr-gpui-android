package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Review of #48 round 2: the bridge's real limit, Cancel by position, and a late final's own host. */
public final class KbTalkRound3Test {
    private static final GhostText.Colors COLORS = new GhostText.Colors(
            0xffB2CCD6, 0xff4A4A4A, 0xffFFCB6B, 0xff000000, 0xffEEFFFF, 0xffF07178, 0xff82AAFF);

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/java/dev/herdr/android/" + name + ".java").toPath()),
                StandardCharsets.UTF_8);
    }

    /** What the composer hands the native bridge for one dictation state, as JSON (ghost_bridge.rs parses it). */
    private static String bridgeJson(DictationEngine.State s, int queued, boolean expanded, boolean attach, boolean undo, int cols) {
        ControlLine.State in = new ControlLine.State();
        in.talks = ComposerBar.talks(s, "0:05", false, false, false, null);
        in.recording = ComposerBar.recording(s);
        in.dictating = s != DictationEngine.State.IDLE;
        in.clearable = true;
        in.queued = queued;
        in.expanded = expanded;
        in.attach = attach;
        if (undo) {
            in.undoWide = "cleared \u00b7 undo";
            in.undoNarrow = "undo";
        }
        String json = GhostText.json("claude", "draft", GhostText.Marks.NONE, new int[0], Collections.<String, Long>emptyMap(),
                Collections.<String>emptySet(), Collections.<QueueView.Row>emptyList(), false, null, cols,
                ControlLine.controls(in, cols), COLORS);
        return GhostText.withRungs(json, ControlLine.ladder(in));
    }

    @Test public void everyDictationUpdateFitsTheNativeBridgesLimitOfEightAlternatives() {
        DictationEngine.State[] states = {DictationEngine.State.CONNECTING, DictationEngine.State.LISTENING,
            DictationEngine.State.TRANSCRIBING, DictationEngine.State.ON_DEVICE, DictationEngine.State.OFFLINE_RECORDING};
        for (DictationEngine.State s : states) {
            for (int queued : new int[] {0, 3, 12}) {
                for (boolean expanded : new boolean[] {false, true}) {
                    for (boolean attach : new boolean[] {false, true}) {
                        for (boolean undo : new boolean[] {false, true}) {
                            String json = bridgeJson(s, queued, expanded, attach, undo, 98);
                            int rungs = rungsIn(json);
                            assertTrue(s + " q=" + queued + " attach=" + attach + ": " + rungs + " alternatives",
                                    rungs <= 8);
                        }
                    }
                }
            }
        }
    }

    private static int rungsIn(String json) {
        try {
            return new org.json.JSONObject(json).getJSONArray("rungs").length();
        } catch (org.json.JSONException bad) {
            throw new AssertionError(bad);
        }
    }

    @Test public void theLaddersTheRealBridgeParsesAreTheOnesJavaEmits() throws Exception {
        // ghost_bridge.rs reads this file through its own parse(): the real limit, not a Java replay.
        StringBuilder all = new StringBuilder();
        DictationEngine.State[] states = {DictationEngine.State.CONNECTING, DictationEngine.State.LISTENING,
            DictationEngine.State.TRANSCRIBING, DictationEngine.State.ON_DEVICE, DictationEngine.State.OFFLINE_RECORDING};
        for (DictationEngine.State s : states) {
            for (int queued : new int[] {0, 3, 12}) {
                for (boolean attach : new boolean[] {false, true}) {
                    for (boolean undo : new boolean[] {false, true}) {
                        all.append(bridgeJson(s, queued, queued > 5, attach, undo, 98)).append('\n');
                    }
                }
            }
        }
        File file = new File("../rust/src/testdata/dictation_ladders.jsonl");
        if (Boolean.getBoolean("controlWidths.write")) {
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), all.toString().getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("rust/src/testdata/dictation_ladders.jsonl is out of date",
                new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), all.toString());
    }

    @Test public void cancelRemovesTheDictatedSpanNotAnythingTypedAfterIt() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.edit("hi");
        drafts.dictationStarted();
        drafts.dictationPartial("ok");
        String typedAfter = drafts.text() + "!";
        drafts.edit(typedAfter);
        assertEquals("hi!", drafts.dictationCancelled());
    }

    @Test public void aNewPartialKeepsWhatWasTypedAroundTheDictatedSpan() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.edit("hi");
        drafts.dictationStarted();
        drafts.dictationPartial("ok");
        drafts.edit(drafts.text() + "!");
        assertEquals("hi ok2!", drafts.dictationPartial("ok2"));
        assertEquals("hi ok2 done!", drafts.dictationFinal("ok2 done"));
    }

    @Test public void cancelRemovesTheSpokenOccurrenceWhenTheTypedTextEndsInTheSameWords() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.edit("go now");
        drafts.dictationStarted();
        drafts.dictationPartial("go now");
        // Typed after the dictated words, ending in the same words: it is the typed copy that stays.
        drafts.edit(drafts.text() + " and go now");
        assertEquals("go now and go now", drafts.dictationCancelled());
        ComposerDrafts again = new ComposerDrafts();
        again.switchTo("t1");
        again.edit("x");
        again.dictationStarted();
        again.dictationPartial("a b");
        again.edit("x a b a b");
        // Cancel takes the first (spoken) "a b", leaving the typed one: the result differs from taking the last.
        assertEquals("x a b", again.dictationCancelled());
    }

    @Test public void switchingAwayWhileListeningTakesThePartialOutOfTheOriginalDraft() throws Exception {
        String bar = source("ComposerBar");
        int from = bar.indexOf("} else if (switched && dictating()) {");
        assertTrue(from > 0);
        String branch = bar.substring(from, bar.indexOf("drafts.setHost(", from));
        assertTrue(branch.contains("drafts.dictationCancelled()"));
        assertFalse(branch.contains("drafts.dictationEnded()"));
    }

    /** The composer, as far as a late final is concerned: where its text lands. */
    private static final class Composer implements DictationRouting.Bar {
        final ComposerDrafts drafts = new ComposerDrafts();
        final DictationRouting routing = new DictationRouting(drafts, this);
        @Override public void showText(String text) { }
        @Override public void setDictationState(DictationEngine.State s) { }
        @Override public void enter() { }
        @Override public void notice(String text) { }
        @Override public void appendDictation(String pane, String text) { drafts.append(pane, text); }
        @Override public void clipLength(String clipId, long durationMs) { }
        @Override public void transcribedOnPhone() { }
    }

    @Test public void aLateFinalNeverLandsInAnotherHostsDraftOfTheSamePaneId() {
        Composer c = new Composer();
        c.drafts.setHost("hostA");
        c.drafts.switchTo("t1");
        DictationEngine.Listener session = c.routing.listener("t1");
        c.drafts.setHost("hostB");
        c.drafts.switchTo("t1");
        session.onFinal("from A", false);
        assertEquals("host B's t1 is untouched", "", c.drafts.text());
    }
}
