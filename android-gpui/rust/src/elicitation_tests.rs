//! Screens are the dialogs observed live (Claude Code 2.1.322, Codex 0.167.1)
//! and Gemini CLI 0.70.0's render snapshots, as listed in `ELICITATION.md`.
//! Paths are shortened; widths are the pane's.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use super::*;

/// Rows from text; `marks` emphasises `(row, from, to)` cells, as a background or underline would.
fn screen(text: &str, marks: &[(usize, usize, usize)]) -> Vec<Row> {
    text.lines()
        .enumerate()
        .map(|(index, line)| {
            let mut row = Row::plain(line);
            for &(at, from, to) in marks {
                if at == index {
                    row = Row::marked(line, from, to);
                }
            }
            row
        })
        .collect()
}

/// The cells of `needle` in row `row` of `text`, as `(row, from, to)`.
fn cells_of(text: &str, row: usize, needle: &str) -> (usize, usize, usize) {
    let line = text.lines().nth(row).expect("row");
    let byte = line.find(needle).expect("needle");
    let from = line[..byte].chars().count();
    (row, from, from + needle.chars().count())
}

fn labels(dialog: &Dialog) -> Vec<(&str, Role)> {
    dialog.choices.iter().map(|c| (c.label.as_str(), c.role)).collect()
}

const CLAUDE_Q1: &str = "\
● Which color?
────────────────────────────────────────────────────────────────────────
←  ☐ Color  ☐ Toppings  ☐ Size  ✔ Submit  →

Which color?

❯ 1. Red
     The color red
  2. Green
     The color green
  3. Blue
     The color blue
  4. Type something.
────────────────────────────────────────────────────────────────────────
  5. Chat about this

Enter to select · Tab/Arrow keys to navigate · Esc to cancel";

fn claude_q1() -> Vec<Row> {
    // Claude paints ` ☐ Color ` on a background.
    let (row, from, to) = cells_of(CLAUDE_Q1, 2, "☐ Color");
    screen(CLAUDE_Q1, &[(row, from - 1, to + 1)])
}

#[test]
fn claude_question_with_tabs() {
    let dialog = parse(&claude_q1(), PromptKind::Claude).expect("dialog");
    assert_eq!(dialog.harness, Harness::Claude);
    assert_eq!(dialog.kind, Kind::Question);
    assert_eq!(dialog.question, "Which color?");
    let tabs: Vec<_> = dialog.tabs.iter().map(|t| (t.label.as_str(), t.answered, t.submit)).collect();
    assert_eq!(
        tabs,
        [("Color", Some(false), false), ("Toppings", Some(false), false), ("Size", Some(false), false), ("Submit", None, true)]
    );
    assert_eq!(dialog.active_tab, Some(0));
    assert_eq!(
        labels(&dialog),
        [
            ("Red", Role::Answer),
            ("Green", Role::Answer),
            ("Blue", Role::Answer),
            ("Type something.", Role::Custom),
            ("Chat about this", Role::Chat)
        ]
    );
    assert_eq!(dialog.choices[1].description, "The color green");
    assert_eq!(dialog.choices[1].number, Some(2));
    assert_eq!(dialog.cursor, 0);
    assert!(!dialog.multi);
    assert!(dialog.detail.is_empty(), "{:?}", dialog.detail);
}

#[test]
fn the_active_tab_follows_the_highlight_not_the_question() {
    let (row, from, to) = cells_of(CLAUDE_Q1, 2, "☐ Size");
    let rows = screen(CLAUDE_Q1, &[(row, from - 1, to + 1)]);
    assert_eq!(parse(&rows, PromptKind::Claude).expect("dialog").active_tab, Some(2));
    // Painted up to the space before the next tab's mark: still the one tab.
    let (row, from, to) = cells_of(CLAUDE_Q1, 2, "☐ Toppings");
    let rows = screen(CLAUDE_Q1, &[(row, from - 1, to + 2)]);
    assert_eq!(parse(&rows, PromptKind::Claude).expect("dialog").active_tab, Some(1));
}

const CLAUDE_MULTI: &str = "\
←  ☐ Color  ☒ Toppings  ☐ Size  ✔ Submit  →

Which toppings?

  1. [✔] Cheese
         Cheese topping
  2. [ ] Ham
         Ham topping
  3. [ ] Olives
         Olives topping
❯ 4. [✔] pepper
     Next
────────────────────────────────────────────────────────────────────────
  5. Chat about this

-- INSERT -- · Enter to select · Tab/Arrow keys to navigate · ctrl+g to edit in Zeditor";

#[test]
fn claude_multi_select_with_typed_custom_answer() {
    let (row, from, to) = cells_of(CLAUDE_MULTI, 0, "☒ Toppings");
    let dialog = parse(&screen(CLAUDE_MULTI, &[(row, from - 1, to + 1)]), PromptKind::Claude).expect("dialog");
    assert!(dialog.multi);
    assert_eq!(dialog.active_tab, Some(1));
    assert_eq!(dialog.tabs[1].answered, Some(true));
    assert_eq!(
        labels(&dialog),
        [
            ("Cheese", Role::Answer),
            ("Ham", Role::Answer),
            ("Olives", Role::Answer),
            ("pepper", Role::Custom),
            ("Next", Role::Next),
            ("Chat about this", Role::Chat)
        ]
    );
    let checked: Vec<_> = dialog.choices.iter().map(|c| c.checked).collect();
    assert_eq!(checked, [Some(true), Some(false), Some(false), Some(true), None, None]);
    assert_eq!(dialog.choices[0].description, "Cheese topping");
    assert_eq!(dialog.cursor, 3);
}

