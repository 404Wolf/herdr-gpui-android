package dev.herdr.android;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The composer's local text, one draft per pane, so switching panes never loses
 * what was typed. Pure state with no views: editing never waits on the link.
 *
 * <p>Dictation joins the draft without owning it: {@link #dictationPartial}
 * replaces only the utterance in progress (engines send the whole utterance so
 * far, not a delta), and {@link #dictationFinal} commits it. A dictation
 * recorded while the desktop was away leaves a {@link DictationToken} where its
 * text will go ({@link #dictationPending}); {@link #placeDictation} fills it in,
 * in whichever pane's draft it is.
 *
 * <p>Where the desktop cut a long dictation's audio without a verified seam
 * ({@link dev.herdr.android.dictation.UnverifiedCuts}), a mark is kept beside the
 * draft, never in it: {@link #cuts} says where to draw it. A mark is the draft's
 * text before it (its length and hash), so it shows only while that text is
 * unchanged: typing after it keeps it, deleting back over it removes it. Marks
 * are not saved with the drafts; they are a hint, and a restart drops them.
 */
final class ComposerDrafts {
    /** Drafts kept for panes not on screen; the oldest is dropped beyond this. */
    static final int MAX_PANES = 32;
    /** A draft longer than this is not saved (it still lives while the app does). */
    static final int MAX_SAVED_CHARS = 64 * 1024;

    // Fixed extensions let a match stop at the filename even when more letters follow it.
    private static final String ATTACHMENT_FILENAME = "herdr-paste-[0-9]+(?:-[0-9]+)?\\.(?:png|jpg|gif|webp)";
    private static final class AttachmentPaths {
        final String revision;
        final Set<String> directories;
        final Pattern pattern;

        AttachmentPaths(String revision, Set<String> directories) {
            this.revision = revision;
            this.directories = directories;
            // An exact prefix preserves adjacent text that would also be valid in a shell path.
            StringJoiner alternatives = new StringJoiner("|");
            for (String directory : directories) {
                String quoted = ImageAttach.quotePath(directory);
                if (!quoted.equals(directory)) {
                    alternatives.add(Pattern.quote(quoted.substring(0, quoted.length() - 1))
                            + ATTACHMENT_FILENAME + "'");
                }
                alternatives.add(Pattern.quote(directory) + ATTACHMENT_FILENAME);
            }
            pattern = Pattern.compile(alternatives.toString());
        }

        static AttachmentPaths restore(String revision, JSONArray saved) {
            if (revision.isEmpty() || saved == null) return null;
            Set<String> directories = new LinkedHashSet<>();
            for (int i = 0; i < saved.length(); i++) {
                String directory = saved.optString(i);
                if (directory.startsWith("/") && directory.endsWith("/")) directories.add(directory);
            }
            return directories.isEmpty() ? null : new AttachmentPaths(revision, directories);
        }
    }

    static final class Draft {
        static final Draft EMPTY = new Draft("", null);
        final String text;
        private final AttachmentPaths attachments;
        /** When it was last edited (epoch ms); 0 when not known (saved by an older build). */
        final long at;

        private Draft(String text, AttachmentPaths attachments) {
            this(text, attachments, 0);
        }

        private Draft(String text, AttachmentPaths attachments, long at) {
            this.text = text;
            this.attachments = attachments != null && attachments.pattern.matcher(text).find() ? attachments : null;
            this.at = at;
        }

        String revision() { return attachments == null ? null : attachments.revision; }

        private Draft withoutAttachments() {
            String remaining = attachments.pattern.matcher(text).replaceAll("");
            return remaining.trim().isEmpty() ? EMPTY : new Draft(remaining, null, at);
        }

        Object encode() throws JSONException {
            if (attachments == null && at <= 0) return text;
            JSONObject out = new JSONObject().put("text", text);
            if (at > 0) out.put("at", at);
            if (attachments == null) return out;
            return out.put("revision", revision()).put("staging", new JSONArray(attachments.directories));
        }

        static Draft decode(Object value) {
            if (value instanceof String) return new Draft((String) value, null);
            if (!(value instanceof JSONObject)) return EMPTY;
            JSONObject saved = (JSONObject) value;
            Object text = saved.opt("text");
            Object revision = saved.opt("revision");
            long at = Math.max(0, saved.optLong("at", 0));
            if (!(text instanceof String)) return EMPTY;
            if (revision == null) return new Draft((String) text, null, at);
            if (!(revision instanceof String)) return EMPTY;
            AttachmentPaths paths = AttachmentPaths.restore((String) revision, saved.optJSONArray("staging"));
            if (paths == null) return EMPTY;
            Draft draft = new Draft((String) text, paths, at);
            return draft.attachments == null ? EMPTY : draft;
        }
    }

    private static <V> Map<String, V> recentPanes() {
        return new LinkedHashMap<String, V>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_PANES;
            }
        };
    }

    private final Map<String, Draft> drafts = recentPanes();
    /**
     * Drafts found on disk at start, kept but not loaded into the composer: a restart may have
     * changed what runs in the pane, so each is offered ("draft from 14:02") and loaded only by
     * the user's tap ({@link #acceptOffered}). Saved with the drafts until accepted.
     */
    private final Map<String, java.util.List<Draft>> offered = recentPanes();
    /** The key prefix offered drafts are saved under, each pane's as a list, apart from typed ones. */
    private static final String OFFERED = "\u0002";
    /** The wall clock for a draft's edit time. */
    java.util.function.LongSupplier clock = System::currentTimeMillis;
    // Cut can remove the last path before Paste puts it back. This history lives only in memory.
    private final Map<String, AttachmentPaths> cutPaths = recentPanes();
    /**
     * The draft key of the pane on screen: {@link #key} of its host and pane id. Pane ids are only
     * unique per computer ("t1" exists on every herdr-core), so a draft is never kept under the id alone.
     */
    private String pane = "";
    private String host = "";
    private String connectionRevision;
    /** The draft before the utterance in progress, or null when not dictating. */
    private String dictationBase;
    /**
     * Where the utterance in progress sits in the draft, [spanStart, spanEnd): tracked by position
     * through every edit (before it shifts it, after it leaves it, inside it taints it), never found
     * again by matching text. -1 when no utterance is in progress.
     */
    private int spanStart = -1, spanEnd = -1;
    private boolean spanTainted;
    /** The span's words were joined to what precedes them by one added space, kept out of the span. */
    private boolean spanSeparator;
    /** At most this many marks per pane; the oldest go first. */
    static final int MAX_CUTS = 16;
    /** Each pane's unverified-cut marks: {length, hash} of the draft's text before each. */
    private final Map<String, java.util.List<long[]>> cutMarks = new java.util.HashMap<>();
    /**
     * The utterance in progress's cuts, anchored like the settled marks ({length, hash} of the
     * pane's draft before each): they show, move with a filled chip, and become marks when it
     * ends, only while the words before them are unchanged.
     */
    private java.util.List<long[]> liveMarks = new java.util.ArrayList<>();

    private static final char SEP = '\u0001';
    /** The key a draft is kept under: the computer and its pane id together. */
    static String key(String host, String pane) {
        String p = pane == null ? "" : pane;
        return host == null || host.isEmpty() ? p : host + SEP + p;
    }

    private static String rawPane(String key) {
        return key.substring(key.lastIndexOf(SEP) + 1);
    }

    /**
     * The computer whose panes are shown. Drafts saved before drafts knew their computer (a key with
     * no host) move to the first computer that can save their adoption.
     */
    void setHost(String next) {
        setHost(next, null, encoded -> true);
    }

    boolean setHost(String next, String revision, java.util.function.Predicate<String> persistMigration) {
        String target = next == null ? "" : next;
        if (target.equals(host) && Objects.equals(revision, connectionRevision)) return true;
        settleCuts(true);
        dictationBase = null;
        endSpan();
        for (java.util.Iterator<Map.Entry<String, Draft>> it = drafts.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Draft> entry = it.next();
            Draft draft = entry.getValue();
            if (!onHost(entry.getKey(), target) || draft.attachments == null
                    || draft.revision().equals(revision)) continue;
            Draft plain = draft.withoutAttachments();
            if (plain.text.isEmpty()) it.remove();
            else entry.setValue(plain);
        }
        cutPaths.entrySet().removeIf(entry -> onHost(entry.getKey(), target)
                && !entry.getValue().revision.equals(revision));
        boolean saved = target.isEmpty() || migrateLegacy(target, persistMigration);
        String raw = rawPane(pane);
        host = target;
        connectionRevision = revision;
        pane = key(host, raw);
        return saved;
    }

    String host() { return host; }
    String connectionRevision() { return connectionRevision; }

    private static boolean onHost(String draftKey, String host) {
        return host.isEmpty() ? draftKey.indexOf(SEP) < 0 : draftKey.startsWith(key(host, ""));
    }

    private boolean migrateLegacy(String to, java.util.function.Predicate<String> persist) {
        java.util.List<String> legacy = new java.util.ArrayList<>();
        for (String k : drafts.keySet()) if (k.indexOf(SEP) < 0) legacy.add(k);
        for (String k : new java.util.ArrayList<>(offered.keySet())) {
            java.util.List<Draft> pieces = k.indexOf(SEP) < 0 ? offered.remove(k) : null;
            if (pieces != null) offered.putIfAbsent(key(to, k), pieces);
        }
        if (legacy.isEmpty()) return true;
        Object before = snapshot();
        for (String k : legacy) {
            Draft draft = drafts.remove(k);
            String moved = key(to, k);
            if (draft != null) drafts.putIfAbsent(moved, draft);
            java.util.List<long[]> marks = cutMarks.remove(k);
            if (marks != null) cutMarks.putIfAbsent(moved, marks);
        }
        if (persist.test(encode())) return true;
        rollback(before);
        return false;
    }

    /** Shows {@code next}'s draft, keeping the current one for later. */
    String switchTo(String nextPane) {
        String target = key(host, nextPane);
        if (target.equals(pane)) return text();
        settleCuts(true);
        dictationBase = null;
        endSpan();
        pane = target;
        return text();
    }

    String pane() { return rawPane(pane); }

    String owner() { return host.isEmpty() || rawPane(pane).isEmpty() ? "" : pane; }

    /** Every pane's draft (the dictation chips they hold must keep their recordings). */
    java.util.List<String> all() {
        java.util.List<String> texts = new java.util.ArrayList<>();
        for (Draft draft : drafts.values()) texts.add(draft.text);
        for (java.util.List<Draft> pieces : offered.values()) for (Draft draft : pieces) texts.add(draft.text);
        return texts;
    }

    /**
     * The draft offered for the pane on screen (found on disk at start), or null: its pieces (a
     * draft typed beside an earlier offer is kept as a piece of its own) joined, oldest first.
     */
    Draft offered() {
        return joined(offered.get(pane));
    }

    private static Draft joined(java.util.List<Draft> pieces) {
        if (pieces == null || pieces.isEmpty()) return null;
        StringBuilder text = new StringBuilder();
        AttachmentPaths paths = null;
        long at = 0;
        for (Draft piece : pieces) {
            if (text.length() > 0) text.append('\n');
            text.append(piece.text.replaceAll("\\s+$", ""));
            if (piece.attachments != null) paths = piece.attachments;
            at = Math.max(at, piece.at);
        }
        return new Draft(text.toString(), paths, at);
    }

    /** Whether {@link #joinedBack} of {@code draft} and {@code text} can be saved whole. */
    static boolean fits(String draft, String text) {
        return joinedBack(draft, text).length() <= MAX_SAVED_CHARS;
    }

    /** {@code text} taken back into {@code draft}: after it, on a line of its own. */
    static String joinedBack(String draft, String text) {
        String base = draft == null ? "" : draft;
        return base.trim().isEmpty() ? text : base.replaceAll("\\s+$", "") + "\n" + text;
    }

    /**
     * The user took the offered draft: it is the pane's draft now, ahead of anything typed
     * since. The text now in the composer; null, and nothing taken, while a dictation runs (its
     * span would no longer match the words, and a cancel could cut restored ones).
     */
    String acceptOffered() {
        if (dictationBase != null) return null;
        Draft draft = joined(offered.remove(pane));
        if (draft == null) return text();
        Draft current = draft();
        String text = current.text.trim().isEmpty() ? draft.text
                : draft.text.replaceAll("\\s+$", "") + "\n" + current.text;
        putDraft(pane, new Draft(text, draft.attachments != null ? draft.attachments : current.attachments, clock.getAsLong()));
        return text();
    }

    /**
     * Removes the draft (offered or not) under {@code key} when it still holds {@code text}: a
     * Send whose message was held before the app stopped. True when one was removed.
     */
    boolean removeSent(String key, String text) {
        String wanted = text == null ? "" : text.trim();
        boolean removed = false;
        Draft draft = drafts.get(key);
        if (draft != null && draft.text.trim().equals(wanted)) {
            drafts.remove(key);
            cutPaths.remove(key);
            cutMarks.remove(key);
            removed = true;
        }
        java.util.List<Draft> pieces = offered.get(key);
        if (pieces != null && pieces.removeIf(piece -> piece.text.trim().equals(wanted))) {
            if (pieces.isEmpty()) offered.remove(key);
            removed = true;
        }
        return removed;
    }

    Draft draft() { return drafts.getOrDefault(pane, Draft.EMPTY); }

    String text() { return draft().text; }

    String draftRevision() { return draft().revision(); }

    /** The field changed under the user's fingers. */
    void edit(String text) {
        String old = text();
        String now = text == null ? "" : text;
        store(now);
        if (spanStart >= 0 && !spanTainted) followSpan(old, now);
    }

    private void store(String text) {
        updateDraft(pane, text, attachmentPaths(pane, draft()));
    }

    /** The span's place after {@code old} became {@code now} (one contiguous edit, as a keyboard makes). */
    private void followSpan(String old, String now) {
        int max = Math.min(old.length(), now.length());
        int p = 0;
        while (p < max && old.charAt(p) == now.charAt(p)) p++;
        int s = 0;
        while (s < max - p && old.charAt(old.length() - 1 - s) == now.charAt(now.length() - 1 - s)) s++;
        int oldEnd = old.length() - s;
        if (oldEnd <= spanStart) {
            // At or before the span's left edge (an insert exactly there is typed text, before the
            // dictated words): the whole span moves.
            int delta = now.length() - old.length();
            spanStart += delta;
            spanEnd += delta;
        } else if (p < spanEnd) {
            spanTainted = true;
        }
    }

    private void endSpan() {
        spanStart = -1;
        spanEnd = -1;
        spanTainted = false;
        spanSeparator = false;
    }

    void setDraft(Draft draft) { putDraft(pane, draft); }

    /** Puts back a cleared draft ahead of any new text, keeping both sets of attachment paths. */
    void restoreDraft(Draft cleared) {
        Draft current = draft();
        String text = current.text.trim().isEmpty() ? cleared.text
                : cleared.text.replaceAll("\\s+$", "") + " " + current.text;
        AttachmentPaths paths = current.attachments;
        if (cleared.attachments != null) {
            paths = cleared.attachments;
            if (current.attachments != null && !paths.directories.containsAll(current.attachments.directories)) {
                Set<String> directories = new LinkedHashSet<>(paths.directories);
                directories.addAll(current.attachments.directories);
                paths = new AttachmentPaths(paths.revision, directories);
            }
        }
        setDraft(new Draft(text, paths));
    }

    private AttachmentPaths attachmentPaths(String key, Draft draft) {
        return draft.attachments == null ? cutPaths.get(key) : draft.attachments;
    }

    private void updateDraft(String key, String text, AttachmentPaths paths) {
        Draft draft = new Draft(text, paths, clock.getAsLong());
        if (paths != null && draft.attachments == null) cutPaths.put(key, paths);
        else cutPaths.remove(key);
        putDraft(key, draft);
    }

    private void putDraft(String key, Draft draft) {
        if (draft.text.isEmpty()) drafts.remove(key);
        else drafts.put(key, draft);
    }

    void dictationStarted() {
        dictationBase = text();
        endSpan();
    }

    boolean dictating() { return dictationBase != null; }

    /**
     * Where the utterance in progress starts in the draft (its words may still
     * change), or -1 when none is in progress.
     */
    int partialFrom() {
        if (dictationBase == null || spanStart < 0 || spanTainted) return -1;
        String text = text();
        if (spanEnd <= spanStart || spanEnd > text.length()) return -1;
        return spanStart < spanEnd ? spanStart : -1;
    }

    /** The whole utterance so far; it replaces the previous partial. */
    String dictationPartial(String utterance) {
        return dictationPartial(utterance, null);
    }

    /** As {@link #dictationPartial(String)}, with the desktop's unverified cuts (offsets in {@code utterance}). */
    String dictationPartial(String utterance, int[] cuts) {
        String text = text();
        if (dictationBase == null) dictationBase = text;
        String prefix;
        String suffix;
        if (spanStart >= 0 && !spanTainted && spanEnd <= text.length()) {
            // The utterance replaces only its own span (and the space that joined it): what was
            // typed before and after it stays.
            int prefixEnd = spanSeparator && spanStart > 0 && text.charAt(spanStart - 1) == ' ' ? spanStart - 1 : spanStart;
            prefix = text.substring(0, prefixEnd);
            suffix = text.substring(spanEnd);
        } else {
            // A first partial, or one whose words were edited (they are now the user's): a new span
            // after everything that is there.
            prefix = text;
            suffix = "";
            dictationBase = text;
        }
        String joined = join(prefix, utterance);
        String spoken = utterance == null ? "" : utterance.trim();
        store(joined + suffix);
        spanEnd = joined.length();
        spanStart = spanEnd - spoken.length();
        spanSeparator = spanStart - prefix.length() == 1;
        spanTainted = false;
        liveMarks = anchors(text(), placed(prefix, utterance, cuts));
        return text();
    }

    String dictationFinal(String utterance) {
        return dictationFinal(utterance, null);
    }

    String dictationFinal(String utterance, int[] cuts) {
        String result = dictationPartial(utterance, cuts);
        settleCuts(true);
        dictationBase = null;
        endSpan();
        return result;
    }

    /**
     * The utterance was saved for the desktop to transcribe later: its token
     * takes the place of the utterance, and the draft goes on after it.
     */
    String dictationPending(String clipId) {
        return dictationFinal(DictationToken.of(clipId));
    }

    /**
     * Puts a transcribed dictation where its token is, in any pane's draft;
     * the pane whose draft held it, or null when no draft does (it was sent or
     * deleted: the outbox, then {@link #append}, are next).
     */
    String placeDictation(String clipId, String spoken) {
        for (Map.Entry<String, Draft> draft : drafts.entrySet()) {
            String before = draft.getValue().text;
            String filled = DictationToken.fill(before, clipId, spoken);
            if (filled == null) continue;
            String pane = draft.getKey();
            shiftMarks(pane, before, before.indexOf(DictationToken.of(clipId)), filled);
            if (pane.equals(this.pane) && dictationBase != null) {
                // A chip filled before the span moves it; one inside it makes its words the user's.
                int tokenAt = before.indexOf(DictationToken.of(clipId));
                int tokenEnd = tokenAt + DictationToken.of(clipId).length();
                if (spanStart >= 0 && !spanTainted) {
                    if (tokenEnd <= spanStart) {
                        spanStart += filled.length() - before.length();
                        spanEnd += filled.length() - before.length();
                    } else if (tokenAt < spanEnd) {
                        spanTainted = true;
                    }
                }
                String inBase = DictationToken.fill(dictationBase, clipId, spoken);
                if (inBase != null) dictationBase = inBase;
            }
            if (filled.isEmpty()) drafts.remove(pane);
            else draft.setValue(new Draft(filled, draft.getValue().attachments, draft.getValue().at));
            return rawPane(pane);
        }
        for (Map.Entry<String, java.util.List<Draft>> entry : offered.entrySet()) {
            java.util.List<Draft> pieces = entry.getValue();
            for (int i = 0; i < pieces.size(); i++) {
                Draft piece = pieces.get(i);
                String filled = DictationToken.fill(piece.text, clipId, spoken);
                if (filled == null) continue;
                if (filled.isEmpty()) pieces.remove(i);
                else pieces.set(i, new Draft(filled, piece.attachments, piece.at));
                if (pieces.isEmpty()) offered.remove(entry.getKey());
                return rawPane(entry.getKey());
            }
        }
        return null;
    }

    /** Adds {@code spoken} to the end of {@code pane}'s draft, e.g. a dictation whose token was deleted. */
    String append(String pane, String spoken) {
        return append(pane, spoken, null);
    }

    /** As {@link #append(String, String)}, with the desktop's unverified cuts (offsets in {@code spoken}). */
    String append(String pane, String spoken, int[] cuts) {
        return append(host, pane, spoken, cuts);
    }

    /** As {@link #append(String, String, int[])} into {@code onHost}'s draft of {@code pane}, shown or not. */
    String append(String onHost, String pane, String spoken, int[] cuts) {
        String key = key(onHost, pane);
        Draft draft = drafts.getOrDefault(key, Draft.EMPTY);
        String before = draft.text;
        String text = join(before, spoken);
        if (text.isEmpty()) return text;
        if (key.equals(this.pane) && dictationBase != null) dictationBase = join(dictationBase, spoken);
        updateDraft(key, text, attachmentPaths(key, draft));
        for (int at : placed(before, spoken, cuts)) mark(key, text, at);
        return text;
    }

    /**
     * Adds an attachment's paths, shell-quoted with a trailing space as on desktop, to the end
     * of the draft of {@code pane} on {@code onHost}, a space after what is already there; never
     * sent. While a dictation is in progress the paths also join its base, so the next partial or
     * final transcript keeps them.
     */
    String appendAttachment(String onHost, String revision, String pane, List<String> uploaded) {
        if (onHost.isEmpty() || revision == null || revision.isEmpty() || uploaded.isEmpty()
                || (host.equals(onHost) && !revision.equals(connectionRevision))) return null;
        String key = key(onHost, pane);
        Draft draft = drafts.getOrDefault(key, Draft.EMPTY);
        if (draft.attachments != null && !revision.equals(draft.revision())) return null;
        AttachmentPaths known = attachmentPaths(key, draft);
        if (known != null && !revision.equals(known.revision)) known = null;
        Set<String> directories = new LinkedHashSet<>();
        if (known != null) directories.addAll(known.directories);
        for (String path : uploaded) directories.add(path.substring(0, path.lastIndexOf('/') + 1));
        String paths = ImageAttach.quotePaths(uploaded);
        String text = joinAttachment(draft.text, paths);
        AttachmentPaths ownership = known != null && directories.equals(known.directories)
                ? known : new AttachmentPaths(revision, directories);
        updateDraft(key, text, ownership);
        if (key.equals(this.pane) && dictationBase != null) dictationBase = joinAttachment(dictationBase, paths);
        return text;
    }

    private static String joinAttachment(String before, String paths) {
        String gap = before.isEmpty() || Character.isWhitespace(before.charAt(before.length() - 1)) ? "" : " ";
        return before + gap + paths;
    }

    /**
     * The utterance ended without a final (an error, or cancelled by a mode or
     * pane change). The last partial stays in the draft: spoken words are never
     * thrown away, and the next utterance builds on them.
     */
    String dictationEnded() {
        settleCuts(true);
        dictationBase = null;
        endSpan();
        return text();
    }

    /**
     * The user cleared the utterance in progress (item 74): unlike {@link #dictationEnded}, its
     * words leave the draft, which is as it was before the dictation began.
     */
    String dictationCancelled() {
        // Only the dictated span goes, found by where it is, not by what it says: what was typed
        // before or after it stays, and a span whose words were edited (they are the user's now)
        // is left as it is.
        String text = text();
        if (spanStart >= 0 && !spanTainted && spanStart <= spanEnd && spanEnd <= text.length()) {
            // The dictated characters, and the space that joined them if it is still right there.
            int from = spanSeparator && spanStart > 0 && text.charAt(spanStart - 1) == ' ' ? spanStart - 1 : spanStart;
            store(text.substring(0, from) + text.substring(spanEnd));
        }
        settleCuts(false);
        dictationBase = null;
        endSpan();
        return text();
    }

    /**
     * What Enter sends: the draft without surrounding blank lines, or null when
     * there is nothing to send and Enter should reach the program as a key.
     * The draft is not cleared until {@link #sent} confirms it was queued.
     */
    String outgoing() {
        String text = text().replaceAll("^\\s+|\\s+$", "");
        return text.isEmpty() ? null : text;
    }

    void sent() {
        drafts.remove(pane);
        cutPaths.remove(pane);
        cutMarks.remove(pane);
        liveMarks = new java.util.ArrayList<>();
        dictationBase = null;
        endSpan();
    }

    /**
     * Where to draw the unverified-cut marks in the pane's draft: offsets in {@link #text}, in
     * order. Only marks whose text before them is unchanged, and the live utterance's.
     */
    int[] cuts() {
        String text = text();
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        java.util.List<long[]> marks = cutMarks.get(pane);
        if (marks != null) {
            for (long[] m : marks) if (holds(text, m)) out.add((int) m[0]);
        }
        if (dictationBase != null) {
            for (long[] m : liveMarks) if (holds(text, m)) out.add((int) m[0]);
        }
        int[] result = new int[out.size()];
        int i = 0;
        for (int at : out) result[i++] = at;
        return result;
    }

    /**
     * The words at {@code at} in {@code pane}'s draft changed from {@code before} to {@code after}
     * (a chip filled in): the marks after them move with their text instead of being dropped.
     */
    private void shiftMarks(String pane, String before, int at, String after) {
        shift(cutMarks.get(pane), before, at, after);
        // The utterance in progress is in the draft on screen.
        if (pane.equals(this.pane) && dictationBase != null) shift(liveMarks, before, at, after);
    }

    private static void shift(java.util.List<long[]> marks, String before, int at, String after) {
        if (marks == null || at < 0) return;
        int delta = after.length() - before.length();
        for (long[] m : marks) {
            int length = (int) m[0];
            if (length <= at || length >= before.length()) continue;
            if (before.substring(0, length).hashCode() != m[1]) continue;
            int moved = length + delta;
            if (moved <= at || moved >= after.length()) continue;
            m[0] = moved;
            m[1] = after.substring(0, moved).hashCode();
        }
    }

    /** The utterance in progress ends: its cuts become marks ({@code keep}), or go with its words. */
    private void settleCuts(boolean keep) {
        if (keep && dictationBase != null) {
            String text = text();
            // Only where the words before a cut are still the ones it was made in.
            for (long[] m : liveMarks) if (holds(text, m)) mark(pane, text, (int) m[0]);
        }
        liveMarks = new java.util.ArrayList<>();
    }

    /** Whether mark {@code m} ({length, hash}) still stands in {@code text}: the words before it unchanged. */
    private static boolean holds(String text, long[] m) {
        return m[0] > 0 && m[0] < text.length() && text.substring(0, (int) m[0]).hashCode() == m[1];
    }

    /** Marks for {@code offsets} in {@code text}, anchored by the words before each. */
    private static java.util.List<long[]> anchors(String text, int[] offsets) {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (int at : offsets) {
            if (at > 0 && at < text.length()) out.add(new long[] {at, text.substring(0, at).hashCode()});
        }
        return out;
    }

    private void mark(String pane, String text, int at) {
        if (at <= 0 || at >= text.length()) return;
        java.util.List<long[]> marks = cutMarks.computeIfAbsent(pane, k -> new java.util.ArrayList<>());
        long hash = text.substring(0, at).hashCode();
        for (long[] m : marks) if (m[0] == at && m[1] == hash) return;
        marks.add(new long[] {at, hash});
        while (marks.size() > MAX_CUTS) marks.remove(0);
        // Panes whose drafts are gone keep no marks.
        cutMarks.keySet().removeIf(p -> !p.equals(this.pane) && !drafts.containsKey(p));
    }

    /**
     * {@code cuts} (offsets in {@code utterance}) as offsets in {@code join(base, utterance)}:
     * past the space join puts between them, less the blanks it trims off the front. Cuts at
     * either end of the spoken words are dropped.
     */
    static int[] placed(String base, String utterance, int[] cuts) {
        if (cuts == null || cuts.length == 0 || utterance == null) return new int[0];
        String spoken = utterance.trim();
        if (spoken.isEmpty()) return new int[0];
        int lead = utterance.indexOf(spoken);
        String joined = join(base, utterance);
        int start = joined.length() - spoken.length();
        int[] out = new int[cuts.length];
        int n = 0;
        for (int cut : cuts) {
            int at = cut - lead;
            if (at > 0 && at < spoken.length()) out[n++] = start + at;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Each pane's draft, oldest first, for saving; at most {@link #MAX_PANES}. */
    String encode() {
        JSONObject out = new JSONObject();
        try {
            for (Map.Entry<String, Draft> entry : drafts.entrySet()) {
                Draft draft = entry.getValue();
                if (draft.text.length() <= MAX_SAVED_CHARS) out.put(entry.getKey(), draft.encode());
            }
            // Offered drafts apart, each piece a record of its own: a draft typed beside an offer
            // is never merged into it, so a Send of one never leaves its words in the other.
            for (Map.Entry<String, java.util.List<Draft>> entry : offered.entrySet()) {
                JSONArray pieces = new JSONArray();
                for (Draft piece : entry.getValue()) {
                    if (piece.text.length() <= MAX_SAVED_CHARS) pieces.put(piece.encode());
                }
                if (pieces.length() > 0) out.put(OFFERED + entry.getKey(), pieces);
            }
        } catch (JSONException impossible) {
            return "{}";
        }
        return out.toString();
    }

    /** Every draft as it is now, to {@link #rollback} a change that could not be saved. */
    Object snapshot() {
        Map<String, java.util.List<long[]>> marks = new java.util.HashMap<>();
        for (Map.Entry<String, java.util.List<long[]>> e : cutMarks.entrySet()) {
            java.util.List<long[]> copy = new java.util.ArrayList<>();
            for (long[] m : e.getValue()) copy.add(m.clone());
            marks.put(e.getKey(), copy);
        }
        java.util.List<long[]> live = new java.util.ArrayList<>();
        for (long[] m : liveMarks) live.add(m.clone());
        return new Object[] {new LinkedHashMap<>(drafts), dictationBase, marks, live, new LinkedHashMap<>(cutPaths),
            new int[] {spanStart, spanEnd, spanTainted ? 1 : 0, spanSeparator ? 1 : 0}, copyOffered(offered)};
    }

    @SuppressWarnings("unchecked")
    void rollback(Object snapshot) {
        Object[] s = (Object[]) snapshot;
        drafts.clear();
        drafts.putAll((Map<String, Draft>) s[0]);
        dictationBase = (String) s[1];
        int[] span = (int[]) s[5];
        spanStart = span[0];
        spanEnd = span[1];
        spanTainted = span[2] != 0;
        spanSeparator = span[3] != 0;
        // Copies: a snapshot rolled back to twice must not see the first rollback's changes.
        cutMarks.clear();
        for (Map.Entry<String, java.util.List<long[]>> e : ((Map<String, java.util.List<long[]>>) s[2]).entrySet()) {
            cutMarks.put(e.getKey(), copy(e.getValue()));
        }
        liveMarks = copy((java.util.List<long[]>) s[3]);
        cutPaths.clear();
        cutPaths.putAll((Map<String, AttachmentPaths>) s[4]);
        offered.clear();
        offered.putAll(copyOffered((Map<String, java.util.List<Draft>>) s[6]));
    }

    private static Map<String, java.util.List<Draft>> copyOffered(Map<String, java.util.List<Draft>> from) {
        Map<String, java.util.List<Draft>> out = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.List<Draft>> e : from.entrySet()) out.put(e.getKey(), new java.util.ArrayList<>(e.getValue()));
        return out;
    }

    private static java.util.List<long[]> copy(java.util.List<long[]> marks) {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (long[] m : marks) out.add(m.clone());
        return out;
    }

    /** Restores what {@link #encode} saved, under drafts already typed since; a damaged value restores nothing. */
    void restore(String saved) {
        restore(saved, false);
    }

    /**
     * As {@link #restore(String)} at the app's start: the drafts are offered, not loaded into the
     * composer (a restart may have changed what runs in the pane): see {@link #offered()}.
     */
    void restoreOffered(String saved) {
        restore(saved, true);
    }

    private void restore(String saved, boolean offer) {
        if (saved == null || saved.isEmpty()) return;
        Map<String, Draft> typed = new LinkedHashMap<>(drafts);
        try {
            JSONObject in = new JSONObject(saved);
            for (java.util.Iterator<String> keys = in.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (key.startsWith(OFFERED)) {
                    JSONArray pieces = in.optJSONArray(key);
                    String pane = key.substring(OFFERED.length());
                    for (int i = 0; pieces != null && i < pieces.length() && !pane.isEmpty(); i++) {
                        Draft piece = Draft.decode(pieces.opt(i));
                        if (!piece.text.isEmpty() && piece.text.length() <= MAX_SAVED_CHARS) offer(pane, piece);
                    }
                    continue;
                }
                Draft draft = Draft.decode(in.opt(key));
                if (key.isEmpty() || draft.text.length() > MAX_SAVED_CHARS) continue;
                if (draft.attachments != null) {
                    if (key.indexOf(SEP) < 0) continue;
                    if (onHost(key, host) && !draft.revision().equals(connectionRevision)) {
                        draft = draft.withoutAttachments();
                    }
                }
                if (draft.text.isEmpty()) continue;
                // A key with no computer is from an older build: adopted, as before.
                if (!offer || key.indexOf(SEP) < 0) drafts.put(key, draft);
                else if (!typed.containsKey(key)) offer(key, draft);
            }
        } catch (JSONException damaged) {
            return;
        }
        drafts.putAll(typed);
    }

    /** Adds a piece to {@code key}'s offer, kept in the order the pieces were typed. */
    private void offer(String key, Draft piece) {
        java.util.List<Draft> pieces = offered.computeIfAbsent(key, k -> new java.util.ArrayList<>());
        pieces.add(piece);
        pieces.sort((a, b) -> Long.compare(a.at, b.at));
    }

    static String join(String base, String utterance) {
        String spoken = utterance == null ? "" : utterance.trim();
        if (base == null || base.isEmpty()) return spoken;
        if (spoken.isEmpty()) return base;
        return Character.isWhitespace(base.charAt(base.length() - 1)) ? base + spoken : base + " " + spoken;
    }
}
