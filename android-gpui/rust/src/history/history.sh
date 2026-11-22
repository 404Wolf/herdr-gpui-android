# An agent's session file, read-only (factory-9zz.12): $1 claude|codex, $2 its session id (the
# phone checked it: letters, digits, dashes), $3 the path found last time or "", $4 the
# "size:mtime" the phone already keeps or "", $5 the most bytes of the file's end to send.
# Found only under this home's own folder for that harness, without following links, and only
# when exactly one regular file matches; otherwise "unavailable".
kind=$1; session=$2; path=$3; known=$4; max=$5
case $kind in
  claude) root="$HOME/.claude/projects"; pat="$session.jsonl" ;;
  codex) root="$HOME/.codex/sessions"; pat="rollout-*-$session.jsonl" ;;
  *) echo "unavailable kind"; exit 0 ;;
esac
case $path in "$root"/*) ;; *) path= ;; esac
if [ -z "$path" ] || [ -L "$path" ] || [ ! -f "$path" ]; then
  found=$(find "$root" -maxdepth 5 -type f -name "$pat" 2>/dev/null | head -n 3)
  n=$(printf '%s' "$found" | grep -c '^')
  [ "$n" = 1 ] || { echo "unavailable $n"; exit 0; }
  path=$found
fi
key=$(stat -c '%s:%Y' -- "$path" 2>/dev/null || stat -f '%z:%m' -- "$path")
printf 'path %s\nkey %s\n' "$path" "$key"
[ -n "$known" ] && [ "$key" = "$known" ] && { echo same; exit 0; }
echo data
tail -c "$max" -- "$path"
