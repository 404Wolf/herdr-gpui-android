package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * factory-9zz.16: "Transcribe on this phone" for a saved clip. The phone's text is only a
 * proposal: never placed and never deleting the audio until the user says Use it, once per clip;
 * the desktop's text, if it comes first, wins; nothing runs without the user asking.
 */
public final class TranscribeHereTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final List<String> placed = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private final Executor now = Runnable::run;

    @After public void stopWorker() {
        worker.shutdownNow();
    }

    private ClipStore store() {
        return new ClipStore(new File(dir.getRoot(), "pending"));
    }

    private static PendingClip record(ClipStore store) {
        ClipStore.Recording r = store.record("host/pane", 1234);
        byte[] chunk = new byte[ClipStore.BYTES_PER_SECOND];
        assertTrue(r.write(chunk, chunk.length));
        return r.keep();
    }

    private PendingDictations courier(ClipStore store, PendingDictations.Transcriber desktop) {
        PendingDictations p = new PendingDictations(store, desktop, worker, now);
        p.setListener(new PendingDictations.Listener() {
            @Override public void onTranscribed(PendingClip clip, String text) {
                placed.add(clip.id + "=" + text);
            }

            @Override public void onChanged() { }

            @Override public void onTranscribedHere(PendingClip clip, String text, String error) {
                results.add(clip.id + "=" + (text != null ? text : "error:" + error));
            }
        });
        return p;
    }

    private static final PendingDictations.Transcriber AWAY = (clip, audio) -> {
        throw new UploadFailure(UploadFailure.Kind.UNREACHABLE, false, "away", null);
    };

    @Test public void thePhonesTextIsAProposalUntilUseItThenPlacedOnce() {
        ClipStore store = store();
        PendingClip clip = record(store);
        PendingDictations p = courier(store, AWAY);
        assertTrue(p.transcribeHere(clip.id, audio -> "hello there", now));
        assertEquals(List.of(clip.id + "=hello there"), results);
        assertTrue("proposed, never placed by itself", placed.isEmpty());
        assertEquals("hello there", p.proposal(clip.id));
        assertTrue("the audio stays until Use it", store.audio(clip.id).isFile());
        assertEquals("it still waits for the desktop", 1, store.untranscribed().size());
        // Survives a restart.
        assertEquals("hello there", store().proposal(clip.id));

        assertTrue(p.useProposal(clip.id));
        assertEquals(List.of(clip.id + "=hello there"), placed);
        assertFalse("the audio goes once its text is stored", store.audio(clip.id).exists());
        assertFalse("exactly once", p.useProposal(clip.id));
        assertFalse(p.transcribeHere(clip.id, audio -> "again", now));
        assertEquals(1, placed.size());
    }

    @Test public void keepWaitingDropsTheProposalAndKeepsTheClip() {
        ClipStore store = store();
        PendingClip clip = record(store);
        PendingDictations p = courier(store, AWAY);
        assertTrue(p.transcribeHere(clip.id, audio -> "maybe wrong", now));
        p.dismissProposal(clip.id);
        assertNull(p.proposal(clip.id));
        assertTrue(store.audio(clip.id).isFile());
        assertEquals(1, store.untranscribed().size());
        assertTrue(placed.isEmpty());
    }

    @Test public void aFailedLocalRunChangesNothingAndCanBeTriedAgain() {
        ClipStore store = store();
        PendingClip clip = record(store);
        PendingDictations p = courier(store, AWAY);
        assertTrue(p.transcribeHere(clip.id, audio -> null, now));
        assertTrue(p.transcribeHere(clip.id, audio -> {
            throw new IOException("model gone");
        }, now));
        assertEquals(List.of(clip.id + "=error:failed", clip.id + "=error:model gone"), results);
        assertNull(p.proposal(clip.id));
        assertTrue(store.audio(clip.id).isFile());
        assertTrue(p.transcribeHere(clip.id, audio -> "third time", now));
        assertEquals("third time", p.proposal(clip.id));
    }

    @Test public void oneTranscriptionAtATimeAndNeverAlongsideTheDesktopsUpload() throws Exception {
        ClipStore store = store();
        PendingClip clip = record(store);
        PendingDictations p = courier(store, AWAY);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Executor background = Executors.newSingleThreadExecutor();
        assertTrue(p.transcribeHere(clip.id, audio -> {
            running.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            return "late";
        }, background));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertFalse("already being transcribed here", p.transcribeHere(clip.id, audio -> "twice", now));
        assertEquals("the courier does not send it meanwhile", -2, store.charge(clip.id));
        release.countDown();
        for (int i = 0; i < 100 && p.proposal(clip.id) == null; i++) Thread.sleep(20);
        assertEquals("late", p.proposal(clip.id));

        // The other way round: a clip the courier is uploading is not transcribed here.
        PendingClip other = record(store);
        assertTrue(store.charge(other.id) > 0);
        assertFalse(p.transcribeHere(other.id, audio -> "no", now));
        store.uploaded(other.id);
        assertTrue(p.transcribeHere(other.id, audio -> "yes", now));
    }

    @Test public void theDesktopsTextWinsOverAnUnusedProposal() throws Exception {
        ClipStore store = store();
        PendingClip clip = record(store);
        PendingDictations p = courier(store, (c, audio) -> "from the desktop");
        assertTrue(p.transcribeHere(clip.id, audio -> "from the phone", now));
        p.pass();
        for (int i = 0; i < 100 && placed.isEmpty(); i++) Thread.sleep(20);
        assertEquals(List.of(clip.id + "=from the desktop"), placed);
        assertNull(p.proposal(clip.id));
        assertFalse(p.useProposal(clip.id));
        assertEquals(1, placed.size());
    }

    @Test public void aClearedClipIsNeverTranscribedHere() {
        ClipStore store = store();
        PendingClip clip = record(store);
        PendingDictations p = courier(store, AWAY);
        assertTrue(p.hold(List.of(clip.id)));
        assertFalse(p.transcribeHere(clip.id, audio -> "no", now));
        assertTrue(results.isEmpty());
    }
}
