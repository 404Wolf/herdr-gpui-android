
f="$HOME/deacon/work/$1.md"; now=$(date +%s)
[ -f "$f" ] || { printf 'missing\n%s\n' "$now"; exit 0; }
m=$(stat -c %Y "$f" 2>/dev/null || stat -f %m "$f"); c=$(cksum < "$f" | cut -d' ' -f1)
[ -n "$2" ] && [ "$2" = "$m:$c" ] && { printf 'same\n%s\n' "$now"; exit 0; }
printf 'ok\n%s\n%s\n%s\n' "$now" "$m" "$c"
head -c 200000 "$f"
