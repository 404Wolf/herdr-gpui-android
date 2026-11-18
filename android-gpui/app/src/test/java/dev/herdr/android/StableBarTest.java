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

    @Test public void wolfAsk4_theFullestLineAndASelectedRowAllGetTargetsThroughTheHint() {
        // The fullest line (✕, undo, the queue, the offline status and the slots) with a selected
        // unsure message on the unfolded grid: its actions come in the hint's own list, on its row.
        ControlLine.State in = state(false, NOTHING_HEARD, false, true, 1, true, true);
        int cols = 98;
        List<GhostText.Control> line = ControlLine.controls(in, cols);
        assertTrue(ControlLine.width(line) + ControlLine.MARGIN <= cols);
        assertTrue(line.size() <= ControlLine.MAX_CONTROLS);
        StringBuilder json = new StringBuilder("{\"left\":0,\"top\":0,\"cellWidth\":9,\"cellHeight\":22,\"cols\":98,"
                + "\"rows\":24,\"busy\":[],\"busyFrom\":24,\"controls\":[");
        List<ControlTargets.Cells> cells = drawn(line, cols);
        for (int i = 0; i < cells.size(); i++) {
            ControlTargets.Cells c = cells.get(i);
            json.append(i == 0 ? "" : ",").append("{\"id\":\"").append(c.id).append("\",\"row\":").append(c.row)
                    .append(",\"start\":").append(c.start).append(",\"end\":").append(c.end).append('}');
        }
        // " send again ─ edit ─ drop ─ back" at the right end of row 17, the row's words before it.
        json.append("],\"echoes\":[{\"id\":\"echo-m\",\"row\":17,\"start\":0,\"end\":66}],\"rowActions\":["
                + "{\"id\":\"row-again\",\"row\":17,\"start\":67,\"end\":77},"
                + "{\"id\":\"row-edit\",\"row\":17,\"start\":80,\"end\":84},"
                + "{\"id\":\"row-drop\",\"row\":17,\"start\":87,\"end\":91},"
                + "{\"id\":\"row-back\",\"row\":17,\"start\":94,\"end\":98}]}");
        ClusterPlacement.Hint hint = ClusterPlacement.Hint.parse(json.toString());
        assertNotNull(hint);
        assertEquals("every drawn control reaches the composer", cells.size(), hint.controls.size());
        assertEquals("and every row action", 4, hint.rowActions.size());
        List<ControlTargets.Rect> rects = new ArrayList<>(ControlTargets.layout(hint.controls, 0, 0, 9, 22, 20,
                24 * 22, 98 * 9, false));
        rects.addAll(ControlTargets.rows(hint.echoes, hint.rowActions, 0, 0, 9, 22, 98));
        Map<String, ControlTargets.Rect> byId = new LinkedHashMap<>();
        for (ControlTargets.Rect r : rects) byId.put(r.id, r);
        for (String id : new String[] {"send", "talk", "type", "queue", "clear", "undo", "row-again", "row-edit",
                "row-drop", "row-back", "echo-m"}) {
            assertNotNull(id + " has its target", byId.get(id));
        }
        // On the row: the row's target ends where the first action's begins; no two overlap,
        // and each covers its own words.
        assertEquals(byId.get("echo-m").right, byId.get("row-again").left, 0.001f);
        String[] row = {"echo-m", "row-again", "row-edit", "row-drop", "row-back"};
        for (int i = 0; i < row.length; i++) {
            ControlTargets.Rect r = byId.get(row[i]);
            assertTrue(row[i], r.left <= r.labelLeft && r.right >= r.labelRight);
            assertEquals(row[i], 17 * 22, r.top, 0.001f);
            assertEquals(row[i], 18 * 22, r.bottom, 0.001f);
            if (i > 0) assertTrue(row[i], byId.get(row[i - 1]).right <= r.left + 0.001f);
        }
        assertEquals("the last reaches the grid's edge", 98 * 9, byId.get("row-back").right, 0.001f);
        // And a named activation (TalkBack's double tap) acts on each.
        List<String> acted = new ArrayList<>();
        for (String id : new String[] {"send", "row-again"}) {
            new ComposerBar.GuardedTarget(id, new TapGuard(), acted::add).onClick(null);
        }
        assertEquals("[send, row-again]", acted.toString());
    }

    @Test public void wolfAsk4_theButtonsPixelsNeverMoveAtAnyDensity() {
        // Review of #35 (r2): 46 columns of 5.9 dp, the surface at an odd offset, density 2.75
        // or 3: from an idle empty draft to a draft (✕ appears) and through every state, each
        // slot target's pixels, as the composer places its views, stay the same.
        for (float density : new float[] {2.75f, 3f, 2.625f}) {
            for (float originX : new float[] {0f, 13.4f}) {
                int cols = 46;
                float cw = 5.9f, ch = 22f;
                Map<String, String> first = new LinkedHashMap<>();
                for (Map.Entry<String, ControlLine.State> e : states().entrySet()) {
                    boolean recording = e.getValue().recording;
                    List<GhostText.Control> line = ControlLine.controls(e.getValue(), cols);
                    List<ControlTargets.Rect> rects = ControlTargets.layout(drawn(line, cols), 0, 0, cw, ch, 20,
                            24 * ch, cols * cw, recording);
                    int[][] px = ControlTargets.pixels(rects, originX, 30.3f, density);
                    // As ComposerBar places them: the strip at the least left/top, each view inside it.
                    int stripLeft = Integer.MAX_VALUE, stripTop = Integer.MAX_VALUE;
                    for (int[] p : px) {
                        stripLeft = Math.min(stripLeft, p[0]);
                        stripTop = Math.min(stripTop, p[1]);
                    }
                    for (int i = 0; i < rects.size(); i++) {
                        String slot = slotOf(rects.get(i).id);
                        if (slot == null || (recording && !slot.equals("type"))) continue;
                        int[] p = px[i];
                        int left = stripLeft + (p[0] - stripLeft), top = stripTop + (p[1] - stripTop);
                        String bounds = left + ".." + (left + p[2] - p[0]) + " x " + top + ".." + (top + p[3] - p[1]);
                        String was = first.putIfAbsent(slot, bounds);
                        if (was != null) {
                            assertEquals(density + "x, " + e.getKey() + ": the " + slot + " pixels moved", was,
                                    bounds);
                        }
                    }
                }
                assertEquals(3, first.size());
            }
        }
    }

    /** The renderer's cells for "kind id row start end; ..." (review of #35, r3: row-layouts.tsv). */
    private static List<ControlTargets.Cells> cells(String spec) {
        List<ControlTargets.Cells> out = new ArrayList<>();
        for (String one : spec.split(";")) {
            String[] f = one.trim().split(" ");
            out.add(new ControlTargets.Cells(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                    Integer.parseInt(f[3])));
        }
        return out;
    }

    private static boolean overlap(ControlTargets.Rect a, ControlTargets.Rect b) {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    }

    @Test public void wolfAsk4_theStopAndASelectedRowsActionsNeverShareATap() {
        // Review of #35 (r3), the renderer's own layouts: recording on the 46 x 23 cover screen,
        // Claude's prompt on row 20, the controls on 21, the queue open with a row selected on
        // row 18 (its actions at its right end). The stop grows up into the rows above the prompt;
        // it must never take "drop" or "edit", nor they a tap meant for the stop.
        List<ControlTargets.Cells> bar = cells("clear 21 15 16; queue 21 19 22; type 21 25 29; talk 21 32 36;"
                + " gap-send 21 39 43");
        String[][] kinds = {
            {"unsure", "echo-m 18 0 14", "row-again 18 15 25; row-edit 18 28 32; row-drop 18 35 39; row-back 18 42 46"},
            {"ordinary", "echo-m 18 0 27", "row-edit 18 28 32; row-drop 18 35 39; row-back 18 42 46"},
            {"parked", "echo-m 18 0 26", "row-retry 18 27 32; row-drop 18 35 39; row-back 18 42 46"},
            {"clip", "echo-m 18 0 34", "row-drop 18 35 39; row-back 18 42 46"},
        };
        float cw = 5.9f, left = 3.25f, top = 1.75f;
        int cols = 46, rows = 23, topRow = 20;
        for (String[] kind : kinds) {
            List<ControlTargets.Cells> echoes = cells("echo-old 17 0 9; " + kind[1]);
            List<ControlTargets.Cells> actions = cells(kind[2]);
            List<ControlTargets.Cells> queueRows = new ArrayList<>(echoes);
            queueRows.addAll(actions);
            for (float ch : new float[] {14, 18, 22, 28}) {
                for (boolean recording : new boolean[] {true, false}) {
                    String what = kind[0] + ", " + ch + " dp rows" + (recording ? ", recording" : "");
                    List<ControlTargets.Rect> rowTargets = ControlTargets.rows(echoes, actions, left, top, cw, ch, cols);
                    List<ControlTargets.Rect> barTargets = ControlTargets.layout(bar, left, top, cw, ch, topRow,
                            top + rows * ch, left + cols * cw, recording, queueRows);
                    for (ControlTargets.Rect r : rowTargets) {
                        assertEquals(what + ": " + r.id + " is one row high", ch, r.height(), 0.001f);
                        for (ControlTargets.Rect b : barTargets) {
                            assertFalse(what + ": " + b.id + " overlaps " + r.id, overlap(r, b));
                        }
                    }
                    for (ControlTargets.Rect b : barTargets) {
                        assertTrue(what + ": " + b.id + " keeps its words", b.left <= b.labelLeft
                                && b.right >= b.labelRight && b.top <= top + 21 * ch && b.bottom >= top + 22 * ch);
                    }
                }
            }
            // The reviewer's tap, at 22 dp rows: the stop's, never drop's.
            float ch = 22, x = 220, y = 416;
            ControlTargets.Rect stop = null;
            for (ControlTargets.Rect b : ControlTargets.layout(bar, left, top, cw, ch, topRow, top + rows * ch,
                    left + cols * cw, true, queueRows)) {
                if (b.id.equals("talk")) stop = b;
            }
            assertNotNull(stop);
            assertEquals("the stop still reaches the grid's right edge", left + cols * cw, stop.right, 0.001f);
            boolean inStop = x >= stop.left && x < stop.right && y >= stop.top && y < stop.bottom;
            for (ControlTargets.Rect r : ControlTargets.rows(echoes, actions, left, top, cw, ch, cols)) {
                boolean inRow = x >= r.left && x < r.right && y >= r.top && y < r.bottom;
                assertFalse(kind[0] + ": the tap at (220, 416) reaches both the stop and " + r.id, inRow && inStop);
            }
        }
    }

    @Test public void wolfAsks1And4_theDraftAndTheOpenQueueBothKeepTheirCells() {
        // #34 and #35 together, the renderer's layouts on the 46 x 23 cover screen: Claude busy, so
        // the draft is at the host's own prompt on row 20 (a rule on 19), the queue open above it
        // with a row selected on 18 (its actions at its right end) and an older one on 17, the
        // controls on 21. Recording, the stop grows up past the draft's rows towards the queue;
        // "✕" pads up into the draft's row. Every target gives up both: the draft's cells (ask 1)
        // and the queue's rows (ask 4).
        List<ControlTargets.Cells> bar = cells("clear 21 15 16; queue 21 19 22; type 21 25 29; talk 21 32 36;"
                + " gap-send 21 39 43");
        List<ControlTargets.Cells> echoes = cells("echo-old 17 0 9; echo-m 18 0 27");
        List<ControlTargets.Cells> actions = cells("row-edit 18 28 32; row-drop 18 35 39; row-back 18 42 46");
        List<ControlTargets.Cells> queueRows = new ArrayList<>(echoes);
        queueRows.addAll(actions);
        int[][] draft = {{20, 0, 14}};
        float cw = 5.9f, left = 3.25f, top = 1.75f;
        int cols = 46, rows = 23, topRow = 20;
        boolean draftMatters = false, rowsMatter = false;
        for (float ch : new float[] {14, 18, 22, 28}) {
            for (boolean recording : new boolean[] {true, false}) {
                String what = ch + " dp rows" + (recording ? ", recording" : "");
                float bottom = top + rows * ch, right = left + cols * cw;
                List<ControlTargets.Rect> rowTargets = ControlTargets.rows(echoes, actions, left, top, cw, ch, cols);
                ControlTargets.Rect draftCells = new ControlTargets.Rect("draft", left + draft[0][1] * cw,
                        top + draft[0][0] * ch, left + draft[0][2] * cw, top + (draft[0][0] + 1) * ch, 0, 0);
                for (ControlTargets.Rect b : ControlTargets.layout(bar, left, top, cw, ch, topRow, bottom, right,
                        recording, draft, queueRows)) {
                    assertFalse(what + ": " + b.id + " covers the draft", overlap(b, draftCells));
                    for (ControlTargets.Rect r : rowTargets) {
                        assertFalse(what + ": " + b.id + " overlaps " + r.id, overlap(r, b));
                    }
                    assertTrue(what + ": " + b.id + " keeps its words", b.left <= b.labelLeft
                            && b.right >= b.labelRight && b.top <= top + 21 * ch && b.bottom >= top + 22 * ch);
                }
                // Neither exclusion stands in for the other: with only one, the other is crossed.
                for (ControlTargets.Rect b : ControlTargets.layout(bar, left, top, cw, ch, topRow, bottom, right,
                        recording, draft)) {
                    for (ControlTargets.Rect r : rowTargets) rowsMatter |= overlap(r, b);
                }
                for (ControlTargets.Rect b : ControlTargets.layout(bar, left, top, cw, ch, topRow, bottom, right,
                        recording, queueRows)) {
                    draftMatters |= overlap(b, draftCells);
                }
            }
        }
        assertTrue("without the queue's rows the stop would take a row's tap", rowsMatter);
        assertTrue("without the draft's spans a control would take the draft's tap", draftMatters);
    }
}
