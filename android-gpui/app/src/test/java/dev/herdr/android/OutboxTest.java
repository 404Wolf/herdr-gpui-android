package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Stage 1 of the offline design (§3, §6.3 E1 to E18 below the emulator): held messages are kept
 * per machine and pane, survive a restart, are typed only by a tap in their live pane, one lane
 * head at a time, and are never reported as delivered.
 */
public final class OutboxTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final MachineKey A = MachineKey.of("core-a", "SHA256:host-a");
    private static final MachineKey B = MachineKey.of("core-b", "SHA256:host-b");

    private File file() {
        return new File(dir.getRoot(), Outbox.FILE_NAME);
    }

    private Outbox fresh() {
        Outbox outbox = new Outbox(file());
        outbox.load();
        return outbox;
    }

    private static Outbox.Target target(MachineKey machine, String profile, String pane, Long created) {
        return new Outbox.Target(machine, profile, "computer " + profile, "default", pane, created, "PM herdr",
                "idle");
    }

    private static Outbox.Live live(MachineKey machine, String profile, String pane, Long created) {
        return new Outbox.Live(machine, profile, pane, created);
    }

    private static int ids;

    private static Outbox.Message hold(Outbox outbox, MachineKey machine, String profile, String pane, String text) {
        return outbox.add("m" + (++ids), target(machine, profile, pane, 7L), text, new Outbox.Composed(1_000, 50, 3));
    }

    /** One tap, as the courier makes it: claim, write, settle. */
    private static void tap(Outbox outbox, Outbox.Message m, Outbox.Live where, Outbox.Outcome outcome) {
        assertTrue("the tap claims its lane's head", outbox.claim(m.id, where, m.text, 2_000, false));
        outbox.settle(m.id, outcome, 2_000, true);
    }

    // ---- E1: typed offline, nothing sent until each tap, in order, then "sent · check it arrived" ----

    @Test public void e1_heldMessagesGoOnlyByATapInOrderAndStaySentNotDelivered() {
        Outbox outbox = fresh();
        Outbox.Message first = hold(outbox, A, "p", "t1", "first");
        Outbox.Message second = hold(outbox, A, "p", "t1", "second");
        Outbox.Live here = live(A, "p", "t1", 7L);
        // Nothing moves by itself: the head is only offered.
        assertEquals(first.id, outbox.offer(here).id);
        assertFalse("the second never overtakes", outbox.claim(second.id, here, second.text, 0, false));
        tap(outbox, first, here, Outbox.Outcome.WRITTEN);
        Outbox.Message sent = outbox.message(first.id);
        assertEquals(Outbox.State.SENT, sent.state);
        assertEquals("first", sent.text);
        String words = HeldWords.state(sent, true, java.util.TimeZone.getTimeZone("UTC"), true);
        assertEquals("sent 00:00 · check it arrived", words);
        assertFalse(words.contains("deliver"));
        // A sent row does not hold its lane: the next is offered.
        assertEquals(second.id, outbox.offer(here).id);
        tap(outbox, second, here, Outbox.Outcome.WRITTEN);
        assertNull(outbox.offer(here));
        assertEquals("sent rows wait for nobody", 0, outbox.waiting());
        assertEquals(2, outbox.all().size());
        // Kept an hour for Copy and Send again, then gone.
        assertFalse(outbox.purgeSent(2_000 + Outbox.SENT_KEEP_MS - 1));
        assertTrue(outbox.purgeSent(2_000 + Outbox.SENT_KEEP_MS));
        assertTrue(outbox.all().isEmpty());
    }

    @Test public void e1_theLeadIsOnTheSameLineForAnAgentAndNeverForAnImmediateSend() {
        java.util.TimeZone zone = java.util.TimeZone.getTimeZone("America/New_York");
        long at = 1_795_201_320_000L; // 2026-11-20T19:02Z
        assertEquals("[from the phone, composed 14:02 EST (2026-11-20T19:02Z), delivered later] go",
                HeldWords.wire("go", at, zone, true, false));
        assertEquals("go", HeldWords.wire("go", at, zone, true, true));
        String wire = HeldWords.wire("two\nlines", at, zone, true, false);
        assertTrue("one input: the lead shares the first line", wire.startsWith("[from the phone") && wire.indexOf('\n') > wire.indexOf(']'));
    }

    // ---- E18: a plain shell gets the text unchanged ----

    @Test public void e18_aProgramWithoutAStatusReportGetsTheTextUnchanged() {
        assertEquals("ls -la", HeldWords.wire("ls -la", 1_000, java.util.TimeZone.getDefault(), false, false));
    }

    // ---- survives restart and process death ----

    @Test public void heldMessagesSurviveARestartAndAKillWhileSendingNeedsTheUser() throws Exception {
        Outbox outbox = fresh();
        Outbox.Message going = hold(outbox, A, "p", "t1", "being typed");
        Outbox.Message kept = hold(outbox, A, "p", "t2", "kept");
        assertTrue(outbox.claim(going.id, live(A, "p", "t1", 7L), going.text, 5, false));
        assertTrue(outbox.save());
        // The process dies here, before the answer.
        Outbox again = fresh();
        assertEquals(2, again.size());
        Outbox.Message unsure = again.message(going.id);
        assertEquals(Outbox.State.NEEDS_YOU, unsure.state);
        assertEquals(Outbox.Reason.UNSURE, unsure.reason);
        assertEquals("needs your decision: may have been sent, or may be sitting unsent in the input box",
                HeldWords.state(unsure, true, java.util.TimeZone.getDefault(), true));
        assertEquals(Outbox.State.PENDING, again.message(kept.id).state);
        JSONObject saved = new JSONObject(new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8));
        assertEquals(2, saved.getInt("v"));
        JSONObject row = saved.getJSONArray("messages").getJSONObject(0);
        assertEquals("core-a", row.getJSONObject("machine").getString("core"));
        assertEquals("SHA256:host-a", row.getJSONObject("machine").getString("hostkey"));
        assertEquals("t1", row.getString("address"));
        assertEquals("sending", row.getString("state"));
        assertTrue(row.getBoolean("tap_required"));
        assertEquals("being typed", row.getJSONObject("attempt").getString("wire_text"));
    }

    // ---- E3, E4, E10, E12: lanes are per machine and terminal ----

    @Test public void e3_e4_twoMachinesWithTheSamePaneIdAreSeparateLanes() {
        Outbox outbox = fresh();
        Outbox.Message forA = hold(outbox, A, "pa", "t1", "for A");
        Outbox.Message forB = hold(outbox, B, "pb", "t1", "for B");
        assertEquals(forB.id, outbox.offer(live(B, "pb", "t1", 7L)).id);
        assertEquals(forA.id, outbox.offer(live(A, "pa", "t1", 7L)).id);
        // A's row is never offered in B's pane, whatever the profile is called.
        assertFalse(outbox.claim(forA.id, live(B, "pa", "t1", 7L), forA.text, 0, false));
        // Viewing B, the whole queue still counts A's row.
        assertEquals(2, outbox.waiting());
        assertEquals("waits for computer pa", HeldWords.state(forA, false, java.util.TimeZone.getDefault(), true));
        // The rows above B's prompt show only B's lane.
        assertEquals(Collections.singletonList(forB.id), ids(outbox.shown(B, "pb", "t1")));
        // An unbound profile (no proven machine) shows nothing of any machine's.
        assertTrue(outbox.shown(null, "pa", "t1").isEmpty());
    }

    @Test public void e10_aTerminalReplacedOnTheSameAddressIsAnotherLane() {
        Outbox outbox = fresh();
        Outbox.Message old = hold(outbox, A, "p", "t1", "for the old one");
        assertNull("another created_ms is another terminal", outbox.offer(live(A, "p", "t1", 99L)));
        assertEquals(old.id, outbox.offer(live(A, "p", "t1", 7L)).id);
    }

    @Test public void aMessageWithNoProvenMachineIsNeverHeld() {
        Outbox outbox = fresh();
        assertNull(outbox.add("x", target(null, "p", "t1", 7L), "hello", new Outbox.Composed(1, 1, 1)));
        assertEquals(0, outbox.size());
    }

    @Test public void e12_theQueueTellsTwoSessionsWithOneNameApart() {
        Outbox.Target one = new Outbox.Target(A, "p1", "home", "default", "t1", 1L, "", "");
        Outbox.Target two = new Outbox.Target(B, "p2", "home", "work", "t1", 1L, "", "");
        List<Outbox.Target> all = java.util.Arrays.asList(one, two);
        assertEquals("home (default) · t1", HeldWords.target(one, all));
        assertEquals("home (work) · t1", HeldWords.target(two, all));
        assertEquals("home · t1", HeldWords.target(one, Collections.singletonList(one)));
    }

    // ---- E5, E6: what a tap's write says ----

    @Test public void e5_e6_aWriteSaysOnlyHowFarItGot() {
        Outbox outbox = fresh();
        Outbox.Live here = live(A, "p", "t1", 7L);
        Outbox.Message dropped = hold(outbox, A, "p", "t1", "link dropped before the write");
        tap(outbox, dropped, here, Outbox.Outcome.UNWRITTEN);
        Outbox.Message pending = outbox.message(dropped.id);
        assertEquals("nothing was written: pending again", Outbox.State.PENDING, pending.state);
        assertTrue("and it needs a tap", pending.tapRequired);
        assertEquals(dropped.id, outbox.offer(here).id);
        tap(outbox, pending, here, Outbox.Outcome.UNSURE);
        Outbox.Message unsure = outbox.message(dropped.id);
        assertEquals(Outbox.State.NEEDS_YOU, unsure.state);
        assertNull("it stops its lane", outbox.offer(here));
        // Send again: pending, offered for one more tap.
        assertTrue(outbox.resend(unsure.id));
        assertEquals(dropped.id, outbox.offer(here).id);
        tap(outbox, outbox.message(dropped.id), here, Outbox.Outcome.WRITTEN);
        assertEquals(Outbox.State.SENT, outbox.message(dropped.id).state);
        // Sent may be sent again, at the user's word only.
        assertTrue(outbox.resend(dropped.id));
        assertEquals(Outbox.State.PENDING, outbox.message(dropped.id).state);
    }

    @Test public void anUnknownAnswerIsInDoubtNeverAResend() {
        assertEquals(Outbox.Outcome.UNSURE, Outbox.Outcome.parse("delivered"));
        assertEquals(Outbox.Outcome.UNSURE, Outbox.Outcome.parse(null));
        assertEquals(Outbox.Outcome.WRITTEN, Outbox.Outcome.parse("written"));
        assertEquals(Outbox.Outcome.UNWRITTEN, Outbox.Outcome.parse("unwritten"));
    }

    @Test public void aMessageTypedAtOnceFromTheLivePaneLeavesWhenWritten() {
        Outbox outbox = fresh();
        Outbox.Message m = hold(outbox, A, "p", "t1", "typed now");
        assertTrue(outbox.claim(m.id, live(A, "p", "t1", 7L), m.text, 1, true));
        outbox.settle(m.id, Outbox.Outcome.WRITTEN, 1, false);
        assertNull(outbox.message(m.id));
    }

    // ---- E8: a removed terminal ----

    @Test public void e8_aRemovedTerminalsMessagesNeedTheUserAndADoubtfulOneKeepsItsDoubt() {
        Outbox outbox = fresh();
        Outbox.Message pending = hold(outbox, A, "p", "t5", "for t5");
        Outbox.Message unsure = hold(outbox, A, "p", "t6", "maybe typed");
        Outbox.Message sent = hold(outbox, A, "p", "t7", "was written");
        tap(outbox, unsure, live(A, "p", "t6", 7L), Outbox.Outcome.UNSURE);
        tap(outbox, sent, live(A, "p", "t7", 7L), Outbox.Outcome.WRITTEN);
        Outbox.Message other = hold(outbox, B, "q", "t5", "B's t5 is still there");
        Map<String, Long> listed = new HashMap<>();
        listed.put("t1", 1L);
        assertTrue(outbox.observe(A, listed));
        Outbox.Message gone = outbox.message(pending.id);
        assertEquals(Outbox.State.NEEDS_YOU, gone.state);
        assertEquals(Outbox.Reason.GONE, gone.reason);
        assertEquals("needs your decision: t5 is gone", HeldWords.state(gone, false, java.util.TimeZone.getDefault(), true));
        assertEquals(Outbox.Reason.UNSURE, outbox.message(unsure.id).reason);
        assertEquals(Outbox.State.SENT, outbox.message(sent.id).state);
        assertEquals("another machine's lane is untouched", Outbox.State.PENDING, outbox.message(other.id).state);
        // The text is offered back as a draft (Edit as draft takes it out).
        assertEquals("for t5", outbox.take(gone.id));
        assertFalse("gone is never sent again", outbox.resend(other.id + "-none"));
    }

    @Test public void aTerminalReplacedOnItsAddressMakesItsPendingMessagesGone() {
        Outbox outbox = fresh();
        Outbox.Message m = hold(outbox, A, "p", "t5", "for the first t5");
        assertTrue(outbox.observe(A, Collections.singletonMap("t5", 99L)));
        assertEquals(Outbox.Reason.GONE, outbox.message(m.id).reason);
    }

    // ---- E9: a head that needs the user holds its lane ----

    @Test public void e9_theOthersWaitBehindAHeadThatNeedsYou() {
        Outbox outbox = fresh();
        Outbox.Live here = live(A, "p", "t1", 7L);
        Outbox.Message first = hold(outbox, A, "p", "t1", "first");
        Outbox.Message second = hold(outbox, A, "p", "t1", "second");
        hold(outbox, A, "p", "t1", "third");
        tap(outbox, first, here, Outbox.Outcome.UNSURE);
        assertNull(outbox.offer(here));
        assertFalse(outbox.claim(second.id, here, second.text, 0, false));
        assertEquals(3, outbox.waitingIn(here));
        assertEquals("3 older messages wait for you", HeldWords.olderWaiting(outbox.waitingIn(here)));
        assertEquals("1 older message waits for you", HeldWords.olderWaiting(1));
        // Settled ("It arrived"): the next is offered.
        assertEquals("first", outbox.take(first.id));
        assertEquals(second.id, outbox.offer(here).id);
    }

    // ---- E16: upgrade from phone-feedback ----

    @Test public void e16_v1MessagesAreMigratedOnceAsUnverifiedAndACancelledOneStaysCancelled() throws Exception {
        File v1 = new File(dir.getRoot(), Outbox.V1_FILE_NAME);
        long until = System.currentTimeMillis() + 60_000;
        String text = new JSONObject().put("version", 1).put("boots", new JSONObject().put("h", "boot"))
                .put("messages", new org.json.JSONArray()
                        .put(new JSONObject().put("id", "q").put("host", "h").put("pane", "t1").put("boot", "boot")
                                .put("text", "queued one").put("created", 100).put("state", "QUEUED").put("clearedUntil", 0))
                        .put(new JSONObject().put("id", "c").put("host", "h").put("pane", "t1").put("boot", "boot")
                                .put("text", "cancelled").put("created", 101).put("state", "QUEUED")
                                .put("clearedUntil", until))
                        .put(new JSONObject().put("id", "s").put("host", "h").put("pane", "t2").put("boot", "boot")
                                .put("text", "was sending").put("created", 102).put("state", "SENDING").put("clearedUntil", 0))
                        .put(new JSONObject().put("id", "g").put("host", "h").put("pane", "t3").put("boot", "boot")
                                .put("text", "gone one").put("created", 103).put("state", "GONE").put("clearedUntil", 0)))
                .toString();
        Files.write(v1.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Outbox outbox = fresh();
        Outbox.Message queued = outbox.message("q");
        assertEquals(Outbox.State.PENDING, queued.state);
        assertEquals(Outbox.Reason.UNVERIFIED, queued.reason);
        assertTrue(queued.unverified());
        assertTrue(HeldWords.state(queued, true, java.util.TimeZone.getDefault(), true)
                .startsWith("destination not verified"));
        assertEquals(Outbox.Reason.UNSURE, outbox.message("s").reason);
        assertEquals(Outbox.Reason.GONE, outbox.message("g").reason);
        assertFalse("cancelled stays cancelled", ids(outbox.all()).contains("c"));
        assertNotNull(outbox.message("c"));
        // Offered only in the pane of its old profile and pane, saying so; never by a guessed machine.
        Outbox.Message offered = outbox.offer(live(A, "h", "t1", 7L));
        assertEquals("q", offered.id);
        assertNull(outbox.offer(live(A, "other", "t1", 7L)));
        String heading = HeldWords.offer(offered, Collections.emptyList(), java.util.TimeZone.getDefault(), true,
                200, 0, 1);
        assertTrue(heading, heading.startsWith("destination not verified · typed "));
        // v1 is put aside; a second start migrates nothing twice.
        assertFalse(v1.exists());
        assertTrue(new File(v1.getPath() + Outbox.MIGRATED_SUFFIX).exists());
        Files.copy(new File(v1.getPath() + Outbox.MIGRATED_SUFFIX).toPath(), v1.toPath());
        Outbox again = fresh();
        assertEquals(4, again.size());
    }

    @Test public void anUnreadableFileIsPutAsideAndTheQueueStartsEmpty() throws Exception {
        Files.write(file().toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        Outbox outbox = fresh();
        assertTrue(outbox.damaged());
        assertEquals(0, outbox.size());
        File[] aside = dir.getRoot().listFiles((d, name) -> name.startsWith(Outbox.FILE_NAME + ".unreadable-"));
        assertEquals(1, aside.length);
    }

    // ---- the courier: a tap's attempt is on disk before the write; nothing is sent by itself ----

    @Test public void theCourierSavesTheAttemptBeforeWritingAndNeverSendsByItself() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        List<String> seenOnDisk = new ArrayList<>();
        OutboxCourier courier = new OutboxCourier(file(), Runnable::run, (handle, attach, text) -> {
            writes.incrementAndGet();
            try {
                seenOnDisk.add(new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            assertEquals(42, attach);
            return "{\"outcome\":\"written\"}";
        });
        Outbox.Message m = courier.add("sub-1", target(A, "p", "t1", 7L), "hello", new Outbox.Composed(1, 1, 1));
        assertNotNull(m);
        // Adding twice under one submission is one message.
        assertEquals(m.id, courier.add("sub-1", target(A, "p", "t1", 7L), "hello", new Outbox.Composed(1, 1, 1)).id);
        courier.observe(A, Collections.singletonMap("t1", 7L));
        assertEquals("held, never sent without a tap", 0, writes.get());
        List<Outbox.Outcome> outcomes = new ArrayList<>();
        courier.send(m.id, live(B, "p", "t1", 7L), 1, 42, "hello", false, outcomes::add);
        assertEquals("another machine's pane writes nothing", 0, writes.get());
        assertNull(outcomes.get(0));
        courier.send(m.id, live(A, "p", "t1", 7L), 1, 42, "hello", false, outcomes::add);
        assertEquals(1, writes.get());
        assertTrue("sending was on disk before the write", seenOnDisk.get(0).contains("\"state\":\"sending\""));
        assertEquals(Outbox.Outcome.WRITTEN, outcomes.get(1));
        assertEquals(Outbox.State.SENT, courier.outbox().message(m.id).state);
        // A second tap on a sent message writes nothing.
        courier.send(m.id, live(A, "p", "t1", 7L), 1, 42, "hello", false, outcomes::add);
        assertEquals(1, writes.get());
    }

    @Test public void aFailedSaveHoldsNothingAndWritesNothing() {
        AtomicInteger writes = new AtomicInteger();
        OutboxCourier courier = new OutboxCourier(file(), Runnable::run, (handle, attach, text) -> {
            writes.incrementAndGet();
            return "{\"outcome\":\"written\"}";
        });
        courier.outbox().failSaves = true;
        assertNull("the draft stays", courier.add("s", target(A, "p", "t1", 7L), "x", new Outbox.Composed(1, 1, 1)));
        assertEquals(0, courier.outbox().size());
        courier.outbox().failSaves = false;
        Outbox.Message m = courier.add("s", target(A, "p", "t1", 7L), "x", new Outbox.Composed(1, 1, 1));
        courier.outbox().failSaves = true;
        courier.send(m.id, live(A, "p", "t1", 7L), 1, 1, "x", false, null);
        assertEquals("no write without a saved attempt", 0, writes.get());
        assertEquals(Outbox.State.PENDING, courier.outbox().message(m.id).state);
    }

    // ---- age (§4.1) ----

    @Test public void ageIsMeasuredOnThePhonesOwnClockAndIsUnknownAcrossAReboot() {
        Outbox.Composed typed = new Outbox.Composed(1_000_000, 10_000, 3);
        assertEquals(47 * 60_000, typed.ageMs(10_000 + 47 * 60_000, 3));
        assertEquals(-1, typed.ageMs(10_000 + 47 * 60_000, 4));
        assertEquals(-1, typed.ageMs(5_000, 3));
        assertEquals("47 min ago", HeldWords.age(47 * 60_000));
        assertEquals("age unknown", HeldWords.age(-1));
        Outbox.Message m = new Outbox.Message("m", target(A, "p", "t1", 7L), "x", typed, Outbox.State.PENDING, null,
                false, 0, "");
        // The wall clock set back before the typing: only the time, age unknown.
        assertTrue(HeldWords.typed(m, 999_999, 10_000 + 60_000, 3, java.util.TimeZone.getDefault(), true)
                .endsWith("· age unknown"));
        String heading = HeldWords.offer(m, Collections.emptyList(), java.util.TimeZone.getTimeZone("UTC"), true,
                1_000_000 + 47 * 60_000, 10_000 + 47 * 60_000, 3);
        assertEquals("typed 00:16 for computer p · PM herdr · t1 · 47 min ago", heading);
    }

    private static List<String> ids(List<Outbox.Message> messages) {
        List<String> out = new ArrayList<>();
        for (Outbox.Message m : messages) out.add(m.id);
        return out;
    }
}
