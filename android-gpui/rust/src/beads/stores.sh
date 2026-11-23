
root="$1"; repos="$2"; shift 2
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
# One store: $1 its name, $2 its .beads folder.
store() {
  fp=$( { find "$2" -path '*/.dolt/noms' -prune -o -printf '%T@\n' 2>/dev/null | sort -n | tail -n 1
    find "$2" -path '*/.dolt/noms/*' -type f -printf '%s %P\n' 2>/dev/null | sort
    find "$2" -path '*/.dolt/noms/manifest' -type f -exec cat {} + 2>/dev/null; } | cksum | tr ' ' '-')
  printf '\036%s\n\034%s\n' "$1" "$fp"
  if [ -n "$fp" ]; then
    for kv in "$@"; do
      if [ "$kv" = "$1=$fp" ]; then printf '\037same\n'; return; fi
    done
  fi
  # bd's stderr apart from its JSON (P2-4): a warning with a bracket can't break the parse;
  # it is shown only when bd fails.
  e=$(mktemp 2>/dev/null || echo "${TMPDIR:-/tmp}/herdr-bd.$$")
  BEADS_DIR="$2" bd list --json --all --brief --flat -n 0 2>"$e"; rc=$?
  [ "$rc" -ne 0 ] && cat "$e"
  rm -f "$e"
  printf '\n\037%s\n' "$rc"
}
# beads-stores's list when it answers (P: modules/beads/SPEC.md), in its order.
if listed; then
  while IFS='	' read -r key dir src main; do
    case "$src" in skip:*) continue ;; esac
    [ -n "$key" ] && [ -d "$dir" ] && store "$key" "$dir" "$@"
  done <<EOF
$found
EOF
  exit 0
fi
# Else the walk, as before: the root's stores, then repos.json's.
[ -n "$repos" ] && [ ! -f "$repos" ] && printf '\035norepos\n'
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
