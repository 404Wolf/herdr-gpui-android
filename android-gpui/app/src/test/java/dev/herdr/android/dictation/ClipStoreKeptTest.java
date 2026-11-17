package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Recordings the phone transcribed itself: kept 48 h, never sent, never in the way. */
public final class ClipStoreKeptTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private final AtomicLong now = new AtomicLong(1_000_000);

    private ClipStore store() {
        return new ClipStore(new File(dir.getRoot(), "pending"), now::get);
    }

    private static ClipStore.Recording recording(ClipStore store, int seconds) {
        ClipStore.Recording r = store.record("host/pane", 1234);
        byte[] chunk = new byte[ClipStore.BYTES_PER_SECOND];
        for (int i = 0; i < seconds; i++) assertTrue(r.write(chunk, chunk.length));
        return r;
    }

    @Test public void aKeptClipIsNeitherWaitingNorCountedAndSurvivesARestart() {
        ClipStore store = store();
        PendingClip kept = recording(store, 2).keepTranscribedOnPhone();
        assertNotNull(kept);
        assertTrue(store.untranscribed().isEmpty());
        assertEquals(0, store.size());
        assertEquals(1, store.keptClips().size());
        assertFalse("not transcribed by the courier", store.transcribed(kept.id, "text"));
        ClipStore again = store();
        assertEquals(1, again.keptClips().size());
        assertEquals(2 * ClipStore.BYTES_PER_SECOND, again.audio(kept.id).length());
        assertTrue(again.untranscribed().isEmpty());
    }

    @Test public void aKeptClipIsDeletedAt48HoursByTheNextSessionStartInARunningApp() {
        ClipStore store = store();
        PendingClip kept = recording(store, 1).keepTranscribedOnPhone();
        now.addAndGet(ClipStore.KEEP_MS - 1);
        store.record("host/pane", now.get()).discard();
        assertEquals("not yet", 1, store.keptClips().size());
        now.addAndGet(2);
        // No restart: the same store, as in an app that stays running for days.
        assertEquals(1, store.keptClips().size());
        assertTrue(store.audio(kept.id).exists());
        store.record("host/pane", now.get()).discard();
        assertTrue(store.keptClips().isEmpty());
        assertFalse(store.audio(kept.id).exists());
        assertTrue("gone after a restart too", store().keptClips().isEmpty());
    }

    @Test public void anExpiredKeptClipIsDeletedOnLoad() {
        PendingClip kept = recording(store(), 1).keepTranscribedOnPhone();
        now.addAndGet(ClipStore.KEEP_MS + 1);
        ClipStore again = store();
        assertTrue(again.keptClips().isEmpty());
        assertFalse(again.audio(kept.id).exists());
    }

    @Test public void aCourierPassDeletesExpiredKeptClipsInARunningApp() throws Exception {
        ClipStore store = store();
        PendingClip kept = recording(store, 1).keepTranscribedOnPhone();
        java.util.concurrent.ScheduledExecutorService worker =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            PendingDictations pending = new PendingDictations(store, (c, f) -> {
                throw new java.io.IOException("not under test");
            }, worker, Runnable::run);
            now.addAndGet(ClipStore.KEEP_MS + 1);
            assertTrue(store.audio(kept.id).exists());
            pending.pass();
            assertFalse(store.audio(kept.id).exists());
            assertTrue(store.keptClips().isEmpty());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test public void keptClipsGiveWayToANewRecording() {
        ClipStore store = store();
        // Fill the whole budget with kept clips of 10 minutes each.
        int minutes = (int) (ClipStore.MAX_TOTAL_BYTES / ClipStore.BYTES_PER_SECOND / 60);
        for (int i = 0; i < minutes / 10; i++) assertNotNull(recording(store, 600).keepTranscribedOnPhone());
        assertEquals(minutes / 10, store.keptClips().size());
        // A new recording still gets its room: the oldest kept clip goes.
        ClipStore.Recording r = recording(store, 60);
        assertNotNull(r.keep());
        assertEquals(minutes / 10 - 1, store.keptClips().size());
        assertEquals(1, store.untranscribed().size());
    }

    @Test public void aClearedRecordingIsHeldNeverSentOrOfferedAndAnUndoLetsItGo() {
        ClipStore store = store();
        PendingClip waiting = recording(store, 1).keep();
        assertNotNull(waiting);
        assertEquals(1, store.untranscribed().size());
        assertTrue(store.hold(waiting.id));
        assertTrue("not sent", store.untranscribed().isEmpty());
        assertEquals(1, store.keptClips().size());
        assertFalse("a held clip's late text is dropped", store.transcribed(waiting.id, "late"));
        assertTrue(store.audio(waiting.id).isFile());
        assertTrue(store.release(waiting.id));
        assertEquals(waiting.id, store.untranscribed().get(0).id);
        // A transcribed one waiting to be placed is hidden while held.
        assertTrue(store.transcribed(waiting.id, "text"));
        assertEquals(1, store.transcribed().size());
        assertTrue(store.hold(waiting.id));
        assertTrue(store.transcribed().isEmpty());
        assertTrue(store.release(waiting.id));
        assertEquals(1, store.transcribed().size());
    }

    @Test public void holdingAParkedRecordingKeepsItParkedThroughAnUndo() {
        ClipStore store = store();
        PendingClip clip = recording(store, 1).keep();
        for (int i = 0; i < ClipStore.MAX_ATTEMPTS; i++) store.attempted(clip.id);
        assertEquals(1, store.parked().size());
        assertTrue(store.hold(clip.id));
        assertTrue(store.parked().isEmpty());
        assertTrue(store.release(clip.id));
        assertEquals("still parked: no surprise resend", 1, store.parked().size());
        assertTrue(store.untranscribed().isEmpty());
    }
}
