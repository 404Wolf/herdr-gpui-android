package dev.herdr.android;

/**
 * Send with an empty draft types the pane's held messages (Wolf 11-22). Pure rules, tested.
 * The harnesses' own behaviour, read from their source and binaries (not guessed):
 * <ul>
 *   <li>Codex (openai/codex tui chat_composer): Enter while a turn runs submits the message as a
 *       steer, taken in at once; Tab queues it; Enter on an empty prompt does nothing
 *       ({@code empty_enter_during_task_does_not_queue}). So typing each held message with Enter
 *       is Codex's own send-now; no extra key.
 *   <li>Claude Code 2.1.322: a message typed while it works waits in its queue, and its prompt
 *       says "Press up to edit queued messages, Enter to send them immediately". So after the
 *       held messages are typed, one Enter on the empty prompt sends them at once.
 * </ul>
 */
final class HeldFlush {
    /** The most held messages one tap types. */
    static final int MAX = 20;

    private HeldFlush() { }

    /** Whether the harness wants its send-now key (Enter on an empty prompt) after the messages are typed. */
    static boolean sendNowKey(String kind) {
        return "claude".equals(kind);
    }
}
