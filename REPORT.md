# Almanak — report

State of `main` at `92d006a` (2026-10-16). Every statement points to `README.md`, `DECISIONS.md`, the git history or a
measured run. Captures are named by path; nothing new was captured for this report.

## 1. Summary

Almanak is an ambient almanac for Android TV / Google TV, designed to be left on: time, date, weather, next bus,
sun and moon (`README.md`). It is a single-module Kotlin / Compose for TV app (`app.almanak`, minSdk 28), built and
checked on an NVIDIA Shield (Android 11, API 30) (`DECISIONS.md` §0, §4).

- The seven planned vertical slices are committed (2026-10-08), followed by refinements up to 2026-10-13
  (`git log`).
- Since 2026-10-16, GitHub Actions builds the debug APK and runs the unit tests on every push and pull request.
  `main` is green: 49 unit tests, 0 failures.
- Not finished: the 24 h screensaver soak and a real-time bus check with a working STM key (sections 4 and 5).

## 2. What works

Each slice lists its commit, its `DECISIONS.md` section, its unit tests (under `app/src/test/java/app/almanak/`)
and its captures (under `shots/`). Captures were taken on the Shield when the slice was built.

| Slice | Commit | What it does | Evidence |
|---|---|---|---|
| 1. Living skeleton | `8cf509a` | Fullscreen clock and date (Fraunces + Instrument Serif), minute ticks, 12/24 h from the system | DECISIONS "Slice 1"; `ClockFormatTest.kt`; `shots/s1-editorial-1.png`, `shots/s1-swiss-1.png` |
| 2. Weather | `a6be18a` | Current conditions, 12-hour temperature line alternating with 5 days, drawn icons, offline cache with an "as of" stamp | DECISIONS "Slice 2"; `WeatherSourceTest.kt`; `shots/s2-band-4.png`, `shots/s2-final-fr-offline.png`, `shots/s2-glyphs-2.png` |
| 3. Bus | `a6f0c9f` | Static GTFS schedule on the device, GTFS-realtime merged into it, Mobility Database mirror as fallback | DECISIONS "Slice 3"; `TransitTest.kt`; `shots/s3-final-2.png`, `shots/s3-stack-3.png` (realtime styling via the debug hook `fakeRt`) |
| 4. Ephemeris | `4778b36` | Rotating line: sun times, moon phase (Meeus), day/week, name day, "on this day" | DECISIONS "Slice 4"; `EphemerisTest.kt`; `shots/s4-caption-*.png`, `shots/s4-final.png` |
| 5. Wallpapers, themes, burn-in | `af66845` | Three themes, generative skies from real sun times, Wikimedia POTD or a custom URL, measured adaptive scrim, ≤7 dp drift | DECISIONS "Slice 5"; `SkyPhaseTest.kt`; `shots/s5-final.png`, `shots/s5-theme-minimal.png`, `shots/s5-theme-paper.png`, `shots/s5-bright-local-2.png` |
| 6. Screensaver | `3be8abd` | `AlmanakDream` hosts the same UI; 3 dream → key → wake cycles with no crash and no ANR | DECISIONS "Slice 6"; `shots/s6-dream-1.png`, `shots/s6-shield-settings-1.png` |
| 7. Personalisation | `bcc3631` | Settings drawer on the TV (DPAD), module grid, phone setup page via QR with a pairing token | DECISIONS "Slice 7"; `SettingsTest.kt`, `SetupServerTest.kt`; `shots/s7-settings-drawer-3.png`, `shots/s7-phone-applied-2.png`, `shots/s7-grid-swapped-2.png` |

Refinements after slice 7. These are described in `README.md` and the commit messages; `DECISIONS.md` has no entry
for them.