#[test]
fn claude_cursor_on_next() {
    let text = CLAUDE_MULTI.replace("❯ 4. [✔] pepper\n     Next", "  4. [✔] pepper\n❯    Next");
    let dialog = parse(&screen(&text, &[]), PromptKind::Claude).expect("dialog");
    assert_eq!(dialog.choices[dialog.cursor].role, Role::Next);
    assert_eq!(dialog.active_tab, None, "no highlight drawn");
}

const CLAUDE_ONE: &str = "\
────────────────────────────────────────────────────────────────────────
 ☐ Pet

Which pet?

❯ 1. Cat
     A cat
  2. Dog
     A dog
  3. Fish
     A fish
  4. Type something.
────────────────────────────────────────────────────────────────────────
  5. Chat about this

Enter to select · ↑/↓ to navigate · Esc to cancel";

#[test]
fn claude_single_question() {
    let dialog = parse(&screen(CLAUDE_ONE, &[]), PromptKind::Claude).expect("dialog");
    assert_eq!(dialog.tabs.len(), 1);
    assert_eq!(dialog.tabs[0].label, "Pet");
    assert_eq!(dialog.question, "Which pet?");
    assert_eq!(dialog.choices.len(), 5);
}

const CLAUDE_REVIEW: &str = "\
────────────────────────────────────────────────────────────────────────
←  ☐ Color  ☒ Toppings  ☒ Size  ✔ Submit  →

Review your answers

⚠ You have not answered all questions

 ● Which toppings?
   → Cheese, pepper
 ● What size?
   → Large

Ready to submit your answers?

❯ 1. Submit answers
  2. Cancel";

#[test]
fn claude_review_tab() {
    // The review tab has no hint row: Claude draws none under Submit/Cancel.
    let text = format!("{CLAUDE_REVIEW}\n\nEnter to select · Tab/Arrow keys to navigate · Esc to cancel");
    let (row, from, to) = cells_of(&text, 1, "✔ Submit");
    let dialog = parse(&screen(&text, &[(row, from - 1, to + 1)]), PromptKind::Claude).expect("dialog");
    assert_eq!(dialog.kind, Kind::Review);
    assert_eq!(dialog.active_tab, Some(3));
    assert_eq!(labels(&dialog), [("Submit answers", Role::Submit), ("Cancel", Role::Cancel)]);
    assert_eq!(dialog.question, "Ready to submit your answers?");
    assert!(dialog.detail.contains(&"→ Cheese, pepper".to_owned()), "{:?}", dialog.detail);
}

#[test]
fn claude_review_without_a_hint_is_not_read() {
    assert_eq!(parse(&screen(CLAUDE_REVIEW, &[]), PromptKind::Claude), None);
}

const CLAUDE_PERMISSION: &str = "\
  Creating empty hello3.txt in the current directory
  ⎿  $ touch hello3.txt

────────────────────────────────────────────────────────────────────────
 Bash command
 Tip: auto mode handles these prompts for you — choose \"switch to auto mode\" below
 Create empty hello3.txt in the current directory
╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
 touch hello3.txt
╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
 Do you want to proceed?
 ❯ 1. Yes
   2. Yes, and always allow access to /tmp/probe from this project
   3. Yes, and switch to auto mode · auto mode handles these prompts for you
   4. No

 Esc to cancel · Tab to amend";

#[test]
fn claude_permission_prompt() {
    let dialog = parse(&screen(CLAUDE_PERMISSION, &[]), PromptKind::Claude).expect("dialog");
    assert_eq!(dialog.kind, Kind::Confirm);
    assert_eq!(dialog.question, "Do you want to proceed?");
    assert_eq!(dialog.choices.len(), 4);
    assert!(dialog.choices.iter().all(|c| c.role == Role::Answer));
    assert_eq!(
        dialog.detail,
        [
            "Bash command",
            "Tip: auto mode handles these prompts for you — choose \"switch to auto mode\" below",
            "Create empty hello3.txt in the current directory",
            "touch hello3.txt"
        ]
    );
}

const CODEX_Q1: &str = "\
  Question 1/2 (2 unanswered)
  Which color?

  › 1. Red                Choose red.
    2. Green              Choose green.
    3. Blue               Choose blue.
    4. None of the above  Optionally, add details in notes (tab)

  tab to add notes | enter to submit answer | ←/→ to navigate questions | esc to interrupt";

