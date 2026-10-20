#!/usr/bin/env bash
# Prepares the export of this private repo's HEAD to the public release mirror algotitanhq/almanak, as ONE commit on
# top of the mirror's main (no private history), and scans that exact tree before anything leaves this machine.
#   tools/publish.sh                    build the export commit in a temp clone, scan it, print what would be pushed
#   tools/publish.sh --push             the same, then push it to the mirror's main (never a tag: tag by hand)
#   tools/publish.sh --ported <sha>     the mirror's main has commits made there (not by this script) up to <sha>,
#                                       already ported into this repo: export over them anyway
# Env: ALMANAK_MIRROR (default https://github.com/algotitanhq/almanak.git), KEEP=1 keeps the temp dir,
#   ALMANAK_PRIVATE_PATTERNS (default ~/.config/almanak/publish-private-patterns): the owner's hostnames, machine
#   names and the like, one regex per line, kept outside this repo so the public tree does not list them. Required.
# Exits non-zero, before any commit or push, on: a dirty tree, a gitleaks finding, a scan hit of
# tools/publish_scan.py (key files, shots/, private IPs, internal hostnames, home paths, personal e-mails, symlinks,
# EXIF location or device tags), or a scan that did not finish. Matched values are never printed.
set -euo pipefail

MIRROR="${ALMANAK_MIRROR:-https://github.com/algotitanhq/almanak.git}"
AUTHOR_NAME="Almanak contributors"
AUTHOR_EMAIL="release@almanak.invalid"
PUSH=0; PORTED=""
while [ $# -gt 0 ]; do
  case "$1" in
    --push) PUSH=1 ;;
    --ported) PORTED="${2:?--ported needs the mirror SHA}"; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
ROOT=$PWD
die() { echo "publish: $*" >&2; exit 1; }
command -v gitleaks >/dev/null || die "gitleaks is not installed"
command -v python3 >/dev/null || die "python3 is not installed"
PRIVATE_PATTERNS="${ALMANAK_PRIVATE_PATTERNS:-$HOME/.config/almanak/publish-private-patterns}"
[ -s "$PRIVATE_PATTERNS" ] && [ -r "$PRIVATE_PATTERNS" ] \
  || die "no private patterns file ($PRIVATE_PATTERNS): the scan would miss the owner's hostnames"

# 1. A clean HEAD: the export is HEAD's tree, so uncommitted work would be silently left out.
git diff --quiet HEAD -- || die "uncommitted changes to tracked files; commit or stash them first"
SRC=$(git rev-parse HEAD)
VERSION=$(sed -n 's/^val almanakVersion = "\(.*\)"$/\1/p' app/build.gradle.kts)
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "almanakVersion in app/build.gradle.kts is not MAJOR.MINOR.PATCH"
if [ "$PUSH" = 1 ]; then
  git fetch -q origin main
  git merge-base --is-ancestor "$SRC" origin/main || die "--push only exports a commit of main (HEAD ${SRC::7} is not)"
  grep -F "## [$VERSION] - " CHANGELOG.md | grep -qE ' - [0-9]{4}-[0-9]{2}-[0-9]{2}$' \
    || die "the $VERSION section of CHANGELOG.md has no release date"
fi

WORK=$(mktemp -d)
cleanup() { if [ "${KEEP:-0}" = 1 ]; then echo "publish: kept $WORK" >&2; else rm -rf "$WORK"; fi; }
trap cleanup EXIT

# 2. The exact tree to publish: tracked files of HEAD only (git archive), so ignored files (shots/, local.properties,
#    build/) cannot slip in.
mkdir "$WORK/tree"
git archive "$SRC" | tar -x -C "$WORK/tree"

# 3. Scan it. Both scans run, then one verdict. Anything but a clean exit counts as a hit (fail closed).
hits=0
echo "publish: scanning the tree of ${SRC::7} (Almanak $VERSION)"
rc=0
python3 -I "$ROOT/tools/publish_scan.py" --private-patterns "$PRIVATE_PATTERNS" "$WORK/tree" >&2 || rc=$?
if [ "$rc" != 0 ]; then
  hits=$((hits + 1))
  [ "$rc" = 1 ] || echo "  HIT [scan] publish_scan.py did not finish (exit $rc)" >&2
