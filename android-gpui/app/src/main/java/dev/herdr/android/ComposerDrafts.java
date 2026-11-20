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

        private Draft(String text, AttachmentPaths attachments) {
            this.text = text;
            this.attachments = attachments != null && attachments.pattern.matcher(text).find() ? attachments : null;
        }

        String revision() { return attachments == null ? null : attachments.revision; }

        private Draft withoutAttachments() {
            String remaining = attachments.pattern.matcher(text).replaceAll("");
            return remaining.trim().isEmpty() ? EMPTY : new Draft(remaining, null);
        }

        Object encode() throws JSONException {
            if (attachments == null) return text;
            return new JSONObject().put("text", text).put("revision", revision())
                    .put("staging", new JSONArray(attachments.directories));
        }

        static Draft decode(Object value) {
            if (value instanceof String) return new Draft((String) value, null);
            if (!(value instanceof JSONObject)) return EMPTY;
            JSONObject saved = (JSONObject) value;
            Object text = saved.opt("text");
            Object revision = saved.opt("revision");
            if (!(text instanceof String) || !(revision instanceof String)) return EMPTY;
            AttachmentPaths paths = AttachmentPaths.restore((String) revision, saved.optJSONArray("staging"));
            if (paths == null) return EMPTY;
            Draft draft = new Draft((String) text, paths);
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
    /** What the utterance in progress added after that base (its words and the space before them). */
    private String dictationTail;
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
        pane = target;
        return text();
    }

    String pane() { return rawPane(pane); }

    String owner() { return host.isEmpty() || rawPane(pane).isEmpty() ? "" : pane; }

    /** Every pane's draft (the dictation chips they hold must keep their recordings). */
    java.util.List<String> all() {
        java.util.List<String> texts = new java.util.ArrayList<>();
        for (Draft draft : drafts.values()) texts.add(draft.text);
        return texts;
    }

    Draft draft() { return drafts.getOrDefault(pane, Draft.EMPTY); }

    String text() { return draft().text; }

    String draftRevision() { return draft().revision(); }

    /** The field changed under the user's fingers. */
    void edit(String text) {
        updateDraft(pane, text == null ? "" : text, attachmentPaths(pane, draft()));
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
        Draft draft = new Draft(text, paths);
        if (paths != null && draft.attachments == null) cutPaths.put(key, paths);
        else cutPaths.remove(key);
        putDraft(key, draft);
    }

    private void putDraft(String key, Draft draft) {
        if (draft.text.isEmpty()) drafts.remove(key);
        else drafts.put(key, draft);
    }

    void dictationStarted() { dictationBase = text(); }

    boolean dictating() { return dictationBase != null; }

    /**
     * Where the utterance in progress starts in the draft (its words may still
     * change), or -1 when none is in progress.
     */
    int partialFrom() {
        if (dictationBase == null) return -1;
        String text = text();
        if (!text.startsWith(dictationBase) || text.length() == dictationBase.length()) return -1;
        int from = dictationBase.length();
        // Past the space join() put between the draft and the utterance.
        while (from < text.length() && text.charAt(from) == ' ') from++;
        return from;
    }

    /** The whole utterance so far; it replaces the previous partial. */
    String dictationPartial(String utterance) {
        return dictationPartial(utterance, null);
    }

    /** As {@link #dictationPartial(String)}, with the desktop's unverified cuts (offsets in {@code utterance}). */
    String dictationPartial(String utterance, int[] cuts) {
        if (dictationBase == null) dictationBase = text();
        edit(join(dictationBase, utterance));
        String now = text();
        dictationTail = now.length() >= dictationBase.length() ? now.substring(dictationBase.length()) : null;
        liveMarks = anchors(text(), placed(dictationBase, utterance, cuts));
        return text();
    }

    String dictationFinal(String utterance) {
        return dictationFinal(utterance, null);
    }

    String dictationFinal(String utterance, int[] cuts) {
        String result = dictationPartial(utterance, cuts);
        settleCuts(true);
        dictationBase = null;
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
                String inBase = DictationToken.fill(dictationBase, clipId, spoken);
                if (inBase != null) dictationBase = inBase;
            }
            if (filled.isEmpty()) drafts.remove(pane);
            else draft.setValue(new Draft(filled, draft.getValue().attachments));
            return rawPane(pane);
        }
        return null;
    }

    /** Adds {@code spoken} to the end of {@code pane}'s draft, e.g. a dictation whose token was deleted. */
    String append(String pane, String spoken) {
        return append(pane, spoken, null);
    }

    /** As {@link #append(String, String)}, with the desktop's unverified cuts (offsets in {@code spoken}). */
    String append(String pane, String spoken, int[] cuts) {
        String key = key(host, pane);
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
        dictationTail = null;
        return text();
    }

    /**
     * The user cleared the utterance in progress (item 74): unlike {@link #dictationEnded}, its
     * words leave the draft, which is as it was before the dictation began.
     */
    String dictationCancelled() {
        // Only the dictated words go: what was typed or edited meanwhile stays. When the words
        // themselves were edited (so they cannot be told apart) nothing is destroyed.
        String text = text();
        if (dictationBase != null && dictationTail != null && !dictationTail.isEmpty() && text.endsWith(dictationTail)) {
            edit(text.substring(0, text.length() - dictationTail.length()));
        }
        settleCuts(false);
        dictationBase = null;
        dictationTail = null;
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
        return new Object[] {new LinkedHashMap<>(drafts), dictationBase, marks, live, new LinkedHashMap<>(cutPaths)};
    }

    @SuppressWarnings("unchecked")
    void rollback(Object snapshot) {
        Object[] s = (Object[]) snapshot;
        drafts.clear();
        drafts.putAll((Map<String, Draft>) s[0]);
        dictationBase = (String) s[1];
        // Copies: a snapshot rolled back to twice must not see the first rollback's changes.
        cutMarks.clear();
        for (Map.Entry<String, java.util.List<long[]>> e : ((Map<String, java.util.List<long[]>>) s[2]).entrySet()) {
            cutMarks.put(e.getKey(), copy(e.getValue()));
        }
        liveMarks = copy((java.util.List<long[]>) s[3]);
        cutPaths.clear();
        cutPaths.putAll((Map<String, AttachmentPaths>) s[4]);
    }

    private static java.util.List<long[]> copy(java.util.List<long[]> marks) {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (long[] m : marks) out.add(m.clone());
        return out;
    }

    /** Restores what {@link #encode} saved, under drafts already typed since; a damaged value restores nothing. */
    void restore(String saved) {
        if (saved == null || saved.isEmpty()) return;
        Map<String, Draft> typed = new LinkedHashMap<>(drafts);
        try {
            JSONObject in = new JSONObject(saved);
            for (java.util.Iterator<String> keys = in.keys(); keys.hasNext(); ) {
                String key = keys.next();
                Draft draft = Draft.decode(in.opt(key));
                if (key.isEmpty() || draft.text.length() > MAX_SAVED_CHARS) continue;
                if (draft.attachments != null) {
                    if (key.indexOf(SEP) < 0) continue;
                    if (onHost(key, host) && !draft.revision().equals(connectionRevision)) {
                        draft = draft.withoutAttachments();
                    }
                }
                if (!draft.text.isEmpty()) drafts.put(key, draft);
            }
        } catch (JSONException damaged) {
            return;
        }
        drafts.putAll(typed);
    }

    static String join(String base, String utterance) {
        String spoken = utterance == null ? "" : utterance.trim();
        if (base == null || base.isEmpty()) return spoken;
        if (spoken.isEmpty()) return base;
        return Character.isWhitespace(base.charAt(base.length() - 1)) ? base + spoken : base + " " + spoken;
    }
}
