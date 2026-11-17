package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The ghost-text JSON handed to the renderer. */
public final class GhostTextTest {
    private static final GhostText.Colors COLORS = new GhostText.Colors(
            0xffB2CCD6, 0xff4A4A4A, 0xffFFCB6B, 0xff000000, 0xffEEFFFF, 0xffF07178, 0xff82AAFF);
    private static final java.util.List<GhostText.Control> NONE = Collections.emptyList();

    @Test public void theDraftSplitsIntoSettledWordsAPartialTailAndChips() throws Exception {
        String token = DictationToken.of("c-1");
        Map<String, Long> lengths = new HashMap<>();
        lengths.put("c-1", 9_000L);
        String draft = "fix " + token + " then the remote test";
        int partial = draft.indexOf("the remote");
        JSONObject o = new JSONObject(GhostText.json("claude", draft, partial, lengths, Collections.emptySet(),
                Collections.emptyList(), false, null, Integer.MAX_VALUE, NONE, COLORS));
        assertEquals("claude", o.getString("kind"));
        assertEquals(0xFFCB6B, o.getJSONObject("colors").getInt("warn"));
        assertEquals(0xF07178, o.getJSONObject("colors").getInt("danger"));
        JSONArray d = o.getJSONArray("draft");
        assertEquals("fix ", d.getJSONObject(0).getString("t"));
        assertEquals("[0:09 waiting for desktop]", d.getJSONObject(1).getString("t"));
        assertEquals("chip", d.getJSONObject(1).getString("s"));
        assertEquals(" then ", d.getJSONObject(2).getString("t"));
        assertEquals("the remote test", d.getJSONObject(3).getString("t"));
        assertEquals("partial", d.getJSONObject(3).getString("s"));
    }

    private static QueueView.Row row(String id, String text, String state, String style) {
        return new QueueView.Row(id, QueueView.Kind.MESSAGE, text, state, style, true, Outbox.State.QUEUED);
    }

    @Test public void queueRowsBecomeEchoesWithTheirStateAtTheRightEnd() throws Exception {
        java.util.List<QueueView.Row> rows = Arrays.asList(
                row("a", "❯ first ⏎ line", "◌ queued 1st", "tag"),
                row("b", "❯ second", "? may have been sent", "warn"));
        JSONObject o = new JSONObject(GhostText.json("codex", "", -1, new HashMap<>(), Collections.emptySet(), rows,
                true, null, 40, NONE, COLORS));
        JSONArray echoes = o.getJSONArray("echoes");
        JSONArray first = echoes.getJSONArray(0);
        assertEquals("❯ first ⏎ line", first.getJSONObject(0).getString("t"));
        assertEquals("echo", first.getJSONObject(0).getString("s"));
        // The state is right-aligned: the row is one cell short of the grid's width.
        String state = first.getJSONObject(1).getString("t");
        assertEquals("tag", first.getJSONObject(1).getString("s"));
        assertEquals("◌ queued 1st", state.trim());
        assertEquals(39, width(first));
        assertEquals("warn", echoes.getJSONArray(1).getJSONObject(1).getString("s"));
        assertEquals(0, o.getJSONArray("draft").length());
    }

    @Test public void eachEchoCarriesItsRowsIdSoATapNeverLandsOnAnotherRow() throws Exception {
        java.util.List<QueueView.Row> rows = Arrays.asList(
                row("6f1c2a9e-0000-4000-8000-000000000001", "❯ first", "◌1", "tag"),
                row("6f1c2a9e-0000-4000-8000-000000000002", "❯ second", "◌2", "tag"));
        JSONObject o = new JSONObject(GhostText.json("claude", "", -1, new HashMap<>(), Collections.emptySet(), rows,
                true, null, 40, NONE, COLORS));
        JSONArray ids = o.getJSONArray("echoIds");
        assertEquals(o.getJSONArray("echoes").length(), ids.length());
        assertEquals("6f1c2a9e-0000-4000-8000-000000000001", ids.getString(0));
        assertEquals("6f1c2a9e-0000-4000-8000-000000000002", ids.getString(1));
        // No rows, no ids.
        JSONObject none = new JSONObject(GhostText.json("claude", "", -1, new HashMap<>(), Collections.emptySet(),
                Collections.emptyList(), true, null, 40, NONE, COLORS));
        assertTrue(!none.has("echoIds"));
    }

    @Test public void aLongRowIsCutWithAnEllipsisAndASelectedRowIsBright() throws Exception {
        QueueView.Row long_ = row("a", "❯ a long message that will not fit in the cover screen width", "◌1", "tag");
        JSONArray cut = GhostText.row(long_, true, true, 30, new HashMap<>(), Collections.emptySet());
        String words = cut.getJSONObject(0).getString("t");
        assertTrue(words, words.endsWith("…"));
        assertEquals("bright", cut.getJSONObject(0).getString("s"));
        assertEquals(29, width(cut));
        assertEquals("◌1", cut.getJSONObject(1).getString("t").trim());
        // A closed queue's rows are dim, their state too.
        JSONArray closed = GhostText.row(row("b", "❯ x", "✗ not sent", "danger"), false, false, 30, new HashMap<>(), Collections.emptySet());
        assertEquals("tag", closed.getJSONObject(1).getString("s"));
    }

