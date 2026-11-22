package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Which bead an agent works on, by its hidden labels (for Tasks); and no beads in the rail (Wolf). */
public final class BeadsLinkTest {
    private static BeadsModel.Term term(String id, String role, String parent, String project, String issue) {
        BeadsModel.Term t = new BeadsModel.Term();
        t.id = id;
        t.role = role;
        t.parent = parent;
        t.project = project;
        t.issue = issue;
        t.kind = "claude";
        return t;
    }

    @Test public void aWorkerHasItsOwnBeadAHelperItsWorkersAndNoneIsNeverGuessed() {
        List<BeadsModel.Term> m = Arrays.asList(
                term("t1", "pm", "", "factory", "factory-setup"),
                term("t2", "worker", "t1", "factory", "factory-setup.22"),
                term("t3", "", "t2", "", ""),
                term("t4", "", "t2", "", "factory-setup.40"),
                term("t5", "worker", "t1", "factory", ""),
                term("t6", "", "t5", "", ""));
        assertEquals("factory-setup.22", BeadsModel.beadOf("t2", m));
        assertEquals("factory-setup.22", BeadsModel.beadOf("t3", m));
        assertEquals("factory-setup.40", BeadsModel.beadOf("t4", m));
        // Not the PM's own bead: the chain stops at the lead.
        assertNull(BeadsModel.beadOf("t5", m));
        assertNull(BeadsModel.beadOf("t6", m));
        assertNull(BeadsModel.beadOf("nobody", m));
        assertEquals("factory", BeadsModel.projectOf("t3", m));
    }

    @Test public void theRailShowsNoBeads() throws IOException {
        String rail = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/GpuiTerminalActivity.java")),
                StandardCharsets.UTF_8);
        for (String beads : new String[] {"BeadsRail", "beads.stores", "beads.detail", "☰ Beads", "☰!"}) {
            assertFalse(beads, rail.contains(beads));
        }
    }
}
