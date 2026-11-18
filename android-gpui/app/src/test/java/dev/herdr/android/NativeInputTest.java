package dev.herdr.android;

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
        assertEquals(52, DraftTaps.drawnAt(draft, 4, 54));
        for (boolean recording : new boolean[] {false, true}) {
            List<ControlTargets.Rect> rects = ControlTargets.layout(cells, 0, 0, 8, 16, 5, 96, 480, recording, draft);
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
        int[][] beside = {{5, 2, 30, 0, 30}};
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
}
