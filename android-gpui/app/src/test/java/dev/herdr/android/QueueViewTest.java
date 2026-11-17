package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.PendingClip;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The queue's rows above the prompt (item 75): every waiting thing, its state, sent ones fading. */
public final class QueueViewTest {
    private static final int WIDE = 100, COVER = 52;

    private static Outbox.Message msg(String id, String pane, String text, Outbox.State state, boolean blocked) {
        return new Outbox.Message(id, "h", pane, "b", text, 1, state, blocked);
    }

    private static PendingClip clip(String id) throws Exception {
        Constructor<PendingClip> c = PendingClip.class.getDeclaredConstructor(
                String.class, String.class, long.class, long.class);
        c.setAccessible(true);
        return c.newInstance(id, "t1", 1L, 12_000L);
    }

    private static String states(List<QueueView.Row> rows) {
        StringBuilder out = new StringBuilder();
        for (QueueView.Row r : rows) out.append(out.length() == 0 ? "" : " | ").append(r.state);
        return out.toString();
    }

    @Test public void eachRowSaysWhatIsHappeningToItWideAndAsAGlyphOnTheCoverScreen() throws Exception {
        QueueView view = new QueueView();
        List<Outbox.Message> messages = Arrays.asList(
                msg("s", "t1", "going now", Outbox.State.SENDING, false),
                msg("a", "t1", "first", Outbox.State.QUEUED, false),
                msg("b", "t1", "second " + DictationToken.of("c9"), Outbox.State.QUEUED, false),
                msg("d", "t1", "behind a dialog", Outbox.State.QUEUED, true),
                msg("u", "t1", "maybe", Outbox.State.UNSURE, false),
                msg("g", "t2", "old pane", Outbox.State.GONE, false));
        view.update("t1", messages, 0);
        List<QueueView.Row> wide = view.rows("t1", messages, Collections.singletonList(clip("c1")),
                Collections.singletonList(clip("p1")), 2, WIDE, true, null, 0);
        // Expanded wide: six rows show, the oldest give way to "+N more".
        assertEquals(QueueView.MAX_ROWS_WIDE, wide.size());
        assertEquals(QueueView.Kind.MORE, wide.get(0).kind);
        assertEquals("+4 more ▲", wide.get(0).text);
        List<QueueView.Row> all = view.rows("t1", messages, Collections.singletonList(clip("c1")),
                Collections.singletonList(clip("p1")), 2, WIDE, true, null, 0);
        assertEquals(6, all.size());
        // Everything, by asking for a long list: states in order.
        QueueView big = new QueueView();
        List<QueueView.Row> rows = big.rows("t1", messages, Collections.singletonList(clip("c1")),
                Collections.singletonList(clip("p1")), 2, WIDE, false, null, 0);
        assertEquals(QueueView.COLLAPSED_ROWS, rows.size());
        assertEquals("✗ not transcribed yet · retry | sends when back", states(rows));
        assertEquals("● sending…", QueueView.state(messages.get(0), "t1", 0, false));
        assertEquals("◌ queued 1st", QueueView.state(messages.get(1), "t1", 1, false));
        assertEquals("◌ waits for desktop", QueueView.state(messages.get(2), "t1", 2, false));
        assertEquals("◌ waits for its dialog", QueueView.state(messages.get(3), "t1", 3, false));
        assertEquals("? may have been sent", QueueView.state(messages.get(4), "t1", 0, false));
        assertEquals("✗ not sent · t2 gone", QueueView.state(messages.get(5), "t1", 0, false));
        assertEquals("●", QueueView.state(messages.get(0), "t1", 0, true));
        assertEquals("◌2", QueueView.state(messages.get(1), "t1", 2, true));
        assertEquals("?", QueueView.state(messages.get(4), "t1", 0, true));
        assertEquals("✗", QueueView.state(messages.get(5), "t1", 0, true));
        assertEquals("3rd", QueueView.ordinal(3));
        assertEquals("11th", QueueView.ordinal(11));
        assertEquals("22nd", QueueView.ordinal(22));
    }

