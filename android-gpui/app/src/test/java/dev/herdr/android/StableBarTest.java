package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
    private static final List<GhostText.Control> RECORDING = talks("danger", "rec 0:05", "0:05", "● stop");
    private static final List<GhostText.Control> TRANSCRIBING = talks("info", "transcribing…", "…");
    private static final List<GhostText.Control> ON_PHONE = talks("warn", "offline · rec 0:05", "offline 0:05",
            "● stop");
    private static final List<GhostText.Control> FOR_LATER = talks("warn", "desktop error · queued · rec 0:05",
            "queued 0:05", "● stop");
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

    @Test public void wolfAsk4_theTouchTargetsOfTypeAndSendNeverMoveWhileShown() {
        // The finger's bounds, not only the words: the targets the composer lays over them.
        int cols = 46;
        float cw = 9f, ch = 22f;
        Map<String, String> first = new LinkedHashMap<>();
        for (Map.Entry<String, ControlLine.State> e : states().entrySet()) {
            List<GhostText.Control> line = ControlLine.controls(e.getValue(), cols);
            List<ControlTargets.Cells> cells = new ArrayList<>();
            int col = cols - ControlLine.width(line);
            for (int i = 0; i < line.size(); i++) {
                col += i == 0 ? 1 : 3;
                int start = col;
                GhostText.Control c = line.get(i);
                for (int s = 0; s + 1 < c.spans.length; s += 2) {
                    col += c.spans[s].codePointCount(0, c.spans[s].length());
                }
                cells.add(new ControlTargets.Cells(c.id, 20, start, col));
            }
            boolean recording = e.getValue().recording;
            for (ControlTargets.Rect r : ControlTargets.layout(cells, 0, 0, cw, ch, 20, 24 * ch, cols * cw,
                    recording)) {
                if (!r.id.equals("type") && !r.id.equals("send")) continue;
                String bounds = r.left + ".." + r.right + " x " + r.top + ".." + r.bottom;
                String was = first.putIfAbsent(r.id, bounds);
                if (was != null) assertEquals(e.getKey() + ": the " + r.id + " target moved", was, bounds);
            }
        }
    }
}