#[test]
fn codex_question() {
    let dialog = parse(&screen(CODEX_Q1, &[]), PromptKind::Codex).expect("dialog");
    assert_eq!(dialog.harness, Harness::Codex);
    assert_eq!(dialog.kind, Kind::Question);
    assert_eq!(dialog.tabs.len(), 2);
    assert_eq!(dialog.active_tab, Some(0));
    assert_eq!(dialog.question, "Which color?");
    assert_eq!(dialog.choices[0].label, "Red");
    assert_eq!(dialog.choices[0].description, "Choose red.");
    assert_eq!(dialog.choices[3].label, "None of the above");
    assert_eq!(dialog.note, None);
}

const CODEX_NOTES: &str = "\
  Question 1/2 (2 unanswered)
  Which color?

    1. Red                Choose red.
  › 2. Green              Choose green.
    3. Blue               Choose blue.
    4. None of the above  Optionally, add details in notes (tab)

  › light

  tab or esc to clear notes | enter to submit answer";

#[test]
fn codex_notes_editor() {
    let dialog = parse(&screen(CODEX_NOTES, &[]), PromptKind::Codex).expect("dialog");
    assert_eq!(dialog.cursor, 1);
    assert_eq!(dialog.note.as_deref(), Some("light"));
    let empty = CODEX_NOTES.replace("› light", "› Add notes");
    assert_eq!(parse(&screen(&empty, &[]), PromptKind::Codex).expect("dialog").note.as_deref(), Some(""));
}

const CODEX_APPROVAL: &str = "\
• Running touch hello.txt

  Would you like to run the following command?

  Environment: local

  Reason: Allow creating hello.txt in the current directory?

  $ touch hello.txt

› 1. Yes, proceed (y)
  2. Yes, and don't ask again for commands that start with `touch hello.txt` (p)
  3. No, and tell Codex what to do differently (esc)

  Press enter to confirm or esc to cancel";

#[test]
fn codex_approval() {
    let dialog = parse(&screen(CODEX_APPROVAL, &[]), PromptKind::Codex).expect("dialog");
    assert_eq!(dialog.kind, Kind::Confirm);
    assert_eq!(dialog.question, "Would you like to run the following command?");
    assert_eq!(
        dialog.detail,
        ["Environment: local", "Reason: Allow creating hello.txt in the current directory?", "$ touch hello.txt"]
    );
    assert_eq!(dialog.choices[0].label, "Yes, proceed (y)");
}

const GEMINI_TABS: &str = "\
← □ Database │ □ ORM │ ≡ Review →

Which database should we use?

● 1.  PostgreSQL
      Relational database
  2.  MongoDB
      Document database
  3.  Enter a custom value

Enter to select · ←/→ to switch questions · Esc to cancel";

#[test]
fn gemini_question_with_tabs() {
    // Gemini underlines and bolds the active tab's label.
    let rows = screen(GEMINI_TABS, &[cells_of(GEMINI_TABS, 0, "Database")]);
    let dialog = parse(&rows, PromptKind::Other).expect("dialog");
    assert_eq!(dialog.harness, Harness::Gemini);
    assert_eq!(dialog.kind, Kind::Question);
    let tabs: Vec<_> = dialog.tabs.iter().map(|t| (t.label.as_str(), t.answered, t.submit)).collect();
    assert_eq!(tabs, [("Database", Some(false), false), ("ORM", Some(false), false), ("Review", None, true)]);
    assert_eq!(dialog.active_tab, Some(0));
    assert_eq!(
        labels(&dialog),
        [("PostgreSQL", Role::Answer), ("MongoDB", Role::Answer), ("Enter a custom value", Role::Custom)]
    );
    assert_eq!(dialog.choices[0].description, "Relational database");
    // A pane the host knows as Claude or Codex never shows Gemini's dialog.
    assert_eq!(parse(&rows, PromptKind::Claude), None);
    // A pane the host knows as Gemini (agent.kind) shows it, and never Claude's.
    assert_eq!(parse(&rows, PromptKind::Gemini).map(|d| d.harness), Some(Harness::Gemini));
    assert_eq!(parse(&screen(CLAUDE_Q1, &[]), PromptKind::Gemini), None);
    assert_eq!(prompt_kind("gemini").ok(), Some(PromptKind::Gemini));
}

const GEMINI_MULTI: &str = "\
Which features?
(Select all that apply)

  1. [x] TypeScript
  2. [x] ESLint
● 3. [x] All of the above
         Select all options
  4. [ ] Enter a custom value
   Done
   Finish selection

Enter to select · ↑/↓ to navigate · Esc to cancel";

#[test]
fn gemini_multi_select() {
    let dialog = parse(&screen(GEMINI_MULTI, &[]), PromptKind::Other).expect("dialog");
    assert!(dialog.multi);
    assert_eq!(dialog.question, "Which features?");
    assert_eq!(
        labels(&dialog),
        [
            ("TypeScript", Role::Answer),
            ("ESLint", Role::Answer),
            ("All of the above", Role::Answer),
            ("Enter a custom value", Role::Custom),
            ("Done", Role::Next)
        ]
    );
    assert_eq!(dialog.choices[2].description, "Select all options");
    assert_eq!(dialog.choices[4].description, "Finish selection");
    assert_eq!(dialog.cursor, 2);
}

const GEMINI_CONFIRM: &str = "\
Allow execution of [Shell]?

