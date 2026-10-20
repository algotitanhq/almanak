# Changelog

All notable changes to Almanak are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-10-20

First public release: a signed APK to sideload on Android TV and Google TV (Android 9 or later).

### Added

- Clock and date, large enough to read across the room, in the UI language and the TV's region (12 h or 24 h).
- Weather from Open-Meteo (no key): now, feels-like, the next hours as a curve or the next five days, °C or °F,
  and the last known values with "as of" when the network is down.
- Next bus for up to 6 stops: the network's GTFS schedule kept on the TV, adjusted by its GTFS-realtime feed when
  there is one. Presets for STM, RTC, OC Transpo, TTC and STS, or any GTFS feed.
- Almanac line: sunrise, sunset and day length, moon phase and next full moon (computed on the TV), name days, day
  and week of the year, and "on this day" from Wikimedia.
- Backgrounds: a generative sky that follows the real sun, the Wikimedia picture of the day, or an image URL,
  with a scrim that keeps the text readable.
- Three themes (Nocturne, Minimal, Paper) and burn-in protection.
- Screensaver (Daydream) showing the same screen.
- Settings drawer on the remote, and a phone setup page reached by a QR code, served by the TV on the local
  network only.
- Guest Wi-Fi join QR code on demand.
- English, French and Spanish.
- Neutral first run: no city, network or stop until you choose; the TV's time zone gives weather and sun times.

[1.0.0]: https://github.com/algotitanhq/almanak/releases/tag/v1.0.0
