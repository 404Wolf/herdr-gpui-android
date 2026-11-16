package dev.herdr.android;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The overflow menu's orders: valid for Android's MenuBuilder, and in the order shown. */
public final class MoreMenuTest {
    @Test public void everyOrderIsAValidMenuOrderAndTheyRunTopToBottom() {
        int[] orders = MoreMenu.all();
        for (int i = 0; i < orders.length; i++) {
            // MenuBuilder.getOrdering: (order & 0xffff0000) >> 16 must name a category; 0 is "none".
            assertTrue("order " + orders[i] + " has no category bits", (orders[i] & 0xffff0000) == 0);
            if (i > 0) assertTrue("panes, hosts, view, session, close, gestures", orders[i] > orders[i - 1]);
        }
    }
}