● 1. Allow once
  2. Allow for this session
  3. No, suggest changes (esc)

Enter to select · ↑/↓ to navigate · Esc to cancel";

#[test]
fn gemini_tool_confirmation() {
    let dialog = parse(&screen(GEMINI_CONFIRM, &[]), PromptKind::Other).expect("dialog");
    assert_eq!(dialog.kind, Kind::Confirm);
    assert_eq!(dialog.question, "Allow execution of [Shell]?");
    assert!(dialog.choices.iter().all(|c| c.role == Role::Answer));
}

const GEMINI_TRUST: &str = "\
 ╭──────────────────────────────────────────────────────────────╮
 │                                                              │
 │ Do you trust the files in this folder?                       │
 │                                                              │
 │ Trusting a folder allows Gemini CLI to load its local        │
 │                                                              │
 │                                                              │
 │ ● 1. Trust folder (gemini-probe)                             │
 │   2. Trust parent folder (scratchpad)                        │
 │   3. Don't trust                                             │
 │                                                              │
 │   (Use Enter to select)                                      │
 ╰──────────────────────────────────────────────────────────────╯";

#[test]
fn gemini_boxed_dialog() {
    let dialog = parse(&screen(GEMINI_TRUST, &[]), PromptKind::Other).expect("dialog");
    assert_eq!(dialog.question, "Do you trust the files in this folder?");
    assert_eq!(dialog.kind, Kind::Confirm);
    assert_eq!(dialog.choices[2].label, "Don't trust");
    assert_eq!(dialog.choices[2].role, Role::Answer, "a trust prompt has no free-text row");
    assert_eq!(dialog.detail, ["Trusting a folder allows Gemini CLI to load its local"]);
}

const GEMINI_REVIEW: &str = "\
← □ License │ □ README │ ≡ Review →

Review your answers:

⚠ You have 2 unanswered questions

License → (not answered)
README → (not answered)

Enter to submit · Tab/Shift+Tab to edit answers · Esc to cancel";

#[test]
fn gemini_review_tab() {
    let rows = screen(GEMINI_REVIEW, &[cells_of(GEMINI_REVIEW, 0, "Review")]);
    let dialog = parse(&rows, PromptKind::Other).expect("dialog");
    assert_eq!(dialog.kind, Kind::Review);
    assert!(dialog.choices.is_empty());
    assert_eq!(dialog.active_tab, Some(2));
    assert!(dialog.detail.contains(&"License → (not answered)".to_owned()));
    // On another tab it is not the review.
    let rows = screen(GEMINI_REVIEW, &[cells_of(GEMINI_REVIEW, 0, "License")]);
    assert_eq!(parse(&rows, PromptKind::Other), None);
}

#[test]
fn agent_output_is_not_a_dialog() {
    // Numbered lists in a reply have no cursor.
    let reply = "\
● Here is the plan:
  1. Read the file
  2. Fix the bug

Enter to select · Esc to cancel";
    assert_eq!(parse(&screen(reply, &[]), PromptKind::Claude), None);
    // Codex printing a question as text (outside Plan mode).
    let printed = "\
? Which color?
  • Red
  • Green
  Worked for 5s • 9:30 PM
› Ask Codex to do anything";
    assert_eq!(parse(&screen(printed, &[]), PromptKind::Codex), None);
    // A user message that is a list, above the working spinner.
    let typed = "\
❯ 1. do this
  2. then that

✻ Working… (esc to interrupt)";
    assert_eq!(parse(&screen(typed, &[]), PromptKind::Claude), None);
    // A hint far below a list is not that list's.
    let far = format!("❯ 1. Red\n  2. Green\n{}Esc to cancel", "text\n".repeat(6));
    assert_eq!(parse(&screen(&far, &[]), PromptKind::Claude), None);
}

#[test]
fn a_list_scrolled_past_option_one_is_not_read() {
    let scrolled = CLAUDE_ONE.replace("❯ 1. Cat\n     A cat\n", "").replace("  2. Dog", "❯ 2. Dog");
    assert_eq!(parse(&screen(&scrolled, &[]), PromptKind::Claude), None);
}

#[test]
fn only_the_bottom_rows_are_read() {
    let mut rows = claude_q1();
    rows.extend((0..SCAN_ROWS).map(|_| Row::plain("")));
    assert_eq!(parse(&rows, PromptKind::Claude), None);
}

// ---- Executing answers against simulated agents ----

/// A Claude AskUserQuestion as the live dialog behaves (inventory): Enter on a
/// single-select answers and moves on; digits are not used; multi-select toggles
/// with Enter and ends at Next; typing on the custom row replaces its label and
/// checks it; with one question, answering submits.
struct FakeClaude {
    questions: Vec<(&'static str, Vec<&'static str>, bool)>,
    tab: usize,
    cursor: usize,
    picked: Vec<Vec<usize>>,
    custom: Vec<String>,
    submitted: Option<Vec<Vec<String>>>,
    chat: bool,
    cancelled: bool,
}

impl FakeClaude {
    fn new(questions: Vec<(&'static str, Vec<&'static str>, bool)>) -> Self {
        let n = questions.len();
        Self { questions, tab: 0, cursor: 0, picked: vec![Vec::new(); n], custom: vec![String::new(); n], submitted: None, chat: false, cancelled: false }
    }

