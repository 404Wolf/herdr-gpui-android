package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The controls' line in the agent's prompt frame: what shows, and what a narrow grid keeps (items 75, 76). */
public final class ControlLineTest {
    private static final GhostText.Control TALK = new GhostText.Control("talk", "talk", "dim");
    private static final GhostText.Control REC =
            new GhostText.Control("talk", "● rec 0:05 · tap to stop", "danger");
    private static final GhostText.Control REC_SHORT = new GhostText.Control("talk", "● 0:05 stop", "danger");
    private static final GhostText.Control REC_TINY = new GhostText.Control("talk", "● stop", "danger");
    private static final java.util.List<GhostText.Control> RECORDING =
            java.util.Arrays.asList(REC, REC_SHORT, REC_TINY);
    private static final java.util.List<GhostText.Control> IDLE = java.util.Collections.singletonList(TALK);
    private static final java.util.List<GhostText.Control> NONE = java.util.Collections.emptyList();

    private static String ids(java.util.List<GhostText.Control> controls) {
        StringBuilder out = new StringBuilder();
        for (GhostText.Control c : controls) out.append(out.length() == 0 ? "" : " ").append(c.id);
        return out.toString();
    }

    /** The line's state as ComposerBar builds it. */
    private static ControlLine.State state(boolean typing, java.util.List<GhostText.Control> talks, int queued,
            boolean recording, boolean clearable) {
        ControlLine.State in = new ControlLine.State();
        in.typing = typing;
        in.talks = talks;
        in.queued = queued;
        in.recording = recording;
        in.clearable = clearable;
        return in;
    }

    private static java.util.List<GhostText.Control> controls(boolean typing,
            java.util.List<GhostText.Control> talks, int queued, int cols, boolean recording, boolean clearable) {
        return ControlLine.controls(state(typing, talks, queued, recording, clearable), cols);
    }

    private static GhostText.Control byId(java.util.List<GhostText.Control> line, String id) {
        for (GhostText.Control c : line) if (c.id.equals(id)) return c;
        return null;
    }

    @Test public void whileTheKeyboardIsUpTypeIsLeftOutAndNoHerdrHideIsDrawn() {
        // #30: the keyboard's own hide key, Back or a swipe down put it away.
        java.util.List<GhostText.Control> idle = controls(false, IDLE, 0, Integer.MAX_VALUE, false, false);
        assertEquals("type talk send", ids(idle));
        assertEquals("type", idle.get(0).spans[0]);
        java.util.List<GhostText.Control> typing = controls(true, IDLE, 0, Integer.MAX_VALUE, false, false);
        assertEquals("talk send", ids(typing));
        assertEquals("talk clear send queue", ids(controls(true, IDLE, 2, Integer.MAX_VALUE, false, true)));
    }

    @Test public void clearShowsWithADraftADictationOrAQueueAndUndoStandsInItsPlace() {
        // #30's rule (a draft) and #20's (a dictation in flight, item 74; a queue, item 76).
        assertEquals("type talk clear send", ids(controls(false, IDLE, 0, Integer.MAX_VALUE, false, true)));
        assertEquals("type talk send", ids(controls(false, IDLE, 0, Integer.MAX_VALUE, false, false)));
        assertEquals("✕", byId(controls(false, IDLE, 0, Integer.MAX_VALUE, false, true), "clear").spans[0]);
        // While recording "✕" still shows: it clears and cancels (Wolf's pick).
        assertEquals("type clear talk", ids(controls(false, RECORDING, 0, Integer.MAX_VALUE, true, true)));
        // Just cleared and nothing left to clear: "undo" stands in "✕"'s place (#30)...
        ControlLine.State justCleared = state(false, IDLE, 0, false, false);
        justCleared.undoWide = "cleared · undo";
        justCleared.undoNarrow = "undo";
        assertEquals("type talk undo send", ids(ControlLine.controls(justCleared, Integer.MAX_VALUE)));
        // ...while a queue is left, "✕" stays (a second tap clears it) and "undo" follows it (#20).
        ControlLine.State queueLeft = state(false, IDLE, 2, false, true);
        queueLeft.undoWide = "cleared · undo";
        queueLeft.undoNarrow = "undo";
        assertEquals("type talk clear undo send queue", ids(ControlLine.controls(queueLeft, Integer.MAX_VALUE)));
    }

    @Test public void onANarrowGridTypeGivesWayBeforeClear() {
        java.util.List<GhostText.Control> wide = controls(false, IDLE, 0, Integer.MAX_VALUE, false, true);
        int width = ControlLine.width(wide);
        assertEquals("talk clear send", ids(controls(false, IDLE, 0, width + ControlLine.MARGIN - 1, false, true)));
    }

