package dev.herdr.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Wolf's ask 1 (11-17): the input is native in Claude's line. The draft is drawn at Claude's
 * own prompt (or, when that cannot take it, at the renderer's own prompt in the terminal: see
 * gpui-rio ghost.rs {@code wolf_ask1_the_draft_is_drawn_in_the_terminal_in_every_state}); no
 * hide button, no separate mic, no separate or Android-styled input box, in any state.
 */
public final class NativeInputTest {
    private static String composerSource() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/ComposerBar.java")),
                StandardCharsets.UTF_8);
    }

    private static int count(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test public void wolfAsk1_theComposerMakesNoVisibleTextBoxOnlyTheHiddenImeSink() throws Exception {
        String src = composerSource();
        // No text view of its own: the draft is never shown in an Android box (the old
        // "fallback" label beside the controls is gone).
        assertEquals("a TextView in the composer", 0, count(src, "new\\s+(android\\.widget\\.)?TextView\\s*\\("));
        assertFalse("the fallback draft box is gone", src.contains("fallback"));
        // Exactly one EditText: the keyboard's input sink, never seen.
        assertEquals("EditTexts in the composer", 1, count(src, "new\\s+(android\\.widget\\.)?EditText\\s*\\("));
        assertTrue("the sink is invisible", src.contains("edit.setAlpha(0f);"));
        assertTrue("the sink draws no cursor", src.contains("edit.setCursorVisible(false);"));
        assertTrue("the sink has no background", src.contains("edit.setBackground(null);"));
    }

    /** Every combination of the line's state the composer can be in, at every width. */
    private static List<List<GhostText.Control>> everyLine() {
        GhostText.Control talk = new GhostText.Control("talk", "talk", "dim");
        List<List<GhostText.Control>> talks = Arrays.asList(
                java.util.Collections.<GhostText.Control>emptyList(),
                java.util.Collections.singletonList(talk),
                Arrays.asList(new GhostText.Control("talk", "● rec 0:05 · tap to stop", "danger"),
                        new GhostText.Control("talk", "● 0:05 stop", "danger"),
                        new GhostText.Control("talk", "● stop", "danger")),
                Arrays.asList(new GhostText.Control("talk", "transcribing…", "info"),
                        new GhostText.Control("talk", "…", "info")));
        List<List<GhostText.Control>> out = new ArrayList<>();
        for (boolean typing : new boolean[] {false, true}) {
            for (int t = 0; t < talks.size(); t++) {
                for (boolean clear : new boolean[] {false, true}) {
                    for (int queued : new int[] {0, 3}) {
                        for (boolean undo : new boolean[] {false, true}) {
                            for (boolean open : new boolean[] {false, true}) {
                                for (int cols = 5; cols <= 140; cols += 5) {
                                    ControlLine.State in = new ControlLine.State();
                                    in.typing = typing;
                                    in.talks = talks.get(t);
                                    in.recording = t == 2;
                                    in.clearable = clear;
                                    in.queued = queued;
                                    in.expanded = open;
                                    if (undo) {
                                        in.undoWide = "cleared · undo";
                                        in.undoNarrow = "undo";
                                    }
                                    out.add(ControlLine.controls(in, cols));
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test public void wolfAsk1_noHideControlInAnyState() {
        for (List<GhostText.Control> line : everyLine()) {
            for (GhostText.Control c : line) {
                assertFalse("a hide control: " + c.id, c.id.contains("hide"));
                for (int i = 0; i < c.spans.length; i += 2) {
                    assertFalse("the word hide: " + c.spans[i], c.spans[i].toLowerCase().contains("hide"));
                }
            }
        }
    }

    @Test public void wolfAsk1_oneMicrophoneOnly_talkInTheLine() {
        for (List<GhostText.Control> line : everyLine()) {
            int talks = 0;
            for (GhostText.Control c : line) if (c.id.equals("talk")) talks++;
            assertTrue("at most one talk in " + line.size() + " controls", talks <= 1);
        }
    }

    @Test public void wolfAsk1_tappingTheDrawnDraftNeverHitsAControl() {
        // Review of #34: a 60-column, 6-row screen with no prompt, a 55-character draft drawn
        // by the renderer on row 4, the controls on row 5. Draft character 52 is under "send".
        List<ControlTargets.Cells> cells = Arrays.asList(
                new ControlTargets.Cells("type", 5, 35, 39),
                new ControlTargets.Cells("talk", 5, 42, 46),
                new ControlTargets.Cells("clear", 5, 49, 50),
                new ControlTargets.Cells("send", 5, 53, 57));
        int[][] draft = {{4, 2, 55, 0, 55}};
        int[][] spans = {{4, 2, 57}};
        assertEquals(52, DraftTaps.drawnAt(draft, 4, 54));
        for (boolean recording : new boolean[] {false, true}) {
            List<ControlTargets.Rect> rects = ControlTargets.layout(cells, 0, 0, 8, 16, 5, 96, 480, recording, spans);
            for (int col = 2; col < 57; col++) {
                float x = col * 8 + 4, y = 4 * 16 + 8;
                for (ControlTargets.Rect r : rects) {
                    assertFalse((recording ? "recording: " : "") + "draft cell " + col + " is under " + r.id,
                            x >= r.left && x < r.right && y >= r.top && y < r.bottom);
                }
            }
            // The controls keep their own words.
            for (ControlTargets.Rect r : rects) assertTrue(r.id, r.top <= 5 * 16 && r.bottom >= 6 * 16);
        }
        // On the controls' own row (a dialog down to them): the draft left of them stays the draft's.
        int[][] beside = {{5, 2, 32}};
        for (ControlTargets.Rect r : ControlTargets.layout(cells, 0, 0, 8, 16, 5, 96, 480, false, beside)) {
            assertTrue(r.id + " starts after the draft", r.left >= 32 * 8);
        }
    }

    @Test public void wolfAsk1_aTapOnAWideOrCombinedCharacterGoesToItsEdges() {
        // 界 at cells 2-3 is draft character 0; a ZWJ family (5 code points) at 4-5 is 1..5.
        int[][] runs = {{0, 2, 2, 0, 1}, {0, 4, 2, 1, 5}, {0, 6, 3, 6, 3}};
        assertEquals(0, DraftTaps.drawnAt(runs, 0, 2.4f));
        assertEquals(1, DraftTaps.drawnAt(runs, 0, 3.2f));
        assertEquals(1, DraftTaps.drawnAt(runs, 0, 4.5f));
        assertEquals(6, DraftTaps.drawnAt(runs, 0, 5.5f));
        // Plain characters still one cell each.
        assertEquals(7, DraftTaps.drawnAt(runs, 0, 7.2f));
        // An older renderer's four-field runs: one cell per character, as before.
        assertEquals(3, DraftTaps.drawnAt(new int[][] {{0, 0, 5, 0}}, 0, 3.2f));
    }

    /** No target of {@code hint}'s controls covers a cell of its draft spans. */
    private static void assertNoTargetOnTheDraft(ClusterPlacement.Hint hint, boolean recording) {
        for (ControlTargets.Rect rect : ControlTargets.layout(hint.controls, 0, 0, 8, 16, hint.targetsTop,
                hint.rows * 16, hint.cols * 8, recording, hint.draftSpans)) {
            for (int[] s : hint.draftSpans) {
                for (int c = s[1]; c < s[2]; c++) {
                    float x = c * 8 + 4, y = s[0] * 16 + 8;
                    assertFalse("draft at row " + s[0] + " col " + c + " is under " + rect.id,
                            x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom);
                }
            }
        }
    }

    private static final String CONTROLS_60 = "\"controls\":[{\"id\":\"type\",\"row\":4,\"start\":35,\"end\":39},"
            + "{\"id\":\"talk\",\"row\":4,\"start\":42,\"end\":46},{\"id\":\"clear\",\"row\":4,\"start\":49,\"end\":50},"
            + "{\"id\":\"send\",\"row\":4,\"start\":53,\"end\":57}]";

    @Test public void wolfAsk1_aDraftWrappedBelowTheControlsNeverHitsSend() {
        // Review of #34 (r2, P1#1), the renderer's own geometry (ghost.rs
        // wolf_ask1_every_drawn_draft_row_is_reported_above_beside_and_below_the_controls): 220
        // characters at Claude's prompt on row 3, its lower rule and the controls on row 4, the
        // draft wrapped on down to row 7.
        String head = "{\"left\":0,\"top\":0,\"cellWidth\":8,\"cellHeight\":16,\"rows\":8,\"cols\":60,"
                + "\"busy\":[0,0,0,0,0,0,0,0],\"targetsTop\":3," + CONTROLS_60 + ",\"draft\":[[3,2,58,0,58,0],"
                + "[4,2,31,58,31,0],[5,2,58,89,58,0],[6,2,58,147,58,0],[7,2,15,205,15,0]]";
        for (String json : new String[] {
                head + ",\"draftSpans\":[[3,2,60],[4,2,33],[5,2,60],[6,2,60],[7,2,17]]}",
                // An older renderer, without spans: its runs stand for them.
                head + "}"}) {
            ClusterPlacement.Hint hint = ClusterPlacement.Hint.parse(json);
            assertEquals(5, hint.draftSpans.length);
            for (boolean recording : new boolean[] {false, true}) assertNoTargetOnTheDraft(hint, recording);
            // The reviewer's tap: row 5, column 54 is the draft's, character 141.
            assertEquals(141, DraftTaps.drawnAt(hint.draftRuns, 5, 54));
        }
    }

    /** A hint with {@code n} combined clusters (é as e + U+0301) from row 2 on, 108 to a row. */
    private static String combining(int n) {
        StringBuilder runs = new StringBuilder();
        java.util.TreeMap<Integer, int[]> spans = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++) {
            int row = 2 + i / 108, col = 2 + i % 108;
            runs.append(i == 0 ? "" : ",").append('[').append(row).append(',').append(col).append(",1,")
                    .append(2 * i).append(",2,1]");
            spans.computeIfAbsent(row, r -> new int[] {r, col, col})[2] = col + 1;
        }
        StringBuilder s = new StringBuilder();
        for (int[] span : spans.values()) {
            s.append(s.length() == 0 ? "" : ",").append('[').append(span[0]).append(',').append(span[1]).append(',')
                    .append(span[2]).append(']');
        }
        int rows = 6 + n / 108;
        StringBuilder busy = new StringBuilder();
        for (int i = 0; i < rows; i++) busy.append(i == 0 ? "0" : ",0");
        int last = rows - 1;
        return "{\"left\":0,\"top\":0,\"cellWidth\":8,\"cellHeight\":16,\"rows\":" + rows + ",\"cols\":110,"
                + "\"busy\":[" + busy + "],\"targetsTop\":" + last + ",\"controls\":[{\"id\":\"type\",\"row\":" + last
                + ",\"start\":85,\"end\":89},{\"id\":\"talk\",\"row\":" + last + ",\"start\":92,\"end\":96},"
                + "{\"id\":\"clear\",\"row\":" + last + ",\"start\":99,\"end\":100},{\"id\":\"send\",\"row\":" + last
                + ",\"start\":103,\"end\":107}],\"draft\":[" + runs + "],\"draftSpans\":[" + s + "]}";
    }

    @Test public void wolfAsk1_everyDrawnClusterReachesTheComposerAndNoneIsUnderSend() {
        // Review of #34 (r2, P1#2): 320 combined clusters, each a run of its own (ghost.rs
        // wolf_ask1_every_drawn_cluster_is_reported_within_the_shared_bound): all kept.
        ClusterPlacement.Hint hint = ClusterPlacement.Hint.parse(combining(320));
        assertEquals(320, hint.draftRuns.length);
        assertNoTargetOnTheDraft(hint, false);
        // Cluster 316 (row 4, column 102) maps to itself.
        assertEquals(2 * 316, DraftTaps.drawnAt(hint.draftRuns, 4, 102.2f));
        // Past the shared bound (a renderer never sends that many): the rest's rows are kept
        // whole from the controls, nothing cut silently.
        int over = ClusterPlacement.Hint.MAX_DRAFT_RUNS + 200;
        ClusterPlacement.Hint big = ClusterPlacement.Hint.parse(combining(over));
        assertEquals(ClusterPlacement.Hint.MAX_DRAFT_RUNS, big.draftRuns.length);
        int lastRow = 2 + (over - 1) / 108;
        int[] last = big.draftSpans[big.draftSpans.length - 1];
        assertArrayEquals(new int[] {lastRow, 0, 110}, last);
    }

    @Test public void wolfAsk1_aTapOnAFlagOrAHeartGoesToItsEdges() {
        // Review of #34 (r2, P2#3): 🇺🇸 and ❤️ are two characters in two cells; the renderer
        // marks them clusters (ghost.rs wolf_ask1_a_flag_or_a_heart_is_one_cluster).
        ClusterPlacement.Hint hint = ClusterPlacement.Hint.parse("{\"left\":0,\"top\":0,\"cellWidth\":8,"
                + "\"cellHeight\":16,\"rows\":6,\"cols\":110,\"busy\":[0,0,0,0,0,0],\"targetsTop\":5,"
                + "\"draft\":[[4,2,2,0,2,1],[4,4,1,2,1,0]]}");
        assertEquals(0, DraftTaps.drawnAt(hint.draftRuns, 4, 2.8f));
        assertEquals(2, DraftTaps.drawnAt(hint.draftRuns, 4, 3.2f));
        // A plain run still maps cell by cell.
        assertEquals(3, DraftTaps.drawnAt(hint.draftRuns, 4, 4.6f));
    }
}
