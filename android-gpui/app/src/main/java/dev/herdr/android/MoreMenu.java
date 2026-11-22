package dev.herdr.android;

/**
 * Where each item of the terminal's overflow menu (⋯) sits. Android reads the
 * top 16 bits of a menu order as its category, so an order must stay within
 * 0..0xFFFF: a negative one ("Panes" at -2 to put it first) made
 * {@code MenuBuilder} throw on Android 14 and the app close.
 */
final class MoreMenu {
    private MoreMenu() { }

    static final int PANES = 0;
    static final int HOSTS = 1;
    static final int VIEW = 2;
    static final int SESSION = 3;
    static final int CLOSE = 4;
    static final int GESTURES = 5;
    /** The inline "img" can be dropped on a narrow grid; this keeps a route to attach an image. */
    static final int ATTACH = 6;
    /** The connected machine's aswap accounts (UsageView). */
    static final int USAGE = 7;

    /** Every order, top to bottom as the menu shows them. */
    static int[] all() {
        return new int[] {PANES, HOSTS, VIEW, SESSION, CLOSE, GESTURES, ATTACH, USAGE};
    }
}
