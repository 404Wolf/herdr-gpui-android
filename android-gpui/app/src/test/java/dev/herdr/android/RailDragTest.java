package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;

/** Dragging the rail's groups, repos and PMs (factory-r8f.8), without a window. */
public final class RailDragTest {
    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")), StandardCharsets.UTF_8);
    }

    @Test public void theRailScrollsNearItsEdgesFasterNearerThem() {
        int h = 1000, edge = 100, max = 36;
        assertEquals(0f, RailDrag.scrollStep(500, h, edge, max), 0f);
        assertEquals(-max, RailDrag.scrollStep(0, h, edge, max), 1e-3f);
        assertEquals(-max / 2f, RailDrag.scrollStep(50, h, edge, max), 1e-3f);
        assertEquals(max / 2f, RailDrag.scrollStep(950, h, edge, max), 1e-3f);
        assertEquals(max, RailDrag.scrollStep(1200, h, edge, max), 1e-3f);
        assertEquals(0f, RailDrag.scrollStep(10, 0, edge, max), 0f);
    }

    @Test public void aStillFingerAtAnEdgeKeepsScrollingAndTheTargetFollows() throws IOException {
        // Audit t483: the finger was kept in content terms, so scrolling moved it out of the edge zone.
        String drag = source("RailDrag");
        assertTrue(drag.contains("viewY = e.getY() - scroll.getScrollY();"));
        assertTrue(drag.contains("return scrollStep(viewY, scroll.getHeight(), dp(EDGE_DP), dp(MAX_SCROLL_DP));"));
        assertTrue(drag.contains("show(dragging, viewY + scroll.getScrollY());"));
        assertTrue(drag.contains("if (scroll.getScrollY() == before) { scrolling = false; return; }"));
        assertFalse(drag.contains("lastY"));
    }

    @Test public void aDropIsDrawnAtOnceAsTheCoreWritesIt() throws Exception {
        JSONObject order = new JSONObject("{\"repos\":[\"mono\"],\"projects\":{},\"groups\":[\"herdr\"],\"future\":1}");
        RailOrder.Move m = new RailOrder.Move("groups", "agents", "herdr", false, Arrays.asList("herdr", "agents"));
        JSONObject got = RailOrder.applied(order, m);
        assertEquals("[\"agents\",\"herdr\"]", got.getJSONArray("groups").toString());
        assertEquals(1, got.getInt("future"));
        assertEquals("[\"mono\"]", got.getJSONArray("repos").toString());
        JSONObject inGroup = RailOrder.applied(null, new RailOrder.Move("group:herdr", "b", "a", false, Arrays.asList("a", "b")));
        assertEquals("[\"b\",\"a\"]", inGroup.getJSONObject("grouped").getJSONArray("herdr").toString());
        JSONObject inRepo = RailOrder.applied(null, new RailOrder.Move("repo:", "b", "a", false, Arrays.asList("a", "b")));
        assertEquals("[\"b\",\"a\"]", inRepo.getJSONObject("projects").getJSONArray("").toString());
        // Nothing moves: the order as it was.
        assertTrue(RailOrder.applied(order, new RailOrder.Move("groups", "herdr", "herdr", true, Arrays.asList("herdr"))) == order);
    }

    @Test public void aLongPressArmsTheDragAndALiftInPlaceStillOpensTheMenu() throws IOException {
        String drag = source("RailDrag");
        assertTrue(drag.contains("row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);"));
        assertTrue(drag.contains("row.getParent().requestDisallowInterceptTouchEvent(true);"));
        assertTrue(drag.contains("getScaledTouchSlop()"));
        assertTrue(drag.contains("row.startDragAndDrop(ClipData.newPlainText(\"rail\", \"\"), new View.DragShadowBuilder(row), ref, 0)"));
        // Lifted without moving: the menu, as before.
        int up = drag.indexOf("case MotionEvent.ACTION_UP:");
        assertTrue(drag.substring(up, up + 300).contains("if (menu != null) menu.run();"));
        // Ended without a drop writes nothing and puts the row back.
        int ended = drag.indexOf("case DragEvent.ACTION_DRAG_ENDED:");
        assertTrue(drag.substring(ended, ended + 400).contains("dragged.setAlpha(1f)"));
        assertFalse(drag.substring(ended, ended + 400).contains("planner.drop"));
        String rail = source("RailController");
        assertTrue(rail.contains("if (!drag().arm(view, menu)) menu.run();"));
        assertTrue(rail.contains("if (!drag().arm(view, () -> showPaneActions(holder))) showPaneActions(holder);"));
        // Only a reachable machine's rows drag; a refusal is said; the move goes through order.move.
        assertTrue(rail.contains("if (live) drag().mark(groupRow.row, new RailDrag.Ref(host.id, RailOrder.At.section(\"group:\" + group.name)));"));
        assertTrue(rail.contains("a.note(\"Can't move it there: \" + plan.why);"));
        assertTrue(rail.contains(".put(\"op\", \"order.move\")"));
        assertTrue(rail.contains(".put(\"op\", \"group.set\")"));
        assertEquals(20, FleetTree.RAIL_DEPTH_DP);
    }

    @Test public void aPlanOnTheDrawnTreeRefusesAGroupAmongTheRepos() throws Exception {
        FleetTree.PmTree tree = new FleetTree.PmTree();
        assertNull(RailOrder.dropPlan(FleetTree.orderSections(tree), RailOrder.At.section("group:x"),
                RailOrder.At.section("repo:y"), false, null));
    }
}
