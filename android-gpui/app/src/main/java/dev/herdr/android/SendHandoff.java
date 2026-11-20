package dev.herdr.android;

import java.util.function.Predicate;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The durable handoff of a Send from the composer to the outbox (§3.2 of the offline design).
 * A tap on Send does, in order, before anything else:
 *
 * <ol>
 *   <li>saves the draft's exact text with a new submission id, synchronously;
 *   <li>holds the outbox message under that id, and fsyncs it; on failure the draft stays;
 *   <li>clears the draft and the submission record, synchronously;
 *   <li>only then acts on the message.
 * </ol>
 *
 * <p>So a kill after step 1 leaves the exact text on disk as a draft or as a message, never both
 * and never neither: at the next start {@link #reconcile} clears a draft whose submission is
 * already held, and offers one that is not ("not sent: tap Send again"). A kill before step 1
 * loses at most the words typed since the last draft save. Pure: the steps are the caller's.
 */
final class SendHandoff {
    private SendHandoff() { }

    /** The composer's preference key for the submission in progress. */
    static final String KEY = "composer_send_handoff";
    /** The note for a draft whose Send did not reach the outbox before the app stopped. */
    static final String NOT_SENT = "not sent: tap Send again";

    /** A Send in progress: which draft it took, and exactly what it took. */
    static final class Record {
        final String id;
        /** The draft's key ({@link ComposerDrafts#key}). */
        final String owner;
        final String text;

        Record(String id, String owner, String text) {
            this.id = id;
            this.owner = owner;
            this.text = text;
        }

        String encode() {
            try {
                return new JSONObject().put("id", id).put("owner", owner).put("text", text).toString();
            } catch (JSONException impossible) {
                return "";
            }
        }

        static Record decode(String saved) {
            if (saved == null || saved.isEmpty()) return null;
            try {
                JSONObject value = new JSONObject(saved);
                String id = value.optString("id", "");
                String owner = value.optString("owner", "");
                if (id.isEmpty() || owner.isEmpty()) return null;
                return new Record(id, owner, value.optString("text", ""));
            } catch (JSONException damaged) {
                return null;
            }
        }
    }

    /** The steps a Send takes; each is synchronous and says whether it is on disk. */
    interface Steps {
        /** Step 1: the drafts and {@code record}, in one synchronous commit. */
        boolean commitDraft(Record record);
        /** Step 2: the outbox message under {@code record.id}, fsynced. */
        boolean holdMessage(Record record);
        /** Step 3: the draft cleared and the record removed, in one synchronous commit. */
        boolean clearDraft(Record record);
        /** The record removed (the message was not held: the draft stays as it is). */
        void forget(Record record);
    }

    /** How a Send ended. */
    enum Result {
        /** Held: act on it now. */
        HELD,
        /** Held, but the draft could not be cleared on disk: the next start clears it. */
        HELD_DRAFT_STALE,
        /** The draft could not be saved: nothing was held, the draft stays. */
        DRAFT_NOT_SAVED,
        /** The message could not be held (bounds, no machine, a failed save): the draft stays. */
        NOT_HELD
    }

    static Result run(Record record, Steps steps) {
        if (!steps.commitDraft(record)) return Result.DRAFT_NOT_SAVED;
        if (!steps.holdMessage(record)) {
            steps.forget(record);
            return Result.NOT_HELD;
        }
        return steps.clearDraft(record) ? Result.HELD : Result.HELD_DRAFT_STALE;
    }

    /** What a start does with a record left behind. */
    enum Recovery {
        /** No record. */
        NONE,
        /** Its message is held: the draft that still holds its text is cleared. */
        CLEAR_DRAFT,
        /** Its message is not held: the draft stays, with {@link #NOT_SENT}. */
        OFFER_AGAIN
    }

    /** Decided only once the outbox has been read: {@code held} says whether an id is a message. */
    static Recovery reconcile(Record record, Predicate<String> held) {
        if (record == null) return Recovery.NONE;
        return held.test(record.id) ? Recovery.CLEAR_DRAFT : Recovery.OFFER_AGAIN;
    }
}