    fn open(&self) -> bool {
        self.submitted.is_none() && !self.chat && !self.cancelled
    }

    fn on_submit_tab(&self) -> bool {
        self.questions.len() > 1 && self.tab == self.questions.len()
    }

    /// Rows in this question: options, custom, (Next), chat.
    fn rows(&self) -> usize {
        if self.on_submit_tab() {
            return 2;
        }
        let (_, options, multi) = &self.questions[self.tab];
        options.len() + 2 + usize::from(*multi)
    }

    fn render(&self) -> Vec<Row> {
        if !self.open() {
            return screen("❯ \n  -- INSERT -- ⏵⏵ bypass permissions on", &[]);
        }
        let mut lines: Vec<String> = vec!["─".repeat(40)];
        let mut header = String::from(if self.questions.len() > 1 { "← " } else { "" });
        let mut mark = (0, 0);
        for (i, (_, _, _)) in self.questions.iter().enumerate() {
            let start = header.chars().count();
            let box_ = if self.picked[i].is_empty() && self.custom[i].is_empty() { '☐' } else { '☒' };
            header.push_str(&format!(" {box_} Q{i} "));
            if i == self.tab {
                mark = (start, header.chars().count());
            }
        }
        if self.questions.len() > 1 {
            let start = header.chars().count();
            header.push_str(" ✔ Submit ");
            if self.on_submit_tab() {
                mark = (start, header.chars().count());
            }
            header.push_str(" →");
        }
        lines.push(header);
        lines.push(String::new());
        let cursor = |i: usize| if i == self.cursor { "❯" } else { " " };
        if self.on_submit_tab() {
            lines.push("Ready to submit your answers?".into());
            lines.push(String::new());
            lines.push(format!("{} 1. Submit answers", cursor(0)));
            lines.push(format!("{} 2. Cancel", cursor(1)));
        } else {
            let (question, options, multi) = &self.questions[self.tab];
            lines.push((*question).into());
            lines.push(String::new());
            for (i, option) in options.iter().enumerate() {
                let label = if *multi {
                    format!("[{}] {option}", if self.picked[self.tab].contains(&i) { "✔" } else { " " })
                } else {
                    (*option).to_owned()
                };
                lines.push(format!("{} {}. {label}", cursor(i), i + 1));
                lines.push(format!("     About {option}"));
            }
            let c = options.len();
            let typed = &self.custom[self.tab];
            let custom = if typed.is_empty() { "Type something.".to_owned() } else { typed.clone() };
            let custom = if *multi { format!("[{}] {custom}", if typed.is_empty() { " " } else { "✔" }) } else { custom };
            lines.push(format!("{} {}. {custom}", cursor(c), c + 1));
            if *multi {
                lines.push(format!("{}    Next", cursor(c + 1)));
            }
            lines.push("─".repeat(40));
            let chat = c + 1 + usize::from(*multi);
            lines.push(format!("{} {}. Chat about this", cursor(chat), c + 2));
        }
        lines.push(String::new());
        lines.push("Enter to select · Tab/Arrow keys to navigate · Esc to cancel".into());
        let text = lines.join("\n");
        screen(&text, &[(1, mark.0, mark.1)])
    }

    fn key(&mut self, key: &str) {
        let n = self.questions.len();
        match key {
            "up" => self.cursor = self.cursor.saturating_sub(1),
            "down" => self.cursor = (self.cursor + 1).min(self.rows() - 1),
            "right" if n > 1 => {
                self.tab = (self.tab + 1).min(n);
                self.cursor = 0;
            }
            "left" if n > 1 => {
                self.tab = self.tab.saturating_sub(1);
                self.cursor = 0;
            }
            "esc" => self.cancelled = true,
            "backspace" => {
                if self.custom_row() {
                    self.custom[self.tab].pop();
                }
            }
            "enter" => self.enter(),
            other => panic!("unexpected key {other}"),
        }
    }

    fn custom_row(&self) -> bool {
        !self.on_submit_tab() && self.cursor == self.questions[self.tab].1.len()
    }

    fn enter(&mut self) {
        if self.on_submit_tab() {
            if self.cursor == 0 {
                self.submit();
            } else {
                self.cancelled = true;
            }
            return;
        }
        let (_, options, multi) = self.questions[self.tab].clone();
        let c = options.len();
        let chat = c + 1 + usize::from(multi);
        if self.cursor == chat {
            self.chat = true;
        } else if multi && self.cursor < c {
            let picked = &mut self.picked[self.tab];
            match picked.iter().position(|&p| p == self.cursor) {
                Some(at) => {
                    picked.remove(at);
                }
                None => picked.push(self.cursor),
            }
        } else if multi && self.cursor == c {
            // Enter on a typed custom row toggles it off (seen live).
            self.custom[self.tab].clear();
        } else {
            if !multi && self.cursor < c {
                self.picked[self.tab] = vec![self.cursor];
                self.custom[self.tab].clear();
            }
            self.advance();
        }
    }

