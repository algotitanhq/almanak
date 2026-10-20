#!/usr/bin/env python3
"""Scans an exported tree for what must not reach the public mirror (called by tools/publish.sh).

    python3 -I tools/publish_scan.py [--private-patterns <file>] <tree>
    python3 -I tools/publish_scan.py --gitleaks-report <report.json> <tree>

Prints one line per hit, "HIT [rule] path[:line]", never the matched value. Exit 0: clean, 1: hits, 2: usage,
3: the scan itself failed (publish.sh treats that as a hit). Text files are read as UTF-8 or UTF-16; binary files are
scanned through their printable strings (ASCII and UTF-16LE), PNG text chunks included (zTXt and iTXt inflated).
With --gitleaks-report, prints the findings of a gitleaks JSON report instead, one "HIT [gitleaks:rule] path:line"
each (never the secret); exit 0 when it listed at least one, 1 when the report is missing, unreadable or empty.
--private-patterns adds the owner's own patterns (hostnames, machine names, network products), kept out of the tree
on purpose: one regular expression per line, a line starting with "#" is a comment, a "text:" prefix limits the line to text files
(short names show up at random in compressed binaries). A missing, empty or invalid file exits 3.
"""
import json
import os
import re
import sys
import zlib

# Paths that are never published, whatever they contain (case-insensitive, on the path relative to the tree).
FORBIDDEN_PATH = re.compile(
    r"(^|/)shots/|(^|/)local\.properties$|(^|/)keystore\.properties$|(^|/)\.env(\.|$)|(^|/)\.gitleaks"
    r"|(^|/)\.(netrc|npmrc|pypirc)$|(^|/)id_(rsa|dsa|ecdsa|ed25519)|google-services\.json$|service-account.*\.json$"
    r"|\.(jks|keystore|bks|p12|pfx|pem|key|der|p8|gpg|asc|b64|apk|aab)$|(^|/)(key|keystore)[-_]?(alias|password)",
    re.I,
)

OCTET = r"(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])"
# Archives are not looked into (neither by gitleaks): only the Gradle wrapper may be one.
ARCHIVE = re.compile(r"\.(gz|tgz|zip|jar|7z|tar|bz2|xz|aar|zst|rar)$", re.I)
ALLOWED_ARCHIVES = {"gradle/wrapper/gradle-wrapper.jar"}

# Private, carrier-grade NAT (100.64/10) and link-local IPv4; ULA and link-local IPv6.
PRIVATE_V4 = re.compile(
    r"(?<![0-9.])(192\.168|10\.%(o)s|172\.(1[6-9]|2[0-9]|3[01])|100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])"
    r"|169\.254)\.%(o)s\.%(o)s(?![0-9])" % {"o": OCTET}
)
# 10.0.2.x is the Android emulator's fixed NAT address, the same on every machine.
ALLOWED_V4 = re.compile(r"10\.0\.2\.%s" % OCTET)
PRIVATE_V6 = re.compile(r"(?<![0-9A-Za-z:])(f[cd][0-9a-f]{2}|fe80):[0-9a-f]{0,4}:[0-9a-f:]*", re.I)
# Local DNS suffixes and home directories. The owner's own hostnames and machine names are not in this file (a reader
# would see them): they come from --private-patterns. (Character classes such as [l] keep this file from matching
# its own patterns.)
HOST = re.compile(
    r"\b[a-z0-9-]+\.(loca[l]|la[n]|home\.arpa|interna[l])\b"
    r"|/home/[a-z_][a-z0-9_-]*\b|/roo[t]/|/Users/[A-Za-z]|[A-Za-z]:\\Users\\"
)
PRIVATE, PRIVATE_TEXT = [], []
MAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
ALLOWED_MAIL = re.compile(r"(release@almanak\.invalid|[0-9]+\+[A-Za-z0-9-]+@users\.noreply\.github\.com"
                          r"|[^@]+@example\.(com|org|net))", re.I)
