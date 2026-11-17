package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The phone never loses a recording: its audio stays on disk until a final is received and
 * stored. A full disk, a failed index write, a kill mid-session or mid-delivery keeps the audio,
 * and the next start recovers what no index entry holds as "not transcribed yet" (parked).
 */
public final class NoLostAudioTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private final AtomicLong now = new AtomicLong(1_000_000);

    private File pending() {
        return new File(dir.getRoot(), "pending");
    }

    private ClipStore store() {
        return new ClipStore(pending(), now::get);
    }

    private static ClipStore.Recording recording(ClipStore store, int seconds) {
        ClipStore.Recording r = store.record("pane", 1234);
        byte[] chunk = new byte[ClipStore.BYTES_PER_SECOND];
        for (int i = 0; i < seconds; i++) assertTrue(r.write(chunk, chunk.length));
        return r;
    }

    @Test public void aKillMidSessionLeavesItsAudioWhichTheNextStartRecoversAsNotTranscribedYet() throws Exception {
        ClipStore store = store();
        ClipStore.Recording r = recording(store, 2);
        // Killed here: neither kept nor discarded. The .part is on disk.
        File part = new File(pending(), r.id + ".part");
        assertTrue(part.isFile());
        ClipStore again = store();
        List<PendingClip> parked = again.parked();
        assertEquals(1, parked.size());
        assertEquals(r.id, parked.get(0).id);
        assertEquals("its pane is not known", "", parked.get(0).target);
        assertEquals(2000, parked.get(0).durationMs);
        assertTrue(again.audio(r.id).isFile());
        assertEquals(2 * ClipStore.BYTES_PER_SECOND, again.audio(r.id).length());
        assertTrue("not sent on its own", again.untranscribed().isEmpty());
        // And it stays recovered after another restart.
        assertEquals(1, store().parked().size());
    }

    @Test public void anOrphanRecordingWhoseIndexWasLostIsRecoveredNotDeleted() throws Exception {
        File dirFile = pending();
        assertTrue(dirFile.mkdirs());
        File orphan = new File(dirFile, "abc-123.pcm");
        Files.write(orphan.toPath(), new byte[ClipStore.BYTES_PER_SECOND]);
        ClipStore store = store();
        assertEquals("abc-123", store.parked().get(0).id);
        assertTrue(orphan.isFile());
    }

    @Test public void onlyAudioWithAStoredFinalOrPastIts48HoursOrEmptyIsDeleted() throws Exception {
        ClipStore store = store();
        PendingClip done = recording(store, 1).keep();
        assertTrue(store.transcribed(done.id, "stored final"));
        // A leftover of it (a delete that failed): its final is stored, so it goes.
        Files.write(store.audio(done.id).toPath(), new byte[100]);
        PendingClip kept = recording(store, 1).keepTranscribedOnPhone();
        File empty = new File(pending(), "empty.part");
        assertTrue(empty.createNewFile());
        now.addAndGet(ClipStore.KEEP_MS + 1);
        ClipStore again = store();
        assertFalse(again.audio(done.id).exists());
        assertFalse("its 48 h are up", again.audio(kept.id).exists());
        assertFalse(empty.exists());
        assertTrue(again.parked().isEmpty());
        assertEquals(1, again.transcribed().size());
    }

    @Test public void aFullDiskKeepsTheAudioAndTheIndexIsWrittenOnceThereIsRoom() throws Exception {
        ClipStore store = store();
        // The index cannot be written: its temporary file's place is taken.
        File blocker = new File(pending(), ClipStore.INDEX + ".tmp");
        assertTrue(blocker.mkdirs());
        assertTrue(new File(blocker, "x").createNewFile());
        PendingClip clip = recording(store, 1).keep();
        assertNotNull("the clip is not given up", clip);
        assertTrue(store.audio(clip.id).isFile());
        assertEquals("still waiting, in memory", clip.id, store.untranscribed().get(0).id);
        assertFalse(store.flush());
        // Room again: the next flush writes the index.
        new File(blocker, "x").delete();
        assertTrue(blocker.delete());
        assertTrue(store.flush());
        assertEquals(clip.id, store().untranscribed().get(0).id);
    }

    @Test public void aFailedFinalStoreKeepsTheAudioForTheNextPass() throws Exception {
        ClipStore store = store();
        PendingClip clip = recording(store, 1).keep();
        File blocker = new File(pending(), ClipStore.INDEX + ".tmp");
        assertTrue(blocker.mkdirs());
        assertTrue(new File(blocker, "x").createNewFile());
        assertFalse("not stored: not accepted", store.transcribed(clip.id, "final"));
        assertTrue("the audio stays for the next pass", store.audio(clip.id).isFile());
        assertEquals(clip.id, store.untranscribed().get(0).id);
    }

    @Test public void aCrashBetweenReceivingTheFinalAndStoringItKeepsTheAudio() throws Exception {
        // The courier received a final; the process dies before the text is stored.
        ClipStore store = store();
        PendingClip clip = recording(store, 1).keep();
        PendingDictations pending = new PendingDictations(store, (c, f) -> {
            throw new IOException("killed while storing");
        }, java.util.concurrent.Executors.newSingleThreadScheduledExecutor(), Runnable::run);
        pending.pass();
        assertTrue(store.audio(clip.id).isFile());
        assertEquals(clip.id, store().untranscribed().get(0).id);
    }

    @Test public void everyEntryIsReadBackHoweverManyThereAre() throws Exception {
        ClipStore store = store();
        int n = ClipStore.MAX_CLIPS + 6;
        for (int i = 0; i < n; i++) {
            PendingClip c = recording(store, 0 == i % 2 ? 1 : 1).keep();
            assertNotNull(c);
            if (i % 2 == 0) assertTrue(store.transcribed(c.id, "final " + i));
        }
        ClipStore again = store();
        assertEquals("nothing dropped on reload", n, again.size());
        assertEquals(n / 2, again.transcribed().size());
        assertEquals(n - n / 2, again.untranscribed().size());
    }
}
