# Almanak — decision log

Format: **context → options → choice → why**. Newest entries at the bottom of each section.

---

## 0. Device survey (2026-10-08)

`adb -s <the Shield's LAN address>:5555 shell …`

| Property | Value |
|---|---|
| Model | NVIDIA SHIELD Android TV (`mdarcy`) |
| Android | 11 (API 30) |
| Display | 1920×1080 px, density 320 → **960×540 dp** canvas |
| ABIs | arm64-v8a, armeabi-v7a |
| RAM | ~3 GB total, ~170 MB free at idle |
| Features | `android.software.leanback`, `leanback_only` |
| Screen-off timeout | 7 200 000 ms (2 h) |

Implications: design is done on a 960×540 dp canvas. A 5 % overscan margin is 48 dp horizontally and 27 dp vertically,
rounded to **48 × 32 dp**. Free RAM is tight, so wallpapers are decoded at screen size, never at full resolution.

## 1. Plan of vertical slices

Each slice ends with: build → install on the Shield → launch → screencap → read and critique the capture → DPAD
navigation check → logcat check → git commit that describes what is now visible.

| # | Slice | Visible result | Main risks |
|---|---|---|---|
| 1 | Living skeleton | Leanback icon + banner; fullscreen time + date in large type | Toolchain (AGP 9 / Kotlin 2.4), font rendering |
| 2 | Weather | Current conditions + next hours, line icons, offline last value + discreet timestamp | Icon style consistency |
| 3 | Real-time bus | Next departures (min) for the two configured stops of one line, RT vs scheduled marker, 30 s refresh, clean fallback | **STM key currently rejected** (see §3); static GTFS is big (stop_times) |
| 4 | Ephemeris | Sunrise/sunset, moon phase, day-of-year, ISO week, saint of the day, "on this day" (Wikimedia fr) | Local astronomy accuracy |
| 5 | Wallpapers + themes | Rotating backgrounds, adaptive scrim, 3 themes, burn-in drift | Memory, legibility on bright images |
| 6 | Screensaver | `DreamService` reusing the same composables; README activation steps | Compose in a Dream needs a manual lifecycle owner |
| 7 | Personalisation | Module grid (on/off, slot, size), stops, city, theme, units; TV screen with DPAD, then a local web page + QR | Focus handling, embedded HTTP server |

Then: run the screensaver for 24 h with `dumpsys meminfo` taken at the start and at the end.

## 2. Ambiguities in the brief resolved without asking

- **Device address.** The brief names both `<IP_SHIELD>` (unfilled placeholder) and the Shield's LAN address. I use
  that LAN address (not published here), which answered. The README documents `ALMANAK_DEVICE` to change it.
- **Default coordinates.** `<LAT>,<LON>` was left unfilled. Default: downtown Montréal, 45.5019, −73.5674.
  It can be changed in settings (slice 7).
- **Log tag.** The brief greps `lucarne` (probably an earlier project name). The app logs under the tag
  `Almanak`; the verification script greps `-iE 'almanak|lucarne|AndroidRuntime|ANR'` so nothing slips through.
