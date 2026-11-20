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
}
