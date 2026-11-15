package dev.herdr.android.dictation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** The recognizer's audio: every chunk, in order, or a reported failure; never a silent drop. */
public final class PcmFeederTest {
    private static final int CHUNK = 3200;

    /** Records which way the feeding ended. */
    private static final class Ending implements PcmFeeder.Listener {
        final CountDownLatch ended = new CountDownLatch(1);
        volatile boolean drained;
        volatile String failure;

        @Override public void onDrained() {
            drained = true;
            ended.countDown();
        }

        @Override public void onFailed(String why) {
            failure = why;
            ended.countDown();
        }

        void await() throws InterruptedException {
            assertTrue("the feeding ended", ended.await(10, TimeUnit.SECONDS));
        }
    }

    /** A pipe whose reader takes {@code delayMs} per write, and can break. */
    private static final class Pipe extends OutputStream {
        final ByteArrayOutputStream got = new ByteArrayOutputStream();
        final long delayMs;
        volatile boolean broken;
        volatile boolean closed;
        volatile int writes;

        Pipe(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (broken || closed) throw new IOException("Broken pipe");
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            got.write(b, off, len);
            writes++;
        }

        @Override public void close() {
            closed = true;
        }
    }

    private static byte[] chunk(int i) {
        byte[] c = new byte[CHUNK];
        for (int j = 0; j < c.length; j++) c[j] = (byte) (i * 7 + j);
        return c;
    }

    private static byte[] audio(int from, int to) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (int i = from; i < to; i++) all.write(chunk(i), 0, CHUNK);
        return all.toByteArray();
    }

    @Test public void theEndNeverPushesQueuedAudioOutEvenWhenTheQueueIsFull() throws Exception {
        Ending ending = new Ending();
        PcmFeeder feeder = new PcmFeeder(10, 500, ending);
        Pipe pipe = new Pipe(5);
        feeder.setSink(pipe);
        // Fill the queue to the brim before the feeder runs, then end.
        for (int i = 0; i < 10; i++) assertTrue(feeder.write(chunk(i), CHUNK));
        feeder.end();
        feeder.start();
        ending.await();
        assertTrue(ending.drained);
        assertNull(ending.failure);
        assertArrayEquals("every chunk, in order", audio(0, 10), pipe.got.toByteArray());
        assertTrue("closed so the recognizer sees the end", pipe.closed);
    }

    @Test public void aRecognizerFallingBehindIsAFailureNotADrop() throws Exception {
        Ending ending = new Ending();
        PcmFeeder feeder = new PcmFeeder(4, 500, ending);
        feeder.setSink(new Pipe(0));
        for (int i = 0; i < 4; i++) assertTrue(feeder.write(chunk(i), CHUNK));
        // The feeder thread is not started: the queue is full.
        assertFalse(feeder.write(chunk(4), CHUNK));
        ending.await();
        assertFalse(ending.drained);
        assertTrue(ending.failure, ending.failure.contains("not keeping up"));
        assertFalse("nothing after a failure", feeder.write(chunk(5), CHUNK));
    }

    @Test public void aBrokenPipeIsReportedAfterTheGrace() throws Exception {
        Ending ending = new Ending();
        PcmFeeder feeder = new PcmFeeder(10, 200, ending);
        Pipe pipe = new Pipe(0);
        pipe.broken = true;
        feeder.setSink(pipe);
        feeder.start();
        long t = System.nanoTime();
        feeder.write(chunk(0), CHUNK);
        ending.await();
        long waited = (System.nanoTime() - t) / 1_000_000;
        assertFalse(ending.drained);
        assertTrue(ending.failure, ending.failure.contains("pipe write failed (IOException: Broken pipe)"));
        assertTrue("waited " + waited + " ms for a restart first", waited >= 150);
    }

    @Test public void aChunkThatHitsARestartGoesToTheNewPipe() throws Exception {
        Ending ending = new Ending();
        PcmFeeder feeder = new PcmFeeder(10, 2000, ending);
        Pipe first = new Pipe(0);
        feeder.setSink(first);
        feeder.start();
        feeder.write(chunk(0), CHUNK);
        while (first.writes < 1) Thread.sleep(5);
        // The recognizer's session ends on a pause and its pipe closes...
        first.broken = true;
        feeder.write(chunk(1), CHUNK);
        feeder.write(chunk(2), CHUNK);
        Thread.sleep(100);
        // ...then it restarts on a fresh one.
        Pipe second = new Pipe(0);
        feeder.setSink(second);
        feeder.end();
        ending.await();
        assertTrue(ending.drained);
        assertArrayEquals(audio(0, 1), first.got.toByteArray());
        assertArrayEquals("nothing lost across the restart", audio(1, 3), second.got.toByteArray());
    }

    @Test public void cancelStopsWithoutCallbacks() throws Exception {
        Ending ending = new Ending();
        PcmFeeder feeder = new PcmFeeder(10, 200, ending);
        Pipe pipe = new Pipe(50);
        feeder.setSink(pipe);
        feeder.start();
        for (int i = 0; i < 5; i++) feeder.write(chunk(i), CHUNK);
        feeder.cancel();
        feeder.end();
        assertFalse(ending.ended.await(500, TimeUnit.MILLISECONDS));
        assertTrue(pipe.closed);
    }

    @Test public void aPipeGivenAfterTheEndIsClosedAtOnce() throws Exception {
        Ending ending = new Ending();
        PcmFeeder feeder = new PcmFeeder(10, 200, ending);
        feeder.setSink(new Pipe(0));
        feeder.start();
        feeder.end();
        ending.await();
        Pipe late = new Pipe(0);
        feeder.setSink(late);
        assertTrue("its reader sees the end", late.closed);
        assertEquals(0, late.writes);
    }
}