    fn advance(&mut self) {
        if self.questions.len() == 1 {
            self.submit();
        } else {
            self.tab += 1;
            self.cursor = 0;
        }
    }

    fn submit(&mut self) {
        let answers = (0..self.questions.len())
            .map(|q| {
                let mut out: Vec<String> = self.picked[q].iter().map(|&i| self.questions[q].1[i].to_owned()).collect();
                out.sort();
                if !self.custom[q].is_empty() {
                    out.push(self.custom[q].clone());
                }
                out
            })
            .collect();
        self.submitted = Some(answers);
    }

    fn text(&mut self, text: &str) {
        assert!(self.custom_row(), "typed off the custom row");
        if !self.questions[self.tab].2 {
            self.picked[self.tab].clear();
        }
        self.custom[self.tab].push_str(text);
    }
}

/// Runs a goal against a fake to the end; returns the steps taken.
fn run(fake: &mut FakeClaude, goal: &Goal) -> Vec<Step> {
    let mut progress = Progress::default();
    let mut steps = Vec::new();
    loop {
        let dialog = parse(&fake.render(), PromptKind::Claude);
        let step = step(dialog.as_ref(), goal, &mut progress);
        steps.push(step.clone());
        match step {
            Step::Keys(keys) => {
                assert!(keys.len() <= KEY_BATCH && !keys.is_empty());
                for key in keys {
                    fake.key(key);
                }
            }
            Step::Text(text) => fake.text(&text),
            Step::Done | Step::Fail(_) => return steps,
            Step::Wait => panic!("waiting on a static screen: {steps:?}"),
        }
        assert!(steps.len() < 100, "{steps:?}");
    }
}

fn three_questions() -> FakeClaude {
    FakeClaude::new(vec![
        ("Which color?", vec!["Red", "Green", "Blue"], false),
        ("Which toppings?", vec!["Cheese", "Ham", "Olives"], true),
        ("What size?", vec!["Small", "Large"], false),
    ])
}

fn answer(tab: usize, question: &str, choose: &[u8], text: Option<&str>) -> Answer {
    Answer { tab, question: question.into(), choose: choose.to_vec(), text: text.map(str::to_owned), note: None }
}

#[test]
fn claude_answers_every_tab_then_submits() {
    let mut fake = three_questions();
    let goal = Goal {
        answers: vec![
            answer(0, "Which color?", &[2], None),
            answer(1, "Which toppings?", &[1, 3], Some("pepper")),
            answer(2, "What size?", &[2], None),
        ],
        finish: Finish::Submit,
    };
    let steps = run(&mut fake, &goal);
    assert_eq!(steps.last(), Some(&Step::Done));
    assert_eq!(
        fake.submitted,
        Some(vec![vec!["Green".to_owned()], vec!["Cheese".into(), "Olives".into(), "pepper".into()], vec!["Large".into()]])
    );
}

#[test]
fn claude_answers_out_of_order_and_retypes_custom_text() {
    let mut fake = three_questions();
    fake.custom[0] = "teal".into();
    fake.tab = 2;
    let goal = Goal {
        answers: vec![answer(0, "Which color?", &[], Some("navy")), answer(2, "What size?", &[1], None)],
        finish: Finish::Submit,
    };
    let steps = run(&mut fake, &goal);
    assert_eq!(steps.last(), Some(&Step::Done), "{steps:?}");
    let submitted = fake.submitted.expect("submitted");
    assert_eq!(submitted[0], ["navy"]);
    assert_eq!(submitted[2], ["Small"]);
}

#[test]
fn claude_single_question_submits_on_its_answer() {
    let mut fake = FakeClaude::new(vec![("Which pet?", vec!["Cat", "Dog", "Fish"], false)]);
    let goal = Goal { answers: vec![answer(0, "Which pet?", &[3], None)], finish: Finish::Submit };
    let steps = run(&mut fake, &goal);
    assert_eq!(steps, [Step::Keys(vec!["down", "down"]), Step::Keys(vec!["enter"]), Step::Done]);
    assert_eq!(fake.submitted, Some(vec![vec!["Fish".to_owned()]]));
}

#[test]
fn claude_chat_and_cancel() {
    let mut fake = three_questions();
    let steps = run(&mut fake, &Goal { answers: Vec::new(), finish: Finish::Chat });
    assert_eq!(steps.last(), Some(&Step::Done));
    assert!(fake.chat && fake.submitted.is_none());
    let mut fake = three_questions();
    run(&mut fake, &Goal { answers: Vec::new(), finish: Finish::Cancel });
    assert!(fake.cancelled && fake.submitted.is_none());
}

#[test]
fn a_different_question_on_screen_stops_the_submission() {
    let mut fake = three_questions();
    let goal = Goal { answers: vec![answer(0, "Which colour?", &[1], None)], finish: Finish::Submit };
    let steps = run(&mut fake, &goal);
    assert_eq!(steps, [Step::Fail(Failure::QuestionChanged)]);
    assert!(fake.submitted.is_none());
    // An option the dialog does not have.
    let goal = Goal { answers: vec![answer(0, "Which color?", &[9], None)], finish: Finish::Submit };
    assert_eq!(run(&mut three_questions(), &goal), [Step::Fail(Failure::NoSuchOption)]);
    // Picking the Chat row as an answer is refused too.
    let goal = Goal { answers: vec![answer(0, "Which color?", &[5], None)], finish: Finish::Submit };
    assert_eq!(run(&mut three_questions(), &goal), [Step::Fail(Failure::NoSuchOption)]);
}

