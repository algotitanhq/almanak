#!/usr/bin/env bash
# README media from emulator captures (1920x1080): a 1280x720 still, or the GIF from a screen recording.
#   tools/readme_media.sh still <in.png> <out.png>
#   tools/readme_media.sh gif <in.mp4> <out.gif>
# Nothing is blurred: capture with the debug extra `--es setupUrl http://192.0.2.10:8737/` so the app itself draws a
# sharp QR code and URL that lead nowhere (RFC 5737 documentation address). Check every output by eye.
# Env: FFMPEG (default ffmpeg).
set -euo pipefail
FF="${FFMPEG:-ffmpeg}"
case "$1" in
  still)
    "$FF" -v error -y -i "$2" -vf "scale=1280:-1:flags=lanczos" "$3" ;;
  gif)
    "$FF" -v error -y -i "$2" -filter_complex \
      "[0]fps=12,scale=960:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128:stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
      -loop 0 "$3" ;;
  *) echo "usage: $0 still|gif <in> <out>" >&2; exit 2 ;;
esac
