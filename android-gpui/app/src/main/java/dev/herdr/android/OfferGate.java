package dev.herdr.android;

/**
 * Whether a tap on a held message's Send may type: only into the attach the offer was shown over,
 * live, with the terminal in view. A pane that reattached since (its program replaced on the same
 * address), a file page over it, or a dropped link and nothing is typed. Pure.
 */
final class OfferGate {
    private OfferGate() { }

    /**
     * @param offered the attach the offer or sheet was shown over
     * @param now the attach on screen at the tap
     * @param live the pane is live: attached over a direct link, painted, its machine proven
     * @param covered something (the file page) covers the terminal
     */
    static boolean mayType(long offered, long now, boolean live, boolean covered) {
        return live && !covered && offered > 0 && offered == now;
    }

    /** Where a tap was made: the pane, its session handle and its attach, taken together. */
    static final class Tap {
        final String pane;
        final long handle;
        final long attach;

        Tap(String pane, long handle, long attach) {
            this.pane = pane == null ? "" : pane;
            this.handle = handle;
            this.attach = attach;
        }
    }

    /**
     * {@link #mayType(long, long, boolean, boolean)} for a tap captured as one {@link Tap}: the
     * pane, session and attach on screen now must all be the tap's. Nothing read after the tap
     * (a pane switch while the outbox saved) can redirect it.
     */
    static boolean mayType(Tap tap, String pane, long handle, long attach, boolean live, boolean covered) {
        return tap.pane.equals(pane) && tap.handle == handle && tap.handle > 0
                && mayType(tap.attach, attach, live, covered);
    }
}
