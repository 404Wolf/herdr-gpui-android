package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Wolf's ask: offline is ONE small indicator in a top corner (a dot and
 * "offline" or the cached copy's time), never a grayed screen, a banner or a
 * status line. What the indicator says, and that it stays small and in the
 * corner.
 */
public final class LinkBadgeTest {
    @Test public void wolfAskOfflineCornerIndicator_isHiddenWhileLive() {
        assertFalse(LinkBadge.look(false, false, null, false, null, 0).shown);
    }

    @Test public void wolfAskOfflineCornerIndicator_saysOfflineWithNoNetwork() {
        LinkBadge.Look look = LinkBadge.look(true, true, null, false, null, 0);
        assertTrue(look.shown);
        assertEquals("offline", look.text);
        assertEquals(LinkBadge.Tone.WARN, look.tone);
        assertTrue(look.description, look.description.startsWith("Offline."));
        assertTrue(look.description, look.description.endsWith("Tap to reconnect or open the hosts."));
    }

    @Test public void wolfAskOfflineCornerIndicator_isQuietWhileANetworkIsBackAndItConnects() {
        LinkBadge.Look look = LinkBadge.look(true, false, null, false, null, 0);
        assertEquals("connecting", look.text);
        assertEquals(LinkBadge.Tone.QUIET, look.tone);
    }

    @Test public void wolfAskOfflineCornerIndicator_givesACachedPanesTime() {
        assertEquals("offline · 14:32", LinkBadge.look(true, true, null, true, "14:32", 0).text);
        assertEquals("connecting · 14:32", LinkBadge.look(true, false, null, true, "14:32", 0).text);
        assertEquals("offline · not cached", LinkBadge.look(true, true, null, true, null, 0).text);
        // A cached copy on screen is never live, even before the monitor reconnects.
        assertTrue(LinkBadge.look(false, false, null, true, "14:32", 0).shown);
        assertTrue(LinkBadge.look(true, true, null, true, "14:32", 0).description
                .contains("Showing the last saved screen, from 14:32; it may be outdated."));
    }

    @Test public void wolfAskOfflineCornerIndicator_countsWhatWaitsForTheLink() {
        assertEquals("offline · 14:32 · 2 queued", LinkBadge.look(true, true, null, true, "14:32", 2).text);
        // Too long with the count: the time stays, the count is in the description (and the queue rows).
        assertEquals("connecting · 14:32", LinkBadge.look(true, false, null, true, "14:32", 2).text);
        assertEquals("offline · 1 queued", LinkBadge.look(true, true, null, false, null, 1).text);
        assertTrue(LinkBadge.look(true, true, null, false, null, 1).description.contains("1 message waits"));
        assertTrue(LinkBadge.look(true, true, null, false, null, 3).description.contains("3 messages wait"));
    }

