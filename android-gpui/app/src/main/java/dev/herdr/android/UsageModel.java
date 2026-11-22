package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the Usage view shows (factory-setup.32): the aswap accounts of the
 * machine the phone is connected to, from the core's {@code usage.look} reply.
 * The core already reduced aswap's JSON to labels, providers, states and
 * numbers (rust phone_ops.rs), so nothing here can carry a token or email.
 * Pure: times come in as arguments, and the view only draws what this says.
 */
final class UsageModel {
    private UsageModel() { }

    /** The aswap {@code list --json} schema this reads. */
    static final int SCHEMA = 1;
    /** A reading older than this is said to be old on its row. */
    static final long STALE_SECONDS = 15 * 60;
    /** used% at which a number turns warn, then danger. */
    static final double WARN = 70, DANGER = 90;

    /** What the whole sheet says. */
    enum Kind {
        /** Accounts to list (maybe none: "No aswap profiles"). */
        ACCOUNTS,
        /** The machine has no aswap. */
        MISSING,
        /** aswap failed or answered something unexpected. */
        FAILED,
        /** aswap's JSON is a schema this app does not read. */
        FORMAT
    }

    /** How loud a used% is. */
    enum Level { NORMAL, WARN, DANGER }

    static final class Window {
        final String name;
        /** used_percent, or null when aswap had none. */
        final Double used;
        /** Unix seconds, or 0 when unknown. */
        final long resetsAt;
        final long minutes;

        Window(String name, Double used, long resetsAt, long minutes) {
            this.name = name;
            this.used = used;
            this.resetsAt = resetsAt;
            this.minutes = minutes;
        }
    }

    static final class Account {
        String label = "", provider = "", status = "", reason = "", errorClass = "";
        boolean active, reserved, hasError;
        Double remaining;
        long fetchedAt, lastAttempt, nextPoll, httpStatus, retryAfter;
        /** age_seconds (aswap 0.2.1); else -1 and the age comes from fetched_at. */
        long ageSeconds = -1;
        final List<Window> windows = new ArrayList<>();
        final List<String> duplicates = new ArrayList<>();

        /** The tightest window's used%: the number the row leads with. */
        Double tightest() {
            Double most = null;
            for (Window w : windows) if (w.used != null && (most == null || w.used > most)) most = w.used;
            return most;
        }

        /** How old this reading is, in seconds, or -1 when never fetched. */
        long age(long nowSeconds) {
            if (ageSeconds >= 0) return ageSeconds;
            return fetchedAt <= 0 ? -1 : Math.max(0, nowSeconds - fetchedAt);
        }

        boolean stale(long nowSeconds) {
            long age = age(nowSeconds);
            return age < 0 || age > STALE_SECONDS;
        }
    }

    static final class Look {
        final Kind kind;
        final List<Account> accounts = new ArrayList<>();
        /** For {@link Kind#FAILED}: what went wrong; for {@link Kind#FORMAT}: the schema seen. */
        String detail = "";
        /** The dim footer: auto-switch and sync, or empty. */
        String footer = "";

        Look(Kind kind) {
            this.kind = kind;
        }
    }

    /** Reads the core's reply to {@code {"op":"usage.look"}}. Never throws. */
    static Look parse(String reply) {
        JSONObject root;
        try {
            root = new JSONObject(reply == null ? "" : reply);
        } catch (JSONException e) {
            return failed("the machine answered something unexpected");
        }
        if (!root.optBoolean("queued", false)) {
            String error = root.optString("error", "");
            return failed(error.isEmpty() ? "the machine refused the look" : error);
        }
        if (root.optBoolean("missing", false)) return new Look(Kind.MISSING);
        JSONObject usage = root.optJSONObject("usage");
        if (usage == null) {
            String error = root.optString("error", "");
            return failed(error.isEmpty() ? "aswap answered something unexpected" : error);
        }
        int schema = usage.optInt("schema_version", 0);
        if (schema != SCHEMA) {
            Look look = new Look(Kind.FORMAT);
            look.detail = "v" + schema;
            return look;
        }
        Look look = new Look(Kind.ACCOUNTS);
        JSONArray accounts = usage.optJSONArray("accounts");
        for (int i = 0; accounts != null && i < accounts.length(); i++) {
            JSONObject a = accounts.optJSONObject(i);
            if (a != null) look.accounts.add(account(a));
        }
        Collections.sort(look.accounts, ORDER);
        look.footer = footer(usage.optJSONObject("auto"), usage.optJSONObject("sync"));
        return look;
    }

