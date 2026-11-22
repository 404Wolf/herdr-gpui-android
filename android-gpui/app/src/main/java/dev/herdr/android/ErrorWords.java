package dev.herdr.android;

/**
 * One mapping from a remote failure's kind (RemoteError, a closed set) to
 * the words the phone shows (factory audit, PR 9): the rail's actions, the
 * panel's and Usage's reads, Home's connections. Decided by kind only, never
 * by the failure's text. Pure.
 */
final class ErrorWords {
    private ErrorWords() { }

    /**
     * What to say about {@code failure} on {@code machine} ("" when unknown):
     * "server is busy; trying again", "Can't reach server", …
     */
    static String of(Throwable failure, String machine) {
        String on = machine == null || machine.isEmpty() ? "the computer" : machine;
        if (!(failure instanceof RemoteError)) return "Can't reach " + on;
        RemoteError e = (RemoteError) failure;
        switch (e.kind) {
            case RemoteError.BUSY: return on + " is busy; trying again";
            case RemoteError.REFUSED: return refused(e.coreKind, on);
            case RemoteError.TIMEOUT: return on + " didn't answer in time; it may still finish there";
            case RemoteError.CLOSED: return "The connection to " + on + " ended";
            case RemoteError.NETWORK: return "Can't reach " + on;
            case RemoteError.CAPACITY: return "Too much is going on with " + on + "; try again in a moment";
            case RemoteError.PROTOCOL: return on + " answered something this app can't read";
            case RemoteError.INVALID: return "That isn't something " + on + " can do here";
            case RemoteError.LOCAL: return "Something went wrong on this phone";
            default: return "Can't reach " + on;
        }
    }

    /** A refusal by the core's own kind. */
    static String refused(String coreKind, String on) {
        switch (coreKind == null ? "" : coreKind) {
            case "unavailable": return on + " is busy; trying again";
            case "not_found": return "That isn't on " + on + " any more";
            case "denied": case "forbidden": return on + " didn't allow it";
            case "conflict": return "Something changed on " + on + " meanwhile; try again";
            default: return on + " refused it";
        }
    }

    /** Worth a retry with backoff (RemoteError.busy): the words then say "trying again". */
    static boolean retries(Throwable failure) {
        return RemoteError.busy(failure);
    }
}
