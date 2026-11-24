package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.PendingClip;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * factory-9zz.16 on screen: a lane held by a dictation says so with its choices; a saved clip
 * offers "on phone" only when the phone has a verified model, and the phone's text is shown as a
 * proposal (English only) with use it / keep waiting.
 */
public final class DictationRowsTest {
    private static final int WIDE = 100;

    private static PendingClip clip(String id) throws Exception {
        Constructor<PendingClip> c = PendingClip.class.getDeclaredConstructor(
                String.class, String.class, long.class, long.class);
        c.setAccessible(true);
        return c.newInstance(id, "t1", 1L, 12_000L);
    }

    private static Outbox.Message msg(String id, String text) {
        return new Outbox.Message(id, "h", "t1", text, Outbox.State.PENDING);
    }

    private static String ids(List<GhostText.Control> controls) {
        StringBuilder out = new StringBuilder();
        for (GhostText.Control c : controls) out.append(out.length() == 0 ? "" : " ").append(c.id);
        return out.toString();
    }

    @Test public void aHeldLaneSaysHowManyWaitAndOffersItsChoices() {
        QueueView view = new QueueView();
        List<Outbox.Message> messages = Arrays.asList(
                msg("m1", "fix " + DictationToken.of("c1")), msg("m2", "second"), msg("m3", "third"));
        view.dictations(new QueueView.Dictations(new Outbox.Blocked("m1", "c1", 2),
                Collections.emptyMap(), Collections.emptySet(), false));
        List<QueueView.Row> rows = view.all("t1", messages, Collections.emptyList(), Collections.emptyList(), 0, WIDE, 0);
        QueueView.Row blocked = rows.get(0);
        assertEquals(QueueView.Kind.BLOCKED, blocked.kind);
        assertEquals("2 messages waiting behind a dictation that is waiting for a transcriber", blocked.text);
        assertEquals("c1", blocked.clipId);
        assertTrue(blocked.actionable);
        // Without a model on the phone: no "on phone".
        assertEquals("row-retry row-aside row-back", ids(ControlLine.rowActions(blocked)));

        view.dictations(new QueueView.Dictations(new Outbox.Blocked("m1", "c1", 1),
                Collections.emptyMap(), Collections.emptySet(), true));
        QueueView.Row one = view.all("t1", messages, Collections.emptyList(), Collections.emptyList(), 0, WIDE, 0).get(0);
        assertEquals("1 message waiting behind a dictation that is waiting for a transcriber", one.text);
        assertEquals("row-retry row-here row-aside row-back", ids(ControlLine.rowActions(one)));
        assertEquals("keep waiting", ControlLine.rowActions(one).get(3).spans[0]);
    }

    @Test public void aSavedClipOffersThePhoneOnlyWithAModelAndShowsItsProposal() throws Exception {
        QueueView view = new QueueView();
        List<PendingClip> clips = Collections.singletonList(clip("c1"));
        List<Outbox.Message> none = Collections.emptyList();
        view.dictations(new QueueView.Dictations(null, Collections.emptyMap(), Collections.emptySet(), false));
        QueueView.Row plain = view.all("t1", none, clips, Collections.emptyList(), 0, WIDE, 0).get(0);
        assertEquals("row-drop row-back", ids(ControlLine.rowActions(plain)));

        view.dictations(new QueueView.Dictations(null, Collections.emptyMap(), Collections.emptySet(), true));
        QueueView.Row here = view.all("t1", none, clips, Collections.emptyList(), 0, WIDE, 0).get(0);
        assertEquals("row-here row-drop row-back", ids(ControlLine.rowActions(here)));

        view.dictations(new QueueView.Dictations(null, Collections.emptyMap(), Collections.singleton("c1"), true));
        QueueView.Row busy = view.all("t1", none, clips, Collections.emptyList(), 0, WIDE, 0).get(0);
        assertEquals("◌ transcribing on this phone…", busy.state);
        assertEquals("not twice", "row-drop row-back", ids(ControlLine.rowActions(busy)));

        view.dictations(new QueueView.Dictations(null, Collections.singletonMap("c1", "fix the build"),
                Collections.emptySet(), true));
        QueueView.Row proposed = view.all("t1", none, clips, Collections.emptyList(), 0, WIDE, 0).get(0);
        assertEquals("“fix the build”", proposed.text);
        assertEquals("? heard on this phone (English only) · use it?", proposed.state);
        assertEquals("row-use row-wait row-drop row-back", ids(ControlLine.rowActions(proposed)));

        // A parked clip keeps its retry, and gets the same choices.
        QueueView.Row parked = view.all("t1", none, Collections.emptyList(), clips, 0, WIDE, 0).get(0);
        assertEquals("row-retry row-use row-wait row-drop row-back", ids(ControlLine.rowActions(parked)));
    }
}
