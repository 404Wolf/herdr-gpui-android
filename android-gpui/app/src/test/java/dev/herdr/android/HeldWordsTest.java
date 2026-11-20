package dev.herdr.android;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** §3.7: the one queue groups every held message by computer, then by pane (E4, E12). */
public final class HeldWordsTest {
    private static Outbox.Message m(String id, String host, String pane) {
        return new Outbox.Message(id, HeldFixtures.target(host, pane), id, HeldFixtures.at(1), Outbox.State.PENDING,
                null, false, 0, "");
    }

    @Test public void theQueueGroupsByComputerThenPaneInTheOrderTheyWereTyped() {
        List<List<Outbox.Message>> groups = HeldWords.grouped(Arrays.asList(
                m("a1", "A", "t1"), m("b1", "B", "t1"), m("a2", "A", "t2"), m("a3", "A", "t1")));
        List<String> order = new ArrayList<>();
        for (List<Outbox.Message> g : groups) {
            StringBuilder ids = new StringBuilder();
            for (Outbox.Message x : g) ids.append(ids.length() == 0 ? "" : ",").append(x.id);
            order.add(ids.toString());
        }
        assertEquals(Arrays.asList("a1,a3", "a2", "b1"), order);
    }
}
