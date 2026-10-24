# Changelog

All notable changes to Almanak are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- An "on this day" event or a name-day line is no longer cut with an ellipsis: it shrinks to fit, takes a third line
  where there is room, and only as a last resort shows in parts that turn by themselves. With four or more bus lines
  the event uses the whole width under the bus table, so it stays whole at the Medium and Large sizes.

- The STM moved its schedule file to its new site: the STM preset uses the new address, with the Mobility Database
  mirror still as the fallback.
- A transit network's site that answers with a web page instead of the schedule zip (as the STM's old address did,
  by redirecting to its home page) no longer leaves the bus block empty: the page is refused and the next source,
  the Mobility Database mirror, is used.

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
