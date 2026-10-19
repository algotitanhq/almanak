#!/usr/bin/env bash
# Device verification loop: install, launch, capture, check logs.
#   tools/shot.sh <name> [am-start extras...]      e.g. tools/shot.sh s1-swiss --es variant Swiss
# Env: ALMANAK_DEVICE=ip:port (required), SKIP_INSTALL=1, SETTLE=seconds before capture.
set -euo pipefail
DEV="${ALMANAK_DEVICE:-}"
if [[ -z "$DEV" || $# -lt 1 ]]; then
  echo "usage: ALMANAK_DEVICE=ip:port tools/shot.sh <name> [am-start extras...]" >&2
  echo "  ALMANAK_DEVICE is required (the adb address of the TV, e.g. 192.0.2.10:5555)" >&2
  exit 2
fi
NAME="$1"; shift || true
cd "$(dirname "$0")/.."
adb connect "$DEV" >/dev/null
A=(adb -s "$DEV")
if [[ -z "${SKIP_INSTALL:-}" ]]; then ./gradlew -q installDebug; fi
"${A[@]}" logcat -c
"${A[@]}" shell am start -S -W -n app.almanak/.MainActivity "$@" | grep -E 'Status|TotalTime' || true
sleep "${SETTLE:-4}"
mkdir -p shots
"${A[@]}" exec-out screencap -p > "shots/$NAME.png"
echo "captured shots/$NAME.png"
echo "--- log: app process warnings+, plus any crash/ANR on the device ---"
PID=$("${A[@]}" shell pidof app.almanak | tr -d '\r' || true)
{
  [[ -n "$PID" ]] && "${A[@]}" logcat -d --pid="$PID" '*:W' | grep -vE 'hidden method|Finsky' || true
  "${A[@]}" logcat -d | grep -iE 'FATAL EXCEPTION|ANR in app.almanak|Process: app.almanak' || true
} | tail -20 | sed '/^$/d' > /tmp/almanak-log.$$ ; [[ -s /tmp/almanak-log.$$ ]] && cat /tmp/almanak-log.$$ || echo "(clean)"; rm -f /tmp/almanak-log.$$
