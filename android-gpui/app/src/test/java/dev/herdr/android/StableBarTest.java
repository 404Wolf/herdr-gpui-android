package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine;
import dev.herdr.android.dictation.DictationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Wolf's ask 4 (11-17): the native bottom bar (talk and the keyboard's "type", with "send")
 * stops jumping around. The buttons keep the same cells, and so the same touch bounds, in every
 * state and across every transition: the keyboard shown or hidden, a dictation started and
 * stopped, an undo appearing and expiring, the queue going from 0 to N, offline and online, and
 * the grid folded or unfolded (the same place from its right edge).
 */
public final class StableBarTest {
    /** talk's words in a state, fullest first, as the composer gives them (ComposerBar.talks). */
    private static List<GhostText.Control> talks(String style, String... words) {
        List<GhostText.Control> out = new ArrayList<>();
        for (String w : words) out.add(new GhostText.Control("talk", w, style));
        return out;
    }

    private static final List<GhostText.Control> IDLE = talks("dim", "talk");
    private static final List<GhostText.Control> CONNECTING = talks("info", "connecting…", "…");
    private static final List<GhostText.Control> RECORDING = talks("danger", "rec 0:05", "0:05", "stop");
    private static final List<GhostText.Control> TRANSCRIBING = talks("info", "transcribing…", "…");
    private static final List<GhostText.Control> ON_PHONE = talks("warn", "offline · rec 0:05", "offline 0:05",
            "stop");
    private static final List<GhostText.Control> FOR_LATER = talks("warn", "desktop error · queued · rec 0:05",
            "queued 0:05", "stop");
    private static final List<GhostText.Control> NOTHING_HEARD = talks("warn", "offline · mic sent silence",
            "offline · silence", "talk");

    @Test public void theseAreTheComposersOwnWords() {
        assertEquals(words(RECORDING), words(ComposerBar.talks(DictationEngine.State.LISTENING, "0:05", false, false,
                false, null)));
        assertEquals(words(FOR_LATER), words(ComposerBar.talks(DictationEngine.State.OFFLINE_RECORDING, "0:05",
                false, true, false, null)));
        assertEquals(words(NOTHING_HEARD), words(ComposerBar.talks(DictationEngine.State.IDLE, "0:05", false, false,
                true, DictationException.Reason.SILENT_AUDIO)));
        assertEquals(words(IDLE), words(ComposerBar.talks(DictationEngine.State.IDLE, "0:05", false, false, false,
                null)));
    }

    private static String words(List<GhostText.Control> talks) {
        StringBuilder out = new StringBuilder();
        for (GhostText.Control c : talks) out.append(c.spans[0]).append('|');
        return out.toString();
    }

    private static ControlLine.State state(boolean typing, List<GhostText.Control> talks, boolean recording,
            boolean clearable, int queued, boolean undo, boolean open) {
        ControlLine.State in = new ControlLine.State();
        in.typing = typing;
        in.talks = talks;
        in.recording = recording;
        in.clearable = clearable;
        in.queued = queued;
        in.expanded = open;
        if (undo) {
            in.undoWide = "cleared · undo";
            in.undoNarrow = "undo";
        }
        return in;
    }

    /** Every state the line takes, in the order of the transitions Wolf named. */
    private static Map<String, ControlLine.State> states() {
        Map<String, ControlLine.State> out = new LinkedHashMap<>();
        out.put("idle", state(false, IDLE, false, false, 0, false, false));
        out.put("a draft (✕ appears)", state(false, IDLE, false, true, 0, false, false));
        out.put("keyboard shown", state(true, IDLE, false, true, 0, false, false));
        out.put("keyboard hidden", state(false, IDLE, false, true, 0, false, false));
        out.put("dictation connecting",
                state(false, CONNECTING, true, true, 0, false, false));
        out.put("dictation recording",
                state(false, RECORDING, true, true, 0, false, false));
        out.put("dictation transcribing",
                state(false, TRANSCRIBING, false, true, 0, false, false));
        out.put("dictation stopped", state(false, IDLE, false, true, 0, false, false));
        out.put("undo appears", state(false, IDLE, false, false, 0, true, false));
        out.put("undo expires", state(false, IDLE, false, false, 0, false, false));
        out.put("queue 0", state(false, IDLE, false, true, 0, false, false));
        out.put("queue 3", state(false, IDLE, false, true, 3, false, false));
        out.put("queue 120 open", state(false, IDLE, false, true, 120, false, true));
        out.put("queue + undo", state(false, IDLE, false, true, 3, true, false));
        out.put("offline, recording on the phone",
                state(false, ON_PHONE, true, true, 3, false, false));
        out.put("offline, recording for later",
                state(false, FOR_LATER, true, true, 3, false,
                        false));
        out.put("offline text, nothing heard",
                state(false, NOTHING_HEARD,
                        false, true, 0, false, false));
        out.put("online again", state(false, IDLE, false, true, 0, false, false));
        out.put("typing while recording",
                state(true, RECORDING, true, true, 3, true, false));
        return out;
    }

