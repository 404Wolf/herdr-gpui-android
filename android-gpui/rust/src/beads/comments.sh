
root="$1"; repos="$2"; project="$3"; id="$4"
home_of() { case "$1" in "~") printf '%s' "$HOME" ;; "~/"*) printf '%s/%s' "$HOME" "${1#"~/"}" ;; *) printf '%s' "$1" ;; esac; }
root=$(home_of "$root"); repos=$(home_of "$repos")
command -v bd >/dev/null 2>&1 || { printf '\035nobd\n'; exit 0; }
found=""
listed() {
  command -v beads-stores >/dev/null 2>&1 || return 1
  found=$(beads-stores list --no-terminals --root "$root" ${repos:+--repos "$repos"} 2>/dev/null) || return 1
}
# The repo stores (keys repo:...) beads-stores lists, else repos.json's: one dir a line.
repo_dirs() {
  if listed; then
    printf '%s
' "$found" | while IFS='	' read -r k d s m; do
      case "$k" in repo:*) case "$s" in skip:*) ;; *) printf '%s
' "$d" ;; esac ;; esac
    done
  elif [ -f "$repos" ] && command -v jq >/dev/null 2>&1; then
    jq -r 'to_entries[] | .value.path' "$repos" 2>/dev/null | while IFS= read -r path; do
      [ -n "$path" ] && printf '%s/.beads
' "$path"
    done
  fi
}
try() {
  out=$(BEADS_DIR="$1" bd comments "$id" --json 2>/dev/null) || return 1
  printf '\035%s\n\037ok\n' "$out"
  exit 0
}
[ -d "$root/$project/.beads" ] && try "$root/$project/.beads"
# Then the repo stores (beads-stores's, else repos.json's), one dir a line, spaces and all
# (P2-5); a here-document keeps try's exit in this shell.
while IFS= read -r dir; do
  [ -n "$dir" ] && [ -d "$dir" ] && try "$dir"
done <<EOF
$(repo_dirs)
EOF
printf '\037missing\n'
exit 0