- **Language.** en-US UI by default, French available. Wikimedia "on this day" is fetched in French as the
  brief asks, even in the English UI (it's content, not chrome).

## 3. Secrets

- The STM API key **must not** be in git. It is read from `local.properties` (`stm.apiKey=…`, gitignored) or the
  `STM_API_KEY` environment variable, and injected through `BuildConfig`. Slice 7 adds a runtime override
  from the settings screen/web page (DataStore).
- **2026-10-08 — the key provided in the brief is rejected by STM.** Per the STM v2 docs, the request is
  `GET https://api.stm.info/pub/od/gtfs-rt/ic/v2/tripUpdates` with header `apikey`. That request returns
  `400 Invalid API Key`. I also tried `apiKey`, `x-api-key`, `Ocp-Apim-Subscription-Key`, Bearer auth, a query
  parameter, and an `l7xx…` prefix (Layer7 keys are usually `l7xx` + 32 hex). All return the same error.
  Likely causes: the key was never *published* on the new API Hub (the FAQ asks for SAVE then PUBLISH), the
  application lacks the GTFS-realtime API subscription, or the key was copied wrong. Slice 3 is built against
  the spec and the static GTFS (no key needed). It shows "scheduled" times until a valid key is supplied.

## 4. Toolchain

- **Context.** The machine had JDK 21 and an SDK without platforms or build tools, and no Gradle.
- **Choice.** Installed `platforms;android-36` and `build-tools;36.0.0`. The Gradle wrapper is pinned to **9.7.1**
  (released 2026-08-19, more than two weeks old). The newest 9.8.x and Kotlin 2.4.21 are too fresh.
  AGP **9.4.1**, Kotlin **2.4.10** (AGP 9 built-in Kotlin, with KGP pinned via the root `plugins {}` block),
  Compose BOM **2026.09.00**, tv-material **1.1.0**.
- **minSdk 28** (Android 9). The Shield is on 30, and Chromecast with Google TV and recent Google TV sets are on
  10+. 28 still covers older Android TV sets for free. Variable fonts (`FontVariation`) need 26+.
- **Single module** `:app`, package `app.almanak`, manual injection through a small `AppGraph` object created
  in `Application`.

---

## Slice 1 — Living skeleton

### Clock typography: Editorial vs Swiss
- **Variants** (captured on the Shield):
  - `shots/s1-editorial-1.png`: Fraunces 300 (opsz 144, SOFT 50), 210 sp, centred, with the date in Instrument
    Serif italic underneath.
  - `shots/s1-swiss-1.png`: Inter 200, 200 sp, bottom-left, with the weekday in tracked caps above.
- **Choice: Editorial.**
- **Why.** At 3 m the 300-weight serif keeps visible stroke contrast, while Inter 200 starts to look like a wire.
  The Swiss variant also had two layout flaws I would have had to compensate for by hand: the large time's side
  bearing indents it ~12 px from the date's left edge, and Inter's line metrics leave a ~160 px dead band under the
  digits. The Editorial pair (Fraunces + Instrument Serif) also reads as an object, not an interface. That is the
  north star. Inter stays as the secondary face for small labels and numbers (meridiem, later bus minutes).
- **Note.** The centred composition is for the clock alone. Slices 2–4 move the clock to make room, but the
  type pairing is kept.

### Other decisions
- **Minute ticks, not seconds.** The clock re-renders once per minute, aligned to the boundary (`minuteTicks()`).
  There are no seconds and no blinking colon, so nothing flickers.
- **12/24 h** follows the system (`DateFormat.is24HourFormat`). The Shield is fr-FR, so it shows 24 h.
  The UI language stays en-US by default, independent of the system locale.
- **Screen on.** `FLAG_KEEP_SCREEN_ON` in the activity. The app *is* the ambient display, so the TV's own sleep
  timer should not cut it off while it is in the foreground.
- **Brand assets** are rendered by `tools/render_brand.swift` from the bundled Fraunces font. That keeps the
  banner/icon reproducible, with no binary design source. The mark is a rising sun on a horizon line.
- **Debug hook.** `am start … --es variant <Name>` selects a visual variant. This is used for side-by-side captures.

---

## Slice 2 — Weather

### Layout: Column vs Band
- **Variants:**
  - `shots/s2-column-1.png`: clock left, a 320 dp right column with now + 5 hours + 5 days.
  - `shots/s2-band-1.png`: clock top-left, a weather band along the bottom (now on the left, 12-hour
    temperature line on the right).
- **Choice: Band.**
- **Why.** The column packs 15 small cells into the right third and reads as a table, which the brief
  forbids. Highs and lows also blur together ("12° 4°"). The band says the one thing that matters on a
  glance, "the next hours trend down to 8°", as a single drawn line. It also leaves the top-right quadrant
  free for the bus (slice 3), which keeps the screen at ≤5 blocks: clock, date, weather, bus, ephemeris.
- **Refinements after the choice:**
  - 13 hours, so labels fall every 3 h with even spacing.
  - Lows muted.
  - The 5-day forecast alternates with the hourly line *on the minute tick* (odd minutes = days), with a 1.5 s
    crossfade. That is one slow transition per minute and no extra timer (`shots/s2-band-4.png`).

### Other decisions
- **Icons are drawn, not fetched.** `ui/Glyphs.kt` draws 10 sky states on a 24-unit grid, with hairline strokes
  and ember for sun/lightning, matching the brand mark. Sun and moon are clipped behind clouds
  (`shots/s2-glyphs-2.png`). This avoids an icon font (Material Symbols is ~10 MB unsubset) and keeps one
  visual language.
- **Data is always metric.** The cache stores °C, and conversion happens at display time, so changing the unit
  setting never invalidates the cache.
- **Offline.** `WeatherSource` writes the last good response to `filesDir/weather.json`. On failure the UI keeps
  that value and shows a faint "as of 20:56" / "relevé à 20:56". The stamp also appears if data is >40 min
  old. Retries back off 1→2→4…→15 min. Refresh runs only while the UI collects (`WhileSubscribed`), so the
  app costs nothing in the background (`shots/s2-final-fr-offline.png`, via the debug `--ez offline true`).
- **UI locale is an app setting.** `ProvideAppLocale` swaps `LocalContext`/`LocalConfiguration` for the
  composition, so string resources follow the app's language (en-US default), not the TV's fr-FR.
  French drops the comma after the weekday ("Jeudi 8 octobre").
- **Hero numerals are cropped at the baseline** (`Modifier.cropToBaseline`). Fraunces' deep descent left ~50 dp
  under the digits. `lineHeight` + `LineHeightStyle.Trim` had no effect on single-line text (the two
  captures were pixel-identical), so layout-level cropping is the deterministic fix.
- **Toolchain notes.** Compose BOM 2026.09 requires compileSdk 37, so the project uses `release(37) { minorApiLevel = 2 }`.
  Unit tests use `isReturnDefaultValues` for `android.util.Log`.

---

## Slice 3 — Real-time bus

### Data pipeline
- **Static schedule on device, filtered to the configured stops.** `GtfsSyncWorker` (WorkManager: weekly,
  plus one immediate run when the stop selection changed or the feed expired) downloads the 43 MB zip with
  `If-None-Match`. `GtfsExtractor` then pulls only the selected stops' rows (`ZipFile`, line streaming) into
  `filesDir/schedule.json`: 850 departures for the two configured stops × one line.
  - On the Shield, extraction took 39 s at first. A raw-line substring prefilter on `stop_times.txt` (≈6 M lines)
    brought it to **12.8 s**.
  - The count matches an `awk` cross-check on the desktop.
- **Realtime merges into the schedule; it never replaces it.** `Departures.upcoming()` is pure, and the rules are
  unit-tested:
  - exact RT time first, else RT delay at our stop, else the latest upstream delay propagated (GTFS-RT semantics);
  - cancelled trips and skipped stops are removed;
  - late buses stay visible up to 30 min past their scheduled time;
  - realtime older than 3 min is ignored.
- **Bindings.** `org.mobilitydata:gtfs-realtime-bindings:0.2.0` (protobuf 4.34, July 2026), not the
  `com.google.transit` 0.0.4 artifact from 2015 (protobuf 2.6). Same API, maintained upstream.
- **Polling.** 30 s while visible. A rejected key is retried every 10 min, no key means no polling, and network
  errors back off up to 5 min.

### A VPN on the test TV breaks stm.info (HTTP 441)
- **Context.** The first on-device sync failed with `HTTP 441`, while the same request from the Mac succeeded.
  The test TV runs a VPN. stm.info rejects VPN exit IPs.
- **Options:**
  1. bind sockets to the underlying Wi-Fi network: circumvents the user's VPN and isn't allowed without bypass;
  2. ask the user to split-tunnel;
  3. fall back to a mirror.
- **Choice:** (3) in the app, with (2) documented. `GtfsStore` tries stm.info, then the Mobility Database mirror
  `files.mobilitydatabase.org/mdb-2126/latest.zip`. It is the same feed, byte-identical in size, and needs no key.
  ETags are stored per URL.
- **Note.** The realtime API (`api.stm.info`) may well be filtered the same way once a valid key exists. The README
  covers excluding Almanak through the VPN's split tunnelling.
- **Correction (ALG-175, 2026-10-22): the 2026-10-22 failure was not the VPN.** It was first read as a variant of
  the block above; it is not. The STM moved its GTFS file when it launched its new site: the old URL
  `www.stm.info/sites/default/files/gtfs/gtfs_stm.zip` answers `302` to the new site's home page, a `200 text/html`
  of about 71 KiB, for everyone. It reproduces with Almanak excluded from the VPN and from a plain connection. The
  fallback only caught HTTP errors, so the page was stored as `gtfs.zip` (with its URL as the cache's source),
  extraction failed with `ZipException`, and the mirror was never tried.
  - **New URL.** The `STM` preset now starts with the link on the STM's open data page (new site, "Données
    ouvertes"): `https://beta.stm.info/getContentAsset/38dcb679-4237-4c02-b6f4-5f8434665e5c/dfc3d011-8f63-43f6-9ed8-4b444333a1d0/GTFS_STM.zip?language=fr`.
    Checked on 2026-10-22: `200 application/zip`, no redirect, an `ETag`, about 64 MiB, holds `stops.txt` and
    `stop_times.txt` (feed version `20261007130000_26N`). The mirror `mdb-2126` stays the fallback. This URL was
    chosen over `beta.stm.info/sites/default/files/gtfs/gtfs_stm.zip` (a `302` to the same asset) because it is
    the one the STM publishes; both depend on the `beta` host, which the next move may retire, and the validation
    rule below turns that day into a fallback to the mirror instead of an empty bus block. Presets are always
    taken from the app (`TransitAgency.normalized`), so a stored STM choice picks up the new URL on update.
  - **Validation rule.** `GtfsStore` treats a body that is not a GTFS zip as a failed source, exactly like an HTTP
    error: a `text/html` response (after any redirect) is refused before reading, and the downloaded
    `gtfs.zip.part` must start with `PK\x03\x04`, open as a zip and hold `stops.txt` and `stop_times.txt`. The
    check runs before the rename, so a bad body never replaces the cached zip or its URL/ETag; the part file is
    always deleted. Both checks, not one: the content type is cheap but a server can label a page
    `application/zip` (or send no type), and the zip check alone would still download the whole page first.
    Checking every entry's CRC was rejected: it reads the whole 64 MiB feed twice for a failure mode (a truncated
    or corrupt zip) the extractor already reports.

### STM key
- Still rejected (`400 Invalid API Key`, see §3). The app treats this as `Unauthorized` and shows the schedule
  with a faint "scheduled times" caption. The realtime styling was verified with the debug hook
  `--ez fakeRt true`, which marks each stop's next trip as 2 min late (`shots/s3-stack-3.png`).

### Layout: Stack vs Lines
- **Variants:**
  - `shots/s3-stack-1.png`: a top-right block per direction.
  - `shots/s3-lines-1.png`: one typographic line per direction under the date.
- **Choice: Stack.**
- **Why.** Lines overflowed the left column and pushed the weather band off-screen. They also put "min" after
  clock times. Stack uses the empty top-right quadrant (reserved in slice 2).
- **First Stack draft:** the big number sat rightmost, so rows read backwards ("54 22:35 24 min").
- **Final form** (`shots/s3-final-2.png`):
  - route dot in the agency colour + number + direction in italic;
  - the next bus large ("19 min");
  - a quiet "then 49 min · 22:35" line, switching to clock times beyond 60 min;
  - baseline-aligned rows.
- **Realtime vs scheduled.** Realtime minutes are bright, with a small ember broadcast mark. Scheduled minutes
  are muted, with no mark. When realtime is down, one faint "scheduled times" caption explains the absence of
  marks without an error message.
- **Robustness.** The top row is `weight(1f)` above the weather band, so the bus block can never push the band
  off-screen (this happened once, when the caption appeared: `shots/s3-final.png`).

---

## Slice 4 — Ephemeris

### Layout: Caption vs Masthead
- **Variants:**
  - A, caption (`shots/s4-caption-*.png`): one line under the date that steps through
    sun → moon → day/week → name day → history, one fact per minute, with a 1.5 s crossfade.
  - B, masthead (`shots/s4-masthead-1.png`): a newspaper-style rule along the top with day/week/sun/moon/saint,
    plus a history line under the date.
- **Choice: Caption.**
- **Why.** The masthead makes six simultaneous blocks (the brief caps it at ~5). It pushes the clock down, and
  its history line nearly collides with the bus stack. Its 16 dp moon at 3 % illumination read as an empty
  circle. The caption keeps the screen at five blocks and gives each fact a full, legible line at 26 sp.
  The minute-tick rotation adds no timer and matches the weather band's rhythm.

### Computation
- **Sun.** Local sunrise equation, ~1 min accuracy. It matches Open-Meteo's 07:01 / 18:21 for 2026-10-08 within
  2 min (unit test) and handles polar day/night. It works offline.
- **Moon.** The first version used a mean synodic month and showed "new moon Oct 11", a day late. It was replaced
  by Meeus ch. 49 true phases (main periodic terms), tested against the 2026 eclipses (new moons 17 Feb and
  12 Aug, full moons 3 Mar and 28 Aug): all within 30 min.
  - The phase fraction is interpolated between the surrounding true new moons, so the glyph and dates agree.
  - Principal phase names apply only within ±1 day of the exact instant.
  - The glyph has a faint earthshine disc and a minimum crescent width, so a 3 % moon still reads at 24 dp.
- **Saints.** From data.gouv.fr "Les saints et fêtes du calendrier" (Licence Ouverte). The dataset has errors, so
  `tools/build_saints.py` rebuilds it reproducibly, with assertions:
  - May/June swapped;
  - a stray 31 June duplicating 31 May;
  - mojibake (ë, î) and one typo.
  Feasts sort before saints. The name day is always French (it's a French tradition) under an English label.
- **On this day.** Wikimedia feed in **French**, as the brief asks, even with the English UI. It's content, like
  the saint's name.
  - Entries over 110 characters are dropped (unreadable from the couch).
  - One request per day, cached on disk; retried every 15 min while missing.
  - Sent with an identifying User-Agent, as Wikimedia requires.

### Found on device, not in tests
- `Duration.toMinutesPart()` is Java 9 → Android API 31. It crashed on the Shield (API 30), while JVM unit tests
  on JDK 21 passed. Fixed, and **`./gradlew lintDebug` is now part of the loop**: it has no `NewApi` findings and
  no errors. It also caught a configuration-unaware `LocalContext` read in `ProvideAppLocale`, now fixed.

---

## Slice 5 — Wallpapers, themes, burn-in

### Three themes (`ui/theme/Theme.kt`)
Each theme is a palette plus two type voices. Labels and small numbers are always Inter.

| Theme | Ground / ink | Display numerals | Captions | Capture |
|---|---|---|---|---|
| **Nocturne** (default) | warm black / paper, ember accent | Fraunces 300 | Instrument Serif italic | `s5-final.png` |
| **Minimal** | true black / cool grey, ice-blue accent | Inter 300, tight | Inter 300, no italics | `s5-theme-minimal.png` |
| **Paper** | cream / ink, rust accent | Fraunces 400 (heavier: dark-on-light reads thinner) | Instrument Serif italic | `s5-theme-paper.png` |

- **Implementation.** The colour constants became `@Composable` getters on a `CompositionLocal`, so existing
  composables were untouched. The compiler flagged the 5 sites that read colours inside draw lambdas; those now
  hoist the values.

### Wallpapers
- **Local set = generative skies, not bundled photos.** Four palettes (night, dawn, day, dusk) are chosen from the
  **real** sun times, with ±45 min around sunrise/sunset counting as dawn/dusk. They are tinted toward the theme
  ground and change with a 4 s crossfade. This avoids photo licensing, adds 0 bytes to the APK, and is always
  on-brand (`s5-sky-night.png`, `s5-sky-dusk.png`).
- **Remote, no key:**
  - Wikimedia Commons *Picture of the Day*: the last 10 days, landscape only, at the standard 1920 px thumbnail
    width;
  - or any custom image URL, re-fetched on each rotation, so random-photo services work.
  - Photos rotate every 10 min with a 4 s crossfade. The licence credit ("Giles Laurent · CC BY-SA 4.0 ·
    Wikimedia Commons") is always shown, faint, in the bottom margin.
- **Resource handling:**
  - decoding at screen size (`inSampleSize`), never full resolution;
  - a disk cache of 12 files;
  - offline on first run falls back to the sky, not a blank.
- **No Coil.** The adaptive scrim needs the decoded bitmap's pixels anyway, and OkHttp + `BitmapFactory` is
  ~40 lines.
- **Cleartext HTTP** is allowed in **debug builds only** (`src/debug/res/xml/network_security_config.xml`) to
  serve test images from a dev machine. Release builds require HTTPS for custom URLs.

### Scrim: Veil vs Local
- **Test material.** Tested on a real POTD (`s5-potd-*.png`) and on a deliberately worst-case image: a white
  overcast sky over snow, served from the Mac (`s5-bright-*.png`).
- **Veil** (uniform dim + vignette, sized for the brightest region) is always legible, but it crushes bright
  photos to a drab grey.
- **Local** (gradients only behind the text regions) keeps the picture alive. In the first version, the bus's
  muted numbers lost contrast over the light mountain.
- **Choice: Local, strengthened:**
  - the base veil scales with the brightest region;
  - the bus gradient spans 45→75 % of the width;
  - (from the Paper + photo capture) a **texture term**: luminance spread per region raises the scrim, because
    a jaguar's spots behind thin type hurt as much as brightness does.
  - Final: `s5-bright-local-2.png`, `s5-theme-paper-potd-2.png`.
- **Why measured luminance rather than a blur.** `Modifier.blur` needs API 31 and the Shield is API 30. Luminance
  is computed once per image, on a 48×27 thumbnail, at the 85th percentile per region, not the mean.

### Burn-in protection
- The whole foreground drifts on a ≤7 dp radius, one step every 3 minutes. Steps follow the golden angle (no
  visible pattern), and each move is animated over 4 s. The wallpaper doesn't move, so nothing appears to slide.
- This sits on top of the existing slow changes: the per-minute caption and forecast crossfades, and the 10 min
  wallpaper rotation.

### Bug found by a new test
- `skyPhase` tests revealed that `Sun.times(date)` returned the **next day's** times. The sunrise equation's day
  count started at the noon Julian date, so the ceiling rounded one day ahead.
- Slice 4's test compared only times of day within 2 min, and tomorrow's 07:02 passed for today's 07:01.
- Fixed. The sun tests now assert the full date with 1 min tolerance.

---

## Slice 6 — Screensaver

- **`AlmanakDream` hosts the exact same `AlmanakRoot` composable as `MainActivity`.** The activity's
  `setContent` body was extracted into `ui/AlmanakRoot.kt` with a `DisplayConfig` (theme, locale, unit, scrim,
  modules), so there is no second UI.
- **Compose in a DreamService.** A Dream is not a `LifecycleOwner`, so the service implements `LifecycleOwner`,
  `ViewModelStoreOwner` and `SavedStateRegistryOwner` and sets them on the decor view.
  - RESUMED while dreaming, CREATED otherwise. The refresh loops use `WhileSubscribed` +
    `collectAsStateWithLifecycle`, so they run exactly while the screensaver is visible.
  - The ViewModel store is cleared on detach.
- **Bug found on the device.** Waking the dream crashed: `State is 'DESTROYED' and cannot be moved to STARTED`.
  On Android 11, `DreamService.detach()` calls `onDetachedFromWindow` *before* `onDreamingStopped`. The fix
  makes transitions order-independent (`moveTo(state)`, with DESTROYED terminal). Verified with 3
  dream → key → wake cycles: no crash, no ANR.
- **Testing route.** `settings put secure screensaver_components app.almanak/.AlmanakDream`, then
  `am start -n com.android.systemui/.Somnambulator` to start it. `dumpsys dreams` confirms
  `mCurrentDreamName=…AlmanakDream`. Capture: `shots/s6-dream-1.png`.
- **The Shield's settings path is verified** by driving the real Settings UI with the remote (DPAD +
  `uiautomator dump`). `shots/s6-shield-settings-1.png` (cropped to the panel, so no personal home-screen
  content) shows "Économiseur d'écran: Almanak". `screen_off_timeout` is the "Durée avant activation".
- **Changed on the Shield**, and recorded here so it can be reverted:
  - before: `screensaver_enabled=0`, `screensaver_components=` (empty),
    `screensaver_default_component=com.google.android.backdrop/.Backdrop`;
  - now: `screensaver_enabled=1`, `screensaver_components=app.almanak/.AlmanakDream`. The 24 h soak test needs it.
- **Variants:**
  - Full: `s6-dream-1.png`.
  - Quiet: `s6-quiet.png`, with clock, date and almanac centred.
  - **Choice: Full** as the screensaver default. The north star is "in 2 seconds I know when my bus comes", and the
    screensaver is how the screen will mostly be seen. Quiet is lovely at night, though, so it isn't a hard-coded
    variant: it falls out of **module visibility** (`DisplayConfig.modules`). With weather and bus off, the layout
    recentres into the slice-1 composition. Slice 7 exposes the modules as a setting.

---

## Slice 7 — Personalisation

### Settings model and persistence
- **One `Settings` document**, kotlinx-serialized, in a typed **DataStore** (`settings/Settings.kt`). It covers:
  theme, language, unit, city (name + coordinates), stops (ordered, max 4), route filter, wallpaper (+ URL),
  modules, and an STM key override.
  - `normalized()` repairs anything a hand-edited or older file could contain: unknown language, duplicate or
    empty stops, impossible module zones, missing modules. It **keeps the user's module order**; a unit test
    caught a first version that reordered them.
- **The first read blocks once at app start** (`runBlocking`). The file is under 1 KB, and the alternative is a frame
  rendered with the wrong city or stops.
- **Everything is live.** The ViewModel's loops `flatMapLatest` on the setting they depend on: city → weather and
  sun, wallpaper → backdrop. A stop change re-runs the GTFS extraction from the cached zip (e.g. 3 stops: 1252
  departures in 14 s), and the bus block shows only stops already extracted. The weather cache records its
  coordinates, so another city's forecast is never shown. Legacy caches without coordinates count as the default
  city.

### Module grid
- **Zones.** The screen has three: right column, bottom band, and the line under the date. Each module has an
  on/off switch, a zone among those it has a layout for, and a size:

  | Module | Zones | Layout per zone |
  |---|---|---|
  | Bus | Right, Bottom | right-aligned stack / side-by-side blocks |
  | Weather | Bottom, Right | band with line/days / compact "aside" with 4 hours |
  | Almanac | Under the date, Bottom | the rotating caption in both |

- **Sizes** (S/M/L = 0.85/1/1.15) are a scoped `LocalDensity`. Every dp and sp inside the module scales
  uniformly, with no per-module size code.
- **No half blocks.** The right column is a `FitColumn`, which places only the children that fit *entirely*, plus
  a footer that is always kept. Three stops at Large showed a stray half-cut block before
  (`shots/s7-phone-applied.png`). With Bus and Weather both off, the layout recentres into the quiet composition.
  `shots/s7-grid-swapped-2.png` shows weather on the right and bus at the bottom.

### TV settings screen (DPAD)
- **Variants:**
  - Drawer (`s7-settings-drawer-3.png`): a 440 dp right-hand panel over the live screen.
  - Full page (`s7-settings-fullpage.png`): a list plus a large QR.
- **Choice: Drawer.** Each OK press is visible immediately on the real screen beside it, which is what makes
  layout and theme tweaking pleasant from the couch. The full page hides the result, and its narrow QR column
  wrapped the caption awkwardly. The drawer's QR is 120 dp.
- **Interaction.** OK or Menu opens it. Every row cycles its value with OK and saves immediately. A key-*up* opens
  the panel, so the matching key-up can't click the first row.
- **Bug found on device: BACK needed two presses.** On TV, Compose turns BACK on a focused item into "exit
  focus", so `BackHandler` only fired on the second press. The panel now catches BACK in `onPreviewKeyEvent`.
  Verified with 3 OK/BACK cycles via screen pixel checks, plus OK→OK theme cycling at 0.5/1/2 s gaps.
- **Labels are string resources** (EN/FR) and follow the app language.

### Phone setup page (QR)
- **Server.** A ~150-line HTTP/1.1 server on a `ServerSocket` (`web/SetupServer.kt`), rather than NanoHTTPD or Ktor,
  for four routes: page, settings GET/PUT, stop search, city search. It runs **only while the settings drawer is
  open**.
- **Security:**
  - Every request needs a random 10-character pairing token, found only in the QR. Without it: 403, verified.
  - Fonts are the only public path, because CSS `@font-face` can't send the header. A real browser would
    otherwise have gotten a 403 for the font; found while verifying.
  - Bodies are capped at 64 KB, and sockets time out after 5 s.
  - The STM key is **never sent back** to the page, only `stmApiKeySet`. An empty key field means "unchanged".
  - Feed-provided names (stops, places) are rendered as text nodes, never `innerHTML`.
- **LAN address.** The QR uses the Wi-Fi/Ethernet IPv4 and skips VPN tunnels: on the Shield the default route can be
  a VPN tunnel, which a phone can't reach.
- **Searches:**
  - Stop search reads `stops.txt` from the cached agency zip, with no network.
  - City search proxies Open-Meteo's keyless geocoder. The TV itself offers a preset list (Montréal, Québec,
    Ottawa, Toronto, Sherbrooke, Paris).
- **Verified end to end:**
  - the QR is decoded from a screencap with ZXing → curl;
  - stop and city search;
  - a PUT applying French + Paper + Québec + a third stop shows live on the TV (`s7-phone-applied-2.png`:
    "Jeudi 8 octobre", "Lever 6:52 · Coucher 18:11", "Dégagé, ressenti 5°");
  - the page itself was driven in **jsdom** against the live TV server: render, theme click, module off, Save,
    server state checked, then restored.
  - Headless Chrome couldn't reach the LAN from this Mac, most likely macOS Local Network privacy. It doesn't
    block curl, but it blocks Homebrew node, hence the `adb forward` used for the jsdom run.

### Other
- **Paper theme with the sky:** the pastel mix went from 72 % to 88 % toward cream, glow included. At 72 % the
  night sky turned muddy grey (`s7-phone-applied.png`) instead of warm paper (`s7-paper-sky.png`).
- **The Shield is back on default settings** after the tests (`files/settings.json` removed).

---

## CI

### GitHub Actions workflow (2026-10-16)
- **Context.** No CI: every build and test ran on a dev machine. The repo is private; agents reach it through a
  git-only deploy key, with no GitHub API token, so they cannot read Actions run status.
- **Options.**
  1. One job on `ubuntu-latest`: checkout, Temurin 21, Android SDK, Gradle cache, `assembleDebug testDebugUnitTest`.
  2. The same plus lint, instrumented tests on an emulator, or a JDK/API matrix.
  3. A self-hosted runner on the forge (SDK already installed, faster warm builds).
- **Choice.** Option 1, in `.github/workflows/ci.yml`:
  - triggers `push`, `pull_request`, `workflow_dispatch`; `permissions: contents: read`; one `concurrency` group
    per ref with `cancel-in-progress`, so a new push cancels the superseded run;
  - `actions/checkout@v7`, `actions/setup-java@v6` (Temurin 21, the tested JDK), `android-actions/setup-android@v4`
    then `sdkmanager "platforms;android-37.2" "build-tools;36.0.0"` (the runner image does not ship 37.2),
    `gradle/actions/setup-gradle@v6` (cache, wrapper validation), `actions/upload-artifact@v7`;
  - pinned to major tags (latest majors on 2026-10-16), not SHAs: these are first-party or widely used actions,
    and major tags keep security fixes flowing without a bot to bump SHAs;
  - the test reports (`app/build/reports/tests/`) are uploaded only when the job fails;
  - `timeout-minutes: 30`, so a hung build cannot eat the free minutes.
- **Why.** It proves what matters on every change (it compiles, the 49 unit tests pass) with one cheap job.
  Lint would fail on existing warnings and is not wanted now; emulator tests are slow and flaky on hosted runners;
  a self-hosted runner on a private repo is a security and maintenance cost for no real need.
- **No secret.** `STM_API_KEY` is optional: without it `BuildConfig.STM_API_KEY` is `""`, which the build and the
  unit tests do not need (the key can also be set at run time from the phone page). The workflow references no
  secret, so nothing can leak into a log.
- **Plan B: agents cannot see the run.** The Engineer reproduces the workflow on the forge from a clean clone of the
  branch, with a `local.properties` holding only `sdk.dir` and no `STM_API_KEY`, running exactly
  `./gradlew assembleDebug testDebugUnitTest`, and records timings, test count and APK size in the issue. storagebits
  confirms the green run on https://github.com/storagebits/almanak/actions before approving the merge. If live
  status is ever needed by agents, a fine-grained read-only token (Actions: read, this repo only) bound as a
  Paperclip secret would do; not needed now.
- **Badge.** At the top of `README.md`, pointing to `actions/workflows/ci.yml/badge.svg`. On a private repo the
  badge only renders for signed-in users with access; that is acceptable.

---

## Pre-release — neutral defaults (ALG-44, 2026-10-18)

Goal: a fresh install anywhere looks like the user's own place at first launch, with nothing that says Montréal or
STM; existing installs keep their settings.

### Place before a city is chosen
- **Context.** `city` defaulted to Montréal. No IP geolocation, no new service, no new dependency.
- **Options.** (1) Android location services: a TV has no GPS and needs a permission prompt. (2) The device time
  zone's representative coordinate from tzdata. (3) Hide weather and sky until set up: an empty first screen.
- **Choice.** (2). `Settings.city` is now nullable; null means "approximate from the time zone" (`settings/Places.kt`,
  `DevicePlaces.kt`). Lookup: the device zone id, then its ICU canonical id (old aliases such as Asia/Calcutta),
  then the first zone of the device region (a TV left on UTC), then Greenwich. The name is ICU's exemplar city in
  the UI language (Londres in French), falling back to the zone id ("Buenos_Aires" → "Buenos Aires").
- **Table.** `zone.tab`, not `zone1970.tab` as the brief suggested: same public-domain data and coordinates, but
  `zone1970.tab` drops 106 per-country zones into another country's zone (Europe/Amsterdam → Europe/Brussels),
  which would name the wrong city. Every zone of `zone1970.tab` is in `zone.tab` (checked with `comm`). 19 KB, raw.
- **Honesty.** The TV settings show "Paris · time zone", the phone page "Paris · from the TV's time zone"; the
  ambient screen itself never named the city, so nothing there claims more precision than it has.
- **Why.** Weather, sunrise/sunset, moon and sky are right to within a few tens of km for most people, offline,
  with no permission and no third party.

### No default network
- **Choice.** `TransitAgency.NONE` (empty id, no URLs) rather than a nullable `agency`: every reader of
  `agency.gtfsUrls` / `realtimeUrl` keeps working, and the phone page's JS never meets a null. `GtfsStore.sync`
  returns success with no URL (no endless WorkManager retries); realtime reports NoFeed; the bus block was already
  hidden without stops. TV settings: Network "Set up on the phone", no Transit row in Sources. Phone page: a "None"
  entry until a network is picked.
- **Licence.** The STM's "CC BY 4.0" in Sources moved from an `id == "stm"` test in the UI to a `licence` field
  of the preset.

### Migration of existing installs
- **Context.** Files written by `main` spell out `city` and `agency` (encodeDefaults), but the Shield's file was
  written on 2026-10-13, before networks existed: no `agency`, no `onboarded`, `stmApiKey`.
- **Choice.** New files carry `"schema": 2`. A stored file without `schema` gets the schema-1 defaults back for
  the fields it lacks (Montréal, STM) before decoding (`SettingsSerializer.decodeStored`). The phone page's PUT
  is decoded without migration: it always sends a whole document.
- **Tests.** A file exactly as `main` writes it, the Shield's key set (values made up), a partial file, and a fresh
  install round trip (`SettingsTest`, `AgencyTest`). Checked on the emulator (install `main`, open settings,
  upgrade) and on the Shield (upgrade in place: Montréal, STM, its stops and live departures unchanged).

### Locale
- **Choice.** The UI language plus the device region when the platform has locale data for that pair, else the
  language's home region: fr + CA → fr-CA, fr + BE → fr-BE, fr + US → fr-FR (no fr-US data), es + MX → es-MX,
  es + FR → es-ES, en + GB → en-GB, en with no region → en-US (`Settings.localeFor`).
- **Why.** It satisfies the brief (fr-CA in Canada, fr-FR otherwise) and extends "same logic for es" without a
  hand-kept country list. English used to be en-US everywhere; en-CA/en-GB differ only in date order details.

### TV city presets
- **Choice.** No fixed list. The cycle is: the time zone's place (city = null), the 6 zone cities nearest to it
  (haversine over `zone.tab`), then a city found on the phone if it is not one of them. Built from the zone, not
  from the chosen city, so the order stays put while the user cycles. Paris gives Brussels, Luxembourg, Jersey,
  London, Guernsey, Amsterdam.
- **Limit.** Zone cities are not always the largest nearby (Jersey, Guernsey; around Vancouver, small BC zones).
  Exact places come from the phone page's geocoder search, which already existed.

### Build-time realtime key
- **Choice.** `realtime.apiKey` + `realtime.keyAgency` (or `REALTIME_API_KEY` + `REALTIME_KEY_AGENCY`): a key for
  one named network, never sent elsewhere. `stm.apiKey` / `STM_API_KEY` still read, as before an STM-only key
  (`BuildConfig.LEGACY_REALTIME_KEY`). Order: phone-page key, then the matching build key, then the legacy key for
  the STM. The rule is a plain function (`realtimeKeyFor`) so it is unit-tested; Gradle only passes raw values.
- **CI.** Still no secret: all three fields default to "".

### Known limits
- The approximate place is recomputed on every settings change and at start, not on a time zone change while the
  app runs (rare on a TV; the next start or settings save picks it up).
- `git grep -i -E "montr[eé]al|stm" app/src/main` also matches `lastModified()` in `Wallpaper.kt` (the letters
  "stM"): a false positive, left as is.

## Pre-release — README, GIF, screenshots, licence (ALG-50, 2026-10-18)

### Capture device
- **Context.** Captures need a non-Montréal setup (Toronto + TTC, English). On the Shield that means replacing
  storagebits's city, network and stops, then restoring them by hand, on his own TV.
- **Options.** Shield (daytime, free) with a settings swap and restore; the Android TV emulator (AVD `tv`, API 34,
  1080p) on the forge.
- **Choice.** The emulator. The brief allows it, it never touches the Shield's settings, and the look is the same
  Compose rendering at 1080p. A Shield capture replaces an image later only if the emulator one looks worse.

### Setup used for the images
- *Superseded by ALG-55 (README media rework, below): no bus, fake QR instead of blur.*
- **Choice.** Time zone `America/Toronto`, English UI, city Toronto (43.6532, −79.3832), network TTC, one stop on
  King St West downtown with the line filter `504` (stop chosen in the TTC GTFS; its name and code appear on no
  image, only the line and its headsigns). Settings written to `files/settings.json` with `run-as` on the debug
  build, `schedule.json` deleted so the filter applies.
- **Realtime.** `bustime.ttc.ca` times out from the forge (port 443), so the images use `fakeRt` and say so in the
  caption; the timetable behind it is the TTC's real one.
- **Minimal theme.** With the TTC's long headsigns the bus block leaves the clock too little width and the "PM"
  label collapses into two bars (a UI debt, REPORT §5). The Minimal still is taken with the bus module off
  (`--es modules weather,almanac`); the GIF shows it for 1.5 s mid-cycle and was kept.

### Privacy of the images
- *Superseded by ALG-55 (README media rework, below): no bus, fake QR instead of blur.*
- **QR codes.** The settings drawer and the welcome card carry a QR with the TV's address and a one-time token.
  `tools/readme_media.sh` box-blurs the QR and the URL line at their fixed 1080p positions (and, in the GIF, only
  while the drawer is open). Every output was checked by eye; the emulator's address is its NAT address
  `10.0.2.15`, blurred anyway.
- **Nothing from `shots/`.** All images were captured fresh into `/tmp` and processed from there. `shots/` is now
  gitignored; the 69 files already tracked stay (no history rewrite).

### GIF
- **Choice.** `adb shell screenrecord` (11 s), then ffmpeg: 12 fps, 960 px wide, a 128-colour palette with
  rectangle diff. Result 0.66 MB, far under the 5 MB limit, so no MP4 plan B. Content: the minute ticking over,
  the drawer opened with OK, the theme cycled Nocturne → Minimal → Paper → Nocturne, BACK.
- **Stills.** Downscaled to 1280×720 (about 0.35 MB each): the README shows them at half width, and five PNGs stay
  under 2 MB in the repo.
- **Tooling.** ffmpeg is not on the forge; a Python venv outside the repo (`imageio-ffmpeg`) provides the binary,
  passed with `FFMPEG=`. Nothing new in the app or its build.

### README and licence
- **Structure.** Kronograf's: title, CI and licence badges, pitch, GIF, "What it shows", screenshots, "Getting
  started", "Configuration", "Architecture", "Project status", "Licence and credits". The device sections keep
  their content, reorganised; the LAN address of the old `adb connect` example became `<tv-ip>`.
- **VPN.** The stm.info note became a generic "VPN and realtime feeds" note.
- **Licence.** MIT, the same text and holder as Kronograf's `LICENSE`. `third_party/DATA.md` now credits every
  network's open-data licence, not only the STM's, and the picture of the day.

## README media rework — fake QR, no bus (ALG-55, 2026-10-18)

storagebits rejected the ALG-50 merge (ALG-54), verbatim: « on peut laisser un fake QR code apparent (qui ne fait
rien) plutôt qu'un QR code flouté où on ne comprend pas trop ce que c'est » and « faire des screens sans les infos
de bus , c'est une feature tres personnelles , dans d'autres futures versions on mettra a jour les screens avec des
features plus populaires ».

### Fake QR instead of blur
- **Context.** A blurred square reads as a grey blot; a visible QR says "scan this with your phone" at a glance.
  The real QR carries the TV's address and a one-time token, so it cannot be shown.
- **Options.** (a) Overlay a generated QR and a re-typeset URL line in ffmpeg at the 1080p positions, as the brief
  suggests; (b) a debug-only launch extra that makes the app show a given address, so the app draws the QR and
  its URL line itself.
- **Choice.** (b): `--es setupUrl http://192.0.2.10:8737/`, read in `MainActivity` only when `BuildConfig.DEBUG`,
  returned by `rememberSetupUrl` in place of the real address (the setup server still runs as usual). The QR has
  exactly the app's plate, ink, margin and size, and the URL line its font and colour, which an overlay would only
  approximate; nothing depends on pixel positions, so the GIF needs no timed overlay. Release builds ignore the
  extra. `192.0.2.10` is in TEST-NET-1 (RFC 5737), reserved for documentation: the code leads nowhere.
  `tools/readme_media.sh` no longer blurs: it scales stills and builds the GIF.

### No bus in the media
- **Choice.** The bus module is off and no network or stop is set (`settings.json` written with `run-as`, Bus
  `enabled: false`, no `agency`), on every theme and in the GIF. The README text still describes the bus feature.
  `fakeRt` and the TTC setup are no longer used for images.
- **Layouts.** Nocturne: default layout, moon fact pinned, odd minute (five-day forecast). Paper: name day, even
  minute (hourly curve). Minimal: weather moved to the right zone and the sun fact, to show that zones move. The
  GIF keeps the ALG-50 sequence (minute tick, drawer, Nocturne → Minimal → Paper → Nocturne, BACK), 14 s
  recording, 1.64 MB.
- **Welcome card wording.** The card's own text says "choose your city, bus stops and language". It is the app's
  copy, not bus data, and was left as is.

## Publishing scan (ALG-56, 2026-10-18)

### Untrack `shots/`
- **Context.** `.gitignore` has listed `shots/` since ALG-50, but the 69 files committed before it stayed tracked,
  so every clone and any export of the tree still carried home-TV captures, against the rule "never commit `shots/`".
- **Options.** (a) Leave them and exclude them only at publishing; (b) `git rm -r --cached shots` now.
- **Choice.** (b). It keeps the local files on the forge (agents' references in this file still resolve there), makes
  `git archive main` a clean export for the public repo, and rewrites nothing. History still holds them (REPORT §8).

### Neutral stop data in tests
- **Context.** Four unit tests and two lines of this file used the real home stop codes and the names of the
  streets at the stop. A clean import of the tree would have published them.
- **Choice.** Replaced by neutral codes (`10001`–`10003`) and street names (`Maple`, `Elm`, `Oak`); the tests check
  the same behaviour (61 tests, 0 failures). Route numbers in tests and placeholders are arbitrary.

### LAN address kept
- **Context.** The Shield's private address is the default device of `tools/shot.sh` and `tools/soak.sh`, and is
  quoted in this file.
- **Choice.** Kept. An RFC 1918 address is not reachable from the internet and says nothing about who or where;
  the agents' tools rely on that default. Reported in REPORT §8 so storagebits can decide otherwise.

### Recommendation: clean public repo over history purge
- **Choice.** Recommend a new repo with one import commit (REPORT §8, Gate 1), not `git filter-repo` plus force push:
  no shared history rewritten, no re-clones, old SHAs in issues stay valid, nothing to miss in a filter. storagebits
  decides at the gate.

## ALG-62: REPORT cleanup (2026-10-18)

- **Forge hostname.** Replaced by "the Almanak forge VM" (the only hit in the tree, `REPORT.md`); no other internal
  host name or VLAN address is tracked. The private history, which keeps it, is not
  published.

## Clean import (ALG-78, 2026-10-19)

- **Clean import.** `algotitanhq/almanak` starts from one commit importing the tree of `algotitanhq/almanak-public`
  @ `aad0d04` (ALG-89: `git archive`, neutral author, no `shots/`). That tree came from `storagebits/almanak`
  `main` (ALG-78, Gate 1 option A): the private history is not published. `almanak-public`'s own first commits carried the Shield's LAN
  address (ALG-88), hence this second squashed import instead of a force push.

## No default device address (ALG-88, 2026-10-19)

- **Choice.** `tools/shot.sh` and `tools/soak.sh` have no default device: `ALMANAK_DEVICE=ip:port` is required, and
  they exit 2 with a usage line when it is missing. Options were a placeholder default (fails later, inside `adb`)
  or a required variable (fails at once, says why). A home LAN address must not be published; the three survey
  lines above now say "the Shield's LAN address". The value stayed in `almanak-public`'s history only; this repo's
  single import commit does not carry it (ALG-89).

## v1.0.0 release: signing, versionCode, release workflow (ALG-111, 2026-10-20)

### Release signing from the environment
- **Context.** v1.0.0 ships as a signed APK on GitHub Releases (no Play Store). Until now `assembleRelease` gave an
  unsigned APK and releases were to be signed by hand (REPORT, Gates 3–4).
- **Options.** (1) A `signingConfig` read from `local.properties`; (2) from environment variables only;
  (3) sign outside Gradle with `apksigner` in the workflow.
- **Choice.** (2): `ALMANAK_KEYSTORE` (a path), `ALMANAK_KEYSTORE_PASSWORD`, `ALMANAK_KEY_ALIAS`,
  `ALMANAK_KEY_PASSWORD`. All four set: the release is signed with that key. None set: it stays unsigned
  (`app-release-unsigned.apk`), with `signingConfig = null` written out so it can never fall back to a debug key.
  Some set, or a keystore path that is not a file: release builds fail (in `preReleaseBuild`) and name the
  missing variables; debug builds and IDE sync are not affected. The base64 keystore (`ALMANAK_KEYSTORE_B64`)
  exists only as an Actions secret: the workflow decodes it into the runner's temp dir and passes the path, so
  Gradle never writes key material under `build/`.
- **Why.** `local.properties` is a file people copy around and already holds other settings; environment
  variables come straight from Actions secrets or a shell that loads the key files. Signing in Gradle keeps the
  same command (`assembleRelease`) for CI and for a local check.

### No API key in a release APK
- The build-time realtime key (`REALTIME_API_KEY`, `STM_API_KEY`, `local.properties`) is overridden with `""` in
  the release build type: only debug builds can carry one. A key in a published APK is a leaked key, and users
  paste their own from the phone page anyway. Checked: with `REALTIME_API_KEY` set, the debug dex contains it and
  the release dex does not.

### versionCode rule
- `versionCode = major * 10000 + minor * 100 + patch`, computed from `versionName` (one source of truth,
  `almanakVersion` in `app/build.gradle.kts`, which must be `MAJOR.MINOR.PATCH`). 1.0.0 → 10000. Minor and patch
  stay below 100 (the build fails otherwise). It always grows with the version, and it is above the 1 of the
  0.1.0 debug builds, so a 1.0.0 build signed with the same key updates them.

### Upload key
- RSA 4096, alias `almanak-upload`, PKCS12, valid until 2054 (10 000 days), held by the maintainer outside the
  repository. Certificate SHA-256
  `40:1B:C0:C3:CE:DD:12:17:E8:2A:1D:D4:1D:78:68:93:F4:79:36:EA:EE:8D:D2:27:16:F8:41:6A:71:5B:69:60`.
- It was generated on the agents' build VM, not on storagebits's computer as REPORT Gate 4 planned: the agents'
  GitHub App cannot set Actions secrets (403), and storagebits takes the key off the VM anyway to back it up
  (ALG-110 decision). The hand-off is the `key-handoff` document of ALG-111. Losing this key means sideloaded
  users can no longer update without uninstalling.
- APK signature: v2 only (minSdk 28 needs no v1; AGP's default).

### Release workflow (`.github/workflows/release.yml`)
- On the Kronograf model: a tag `v*` builds, signs, verifies (`apksigner verify --print-certs`), writes
  `SHA256SUMS` and runs `gh release create` with the CHANGELOG section of that version plus the certificate's
  SHA-256. `workflow_dispatch` on `main` is a dry run: the same tests, build and signing, the APK kept 7 days as an
  artifact, nothing published.
- **Two jobs.** `build` (token `contents: read`) holds the signing secrets; `publish` (`contents: write`, only on a
  tag push) has no secret, no build and no third-party action: it downloads the artifact and runs `gh`.
- **Who can sign.** The secrets live in a GitHub environment `release`, whose deployment rule allows only tags
  `v*` and `main` (set up by storagebits, `key-handoff`). The job also refuses a manual run off `main`, a tag that
  does not equal `v` + `versionName`, and a tagged commit that is not on `main`.
- **Guards.** The CHANGELOG must have a section for the version, dated for a tag; all four secrets must be set (a
  dry run without them fails, it does not build unsigned); `bash -eo pipefail` on every step.
- **Secret handling.** Unit tests run first, without secrets. Secrets reach only the decode and build steps, as
  `env:`; the build uses `--no-daemon` so no daemon keeps the passwords; the keystore file is deleted in an
  `always()` step. Checkout without persisted credentials.
- **No cache.** `setup-gradle` with `cache-disabled` and `--no-build-cache`: a signed APK is built from source,
  never from task outputs another run saved (about 70 s more).
- **Pinned actions.** In this workflow every action is pinned to a commit SHA (version in a comment), unlike
  `ci.yml`'s major tags: this job holds the signing key. Bump them by hand when a release needs it.
- One `concurrency` group for all releases, never cancelled.

### README and links point to the public repo
- The README's badges, clone URL and install links point to `algotitanhq/almanak` (the public release mirror,
  ALG-110 decision), since the README is written for the public. In the private repo the badge shows the mirror's
  CI.

### User-Agent
- Wikimedia requests sent `Almanak/0.1`; they now send `Almanak/<versionName>` from `BuildConfig`.

### Signer pinned in the release workflow
- **Context.** Verify printed the certificate but accepted any key: a keystore swapped in the secrets would have
  published under another key, and sideloaded users would not be able to update (QA, ALG-111).
- **Choice.** The upload certificate's SHA-256 is committed in `release.yml` (`EXPECTED_SIGNER`, public); Verify
  fails unless the APK has exactly one signer with that digest. Changing the key now takes a reviewed commit, not
  only a secret.

### Export to the release mirror: `tools/publish.sh`
- **Context.** ALG-110 decision: work in `storagebits/almanak` only; `algotitanhq/almanak` gets one scanned export
  commit per version, just before the tag. The private history must never reach it.
- **Choice.** The script takes the tree of `git archive HEAD` (tracked files only: ignored `shots/`,
  `local.properties`, `build/` cannot slip in), scans it, and only then commits it on top of the mirror's `main`
  as "Almanak vX.Y.Z" with a neutral author (`Almanak contributors`, see ALG-113) and a `Source-Commit:` trailer. Any hit, or a
  scan that does not finish, exits 1 before the commit; hits print rule and file:line, never the value. Default
  is a dry run; `--push` pushes `main` only, from a commit of `origin/main` with a dated CHANGELOG section. The tag
  stays a separate, manual step (the release workflow then checks it against `versionName`).
- **Two scanners.** `gitleaks dir` for secrets, run with a config and ignore file of the script's own (a
  `.gitleaks.toml` or `.gitleaksignore` in the tree would otherwise silence it, and is itself a forbidden path).
  `tools/publish_scan.py` for the rest, after a security-auditor pass found the first grep version fail-open
  (file names with `:`, an IP right after an allowed one, symlinks, UTF-16 and binaries, case of key files): it
  walks the tree in Python, flags every symlink, reads UTF-8/UTF-16 text and the printable strings of binaries,
  inflates PNG text chunks and reads EXIF tags (GPS, device, owner are hits; pixel size is not: the launcher PNGs
  carry one; TIFF, WebP and HEIC EXIF too). Archives are not looked into, so any archive but the Gradle wrapper
  is a hit. gitleaks still reads a `.gitleaksignore` in the scanned tree whatever its flags say: such files are
  reported, then removed from the scanned copy so gitleaks runs on its default rules. Short VM names are matched in text only: a letter and two digits occur at random in
  compressed images. Its own patterns use character classes so the file does not match itself. The owner's own hostnames and machine
  names are not in the tree: `publish.sh` reads them from a private patterns file outside the repo (ALG-118) and
  stops when it is missing.
- **Allowed values.** `10.0.2.x` (the Android emulator's fixed NAT address, the same everywhere), `192.0.2.x`
  (TEST-NET-1, not private: the README's example), `release@almanak.invalid`, GitHub no-reply and example.com
  addresses. Anything else that looks like an e-mail is a hit.
- **Commits made on the mirror.** The export replaces the mirror's tree, so an edit made there would be lost. The
  last export is the newest commit whose `Source-Commit` names a commit of this repo with the same tree (a subject
  can be copied, a matching tree cannot); the mirror's root commit stands for the first import. Commits after it
  stop the script, listed; once they are ported here, `--ported <mirror sha>` lets it go on. storagebits's README
  commit `8ab7361` is ported in this PR, with the import's scrubs (ALG-88, ALG-89), which existed only on the
  mirror.

## v1.0.0 publish prep (ALG-113, 2026-10-20)

### CHANGELOG date
- **Context.** `publish.sh --push` and the release workflow (on a tag) refuse an undated `1.0.0` section; the
  publish happens in its own issue, after storagebits's release card, so its day is not known yet.
- **Choice.** Date the section now (`2026-10-20`, the day this prep merges). The rule: the date is the day the
  tag `v1.0.0` is pushed. If the publish happens on another day, the publish issue fixes the date in a one-line
  commit before the export (the export then carries the right date; nothing is re-tagged).

### Export author on a domain nobody can claim
- **Context.** Release-gate NIT (ALG-111): the export commits were authored with a no-reply address on a `.dev`
  domain the organization does not hold. Whoever registers that domain could verify the address on a GitHub
  account and have every export commit shown as theirs.
- **Choice.** `Almanak contributors <release@almanak.invalid>`: `.invalid` is reserved (RFC 2606) and can never be
  registered, so the address can never be verified. It is the only project address the scanner allows (besides GitHub no-reply and `example.*`);
  the first import keeps its old author in the mirror's history (not rewritten: it is public history and harmless).

### gitleaks findings in the export scan's output
- **Context.** Release-gate NIT (QA, ALG-111): on a planted token the `HIT [gitleaks]` block listed no finding (the
  inline CSV parser printed nothing; the verdict, exit 1, was right). Not reproduced here with gitleaks 8.30.1, so the
  cause on the reviewer's VM is unknown: the fix removes the fragile parts instead of guessing.
- **Choice.** gitleaks writes a JSON report (stable field names across 8.x); `publish_scan.py --gitleaks-report`
  prints one `HIT [gitleaks:<rule>] file:line` per finding, path relative to the scanned tree, never the secret
  (`--redact` stays). When the report is missing, unreadable (even halfway) or empty although gitleaks failed, the script says so
  and prints gitleaks' own (redacted) log, so an empty list can no longer look like a detail.