    /** The cells each slot takes, counted from the grid's right edge, as the renderer draws the line. */
    private static Map<String, int[]> slots(List<GhostText.Control> line, int cols) {
        Map<String, int[]> out = new LinkedHashMap<>();
        int col = cols - ControlLine.width(line);
        for (int i = 0; i < line.size(); i++) {
            col += i == 0 ? 1 : 3;
            int start = col;
            GhostText.Control c = line.get(i);
            for (int s = 0; s + 1 < c.spans.length; s += 2) col += c.spans[s].codePointCount(0, c.spans[s].length());
            String slot = c.id.equals("gap-type") ? "type" : c.id.equals("gap-send") ? "send" : c.id;
            if (slot.equals("type") || slot.equals("talk") || slot.equals("send")) {
                out.put(slot, new int[] {cols - start, cols - col});
            }
        }
        return out;
    }

    private static void sameSlots(int cols) {
        Map<String, int[]> first = null;
        String firstName = null;
        for (Map.Entry<String, ControlLine.State> e : states().entrySet()) {
            List<GhostText.Control> line = ControlLine.controls(e.getValue(), cols);
            Map<String, int[]> now = slots(line, cols);
            if (first == null) {
                first = now;
                firstName = e.getKey();
                continue;
            }
            for (String slot : new String[] {"type", "talk", "send"}) {
                int[] was = first.get(slot), is = now.get(slot);
                assertNotNull(cols + " cols, " + e.getKey() + ": no " + slot + " slot", is);
                assertEquals(cols + " cols, " + firstName + " -> " + e.getKey() + ": " + slot + " moved",
                        was[0] + ".." + was[1], is[0] + ".." + is[1]);
            }
        }
    }

    @Test public void wolfAsk4_theButtonsKeepTheirCellsInEveryStateOnTheCoverScreen() {
        sameSlots(46);
    }

    @Test public void wolfAsk4_theButtonsKeepTheirCellsInEveryStateUnfolded() {
        sameSlots(98);
    }

    @Test public void wolfAsk4_foldingKeepsTheButtonsTheSameFromTheRightEdge() {
        // Each width a fold, an unfold or a zoom gives, from the cover screen's up.
        Map<String, int[]> cover = slots(ControlLine.controls(states().get("idle"), 46), 46);
        for (int cols = 40; cols <= 140; cols++) {
            for (Map.Entry<String, ControlLine.State> e : states().entrySet()) {
                Map<String, int[]> now = slots(ControlLine.controls(e.getValue(), cols), cols);
                for (String slot : new String[] {"type", "talk", "send"}) {
                    int[] was = cover.get(slot), is = now.get(slot);
                    assertNotNull(cols + " cols, " + e.getKey() + ": no " + slot, is);
                    assertEquals(cols + " cols, " + e.getKey() + ": " + slot,
                            was[0] + ".." + was[1], is[0] + ".." + is[1]);
                }
            }
        }
    }

    /** The line's cells on row 20 as the renderer draws them, right-aligned on {@code cols}. */
    private static List<ControlTargets.Cells> drawn(List<GhostText.Control> line, int cols) {
        List<ControlTargets.Cells> cells = new ArrayList<>();
        int col = cols - ControlLine.width(line);
        for (int i = 0; i < line.size(); i++) {
            col += i == 0 ? 1 : 3;
            int start = col;
            GhostText.Control c = line.get(i);
            for (int s = 0; s + 1 < c.spans.length; s += 2) col += c.spans[s].codePointCount(0, c.spans[s].length());
            cells.add(new ControlTargets.Cells(c.id, 20, start, col));
        }
        return cells;
    }

    /** The slot a target is ("type" for "gap-type", "send" for "gap-send"), or null. */
    private static String slotOf(String id) {
        if (id.equals("type") || id.equals("gap-type")) return "type";
        if (id.equals("send") || id.equals("gap-send")) return "send";
        return id.equals("talk") ? "talk" : null;
    }

