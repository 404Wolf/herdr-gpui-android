package dev.herdr.android;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ignores a tap on a control that appeared or moved under the finger a moment ago. The prompt
 * line's controls shift when one comes or goes: when "cleared · undo" ends after its window,
 * "talk" slides left into the place "undo" had, and a tap aimed at "undo" just then would start
 * a recording. A control is only tappable {@link #SETTLE_MS} after it was last laid out
 * somewhere new. Pure: the caller gives the clock.
 */
final class TapGuard {
    static final long SETTLE_MS = 300;

    /** Each control's drawn words (left, right, top in dp) and when they last changed place. */
    private final Map<String, float[]> places = new HashMap<>();
    private final Map<String, Long> movedAt = new HashMap<>();

    /** The controls as just laid out at {@code now}; ids not in {@code rects} are forgotten. */
    void laid(List<ControlTargets.Rect> rects, long now) {
        Map<String, float[]> next = new HashMap<>();
        for (ControlTargets.Rect r : rects) {
            float[] place = {r.labelLeft, r.labelRight, r.top};
            float[] before = places.get(r.id);
            if (before == null || before[0] != place[0] || before[1] != place[1] || before[2] != place[2]) {
                movedAt.put(r.id, now);
            }
            next.put(r.id, place);
        }
        places.clear();
        places.putAll(next);
        movedAt.keySet().retainAll(next.keySet());
    }

    /** Whether a tap at {@code now} on {@code id} acts: it has been where it is long enough. */
    boolean accepts(String id, long now) {
        Long moved = movedAt.get(id);
        return moved == null || now - moved >= SETTLE_MS;
    }
}
