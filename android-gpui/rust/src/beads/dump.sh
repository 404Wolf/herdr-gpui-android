
# The phone's whole-store mirror (factory-9zz.14): every beads store's `bd export` (each issue
# with its plan, labels, dependencies and comments), read-only. Stores are those the machine's
# beads-stores lists, else found as STORES_SH finds them, and fingerprinted by content (below); a
# store whose name=fingerprint is among the args answers "same". Per store: \x1e<store>\n\x1c<fingerprint>\n, then \x1fsame\n, or
# \x1d<encoding>\n<base64 of the export, gzip'd when gzip is there>\n\x1f<bd's exit code>\n, or
# \x1d-\n<bd's error output>\n\x1f<code>\n when bd failed. "\x1dnobd\n" alone when bd is missing.
root="$1"; repos="$2"; shift 2
home_of() { case "$1" in "~") printf '%s' "$HOME" ;; "~/"*) printf '%s/%s' "$HOME" "${1#"~/"}" ;; *) printf '%s' "$1" ;; esac; }
root=$(home_of "$root"); repos=$(home_of "$repos")
command -v bd >/dev/null 2>&1 || { printf '\035nobd\n'; exit 0; }
command -v base64 >/dev/null 2>&1 || { printf '\035nobase64\n'; exit 0; }
out=$(mktemp 2>/dev/null) && err=$(mktemp 2>/dev/null) || { printf '\035notmp\n'; exit 0; }
# Its own two temporary files are the only thing it ever removes.
trap 'rm -f "$out" "$err"' EXIT
if command -v gzip >/dev/null 2>&1; then enc=gzip; else enc=plain; fi
# One store: $1 its name, $2 its .beads folder.
store() {
  # By content, not mtime: embedded Dolt touches its journal and manifest on every read (bd list,
  # bd export), so the newest mtime STORES_SH uses changes with each poll and "same" never
  # fires. The manifest's bytes change with every write and never with a read (DETAIL_SH).
  dolt=
  for m in "$2"/embeddeddolt/*/.dolt/noms/manifest; do [ -f "$m" ] && dolt=1; done
  if [ -n "$dolt" ]; then
    fp=$(for m in "$2"/embeddeddolt/*/.dolt/noms/manifest; do cat "$m"; done 2>/dev/null | cksum | tr ' ' '.')
  else
    fp=$(find "$2" -printf '%T@\n' 2>/dev/null | sort -n | tail -n 1)
  fi
  printf '\036%s\n\034%s\n' "$1" "$fp"
  if [ -n "$fp" ]; then
    for kv in "$@"; do
      if [ "$kv" = "$1=$fp" ]; then printf '\037same\n'; return; fi
    done
  fi
  BEADS_DIR="$2" bd export > "$out" 2> "$err"
  code=$?
  if [ "$code" -ne 0 ]; then
    printf '\035-\n'; tail -n 5 "$err"; printf '\n\037%s\n' "$code"; return
  fi
  printf '\035%s\n' "$enc"
  if [ "$enc" = gzip ]; then gzip -c < "$out" | base64 | tr -d '\n'; else base64 < "$out" | tr -d '\n'; fi
  printf '\n\0370\n'
}
# The machine's own discovery (home-manager-conf modules/beads/SPEC.md) when it has it: its
# keys, directories and order, in-repo stores with no repos.json entry included. Missing or
# failing (an older generation): today's walk below, with today's keys.
if command -v beads-stores >/dev/null 2>&1 && list=$(beads-stores list --root "$root" --repos "$repos" 2>/dev/null); then
  while IFS='	' read -r key path _; do
    [ -n "$key" ] && [ -d "$path" ] && store "$key" "$path" "$@"
  done <<EOF
$list
EOF
  exit 0
fi
if [ -d "$root" ]; then
  for d in "$root"/*/; do
    [ -d "$d.beads" ] || continue
    store "$(basename "$d")" "$d.beads" "$@"
  done
fi
if [ -f "$repos" ] && command -v jq >/dev/null 2>&1; then
  jq -r 'to_entries[] | "\(.key)\t\(.value.path)"' "$repos" 2>/dev/null | while IFS='	' read -r name path; do
    if [ -d "$path/.beads" ]; then store "repo:$name" "$path/.beads" "$@"; fi
  done
fi
exit 0
