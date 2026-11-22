package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * An agent's question dialog as {@link ElicitationNative#parse} read it, and the answers the
 * user picked for it on the phone. Answers stay here until the user presses Submit; then
 * {@link #goal} hands them to the native step planner (factory-setup.33).
 */
final class Elicitation {
    enum Harness { CLAUDE, CODEX, GEMINI }
    enum Kind { QUESTION, CONFIRM, REVIEW }
    enum Role { ANSWER, CUSTOM, CHAT, NEXT, SUBMIT, CANCEL }
    enum Finish { SUBMIT, CHAT, CANCEL }

    static final class Choice {
        final int number;
        final String label;
        final String description;
        /** Null for a single-select row. */
        final Boolean checked;
        final Role role;

        Choice(int number, String label, String description, Boolean checked, Role role) {
            this.number = number;
            this.label = label;
            this.description = description;
            this.checked = checked;
            this.role = role;
        }
    }

    static final class Tab {
        final String label;
        final Boolean answered;
        final boolean submit;

        Tab(String label, Boolean answered, boolean submit) {
            this.label = label;
            this.answered = answered;
            this.submit = submit;
        }
    }

    /** One screen of the dialog: the tab on screen now. */
    static final class Dialog {
        final Harness harness;
        final Kind kind;
        final List<Tab> tabs;
        /** -1 when the screen does not show which tab is active. */
        final int activeTab;
        final String question;
        final List<String> detail;
        final boolean multi;
        final List<Choice> choices;
        final int cursor;
        /** Codex's open notes editor, else null. */
        final String note;

        Dialog(Harness harness, Kind kind, List<Tab> tabs, int activeTab, String question,
                List<String> detail, boolean multi, List<Choice> choices, int cursor, String note) {
            this.harness = harness;
            this.kind = kind;
            this.tabs = tabs;
            this.activeTab = activeTab;
            this.question = question;
            this.detail = detail;
            this.multi = multi;
            this.choices = choices;
            this.cursor = cursor;
            this.note = note;
        }

        /** The tab the question is on: 0 for a single question. */
        int tab() {
            return Math.max(activeTab, 0);
        }

        /** The question tabs, without Claude's Submit or Gemini's Review. */
        int questionCount() {
            int count = 0;
            for (Tab tab : tabs) if (!tab.submit) count++;
            return Math.max(count, 1);
        }

        boolean has(Role role) {
            for (Choice choice : choices) if (choice.role == role) return true;
            return false;
        }

        Choice custom() {
            for (Choice choice : choices) if (choice.role == Role.CUSTOM) return choice;
            return null;
        }

        /** What the agent shows as already typed in its custom row, or "" for its placeholder. */
        String typedCustom() {
            Choice custom = custom();
            if (custom == null) return "";
            String label = custom.label;
            boolean placeholder = label.isEmpty() || label.equals("Type something.")
                    || label.equals("Type something") || label.equals("Enter a custom value");
            return placeholder ? "" : label;
        }

        /** A fingerprint of what the sheet shows, to redraw only when it changes. */
        String identity() {
            StringBuilder out = new StringBuilder(harness.name()).append('|').append(kind.name())
                    .append('|').append(activeTab).append('|').append(question);
            for (Choice choice : choices) out.append('|').append(choice.number).append(choice.label);
            return out.toString();
        }
    }

    /** The user's answer to one tab's question. */
    static final class Answer {
        final int tab;
        final String question;
        final TreeSet<Integer> chosen = new TreeSet<>();
        String text = "";
        String note = "";
        boolean useCustom;

        Answer(int tab, String question) {
            this.tab = tab;
            this.question = question;
        }

        boolean isEmpty() {
            return chosen.isEmpty() && !(useCustom && !text.trim().isEmpty());
        }
    }

    /** Answers keyed by tab, in tab order. */
    private final Map<Integer, Answer> answers = new LinkedHashMap<>();

    /** Forgets every answer: the dialog they were for is answered. */
    void clear() {
        answers.clear();
    }

    /** The answer for the dialog's current tab, created empty the first time. */
    Answer answerFor(Dialog dialog) {
        int tab = dialog.tab();
        Answer answer = answers.get(tab);
        if (answer == null || !answer.question.equals(dialog.question)) {
            answer = new Answer(tab, dialog.question);
            // Start from what the agent already shows: checked boxes and typed text.
            for (Choice choice : dialog.choices) {
                if (choice.role == Role.ANSWER && Boolean.TRUE.equals(choice.checked)) {
                    answer.chosen.add(choice.number);
                }
            }
            String typed = dialog.typedCustom();
            if (!typed.isEmpty()) {
                answer.text = typed;
                answer.useCustom = true;
            }
            answers.put(tab, answer);
        }
        return answer;
    }

    /** Picks option {@code number}: replaces a single-select answer, toggles a multi-select box. */
    void choose(Dialog dialog, int number) {
        Answer answer = answerFor(dialog);
        if (dialog.multi) {
            if (!answer.chosen.remove(number)) answer.chosen.add(number);
            return;
        }
        answer.chosen.clear();
        answer.chosen.add(number);
        answer.useCustom = false;
    }

    /** Selects the custom row with {@code text}; single-select drops the picked option. */
    void custom(Dialog dialog, String text, boolean selected) {
        Answer answer = answerFor(dialog);
        answer.text = text;
        answer.useCustom = selected;
        if (selected && !dialog.multi) answer.chosen.clear();
    }

    void note(Dialog dialog, String note) {
        answerFor(dialog).note = note;
    }

    int answeredCount() {
        int count = 0;
        for (Answer answer : answers.values()) if (!answer.isEmpty()) count++;
        return count;
    }

    boolean answered(int tab) {
        Answer answer = answers.get(tab);
        return answer != null && !answer.isEmpty();
    }

    /**
     * Whether Submit may run. Codex submits on its last question's Enter, so every question
     * needs an answer; Claude and Gemini submit from their review tab with gaps allowed, but
     * not with none.
     */
    boolean canSubmit(Dialog dialog) {
        if (dialog.kind == Kind.REVIEW) return true;
        if (dialog.harness == Harness.CODEX) return answeredCount() >= dialog.questionCount();
        return answeredCount() > 0;
    }

    /** The goal for {@link ElicitationNative#step}: answered tabs in order, then the finish. */
    String goal(Finish finish) throws JSONException {
        JSONArray list = new JSONArray();
        if (finish == Finish.SUBMIT) {
            List<Integer> tabs = new ArrayList<>(answers.keySet());
            Collections.sort(tabs);
            for (int tab : tabs) {
                Answer answer = answers.get(tab);
                if (answer.isEmpty()) continue;
                JSONObject item = new JSONObject().put("tab", tab).put("question", answer.question);
                JSONArray chosen = new JSONArray();
                for (int number : answer.chosen) chosen.put(number);
                item.put("choose", chosen);
                if (answer.useCustom && !oneLine(answer.text).isEmpty()) item.put("text", oneLine(answer.text));
                if (!oneLine(answer.note).isEmpty()) item.put("note", oneLine(answer.note));
                list.put(item);
            }
        }
        return new JSONObject().put("answers", list)
                .put("finish", finish.name().toLowerCase(java.util.Locale.ROOT)).toString();
    }

    /**
     * The text as one line: the agent's answer row ends at Enter, so a pasted line break
     * would submit part of it. Breaks and tabs become single spaces.
     */
    static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    static Dialog parse(String json) throws JSONException {
        if (json == null) return null;
        JSONObject root = new JSONObject(json);
        List<Tab> tabs = new ArrayList<>();
        JSONArray rawTabs = root.optJSONArray("tabs");
        for (int i = 0; rawTabs != null && i < rawTabs.length(); i++) {
            JSONObject tab = rawTabs.getJSONObject(i);
            tabs.add(new Tab(tab.getString("label"),
                    tab.isNull("answered") ? null : tab.getBoolean("answered"), tab.getBoolean("submit")));
        }
        List<Choice> choices = new ArrayList<>();
        JSONArray rawChoices = root.getJSONArray("choices");
        for (int i = 0; i < rawChoices.length(); i++) {
            JSONObject choice = rawChoices.getJSONObject(i);
            choices.add(new Choice(choice.isNull("number") ? 0 : choice.getInt("number"),
                    choice.getString("label"), choice.optString("description", ""),
                    choice.isNull("checked") ? null : choice.getBoolean("checked"),
                    Role.valueOf(choice.getString("role").toUpperCase(java.util.Locale.ROOT))));
        }
        List<String> detail = new ArrayList<>();
        JSONArray rawDetail = root.optJSONArray("detail");
        for (int i = 0; rawDetail != null && i < rawDetail.length(); i++) detail.add(rawDetail.getString(i));
        return new Dialog(
                Harness.valueOf(root.getString("harness").toUpperCase(java.util.Locale.ROOT)),
                Kind.valueOf(root.getString("kind").toUpperCase(java.util.Locale.ROOT)),
                Collections.unmodifiableList(tabs),
                root.isNull("active_tab") ? -1 : root.getInt("active_tab"),
                root.getString("question"),
                Collections.unmodifiableList(detail),
                root.getBoolean("multi"),
                Collections.unmodifiableList(choices),
                root.getInt("cursor"),
                root.isNull("note") ? null : root.getString("note"));
    }

    /** One reply of {@link ElicitationNative#step}. */
    static final class Step {
        enum Type { KEYS, TEXT, WAIT, DONE, FAIL }

        final Type type;
        final List<String> keys;
        final String text;
        final String failure;
        final String progress;
        final Dialog dialog;

        private Step(Type type, List<String> keys, String text, String failure, String progress, Dialog dialog) {
            this.type = type;
            this.keys = keys;
            this.text = text;
            this.failure = failure;
            this.progress = progress;
            this.dialog = dialog;
        }

        static Step parse(String json) throws JSONException {
            JSONObject root = new JSONObject(json);
            String progress = root.getJSONObject("progress").toString();
            Dialog dialog = root.isNull("dialog") ? null : Elicitation.parse(root.getJSONObject("dialog").toString());
            Object step = root.get("step");
            if (step instanceof String) {
                Type type = "done".equals(step) ? Type.DONE : Type.WAIT;
                if (!"done".equals(step) && !"wait".equals(step)) throw new JSONException("step " + step);
                return new Step(type, Collections.emptyList(), null, null, progress, dialog);
            }
            JSONObject object = (JSONObject) step;
            if (object.has("keys")) {
                JSONArray raw = object.getJSONArray("keys");
                List<String> keys = new ArrayList<>();
                for (int i = 0; i < raw.length(); i++) keys.add(raw.getString(i));
                return new Step(Type.KEYS, Collections.unmodifiableList(keys), null, null, progress, dialog);
            }
            if (object.has("text")) return new Step(Type.TEXT, Collections.emptyList(), object.getString("text"), null, progress, dialog);
            return new Step(Type.FAIL, Collections.emptyList(), null, object.getString("fail"), progress, dialog);
        }
    }

    /** What the user reads when a submission stops part way. */
    static String failureText(String failure) {
        switch (failure == null ? "" : failure) {
            case "question_changed": return "The agent's dialog changed. Check it on the terminal.";
            case "no_such_option": return "That option is no longer in the agent's dialog.";
            case "unsupported": return "This agent's dialog cannot do that.";
            case "no_such_tab": return "That question is no longer in the agent's dialog.";
            case "too_many_steps": return "The agent did not respond as expected. Finish on the terminal.";
            case "timeout": return "The agent stopped redrawing. Finish on the terminal.";
            case "gone": return "The dialog closed before the answers were in. Check the terminal.";
            default: return "Stopped. Finish on the terminal.";
        }
    }
}
