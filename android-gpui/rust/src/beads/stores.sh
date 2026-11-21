
root="$1"; repos="$2"; shift 2
home_of() { case "$1" in "~") printf '%s' "$HOME" ;; "~/"*) printf '%s/%s' "$HOME" "${1#"~/"}" ;; *) printf '%s' "$1" ;; esac; }
root=$(home_of "$root"); repos=$(home_of "$repos")
command -v bd >/dev/null 2>&1 || { printf '\035nobd\n'; exit 0; }
# One store: $1 its name, $2 its .beads folder.
store() {
  fp=$(find "$2" -printf '%T@\n' 2>/dev/null | sort -n | tail -n 1)
  printf '\036%s\n\034%s\n' "$1" "$fp"
  if [ -n "$fp" ]; then
    for kv in "$@"; do
      if [ "$kv" = "$1=$fp" ]; then printf '\037same\n'; return; fi
    done
  fi
  BEADS_DIR="$2" bd list --json --all --brief --flat -n 0 2>&1
  printf '\n\037%s\n' "$?"
}
if [ -d "$root" ]; then
  for d in "$root"/*/; do
    [ -d "$d.beads" ] || continue
    store "$(basename "$d")" "$d.beads" "$@"
  done
fi
# Repos with their own in-repo store (programs.beads.repos): one store, several projects.
if [ -f "$repos" ] && command -v jq >/dev/null 2>&1; then
  jq -r 'to_entries[] | "\(.key)\t\(.value.path)"' "$repos" 2>/dev/null | while IFS='	' read -r name path; do
    if [ -d "$path/.beads" ]; then store "repo:$name" "$path/.beads" "$@"; fi
  done
fi
exit 0