    @Test public void theFullListHasEveryRowRecordingsAndParkedOnesTooWithNoMoreRow() throws Exception {
        QueueView view = new QueueView();
        List<Outbox.Message> messages = Arrays.asList(
                msg("a", "t1", "1", Outbox.State.QUEUED, false), msg("b", "t1", "2", Outbox.State.QUEUED, false),
                msg("c", "t1", "3", Outbox.State.QUEUED, false), msg("d", "t1", "4", Outbox.State.QUEUED, false),
                msg("e", "t1", "5", Outbox.State.QUEUED, false), msg("f", "t1", "6", Outbox.State.QUEUED, false));
        List<PendingClip> clips = Collections.singletonList(clip("c1"));
        List<PendingClip> parked = Collections.singletonList(clip("p1"));
        // On the line the oldest hide behind "+N more"...
        List<QueueView.Row> line = view.rows("t1", messages, clips, parked, 0, COVER, true, null, 0);
        assertEquals(QueueView.Kind.MORE, line.get(0).kind);
        // ...the full list has them all, the recordings with their actions.
        List<QueueView.Row> all = view.all("t1", messages, clips, parked, 0, COVER, 0);
        assertEquals(8, all.size());
        for (QueueView.Row r : all) assertTrue(r.kind != QueueView.Kind.MORE);
        assertEquals(QueueView.Kind.CLIP, all.get(6).kind);
        assertEquals(QueueView.Kind.PARKED, all.get(7).kind);
        assertTrue(all.get(7).actionable);
    }

    @Test public void aMessageBeingSentHasNoActionsAndTheCountLeavesSentOnesOut() throws Exception {
        QueueView view = new QueueView();
        List<Outbox.Message> messages = Arrays.asList(
                msg("s", "t1", "going", Outbox.State.SENDING, false),
                msg("a", "t1", "next", Outbox.State.QUEUED, false));
        view.update("t1", messages, 0);
        List<QueueView.Row> rows = view.rows("t1", messages, Collections.emptyList(), 0, WIDE, true, null, 0);
        assertFalse("never touched while it is being sent", rows.get(0).actionable);
        assertTrue(rows.get(1).actionable);
        assertEquals(2, QueueView.count(messages, Collections.emptyList(), 0));
        assertEquals(4, QueueView.count(messages, Collections.singletonList(clip("c")),
                Collections.singletonList(clip("p")), 0));
        assertEquals(3, QueueView.count(messages, Collections.emptyList(), 5));
    }

    @Test public void aSentMessageShowsSentForThreeSecondsThenGoes() {
        QueueView view = new QueueView();
        Outbox.Message a = msg("a", "t1", "hello", Outbox.State.SENDING, false);
        view.update("t1", Collections.singletonList(a), 1_000);
        view.update("t1", Collections.emptyList(), 1_200);
        List<QueueView.Row> rows = view.rows("t1", Collections.emptyList(), Collections.emptyList(), 0, WIDE, true,
                null, 1_300);
        assertEquals(1, rows.size());
        assertEquals(QueueView.Kind.SENT, rows.get(0).kind);
        assertEquals("✓ sent", rows.get(0).state);
        assertEquals("❯ hello", rows.get(0).text);
        assertEquals(1_200 + QueueView.SENT_FADE_MS, view.nextChange(1_300));
        assertTrue(view.rows("t1", Collections.emptyList(), Collections.emptyList(), 0, WIDE, true, null,
                1_200 + QueueView.SENT_FADE_MS).isEmpty());
        assertEquals(Long.MAX_VALUE, view.nextChange(1_200 + QueueView.SENT_FADE_MS));
    }