# The inline allow marker of gitleaks would silence it for that line.
GITLEAKS_ALLOW = re.compile(r"gitleaks:allo[w]", re.I)

hits = 0


def hit(rule, where):
    global hits
    hits += 1
    print("  HIT [%s] %s" % (rule, where))


def scan_text(rel, text, located=True):
    for n, line in enumerate(text.splitlines(), 1):
        where = "%s:%d" % (rel, n) if located else rel
        for m in PRIVATE_V4.finditer(line):
            if not ALLOWED_V4.fullmatch(m.group(0)):
                hit("private-ip", where)
        if PRIVATE_V6.search(line):
            hit("private-ipv6", where)
        if HOST.search(line):
            hit("internal-host", where)
        if any(p.search(line) for p in PRIVATE) or (located and any(p.search(line) for p in PRIVATE_TEXT)):
            hit("private-pattern", where)
        for m in MAIL.finditer(line):
            if not ALLOWED_MAIL.fullmatch(m.group(0)):
                hit("email", where)
        if GITLEAKS_ALLOW.search(line):
            hit("gitleaks-allow", where)


def strings(data):
    out = [m.group(0).decode("ascii") for m in re.finditer(rb"[\x20-\x7e]{4,}", data)]
    out += [m.group(0).decode("utf-16-le") for m in re.finditer(rb"(?:[\x20-\x7e]\x00){4,}", data)]
    return "\n".join(out)


# EXIF tags that place or identify: GPSInfo, Make, Model, Artist, Copyright, HostComputer, owner and serial numbers.
EXIF_TAGS = {0x8825: "gps", 0x010F: "device", 0x0110: "device", 0x013B: "owner", 0x8298: "owner", 0x013C: "device",
             0xA430: "owner", 0xA431: "device", 0xA435: "device", 0xC62F: "device"}


def exif(rel, tiff):
    """Walks IFD0 and its Exif sub-IFD; a tag of EXIF_TAGS is a hit (pixel sizes, colour space and the like are not)."""
    order = {b"II": "little", b"MM": "big"}.get(tiff[:2])
    if order is None:
        hit("image-exif-unreadable", rel)
        return
    u = lambda at, n: int.from_bytes(tiff[at:at + n], order)
    todo, seen = [u(4, 4)], set()
    while todo:
        ifd = todo.pop()
        if ifd in seen or ifd + 2 > len(tiff):
            continue
        seen.add(ifd)
        for i in range(u(ifd, 2)):
            entry = ifd + 2 + 12 * i
            tag = u(entry, 2)
            if tag in EXIF_TAGS:
                hit("image-exif-" + EXIF_TAGS[tag], rel)
            elif tag == 0x8769:
                todo.append(u(entry + 8, 4))


def png_text(rel, data):
    """Text chunks of a PNG (tEXt, zTXt, iTXt) inflated; an eXIf chunk goes through exif()."""
    out, pos = [], 8
    while pos + 8 <= len(data):
        size = int.from_bytes(data[pos:pos + 4], "big")
        kind, body = data[pos + 4:pos + 8], data[pos + 8:pos + 8 + size]
        pos += 12 + size
        if kind == b"eXIf":
            exif(rel, body)
        elif kind == b"tEXt":
            out.append(body.decode("latin-1"))
        elif kind == b"zTXt":
            key, _, rest = body.partition(b"\0")
            out.append(key.decode("latin-1") + " " + zlib.decompress(rest[1:]).decode("latin-1"))
        elif kind == b"iTXt":
            key, _, rest = body.partition(b"\0")
            compressed, rest = rest[0], rest[2:]
            _lang, _, rest = rest.partition(b"\0")
            _tkey, _, text = rest.partition(b"\0")
            text = zlib.decompress(text) if compressed else text
            out.append(key.decode("latin-1") + " " + text.decode("utf-8", "replace"))
    return "\n".join(out)