    @Test public void wolfAsk4_theButtonsTouchBoundsNeverMoveAtCompactAndWideCells() {
        // The finger's bounds, not only the words: the targets the composer lays over them, an
        // empty slot's included, at the compact 5.9 dp cell and a wide 9 dp one. While recording
        // the stop owns the corner (its own, larger, bounds): that is the one exception.
        for (float cw : new float[] {5.9f, 9f}) {
            for (int cols : new int[] {46, 98}) {
                float ch = 22f;
                Map<String, String> first = new LinkedHashMap<>();
                for (Map.Entry<String, ControlLine.State> e : states().entrySet()) {
                    boolean recording = e.getValue().recording;
                    List<GhostText.Control> line = ControlLine.controls(e.getValue(), cols);
                    for (ControlTargets.Rect r : ControlTargets.layout(drawn(line, cols), 0, 0, cw, ch, 20, 24 * ch,
                            cols * cw, recording)) {
                        String slot = slotOf(r.id);
                        if (slot == null || (recording && !slot.equals("type"))) continue;
                        String bounds = r.left + ".." + r.right + " x " + r.top + ".." + r.bottom;
                        String was = first.putIfAbsent(slot, bounds);
                        if (was != null) {
                            assertEquals(cw + " dp, " + cols + " cols, " + e.getKey() + ": the " + slot
                                    + " target moved", was, bounds);
                        }
                    }
                }
                assertEquals(cw + " dp " + cols + " cols", 3, first.size());
            }
        }
    }

    @Test public void wolfAsk4_anEmptySlotTakesItsWholePlaceAndNoNeighbourGrowsIntoIt() {
        float cw = 5.9f, ch = 22f;
        int cols = 46;
        ControlLine.State typing = states().get("keyboard shown");
        List<ControlTargets.Rect> rects = ControlTargets.layout(drawn(ControlLine.controls(typing, cols), cols), 0, 0,
                cw, ch, 20, 24 * ch, cols * cw, false);
        ControlTargets.Rect gap = null, talk = null;
        for (ControlTargets.Rect r : rects) {
            if (r.id.equals("gap-type")) gap = r;
            if (r.id.equals("talk")) talk = r;
        }
        assertNotNull("the empty type slot has a target", gap);
        assertFalse("it does nothing and is no button", ControlLine.tappable(gap.id));
        // Every point of the slot's words is in it, none in talk's.
        for (float x = gap.labelLeft; x < gap.labelRight; x += 0.5f) {
            assertTrue(x >= gap.left && x < gap.right);
            assertFalse(x >= talk.left && x < talk.right);
        }
        assertEquals("talk starts where the empty slot ends", gap.right, talk.left, 0.001f);
    }

    @Test public void wolfAsk4_nineControlsAllGetTargetsThroughTheHint() {
        // A selected unsure message with the offline status on the unfolded grid: nine controls.
        ControlLine.State in = state(false, NOTHING_HEARD, false, true, 1, false, true);
        in.rowActions = ControlLine.rowActions(new QueueView.Row("m", QueueView.Kind.MESSAGE, "❯ x", "?", "warn",
                true, Outbox.State.UNSURE));
        int cols = 98;
        List<GhostText.Control> line = ControlLine.controls(in, cols);
        assertTrue(line.size() >= 9);
        StringBuilder json = new StringBuilder("{\"left\":0,\"top\":0,\"cellWidth\":9,\"cellHeight\":22,\"cols\":98,"
                + "\"rows\":24,\"busy\":[],\"busyFrom\":24,\"controls\":[");
        List<ControlTargets.Cells> cells = drawn(line, cols);
        for (int i = 0; i < cells.size(); i++) {
            ControlTargets.Cells c = cells.get(i);
            json.append(i == 0 ? "" : ",").append("{\"id\":\"").append(c.id).append("\",\"row\":").append(c.row)
                    .append(",\"start\":").append(c.start).append(",\"end\":").append(c.end).append('}');
        }
        json.append("]}");
        ClusterPlacement.Hint hint = ClusterPlacement.Hint.parse(json.toString());
        assertNotNull(hint);
        assertEquals("every drawn control reaches the composer", cells.size(), hint.controls.size());
        List<ControlTargets.Rect> rects = ControlTargets.layout(hint.controls, 0, 0, 9, 22, 20, 24 * 22, 98 * 9, false);
        ControlTargets.Rect send = null;
        for (ControlTargets.Rect r : rects) if (r.id.equals("send")) send = r;
        assertNotNull("send has its target", send);
        // And a named activation (TalkBack's double tap) on it sends.
        List<String> acted = new ArrayList<>();
        new ComposerBar.GuardedTarget("send", new TapGuard(), acted::add).onClick(null);
        assertEquals("send", acted.get(0));
    }
}
