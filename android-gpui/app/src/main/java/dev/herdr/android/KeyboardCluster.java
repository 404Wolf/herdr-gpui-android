package dev.herdr.android;

/** Stub: failing-first. */
final class KeyboardCluster {
    private KeyboardCluster() { }

    static boolean overlay(boolean ime, boolean agent, boolean covered) { return false; }

    static boolean toolbarShown(boolean ime, boolean agent, boolean covered) { return true; }

    static int belowTerminal(boolean overlay, int clusterPx, float footerPx) { return clusterPx; }

    static float ride(int laidOutImePx, float imeNowPx) { return 0f; }
}
