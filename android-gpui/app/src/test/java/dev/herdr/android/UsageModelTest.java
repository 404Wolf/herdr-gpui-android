package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import org.junit.Test;

/** The Usage view's words and order from the core's reduced usage.look reply (synthetic accounts). */
public final class UsageModelTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    /** 2026-11-21 10:42:00 UTC. */
    private static final long NOW = 1795257720L;

    private static String reply(String accounts) {
        return "{\"queued\":true,\"usage\":{\"schema_version\":1,\"accounts\":[" + accounts + "],"
                + "\"auto\":{\"codex\":{\"enabled\":false,\"threshold\":90},\"claude\":{\"enabled\":true,\"threshold\":90}},"
                + "\"sync\":{\"failures\":0,\"last_sync\":1}}}";
    }

    private static final String WORK = "{\"label\":\"work-max\",\"provider\":\"claude\",\"active\":true,"
            + "\"usage_status\":\"ok\",\"remaining_percent\":16,\"fetched_at\":" + (NOW - 60) + ","
            + "\"windows\":[{\"name\":\"five_hour\",\"used_percent\":84.4,\"resets_at\":" + (NOW + 3900) + ",\"duration_minutes\":300},"
            + "{\"name\":\"seven_day\",\"used_percent\":41,\"resets_at\":" + (NOW + 3 * 86400 + 7200) + ",\"duration_minutes\":10080}],"
            + "\"duplicates\":[\"work-old\"]}";
    private static final String SPARE = "{\"label\":\"spare-1\",\"provider\":\"claude\",\"active\":false,"
            + "\"usage_status\":\"backoff\",\"usage_error\":{\"class\":\"provider-limit\",\"http_status\":429},"
            + "\"next_poll_at\":" + (NOW + 240) + ",\"fetched_at\":" + (NOW - 120) + ","
            + "\"windows\":[{\"name\":\"seven_day\",\"used_percent\":100,\"resets_at\":" + (NOW + 18 * 3600) + "}],\"duplicates\":[]}";
    private static final String CODEX = "{\"label\":\"main-pro\",\"provider\":\"codex\",\"active\":true,"
            + "\"usage_status\":\"unknown\",\"fetched_at\":" + (NOW - 23 * 60) + ","
            + "\"windows\":[{\"name\":\"weekly\",\"used_percent\":0}],\"duplicates\":[]}";
    private static final String EMPTY = "{\"label\":\"new-one\",\"provider\":\"pi\",\"active\":false,"
            + "\"usage_status\":\"weird-new-state\",\"windows\":[],\"duplicates\":[]}";

    @Test public void activeAccountsComeFirstThenProviderThenLabel() {
        UsageModel.Look look = UsageModel.parse(reply(EMPTY + "," + SPARE + "," + CODEX + "," + WORK));
        assertEquals(UsageModel.Kind.ACCOUNTS, look.kind);
        assertEquals(4, look.accounts.size());
        assertEquals("work-max", look.accounts.get(0).label);
        assertEquals("main-pro", look.accounts.get(1).label);
        assertEquals("spare-1", look.accounts.get(2).label);
        assertEquals("new-one", look.accounts.get(3).label);
        assertEquals("auto-switch: claude at 90% · codex off", look.footer);
    }

    @Test public void aRowLeadsWithItsTightestWindowAndSaysWhenEachResets() {
        UsageModel.Account work = UsageModel.parse(reply(WORK)).accounts.get(0);
        assertEquals(84.4, work.tightest(), 0.001);
        assertEquals("84%", UsageModel.percent(work.tightest()));
        assertEquals(UsageModel.Level.WARN, UsageModel.level(work.tightest()));
        UsageModel.Window five = work.windows.get(0);
        assertEquals("5h", UsageModel.windowName(five));
        assertEquals(0.844f, UsageModel.fill(five.used), 1e-4f);
        assertEquals("resets in 1h 05m · 11:47", UsageModel.resets(five, NOW, UTC));
        assertEquals("resets in 3d 2h · Tue 12:42", UsageModel.resets(work.windows.get(1), NOW, UTC));
        assertEquals("", UsageModel.problem(work, NOW));
        assertEquals("", UsageModel.staleWords(work, NOW));
        assertEquals("work-old", work.duplicates.get(0));
        assertFalse(UsageModel.noData(work));
    }

    @Test public void backoffSaysWhyAndWhenItRetries() {
        UsageModel.Account spare = UsageModel.parse(reply(SPARE)).accounts.get(0);
        assertEquals(UsageModel.Level.DANGER, UsageModel.level(spare.tightest()));
        assertEquals("backoff (limit reached), retry in 4m", UsageModel.problem(spare, NOW));
        assertEquals(429, spare.httpStatus);
        assertEquals("can't read usage", UsageModel.errorWords("some-class-aswap-adds-later"));
        assertEquals("login expired", UsageModel.errorWords("refresh-invalid-grant"));
    }

    @Test public void oldReadingsAndUnknownStatesAreSaidNotHidden() {
        UsageModel.Look look = UsageModel.parse(reply(CODEX + "," + EMPTY));
        UsageModel.Account codex = look.accounts.get(0);
        assertEquals("wk", UsageModel.windowName(codex.windows.get(0)));
        assertEquals("data 23m old", UsageModel.staleWords(codex, NOW));
        assertEquals("no fresh reading", UsageModel.problem(codex, NOW));
        UsageModel.Account fresh = look.accounts.get(1);
        assertEquals("weird-new-state", UsageModel.problem(fresh, NOW));
        assertEquals("never read", UsageModel.staleWords(fresh, NOW));
        assertTrue(UsageModel.noData(fresh));
        assertNull(fresh.tightest());
        assertEquals("aswap hasn't refreshed for 23m", UsageModel.allStale(look.accounts, NOW));
        assertEquals("", UsageModel.allStale(UsageModel.parse(reply(WORK + "," + CODEX)).accounts, NOW));
    }

    @Test public void asapsOwnAgeWinsOverFetchedAt() {
        String aged = "{\"label\":\"a\",\"provider\":\"claude\",\"fetched_at\":" + NOW + ",\"age_seconds\":3600,\"windows\":[]}";
        assertEquals("data 1h 00m old", UsageModel.staleWords(UsageModel.parse(reply(aged)).accounts.get(0), NOW));
    }

    @Test public void wholeSheetStates() {
        assertEquals(UsageModel.Kind.MISSING, UsageModel.parse("{\"queued\":true,\"missing\":true}").kind);
        UsageModel.Look failed = UsageModel.parse("{\"queued\":true,\"error\":\"aswap list exited with 1\"}");
        assertEquals(UsageModel.Kind.FAILED, failed.kind);
        assertEquals("aswap list exited with 1", failed.detail);
        UsageModel.Look format = UsageModel.parse("{\"queued\":true,\"usage\":{\"schema_version\":2,\"accounts\":[]}}");
        assertEquals(UsageModel.Kind.FORMAT, format.kind);
        assertEquals("v2", format.detail);
        assertEquals(UsageModel.Kind.FAILED, UsageModel.parse("not json").kind);
        assertEquals(UsageModel.Kind.FAILED, UsageModel.parse(null).kind);
        assertEquals(UsageModel.Kind.FAILED, UsageModel.parse("{\"queued\":false,\"error\":\"stale\"}").kind);
        UsageModel.Look none = UsageModel.parse(reply(""));
        assertEquals(UsageModel.Kind.ACCOUNTS, none.kind);
        assertTrue(none.accounts.isEmpty());
    }

    @Test public void spansAndBars() {
        assertEquals("<1m", UsageModel.span(30));
        assertEquals("4m", UsageModel.span(240));
        assertEquals("2d", UsageModel.span(2 * 86400));
        assertEquals(1f, UsageModel.fill(130.0), 0f);
        assertEquals(0f, UsageModel.fill(null), 0f);
        assertEquals(0f, UsageModel.fill(-5.0), 0f);
        // Rounded down: 89.6 must not read as the 90% danger threshold.
        assertEquals("89%", UsageModel.percent(89.6));
        assertEquals("90m", UsageModel.windowName(new UsageModel.Window("window_90m", 1.0, 0, 90)));
        assertEquals("reset due", UsageModel.resets(new UsageModel.Window("seven_day", 1.0, NOW - 1, 0), NOW, UTC));
    }

    @Test public void aCachedAnswerShowsAsOldUntilThisOpensLookAnswers() {
        UsageModel.Look cached = UsageModel.parse(reply(WORK));
        UsageModel.Sheet sheet = new UsageModel.Sheet("demo");
        assertEquals("asking…", sheet.updated(NOW * 1000));
        sheet.opened(cached, NOW * 1000 - 30 * 60_000);
        assertTrue(sheet.old());
        // What was kept shows at once, with its age, until this open's look answers.
        assertEquals("updated 30m ago · asking…", sheet.updated(NOW * 1000));
        sheet.answered(UsageModel.parse(reply(WORK)), false, NOW * 1000);
        assertFalse(sheet.old());
        assertEquals("updated just now", sheet.updated(NOW * 1000 + 20_000));
        assertEquals("updated 4m ago", sheet.updated(NOW * 1000 + 4 * 60_000));
        assertEquals("", sheet.note(NOW, UTC));
    }

    @Test public void aFailedLookKeepsTheLastAccountsGreyedAndSaysWhy() {
        UsageModel.Sheet sheet = new UsageModel.Sheet("demo");
        sheet.answered(UsageModel.parse(reply(WORK)), false, NOW * 1000);
        sheet.answered(null, true, NOW * 1000 + 60_000);
        assertTrue(sheet.old());
        assertEquals("updated 1m ago", sheet.updated(NOW * 1000 + 60_000));
        assertEquals("Can't reach demo · showing 10:42", sheet.note(NOW, UTC));
        sheet.answered(UsageModel.parse("{\"queued\":true,\"missing\":true}"), false, NOW * 1000 + 120_000);
        assertEquals("aswap isn't installed on demo · showing 10:42", sheet.note(NOW, UTC));
        sheet.answered(UsageModel.parse(reply(WORK)), false, NOW * 1000 + 180_000);
        assertFalse(sheet.old());
    }

    @Test public void aBusyMachineKeepsWhatShowsAndSaysItIsRetrying() {
        UsageModel.Sheet sheet = new UsageModel.Sheet("demo");
        sheet.answered(UsageModel.parse(reply(WORK)), false, NOW * 1000);
        sheet.busy();
        assertEquals("demo is busy, retrying · showing 10:42", sheet.note(NOW, UTC));
        assertTrue(sheet.old());
        sheet.answered(UsageModel.parse(reply(WORK)), false, NOW * 1000 + 30_000);
        assertEquals("", sheet.note(NOW, UTC));
        UsageModel.Sheet empty = new UsageModel.Sheet("demo");
        empty.busy();
        assertEquals("demo is busy, retrying", empty.note(NOW, UTC));
    }

    @Test public void withNothingShownTheSheetSaysTheWholeState() {
        UsageModel.Sheet sheet = new UsageModel.Sheet("demo");
        sheet.answered(null, true, NOW * 1000);
        assertEquals("Can't reach demo", sheet.note(NOW, UTC));
        sheet.answered(UsageModel.parse("{\"queued\":true,\"usage\":{\"schema_version\":2,\"accounts\":[]}}"), false, NOW * 1000);
        assertEquals("aswap's format changed (v2)", sheet.note(NOW, UTC));
        sheet.answered(UsageModel.parse(reply("")), false, NOW * 1000);
        assertEquals("No aswap profiles on demo", sheet.note(NOW, UTC));
        sheet.answered(UsageModel.parse("{\"queued\":true,\"error\":\"aswap list timed out on the machine\"}"), false, NOW * 1000);
        assertEquals("Couldn't read aswap on demo: aswap list timed out on the machine", sheet.note(NOW, UTC));
    }

    @Test public void dataTheRailReadMomentsAgoIsThisOpensOwn() {
        long now = NOW * 1000;
        assertTrue(UsageModel.Sheet.recent(now - 9_000, now));
        assertFalse(UsageModel.Sheet.recent(now - 16_000, now));
        assertFalse(UsageModel.Sheet.recent(now + 5_000, now));
    }

    @Test public void theSheetDrawsTheFeedAndClosesOnAMachineSwitch() throws java.io.IOException {
        String usage = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/UsageView.java")), java.nio.charset.StandardCharsets.UTF_8);
        String feed = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/UsageFeed.java")), java.nio.charset.StandardCharsets.UTF_8);
        // The feed pauses with the app and asks on a thread of its own, never the terminal's action worker.
        assertTrue(feed.contains("PauseWatch.on(activity"));
        assertTrue(feed.contains("catch (HostSwitched gone)"));
        assertTrue(feed.contains("new Thread(task, \"herdr-usage\")"));
        assertTrue(usage.contains("case SWITCHED: dialog.dismiss(); return;"));
        assertTrue(usage.contains("UsageFeed.Answer kept = feed.kept(hostId);"));
        assertTrue(usage.indexOf("feed.kept(hostId)") < usage.indexOf("dialog.show();"));
        assertTrue(usage.contains("feed.watch(UsageFeed.Watch.SHEET, false);"));
        assertTrue(usage.contains("note.setAccessibilityLiveRegion("));
    }

    @Test public void theMenuOffersUsageOnlyWithHerdrAndAsksTheSameMachine() throws java.io.IOException {
        String rail = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), java.nio.charset.StandardCharsets.UTF_8);
        int start = rail.indexOf("private void showUsage(");
        String usage = rail.substring(start, rail.indexOf("private ImageButton iconButton(", start));
        assertTrue(usage.contains("!hostId.equals(now.hostId)"));
        assertTrue(usage.contains("UsageView.show(this, chrome, opened.hostId, opened.hostName, usageFeed);"));
        assertFalse(usage.contains("actionWorker"));
        // The feed follows the machine on screen, whether it is online, and whether the rail shows.
        assertTrue(rail.contains("usageFeed.select(usageHost, current != null && current.ready && !current.isClosed(), () -> usageLook(usageHost));"));
        assertTrue(rail.contains("usageFeed.watch(UsageFeed.Watch.RAIL, next.open && !next.minimized);"));
        assertTrue(rail.contains("if (usageFeed != null) usageFeed.detach(this);"));
        assertTrue(rail.contains("usageFeed.typing(() -> imeShown || (composer != null && composer.drafting()));"));
        assertTrue(rail.contains("if (!shellMode()) menu.getMenu().add(0, 8, MoreMenu.USAGE, \"Usage\").setIcon(R.drawable.ic_bar_usage)"));
    }
}