    @Test public void theQueueHasItsOwnControlAndSendIsJustSend() {
        // Item 75: no longer a two-character count glued to "send".
        java.util.List<GhostText.Control> line = controls(false, IDLE, 2, Integer.MAX_VALUE, false, false);
        assertEquals("type talk send queue", ids(line));
        assertEquals(2, byId(line, "send").spans.length);
        assertEquals("2 queued", byId(line, "queue").spans[0]);
        // Without dictation: no talk, the rest stays.
        assertEquals("type send queue", ids(controls(false, NONE, 2, Integer.MAX_VALUE, false, false)));
        // Open, it says so; the colour is the most pressing state's.
        ControlLine.State open = state(false, IDLE, 2, false, false);
        open.expanded = true;
        open.queueStyle = "danger";
        GhostText.Control queue = byId(ControlLine.controls(open, Integer.MAX_VALUE), "queue");
        assertEquals("2 queued ▲", queue.spans[0]);
        assertEquals("danger", queue.spans[1]);
    }

    @Test public void clearShowsOnlyWhenThereIsSomethingToClear() {
        assertEquals("type talk send", ids(controls(false, IDLE, 0, Integer.MAX_VALUE, false, false)));
        java.util.List<GhostText.Control> line = controls(false, IDLE, 0, Integer.MAX_VALUE, false, true);
        assertEquals("type talk clear send", ids(line));
        assertEquals("✕", byId(line, "clear").spans[0]);
    }

    @Test public void theLineIsMeasuredAsTheRendererDrawsIt() {
        // " type ─ talk ─ send ──"
        assertEquals(22, ControlLine.width(controls(false, IDLE, 0, Integer.MAX_VALUE, false, false)));
        // " type ─ talk ─ ✕ ─ send ─ 2 queued ──": the cross and rules are one cell each.
        assertEquals(37, ControlLine.width(controls(false, IDLE, 2, Integer.MAX_VALUE, false, true)));
    }

    @Test public void theCoverScreenLadderNeverDropsClearTheQueueOrTheStop() {
        for (boolean recording : new boolean[] {false, true}) {
            java.util.List<GhostText.Control> talks = recording ? RECORDING : IDLE;
            for (int cols = 5; cols <= 140; cols++) {
                java.util.List<GhostText.Control> line = controls(false, talks, 3, cols, recording, true);
                String at = (recording ? "recording" : "idle") + " at " + cols + ": " + ids(line);
                assertTrue(at, ids(line).contains("clear"));
                assertTrue(at, ids(line).contains("queue"));
                assertTrue(at, ids(line).contains("talk"));
                if (cols >= 30) assertTrue("fits " + at, ControlLine.width(line) + ControlLine.MARGIN <= cols);
            }
        }
        // The cover screen (~52 columns) idle: the short queue word, everything else kept.
        java.util.List<GhostText.Control> cover = controls(false, IDLE, 3, 52, false, true);
        assertEquals("type talk clear send queue", ids(cover));
        // Narrower: the queue takes "≡3", then "type" goes, then send; never ✕ or the queue.
        assertEquals("≡3", byId(controls(false, IDLE, 3, 40, false, true), "queue").spans[0]);
        assertEquals("talk clear queue", ids(controls(false, IDLE, 3, 30, false, true)));
    }

    @Test public void whileRecordingSendIsNotDrawnSoTheStopOwnsTheCorner() {
        for (int cols : new int[] {22, 50, 100}) {
            java.util.List<GhostText.Control> line = controls(false, RECORDING, 2, cols, true, true);
            assertFalse("send at " + cols, ids(line).contains("send"));
            // The stop is last: the right end of the line, the bottom-right corner.
            assertEquals("talk", line.get(line.size() - 1).id);
        }
        java.util.List<GhostText.Control> wide = controls(false, RECORDING, 2, 100, true, true);
        assertEquals("type clear queue talk", ids(wide));
        assertEquals("● rec 0:05 · tap to stop", byId(wide, "talk").spans[0]);
        java.util.List<GhostText.Control> cover = controls(false, RECORDING, 2, 40, true, true);
        assertEquals("● 0:05 stop", byId(cover, "talk").spans[0]);
        assertEquals("≡2", byId(cover, "queue").spans[0]);
    }

    @Test public void typingNarrowGridsKeepTheStopAndNeverDrawAHide() {
        for (int cols : new int[] {100, 50, 38, 30, 22}) {
            java.util.List<GhostText.Control> line = controls(true, RECORDING, 3, cols, true, true);
            assertFalse("no hide at " + cols, ids(line).contains("hide"));
            assertTrue("talk at " + cols, ids(line).contains("talk"));
            assertEquals("the stop last at " + cols, "talk", line.get(line.size() - 1).id);
        }
        assertEquals("talk clear queue", ids(controls(false, RECORDING, 3, 30, false, true)));
    }

    @Test public void undoTakesItsWordsAndGivesWayLastButOne() {
        ControlLine.State in = state(false, IDLE, 2, false, true);
        in.undoWide = "cleared · undo";
        in.undoNarrow = "undo";
        assertEquals("type talk clear undo send queue", ids(ControlLine.controls(in, 120)));
        assertEquals("cleared · undo", byId(ControlLine.controls(in, 120), "undo").spans[0]);
        assertEquals("undo", byId(ControlLine.controls(in, 46), "undo").spans[0]);
        // At the narrowest the undo goes before ✕ and the queue do.
        assertEquals("talk clear queue", ids(ControlLine.controls(in, 20)));
    }

