package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The rail's order and drop plans against the cases shared with the
 * desktop's projects model (order-cases.json, t47c's, home-manager-conf
 * 8cfb8310): the same sections and the same drops on every surface.
 */
public final class RailOrderTest {
    private static JSONObject fixture() throws Exception {
        try (InputStream in = RailOrderTest.class.getClassLoader().getResourceAsStream("order-cases.json")) {
            assertNotNull("order-cases.json is on the test classpath", in);
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static List<RailOrder.Item> items(JSONArray projects) throws Exception {
        List<RailOrder.Item> out = new ArrayList<>();
        for (int i = 0; i < projects.length(); i++) {
            JSONObject p = projects.getJSONObject(i);
            out.add(new RailOrder.Item(p.getString("name"), p.optString("lead", ""), p.optLong("born", 0),
                    p.optString("group", ""), p.optString("old_group", ""), p.has("repo") ? p.getString("repo") : null));
        }
        return out;
    }

    private static JSONObject order(JSONObject c) {
        return c.optJSONObject("order");
    }

    private static JSONArray drawn(List<RailOrder.Section> sections) throws Exception {
        JSONArray out = new JSONArray();
        for (RailOrder.Section s : sections) {
            JSONArray names = new JSONArray();
            for (RailOrder.Item p : s.projects) names.put(p.name);
            out.put(new JSONArray().put(s.key).put(names));
        }
        return out;
    }

    @Test public void theSectionsAreTheDesktopsSections() throws Exception {
        JSONArray cases = fixture().getJSONArray("sections");
        assertTrue(cases.length() >= 3);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            List<RailOrder.Section> got = RailOrder.sections(items(c.getJSONArray("projects")), order(c));
            assertEquals(c.getString("name"), c.getJSONArray("expect").toString(), drawn(got).toString());
        }
    }

    private static RailOrder.At at(JSONObject where, List<RailOrder.Section> sections) throws Exception {
        if (where.has("section")) return RailOrder.At.section(where.getString("section"));
        String name = where.getString("project");
        for (RailOrder.Section s : sections) for (RailOrder.Item p : s.projects) {
            if (p.name.equals(name)) return RailOrder.At.project(p.orderId, s.key);
        }
        throw new AssertionError("no project " + name);
    }

    @Test public void everyDropPlansWhatTheDesktopPlans() throws Exception {
        JSONArray cases = fixture().getJSONArray("drops");
        assertTrue(cases.length() >= 10);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            List<RailOrder.Section> sections = RailOrder.sections(items(c.getJSONArray("projects")), order(c));
            RailOrder.Plan plan = RailOrder.dropPlan(sections, at(c.getJSONObject("item"), sections),
                    at(c.getJSONObject("over"), sections), c.getBoolean("after"), order(c));
            if (c.isNull("expect")) { assertNull(name, plan); continue; }
            JSONObject want = c.getJSONObject("expect");
            assertNotNull(name, plan);
            assertEquals(name, want.getString("op"), plan.op.name().toLowerCase(java.util.Locale.ROOT));
            if (want.has("label")) {
                JSONObject label = want.getJSONObject("label");
                assertEquals(name, label.getJSONArray("leads").toString(), new JSONArray(plan.leads).toString());
                assertEquals(name, label.isNull("group") ? null : label.getString("group"), plan.group);
            }
            if (want.has("move")) {
                JSONObject m = want.getJSONObject("move");
                assertNotNull(name, plan.move);
                assertEquals(name, m.getString("scope"), plan.move.scope);
                assertEquals(name, m.getString("id"), plan.move.id);
                assertEquals(name, m.getString("target"), plan.move.target);
                assertEquals(name, m.getBoolean("after"), plan.move.after);
                assertEquals(name, m.getJSONArray("peers").toString(), new JSONArray(plan.move.peers).toString());
            }
        }
    }

    @Test public void twoPmsWithOneNameAreToldApartByTheirLead() throws Exception {
        List<RailOrder.Item> items = new ArrayList<>();
        items.add(new RailOrder.Item("app", "t1", 1, "herdr", "", null));
        items.add(new RailOrder.Item("app", "t2", 2, "herdr", "", null));
        List<RailOrder.Section> s = RailOrder.sections(items, null);
        assertEquals("app#t1", s.get(0).projects.get(0).orderId);
        assertEquals("app#t2", s.get(0).projects.get(1).orderId);
    }
}