| Commit | What it adds | Evidence |
|---|---|---|
| `7b3ba4d` | README: release build and soak script documented | `shots/s7-release-build.png` |
| `90e10b0` | Soak run output | `shots/soak/soak.csv`, `shots/soak/dream-start.png` |
| `a87c31b` | Spanish UI; on-this-day and city search follow the UI language | `ClockFormatTest.kt`, `EphemerisTest.kt` changed |
| `40b0359` | On this day: drop notes about the feed's picture | `EphemerisTest.kt` changed |
| `2e9e503` | Guest Wi-Fi join QR shown on demand with the remote arrows | `SettingsTest.kt`, `SetupServerTest.kt` changed |
| `c30104c` | Bus: one block per route and direction, compact lines past 3, up to 6 stops | `TransitTest.kt` changed; `shots/bus-6.png` |
| `1af25c7` | Bus glyph heading the bus block | `shots/bus-icon.png` |
| `8b06bed` | Next timetable departure when nothing comes within 2 h | `TransitTest.kt` changed; `shots/bus-later.png` |
| `61f85d8` | Phone page in English, French and Spanish; Spanish santoral | `EphemerisTest.kt` changed; `shots/santoral-es.png` |
| `2da3929` | First run: no stops in the code, a "scan to set up" card | `shots/welcome.png` |
| `e1877b9` | Transit network as a setting (STM, RTC, OC Transpo, TTC, STS or any GTFS URL); Sources in settings | `AgencyTest.kt`; `shots/network.png`, `shots/settings-sources.png` |

CI (2026-10-16): `bc1bb62`, merged into `main` by `92d006a`. `.github/workflows/ci.yml` runs
`./gradlew assembleDebug testDebugUnitTest` on `ubuntu-latest` with no secret. The README has a badge. Evidence:
DECISIONS "CI" and [run 38928000679](https://github.com/storagebits/almanak/actions/runs/38928000679) on `main`
(success).

### Neutral defaults (ALG-44, 2026-10-18)

- A fresh install has no city and no network. Weather, sun, moon and sky use the TV's time zone city (tzdata
  `zone.tab`), shown as "Paris · time zone"; the bus block stays hidden. Checked on the Android TV emulator
  (API 34) with Europe/Paris (weather fetched at 48.87, 2.33; sunrise 8:14, sunset 18:56) and America/Vancouver
  (49.27, −123.12; sunrise 7:37, sunset 18:17). Captures are attached to the issue, not committed.
- Upgrades keep their settings: emulator (`main` build, then this branch) and the Shield (2026-10-18 19:53 UTC,
  file without `agency`): Montréal, STM and its stops unchanged.
- Locale follows the device region; TV city presets are the zone cities nearest the time zone's place; the
  build-time key is `realtime.apiKey` for one named network, `stm.apiKey` still honoured. 61 unit tests.

### README media (ALG-50, reworked in ALG-55, 2026-10-18)

- `docs/screens/`: five 1280×720 stills (Nocturne, Paper, Minimal, settings drawer, first launch) and a 13.7 s GIF
  (1.64 MB, 960 px, 12 fps), all from the Android TV emulator, Toronto, bus module off and no network set.
- The setup QR codes are sharp and decode to `http://192.0.2.10:8737/` only (RFC 5737 documentation address), drawn
  by the app itself through the debug extra `--es setupUrl`. Checked with `zxing-cpp` on every still and on one
  frame per second of the GIF: the drawer and welcome QRs decode to the placeholder, no other code is found.
- Every image checked by eye: no bus line, stop or headsign, no real address, hostname or SSID.

## 3. Measures

"Build VM" means an Ubuntu VM with 8 vCPUs, JDK 21.0.12 and the Android SDK preinstalled. It used a clean clone with a
`local.properties` holding only `sdk.dir`, and `STM_API_KEY` unset.

| Measure | Value | Source |
|---|---|---|
| Cold build: `assembleDebug testDebugUnitTest`, empty Gradle home | **114 s** | Build VM, CI branch at `bc1bb62`, 2026-10-16 (slice 1 reproduction) |
| Same with the Gradle cache, daemon stopped | 13 s (26 tasks from cache) | Same run series |
| Full re-execution: `assembleDebug testDebugUnitTest --rerun-tasks`, warm Gradle home | 32.9 s | Build VM, `main` at `92d006a`, 2026-10-16 |
| Unit tests | **49 tests, 0 failures, 0 errors, 0 skipped**, in 8 test classes | JUnit XML in `app/build/test-results/testDebugUnitTest/`, `main` at `92d006a` |
| Unit test time | 0.76 s summed over the 8 suites; 2.5 s wall for `testDebugUnitTest --rerun` with compiled classes | Same run |
| Debug APK size | **39,708,095 bytes (39.7 MB)** | `app/build/outputs/apk/debug/app-debug.apk`, same on `bc1bb62` and `92d006a` |
| Release APK size | ~4 MB (R8-minified, unsigned) | `README.md`; not re-measured |
| GitHub Actions, CI branch, run 38918171310 | job 4 min 47 s; Gradle step 4 min 20 s | Actions API, 2026-10-16 |
| GitHub Actions, `main`, run 38928000679 | job 5 min 42 s; Gradle step 4 min 55 s ("BUILD SUCCESSFUL in 4m 54s", 43 of 44 tasks executed) | Actions API and job log |
| SDK setup on the runner | `setup-android` 17 s, `sdkmanager` for `android-37.2` + `build-tools;36.0.0` 3 s | Run 38928000679 |
| Gradle cache on Actions, first save | Run 38928000679 logs "Gradle User Home cache not found". Its post step (16 s) saved the first cache entries | Job log of run 38928000679 |
| Gradle cache on Actions, effect | Run 38929622344 (branch `docs/report`, docs-only change) restored those entries. Job **1 min 7 s**; Gradle step 29 s ("BUILD SUCCESSFUL in 28s", 26 of 44 tasks from cache). That is against 5 min 42 s / 4 min 55 s uncached. `testDebugUnitTest` came FROM-CACHE: the inputs were unchanged, so the tests did not execute | Actions API and job log, 2026-10-16 |