    @Test public void aSelectedRowsActionsKeepTheQueueControlAndTalk() {
        QueueView.Row unsure = new QueueView.Row("m", QueueView.Kind.MESSAGE, "❯ x", "?", "warn", true,
                Outbox.State.UNSURE);
        assertEquals("row-again row-edit row-drop row-back", ids(ControlLine.rowActions(unsure)));
        QueueView.Row queued = new QueueView.Row("m", QueueView.Kind.MESSAGE, "❯ x", "◌1", "tag", true,
                Outbox.State.QUEUED);
        assertEquals("row-edit row-drop row-back", ids(ControlLine.rowActions(queued)));
        QueueView.Row clip = new QueueView.Row("c", QueueView.Kind.CLIP, "⟦dictation c⟧", "◌", "tag", true, null);
        assertEquals("row-drop row-back", ids(ControlLine.rowActions(clip)));
        QueueView.Row parked = new QueueView.Row("p", QueueView.Kind.PARKED, "⟦dictation p⟧", "✗", "warn", true, null);
        assertEquals("row-retry row-drop row-back", ids(ControlLine.rowActions(parked)));
        ControlLine.State in = state(false, IDLE, 1, false, true);
        in.rowActions = ControlLine.rowActions(queued);
        assertEquals("row-edit row-drop row-back queue talk", ids(ControlLine.controls(in, 200)));
        // Recording: the stop stays, last, beside the row's actions, at every width the grid has.
        java.util.List<java.util.List<GhostText.Control>> recordings = java.util.Arrays.asList(
                ControlLine.rowActions(unsure), ControlLine.rowActions(queued), ControlLine.rowActions(parked));
        for (java.util.List<GhostText.Control> acts : recordings) {
            for (int cols = 20; cols <= 200; cols++) {
                ControlLine.State rec = state(false, RECORDING, 3, true, true);
                rec.rowActions = acts;
                java.util.List<GhostText.Control> line = ControlLine.controls(rec, cols);
                assertTrue(cols + ": " + ids(line), line.size() <= 6);
                assertTrue(cols + ": " + ids(line), line.get(line.size() - 1).id.equals("talk"));
                assertTrue(cols + ": the queue control stays", byId(line, "queue") != null);
                assertTrue(cols + ": " + ids(line), byId(line, "row-drop") != null);
            }
        }
    }

    /** The line this ladder picks at every width from 5 to 140, idle and recording, as a table. */
    static String widthTable() {
        StringBuilder out = new StringBuilder();
        for (String state : new String[] {"idle", "recording"}) {
            for (int cols = 5; cols <= 140; cols++) {
                boolean rec = state.equals("recording");
                java.util.List<GhostText.Control> line = controls(false, rec ? RECORDING : IDLE, 2, cols, rec, true);
                out.append(cols).append('\t').append(state);
                for (GhostText.Control c : line) {
                    StringBuilder text = new StringBuilder();
                    for (int i = 0; i < c.spans.length; i += 2) text.append(c.spans[i]);
                    out.append('\t').append(c.id).append('=').append(text);
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * The renderer's test draws this table at every width and checks ✕, the queue and talk are all
     * there (gpui-rio ghost.rs, the_hosts_line_at_every_width_keeps_clear_the_queue_and_talk). The
     * table is kept beside it; this fails when the ladder changes without it (rerun with
     * -DcontrolWidths.write=true to write it).
     */
    @Test public void theRenderersWidthTableIsThisLadders() throws Exception {
        java.io.File table = new java.io.File("../rust/vendor/gpui-rio/src/testdata/control_widths.tsv");
        String now = widthTable();
        if (Boolean.getBoolean("controlWidths.write")) {
            table.getParentFile().mkdirs();
            java.nio.file.Files.write(table.toPath(), now.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String kept = new String(java.nio.file.Files.readAllBytes(table.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("ghost.rs's width table is out of date", kept, now);
        // And the ladder itself never leaves out ✕, the queue or talk.
        for (String row : now.split("\n")) {
            assertTrue(row, row.contains("\tclear=") && row.contains("\tqueue=") && row.contains("\ttalk="));
        }
    }

    @Test public void theRendererTakesSixControls() {
        ControlLine.State in = state(false, IDLE, 2, false, true);
        in.undoWide = in.undoNarrow = "undo";
        assertTrue(ControlLine.controls(in, 200).size() <= 6);
    }

    @Test public void everyControlIsDrawnByThePhonesFontNeverAsABox() {
        for (int cols : new int[] {200, 80, 60, 48, 40, 30}) {
            for (boolean recording : new boolean[] {false, true}) {
                for (boolean expanded : new boolean[] {false, true}) {
                    ControlLine.State in = state(!recording, recording ? RECORDING : IDLE, 12, recording, true);
                    in.expanded = expanded;
                    in.undoWide = "cleared · undo";
                    in.undoNarrow = "undo";
                    for (GhostText.Control c : ControlLine.controls(in, cols)) {
                        for (int i = 0; i < c.spans.length; i += 2) {
                            assertEquals(cols + " cols: " + c.spans[i], "", GridGlyphs.boxes(c.spans[i]));
                        }
                    }
                }
            }
        }
    }
}
