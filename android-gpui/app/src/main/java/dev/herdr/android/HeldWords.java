package dev.herdr.android;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * What a held message says on screen, and what it carries when it is typed late. Pure: the
 * queue screen, the rows above the prompt and the live pane's offer all read it here.
 *
 * <p>Nothing here ever says "delivered": the phone cannot know. A written message reads "sent
 * 14:49 · check it arrived".
 */
final class HeldWords {
    private HeldWords() { }

    /** A clock time as the rows show it. */
    static String time(long wallMs, TimeZone zone, boolean twentyFour) {
        SimpleDateFormat format = new SimpleDateFormat(twentyFour ? "HH:mm" : "h:mm a", Locale.US);
        format.setTimeZone(zone);
        return format.format(new Date(wallMs));
    }

    /**
     * The computer as the user named it; its session is added when another message's computer
     * has the same name ({@code names}: every target in the list).
     */
    static String computer(Outbox.Target target, List<Outbox.Target> others) {
        String name = target.profileName.isEmpty() ? "a saved computer" : target.profileName;
        if (target.session.isEmpty()) return name;
        for (Outbox.Target other : others) {
            if (other.profileName.equals(target.profileName) && !other.session.equals(target.session)) {
                return name + " (" + target.session + ")";
            }
        }
        return name;
    }

    /** The pane: its name and address, or the address alone. */
    static String pane(Outbox.Target target) {
        return target.label.isEmpty() || target.label.equals(target.address)
                ? target.address : target.label + " · " + target.address;
    }

    /** {@code home server · PM herdr · t5}. */
    static String target(Outbox.Target target, List<Outbox.Target> others) {
        return computer(target, others) + " · " + pane(target);
    }

    /** How long ago, in a few words; "age unknown" when the phone cannot know. */
    static String age(long ageMs) {
        if (ageMs < 0) return "age unknown";
        long minutes = ageMs / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 48) return hours + " h " + (minutes % 60) + " min ago";
        return (hours / 24) + " days ago";
    }

    /**
     * {@code typed 14:02 · 47 min ago}. The wall time alone, with "age unknown", when the phone
     * rebooted since or its clock now reads earlier than the typing.
     */
    static String typed(Outbox.Message m, long wallNow, long elapsedNow, int bootNow, TimeZone zone,
            boolean twentyFour) {
        return "typed " + time(m.composed.wallMs, zone, twentyFour) + " · " + age(ageOf(m, wallNow, elapsedNow, bootNow));
    }

    /** The message's age on the phone's own clock; -1 across a reboot or a clock set back. */
    static long ageOf(Outbox.Message m, long wallNow, long elapsedNow, int bootNow) {
        long age = m.composed.ageMs(elapsedNow, bootNow);
        return wallNow < m.composed.wallMs ? -1 : age;
    }

    /** The message's state in words, for the queue screen and the rows. */
    static String state(Outbox.Message m, boolean paneLive, TimeZone zone, boolean twentyFour) {
        switch (m.state) {
            case SENDING:
                return "sending…";
            case SENT:
                return "sent " + time(m.sentAt, zone, twentyFour) + " · check it arrived";
            case NEEDS_YOU:
                if (m.reason == Outbox.Reason.GONE) return "needs your decision: " + m.pane + " is gone";
                return "needs your decision: may have been sent, or may be sitting unsent in the input box";
            default:
                String head = m.unverified() ? "destination not verified · " : "";
                if (m.awaitsDictation) return head + "waits for its dictation";
                if (!m.known.isEmpty()) return head + m.known + " · tap Send in its pane";
                if (paneLive) return head + "tap Send to type it here";
                return head + "waits for " + (m.target.profileName.isEmpty() ? "its computer" : m.target.profileName);
        }
    }

    /**
     * What the live pane's offer says above the message: the target as it was when typed, which
     * the tap confirms. A migrated message names only its old profile and pane.
     */
    static String offer(Outbox.Message m, List<Outbox.Target> others, TimeZone zone, boolean twentyFour,
            long wallNow, long elapsedNow, int bootNow) {
        String at = time(m.composed.wallMs, zone, twentyFour);
        String age = age(ageOf(m, wallNow, elapsedNow, bootNow));
        if (m.unverified()) {
            return "destination not verified · typed " + at + " for " + m.pane + " · " + age;
        }
        return "typed " + at + " for " + target(m.target, others) + " · " + age;
    }

    /** "1 older message waits for you" / "N older messages wait for you". */
    static String olderWaiting(int count) {
        return count == 1 ? "1 older message waits for you" : count + " older messages wait for you";
    }

    /**
     * What is typed for a held message: the text as it was, with a short lead on the same line
     * when the program reports status (an agent), so it cannot split into two inputs. It says
     * only when the message was composed, which is true whenever it is typed. A message sent at
     * once from the live pane, and anything for a program without a status report, is unchanged.
     */
    static String wire(String text, long composedWallMs, TimeZone zone, boolean reportsStatus, boolean immediate) {
        if (immediate || !reportsStatus) return text;
        return lead(composedWallMs, zone) + " " + text;
    }

    /** {@code [from the phone, composed 14:02 EST (2026-11-20T19:02Z), delivered later]}. */
    static String lead(long composedWallMs, TimeZone zone) {
        Date at = new Date(composedWallMs);
        SimpleDateFormat local = new SimpleDateFormat("HH:mm", Locale.US);
        local.setTimeZone(zone);
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String name = zone.getDisplayName(zone.inDaylightTime(at), TimeZone.SHORT, Locale.US);
        return "[from the phone, composed " + local.format(at) + " " + name + " (" + utc.format(at)
                + "), delivered later]";
    }

    /**
     * The queue screen's order: grouped by computer, then by pane, each group in the order its
     * first message was typed, and each pane's messages in lane order.
     */
    static List<List<Outbox.Message>> grouped(List<Outbox.Message> messages) {
        java.util.Map<String, java.util.Map<String, List<Outbox.Message>>> computers = new java.util.LinkedHashMap<>();
        for (Outbox.Message m : messages) {
            String computer = m.target.machine == null ? "profile " + m.host : "machine " + m.target.machine;
            computers.computeIfAbsent(computer, k -> new java.util.LinkedHashMap<>())
                    .computeIfAbsent(m.pane + " " + m.target.createdMs, k -> new java.util.ArrayList<>()).add(m);
        }
        List<List<Outbox.Message>> out = new java.util.ArrayList<>();
        for (java.util.Map<String, List<Outbox.Message>> panes : computers.values()) out.addAll(panes.values());
        return out;
    }
}
