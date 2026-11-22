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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** factory-9zz.2: pinned agents, keyed by machine + address + created_ms, from proven lists only. */
public final class PinsTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final MachineKey A = MachineKey.of("core-a", "SHA256:host-a");
    private static final MachineKey B = MachineKey.of("core-b", "SHA256:host-b");
    private static final String INC = "0123456789abcdef";
    private static final String INC2 = "fedcba9876543210";

    private long now = 1_000;
    /** Every disk write the store asked for: nothing else may be scheduled (no polling). */
    private final List<Runnable> scheduled = new ArrayList<>();
    private final Executor disk = task -> { scheduled.add(task); task.run(); };

    private File file() {
        return new File(dir.getRoot(), Pins.FILE_NAME);
    }

    private Pins fresh() {
        Pins pins = new Pins(file(), disk, () -> now);
        pins.load();
        return pins;
    }

    private static Pins.Key key(MachineKey m, String address, long created) {
        return Pins.Key.of(m, address, created);
    }

    private static JSONArray list(Object... idCreatedIncarnation) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < idCreatedIncarnation.length; i += 3) {
            out.put(new JSONObject().put("id", idCreatedIncarnation[i]).put("created_ms", idCreatedIncarnation[i + 1])
                    .put("incarnation", idCreatedIncarnation[i + 2] == null ? JSONObject.NULL : idCreatedIncarnation[i + 2])
                    .put("status", "working"));
        }
        return out;
    }

    private Pins.Toggle pin(Pins pins, Pins.Key key) {
        return pins.toggle(key, INC, "profile-a", "default", "phone-rail", "desktop", "working");
    }

    // ---- identity ----

    @Test public void aPinNeedsAProvenMachineAPlainAddressAndACreationTime() {
        assertNotNull(key(A, "t5", 7));
        assertNull("no machine", key(null, "t5", 7));
        assertNull("relayed address", key(A, "laptop/t5", 7));
        assertNull("no creation time", Pins.Key.of(A, "t5", null));
        assertNull(key(A, "t5", 0));
        assertEquals(key(A, "t5", 7), key(A, "t5", 7));
        assertFalse(key(A, "t5", 7).equals(key(A, "t5", 8)));
        assertFalse(key(A, "t5", 7).equals(key(B, "t5", 7)));
        assertEquals(Pins.Toggle.INVALID, fresh().toggle(null, INC, "p", "s", "x", "m", ""));
    }

    @Test public void aReusedAddressEndsThePinForGood() throws Exception {
        Pins pins = fresh();
        Pins.Key k = key(A, "t5", 7);
        assertEquals(Pins.Toggle.PINNED, pin(pins, k));
        pins.observe(A, list("t5", 8L, INC));
        assertEquals(Pins.Seen.REPLACED, pins.get(k).seen);
        // Sticky: a later list with the original values again never revives it.
        pins.observe(A, list("t5", 7L, INC));
        assertTrue(pins.get(k).ended());
    }

    @Test public void aTerminalMissingFromAProvenListIsEnded() throws Exception {
        Pins pins = fresh();
        Pins.Key k = key(A, "t5", 7);
        pin(pins, k);
        pins.observe(A, list("t6", 9L, INC));
        assertEquals(Pins.Seen.GONE, pins.get(k).seen);
    }

    @Test public void anotherMachinesListNeverChangesThePin() throws Exception {
        Pins pins = fresh();
        Pins.Key k = key(A, "t5", 7);
        pin(pins, k);
        pins.observe(B, list("t6", 9L, INC));
        assertEquals(Pins.Seen.LISTED, pins.get(k).seen);
        pins.observe(null, list());
        assertEquals(Pins.Seen.LISTED, pins.get(k).seen);
    }

    @Test public void aNewProgramInTheSameTerminalKeepsThePinMarkedRestartedUntilOpened() throws Exception {
        Pins pins = fresh();
        Pins.Key k = key(A, "t5", 7);
        pin(pins, k);
        assertFalse(pins.get(k).restarted());
        pins.observe(A, list("t5", 7L, INC2));
        assertEquals(Pins.Seen.LISTED, pins.get(k).seen);
        assertTrue(pins.get(k).restarted());
        pins.opened(k);
        assertFalse(pins.get(k).restarted());
        assertEquals(INC2, pins.get(k).incarnation);
        // A core that stops saying keeps the last known program, and no restart is invented.
        pins.observe(A, list("t5", 7L, null));
        assertFalse(pins.get(k).restarted());
    }

    // ---- verify (the switch never lands anywhere else) ----

    @Test public void verifyDemandsTheSameMachineAndTheSameTerminal() throws Exception {
        Pins.Key k = key(A, "t5", 7);
        assertEquals(Pins.Verify.GO, Pins.verify(k, "core-a", "SHA256:host-a", list("t5", 7L, INC)));
        assertEquals(Pins.Verify.WRONG_MACHINE, Pins.verify(k, "core-a", "SHA256:other", list("t5", 7L, INC)));
        assertEquals(Pins.Verify.WRONG_MACHINE, Pins.verify(k, "core-b", "SHA256:host-a", list("t5", 7L, INC)));
        assertEquals(Pins.Verify.ENDED, Pins.verify(k, "core-a", "SHA256:host-a", list("t6", 7L, INC)));
        assertEquals(Pins.Verify.ENDED, Pins.verify(k, "core-a", "SHA256:host-a", list("t5", 8L, INC)));
        assertEquals(Pins.Verify.NOT_LISTED_YET, Pins.verify(k, "core-a", "SHA256:host-a", null));
    }

    // ---- states, from what is already known ----

    @Test public void stateTruthTable() throws Exception {
        Pins pins = fresh();
        Pins.Key k = key(A, "t5", 7);
        pin(pins, k);
        Pins.Pin p = pins.get(k);
        Pins.Context c = new Pins.Context();
        assertEquals("nothing known: its saved state", Pins.State.OFFLINE, Pins.state(p, c));
        c.onScreen = A;
        c.screenLive = true;
        assertEquals(Pins.State.LIVE, Pins.state(p, c));
        c.screenLive = false;
        c.reconnecting = true;
        assertEquals(Pins.State.RECONNECTING, Pins.state(p, c));
        c.onScreen = B;
        c.mirrored = new HashSet<>(Collections.singleton(k));
        assertEquals("a proven mirror of its machine lists it", Pins.State.LIVE, Pins.state(p, c));
        c.unresolved = new HashSet<>(Collections.singleton(k));
        assertEquals(Pins.State.UNAVAILABLE, Pins.state(p, c));
        pins.observe(A, list());
        assertEquals("ended wins over everything", Pins.State.ENDED, Pins.state(pins.get(k), c));
    }

    // ---- resolve ----

    @Test public void resolvePrefersTheHintThenAnyProfileBoundToTheMachine() throws Exception {
        Pins pins = fresh();
        Pins.Key k = key(A, "t5", 7);
        pin(pins, k);
        Pins.Pin p = pins.get(k);
        Pins.Candidate hint = new Pins.Candidate("profile-a", A, false, true);
        Pins.Candidate other = new Pins.Candidate("profile-z", A, false, true);
        Pins.Route r = Pins.resolve(p, Arrays.asList(other, hint));
        assertEquals("profile-a", r.profile);
        assertFalse(r.mustVerify);
        // The hint was edited (unbound revision) but another profile is bound: that one.
        r = Pins.resolve(p, Arrays.asList(new Pins.Candidate("profile-a", null, false, true), other));
        assertEquals("profile-z", r.profile);
        // Only the edited hint: tried, and its hello must prove the machine.
        r = Pins.resolve(p, Collections.singletonList(new Pins.Candidate("profile-a", null, false, true)));
        assertEquals("profile-a", r.profile);
        assertTrue(r.mustVerify);
        // Edited to another machine, refused, untrusted or deleted: unavailable.
        assertNull(Pins.resolve(p, Collections.singletonList(new Pins.Candidate("profile-a", B, false, true))));
        assertNull(Pins.resolve(p, Collections.singletonList(new Pins.Candidate("profile-a", A, true, true))));
        assertNull(Pins.resolve(p, Collections.singletonList(new Pins.Candidate("profile-a", A, false, false))));
        assertNull(Pins.resolve(p, Collections.emptyList()));
    }

    // ---- store ----

    @Test public void pinsRoundTripThroughTheFile() throws Exception {
        Pins pins = fresh();
        Pins.Key k = key(A, "t5", 7);
        pin(pins, k);
        pins.observe(A, list("t5", 7L, INC2));
        Pins again = fresh();
        Pins.Pin p = again.get(k);
        assertNotNull(p);
        assertEquals("phone-rail", p.label);
        assertEquals("desktop", p.machineName);
        assertTrue(p.restarted());
        assertEquals(Pins.Seen.LISTED, p.seen);
    }

    @Test public void toggleUnpinsAndTheThirteenthIsRefused() {
        Pins pins = fresh();
        for (int i = 1; i <= Pins.MAX_PINS; i++) assertEquals(Pins.Toggle.PINNED, pin(pins, key(A, "t" + i, i)));
        assertEquals(Pins.Toggle.FULL, pin(pins, key(A, "t99", 99)));
        assertEquals(Pins.Toggle.UNPINNED, pin(pins, key(A, "t1", 1)));
        assertFalse(pins.pinned(key(A, "t1", 1)));
        assertEquals(Pins.MAX_PINS - 1, pins.all().size());
    }

    @Test public void nothingIsPinnableOrObservedBeforeTheFileIsRead() throws Exception {
        Pins pins = new Pins(file(), disk, () -> now);
        assertEquals(Pins.Toggle.NOT_LOADED, pin(pins, key(A, "t5", 7)));
        pins.observe(A, list("t5", 7L, INC));
        assertTrue(scheduled.isEmpty());
    }

    @Test public void aDamagedOrNewerFileGivesNoPinsAndIsKeptAsideOnTheNextWrite() throws Exception {
        Files.write(file().toPath(), "{\"v\":2,\"pins\":[]}".getBytes(StandardCharsets.UTF_8));
        Pins pins = fresh();
        assertTrue(pins.all().isEmpty());
        assertEquals("{\"v\":2,\"pins\":[]}", new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8));
        pin(pins, key(A, "t5", 7));
        assertTrue(new File(file().getPath() + ".bad").isFile());
        assertEquals(1, fresh().all().size());

        Files.write(file().toPath(), "not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(fresh().all().isEmpty());
    }

    @Test public void anOversizeFileIsIgnoredAndStringsAreBounded() throws Exception {
        byte[] big = new byte[Pins.MAX_BYTES + 1];
        Arrays.fill(big, (byte) ' ');
        Files.write(file().toPath(), big);
        assertTrue(fresh().all().isEmpty());
        file().delete();
        Pins pins = fresh();
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 500; i++) longName.append('x');
        pins.toggle(key(A, "t5", 7), INC, "p", "s", longName + "\n", "m\u0007", "");
        Pins.Pin p = pins.get(key(A, "t5", 7));
        assertEquals(Pins.MAX_TEXT, p.label.length());
        assertEquals("m", p.machineName);
    }

    @Test public void theStoreSchedulesOnlyWritesAndOnlyOnChange() throws Exception {
        Pins pins = fresh();
        Pins.Key k = key(A, "t5", 7);
        pin(pins, k);
        int writes = scheduled.size();
        now += 60_000;
        pins.observe(A, list("t5", 7L, INC));
        assertEquals("the same list again writes nothing", writes, scheduled.size());
        assertEquals("but the as-of time in memory moves", now, pins.get(k).asOfMs);
        pins.observe(A, list("t6", 9L, INC));
        assertEquals(writes + 1, scheduled.size());
    }
}
