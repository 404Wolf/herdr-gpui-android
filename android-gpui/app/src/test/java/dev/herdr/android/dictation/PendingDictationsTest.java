package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Recordings kept for the desktop: bounds, restarts, and the courier's delivery. */
public final class PendingDictationsTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();

    @After public void stopWorker() {
        worker.shutdownNow();
    }

    private ClipStore store() {
        return new ClipStore(new File(dir.getRoot(), "pending"));
    }

    private static byte[] audio(int bytes, int value) {
        byte[] b = new byte[bytes];
        java.util.Arrays.fill(b, (byte) value);
        return b;
    }

    private static PendingClip record(ClipStore store, String target, int bytes) {
        ClipStore.Recording r = store.record(target, 1234);
        byte[] chunk = audio(bytes, 7);
        assertTrue(r.write(chunk, chunk.length));
        return r.keep();
    }

    @Test public void aKeptClipSurvivesARestartWithItsAudio() throws IOException {
        ClipStore store = store();
        PendingClip clip = record(store, "host/pane", ClipStore.BYTES_PER_SECOND * 2);
        assertNotNull(clip);
        assertEquals(2000, clip.durationMs);
        ClipStore again = store();
        List<PendingClip> waiting = again.untranscribed();
        assertEquals(1, waiting.size());
        assertEquals(clip.id, waiting.get(0).id);
        assertEquals("host/pane", waiting.get(0).target);
        assertEquals(1234, waiting.get(0).createdAt);
        assertEquals(ClipStore.BYTES_PER_SECOND * 2, Files.size(again.audio(clip.id).toPath()));
    }

    @Test public void aDiscardedOrUnfinishedRecordingLeavesNothing() {
        ClipStore store = store();
        ClipStore.Recording gone = store.record("t", 1);
        gone.write(audio(100, 1), 100);
        gone.discard();
        ClipStore.Recording killed = store.record("t", 1);
        killed.write(audio(100, 1), 100); // the process dies before keep()
        ClipStore again = store();
        assertEquals(0, again.size());
        File[] left = new File(dir.getRoot(), "pending").listFiles();
        assertNotNull(left);
        for (File f : left) assertEquals(ClipStore.INDEX, f.getName());
    }

    @Test public void anEmptyRecordingIsNotKept() {
        assertNull(store().record("t", 1).keep());
    }

    @Test public void aClipStopsAtTheSessionLimit() {
        ClipStore store = store();
        ClipStore.Recording r = store.record("t", 1);
        byte[] minute = new byte[ClipStore.BYTES_PER_SECOND * 60];
        for (int i = 0; i < 15; i++) assertTrue(r.write(minute, minute.length));
        assertFalse(r.write(minute, 2));
        assertFalse("stays stopped", r.write(minute, 0 + 1));
        r.discard();
    }

    @Test public void allClipsTogetherAreBounded() {
        ClipStore store = store();
        byte[] minute = new byte[ClipStore.BYTES_PER_SECOND * 60];
        for (int clip = 0; clip < 2; clip++) {
            ClipStore.Recording r = store.record("t", 1);
            for (int i = 0; i < 15; i++) assertTrue(r.write(minute, minute.length));
            assertNotNull(r.keep());
        }
        ClipStore.Recording third = store.record("t", 1);
        assertFalse(third.write(minute, 2));
        third.discard();
    }

    @Test public void theTargetIsCut() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 2000; i++) huge.append('x');
        PendingClip clip = record(store(), huge.toString(), 10);
        assertEquals(PendingClip.MAX_TARGET_CHARS, clip.target.length());
    }

    /** Collects deliveries; delivers back at once unless told to hold. */
    private static final class Composer implements PendingDictations.Listener {
        final List<String> got = new ArrayList<>();
        PendingDictations from;
        boolean place = true;
        int changes;

        @Override public void onTranscribed(PendingClip clip, String text) {
            got.add(clip.id + "=" + text);
            if (place) from.delivered(clip.id);
        }

        @Override public void onChanged() {
            changes++;
        }
    }

    @Test public void theCourierTranscribesOldestFirstAndDelivers() {
        ClipStore store = store();
        PendingClip a = record(store, "1", 10);
        PendingClip b = record(store, "2", 10);
        List<String> sent = new ArrayList<>();
        PendingDictations pending = new PendingDictations(store, (clip, file) -> {
            sent.add(clip.id);
            assertTrue(file.isFile());
            return "text " + clip.target;
        }, worker, Runnable::run);
        Composer composer = new Composer();
        composer.from = pending;
        pending.setListener(composer);
        assertEquals(2, pending.count());
        pending.pass();
        assertEquals(List.of(a.id, b.id), sent);
        assertEquals(List.of(a.id + "=text 1", b.id + "=text 2"), composer.got);
        assertEquals(0, pending.count());
        assertFalse(store.audio(a.id).exists());
    }

    @Test public void anUnreachableDesktopKeepsTheClipsForLater() {
        ClipStore store = store();
        PendingClip a = record(store, "1", 10);
        record(store, "2", 10);
        int[] tries = {0};
        PendingDictations pending = new PendingDictations(store, (clip, file) -> {
            tries[0]++;
            throw new IOException("refused");
        }, worker, Runnable::run);
        pending.pass();
        assertEquals("stops at the first failure", 1, tries[0]);
        assertEquals(2, pending.waiting().size());
        assertEquals(a.id, pending.waiting().get(0).id);
    }

    @Test public void textWithNoOneToPlaceItIsHeldOnDiskAndOfferedLater() {
        ClipStore store = store();
        PendingClip a = record(store, "1", 10);
        new PendingDictations(store, (clip, file) -> "hello", worker, Runnable::run).pass();
        // The app was killed before a composer took it.
        PendingDictations restarted = new PendingDictations(store(), (clip, file) -> {
            throw new AssertionError("already transcribed");
        }, worker, Runnable::run);
        assertEquals(1, restarted.count());
        Composer composer = new Composer();
        composer.from = restarted;
        composer.place = false;
        restarted.setListener(composer);
        assertEquals(List.of(a.id + "=hello"), composer.got);
        restarted.setListener(composer);
        assertEquals("offered again to a new listener until delivered", 2, composer.got.size());
        restarted.delivered(a.id);
        assertEquals(0, restarted.count());
    }

    @Test public void aDroppedClipIsNotTranscribed() {
        ClipStore store = store();
        PendingClip a = record(store, "1", 10);
        PendingDictations pending = new PendingDictations(store, (clip, file) -> {
            throw new AssertionError("dropped");
        }, worker, Runnable::run);
        pending.drop(a.id);
        pending.pass();
        assertEquals(0, pending.count());
        assertFalse(store.audio(a.id).exists());
    }
}