Screensaver memory (`shots/soak/soak.csv`, 2026-10-09): 6 samples between 02:33Z and 21:58Z, against about 96
expected at 15 min intervals over 24 h. The dream ran for the first 3 samples (45 min). Total PSS was 143–157 MB in
the 5 samples that have values, and crash lines stayed at 0. No `dream-end.png` was written, so the 24 h run did not
complete.

## 4. Not done and why

- **24 h screensaver soak.** Planned in `DECISIONS.md` §1. In the CSV, the dream stopped after 45 min, and only 6
  samples were taken. No record says why. The Shield was out of scope for the CI mission.
- **Real-time bus against the live STM API.** The key was rejected (`400 Invalid API Key`, DECISIONS §3, 2026-10-08).
  The realtime path is unit-tested and its display was checked with the `fakeRt` hook. No later entry records a valid
  key.
- **Lint in CI.** Left out on purpose. DECISIONS "CI" says lint would fail on existing warnings. The README still
  lists `./gradlew testDebugUnitTest lintDebug` as the local loop.
- **Instrumented / UI tests, Play Store.** Out of scope (DECISIONS "CI"). Signing and the release job came with
  v1.0.0 (section 9).
- **Google TV.** The README gives the adb steps for the screensaver. No capture or decision entry records a run on a
  Google TV device.

## 5. Debts and risks

- **Personal data in the repository. This blocks any future public mirror.**
  - A commit subject in slice 3 contains personal data.
  - 69 files under `shots/` (untracked in ALG-56, still in history) (68 PNG captures plus `soak/soak.csv`) were taken on a home device.
  - `DECISIONS.md`, `tools/shot.sh` and `tools/soak.sh` contain a LAN device address (`README.md` has none since
    ALG-56).
  - `DECISIONS.md` names the configured stop identifiers (removed from the tree in ALG-56, still in history).
  - Most commits carry a personal author email.

  History is not rewritten, under the shared-branch rule. A public mirror would need a separate, cleaned export,
  decided by storagebits.
- **Decision log gaps.** `DECISIONS.md` has no entries for the 11 commits after slice 7 (section 2), and it still
  says "max 4" stops, while `c30104c` raised the limit to 6.
- **Device-only failures.** Several bugs appeared only on the Shield: a Java 9 API crash on API 30 (slice 4), a
  dream lifecycle crash (slice 6), and BACK needing two presses (slice 7). CI runs JVM unit tests only, so this class
  of bug still needs a device pass.
- **Realtime may be filtered.** The test TV runs a VPN, and stm.info returned HTTP 441 through it. The static schedule
  falls back to a mirror, but realtime has none (DECISIONS "Slice 3", README "VPN on the TV").
- **Shield settings changed in slice 6.** The screensaver was set to Almanak for the soak test. The before and after
  values are recorded in DECISIONS "Slice 6", and no entry records a revert.
- **CI.**
  - Actions are pinned to major tags, not SHAs (a choice recorded in DECISIONS "CI").
  - `push` has no branch filter, so a pull request from a branch of this repo runs twice (QA note).
  - `org.gradle.caching=true` means an unchanged rerun takes tests from the cache instead of executing them.
- **Fresh toolchain.** The build uses AGP 9.4.1, Kotlin 2.4.10 and compileSdk 37.2. The runner image does not ship
  37.2, so CI installs it on every run (DECISIONS §4, "CI").

