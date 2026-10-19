# Bundled data

- `app/src/main/res/raw/saints.json`: built by `tools/build_saints.py` from
  [Les saints et fêtes du calendrier](https://www.data.gouv.fr/datasets/les-saints-et-fetes-du-calendrier)
  (data.gouv.fr, Licence Ouverte / Open Licence 2.0). Corrections: May/June un-swapped, mojibake fixed (ë, î),
  "Converstion" typo, "St-" → "St ".
- `app/src/main/res/raw/saints_es.json`: built by `tools/build_santoral.py` from the "Santoral católico" section of
  Spanish Wikipedia's day pages ("4 de octubre", …), text under CC BY-SA 4.0. Saints and feasts in page order (the
  main saint first), blessed and venerables dropped, at most 4 per day.
- `app/src/main/res/raw/tzdata_zone.tab`: `zone.tab` from the IANA tz database 2026c, verbatim (public domain).
  Each zone's country and the coordinate of the city it is named after: the place used before a city is chosen.
  `zone.tab` rather than `zone1970.tab`: same coordinates, plus one row per country (Europe/Amsterdam has its own
  row instead of folding into Brussels), so the city name matches the TV's zone.
- Runtime sources (not bundled): Open-Meteo (CC BY 4.0); each transit network's GTFS / GTFS-realtime under its own
  open-data licence (STM: CC BY 4.0), via the agency or the Mobility Database mirror; Wikimedia "On this day" feed
  (CC BY-SA 4.0) and picture of the day (each image's own licence).