    @Test public void aMessageTheUserTookOutIsNotSent() {
        QueueView view = new QueueView();
        Outbox.Message a = msg("a", "t1", "edit me", Outbox.State.QUEUED, false);
        view.update("t1", Collections.singletonList(a), 0);
        view.forget("a");
        view.update("t1", Collections.emptyList(), 10);
        assertTrue(view.rows("t1", Collections.emptyList(), Collections.emptyList(), 0, WIDE, true, null, 20).isEmpty());
    }

    @Test public void aPaneSwitchIsNotASendAndNeitherIsABlankDictation() {
        QueueView view = new QueueView();
        view.update("t1", Collections.singletonList(msg("a", "t1", "on t1", Outbox.State.QUEUED, false)), 0);
        view.update("t2", Collections.emptyList(), 10);
        view.update("t1", Collections.emptyList(), 20);
        assertTrue(view.rows("t1", Collections.emptyList(), Collections.emptyList(), 0, WIDE, true, null, 30).isEmpty());
        // A message whose dictation came back empty is dropped, not sent.
        view.update("t1", Collections.singletonList(
                msg("b", "t1", DictationToken.of("c1"), Outbox.State.QUEUED, false)), 40);
        view.update("t1", Collections.emptyList(), 50);
        assertTrue(view.rows("t1", Collections.emptyList(), Collections.emptyList(), 0, WIDE, true, null, 60).isEmpty());
    }

    @Test public void theCoverScreenShowsFewerRowsWithGlyphs() {
        QueueView view = new QueueView();
        List<Outbox.Message> messages = Arrays.asList(
                msg("a", "t1", "1", Outbox.State.QUEUED, false), msg("b", "t1", "2", Outbox.State.QUEUED, false),
                msg("c", "t1", "3", Outbox.State.QUEUED, false), msg("d", "t1", "4", Outbox.State.QUEUED, false));
        List<QueueView.Row> rows = view.rows("t1", messages, Collections.emptyList(), 0, COVER, true, null, 0);
        assertEquals(QueueView.MAX_ROWS_COVER, rows.size());
        assertEquals("+2 more ▲", rows.get(0).text);
        assertEquals("◌3 | ◌4", states(rows.subList(1, 3)));
        // Closed, the newest two stay: the queue is never invisible.
        List<QueueView.Row> closed = view.rows("t1", messages, Collections.emptyList(), 0, COVER, false, null, 0);
        assertEquals(2, closed.size());
        assertEquals("d", closed.get(1).id);
    }

    @Test public void everyRowIsDrawnByThePhonesFontNeverAsABox() throws Exception {
        List<Outbox.Message> messages = Arrays.asList(
                msg("s", "t1", "going now", Outbox.State.SENDING, false),
                msg("a", "t1", "first", Outbox.State.QUEUED, false),
                msg("b", "t1", "second " + DictationToken.of("c9"), Outbox.State.QUEUED, false),
                msg("d", "t1", "behind a dialog", Outbox.State.QUEUED, true),
                msg("u", "t1", "maybe", Outbox.State.UNSURE, false),
                msg("g", "t2", "old pane", Outbox.State.GONE, false));
        for (int cols : new int[] {WIDE, COVER}) {
            for (boolean expanded : new boolean[] {false, true}) {
                QueueView view = new QueueView();
                view.update("t1", messages, 0);
                for (QueueView.Row r : view.rows("t1", messages, Collections.singletonList(clip("c1")),
                        Collections.singletonList(clip("p1")), 2, cols, expanded, null, 0)) {
                    assertEquals(cols + " cols: " + r.state, "", GridGlyphs.boxes(r.state));
                    // A message's own words are the user's; every other row's are ours. Dictation
                    // tokens are drawn as chips, not as their characters.
                    String ours = DictationToken.PATTERN.matcher(r.text).replaceAll("");
                    if (r.messageState == null) assertEquals(r.text, "", GridGlyphs.boxes(ours));
                }
            }
        }
    }
}
