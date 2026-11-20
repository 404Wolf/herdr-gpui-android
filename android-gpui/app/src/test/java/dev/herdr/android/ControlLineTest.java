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

    /** talk's words while recording, as ComposerBar gives them: status words, then the slot's. */
    private static final java.util.List<GhostText.Control> LISTENING = ComposerBar.talks(
            dev.herdr.android.dictation.DictationEngine.State.LISTENING, "0:05", false, false, false, null);

    @Test public void theLineEndsInThreeFixedSlotsTypeTalkSend() {
        // Wolf's ask 4 (11-17): the buttons keep their places; everything else is left of them.
        java.util.List<GhostText.Control> idle = controls(false, IDLE, 0, Integer.MAX_VALUE, false, false);
        assertEquals("type talk send", ids(idle));
        assertEquals("type", idle.get(0).spans[0]);
        assertEquals("talk", byId(idle, "talk").spans[0]);
        // Ask 9: while the keyboard is up the same slot, the same width, says "hide" and puts it away.
        java.util.List<GhostText.Control> typing = controls(true, IDLE, 0, Integer.MAX_VALUE, false, false);
        assertEquals("type talk send", ids(typing));
        assertEquals("hide", typing.get(0).spans[0]);
        assertTrue(ControlLine.tappable("type"));
        // Ask 10: recording keeps "send" (stop and send) and adds "cancel".
        java.util.List<GhostText.Control> rec = controls(false, LISTENING, 0, Integer.MAX_VALUE, true, true);
        assertEquals("cancel clear status type talk send", ids(rec));
        assertEquals("stop", byId(rec, "talk").spans[0]);
        assertEquals("rec 0:05", byId(rec, "status").spans[0]);
        assertFalse(ControlLine.tappable("status"));
    }

    @Test public void clearShowsWithADraftADictationOrAQueueAndUndoStandsInItsPlace() {
        // #30's rule (a draft) and #20's (a dictation in flight, item 74; a queue, item 76).
        assertEquals("clear type talk send", ids(controls(false, IDLE, 0, Integer.MAX_VALUE, false, true)));
        assertEquals("type talk send", ids(controls(false, IDLE, 0, Integer.MAX_VALUE, false, false)));
        // Ask 14: the wide line says "clear" in words; the narrow ones keep the cross.
        assertEquals("clear", byId(controls(false, IDLE, 0, Integer.MAX_VALUE, false, true), "clear").spans[0]);
        assertEquals("✕", byId(controls(false, IDLE, 0, 32, false, true), "clear").spans[0]);
        // While recording "cancel" leads and "✕" stays beside it: it still clears and cancels.
        assertTrue(ids(controls(false, LISTENING, 0, Integer.MAX_VALUE, true, true)).startsWith("cancel clear "));
        // Just cleared and nothing left to clear: "undo" stands in "✕"'s place (#30)...
        ControlLine.State justCleared = state(false, IDLE, 0, false, false);
        justCleared.undoWide = "cleared · undo";
        justCleared.undoNarrow = "undo";
        assertEquals("undo type talk send", ids(ControlLine.controls(justCleared, Integer.MAX_VALUE)));
        // ...while a queue is left, "✕" stays (a second tap clears it) and "undo" follows it (#20).
        ControlLine.State queueLeft = state(false, IDLE, 2, false, true);
        queueLeft.undoWide = "cleared · undo";
        queueLeft.undoNarrow = "undo";
        assertEquals("clear undo queue type talk send", ids(ControlLine.controls(queueLeft, Integer.MAX_VALUE)));
    }

    @Test public void theQueueHasItsOwnControlAndSendIsJustSend() {
        // Item 75: no longer a two-character count glued to "send".
        java.util.List<GhostText.Control> line = controls(false, IDLE, 2, Integer.MAX_VALUE, false, false);
        assertEquals("queue type talk send", ids(line));
        assertEquals(2, byId(line, "send").spans.length);
        assertEquals("2 queued", byId(line, "queue").spans[0]);
        // Without dictation: no talk, the rest stays.
        assertEquals("queue type send", ids(controls(false, NONE, 2, Integer.MAX_VALUE, false, false)));
        // Open, it says so; the colour is the most pressing state's.
        ControlLine.State open = state(false, IDLE, 2, false, false);
        open.expanded = true;
        open.queueStyle = "danger";
        GhostText.Control queue = byId(ControlLine.controls(open, Integer.MAX_VALUE), "queue");
        assertEquals("2 queued ▲", queue.spans[0]);
        assertEquals("danger", queue.spans[1]);
    }

    @Test public void theLineIsMeasuredAsTheRendererDrawsIt() {
        // " type ─ talk ─ send ──": talk's slot is four cells.
        assertEquals(22, ControlLine.width(controls(false, IDLE, 0, Integer.MAX_VALUE, false, false)));
        // " clear ─ 2 queued ─ type ─ talk ─ send ──": "clear" is five cells (the narrow lines keep ✕).
        assertEquals(41, ControlLine.width(controls(false, IDLE, 2, Integer.MAX_VALUE, false, true)));
        // A padded word: "…" in the middle of talk's four cells.
        assertEquals(" …  ", ControlLine.padded(new String[] {"…", "info"}, ControlLine.TALK_SLOT)[0]);
    }

    @Test public void theCoverScreenLadderNeverDropsClearTheQueueOrTheStop() {
        for (boolean recording : new boolean[] {false, true}) {
            java.util.List<GhostText.Control> talks = recording ? LISTENING : IDLE;
            for (int cols = 5; cols <= 140; cols++) {
                java.util.List<GhostText.Control> line = controls(false, talks, 3, cols, recording, true);
                String at = (recording ? "recording" : "idle") + " at " + cols + ": " + ids(line);
                if (recording) {
                    // Dictating: hide, cancel, stop and send are the priorities; the cross and the queue give way.
                    assertTrue(at, ids(line).contains("cancel") && ids(line).contains("type") && ids(line).contains("send"));
                } else {
                    assertTrue(at, ids(line).contains("clear"));
                    assertTrue(at, ids(line).contains("queue"));
                }
                assertTrue(at, ids(line).contains("talk"));
                if (cols >= 39) assertTrue("fits " + at, ControlLine.width(line) + ControlLine.MARGIN <= cols);
            }
        }
        // The cover screen (~46 columns) recording: the status words shorten, then go.
        assertEquals("0:05", byId(controls(false, LISTENING, 3, 62, true, true), "status").spans[0]);
        assertEquals("cancel type talk send", ids(controls(false, LISTENING, 3, 41, true, true)));
        assertEquals("≡3", byId(controls(false, IDLE, 3, 41, false, true), "queue").spans[0]);
    }

    @Test public void undoTakesItsWordsAndGivesWayBeforeClearAndTheQueue() {
        ControlLine.State in = state(false, IDLE, 2, false, true);
        in.undoWide = "cleared · undo";
        in.undoNarrow = "undo";
        assertEquals("clear undo queue type talk send", ids(ControlLine.controls(in, 120)));
        assertEquals("cleared · undo", byId(ControlLine.controls(in, 120), "undo").spans[0]);
        assertEquals("undo", byId(ControlLine.controls(in, 50), "undo").spans[0]);
        // At the narrowest the undo goes before ✕ and the queue do.
        assertEquals("clear queue type talk send", ids(ControlLine.controls(in, 20)));
    }

    @Test public void wolfAsk4_aSelectedRowsActionsGoOnItsRowNeverInTheLine() {
        QueueView.Row unsure = new QueueView.Row("m", QueueView.Kind.MESSAGE, "❯ x", "?", "warn", true,
                Outbox.State.NEEDS_YOU);
        assertEquals("row-again row-edit row-drop row-back", ids(ControlLine.rowActions(unsure)));
        QueueView.Row queued = new QueueView.Row("m", QueueView.Kind.MESSAGE, "❯ x", "◌1", "tag", true,
                Outbox.State.PENDING);
        assertEquals("row-edit row-drop row-back", ids(ControlLine.rowActions(queued)));
        QueueView.Row clip = new QueueView.Row("c", QueueView.Kind.CLIP, "⟦dictation c⟧", "◌", "tag", true, null);
        assertEquals("row-drop row-back", ids(ControlLine.rowActions(clip)));
        QueueView.Row parked = new QueueView.Row("p", QueueView.Kind.PARKED, "⟦dictation p⟧", "✗", "warn", true, null);
        assertEquals("row-retry row-drop row-back", ids(ControlLine.rowActions(parked)));
        // The rungs the renderer picks from on the row: all, then without "back", then (an
        // unsure message) "again" for "send again"; every action but "back" in every rung.
        java.util.List<java.util.List<GhostText.Control>> u = ControlLine.rowRungs(unsure);
        assertEquals(3, u.size());
        assertEquals("row-again row-edit row-drop", ids(u.get(1)));
        assertEquals("again", byId(u.get(2), "row-again").spans[0]);
        for (QueueView.Row row : new QueueView.Row[] {queued, clip, parked}) {
            java.util.List<java.util.List<GhostText.Control>> r = ControlLine.rowRungs(row);
            assertEquals(2, r.size());
            assertEquals(ids(ControlLine.rowActions(row)).replace(" row-back", ""), ids(r.get(1)));
        }
        // The narrowest fits beside a few of the row's words on the narrowest cover screen.
        assertTrue(ControlLine.width(u.get(2)) - 3 + 8 <= 39);
        // No line of the controls carries a row's action, whatever the state.
        ControlLine.State in = state(false, LISTENING, 3, true, true);
        in.expanded = true;
        for (java.util.List<GhostText.Control> line : ControlLine.ladder(in)) {
            assertFalse(ids(line), ids(line).contains("row-"));
        }
    }

    @Test public void everyLineOfTheLadderEndsInTheSameSlots() {
        ControlLine.State in = state(false, LISTENING, 3, true, true);
        in.undoWide = "cleared · undo";
        in.undoNarrow = "undo";
        java.util.List<java.util.List<GhostText.Control>> ladder = ControlLine.ladder(in);
        assertTrue(ladder.size() >= 3);
        int last = Integer.MAX_VALUE;
        for (java.util.List<GhostText.Control> line : ladder) {
            assertTrue(ids(line), ids(line).endsWith("type talk send"));
            assertTrue("widest first", ControlLine.width(line) <= last);
            last = ControlLine.width(line);
        }
    }

    /** The line this ladder picks at every width from 5 to 140, idle and recording, as a table. */
    static String widthTable() {
        StringBuilder out = new StringBuilder();
        for (String state : new String[] {"idle", "recording"}) {
            for (int cols = 5; cols <= 140; cols++) {
                boolean rec = state.equals("recording");
                java.util.List<GhostText.Control> line = controls(false, rec ? LISTENING : IDLE, 2, cols, rec, true);
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
            if (row.contains("\trecording\t")) {
                // While dictating: cancel, hide (type), stop and send lead the priorities.
                assertTrue(row, row.contains("\tcancel=") && row.contains("\ttype=") && row.contains("\ttalk=") && row.contains("\tsend="));
            } else {
                assertTrue(row, row.contains("\tclear=") && row.contains("\tqueue=") && row.contains("\ttalk="));
            }
        }
    }

    @Test public void theRendererTakesTenControls() {
        ControlLine.State in = state(false, LISTENING, 2, true, true);
        in.undoWide = in.undoNarrow = "undo";
        assertTrue(ControlLine.controls(in, 200).size() <= 10);
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