def decode(data):
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16")
    if b"\0" not in data:
        try:
            return data.decode("utf-8")
        except UnicodeDecodeError:
            pass
    return None


current = "?"


def fail(err):
    raise err


def main(root):
    global current
    for dirpath, dirnames, filenames in os.walk(root, onerror=fail):
        for name in sorted(dirnames + filenames):
            full = os.path.join(dirpath, name)
            rel = current = os.path.relpath(full, root)
            scan_text(rel, rel, located=False)
            if os.path.islink(full):
                hit("symlink", rel)
                continue
            if os.path.isdir(full):
                continue
            if FORBIDDEN_PATH.search(rel):
                hit("forbidden-path", rel)
            if ARCHIVE.search(rel) and rel not in ALLOWED_ARCHIVES:
                hit("archive", rel)
            with open(full, "rb") as f:
                data = f.read()
            text = decode(data)
            if text is not None:
                scan_text(rel, text)
                continue
            scan_text(rel, strings(data), located=False)
            if data.startswith(b"\x89PNG\r\n\x1a\n"):
                scan_text(rel, png_text(rel, data), located=False)
            elif data[:4] in (b"II*\0", b"MM\0*"):  # TIFF (and DNG): the file is the EXIF structure
                exif(rel, data)
            else:  # JPEG APP1, HEIC Exif item, WebP EXIF chunk: with the "Exif" prefix or a bare TIFF header
                at = data.find(b"Exif\0\0")
                if at >= 0:
                    exif(rel, data[at + 6:])
                elif data[:4] == b"RIFF" and data[8:12] == b"WEBP" and b"EXIF" in data:
                    at = data.index(b"EXIF") + 8
                    exif(rel, data[at:])
        dirnames[:] = [d for d in dirnames if not os.path.islink(os.path.join(dirpath, d))]
    return 1 if hits else 0


def gitleaks_report(report, root):
    # Exit 0 here means "the findings are listed", not "clean": the verdict is gitleaks' own exit, in publish.sh.
    try:
        with open(report, encoding="utf-8") as f:
            findings = json.load(f)
        for f in findings:
            path = os.path.relpath(os.path.abspath(f["File"]), os.path.abspath(root))
            hit("gitleaks:%s" % f["RuleID"], "%s:%s" % (path, f["StartLine"]))
    except Exception as e:  # a partial list is not the list: the caller then prints gitleaks' log too
        print("  publish_scan: gitleaks report unreadable: %s" % type(e).__name__)
        return 1
    return 0 if hits else 1


def load_private(path):
    """Fills PRIVATE and PRIVATE_TEXT; raises when the file is missing, unreadable, empty or holds a bad pattern."""
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("text:"):
                PRIVATE_TEXT.append(re.compile(line[5:].strip()))
            else:
                PRIVATE.append(re.compile(line))
    if not PRIVATE and not PRIVATE_TEXT:
        raise ValueError("no pattern")


if __name__ == "__main__":
    args = sys.argv[1:]
    if len(args) == 3 and args[0] == "--gitleaks-report":
        sys.exit(gitleaks_report(args[1], args[2]))
    if len(args) == 3 and args[0] == "--private-patterns":
        try:
            load_private(args[1])
        except Exception as e:  # never print the file's content: it is the private part
            print("publish_scan: private patterns unusable: %s" % type(e).__name__, file=sys.stderr)
            sys.exit(3)
        args = args[2:]
    if len(args) != 1 or not os.path.isdir(args[0]):
        print("usage: publish_scan.py [--gitleaks-report <report.json> | --private-patterns <file>] <tree>",
              file=sys.stderr)
        sys.exit(2)
    sys.argv[1:] = args
    try:
        code = main(sys.argv[1])
    except Exception as e:  # a scan that did not finish is not a clean scan
        print("publish_scan: failed on %s: %s" % (current, type(e).__name__), file=sys.stderr)
        code = 3
    sys.exit(code)
