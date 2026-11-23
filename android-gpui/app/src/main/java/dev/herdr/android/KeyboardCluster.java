package dev.herdr.android;

/**
 * Where the composer's icon cluster ({@link ActionBar}) goes while the keyboard is up (Wolf
 * 11-23): on an agent's pane it sits just above the keyboard, over the session, with no toolbar
 * and no strip reserved under the terminal; the terminal runs down to the keyboard and is lifted
 * only as far as keeps the prompt's row (the draft and its caret) clear of the cluster. While the
 * keyboard moves, the cluster rides on it. Pure, so the rules are tested without a device.
 */
final class KeyboardCluster {
    private KeyboardCluster() { }

    /** The cluster overlays the session: the keyboard is up on an agent's pane, nothing covers it. */
    static boolean overlay(boolean ime, boolean agent, boolean covered) {
        return ime && agent && !covered;
    }

    /** The bottom toolbar shows unless the cluster overlays the session (a shell keeps its keys). */
    static boolean toolbarShown(boolean ime, boolean agent, boolean covered) {
        return !overlay(ime, agent, covered);
    }

    /**
     * Pixels kept free under the terminal for a cluster {@code clusterPx} tall (with its margin).
     * Overlaid, only what the cluster would cover of the prompt's row: {@code footerPx} is how far
     * the bottom of the prompt's last row is above the terminal's bottom (NaN when not known, then
     * the whole cluster). Otherwise the cluster keeps its own room, as before.
     */
    static int belowTerminal(boolean overlay, int clusterPx, float footerPx) {
        if (!overlay || Float.isNaN(footerPx)) return Math.max(0, clusterPx);
        return Math.max(0, (int) Math.ceil(clusterPx - Math.max(0f, footerPx)));
    }

    /**
     * The cluster's translation while the keyboard animates: laid out above a keyboard
     * {@code laidOutImePx} tall, it moves by how far the keyboard ({@code imeNowPx} now) has still
     * to go, so it stays on the keyboard's top edge.
     */
    static float ride(int laidOutImePx, float imeNowPx) {
        return laidOutImePx - imeNowPx;
    }
}