- **Minimal theme with long headsigns** (found in ALG-50). With the TTC's headsigns ("East - 504A King towards
  Distillery") the bus block takes the width the Minimal clock needs and its "PM" label collapses into two vertical
  bars. Nocturne and Paper are fine. Not fixed in a docs slice. The README media show no bus at all since ALG-55,
  so the defect is not visible there.
- **TTC realtime unverified.** `bustime.ttc.ca` timed out from the forge VM (ALG-50). Whether the feed answers from
  a home network is not checked.
- **README media without the bus** (ALG-55, storagebits's call). The images show clock, weather, almanac, themes and
  setup only. When a more popular feature lands, re-capture `docs/screens/` with the steps in DECISIONS "README
  media rework" and `tools/readme_media.sh`.

## 6. What storagebits must do himself

- **STM key.** Publish a working key on the STM developer portal (Save, then Publish; README "Configuration"). Then
  either put it in `local.properties` / `STM_API_KEY` for builds, or paste it in the phone setup page.
- **VPN on the test TV.** Exclude Almanak through split tunnelling (README "VPN on the TV"), or accept
  scheduled-only times.
- **Release signing.** Take the upload key off the VM, back it up and set the four Actions secrets (section 9,
  ALG-111 `key-handoff` document).
- **Shield screensaver.** Decide whether to keep Almanak as the screensaver or restore the values recorded in
  DECISIONS "Slice 6". Agents touch the Shield only when an issue says it is free.
- **Public mirror.** Decide if one is ever wanted. Only he can approve publishing and choose how to clean it (see
  section 5).

## 7. Next steps

1. Run the 24 h soak (`tools/soak.sh 24 15`) when the Shield is free, and record start and end memory and crashes.
2. Verify realtime departures on the device once a valid STM key exists.
3. Add `DECISIONS.md` entries for the post-slice-7 refinements, and fix the stop limit.
4. Fix the existing lint warnings, then add `lintDebug` to CI.
5. Optional: limit `push` to `main` in `ci.yml` to avoid double runs on pull requests.
6. Fix the Minimal theme clock squeeze with long headsigns (section 5).

## 8. Publishing (ALG-56, 2026-10-18)

State of the repository before storagebits's publishing gate. Nothing below has been done: no visibility change, no
tag, no release, no history rewrite, no force push. Each gate decision is storagebits's (T2, T3 for the key).

### What is ready

- **Defaults.** First run shows no city, stop or network from Montréal or the STM (ALG-44, section 2).
- **README.** Description, GIF and five stills in `docs/screens/` (fake QR, no bus data; ALG-50, ALG-55), build
  and configuration instructions, CI and licence badges.
- **Licence.** `LICENSE` (MIT); third-party data and fonts listed in README "Licence".
- **CI.** `.github/workflows/ci.yml` runs on `push`, `pull_request` and `workflow_dispatch`, with
  `permissions: contents: read` and no `secrets.` reference. It builds the debug APK and runs the unit tests. The
  README badge points to this workflow. `main` is green at `e9aa0a4` (run 39169879341, push event).
- **Tree clean-up in this slice.** The 69 files under `shots/` are no longer tracked (`git rm --cached`; the local
  files stay on the forge, `.gitignore` already had `shots/`). The home stop identifiers and nearby street names
  were replaced by neutral values in four unit tests and two `DECISIONS.md` lines (61 tests, 0 failures).

### Scan results (gitleaks 8.30.1, `--redact`)

| Scan | Scope | Findings |
|---|---|---|
| `gitleaks detect --redact --log-opts=--all` | 24 non-merge commits, all refs (~530 KB of patches) | 0 |
| `gitleaks detect --no-git --redact` | working tree, including untracked `build/` and `shots/` (~10 MB) | 0 |

A targeted search for `stm.apiKey=` lines added in history found three, all placeholders (README and
`DECISIONS.md`, commits `8cf509a`, `7b3ba4d`). No API key, password or token is in the history.

### Privacy scan of history

The private history holds personal data (old captures, the home stops, a personal e-mail) and is not published:
the public repo gets scanned exports of the tree only (Gate 1 option A, then `tools/publish.sh`).

### Gate 1: clean public repo or history purge

| | A. Clean public repo (recommended) | B. History purge |
|---|---|---|
| What | New repo, one commit importing the current tree (without `shots/`) | `git filter-repo` on a copy, then force push to `storagebits/almanak` |
| Exposure | None of the history above | Only what the filters catch; a commit subject, the author e-mail and every past capture each need a rule |
| Risk | Low: the private repo stays untouched as the archive | Rewrites shared `main`; every clone (forge, Paperclip workspaces) must be re-cloned; open PR refs and old SHAs in issues and `DECISIONS.md` break; GitHub keeps cached views of old commits until support purges them |
| Loss | Public history starts at 0.1.0 | None for the public, but SHAs change |

Recommendation: **A**. The history is short (28 commits), its value is in `DECISIONS.md` and `REPORT.md`, which
are kept, and A changes nothing that agents or issues point to.

Steps for A (storagebits, or an agent once a card is accepted; creating a repo is a T2 action):

1. Merge this branch into `main` (merge issue after QA).
2. Create an empty **private** repo, e.g. `storagebits/almanak-public` (name is storagebits's choice; no README, no
   licence, so the import is the first commit). Grant it to the GitHub App `algotitanhq-agents` if agents
   should work there.
3. Import a clean tree, with no old history and a neutral author:
   ```
   git clone https://github.com/storagebits/almanak.git /tmp/almanak-src
   git -C /tmp/almanak-src archive --format=tar main | (mkdir /tmp/almanak-pub && tar -x -C /tmp/almanak-pub)
   cd /tmp/almanak-pub
   test ! -e shots && git init -q -b main && git add -A
   git -c user.name="Almanak" -c user.email="<GitHub noreply address of the publishing account>" \
       commit -q -m "Almanak 0.1.0"
   gitleaks detect --redact && gitleaks detect --no-git --redact
   git remote add origin https://github.com/storagebits/almanak-public.git && git push -u origin main
   ```
4. Update the CI badge URL in `README.md` if the new repo has another name (one line), push, check the Actions
   run is green.
5. Keep `storagebits/almanak` private as the working and archive repo, or archive it once the public repo
   becomes the working one (storagebits's choice).

Steps for B, if storagebits prefers it (never on the shared clone):

1. Merge this branch; announce a freeze (no agent pushes); close or merge open PRs.
2. `git clone --mirror https://github.com/storagebits/almanak.git /tmp/almanak-purge && cd /tmp/almanak-purge`;
   keep a second untouched mirror as the backup.
3. `git filter-repo --path shots/ --invert-paths`, then `--replace-text <file>` (stop identifiers, street names,
   LAN address), `--replace-message <file>` (same file) and `--mailmap <file>` (personal e-mail to a noreply
   address).
4. Re-run both gitleaks scans and the privacy greps on the result.
5. `git push --force --mirror` (force push to `main`: needs storagebits's accepted card).
6. Re-clone every copy on the forge and in Paperclip workspaces; old SHAs in issues and docs no longer resolve.

### Gate 2: visibility

1. Run the scans again on the repo to publish (they must still show 0 findings).
2. GitHub, repo → Settings → General → Danger Zone → Change repository visibility → Make public → confirm the
   repo name.
3. Check: the README renders with the GIF; the CI badge shows "passing" for an anonymous visitor (private
   window); Settings → Actions → General → "Approval for running fork pull request workflows": "Require approval
   for all external contributors".

### Gate 3: first tag and GitHub release

*Superseded by section 9 (ALG-111): a tag runs `.github/workflows/release.yml`.*

1. Decide the version: `versionName = "0.1.0"`, `versionCode = 1` (`app/build.gradle.kts`).
2. Build and sign the release APK on the machine that holds the key (Gate 4):
   `./gradlew assembleRelease`, then
   `apksigner sign --ks <keystore> --out almanak-0.1.0.apk app/build/outputs/apk/release/app-release-unsigned.apk`,
   and `apksigner verify --print-certs almanak-0.1.0.apk`.
3. Test it once on the Shield (installs beside or over the current build, see Gate 4).
4. Tag and release: `git tag -a v0.1.0 -m "Almanak 0.1.0" && git push origin v0.1.0`, then
   `gh release create v0.1.0 almanak-0.1.0.apk --title "Almanak 0.1.0" --notes-file <notes>` (or GitHub →
   Releases → Draft a new release → choose the tag → attach the APK → Publish).
5. Attach `sha256sum almanak-0.1.0.apk` in the notes.

### Gate 4: release signing key (T3, storagebits)

*Superseded by section 9 (ALG-111): the upload key was generated on the Almanak forge VM, and CI signs releases.*

- **Today.** The repo has no signing config; the APK on the Shield is signed with the forge's debug keystore
  (`~/.android/debug.keystore` on the Almanak forge VM), which must never be used for a public release.
- **Create.** On storagebits's own computer:
  `keytool -genkeypair -v -keystore almanak-release.jks -alias almanak -keyalg RSA -keysize 4096 -validity 10000`.
- **Where it lives.** His password manager (keystore file as an attachment, plus both passwords) and one offline
  backup. Never in a repo, issue, Paperclip document or the forge. Losing it means no update path for users.
- **CI signing later (optional).** Only as GitHub Actions secrets (keystore base64 and passwords) on a separate
  release workflow triggered by tags; this changes "no secret in CI", so it is its own decision.
- **Shield.** Android refuses an update signed by another key: switching the Shield to the release build means
  uninstalling the debug-signed app (settings are lost) — storagebits does it himself, agents never uninstall.

## 9. v1.0.0 release build (ALG-111, 2026-10-20)

Branch `release/v1.0.0`. Choices in DECISIONS "v1.0.0 release". Nothing is tagged or published here: the export to
the public repo and the tag are a separate issue, after storagebits's release card.

### What works, with evidence

- **Signed release build.** `assembleRelease` with the four `ALMANAK_*` variables gives `app-release.apk`, signed
  with the new upload key; with none it gives `app-release-unsigned.apk` (`apksigner verify`: "DOES NOT VERIFY",
  no signature at all); with only some, `assembleRelease` fails with "Release signing is half configured: missing
  […]" while `assembleDebug` still builds. With `REALTIME_API_KEY` set, the key is in the debug dex and not in the
  release dex.
- **`apksigner verify --print-certs`** on the APK built from `97c085f` on the forge VM:

  ```
  Verifies
  Verified using v2 scheme (APK Signature Scheme v2): true
  Signer #1 certificate DN: CN=Almanak, O=ALGOTITAN
  Signer #1 certificate SHA-256 digest: 401bc0c3cedd1217e82a1dd41d786893f47936eaee8dd22716f8416a715b6960
  Signer #1 key algorithm: RSA
  Signer #1 key size (bits): 4096
  ```

  `aapt2 dump badging`: `versionCode='10000' versionName='1.0.0'`.
- **Installs over nothing and runs.** On the Android TV emulator (API 34, 1080p), with no Almanak installed:
  `adb install` → Success, first launch shows the welcome card, the clock, Open-Meteo weather and "on this day";
  the settings drawer opens with OK; the picture of the day loads with its credit; the setup server answers 403
  without the pairing token. No `FATAL` or `AndroidRuntime` line in logcat. Capture `shots/alg111-emulator-release.png`
  (local, not committed).
- **R8 does not break realtime.** The GTFS-realtime parser uses full protobuf, which R8 renames. On the emulator,
  `dalvikvm` ran the installed minified APK's own `RealtimeSource.parse` (found through `mapping.txt`) on a live
  MBTA TripUpdates feed (608 KB): 1 215 trips parsed, with stop sequences, times and SKIPPED flags. No keep rule
  was needed.
- **On the Shield (Android 11).** The same R8 release, signed with the forge's debug key so it updates the
  installed app in place, was installed on 2026-10-19 (`versionName 1.0.0`, `versionCode 10000`):
  settings kept, clock, weather, the bus block with scheduled times (no valid STM key, section 4) and "on this
  day" in Spanish. Capture `shots/alg111-shield-r8.png` (local only: it shows a configured stop's lines). The
  build signed with the new key cannot go on the Shield until storagebits uninstalls the debug-signed app (agents
  never uninstall): Android refuses an update signed by another key.
- **Unit tests.** `./gradlew assembleRelease testDebugUnitTest`: 61 tests, 0 failures.
- **Release workflow.** `.github/workflows/release.yml`, see DECISIONS. Not run yet: the secrets live only on the
  public repo after storagebits's hand-off, and a `workflow_dispatch` dry run needs the file on the default branch.
  `actionlint` 1.7.12 reports nothing; its shell steps (version read, CHANGELOG extraction, date check) were run
  locally against this tree.
- **Signer pinned.** The workflow's Verify step fails unless the APK has exactly one signer whose certificate
  SHA-256 is the upload key's (`EXPECTED_SIGNER`), and no rotation signer or source stamp. Its shell was run locally: the new-key APK passes; the same APK
  re-signed with a debug key fails.
- **Export script.** `tools/publish.sh` builds the mirror's export commit from `git archive HEAD` and scans that
  tree with gitleaks (its own config: the tree cannot silence it) and `tools/publish_scan.py` (forbidden paths such
  as `shots/` and key files, private/CGNAT/link-local IPs, internal hostnames and home paths, personal e-mails,
  symlinks, binary strings, PNG text chunks, EXIF GPS and device tags). On this branch: scan clean, one commit
  prepared on top of the mirror's `8ab7361` with only this PR's files changed, nothing pushed. In a scratch clone
  with a local mirror, each planted case exits 1 before any commit with a `HIT` line (rule and file:line): a
  GitHub token, the same token with a `.gitleaks.toml` allow-all and with an inline allow marker, a LAN address
  after an allowed emulator address, a file name containing `:`, a home path without a trailing slash, a symlink to a
  keystore, a UTF-16 file, `Upload.JKS`, `id_ed25519`, `keystore.properties`, `upload.p8`, look-alike no-reply
  e-mails, a PNG whose compressed comment holds a hostname; a dirty tree exits 1 too. The mirror guard stops on
  a commit made on the mirror, also when its subject imitates an export ("Almanak v1.0.0 docs"), and accepts an
  export it made itself (same tree: "nothing to export"). storagebits's README commit on the mirror and the import's
  scrubs (no default `ALMANAK_DEVICE`, no LAN address in DECISIONS) are ported here, so the export keeps them.
- **Docs.** `CHANGELOG.md` (Keep a Changelog; the workflow refuses an undated section on a tag; dated in ALG-113). README: *Install* from Releases (adb or a file manager, checksum and certificate check,
  updates), *Privacy*, links to the public repo. All six images in `docs/screens/` re-checked: every QR code
  (the two PNGs and 73 GIF frames) decodes to the placeholder `http://192.0.2.10:8737/`; no bus, stop, LAN address,
  host name or SSID.

### Measures

| | |
|---|---|
| Signed release APK | 4 282 853 bytes (4.1 MB), R8-minified |
| Unsigned release APK | 4 270 565 bytes |
| `clean assembleRelease --no-build-cache`, warm daemon, forge VM | 73 s (2.6 s from the build cache) |

### Open items (before the release card)

- **New-key APK on the Shield (acceptance criterion, not met yet).** Met on the emulator only (above). Android
  refuses the new key over the installed debug-signed Almanak, and agents never uninstall. storagebits uninstalls
  Almanak on the Shield (settings are lost), then an agent installs the new-key release, captures it running and
  adds the capture and `apksigner` line here; the release card waits for that check.
  Status 2026-10-20 (ALG-113): still open, the only one. storagebits's card on ALG-110 (key backup, Shield
  uninstall) is not answered yet.
  Status 2026-10-20 (ALG-118): **closed.** After storagebits's key hand-off, the public repo's Release workflow
  ran on `workflow_dispatch` off the export `e6c1d36` (run 39495633615, success, publish job skipped as designed).
  Its artifact `almanak-1.0.0.apk` (4 282 853 bytes) matches its `SHA256SUMS`, and `apksigner verify
  --print-certs` gives v2 only, `CN=Almanak, O=ALGOTITAN`, SHA-256 `401bc0c3…a715b6960`. On the Shield, at 17:10
  Montréal (nothing playing), the debug-signed Almanak (signer `72cba87b…`) was uninstalled, and this APK was
  installed and launched: `versionName 1.0.0`, `versionCode 10000`; the APK pulled back from the device is
  byte-identical to the artifact and has the same signer; `MainActivity` stays in the foreground, showing the
  clock, the date, sunrise and sunset, the weather with its hourly strip, and the welcome card with the setup QR
  code (a fresh install: settings gone, as expected). Capture `shots/alg118-shield-release.png` (local only: its
  QR code holds the Shield's LAN setup address).

### Publish prep (ALG-113, 2026-10-20)

- PR #5 merged into `main` at its approved head `0d94a93` (merge `1f64729`).
- Release-gate NITs fixed: export author `Almanak contributors <release@almanak.invalid>` (the only project address
  the scanner allows; the old `.dev` one is a hit); a gitleaks hit lists `HIT [gitleaks:<rule>] file:line` from its
  JSON report, never the secret. Checked in a scratch worktree: a planted `ghp_` token in `docs/x/le ak.txt` gives
  `HIT [gitleaks:github-pat] docs/x/le ak.txt:1`, exit 1, nothing committed; a missing or empty report prints
  "unreadable"/falls back to gitleaks' redacted log.
- **`tools/publish.sh` dry run** on the head of `release/v1.0.0-prep` (its tree is the new `main`'s after the
  merge): scan clean; the mirror guard stops on storagebits's ported `8ab7361` as designed; with
  `--ported 8ab7361…` exit 0, export commit prepared on `8ab7361` (10 files changed),
  nothing pushed. Mirror checked: public, `main` = `8ab7361`, only `ci.yml` in its workflows.
- The exact publish commands and their rollbacks: ALG-113 document `publish-plan`.

### Debts

- The workflow's first real run is the dry run on the public repo, after the secrets are set: the export issue
  should start with it (`gh workflow run release.yml`), before any tag. Done in ALG-118 (run 39495633615, green);
  the agents' GitHub App cannot dispatch it (403, no *Actions: write*), so storagebits started it by hand.
- `CHANGELOG.md` is dated `2026-10-20` (ALG-113); if the tag is pushed on another day, the publish issue fixes it
  first (DECISIONS "v1.0.0 publish prep").
- The key hand-off (backup, environment and secrets on the public repo) is pending: ALG-111 `key-handoff`.


## GTFS: a 200 HTML page is a failed source (ALG-176, 2026-10-22)

- **Bug** (ALG-175). The STM moved its GTFS file to its new site: the old URL answers `302` to the home page, a
  `200` with a ~71 KiB HTML page, VPN or not (first blamed on the VPN, corrected in DECISIONS). `GtfsStore` stored
  it as `gtfs.zip`, every sync failed with `ZipException`, and the mirror was never tried: the bus block stayed
  empty.
- **New STM URL.** The `STM` preset starts with the link on the STM's new open data page, mirror second
  (DECISIONS, "Correction (ALG-175)"). Checked from the forge: `200 application/zip`, no redirect, `ETag`
  present, 66,986,445 bytes, `PK\x03\x04`, holds `stops.txt` and `stop_times.txt`, feed version
  `20261007130000_26N`.
- **Fix.** `GtfsStore.downloadFrom` refuses a `text/html` response and validates `gtfs.zip.part` before the rename
  (zip magic, opens as a zip, has `stops.txt` and `stop_times.txt`); a failure deletes the part file and moves on
  to the next URL. The cached zip and its URL/ETag are only replaced by a valid zip (DECISIONS, "Correction
  (ALG-175)", validation rule).
- **Tests.** `GtfsStoreTest` (3 tests, MockWebServer): a `302` to an HTML `200` page, then the mirror's zip → the mirror's zip and ETag are
  stored and extracted; an HTML body labelled `application/zip` → same; garbage from every source (a page, then a
  zip without `stop_times.txt`) → `sync` returns false (so `GtfsSyncWorker` answers `Result.retry()`), the cached
  zip and ETag are byte-identical, no part file is left. The three fail on the previous code.
- **Emulator** (debug build, Android TV emulator, 1080p). A custom network whose first URL is a local server
  answering every request with `200` and a 71 KiB HTML page, the STM mirror second, one STM stop:
  - served as `text/html`: `GTFS download from … failed: … is an HTML page`, then `GTFS downloaded 65416 KiB` and
    `GTFS extracted 1192 departures` (about 16 s); the bus block shows lines 24 and 356;
  - served as `application/zip` (the Shield's case): `… is not a zip (71 KiB)`, then the same download, extraction
    and bus block.
- **Emulator, STM preset** (QA round 1, same debug build setup). `STM` preset, cached zip and ETag removed: `GTFS
  downloaded 65416 KiB`, then `GTFS extracted 1192 departures for https://beta.stm.info/getContentAsset/…` (feed
  `20261007130000_26N`) with no fallback; the stored ETag is the STM's. The bus block shows lines 24 and 356.
- **Shield.** Not checked: it runs a custom mirror-only "STM · Montréal" network since ALG-175, so it does not take
  the preset's URL. Once this ships, storagebits switches it back to the `STM` preset.
- **Debts.** Older than this bug, found in review: a zip that passes the check but whose entries are corrupt (bad
  CRC, broken deflate stream) or that lacks `trips.txt` / `calendar*.txt` still replaces the cached zip and ETag;
  extraction then fails or yields an empty schedule, and the next sync gets a `304`, so nothing retries until the
  feed's ETag changes. The full fix extracts from the part file before committing it (and writes the ETag only
  after); out of this issue's scope.
