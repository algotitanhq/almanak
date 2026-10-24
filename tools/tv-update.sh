#!/usr/bin/env bash
# Update Almanak on a TV in place, keeping every setting (ALG-224). Never uninstalls: an uninstall erases the
# settings (stops, city, weather, modules, keys). Refuses an APK signed by another key than the installed app (a
# debug build over the release APK: Android would refuse it, and the only way past is an uninstall), or an older
# version. Also refuses while a video plays.
#   ALMANAK_DEVICE=ip:port tools/tv-update.sh <apk>
set -euo pipefail
DEV="${ALMANAK_DEVICE:-}"
APK="${1:-}"
if [[ -z "$DEV" || ! -f "$APK" ]]; then
  echo "usage: ALMANAK_DEVICE=ip:port tools/tv-update.sh <apk>" >&2
  exit 2
fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner | sort -V | tail -1)
AAPT=$(ls "$SDK"/build-tools/*/aapt | sort -V | tail -1)
[[ "$DEV" == emulator-* ]] || adb connect "$DEV" >/dev/null
A=(adb -s "$DEV")
signer() { "$APKSIGNER" verify --print-certs "$1" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'; }

# Read whole before matching: grep -q closing the pipe early would fail the check under pipefail.
sessions=$("${A[@]}" shell dumpsys media_session)
if grep -Eq 'state=(PlaybackState \{state=)?(3|PLAYING)\b' <<<"$sessions"; then
  echo "refused: something is playing on the TV; try later" >&2; exit 1
fi
new_signer=$(signer "$APK")
new_code=$("$AAPT" dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p;T;q")
[[ -n "$new_signer" && -n "$new_code" ]] || { echo "cannot read the signer or version of $APK" >&2; exit 1; }
path=$("${A[@]}" shell pm path app.almanak 2>/dev/null | sed -n 's/^package://p' | tr -d '\r' || true)
path=${path%%$'\n'*}
if [[ -n "$path" ]]; then
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
  "${A[@]}" pull "$path" "$tmp/installed.apk" >/dev/null 2>&1 || { echo "cannot copy the installed APK to check its signer" >&2; exit 1; }
  old_signer=$(signer "$tmp/installed.apk")
  old_code=$("${A[@]}" shell dumpsys package app.almanak | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p;T;q')
  [[ -n "$old_signer" && -n "$old_code" ]] || { echo "cannot read the installed app's signer or version" >&2; exit 1; }
  if [[ "$new_signer" != "$old_signer" ]]; then
    echo "refused: $APK is signed by $new_signer, the installed app by $old_signer." >&2
    echo "  Installing it would need an uninstall, which erases the settings. Use a build signed by the same key" >&2
    echo "  (for the release APK: the Release workflow's dry run on main, or a published release)." >&2
    exit 1
  fi
  if (( new_code < old_code )); then
    echo "refused: versionCode $new_code is older than the installed $old_code" >&2; exit 1
  fi
fi
"${A[@]}" install -r "$APK"
"${A[@]}" shell monkey -p app.almanak 1 >/dev/null 2>&1
echo "updated in place: versionCode ${old_code:-none} -> $new_code, signer $new_signer"
