package dev.herdr.android;

/**
 * "Edit as draft" on a held message, durable at every moment: its text joins the pane's draft and
 * that draft is committed to disk before the message leaves the outbox, so a process killed in
 * between leaves the words in both places, never in neither. Pure: the steps are the caller's.
 */
final class HeldEdit {
    private HeldEdit() { }

    interface Steps {
        /** The message's text, or null when there is none to edit (gone, or being sent). */
        String text(String id);
        /** The text joined to the draft and the drafts committed synchronously. */
        boolean commitDraft(String text);
        /** The message taken out of the outbox; false when it could not be (being sent). */
        boolean take(String id);
        /** The draft as it was before {@link #commitDraft}, committed again. */
        void rollback();
    }

    enum Result { EDITED, GONE, DRAFT_NOT_SAVED, NOT_TAKEN }

    static Result run(String id, Steps steps) {
        String text = steps.text(id);
        if (text == null) return Result.GONE;
        if (!steps.commitDraft(text)) {
            steps.rollback();
            return Result.DRAFT_NOT_SAVED;
        }
        if (!steps.take(id)) {
            steps.rollback();
            return Result.NOT_TAKEN;
        }
        return Result.EDITED;
    }
}
