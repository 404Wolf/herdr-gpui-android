package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** §1.1: a profile revision reaches a machine only by its own hello; collisions are refused (E10, E11). */
public final class MachineBindingsTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private MachineBindings fresh() {
        MachineBindings bindings = new MachineBindings(new File(dir.getRoot(), MachineBindings.FILE_NAME));
        bindings.load();
        return bindings;
    }

    @Test public void theMachineKeyIsTheCoreIdAndTheHostKeyTogether() {
        assertEquals(MachineKey.of("c", "k"), MachineKey.of("c", "k"));
        assertFalse(MachineKey.of("c", "k").equals(MachineKey.of("c", "other key")));
        assertNull(MachineKey.of("", "k"));
        assertNull(MachineKey.of("c", null));
        assertNull(MachineKey.of("c\n", "k"));
    }

    @Test public void e10_anEditedProfileIsUnboundUntilItsOwnHello() {
        MachineBindings bindings = fresh();
        MachineKey a = MachineKey.of("core-a", "key-a");
        MachineKey b = MachineKey.of("core-b", "key-b");
        assertTrue(bindings.bind("p", "rev1", "default", "home", a).bound());
        assertEquals(a, bindings.machine("p", "rev1"));
        // Edited to another machine: a new revision, unbound, shows and sends nothing of A's.
        assertNull(bindings.machine("p", "rev2"));
        assertTrue(bindings.bind("p", "rev2", "default", "home", b).bound());
        assertEquals(b, bindings.machine("p", "rev2"));
        // Survives a restart.
        MachineBindings again = fresh();
        assertEquals(a, again.machine("p", "rev1"));
        assertEquals(b, again.machine("p", "rev2"));
    }

    @Test public void e11_aCloneWithItsOwnHostKeyIsAnotherMachine() {
        MachineBindings bindings = fresh();
        assertTrue(bindings.bind("pa", "r", "offA", "A", MachineKey.of("core-1", "key-a")).bound());
        // offC: offA's state copied, behind another sshd with its own host key.
        assertTrue(bindings.bind("pc", "r", "offA", "C", MachineKey.of("core-1", "key-c")).bound());
        assertFalse(bindings.machine("pa", "r").equals(bindings.machine("pc", "r")));
    }

    @Test public void e11_aSecondSessionReportingABoundMachineIsRefusedAndWorksLiveOnly() {
        MachineBindings bindings = fresh();
        MachineKey a = MachineKey.of("core-1", "key-a");
        assertTrue(bindings.bind("pa", "r", "offA", "A", a).bound());
        // Two profiles on the same session share one machine.
        assertTrue(bindings.bind("pa2", "r", "offA", "A again", a).bound());
        MachineBindings.Binding refused = bindings.bind("pb", "r", "offB", "B", a);
        assertFalse(refused.bound());
        assertEquals("A", refused.refusedBy);
        assertNull("refused: no machine, so nothing is held or offered", bindings.machine("pb", "r"));
        assertEquals("A", fresh().binding("pb", "r").refusedBy);
        // The first binding keeps its machine.
        assertEquals(a, bindings.machine("pa", "r"));
    }
}
