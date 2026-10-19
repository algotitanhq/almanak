#!/usr/bin/env python3
"""Builds app/src/main/res/raw/saints_es.json, the Spanish santoral, from the "Santoral católico" section of
Spanish Wikipedia's day pages ("4 de octubre", ...). Text under CC BY-SA 4.0 (see third_party/DATA.md).

Keeps saints and feasts, in page order (the main saint of the day comes first), and drops the blessed
(Beato/Beata) and venerables: the French calendar lists saints only, and the TV shows two names a day.

Usage: python3 -I tools/build_santoral.py [out.json]
"""
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://es.wikipedia.org/w/api.php"
MONTHS = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
          "noviembre", "diciembre"]
DAYS = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
PER_DAY = 4


def wikitexts(titles: list[str]) -> dict[str, str]:
    """Up to 50 pages per request (the API's limit), retrying when asked to slow down."""
    q = urllib.parse.urlencode({"action": "query", "prop": "revisions", "rvprop": "content", "rvslots": "main",
                                "titles": "|".join(titles), "format": "json", "formatversion": 2})
    # Wikimedia asks API clients to identify themselves.
    req = urllib.request.Request(f"{API}?{q}", headers={"User-Agent": "Almanak/0.1 (santoral build script)"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                pages = json.load(r)["query"]["pages"]
            return {p["title"]: p["revisions"][0]["slots"]["main"]["content"] for p in pages if "revisions" in p}
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 4:
                raise
            time.sleep(int(e.headers.get("Retry-After", "10")) + 1)


def section(text: str) -> str:
    # Some pages capitalise it ("Santoral Católico") or make it a link ("[[Santoral católico]]").
    m = re.search(r"^(=+)\s*(?:\[\[)?Santoral católico(?:\]\])?\s*\1\s*$", text, re.M | re.I)
    if not m:
        return ""
    rest = text[m.end():]
    end = re.search(r"^=+[^=].*=+\s*$", rest, re.M)
    return rest[: end.start()] if end else rest


def clean(item: str) -> str:
    s = re.sub(r"<ref[^>]*/>|<ref[^>]*>.*?</ref>", "", item, flags=re.S)
    s = re.sub(r"<small>.*?</small>|<br\s*/?>", "", s, flags=re.S)
    s = re.sub(r"\{\{[^{}]*\}\}", "", s)
    s = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", s)
    s = s.replace("'''", "").replace("''", "")
    # The name only: drop "(f. 1226)", ", obispo", ", mártires"... but keep "Santos Fausto, Jenaro y Marcial".
    s = re.split(r"\s*\(|,\s+(?=[a-záéíóúñ])|;|\.\s*$", s, maxsplit=1)[0]
    s = re.sub(r"\s+", " ", s).strip(" .,:'")
    return s[:1].upper() + s[1:]  # some pages write "san Antero"


def names(text: str) -> list[str]:
    out = []
    for line in section(text).splitlines():
        if not re.match(r"^\*[^*]", line):
            continue
        n = clean(line[1:])
        # A name, not a stray ":" or a sentence about the day ("Día Mundial de...").
        is_name = len(n) >= 3 and ". " not in n and "." not in n[-1:] and len(n) <= 70
        if is_name and not re.match(r"^(Beat[oa]s?|Venerable)\b", n) and n not in out:
            out.append(n)
    return out[:PER_DAY]


def main(dst: str) -> None:
    keys = {f"{d} de {month}": f"{m}-{d}" for m, (month, days) in enumerate(zip(MONTHS, DAYS), 1) for d in range(1, days + 1)}
    titles = list(keys)
    texts = {}
    for i in range(0, len(titles), 50):
        texts.update(wikitexts(titles[i:i + 50]))
        time.sleep(2)  # polite
    out = {key: names(texts.get(title, "")) for title, key in keys.items()}
    empty = [k for k, v in out.items() if not v]
    assert len(out) == 366 and len(empty) < 10, f"{len(empty)} empty days: {empty}"
    assert "José de Nazaret" in out["3-19"][0] and "Francisco de Asís" in out["10-4"][0], "spot check failed"
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    print(f"wrote {dst}: {len(out)} days, {len(empty)} empty {empty}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/raw/saints_es.json")
