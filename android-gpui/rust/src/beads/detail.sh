
root="$1"; repos="$2"; project="$3"; id="$4"
home_of() { case "$1" in "~") printf '%s' "$HOME" ;; "~/"*) printf '%s/%s' "$HOME" "${1#"~/"}" ;; *) printf '%s' "$1" ;; esac; }
root=$(home_of "$root"); repos=$(home_of "$repos")
command -v bd >/dev/null 2>&1 || { printf '\035nobd\n'; exit 0; }
try() {
  out=$(BEADS_DIR="$1" bd show "$id" --json 2>/dev/null) || return 1
  printf '\036%s\n\035' "$out"
  BEADS_DIR="$1" bd comments "$id" --json 2>/dev/null
  printf '\n\037ok\n'
  exit 0
}
[ -d "$root/$project/.beads" ] && try "$root/$project/.beads"
if [ -f "$repos" ] && command -v jq >/dev/null 2>&1; then
  for path in $(jq -r 'to_entries[] | .value.path' "$repos" 2>/dev/null); do
    [ -d "$path/.beads" ] && try "$path/.beads"
  done
fi
printf '\037missing\n'
exit 0
