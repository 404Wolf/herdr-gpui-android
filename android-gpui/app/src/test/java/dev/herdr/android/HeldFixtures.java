package dev.herdr.android;

import java.util.UUID;

/** Held messages for tests: a proven machine per host name, and its panes. */
final class HeldFixtures {
    private HeldFixtures() { }

    static MachineKey machine(String host) {
        return MachineKey.of("core-" + host, "SHA256:key-" + host);
    }

    static Outbox.Target target(String host, String pane) {
        return new Outbox.Target(machine(host), host, "computer " + host, "default", pane, null, pane, "");
    }

    static Outbox.Live live(String host, String pane) {
        return new Outbox.Live(machine(host), host, pane, null);
    }

    static Outbox.Composed at(long wallMs) {
        return new Outbox.Composed(wallMs, wallMs, 1);
    }

    static Outbox.Message add(OutboxCourier courier, String host, String pane, String text) {
        return courier.add(UUID.randomUUID().toString(), target(host, pane), text, at(System.currentTimeMillis()));
    }

    static Outbox.Message add(Outbox outbox, String host, String pane, String text, long now) {
        return outbox.add(UUID.randomUUID().toString(), target(host, pane), text, at(now));
    }

    static java.util.List<Outbox.Message> shown(Outbox outbox, String host, String pane) {
        return outbox.shown(machine(host), host, pane);
    }
}