fi
# gitleaks reads a .gitleaks.toml or .gitleaksignore found in the tree it scans, whatever its flags say: the scanner
# above has reported them as forbidden paths; they are removed here so that gitleaks runs on its default rules.
find "$WORK/tree" -iname '.gitleaks*' -exec rm -rf {} +
printf '[extend]\nuseDefault = true\n' > "$WORK/gitleaks.toml"
rc=0
gitleaks dir --config "$WORK/gitleaks.toml" --redact --no-banner --log-level error --exit-code 1 \
  --report-format json --report-path "$WORK/gitleaks.json" "$WORK/tree" >/dev/null 2>"$WORK/gitleaks.err" || rc=$?
if [ "$rc" != 0 ]; then
  hits=$((hits + 1))
  echo "  HIT [gitleaks] exit $rc; findings (rule, file:line, never the secret):" >&2
  # Exit 0 = the findings are listed; anything else = no complete list, so gitleaks' own log is shown.
  python3 -I "$ROOT/tools/publish_scan.py" --gitleaks-report "$WORK/gitleaks.json" "$WORK/tree" >&2 \
    || { echo "    no finding in gitleaks' report; its log (redacted):"; sed 's/^/    /' "$WORK/gitleaks.err"; } >&2
fi
if [ "$hits" -gt 0 ]; then
  die "scan failed: nothing committed, nothing pushed. Fix it in this repo, commit, run again."
fi
echo "publish: scan clean (paths, archives, private IPs, hostnames, home paths, e-mails, symlinks, image metadata; gitleaks)"

# 4. One commit on top of the mirror's main.
git clone -q "$MIRROR" "$WORK/mirror"
cd "$WORK/mirror"
MAIN=$(git rev-parse HEAD)
# The last export: the newest commit whose Source-Commit trailer names a commit of this repo with the very same
# tree (a subject or author can be copied, a matching tree cannot be made up). The first import predates the
# trailer: the mirror's root commit stands for it.
last=""
while read -r c; do
  src=$(git log -1 --format='%(trailers:key=Source-Commit,valueonly)' "$c" | head -n1)
  if [ -n "$src" ] && git -C "$ROOT" cat-file -e "$src^{commit}" 2>/dev/null \
     && [ "$(git rev-parse "$c^{tree}")" = "$(git -C "$ROOT" rev-parse "$src^{tree}")" ]; then
    last=$c; break
  fi
done < <(git rev-list --first-parent HEAD)
[ -n "$last" ] || last=$(git rev-list --max-parents=0 HEAD | tail -n1)
foreign=$(git rev-list --count "$last..HEAD")
if [ "$foreign" -gt 0 ] && [ "$PORTED" != "$MAIN" ]; then
  git log --format='  %h %an: %s' "$last..HEAD" >&2
  die "the mirror's main has $foreign commit(s) after the last export (above). Port them into this repo, then run
  again with --ported $MAIN"
fi

find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -a "$WORK/tree/." .
# -f: the exported .gitignore must not drop a file that is tracked in this repo.
git add -A -f
if git diff --cached --quiet; then
  echo "publish: the mirror's main already has this tree; nothing to export"
  exit 0
fi
echo "publish: changes against the mirror's main (${MAIN::7}):"
git diff --cached --stat | tail -n 25
images=$(git diff --cached --name-only --diff-filter=AM | grep -iE '\.(png|jpe?g|gif|webp)$' || true)
if [ -n "$images" ]; then
  echo "publish: new or changed images, each must have been reviewed by eye (no stop, intersection, LAN address,"
  echo "hostname, SSID or real QR):"
  echo "$images" | sed 's/^/  /'
fi
GIT_AUTHOR_NAME="$AUTHOR_NAME" GIT_AUTHOR_EMAIL="$AUTHOR_EMAIL" \
GIT_COMMITTER_NAME="$AUTHOR_NAME" GIT_COMMITTER_EMAIL="$AUTHOR_EMAIL" \
  git -c commit.gpgsign=false commit -q -m "Almanak v$VERSION" -m "Source-Commit: $SRC"
echo "publish: export commit $(git rev-parse --short HEAD) prepared on top of ${MAIN::7}"

if [ "$PUSH" = 1 ]; then
  git push -q origin HEAD:main
  echo "publish: pushed to the mirror's main. Tag it by hand once checked: git tag -a v$VERSION <sha> && git push origin v$VERSION"
else
  echo "publish: dry run, nothing pushed (KEEP=1 keeps the prepared clone)."
fi
