
root="$1"; repos="$2"; project="$3"; id="$4"; mode="$5"
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
cache="${XDG_CACHE_HOME:-$HOME/.cache}/herdr-beads"
# What says whether a store's export may lag its database, for this bead: its embedded Dolt
# manifest (it changes with every write, never with a read) and the export's commit. The
# mark records the manifest and commit at which this bead's export was found current.
state_of() {
  rev=$(sed -n 's/.*"last_dolt_commit":"\([^"]*\)".*/\1/p' "$1/export-state.json" 2>/dev/null)
  man=$(ls -d "$1"/embeddeddolt/*/.dolt/noms/manifest 2>/dev/null | head -n 1)
  sum=""; [ -n "$man" ] && sum=$(cksum < "$man" | cut -d' ' -f1)
  mkdir -p "$cache" 2>/dev/null
  mark="$cache/$(printf '%s %s' "$1" "$id" | cksum | cut -d' ' -f1).fresh"
}
fast() {
  [ "$mode" = confirm ] && return 1
  [ -f "$1/export-state.json" ] && [ -f "$1/issues.jsonl" ] && command -v jq >/dev/null 2>&1 || return 1
  state_of "$1"
  [ -n "$rev" ] || return 1
  out=$(jq -c --arg id "$id" 'select(.id == $id)' "$1/issues.jsonl" 2>/dev/null) || return 1
  # Not in the export: absent (2), unless the store was written since (its manifest is newer
  # than the export, or there is no manifest to tell), when the bead may be new: bd show (1).
  if [ -z "$out" ]; then
    [ -n "$man" ] && [ ! "$man" -nt "$1/export-state.json" ] && return 2
    [ -z "$man" ] && [ "$2" != own ] && return 2
    return 1
  fi
  # Fresh: no write since the export (the manifest is no newer than it), or this bead was
  # found current at this very manifest and commit (a confirm read below). Else "maybe":
  # shown at once, and the panel confirms with bd show.
  state=maybe
  if [ -n "$sum" ]; then
    if [ ! "$man" -nt "$1/export-state.json" ]; then state=fresh; printf '%s %s\n' "$sum" "$rev" > "$mark" 2>/dev/null
    elif [ "$(cat "$mark" 2>/dev/null)" = "$sum $rev" ]; then state=fresh; fi
  fi
  # Its comments come with it (the export keeps them): one read for the plan and its comments.
  printf '\036[%s]\n\035%s\n\034%s %s\n\037ok\n' "$out" "$(printf '%s' "$out" | jq -c '.comments // []' 2>/dev/null)" "$rev" "$state"
  exit 0
}
slow() {
  mkdir -p "$cache" 2>/dev/null
  f="$cache/$(printf '%s %s' "$1" "$id" | cksum | cut -d' ' -f1)"
  # A window that waited gets the answer written while it waited (single-flight); nothing
  # older: each answer bumps a generation, read before waiting.
  gen=$(cat "$f.gen" 2>/dev/null)
  if command -v flock >/dev/null 2>&1 && exec 9>"$f.lock"; then flock -w 30 9; fi
  [ -f "$1/export-state.json" ] && state_of "$1"
  if [ "$(cat "$f.gen" 2>/dev/null)" = "$gen" ] || [ ! -f "$f" ] || [ ! -f "$f.json" ]; then
    out=$(BEADS_DIR="$1" bd show "$id" --json 2>/dev/null) || return 1
    printf '%s' "$out" > "$f.json.tmp" && mv "$f.json.tmp" "$f.json"
    # bd show leaves comments out: read in the same command when it counts any (an empty
    # part: they couldn't be read, so the panel asks for them on their own).
    cc=$(printf '%s' "$out" | jq -r 'if type == "array" then .[0] else . end | .comment_count // 0' 2>/dev/null)
    comments="[]"
    if [ "${cc:-0}" != 0 ]; then comments=$(BEADS_DIR="$1" bd comments "$id" --json 2>/dev/null) || comments=""; fi
    printf '\036%s\n\035%s\n\034\n\037ok\n' "$out" "$comments" > "$f.tmp" && mv "$f.tmp" "$f"
    printf '%s\n' "$$-$(date +%s)" > "$f.gen"
    find "$cache" -type f -mmin +1440 -delete 2>/dev/null
  fi
  # A confirm that found the export's copy of this bead current (the same updated_at):
  # remembered for this manifest and commit, so the next open needs no bd.
  if [ "$mode" = confirm ] && [ -n "$sum" ] && [ -n "$rev" ] && command -v jq >/dev/null 2>&1; then
    db=$(jq -r 'if type == "array" then .[0] else . end | .updated_at // empty' "$f.json" 2>/dev/null)
    ex=$(jq -r --arg id "$id" 'select(.id == $id) | .updated_at // empty' "$1/issues.jsonl" 2>/dev/null)
    [ -n "$db" ] && [ "$db" = "$ex" ] && printf '%s %s\n' "$sum" "$rev" > "$mark" 2>/dev/null
  fi
  cat "$f"
  exit 0
}
# $2 "own": the project's own store, where a bead with no manifest to tell is asked of bd.
# Mode "fast" (DETAILS_SH's prefetch): the export's copy or nothing ("skip"), never bd.
try() { fast "$1" "$2"; [ $? -eq 2 ] && return 1; [ "$mode" = fast ] && { printf '\037skip\n'; exit 0; }; slow "$1"; }
[ -d "$root/$project/.beads" ] && try "$root/$project/.beads" own
# Then the repo stores (beads-stores's, else repos.json's), one dir a line, spaces and all
# (P2-5); a here-document keeps try's exit in this shell.
while IFS= read -r dir; do
  [ -n "$dir" ] && [ -d "$dir" ] && try "$dir"
done <<EOF
$(repo_dirs)
EOF
printf '\037missing\n'
exit 0