#[test]
fn steps_wait_through_a_redraw_and_end_when_the_dialog_closes() {
    let goal = Goal { answers: vec![answer(0, "Which pet?", &[1], None)], finish: Finish::Submit };
    let mut progress = Progress::default();
    assert_eq!(step(None, &goal, &mut progress), Step::Wait);
    progress.finished = true;
    assert_eq!(step(None, &goal, &mut progress), Step::Done);
    let mut progress = Progress { steps: MAX_STEPS, ..Progress::default() };
    assert_eq!(step(None, &goal, &mut progress), Step::Fail(Failure::TooManySteps));
}

#[test]
fn codex_answer_with_notes_and_submit_on_the_last() {
    let dialog = parse(&screen(CODEX_Q1, &[]), PromptKind::Codex).expect("dialog");
    let goal = Goal {
        answers: vec![Answer { tab: 0, question: "Which color?".into(), choose: vec![2], text: None, note: Some("light".into()) }],
        finish: Finish::Submit,
    };
    let mut progress = Progress::default();
    assert_eq!(step(Some(&dialog), &goal, &mut progress), Step::Keys(vec!["down"]));
    let moved = parse(&screen(&CODEX_NOTES.replace("  › light\n", "").replace("tab or esc to clear notes", "tab to add notes"), &[]), PromptKind::Codex).expect("dialog");
    assert_eq!(moved.note, None);
    assert_eq!(step(Some(&moved), &goal, &mut progress), Step::Keys(vec!["tab"]));
    let open = parse(&screen(&CODEX_NOTES.replace("› light", "› Add notes"), &[]), PromptKind::Codex).expect("dialog");
    assert_eq!(step(Some(&open), &goal, &mut progress), Step::Text("light".into()));
    let typed = parse(&screen(CODEX_NOTES, &[]), PromptKind::Codex).expect("dialog");
    assert_eq!(step(Some(&typed), &goal, &mut progress), Step::Keys(vec!["enter"]));
    // Codex has no review tab, so that Enter (the only answer here) was the submission.
    assert!(progress.finished);
    assert_eq!(step(None, &goal, &mut progress), Step::Done);
}

#[test]
fn notes_are_codex_only_and_chat_is_claude_only() {
    let claude = parse(&claude_q1(), PromptKind::Claude).expect("dialog");
    let goal = Goal {
        answers: vec![Answer { tab: 0, question: "Which color?".into(), choose: vec![1], text: None, note: Some("x".into()) }],
        finish: Finish::Submit,
    };
    assert_eq!(step(Some(&claude), &goal, &mut Progress::default()), Step::Fail(Failure::Unsupported));
    let codex = parse(&screen(CODEX_Q1, &[]), PromptKind::Codex).expect("dialog");
    let chat = Goal { answers: Vec::new(), finish: Finish::Chat };
    assert_eq!(step(Some(&codex), &chat, &mut Progress::default()), Step::Fail(Failure::Unsupported));
}

#[test]
fn gemini_all_of_the_above_is_left_alone_unless_picked() {
    // All three boxes are checked because both options are; the user picked both.
    let dialog = parse(&screen(GEMINI_MULTI, &[]), PromptKind::Other).expect("dialog");
    let goal = Goal { answers: vec![answer(0, "Which features?", &[1, 2], None)], finish: Finish::Submit };
    let mut progress = Progress::default();
    // Straight to Done (two rows down), not unchecking All of the above.
    assert_eq!(step(Some(&dialog), &goal, &mut progress), Step::Keys(vec!["down", "down"]));
}

#[test]
fn moves_and_erasing_come_in_bounded_batches() {
    assert_eq!(moves(0, 40).len(), KEY_BATCH);
    assert_eq!(tab_moves(30, 0).len(), KEY_BATCH);
    let mut progress = Progress::default();
    let long = "x".repeat(20);
    assert_eq!(erase(&long, &mut progress), Some(Step::Keys(vec!["backspace"; 16])));
    assert_eq!(erase("xxxx", &mut progress), Some(Step::Keys(vec!["backspace"; 4])));
    assert_eq!(erase("", &mut progress), None);
}