    @Test public void wolfAskOfflineCornerIndicator_staysSmallWhateverItSays() {
        String[] whens = {null, "14:32", "2:32 PM", "Sun 14:32", "Nov 12 11:59 PM"};
        for (boolean noNetwork : new boolean[] {true, false}) {
            for (String when : whens) {
                for (int waiting : new int[] {0, 1, 12, 1234}) {
                    String text = LinkBadge.look(true, noNetwork, null, true, when, waiting).text;
                    assertTrue(text + " is " + text.length() + " chars", text.length() <= LinkBadge.MAX_CHARS);
                }
            }
        }
        // A long date drops the count before the time, then the time.
        assertEquals("offline · Nov 12 11:59 PM", LinkBadge.look(true, true, null, true, "Nov 12 11:59 PM", 9).text);
        assertEquals("connecting", LinkBadge.look(true, false, null, true, "Nov 12 11:59 PM", 0).text);
        // The small type, a bounded width, in the top row's end (the top right corner).
        assertTrue(LinkBadge.TEXT_SP <= 12);
        assertTrue("under half a 360 dp phone", LinkBadge.MAX_WIDTH_DP < 180);
        assertEquals(android.view.Gravity.TOP, LinkBadge.GRAVITY & android.view.Gravity.VERTICAL_GRAVITY_MASK);
        assertEquals(android.view.Gravity.END, LinkBadge.GRAVITY & android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
    }

    @Test public void wolfAskOfflineCornerIndicator_carriesTheOldBannersFailureToItsTap() {
        String why = BannerCopy.human("Reconnect failed. Check the host and network.");
        LinkBadge.Look look = LinkBadge.look(false, false, why, false, null, 0);
        assertTrue(look.shown);
        assertEquals("offline", look.text);
        assertEquals(LinkBadge.Tone.WARN, look.tone);
        assertEquals(why, look.title);
        // With no failure, the in-place actions say what the indicator means.
        assertEquals("Offline. Showing the last saved screen, from 14:32; it may be outdated.", LinkBadge.look(true, true, null, true, "14:32", 0).title);
        assertEquals(LinkBadge.RECONNECT, look.primary);
    }

    @Test public void wolfAskOfflineCornerIndicator_aRunningShellShowsNothing() {
        assertFalse(LinkBadge.shell(null, null).shown);
    }

    @Test public void wolfAskOfflineCornerIndicator_aShellsLostConnectionIsTheSameSmallOffline() {
        LinkBadge.Look look = LinkBadge.shell(LinkBadge.ShellEnd.LOST, "Connection lost · the host timed out");
        assertTrue(look.shown);
        assertEquals("offline", look.text);
        assertEquals(LinkBadge.Tone.WARN, look.tone);
        assertEquals("Connection lost · the host timed out", look.title);
        // A new connection opens a new shell; nothing typed before is sent again.
        assertEquals(LinkBadge.RECONNECT, look.primary);
        assertTrue(look.description, look.description.contains("Nothing typed is sent again."));
        assertEquals("The shell's connection was lost.", LinkBadge.shell(LinkBadge.ShellEnd.LOST, null).title);
    }

    @Test public void wolfAskOfflineCornerIndicator_aShellThatExitedIsNotALostLink() {
        LinkBadge.Look look = LinkBadge.shell(LinkBadge.ShellEnd.EXITED, "The shell exited");
        assertTrue(look.shown);
        assertEquals("exited", look.text);
        assertEquals(LinkBadge.Tone.QUIET, look.tone);
        assertEquals(LinkBadge.NEW_SHELL, look.primary);
        assertFalse(look.description, look.description.toLowerCase(java.util.Locale.ROOT).contains("offline"));
        for (LinkBadge.ShellEnd end : LinkBadge.ShellEnd.values()) {
            assertTrue(LinkBadge.shell(end, "x").text.length() <= LinkBadge.MAX_CHARS);
        }
    }

    @Test public void wolfAskOfflineIndicator_aShellsFailureEndsAsASentenceBeforeTheNext() {
        LinkBadge.Look look = LinkBadge.shell(LinkBadge.ShellEnd.LOST,
                "Could not open a shell on alpha: the SSH server rejected the command");
        assertTrue(look.description, look.description.contains("rejected the command. Nothing typed is sent again."));
        assertTrue(LinkBadge.shell(LinkBadge.ShellEnd.LOST, "Lost.").description.contains("Lost. Nothing typed"));
        assertTrue(LinkBadge.shell(LinkBadge.ShellEnd.LOST, "  ").description.contains(
                "The shell's connection was lost. Nothing typed"));
        assertEquals("Why?", LinkBadge.sentence("Why?"));
        assertEquals("", LinkBadge.sentence(""));
    }

    /** Stage 1: the count is the whole queue; live, it shows alone and a tap opens the queue. */
    @Test public void heldMessagesShowTheirCountEvenWhileLiveAndOpenTheQueue() {
        assertFalse(LinkBadge.look(false, false, null, false, null, 0).shown);
        LinkBadge.Look live = LinkBadge.look(false, false, null, false, null, 3);
        assertTrue(live.shown);
        assertTrue(live.queuedOnly);
        assertEquals("3 queued", live.text);
        assertEquals(LinkBadge.Tone.QUIET, live.tone);
        assertTrue(live.description.contains("open the queue"));
        assertFalse(LinkBadge.look(true, true, null, false, null, 3).queuedOnly);
    }

    @Test public void offlineRedesign_spentAttemptsSayTapToReconnect() {
        LinkBadge.Look look = LinkBadge.look(true, false, true, null, true, "14:32", 2);
        assertEquals(LinkBadge.STOPPED, look.text);
        assertTrue(look.text.length() <= LinkBadge.MAX_CHARS);
        assertEquals(LinkBadge.Tone.WARN, look.tone);
        assertEquals(LinkBadge.RECONNECT, look.primary);
        assertTrue(look.description.contains("14:32"));
        // Not spent: as before.
        assertEquals("offline · 14:32 · 2 queued", LinkBadge.look(true, true, false, null, true, "14:32", 2).text);
        // Spent but back up (no reconnect wanted): hidden as before.
        assertFalse(LinkBadge.look(false, false, true, null, false, null, 0).shown);
    }
}
