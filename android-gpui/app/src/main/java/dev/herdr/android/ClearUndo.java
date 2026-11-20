package dev.herdr.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The composer's clear (✕, item 76) and its undo, instead of a confirm dialog. A tap clears the
 * pane's draft and cancels a dictation still recording or transcribing, for good (item 74: its
 * text never lands afterwards). A second tap within the undo window, or a long press, clears the
 * pane's queue too, never a message being sent. Dropping one queued row is undoable the same way.
 *
 * <p>Nothing is lost or sent by a crash at any step. Each step is ordered so that a step that
 * makes the next one safe is on disk first, and what a crash between them leaves is put right by
 * {@link #reconcile}:
 *
 * <ul>
 *   <li>Clearing: the recordings first (held, in one write), then the messages (marked cleared in
 *       the outbox, kept, never sent), then the undo record together with the emptied draft (one
 *       write). A crash after the holds leaves the draft and messages with their chips: reconcile
 *       lets those recordings go again. A crash after the messages leaves them cleared on disk
 *       without a record: reconcile adopts them into the window, so the undo still brings them back.
 *   <li>Undo: the record, marked undone, together with the restored draft (one write; if it fails
 *       the undo fails, says so, and stays on offer), then the messages and recordings back. Until
 *       those are saved too the record stays, and reconcile (soon, and after a restart) finishes.
 *   <li>Always: a recording a live draft or message holds is never held (reconcile lets it go),
 *       so a refused or interrupted clear never strands a chip on quarantined audio.
 * </ul>
 *
 * <p>Pure: no views; the composer does the work through {@link Effects}. Main thread. Times are
 * epoch milliseconds (they outlive the process).
 */
final class ClearUndo {
    /** How long "cleared · undo" stays after clearing the draft, and after clearing the queue too. */
    static final long DRAFT_WINDOW_MS = 6000, QUEUE_WINDOW_MS = 8000;

    /** What clearing does to the composer, the outbox and the recordings. */
    interface Effects {
        /** Ends a dictation still recording or transcribing, discarding it; true when one ran. */
        boolean cancelDictation();

        /** The pane's draft. */
        String draftText();

        /** Sets the pane's draft (in memory; {@link #persist} saves it). */
        void setDraft(String text);

        /** Puts {@code text} back as the pane's draft, ahead of anything typed since (in memory). */
        void restoreDraft(String text);

        /** The pane's queued messages a clear takes: all but one being sent. */
        List<String> clearable();

        /** A message's text, cleared or not; null when there is none. */
        String messageText(String id);

        /** The pane's recordings (waiting or parked) that no draft or message holds. */
        List<String> looseClips();

        /** Holds these recordings, all or none, saved; false when that failed (none held). */
        boolean holdClips(List<String> ids);

        /** Lets held recordings go (waiting, or parked, as before), all or none, saved. */
        boolean releaseClips(List<String> ids);

        /** Marks messages cleared until {@code until}, saved; false when that failed (none). */
        boolean clearMessages(List<String> ids, long until);

        /** Un-marks cleared messages at once; true when that is saved too. */
        boolean unclearMessages(List<String> ids);

        /** The window closed: cleared messages go for good. */
        void purgeMessages(List<String> ids);

        /** Saves the drafts and the undo record ({@code ""} for none) in one write; false on failure. */
        boolean persist(String record);

        /** Whether the outbox has been read from disk (its cleared messages are known). */
        boolean outboxLoaded();

        /** The host's messages cleared and still in their window at {@code now}. */
        List<Outbox.Cleared> clearedMessages(long now);

        /** Every draft (every pane) and every message not cleared: what holds its chips. */
        List<String> liveTexts();

        /** Something could not be saved: call {@link #reconcile} again in a moment. */
        void retryLater();

        /** Tells the user a clear or an undo could not be done. */
        void failed(String why);
    }

    /** What the window would undo. */
    enum Level { NONE, DRAFT, QUEUE, ROW }

    private final Effects fx;
    private Level level = Level.NONE;
    private String owner = "";
    private long until;
    private String draft = "";
    private final List<String> messages = new ArrayList<>();
    private final List<String> clips = new ArrayList<>();
    /** The user undid it; the record stays until the messages and recordings are back on disk. */
    private boolean undone;
    /**
     * A queue clear or a drop in progress, saved before its first effect: what it is about to hold
     * and clear. After a crash, {@link #reconcile} finishes it when its messages were cleared on
     * disk, or gives its recordings back when they were not (a loose recording has no chip that
     * would say so).
     */
    private Level pendingLevel = Level.NONE;
    private long pendingUntil;
    private final List<String> pendingMessages = new ArrayList<>();
    private final List<String> pendingClips = new ArrayList<>();
    private boolean cancelled;

    ClearUndo(Effects fx) {
        this.fx = fx;
    }

    /**
     * ✕ tapped: the draft (and a dictation in flight); a second tap in the window, the queue too.
     * With no draft and no dictation ({@code draftClearable} false) the tap clears the queue.
     */
    void tap(String owner, boolean draftClearable, long now) {
        if (open(owner, now) && level == Level.DRAFT) {
            clearQueue(now);
            return;
        }
        if (!begin(now) || !clearDraft(owner, now)) return;
        if (!draftClearable) clearQueue(now);
    }

    /** ✕ long-pressed: the draft, a dictation in flight and the pane's queue at once. */
    void longPress(String owner, long now) {
        if (!(open(owner, now) && level == Level.DRAFT)) {
            if (!begin(now) || !clearDraft(owner, now)) return;
        }
        clearQueue(now);
    }

    /**
     * One queued row dropped: a message ({@code messageId}) or a recording ({@code clipId});
     * undoable for {@link #DRAFT_WINDOW_MS}. False when it could not be done durably.
     */
    boolean dropped(String owner, String messageId, String clipId, long now) {
        if (!begin(now)) return false;
        List<String> ids = messageId == null ? Collections.emptyList() : Collections.singletonList(messageId);
        List<String> held = new ArrayList<>();
        if (messageId != null) held.addAll(DictationToken.ids(text(messageId)));
        if (clipId != null) held.add(clipId);
        this.owner = owner;
        if (!step(Level.ROW, now + DRAFT_WINDOW_MS, ids, held)) {
            fx.failed("Could not drop: storage");
            return false;
        }
        return true;
    }

    /**
     * One step of a clear that takes messages and recordings: its intent saved first, then the
     * recordings held, the messages cleared, and the record saved with the step in it. False
     * (and nothing taken, or soon given back) when any of these could not be saved.
     */
    private boolean step(Level to, long end, List<String> ids, List<String> held) {
        pendingLevel = to;
        pendingUntil = end;
        pendingMessages.addAll(ids);
        pendingClips.addAll(held);
        if (!fx.persist(encode())) {
            clearPending();
            if (level == Level.NONE) reset();
            return false;
        }
        if (!take(held, ids, end)) {
            abandon();
            if (level == Level.NONE && pendingLevel == Level.NONE) reset();
            return false;
        }
        Level was = level;
        long wasUntil = until;
        finishPending();
        if (!fx.persist(encode())) {
            messages.removeAll(ids);
            clips.removeAll(held);
            level = was;
            until = wasUntil;
            pendingLevel = to;
            pendingUntil = end;
            pendingMessages.addAll(ids);
            pendingClips.addAll(held);
            abandon();
            if (level == Level.NONE && pendingLevel == Level.NONE) reset();
            return false;
        }
        return true;
    }

    /** The pending step is part of the window now. */
    private void finishPending() {
        messages.addAll(pendingMessages);
        for (String c : pendingClips) if (!clips.contains(c)) clips.add(c);
        if (level == Level.NONE || level == Level.DRAFT || pendingLevel == Level.QUEUE) level = pendingLevel;
        until = Math.max(until, pendingUntil);
        clearPending();
    }

    /**
     * The pending step did not happen: its messages and recordings back as they were; its intent
     * goes once that is saved (else {@link #reconcile} tries again).
     */
    private void abandon() {
        boolean saved = pendingMessages.isEmpty() || fx.unclearMessages(new ArrayList<>(pendingMessages));
        if (!pendingClips.isEmpty() && !fx.releaseClips(new ArrayList<>(pendingClips))) saved = false;
        if (!saved) {
            fx.retryLater();
            return;
        }
        clearPending();
        if (!fx.persist(encode())) fx.retryLater();
    }

    private void clearPending() {
        pendingLevel = Level.NONE;
        pendingUntil = 0;
        pendingMessages.clear();
        pendingClips.clear();
    }

    /** Before a new clear: the last one finished (an undo restored, a window committed). */
    private boolean begin(long now) {
        reconcile(now);
        if (undone || pendingLevel != Level.NONE) {
            fx.failed("Still restoring the last clear: storage");
            return false;
        }
        commit();
        return true;
    }

    private boolean clearDraft(String owner, long now) {
        cancelled = fx.cancelDictation();
        String text = fx.draftText();
        List<String> held = DictationToken.ids(text);
        if (!held.isEmpty() && !fx.holdClips(held)) {
            fx.failed("Could not clear: storage");
            return false;
        }
        fx.setDraft("");
        this.owner = owner;
        level = Level.DRAFT;
        until = now + DRAFT_WINDOW_MS;
        draft = text;
        clips.addAll(held);
        // The record and the emptied draft, in one write.
        if (!fx.persist(encode())) {
            fx.setDraft(text);
            reset();
            // The draft holds its chips again: their recordings go back (now, or soon).
            if (!held.isEmpty() && !fx.releaseClips(held)) fx.retryLater();
            fx.failed("Could not clear: storage");
            return false;
        }
        return true;
    }

    private void clearQueue(long now) {
        List<String> ids = fx.clearable();
        Set<String> held = new LinkedHashSet<>();
        for (String id : ids) held.addAll(DictationToken.ids(text(id)));
        held.addAll(fx.looseClips());
        held.removeAll(clips);
        if (!step(Level.QUEUE, now + QUEUE_WINDOW_MS, ids, new ArrayList<>(held))) {
            fx.failed("Could not clear the queue: storage");
        }
    }

    /** Holds the recordings, then clears the messages; false (nothing changed) when either failed. */
    private boolean take(List<String> held, List<String> ids, long end) {
        if (!held.isEmpty() && !fx.holdClips(held)) return false;
        return ids.isEmpty() || fx.clearMessages(ids, end);
    }

    private String text(String messageId) {
        String t = fx.messageText(messageId);
        return t == null ? "" : t;
    }

    /**
     * "undo" tapped: everything the window cleared comes back. False when the window closed, or
     * when the restored draft could not be saved (then nothing changed, the user is told and the
     * undo stays on offer).
     */
    boolean undo(String owner, long now) {
        if (owner.isEmpty() || !this.owner.equals(owner)) return false;
        if (!open(now)) {
            if (!undone) commit();
            return false;
        }
        String before = fx.draftText();
        if (!draft.isEmpty()) fx.restoreDraft(draft);
        undone = true;
        if (!fx.persist(encode())) {
            undone = false;
            fx.setDraft(before);
            fx.failed("Could not undo: storage");
            return false;
        }
        finishUndo();
        return true;
    }

    /** The undone clear's messages and recordings (a pending step's too) back; then the record goes. */
    private void finishUndo() {
        // A step still pending (its rollback not yet saved) is undone with the rest: the record
        // goes only once every message and recording it covers is back on disk.
        Set<String> ids = new LinkedHashSet<>(messages);
        ids.addAll(pendingMessages);
        Set<String> held = new LinkedHashSet<>(clips);
        held.addAll(pendingClips);
        boolean saved = ids.isEmpty() || fx.unclearMessages(new ArrayList<>(ids));
        if (!held.isEmpty() && !fx.releaseClips(new ArrayList<>(held))) saved = false;
        if (saved && fx.persist("")) {
            reset();
        } else {
            fx.retryLater();
        }
    }

    /**
     * Puts right what an interrupted clear or undo left (see the class comment), once the outbox
     * is known: finishes an undo, adopts cleared messages no record has, lets go of recordings a
     * live draft or message holds, and ends a window that has closed. Safe to call at any time.
     */
    void reconcile(long now) {
        if (!fx.outboxLoaded()) return;
        if (undone) finishUndo();
        if (!undone) {
            if (pendingLevel != Level.NONE) resolvePending();
            adopt(now);
            purgeExpired(now);
        }
        // A chip in a live draft or message always has its recording waiting, never held.
        List<String> live = new ArrayList<>();
        for (String t : fx.liveTexts()) live.addAll(DictationToken.ids(t));
        if (!live.isEmpty() && !fx.releaseClips(live)) fx.retryLater();
        expire(now);
    }

    /** A step interrupted by a crash: finished when its messages were cleared, else given back. */
    private void resolvePending() {
        Set<String> marked = new java.util.HashSet<>();
        for (Outbox.Cleared c : fx.clearedMessages(0)) marked.add(c.id);
        boolean cleared = false;
        for (String id : pendingMessages) if (marked.contains(id)) cleared = true;
        if (!cleared) {
            abandon();
            return;
        }
        if (!pendingClips.isEmpty() && !fx.holdClips(new ArrayList<>(pendingClips))) fx.retryLater();
        finishPending();
        if (!fx.persist(encode())) fx.retryLater();
    }

    /**
     * Cleared messages whose window passed and that no record or pending step has go for good
     * (here, not when the outbox is read: an undo accepted before a restart must find them).
     */
    private void purgeExpired(long now) {
        List<String> gone = new ArrayList<>();
        for (Outbox.Cleared c : fx.clearedMessages(0)) {
            if (c.until <= now && !messages.contains(c.id) && !pendingMessages.contains(c.id)) gone.add(c.id);
        }
        if (!gone.isEmpty()) fx.purgeMessages(gone);
    }

    /** Messages cleared on disk that the record does not have (a crash before it was saved). */
    private void adopt(long now) {
        List<String> found = new ArrayList<>();
        Set<String> held = new LinkedHashSet<>();
        long end = until;
        String at = owner;
        for (Outbox.Cleared c : fx.clearedMessages(now)) {
            if (messages.contains(c.id) || pendingMessages.contains(c.id)) continue;
            if (level != Level.NONE && !owner.equals(c.owner)) continue;
            if (at.isEmpty()) at = c.owner;
            if (!at.equals(c.owner)) continue;
            found.add(c.id);
            held.addAll(DictationToken.ids(c.text));
            end = Math.max(end, c.until);
        }
        if (found.isEmpty()) return;
        held.removeAll(clips);
        List<String> newClips = new ArrayList<>(held);
        if (!newClips.isEmpty() && !fx.holdClips(newClips)) fx.retryLater();
        if (level == Level.NONE || level == Level.DRAFT) level = Level.QUEUE;
        owner = at;
        until = end;
        messages.addAll(found);
        clips.addAll(newClips);
        if (!fx.persist(encode())) fx.retryLater();
    }

    /** Whether the pane on screen owns an open undo window. */
    boolean open(String owner, long now) {
        return !owner.isEmpty() && this.owner.equals(owner) && open(now);
    }

    /** Whether the undo window is open. */
    boolean open(long now) {
        return level != Level.NONE && !undone && now < until;
    }

    /** The window closed (or the pane changed): what was cleared stays cleared. */
    void expire(long now) {
        if (level != Level.NONE && !undone && now >= until) commit();
    }

    /** Ends the window now: cleared messages go for good, held recordings stay held. */
    void commit() {
        if (level == Level.NONE || undone || pendingLevel != Level.NONE) return;
        if (!messages.isEmpty()) fx.purgeMessages(new ArrayList<>(messages));
        reset();
        // Not saved: the record, past its window or committed again on the next start, is harmless.
        fx.persist("");
    }

    /** When the window closes, for a refresh; {@link Long#MAX_VALUE} with none. */
    long until() {
        return level == Level.NONE || undone ? Long.MAX_VALUE : until;
    }

    Level level() {
        return level;
    }

    /** Whether an undo is still being saved (its record stays until it is). */
    boolean restoring() {
        return undone;
    }

    /** Whether the clear also cancelled a dictation in flight. */
    boolean cancelledDictation() {
        return cancelled;
    }

    /** The undo control's words. */
    String label(boolean narrow) {
        if (narrow) return "undo";
        switch (level) {
            case QUEUE: return "cleared " + (messages.size() + (draft.isEmpty() ? 0 : 1)) + " · undo";
            case ROW: return "dropped · undo";
            default: return "cleared · undo";
        }
    }

    /** The undo record, as saved. */
    String encode() {
        try {
            return new JSONObject().put("pane", owner).put("level", level.name()).put("until", until)
                    .put("draft", draft).put("messages", new JSONArray(messages))
                    .put("clips", new JSONArray(clips)).put("undone", undone)
                    .put("pendingLevel", pendingLevel.name()).put("pendingUntil", pendingUntil)
                    .put("pendingMessages", new JSONArray(pendingMessages))
                    .put("pendingClips", new JSONArray(pendingClips)).toString();
        } catch (JSONException impossible) {
            return "";
        }
    }

    /**
     * Picks up a saved undo record after a restart: within its window the undo is offered again;
     * an undo that was not finished is finished by {@link #reconcile}; past the window, the clear
     * is committed. False when there was none.
     */
    boolean resume(String record, long now) {
        if (record == null || record.isEmpty()) return false;
        try {
            JSONObject o = new JSONObject(record);
            reset();
            // The persisted field also held bare pane IDs before clears were host-scoped.
            // Those records can finish queue/clip cleanup, but can't match a host-qualified owner.
            owner = o.optString("pane", "");
            level = Level.valueOf(o.optString("level", "NONE"));
            until = o.optLong("until", 0);
            draft = o.optString("draft", "");
            undone = o.optBoolean("undone", false);
            JSONArray m = o.optJSONArray("messages");
            for (int i = 0; m != null && i < m.length(); i++) messages.add(m.getString(i));
            JSONArray c = o.optJSONArray("clips");
            for (int i = 0; c != null && i < c.length(); i++) clips.add(c.getString(i));
            pendingLevel = Level.valueOf(o.optString("pendingLevel", "NONE"));
            pendingUntil = o.optLong("pendingUntil", 0);
            JSONArray pm = o.optJSONArray("pendingMessages");
            for (int i = 0; pm != null && i < pm.length(); i++) pendingMessages.add(pm.getString(i));
            JSONArray pc = o.optJSONArray("pendingClips");
            for (int i = 0; pc != null && i < pc.length(); i++) pendingClips.add(pc.getString(i));
        } catch (JSONException | IllegalArgumentException damaged) {
            reset();
            return false;
        }
        if (level == Level.NONE && pendingLevel == Level.NONE) return false;
        // Past the window the clear is committed, once any step in progress is settled.
        reconcile(now);
        return true;
    }

    /**
     * Whether the composer going from pane {@code previous} to {@code next} ends the window: an
     * undo belongs to the pane it cleared. The first pane after a start ({@code previous} "") is
     * no switch, so a clear resumed from before the restart stays on offer.
     */
    boolean endsOnPaneChange(String previous, String next) {
        return !previous.isEmpty() && !owner.equals(next);
    }

    private void reset() {
        level = Level.NONE;
        owner = "";
        until = 0;
        draft = "";
        messages.clear();
        clips.clear();
        undone = false;
        cancelled = false;
        clearPending();
    }
}