#[test]
fn goals_and_progress_cross_the_boundary_as_json() {
    let goal: Goal = serde_json::from_str(
        r#"{"answers":[{"tab":1,"question":"Which toppings?","choose":[1,3],"text":"pepper"}],"finish":"submit"}"#,
    )
    .expect("goal");
    assert_eq!(goal.answers[0].choose, [1, 3]);
    assert!(serde_json::from_str::<Goal>(r#"{"answers":[],"finish":"submit","auto":true}"#).is_err());
    let progress = Progress { next: 2, typed: true, ..Progress::default() };
    let json = serde_json::to_string(&progress).expect("json");
    assert_eq!(serde_json::from_str::<Progress>(&json).expect("progress"), progress);
    let dialog = parse(&claude_q1(), PromptKind::Claude).expect("dialog");
    let json = serde_json::to_value(&dialog).expect("json");
    assert_eq!(json["harness"], "claude");
    assert_eq!(json["choices"][3]["role"], "custom");
    assert_eq!(serde_json::to_value(Step::Keys(vec!["down"])).expect("json"), serde_json::json!({"keys": ["down"]}));
    assert_eq!(serde_json::to_value(Step::Done).expect("json"), serde_json::json!("done"));
}

#[test]
fn the_live_grid_marks_claudes_active_tab() {
    // Claude Code's own bytes for its tab row and list (from a `script` capture).
    let bytes = "\x1b[2J\x1b[H← \x1b[48;2;177;185;249m\x1b[38;2;0;0;0m ☐ Color \x1b[39m\x1b[49m ☐ Size  ✔ Submit  →\r\n\r\n\
\x1b[1mWhich color do you prefer?\x1b[22m\r\n\r\n\
\x1b[38;2;177;185;249m❯\x1b[39m 1. Red\r\n     Choose red.\r\n  2. Green\r\n     Choose green.\r\n  3. Type something.\r\n\
────────────────────\r\n  4. Chat about this\r\n\r\nEnter to select · Tab/Arrow keys to navigate · Esc to cancel";
    let session = gpui_rio::Session::start(
        std::io::Cursor::new(bytes.as_bytes().to_vec()),
        std::io::sink(),
        |_, _| {},
        crate::shell::theme(&crate::model::palette()),
        80,
        16,
    )
    .expect("session");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while session.is_alive() {
        assert!(std::time::Instant::now() < deadline, "the bytes were never parsed");
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    let rows = screen_rows(&session.grid().lock()).expect("rows");
    assert_eq!(rows.len(), 16);
    let dialog = parse(&rows, PromptKind::Claude).expect("dialog");
    assert_eq!(dialog.active_tab, Some(0));
    assert_eq!(dialog.question, "Which color do you prefer?");
    assert_eq!(labels(&dialog)[2], ("Type something.", Role::Custom));
}

#[test]
fn the_next_step_waits_for_the_agent_to_redraw() {
    let rows = claude_q1();
    let goal = Goal { answers: vec![answer(0, "Which color?", &[2], None)], finish: Finish::Submit };
    let mut progress = Progress::default();
    let (first, dialog) = step_after_redraw(Some(&rows), PromptKind::Claude, &goal, &mut progress);
    assert_eq!(first, Step::Keys(vec!["down"]));
    assert!(dialog.is_some());
    // The same frame again: the key has not shown yet, so nothing more is sent.
    for _ in 0..MAX_WAIT_STEPS {
        assert_eq!(step_after_redraw(Some(&rows), PromptKind::Claude, &goal, &mut progress).0, Step::Wait);
    }
    // Long enough: the key changed nothing, so the screen is read as it is.
    assert_eq!(step_after_redraw(Some(&rows), PromptKind::Claude, &goal, &mut progress).0, Step::Keys(vec!["down"]));
    // A redraw is read at once.
    let moved = CLAUDE_Q1.replace("❯ 1. Red", "  1. Red").replace("  2. Green", "❯ 2. Green");
    let (row, from, to) = cells_of(&moved, 2, "☐ Color");
    let moved = screen(&moved, &[(row, from - 1, to + 1)]);
    assert_eq!(step_after_redraw(Some(&moved), PromptKind::Claude, &goal, &mut progress).0, Step::Keys(vec!["enter"]));
    let json = step_json(Some(&moved), PromptKind::Claude, r#"{"answers":[{"question":"Which color?","choose":[2]}]}"#, "").expect("step");
    let reply: serde_json::Value = serde_json::from_str(&json).expect("json");
    assert_eq!(reply["step"], serde_json::json!({"keys": ["enter"]}));
    assert_eq!(reply["progress"]["next"], 1);
    assert_eq!(reply["dialog"]["question"], "Which color?");
    assert!(step_json(None, PromptKind::Claude, "{}", "{\"bogus\":1}").is_err());
    assert!(prompt_kind("vim").is_err());
}

#[test]
fn fingerprints_survive_the_hosts_json_numbers() {
    // Android's org.json turns integers past i64 into doubles; past 2^53 they round.
    for rows in [claude_q1(), screen(CODEX_Q1, &[]), screen(GEMINI_TABS, &[]), Vec::new()] {
        let print = fingerprint(&rows);
        assert!(print < 1 << 53);
        assert_eq!(print as f64 as u64, print);
    }
}

#[test]
fn typed_answers_never_carry_keys() {
    let dialog = parse(&claude_q1(), PromptKind::Claude).expect("dialog");
    for text in ["navy\n", "a\rb", "esc\u{1b}[A"] {
        let goal = Goal { answers: vec![answer(0, "Which color?", &[], Some(text))], finish: Finish::Submit };
        assert_eq!(step(Some(&dialog), &goal, &mut Progress::default()), Step::Fail(Failure::Unsupported), "{text:?}");
    }
}
