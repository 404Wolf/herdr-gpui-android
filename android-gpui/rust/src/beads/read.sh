# One bead for the phone's Tasks tab, in one exec: its plan (DETAIL_SH, $1) and its
# comments (COMMENTS_SH, $2), with the store args after them; a bead whose read says
# it has no comments skips the comments read. Output: DETAIL_SH's, "\a" on a line,
# then COMMENTS_SH's. Read-only, as both are.
d=$1; c=$2; shift 2
out=$(sh -c "$d" sh "$@")
printf '%s\n\007\n' "$out"
case "$out" in
  *'"comment_count":0'*) printf '\035[]\n\037ok\n'; exit 0 ;;
esac
exec sh -c "$c" sh "$1" "$2" "$3" "$4"
