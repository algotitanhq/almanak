#!/usr/bin/env bash
# Upgrade test: an install of the old APK with a populated configuration, updated in place by the new APK, must keep
# every setting (ALG-224). EMULATOR ONLY: it uninstalls the app first.
#   ALMANAK_DEVICE=emulator-5554 tools/upgrade-test.sh <old.apk> <new.apk>
# Both APKs must be signed by the same key (two debug builds of this machine, or two release builds); the old one
# must be debuggable, so the test can write and read the settings file with run-as.
# Output: the old and new settings files and a capture after the update, in shots/upgrade/.
set -euo pipefail
DEV="${ALMANAK_DEVICE:-}"
if [[ "$DEV" != emulator-* || $# -ne 2 ]]; then
  echo "usage: ALMANAK_DEVICE=emulator-NNNN tools/upgrade-test.sh <old.apk> <new.apk>" >&2
  echo "  emulators only: the test uninstalls app.almanak, which erases its settings" >&2
  exit 2
fi
OLD="$1"; NEW="$2"
cd "$(dirname "$0")/.."
A=(adb -s "$DEV")
OUT=shots/upgrade
mkdir -p "$OUT"
pkg() { "${A[@]}" shell dumpsys package app.almanak | sed -n "s/^ *$1=\(.*\)$/\1/p" | head -1 | tr -d '\r' | sed 's/ versionCode.*//; s/ minSdk.*//'; }
settings() { "${A[@]}" shell run-as app.almanak cat files/settings.json; }

echo "1. fresh install of the old APK"
"${A[@]}" uninstall app.almanak >/dev/null 2>&1 || true
"${A[@]}" install "$OLD" >/dev/null
"${A[@]}" shell am start -W -n app.almanak/.MainActivity >/dev/null
sleep 4
"${A[@]}" shell am force-stop app.almanak

echo "2. a populated configuration, saved by the old app itself"
"${A[@]}" push tools/upgrade-settings.json /data/local/tmp/almanak-settings.json >/dev/null
"${A[@]}" shell "run-as app.almanak sh -c 'cat /data/local/tmp/almanak-settings.json > files/settings.json'"
"${A[@]}" shell rm /data/local/tmp/almanak-settings.json
# The TV settings open on the theme row: three presses go round the three themes, so the old app rewrites the file
# through its own serializer three times and ends on the same theme.
"${A[@]}" shell am start -W -n app.almanak/.MainActivity --ez settings true >/dev/null
sleep 6
for _ in 1 2 3; do "${A[@]}" shell input keyevent KEYCODE_DPAD_CENTER; sleep 1.5; done
"${A[@]}" shell input keyevent KEYCODE_BACK
sleep 2
"${A[@]}" shell am force-stop app.almanak
settings > "$OUT/settings-old.json"
if cmp -s tools/upgrade-settings.json "$OUT/settings-old.json"; then
  echo "FAIL: the old app did not rewrite the settings file" >&2; exit 1
fi
first=$(pkg firstInstallTime); code_old=$(pkg versionCode)

echo "3. update in place with the new APK (adb install -r)"
"${A[@]}" install -r "$NEW"
[[ "$(pkg firstInstallTime)" == "$first" ]] || { echo "FAIL: firstInstallTime changed: the app was reinstalled, not updated" >&2; exit 1; }
"${A[@]}" shell am start -W -n app.almanak/.MainActivity >/dev/null
sleep "${SETTLE:-25}"
"${A[@]}" exec-out screencap -p > "$OUT/after-update.png"
settings > "$OUT/settings-new.json"

echo "4. compare"
python3 -I - "$OUT/settings-old.json" "$OUT/settings-new.json" <<'EOF'
import json, sys
old, new = (json.load(open(p)) for p in sys.argv[1:3])
secret = {"realtimeKey", "password"}
def show(d):
    return {k: ("<set>" if k in secret and v else v) for k, v in d.items()}
diff = sorted(k for k in old.keys() | new.keys() if old.get(k) != new.get(k))
for k in ("stops", "routes", "city", "language", "unit", "theme", "wallpaper", "modules", "onboarded", "schema"):
    print(f"  {k}: {json.dumps(new.get(k), ensure_ascii=False)}")
print("  realtimeKey:", "kept" if old.get("realtimeKey") and old.get("realtimeKey") == new.get("realtimeKey") else "LOST")
print("  guestWifi:", "kept" if old.get("guestWifi") == new.get("guestWifi") and old["guestWifi"].get("ssid") else "LOST")
if diff:
    print("FAIL: settings changed on update:", diff); sys.exit(1)
print("PASS: every setting kept (%d fields)" % len(new))
EOF
echo "versionCode $code_old -> $(pkg versionCode); firstInstallTime $first (unchanged); lastUpdateTime $(pkg lastUpdateTime)"
