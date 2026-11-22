package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** The app's bottom-right buttons: the same controls as the old prompt words, as buttons. */
public final class ActionBarTest {
    private static ControlLine.State idle() {
        ControlLine.State in = new ControlLine.State();
        in.talks = java.util.Collections.singletonList(new GhostText.Control("talk", "talk", "dim"));
        in.attach = true;
        in.clearable = true;
        in.queued = 2;
        return in;
    }

    @Test public void theWidestLineIsImgClearQueueTypeTalkSendWithSendLast() {
        List<ActionBar.Item> items = ActionBar.items(ControlLine.ladder(idle()).get(0));
        StringBuilder ids = new StringBuilder();
        for (ActionBar.Item i : items) ids.append(i.id).append(' ');
        assertEquals("attach clear queue type talk send ", ids.toString());
        assertEquals("img", items.get(0).label);
        assertEquals("clear", items.get(1).label);
        assertEquals("send", items.get(items.size() - 1).id);
        for (ActionBar.Item i : items) assertTrue(i.id, i.button);
    }

    @Test public void typeSaysHideWhileTheKeyboardIsUp() {
        ControlLine.State in = idle();
        in.typing = true;
        List<ActionBar.Item> items = ActionBar.items(ControlLine.ladder(in).get(0));
        boolean hide = false;
        for (ActionBar.Item i : items) if ("type".equals(i.id)) hide = "hide".equals(i.label);
        assertTrue(hide);
    }

    @Test public void statusWordsAreALabelAndGapsAreDropped() {
        List<GhostText.Control> line = java.util.Arrays.asList(
                new GhostText.Control(ControlLine.STATUS, "rec 0:05", "danger"),
                new GhostText.Control(ControlLine.GAP_TYPE, "────", "dim"),
                new GhostText.Control("talk", " stop ", "danger"),
                new GhostText.Control("clear", "✕", "dim"));
        List<ActionBar.Item> items = ActionBar.items(line);
        assertEquals(3, items.size());
        assertFalse("status is not a button", items.get(0).button);
        assertEquals("stop", items.get(1).label);
        assertEquals("danger", items.get(1).style);
        assertEquals("the cross says clear", "clear", items.get(2).label);
    }

    @Test public void theRowTakesTheWidestLineThatFits() {
        List<List<GhostText.Control>> ladder = ControlLine.ladder(idle());
        java.util.function.ToIntFunction<List<ActionBar.Item>> chars = items -> {
            int n = 0;
            for (ActionBar.Item i : items) n += i.label.length() + 1;
            return n;
        };
        assertEquals(0, ActionBar.pick(ladder, Integer.MAX_VALUE, chars));
        int narrow = ActionBar.pick(ladder, 1, chars);
        assertEquals("nothing fits: the barest line", ladder.size() - 1, narrow);
    }

    @Test public void thePromptGetsNoControlWords() throws Exception {
        String composer = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/ComposerBar.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(composer.contains("shownRows, queueOpen, selectedRow, cols, Collections.emptyList(), GhostText.Colors.of(chrome));"));
        assertFalse(composer.contains("GhostText.withRungs(json, ladder)"));
    }
}
