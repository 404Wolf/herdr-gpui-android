package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

/** Review of #48: the dictation controls never drop on narrow grids, img stays, Cancel and Send keep their pane. */
public final class KbTalkRound2Test {
    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/java/dev/herdr/android/" + name + ".java").toPath()),
                StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int first = text.indexOf(start);
        assertTrue(start, first >= 0);
        int last = text.indexOf(end, first + start.length());
        assertTrue(end, last > first);
        return text.substring(first, last);
    }

    private static String ids(List<GhostText.Control> line) {
        StringBuilder out = new StringBuilder();
        for (GhostText.Control c : line) out.append(out.length() == 0 ? "" : " ").append(c.id);
        return out.toString();
    }

    /** The line the renderer draws (ghost.rs place_all): widest rung with the draft margin, else any that fits; null: its fallback. */
    private static List<GhostText.Control> rendered(ControlLine.State in, int cols) {
        List<List<GhostText.Control>> ladder = ControlLine.ladder(in);
        for (List<GhostText.Control> rung : ladder) if (ControlLine.width(rung) + ControlLine.MARGIN <= cols) return rung;
        for (List<GhostText.Control> rung : ladder) if (ControlLine.width(rung) <= cols) return rung;
        return java.util.Collections.emptyList();
    }

    private static ControlLine.State dictating(DictationEngine.State s, int queued, boolean expanded, boolean attach) {
        ControlLine.State in = new ControlLine.State();
        in.talks = ComposerBar.talks(s, "0:05", false, false, false, null);
        in.recording = ComposerBar.recording(s);
        in.dictating = true;
        in.clearable = true;
        in.queued = queued;
        in.expanded = expanded;
        in.attach = attach;
        return in;
    }

    @Test public void everyDictationStateKeepsHideCancelStopAndSendDownToTheNarrowestRealGrids() {
        DictationEngine.State[] states = {DictationEngine.State.CONNECTING, DictationEngine.State.LISTENING,
            DictationEngine.State.TRANSCRIBING, DictationEngine.State.ON_DEVICE, DictationEngine.State.OFFLINE_RECORDING};
        for (DictationEngine.State s : states) {
            for (int queued : new int[] {0, 3, 12}) {
                for (boolean expanded : new boolean[] {false, true}) {
                    for (int cols = 32; cols <= 140; cols++) {
                        String line = ids(rendered(dictating(s, queued, expanded, true), cols));
                        String at = s + " queued=" + queued + " expanded=" + expanded + " at " + cols + ": " + line;
                        assertTrue(at, line.contains("type") && line.contains("cancel")
                                && line.contains("talk") && line.contains("send"));
                    }
                }
            }
        }
    }

    @Test public void theTimerStaysAtTheCoverAndInnerScreensWidths() {
        for (int cols : new int[] {46, 98}) {
            for (int queued : new int[] {0, 3, 12}) {
                String line = ids(rendered(dictating(DictationEngine.State.LISTENING, queued, false, true), cols));
                assertTrue("timer at " + cols + " queued=" + queued + ": " + line, line.contains("status"));
            }
        }
    }

    @Test public void imgIsStillThereWhereTheBaseShowedIt() {
        ControlLine.State draft = new ControlLine.State();
        draft.talks = ComposerBar.talks(DictationEngine.State.IDLE, "0:05", false, false, false, null);
        draft.clearable = true;
        draft.attach = true;
        for (int cols = 40; cols <= 43; cols++) {
            assertTrue("a draft at " + cols, ids(rendered(draft, cols)).contains("attach"));
        }
        assertTrue("recording at 52", ids(rendered(
                dictating(DictationEngine.State.LISTENING, 0, false, true), 52)).contains("attach"));
    }

    @Test public void cancelKeepsWhatWasTypedBeforeTheDictatedWordsAndNeverDestroysAnEditOfThem() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.edit("hello");
        drafts.dictationStarted();
        drafts.dictationPartial("dictated words");
        // An edit before the dictated tail survives the cancel; only the dictated words go.
        drafts.edit("hello there dictated words");
        assertEquals("hello there", drafts.dictationCancelled());
        // An edit inside the dictated words: nothing is destroyed, the draft is left as the user made it.
        drafts.dictationStarted();
        drafts.dictationPartial("more words");
        drafts.edit("hello there more wordz");
        assertEquals("hello there more wordz", drafts.dictationCancelled());
    }

    @Test public void aPaneSwitchTakesThePartialOutOfTheOldDraftAndTheFinalLandsOnceNotWithIt() throws Exception {
        String bar = source("ComposerBar");
        String setPane = section(bar, "void setPane(String hostId, String paneId, String paneKind)", "String next = paneKind");
        assertTrue(setPane.contains("drafts.dictationCancelled()"));
        String routing = source("DictationRouting");
        assertTrue("an error after a switch still lands the spoken words", routing.contains("lastPartial"));
    }

    @Test public void aSessionAndItsEnterAreBoundToTheOwnerOfTheDraftNotJustThePaneId() throws Exception {
        String routing = source("DictationRouting");
        assertTrue(routing.contains("sessionOwner"));
        assertTrue(routing.contains("drafts.owner()"));
        assertTrue("an Enter on another pane never waits on this session", routing.contains("enterFor("));
    }

    @Test public void attachIsStillReachableFromTheMenuWhenTheLineHasNoRoomForImg() throws Exception {
        assertTrue(source("MoreMenu").contains("ATTACH"));
        String more = section(source("GpuiTerminalActivity"), "private void showMoreActions(", "A 48 dp icon button");
        assertTrue(more.contains("Attach image") && more.contains("showAttachSources()"));
    }
}
