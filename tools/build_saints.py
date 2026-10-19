#!/usr/bin/env python3
"""Builds app/src/main/res/raw/saints.json from the data.gouv.fr dataset
"Les saints et fêtes du calendrier" (Licence Ouverte), with documented corrections.

Usage: python3 -I tools/build_saints.py path/to/saints.json
Source: https://www.data.gouv.fr/datasets/les-saints-et-fetes-du-calendrier (resource of 2024-09-07)
"""
import json
import re
import sys

MOJIBAKE = {"à«": "ë", "à®": "î"}
TYPOS = {"Converstion": "Conversion"}


def clean(name: str) -> str:
    for bad, good in {**MOJIBAKE, **TYPOS}.items():
        name = name.replace(bad, good)
    name = re.sub(r"^(Sts?|Ste)-", r"\1 ", name.strip())
    return re.sub(r"\s+", " ", name)


def main(src: str, dst: str) -> None:
    raw = json.load(open(src, encoding="utf-8"))
    # The source has May and June swapped (24 June = St John the Baptist sits under month 5,
    # and "June" has 31 days). Reported on the dataset's forum on 2026-05-30.
    raw["5"], raw["6"] = raw["6"], raw["5"]
    # The swapped-in June also carries a stray "31 June", a copy of 31 May (Ste Pétronille).
    assert raw["6"]["31"] == raw["5"]["31"], "stray 31 June is not the 31 May duplicate"
    del raw["6"]["31"]
    assert len(raw["5"]) == 31 and len(raw["6"]) == 30, "unexpected month lengths after swap"
    assert any("Baptiste" in n for n in raw["6"]["24"]), "swap check failed"

    out = {}
    for month, days in raw.items():
        for day, names in days.items():
            names = [clean(n) for n in names if n.strip()]
            # Feasts ("Noël", "Assomption") before saints; otherwise keep source order.
            names.sort(key=lambda n: 0 if not re.match(r"^(Sts?|Ste) ", n) else 1)
            out[f"{int(month)}-{int(day)}"] = names
    assert len(out) == 366, len(out)
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    print(f"wrote {dst}: {len(out)} days")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "app/src/main/res/raw/saints.json")
