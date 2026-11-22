package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** factory-9zz.12: a full-screen agent's transcript is its own session file, not its screen. */
public final class AgentHistoryTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();
    private static final MachineKey A = MachineKey.of("core-a", "SHA256:host-a");
    private static final String INC = "0123456789abcdef";
    private static final String SESSION = "01a1f1fe-268f-7ce3-9547-c422c66af57b";

    /** A session that answers each op with the next scripted reply, and keeps what was asked. */
    private static final class Wire implements TranscriptKeeper.Wire {
        final List<String> asked = new ArrayList<>();
        final List<String> replies = new ArrayList<>();
        @Override public String read(long handle, String terminal, int lines, int maxBytes) {
            return "{\"supported\":true,\"text\":\"the screen\",\"screen\":true,\"incarnation\":\"" + INC + "\"}";
        }
        @Override public String op(long handle, String json) {
            asked.add(json);
            return replies.isEmpty() ? null : replies.remove(0);
        }
    }

    @Test public void anAgentsListedLabelsAreReadOnlyWhenPlain() throws Exception {
        JSONArray list = new JSONArray()
                .put(new JSONObject().put("id", "t5").put("kind", "codex").put("session", SESSION))
                .put(new JSONObject().put("id", "t6").put("kind", "claude").put("session", "../etc"))
                .put(new JSONObject().put("id", "t7").put("kind", "").put("session", SESSION));
        String[] agent = TranscriptKeeper.agentOf(list, "t5");
        assertEquals("codex", agent[0]);
        assertEquals(SESSION, agent[1]);
        assertNull("a session that could name a path is never asked for", TranscriptKeeper.agentOf(list, "t6"));
        assertNull("a shell has no history file", TranscriptKeeper.agentOf(list, "t7"));
    }

    @Test public void theHistoryIsSavedAsTheTranscriptFoundOnceThenOnlyChecked() throws Exception {
        Transcripts store = new Transcripts(dir.getRoot());
        store.start();
        Wire wire = new Wire();
        TranscriptKeeper keeper = new TranscriptKeeper(store, Runnable::run, Runnable::run, wire, () -> 0, () -> 1_000);
        TranscriptKey key = TranscriptKey.of(A, "t5", 7L, INC);
        TranscriptKeeper.Link link = new TranscriptKeeper.Link(1, 1, A, true, false, "t5", 0, new JSONArray());
        String[] agent = {"codex", SESSION};
        wire.replies.add("{\"queued\":true,\"path\":\"/h/.codex/sessions/rollout-" + SESSION + ".jsonl\",\"key\":\"10:20\",\"text\":\"› hi\\n\",\"truncated\":false}");
        assertTrue(keeper.readHistory(link, key, agent));
        assertFalse("found by labels only the first time", wire.asked.get(0).contains("\"path\""));
        store.observe(A, Collections.singletonMap("t5", new Transcripts.Seen(7L, INC)), 1_500);
        Transcripts.Transcript shown = store.proven(A, "t5");
        assertEquals("› hi", shown.text);
        assertFalse("the history, not the screen", shown.screen);
        // Next time: the path and size:mtime go back, and "same" keeps the saved history.
        wire.replies.add("{\"queued\":true,\"same\":true,\"path\":\"p\",\"key\":\"10:20\"}");
        assertTrue(keeper.readHistory(link, key, agent));
        assertTrue(wire.asked.get(1).contains("\"key\":\"10:20\"") && wire.asked.get(1).contains("\"path\""));
        // No file (or two): the screen read stands, nothing pretends to be history.
        TranscriptKey other = TranscriptKey.of(A, "t6", 8L, INC);
        wire.replies.add("{\"queued\":true,\"unavailable\":\"its session file is not on that machine\"}");
        assertFalse(keeper.readHistory(link, other, agent));
        assertFalse("no agent, no ask", keeper.readHistory(link, other, null));
        assertEquals(3, wire.asked.size());
    }
}
