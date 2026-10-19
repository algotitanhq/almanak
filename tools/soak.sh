#!/usr/bin/env bash
# Screensaver soak test: starts Almanak's dream and samples its memory on a fixed interval.
#   tools/soak.sh [hours=24] [every_minutes=15] [out=shots/soak/soak.csv]
# Env: ALMANAK_DEVICE=ip:port (required).
# Columns: ISO time, pid, dreaming (1/0), TOTAL PSS, Java heap, Native heap, Graphics (KB), crash lines so far.
# The dream is started once; if someone wakes the TV, samples keep going and "dreaming" shows 0.
set -uo pipefail
DEV="${ALMANAK_DEVICE:-}"
if [[ -z "$DEV" ]]; then
  echo "usage: ALMANAK_DEVICE=ip:port tools/soak.sh [hours=24] [every_minutes=15] [out=shots/soak/soak.csv]" >&2
  echo "  ALMANAK_DEVICE is required (the adb address of the TV, e.g. 192.0.2.10:5555)" >&2
  exit 2
fi
HOURS="${1:-24}"; EVERY="${2:-15}"; OUT="${3:-shots/soak/soak.csv}"
cd "$(dirname "$0")/.."
mkdir -p "$(dirname "$OUT")"
A=(adb -s "$DEV")
adb connect "$DEV" >/dev/null
"${A[@]}" logcat -b crash -c
"${A[@]}" shell am start -n com.android.systemui/.Somnambulator >/dev/null
sleep 20
"${A[@]}" exec-out screencap -p > "$(dirname "$OUT")/dream-start.png"
echo "time,pid,dreaming,total_pss_kb,java_heap_kb,native_heap_kb,graphics_kb,crash_lines" > "$OUT"
end=$(( $(date +%s) + HOURS * 3600 ))
while :; do
  adb connect "$DEV" >/dev/null 2>&1
  mem=$("${A[@]}" shell dumpsys meminfo app.almanak 2>/dev/null)
  field() { echo "$mem" | awk -v k="$1" '$0 ~ k {for (i=1;i<=NF;i++) if ($i ~ /^[0-9]+$/) {print $i; exit}}'; }
  pid=$("${A[@]}" shell pidof app.almanak 2>/dev/null | tr -d '\r')
  dreaming=$("${A[@]}" shell dumpsys dreams 2>/dev/null | grep -c 'mCurrentDreamName=ComponentInfo{app.almanak')
  crashes=$("${A[@]}" logcat -b crash -d 2>/dev/null | grep -c 'app.almanak')
  echo "$(date -u +%FT%TZ),${pid:-none},$dreaming,$(field 'TOTAL PSS:'),$(field 'Java Heap:'),$(field 'Native Heap:'),$(field 'Graphics:'),$crashes" >> "$OUT"
  [[ $(date +%s) -ge $end ]] && break
  sleep $(( EVERY * 60 ))
done
"${A[@]}" exec-out screencap -p > "$(dirname "$OUT")/dream-end.png"
echo "done: $OUT"
