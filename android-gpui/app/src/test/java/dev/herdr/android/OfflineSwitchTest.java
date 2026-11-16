package dev.herdr.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.Test;

/** Item 77: which way a pane switch goes, and how a cached pane is labelled. */
public final class OfflineSwitchTest {
    private static final TimeZone NY = TimeZone.getTimeZone("America/New_York");

    @Test public void aLiveSessionSwitchesThroughTheCore() {
        assertEquals(OfflineSwitch.Route.LIVE, OfflineSwitch.route(false, false, 7));
    }

    @Test public void aDroppedLinkOrAComputerWithNoSessionSwitchesFromTheCache() {
        // The link dropped: the session ended, the monitor is reconnecting.
        assertEquals(OfflineSwitch.Route.OFFLINE, OfflineSwitch.route(false, true, 7));
        // Opened, or switched to, with no link: a stand-in with no session.
        assertEquals(OfflineSwitch.Route.OFFLINE, OfflineSwitch.route(true, true, 0));
    }

    @Test public void somethingThatIsNotAHerdrSessionIsNotSwitched() {
        assertEquals(OfflineSwitch.Route.REFUSE, OfflineSwitch.route(false, false, 0));
        assertEquals(OfflineSwitch.Route.REFUSE, OfflineSwitch.route(false, true, 0));
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(NY, Locale.US);
        c.clear();
        c.set(year, month - 1, day, hour, minute);
        return c.getTimeInMillis();
    }

    @Test public void theAgeReadsAsATimeTodayAWeekdayThisWeekElseADate() {
        long now = at(2026, 11, 17, 16, 5);
        assertEquals("14:32", OfflineSwitch.when(at(2026, 11, 17, 14, 32), now, NY, true, Locale.US));
        assertEquals("2:32 PM", OfflineSwitch.when(at(2026, 11, 17, 14, 32), now, NY, false, Locale.US));
        assertEquals("Sun 14:32", OfflineSwitch.when(at(2026, 11, 15, 14, 32), now, NY, true, Locale.US));
        assertEquals("Nov 2 09:10", OfflineSwitch.when(at(2026, 11, 2, 9, 10), now, NY, true, Locale.US));
        // Just before midnight yesterday is not "today".
        assertEquals("Mon 23:59", OfflineSwitch.when(at(2026, 11, 16, 23, 59), now, NY, true, Locale.US));
    }

    @Test public void theLabelSaysCachedWhenAndWhyItIsNotLive() {
        assertEquals("cached 14:32 · offline", OfflineSwitch.label("14:32", true, 0));
        assertEquals("cached Tue 14:32 · offline", OfflineSwitch.label("Tue 14:32", true, 0));
        assertEquals("cached 14:32 · connecting", OfflineSwitch.label("14:32", false, 0));
        assertEquals("cached 14:32 · offline · 1 waits", OfflineSwitch.label("14:32", true, 1));
        assertEquals("cached 14:32 · offline · 3 wait", OfflineSwitch.label("14:32", true, 3));
        assertEquals("not cached · offline", OfflineSwitch.label(null, true, 0));
        assertEquals("not cached · opens when the link is back", OfflineSwitch.NOT_CACHED);
    }

    @Test public void theNoteFillsTheTerminalsGrid() {
        assertArrayEquals(new int[] {52, 30}, OfflineSwitch.gridSize("52,30,8,16"));
        assertArrayEquals(new int[] {80, 24}, OfflineSwitch.gridSize("x,30"));
        assertArrayEquals(new int[] {80, 24}, OfflineSwitch.gridSize(null));
    }
}
