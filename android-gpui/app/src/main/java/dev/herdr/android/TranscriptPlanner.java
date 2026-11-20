package dev.herdr.android;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which agent's transcript to read next, and whether a reply may be kept (stage 2 of the offline
 * design). Reads happen only on a direct link whose core offers the bounded read and
 * incarnations, for agents whose incarnation the list proves, one at a time:
 *
 * <ul>
 *   <li>the pane on screen while it prints, at most once a minute;
 *   <li>the other agents only on an unmetered network, {@link #SPACING_MS} apart, each at most
 *       once in {@link #OTHERS_EVERY_MS}.
 * </ul>
 *
 * Pure: the caller passes the clock ({@code elapsedRealtime}) and does the reading.
 */
final class TranscriptPlanner {
    static final long SCREEN_EVERY_MS = 60_000;
    static final long OTHERS_EVERY_MS = 5 * 60_000;
    static final long SPACING_MS = 2_000;

    /** One pane as the current direct list shows it. */
    static final class Pane {
        final String address;
        final Long createdMs;
        final String incarnation;
        /** It runs an agent (its kind label says so): only agents' transcripts are kept. */
        final boolean agent;

        Pane(String address, Long createdMs, String incarnation, boolean agent) {
            this.address = address == null ? "" : address;
            this.createdMs = createdMs;
            this.incarnation = incarnation;
            this.agent = agent;
        }

        boolean readable() {
            return agent && createdMs != null && createdMs > 0 && TranscriptKey.plainAddress(address)
                    && TranscriptKey.plainIncarnation(incarnation);
        }
    }

    private final Map<String, Long> lastRead = new HashMap<>();
    private long lastAnyRead = Long.MIN_VALUE / 2;
    /** The pane on screen's output count when its last read began. */
    private long screenOutput = Long.MIN_VALUE;
    private String screenAddress = "";
    private String inFlight;
    private long inFlightOutput;

    /**
     * The address to read now, or null; a non-null answer is in flight until {@link #finished}.
     *
     * @param supported the core offers {@code read-bounded} and {@code incarnation}
     * @param machineProven this link's machine is bound (a direct hello, not refused)
     * @param metered the phone's network is metered: only the pane on screen is read
     * @param onScreen the pane on screen, live
     * @param output its output count so far: unchanged since its last read, nothing new to read
     */
    String next(boolean supported, boolean machineProven, boolean metered, String onScreen, long output,
            List<Pane> panes, long now) {
        if (!supported || !machineProven || inFlight != null || panes == null) return null;
        for (Pane p : panes) {
            if (!p.address.equals(onScreen) || !p.readable()) continue;
            boolean printed = !onScreen.equals(screenAddress) || output != screenOutput;
            if (printed && due(p.address, now, SCREEN_EVERY_MS)) return start(p.address, onScreen, output);
        }
        if (metered || now - lastAnyRead < SPACING_MS) return null;
        Pane pick = null;
        for (Pane p : panes) {
            if (p.address.equals(onScreen) || !p.readable() || !due(p.address, now, OTHERS_EVERY_MS)) continue;
            if (pick == null || last(p.address) < last(pick.address)) pick = p;
        }
        return pick == null ? null : start(pick.address, null, 0);
    }

    private String start(String address, String screen, long output) {
        inFlight = address;
        if (screen != null) {
            screenAddress = screen;
            inFlightOutput = output;
            screenOutput = output;
        }
        return address;
    }

    /** The read of {@code address} ended (kept or not) at {@code now}. */
    void finished(String address, long now) {
        if (address == null || !address.equals(inFlight)) return;
        inFlight = null;
        lastRead.put(address, now);
        lastAnyRead = now;
        if (address.equals(screenAddress)) screenOutput = inFlightOutput;
    }

    private boolean due(String address, long now, long every) {
        Long last = lastRead.get(address);
        return last == null || now - last >= every;
    }

    private long last(String address) {
        Long last = lastRead.get(address);
        return last == null ? Long.MIN_VALUE : last;
    }

    /**
     * Whether a reply may be kept: read on the link that is still current ({@code tokenAtStart}
     * equals {@code tokenNow}), and of the incarnation the list names now. A core that could not
     * say which program it read gives nothing to keep.
     */
    static boolean keep(String replyIncarnation, String listedIncarnation, long tokenAtStart, long tokenNow) {
        return tokenAtStart == tokenNow && TranscriptKey.plainIncarnation(replyIncarnation)
                && replyIncarnation.equals(listedIncarnation);
    }
}