    @Test public void aChipInARowIsNeverCutInHalf() throws Exception {
        Map<String, Long> lengths = new HashMap<>();
        lengths.put("c-1", 12_000L);
        QueueView.Row clip = row("c", "❯ check this " + DictationToken.of("c-1") + " and more words", "◌", "tag");
        JSONArray cut = GhostText.row(clip, true, false, 24, lengths, Collections.emptySet());
        for (int i = 0; i < cut.length(); i++) {
            String t = cut.getJSONObject(i).getString("t");
            if ("chip".equals(cut.getJSONObject(i).getString("s"))) assertTrue(t, t.startsWith("[") && t.endsWith("]"));
        }
    }

    private static int width(JSONArray spans) throws Exception {
        int n = 0;
        for (int i = 0; i < spans.length(); i++) {
            String t = spans.getJSONObject(i).getString("t");
            n += t.codePointCount(0, t.length());
        }
        return n;
    }

    @Test public void controlsCarryTheirIdsAndStyledWords() throws Exception {
        JSONObject o = new JSONObject(GhostText.json("claude", "", -1, new HashMap<>(), Collections.emptySet(),
                Collections.emptyList(), false, null, Integer.MAX_VALUE, Arrays.asList(
                        new GhostText.Control("talk", "● rec 0:06 · tap to stop", "danger"),
                        new GhostText.Control("send", "send", "bright", "·2", "info", "", "dim")),
                COLORS));
        JSONArray controls = o.getJSONArray("controls");
        assertEquals("talk", controls.getJSONObject(0).getString("id"));
        assertEquals("danger", controls.getJSONObject(0).getJSONArray("spans").getJSONObject(0).getString("s"));
        // Empty words are left out.
        assertEquals(2, controls.getJSONObject(1).getJSONArray("spans").length());
    }

    @Test public void kindsTheRendererDoesNotKnowAreOther() {
        assertEquals("claude", GhostText.kind("claude"));
        assertEquals("codex", GhostText.kind("codex"));
        assertEquals("other", GhostText.kind("aider"));
        assertEquals("other", GhostText.kind(null));
    }

    @Test public void aParkedRecordingsChipReadsNotTranscribedYet() throws Exception {
        String token = DictationToken.of("c-2");
        Map<String, Long> lengths = new HashMap<>();
        lengths.put("c-2", 124_600L);
        JSONObject o = new JSONObject(GhostText.json("claude", "see " + token, -1, lengths,
                Collections.singleton("c-2"), Collections.emptyList(), false, null, Integer.MAX_VALUE, NONE, COLORS));
        assertEquals("[2:04 not transcribed yet · retry]", o.getJSONArray("draft").getJSONObject(1).getString("t"));
    }

    private static String runs(String text, GhostText.Marks marks) {
        StringBuilder out = new StringBuilder();
        for (String[] run : GhostText.runs(text, marks)) {
            out.append(run[1].equals("caret") ? "|" : run[1].equals("text") ? run[0] : run[1] + "(" + run[0] + ")");
        }
        return out.toString();
    }

    @Test public void theKeyboardsCursorSelectionAndComposingWordAreDrawnWhereTheyAre() {
        // "fix the bug" with the cursor after "fix ".
        assertEquals("fix |the bug", runs("fix the bug", GhostText.Marks.of(4, 4, -1, -1)));
        // At the end, and at the start.
        assertEquals("fix the bug|", runs("fix the bug", GhostText.Marks.of(11, 11, -1, -1)));
        assertEquals("|fix the bug", runs("fix the bug", GhostText.Marks.of(0, 0, -1, -1)));
        // "the" selected (either direction): no bar caret.
        assertEquals("fix selected(the) bug", runs("fix the bug", GhostText.Marks.of(4, 7, -1, -1)));
        assertEquals("fix selected(the) bug", runs("fix the bug", GhostText.Marks.of(7, 4, -1, -1)));
        // The underlined word in the middle, the cursor at its end.
        assertEquals("fix partial(the)| bug", runs("fix the bug", GhostText.Marks.of(7, 7, 4, 7)));
        // Not focused: nothing marked.
        assertEquals("fix", runs("fix", GhostText.Marks.of(-1, -1, -1, -1)));
        assertEquals("|", runs("", GhostText.Marks.of(0, 0, -1, -1)));
    }

    @Test public void noMarkCutsADictationChip() throws Exception {
        String token = DictationToken.of("c-1");
        String draft = "see " + token + " now";
        int inside = draft.indexOf(token) + 3;
        JSONArray d = new JSONObject(GhostText.json("claude", draft, GhostText.Marks.of(inside, inside, -1, -1),
                new HashMap<>(), Collections.emptySet(), Collections.emptyList(), false, null, Integer.MAX_VALUE,
                NONE, COLORS))
                .getJSONArray("draft");
        assertEquals("see ", d.getJSONObject(0).getString("t"));
        assertEquals("chip", d.getJSONObject(1).getString("s"));
        // The cursor moved to the chip's end.
        assertEquals("caret", d.getJSONObject(2).getString("s"));
        assertEquals("", d.getJSONObject(2).getString("t"));
        assertEquals(" now", d.getJSONObject(3).getString("t"));
    }

    @Test public void aCursorInADraftCutForLengthKeepsItsPlace() {
        GhostText.Marks marks = GhostText.Marks.of(100, 100, -1, -1).shifted(40);
        // 40 dropped and replaced by one "…": 100 is now 61.
        assertEquals(61, marks.caret);
        assertEquals(1, GhostText.Marks.of(3, 3, -1, -1).shifted(40).caret);
    }
}
