package dev.herdr.android;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The ghost-text JSON handed to the renderer. */
public final class GhostTextTest {
    private static final GhostText.Colors COLORS = new GhostText.Colors(0xffB2CCD6, 0xff4A4A4A, 0xffFFCB6B, 0xff000000);

    @Test public void theDraftSplitsIntoSettledWordsAPartialTailAndChips() throws Exception {
        String token = DictationToken.of("c-1");
        Map<String, Long> lengths = new HashMap<>();
        lengths.put("c-1", 9_000L);
        String draft = "fix " + token + " then the remote test";
        int partial = draft.indexOf("the remote");
        JSONObject o = new JSONObject(GhostText.json("claude", draft, partial, lengths,
                Collections.emptyList(), "t1", 18, 20, COLORS));
        assertEquals("claude", o.getString("kind"));
        assertEquals(18, o.getInt("reserve"));
        assertEquals(20, o.getInt("reserveFrom"));
        assertEquals(0xFFCB6B, o.getJSONObject("colors").getInt("warn"));
        JSONArray d = o.getJSONArray("draft");
        assertEquals("fix ", d.getJSONObject(0).getString("t"));
        assertEquals("◷ 0:09 waiting for desktop", d.getJSONObject(1).getString("t"));
        assertEquals("chip", d.getJSONObject(1).getString("s"));
        assertEquals(" then ", d.getJSONObject(2).getString("t"));
        assertEquals("the remote test", d.getJSONObject(3).getString("t"));
        assertEquals("partial", d.getJSONObject(3).getString("s"));
    }

    @Test public void queuedMessagesBecomeEchoesWithTheirState() throws Exception {
        Outbox.Message queued = new Outbox.Message("a", "h", "t1", "b", "first\nline", 1, Outbox.State.QUEUED, false);
        Outbox.Message unsure = new Outbox.Message("b", "h", "t1", "b", "second", 2, Outbox.State.UNSURE, false);
        JSONObject o = new JSONObject(GhostText.json("codex", "", -1, new HashMap<>(),
                Arrays.asList(queued, unsure), "t1", 0, 0, COLORS));
        JSONArray echoes = o.getJSONArray("echoes");
        assertEquals("❯ first ⏎ line", echoes.getJSONArray(0).getJSONObject(0).getString("t"));
        assertEquals("tag", echoes.getJSONArray(0).getJSONObject(1).getString("s"));
        assertEquals("  may have been sent", echoes.getJSONArray(1).getJSONObject(1).getString("t"));
        assertEquals("warn", echoes.getJSONArray(1).getJSONObject(1).getString("s"));
        assertEquals(0, o.getJSONArray("draft").length());
    }

    @Test public void kindsTheRendererDoesNotKnowAreOther() {
        assertEquals("claude", GhostText.kind("claude"));
        assertEquals("codex", GhostText.kind("codex"));
        assertEquals("other", GhostText.kind("aider"));
        assertEquals("other", GhostText.kind(null));
    }
}