    private static Look failed(String why) {
        Look look = new Look(Kind.FAILED);
        look.detail = why;
        return look;
    }

    /** Active accounts first, then by provider, then label. */
    static final Comparator<Account> ORDER = Comparator
            .comparing((Account a) -> !a.active)
            .thenComparing(a -> a.provider)
            .thenComparing(a -> a.label);

    private static Account account(JSONObject a) {
        Account out = new Account();
        out.label = a.optString("label", "");
        out.provider = a.optString("provider", "");
        out.active = a.optBoolean("active", false);
        out.reserved = a.optBoolean("reserved", false);
        out.status = a.optString("usage_status", "");
        out.reason = a.optString("usage_reason", "");
        out.remaining = number(a, "remaining_percent");
        out.fetchedAt = seconds(a, "fetched_at");
        out.lastAttempt = seconds(a, "last_attempt");
        out.nextPoll = seconds(a, "next_poll_at");
        if (a.has("age_seconds") && !a.isNull("age_seconds")) out.ageSeconds = seconds(a, "age_seconds");
        JSONObject error = a.optJSONObject("usage_error");
        if (error != null) {
            out.hasError = true;
            out.errorClass = error.optString("class", "");
            out.httpStatus = seconds(error, "http_status");
            out.retryAfter = seconds(error, "retry_after_seconds");
        }
        JSONArray windows = a.optJSONArray("windows");
        for (int i = 0; windows != null && i < windows.length(); i++) {
            JSONObject w = windows.optJSONObject(i);
            if (w == null) continue;
            out.windows.add(new Window(w.optString("name", ""), number(w, "used_percent"),
                    seconds(w, "resets_at"), seconds(w, "duration_minutes")));
        }
        JSONArray duplicates = a.optJSONArray("duplicates");
        for (int i = 0; duplicates != null && i < duplicates.length(); i++) {
            String label = duplicates.optString(i, "");
            if (!label.isEmpty()) out.duplicates.add(label);
        }
        return out;
    }

    private static Double number(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        double value = o.optDouble(key, Double.NaN);
        return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
    }

    private static long seconds(JSONObject o, String key) {
        Double value = number(o, key);
        return value == null ? 0 : value.longValue();
    }

    /** A window's short name: 5h, 7d, 7d opus, wk, 90m, quota; an unknown name as it is. */
    static String windowName(Window w) {
        switch (w.name) {
            case "five_hour": case "session": return "5h";
            case "seven_day": return "7d";
            case "seven_day_opus": return "7d opus";
            case "seven_day_sonnet": return "7d sonnet";
            case "weekly": return "wk";
            case "quota": return "quota";
            default:
                // codex's window_<n>m names its own length.
                if (w.name.matches("window_[0-9]{1,6}m")) return w.name.substring("window_".length());
                return w.name;
        }
    }

    static Level level(Double used) {
        if (used == null) return Level.NORMAL;
        return used >= DANGER ? Level.DANGER : used >= WARN ? Level.WARN : Level.NORMAL;
    }

    /** "84%", rounded down so 89.6 never reads as the 90% threshold. */
    static String percent(Double used) {
        return used == null ? "" : (long) Math.floor(used) + "%";
    }

    /** How full a window's bar is, 0 to 1: over 100% fills it, unknown is empty. */
    static float fill(Double used) {
        return used == null ? 0f : (float) Math.max(0, Math.min(1, used / 100));
    }

    /** "3d 2h", "1h 05m", "4m", "<1m". */
    static String span(long seconds) {
        if (seconds < 60) return "<1m";
        long minutes = seconds / 60, hours = minutes / 60, days = hours / 24;
        if (days > 0) return hours % 24 == 0 ? days + "d" : days + "d " + hours % 24 + "h";
        if (hours > 0) return String.format(Locale.ROOT, "%dh %02dm", hours, minutes % 60);
        return minutes + "m";
    }

