package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Where a switch lands, against a scripted session and a fake clock (audit PR 12, test gap I). */
public final class HostLandingTest {
    /** A session that answers each poll with the next scripted view (the last one repeats). */
    private static final class Script implements HostLanding.Link, HostLanding.Memory {
        final Deque<JSONObject> views = new ArrayDeque<>();
        final List<String> focused = new ArrayList<>();
        final Map<String, String> remembered = new HashMap<>();
        long now;
        /** What the session shows once a pane is focused: the pane attached, or refused. */
        java.util.function.Function<String, JSONObject> onFocus = pane -> view("ready", pane);
        JSONObject last;

        @Override public JSONObject poll() {
            if (!views.isEmpty()) last = views.poll();
            return last;
        }

        @Override public void focus(String pane) {
            focused.add(pane);
            views.clear();
            views.add(onFocus.apply(pane));
        }

        @Override public String remembered(String host) {
            return remembered.getOrDefault(host, "");
        }

        @Override public void remember(String host, String pane) {
            remembered.put(host, pane);
        }

        HostLanding landing() {
            return new HostLanding(this, this, () -> now, ms -> now += ms);
        }
    }

    private static JSONObject pane(String id, String status) throws Exception {
        return new JSONObject().put("id", id).put("status", status);
    }

    private static JSONObject view(String state, String paneId) {
        try {
            return new JSONObject().put("state", state).put("pane_id", paneId)
                    .put("panes", new JSONArray().put(pane("t1", "idle")).put(pane("t2", "working")).put(pane("t3", "idle")));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test public void anExplicitPickLandsAndIsRemembered() throws Exception {
        Script s = new Script();
        s.views.add(view("connecting", ""));
        s.views.add(view("ready", ""));
        assertEquals("t3", s.landing().await("server", "t3", false, 10_000));
        assertEquals(List.of("t3"), s.focused);
        assertEquals("t3", s.remembered.get("server"));
    }

    @Test public void withoutAPickItTakesTheRememberedPaneThenOneThatIsWorking() throws Exception {
        Script s = new Script();
        s.views.add(view("ready", ""));
        s.remembered.put("server", "t1");
        assertEquals("t1", s.landing().await("server", null, false, 10_000));
        Script fresh = new Script();
        fresh.views.add(view("ready", ""));
        assertEquals("an idle shell reads as blank: a working pane first", "t2", fresh.landing().await("laptop", "", false, 10_000));
    }

    @Test public void aRefusedPickSaysWhyAtOnceInsteadOfWaiting() throws Exception {
        Script s = new Script();
        s.views.add(view("ready", ""));
        s.onFocus = pane -> {
            JSONObject v = view("ready", "");
            try {
                v.put("refused", new JSONObject().put("terminal", pane).put("reason", "no terminal \"" + pane + "\""));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return v;
        };
        try {
            s.landing().await("macro", "t1", false, 10_000);
            fail("refused");
        } catch (HostLanding.Refused refused) {
            assertEquals("t1", refused.pane);
            assertEquals("no terminal \"t1\"", refused.reason);
        }
        assertTrue("well inside the 20 s landing wait", s.now < 1_000);
    }

    @Test public void aSessionThatNeverListsGivesUpAtItsTimeAndAClosedOneSaysWhy() throws Exception {
        Script silent = new Script();
        silent.views.add(view("connecting", "").put("panes", new JSONArray()));
        try {
            silent.landing().await("server", "t1", false, 5_000);
            fail("no answer");
        } catch (IllegalStateException e) {
            assertEquals("no answer within 5 s", e.getMessage());
        }
        assertTrue(silent.now >= 5_000 && silent.now < 5_500);
        Script closed = new Script();
        closed.views.add(new JSONObject().put("state", "closed").put("status", "host key changed"));
        try {
            closed.landing().await("server", "t1", false, 5_000);
            fail("closed");
        } catch (IllegalStateException e) {
            assertEquals("closed: host key changed", e.getMessage());
        }
    }

    @Test public void aPaneThatNeverAttachesIsBoundedByTheLandingWait() throws Exception {
        Script s = new Script();
        s.views.add(view("ready", ""));
        s.onFocus = pane -> view("connecting", "");
        assertEquals("t3", s.landing().await("server", "t3", false, 1_000));
        assertTrue("one landing wait, not three", s.now <= 1_000 + HostLanding.FOCUS_MS + HostLanding.STEP_MS);
    }

    @Test public void choosingFallsBackInOrder() throws Exception {
        JSONArray panes = new JSONArray().put(pane("a", "unknown")).put(pane("b", "idle"));
        JSONObject state = new JSONObject().put("focused_pane", "a").put("panes", panes);
        assertEquals("a stale pick falls to the daemon's focus (as before the move)", "a", HostLanding.choose(state, panes, "gone", false, ""));
        JSONArray unknown = new JSONArray().put(pane("a", "unknown")).put(pane("c", "unknown"));
        assertEquals("c", HostLanding.choose(new JSONObject().put("focused_pane", "c"), unknown, null, false, ""));
        assertEquals("a", HostLanding.choose(new JSONObject(), unknown, null, false, ""));
    }
}
