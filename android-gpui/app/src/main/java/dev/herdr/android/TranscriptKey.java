package dev.herdr.android;

/**
 * Which program a transcript was read from: the machine ({@link MachineKey}, proven by a direct
 * hello), the terminal (its address and {@code created_ms}) and the program in it (its
 * {@code incarnation}, which a core with the {@code incarnation} feature changes whenever a program
 * starts in the terminal). An old core gives no incarnation, so it gives no key: nothing is kept.
 */
final class TranscriptKey {
    final MachineKey machine;
    final String address;
    final long createdMs;
    final String incarnation;

    private TranscriptKey(MachineKey machine, String address, long createdMs, String incarnation) {
        this.machine = machine;
        this.address = address;
        this.createdMs = createdMs;
        this.incarnation = incarnation;
    }

    /** The key, or null when any part is missing or unproven (a relayed pane has none). */
    static TranscriptKey of(MachineKey machine, String address, Long createdMs, String incarnation) {
        if (machine == null || !plainAddress(address) || createdMs == null || createdMs <= 0
                || !plainIncarnation(incarnation)) {
            return null;
        }
        return new TranscriptKey(machine, address, createdMs, incarnation);
    }

    /** A terminal of the core itself: a relayed address ({@code machine/t7}) proves nothing. */
    static boolean plainAddress(String address) {
        if (address == null || address.isEmpty() || address.length() > 64) return false;
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') return false;
        }
        return true;
    }

    /** The core's incarnation: short, letters and digits only (so it can name a file). */
    static boolean plainIncarnation(String incarnation) {
        if (incarnation == null || incarnation.isEmpty() || incarnation.length() > 64) return false;
        for (int i = 0; i < incarnation.length(); i++) {
            char c = incarnation.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')) return false;
        }
        return true;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof TranscriptKey)) return false;
        TranscriptKey that = (TranscriptKey) other;
        return machine.equals(that.machine) && address.equals(that.address) && createdMs == that.createdMs
                && incarnation.equals(that.incarnation);
    }

    @Override public int hashCode() {
        return ((machine.hashCode() * 31 + address.hashCode()) * 31 + Long.hashCode(createdMs)) * 31
                + incarnation.hashCode();
    }
}