    /** "resets in 1h 05m · 11:47", the day too when it isn't today; "" when unknown. */
    static String resets(Window w, long nowSeconds, ZoneId zone) {
        if (w.resetsAt <= 0) return "";
        if (w.resetsAt <= nowSeconds) return "reset due";
        ZonedDateTime at = Instant.ofEpochSecond(w.resetsAt).atZone(zone);
        boolean today = at.toLocalDate().equals(Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate());
        String clock = at.format(DateTimeFormatter.ofPattern(today ? "HH:mm" : "EEE HH:mm", Locale.US));
        return "resets in " + span(w.resetsAt - nowSeconds) + " · " + clock;
    }

    /** "HH:mm" in the phone's zone, for "updated" and "showing". */
    static String clock(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US));
    }

    /** The words for an aswap error class (t2kz's inventory); unknown ones as can't read usage. */
    static String errorWords(String errorClass) {
        switch (errorClass) {
            case "provider-limit": return "limit reached";
            case "expired-or-invalid-token": case "refresh-invalid-grant": return "login expired";
            case "owner-profile-missing": return "removed on the owner";
            case "rate-limited": return "rate limited";
            default: return "can't read usage";
        }
    }

    /**
     * The row's warning line, or "": "backoff (limit reached), retry in 4m",
     * or an unknown status as its own word (never hidden).
     */
    static String problem(Account a, long nowSeconds) {
        if (a.hasError || "backoff".equals(a.status)) {
            String why = a.hasError ? errorWords(a.errorClass) : a.reason.isEmpty() ? "" : a.reason;
            String retry = a.nextPoll > nowSeconds ? ", retry in " + span(a.nextPoll - nowSeconds)
                    : a.retryAfter > 0 ? ", retry in " + span(a.retryAfter) : "";
            return (a.status.isEmpty() ? "error" : a.status) + (why.isEmpty() ? "" : " (" + why + ")") + retry;
        }
        if (a.status.isEmpty() || "ok".equals(a.status)) return "";
        if ("unknown".equals(a.status)) return a.reason.isEmpty() ? "no fresh reading" : "no fresh reading (" + a.reason + ")";
        return a.status + (a.reason.isEmpty() ? "" : " (" + a.reason + ")");
    }

    /** "data 23m old" past {@link #STALE_SECONDS}, "never read" when never fetched, else "". */
    static String staleWords(Account a, long nowSeconds) {
        long age = a.age(nowSeconds);
        if (age < 0) return "never read";
        return age > STALE_SECONDS ? "data " + span(age) + " old" : "";
    }

    /** "aswap hasn't refreshed for 1h" when every account is stale, else "". */
    static String allStale(List<Account> accounts, long nowSeconds) {
        if (accounts.isEmpty()) return "";
        long freshest = Long.MAX_VALUE;
        for (Account a : accounts) {
            if (!a.stale(nowSeconds)) return "";
            long age = a.age(nowSeconds);
            if (age >= 0) freshest = Math.min(freshest, age);
        }
        return freshest == Long.MAX_VALUE ? "aswap has no readings yet" : "aswap hasn't refreshed for " + span(freshest);
    }

    /** "no quota data": nothing to draw a bar from. */
    static boolean noData(Account a) {
        return a.remaining == null && a.windows.isEmpty();
    }

    /** "auto-switch: claude at 90% · codex off · sync: 2 failures". */
    static String footer(JSONObject auto, JSONObject sync) {
        List<String> parts = new ArrayList<>();
        if (auto != null && auto.length() > 0) {
            List<String> names = new ArrayList<>();
            JSONArray keys = auto.names();
            for (int i = 0; keys != null && i < keys.length(); i++) names.add(keys.optString(i));
            Collections.sort(names);
            StringBuilder words = new StringBuilder("auto-switch: ");
            for (int i = 0; i < names.size(); i++) {
                JSONObject setting = auto.optJSONObject(names.get(i));
                if (setting == null) continue;
                if (i > 0) words.append(" · ");
                Double threshold = number(setting, "threshold");
                words.append(names.get(i)).append(setting.optBoolean("enabled", false)
                        ? threshold == null ? " on" : " at " + percent(threshold) : " off");
            }
            parts.add(words.toString());
        }
        if (sync != null) {
            Double failures = number(sync, "failures");
            if (failures != null && failures > 0) {
                long n = failures.longValue();
                parts.add("sync: " + n + (n == 1 ? " failure" : " failures"));
            }
        }
        return String.join(" · ", parts);
    }

    /**
     * The sheet's own state across looks, pure so it can be tested: what it
     * shows, from when, and whether that is this open's answer or an older one.
     * A failed look keeps the last good accounts, greyed ({@link #old}).
     */
    static final class Sheet {
        final String machine;
        Look shown;
        long shownAt;
        /** Why the latest look failed while older accounts show; else null. */
        String failure;
        /** An answer came during this open (the cached one is not fresh). */
        boolean fresh;

        Sheet(String machine) {
            this.machine = machine;
        }

        /** Data read this recently is this open's own (the rail's look moments ago), not greyed. */
        static final long RECENT_MS = 15_000;

        static boolean recent(long atMs, long nowMs) {
            return nowMs - atMs >= 0 && nowMs - atMs <= RECENT_MS;
        }

        /** Opened with the last good answer from an earlier open, if any. */
        void opened(Look cached, long cachedAt) {
            shown = cached;
            shownAt = cachedAt;
            failure = null;
            fresh = false;
        }

        /** The machine was busy (its core refused the look for now): what shows stays, the note says so. */
        void busy() {
            fresh = true;
            failure = "busy, retrying";
        }

        /** A look's answer, or {@code unreachable} when the machine could not be asked. */
        void answered(Look answer, boolean unreachable, long now) {
            fresh = true;
            if (answer != null && answer.kind == Kind.ACCOUNTS) {
                shown = answer;
                shownAt = now;
                failure = null;
            } else if (listed()) {
                failure = unreachable || answer == null ? "Can't reach " + machine : words(answer);
            } else {
                shown = answer;
                shownAt = now;
                failure = unreachable || answer == null ? "Can't reach " + machine : null;
            }
        }

        /** Accounts shown that are not this open's latest answer: greyed. */
        boolean old() {
            return listed() && (failure != null || !fresh);
        }

        /** Accounts are on screen (an empty list is a state, not something to keep greyed). */
        private boolean listed() {
            return shown != null && shown.kind == Kind.ACCOUNTS && !shown.accounts.isEmpty();
        }

        /** Nothing to show yet and nothing wrong: the sheet draws its skeleton (Wolf: no words with dots). */
        boolean waiting() {
            return shown == null && failure == null;
        }

        /**
         * The header's right-hand words, the age of what shows: "updated just
         * now", "updated 4m ago"; nothing before the first look (the skeleton says it).
         */
        String updated(long nowMs) {
            if (shown == null) return "";
            long age = Math.max(0, nowMs - shownAt) / 1000;
            return "updated " + (age < 60 ? "just now" : span(age) + " ago");
        }

        /** The note under the title, or "": why it is old, a whole-sheet state, or that aswap is stale. */
        String note(long nowSeconds, ZoneId zone) {
            if ("busy, retrying".equals(failure)) {
                return machine + " is busy, retrying" + (listed() ? " · showing " + clock(shownAt, zone) : "");
            }
            if (failure != null && listed()) {
                return failure + " · showing " + clock(shownAt, zone);
            }
            if (shown == null) return failure == null ? "" : failure;
            if (shown.kind != Kind.ACCOUNTS || shown.accounts.isEmpty()) return failure != null ? failure : words(shown);
            return allStale(shown.accounts, nowSeconds);
        }

        /** What a whole-sheet state says. */
        String words(Look look) {
            switch (look.kind) {
                case MISSING: return "aswap isn't installed on " + machine;
                case FORMAT: return "aswap's format changed (" + look.detail + ")";
                case FAILED: return "Couldn't read aswap on " + machine + ": " + look.detail;
                default: return look.accounts.isEmpty() ? "No aswap profiles on " + machine : "";
            }
        }
    }
}
