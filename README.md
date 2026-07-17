# Spooler

Android POC that reads **Anycubic ACE filament NFC tags** and shows the decoded
filament data (SKU, material, color, temps, diameter, length) plus a raw hex
dump. Built-in **[Spoolman](https://github.com/Donkie/Spoolman) integration**
sends that data straight to your own Spoolman instance — see
[Spoolman integration](#spoolman-integration) below.

**Prebuilt APK:** every push to `main` is built and published automatically —
grab the latest one from the [Releases page](https://github.com/bagstac/Spooler/releases).
It's a debug build (debug-key signed), so enable "Install unknown apps" for
your file manager/browser to sideload it.

## Why a native app

Anycubic tags are NFC Forum **Type 2 clone chips** (Shanghai Feiju, SAK `0x00`,
ATQA `0x44`, UID starting `53:`) that store a **raw bytestream, not NDEF**.
Web NFC and NDEF-based apps can't read them at all. This app uses Android
reader mode (`FLAG_READER_NFC_A | FLAG_READER_SKIP_NDEF_CHECK`) and issues raw
Type 2 `READ (0x30)` commands over `NfcA.transceive()` — 16 bytes (4 pages) per
command.

Byte layout (per [DnG-Crafts/ACE-RFID](https://github.com/DnG-Crafts/ACE-RFID)
reverse-engineering): header `7B 00 65 00` at page 4, SKU pages 5–8, brand
10–13, material 15–18, color (ABGR) page 20, extruder temps page 24, bed temps
page 29, diameter + length page 30.

## Stack

- Kotlin 2.1, Jetpack Compose (Material 3), kotlinx.serialization
- minSdk 34 (Pixel 8 Pro POC target), compileSdk/targetSdk 35
- No DI/network/database — scan history is a small JSON file in app storage

## Project layout

```
app/src/main/java/com/bsbagley/spooler/
  MainActivity.kt                            NFC reader mode lifecycle, ReaderCallback
  ScanViewModel.kt                            all UI state (scan/send/write/lookup)
  nfc/TagReader.kt                            raw Type 2 READ/WRITE loop
  tag/AnycubicDecoder.kt                      tag bytes -> FilamentInfo
  tag/AnycubicEncoder.kt                      FilamentInfo -> tag bytes
  spoolman/SpoolmanClient.kt                  Spoolman REST client
  ocr/LabelTextRecognizer.kt                  ML Kit text recognition wrapper
  ocr/LabelFieldParser.kt                     OCR text -> best-guess fields
  filamentdb/OpenFilamentDatabaseClient.kt    api.openfilamentdatabase.org client
  data/ScanHistoryStore.kt                    JSON-file scan history (capped at 50)
  data/SettingsStore.kt                       SharedPreferences wrapper
  ui/MainScreen.kt                            home screen + several sub-screens
  ui/LabelReviewScreen.kt                     manual-entry / label-capture form
  ui/FieldCaptureScreen.kt                    per-field camera capture dialog
```

## Building

1. Install [Android Studio](https://developer.android.com/studio) (bundles the
   JDK and Android SDK — nothing else needed; the system Java is not used).
2. **Open** this folder (`C:\git\Spooler`) in Android Studio and let Gradle sync
   (first sync downloads Gradle 8.11.1 + dependencies).
3. On the Pixel: Settings → About phone → tap **Build number** 7× to enable
   Developer options, then Settings → System → Developer options → enable
   **USB debugging**. Plug in via USB and accept the debugging prompt.
4. Press **Run** (▶) with the phone selected.

> Command-line note: `gradlew` needs `gradle/wrapper/gradle-wrapper.jar`, which
> isn't committed. Generate it once from Studio's terminal with `gradle wrapper`
> or let Studio manage builds.

Run unit tests (decoder): right-click `AnycubicDecoderTest` → Run, or
`gradlew :app:testDebugUnitTest`.

## Using it

**Main Screen** offers two entry points: hold a filament tag against the
upper back of the phone (reads pages 0–44, stopping gracefully wherever the
clone chip NAKs, decodes the Anycubic layout, saves to history), or tap
**Enter New Spool** for a tagless spool. A scan result shows:

- **Filament card** — SKU, brand, material, color swatch, temps, diameter, length
- **Tag card** — UID, ATQA, SAK, pages read
- **Send to Spoolman** — the primary action; see [Spoolman integration](#spoolman-integration)
- **More actions** (overflow menu) — Copy JSON, Share, Write tag…, View raw memory
- **↻ New scan** (top bar) — clears the result and returns to the idle state

**Spool Info** (opened via "Enter New Spool") is a manual-entry form with a
camera icon on every field for OCR capture from a printed label:
- **Identify the Filament** — Brand, Material, Color name; filling in all
  three automatically triggers an [Open Filament Database](https://api.openfilamentdatabase.org)
  lookup (debounced ~600ms after you stop typing) to fill in the rest
- **Other Details** (collapsed by default) — SKU, color hex, extruder/bed
  temps, diameter, length — usually filled by the lookup above
- **Send to Spoolman** is pinned to the bottom of the screen

**Write tag…** (from either screen) edits the filament values and arms a
write; the next tag held to the phone is overwritten (`WRITE 0xA2`, pages
4–31 only — UID and lock pages are never touched) and verified by read-back.

### Settings (⚙)

- **Spoolman URL** — see [Spoolman integration](#spoolman-integration)
- **Enable Spoolman** (on by default) — hides all "Send to Spoolman" buttons when off
- **Show write NFC tag option** (on by default) — hides all "Write tag…" buttons when off
- **Show raw memory dump** (off by default) — adds a "View raw memory" action
- **Show history** (off by default) — adds a History button listing past scans

## Spoolman integration

Spooler talks directly to **your own [Spoolman](https://github.com/Donkie/Spoolman)
instance** over its REST API — nothing goes through a third-party server.
This has been built and tested against a local Spoolman running in Docker
(both on a PC and on a Raspberry Pi). Turn it off entirely via ⚙ Settings →
**Enable Spoolman** if you don't run an instance — this hides every "Send to
Spoolman" button in the app.

**Setup:**
1. Have a Spoolman instance reachable from your phone (same Wi-Fi/LAN). Any
   standard Spoolman Docker deployment works — see Spoolman's own
   [installation docs](https://donkie.github.io/Spoolman/) if you don't have
   one running yet.
2. In Spooler, tap **⚙ Settings** (opens automatically on first launch) and
   enter your Spoolman instance's URL, e.g. `http://192.168.1.171:7912` —
   whatever address and port your instance is reachable at. Tap **Save**.

**Using it:**
1. Get filament data into the app either by **scanning an Anycubic tag** or
   by tapping **Enter New Spool** to type in / photograph a printed label.
2. Once the filament details are showing, tap **Send to Spoolman**.
3. Spooler will:
   - find or create the **vendor** (matched by brand name),
   - find or create the **filament** (matched by vendor + material + color),
   - create a **spool**, estimating initial weight from length × diameter ×
     density when the source data doesn't include weight directly.
4. Re-sending the same physical tag is safe — its UID is stored as the
   spool's `lot_nr`, so a rescan is recognized as *"already in Spoolman"*
   rather than creating a duplicate. (Manually-entered spools get a
   synthetic UID instead, since there's no tag to dedupe on.)

Network trouble reaching a Docker-hosted Spoolman from your phone? The most
common culprit is a firewall between your LAN and the Docker host (WSL2's
mirrored networking mode is a known offender on Windows) — running Spoolman
on a plain Linux box (a Raspberry Pi, a NAS, etc.) sidesteps this entirely.

## Release signing

For Play Store internal testing (or any signed release build), `bundleRelease`
looks for credentials in a properties file **kept outside this public repo**:

```
~/keystores/spooler-keystore.properties   (forward slashes even on Windows —
                                            Java .properties treats backslash
                                            as an escape character)
```

containing:
```
storeFile=C:/Users/you/keystores/spooler-upload.jks
storePassword=...
keyAlias=spooler-upload
keyPassword=...
```

Generate a keystore once with `keytool -genkeypair` (see git history of
`app/build.gradle.kts` for the exact command used). **Back up the keystore
file and password somewhere durable** — with Play App Signing, Google can
help recover a lost upload key, but it's a support process, not instant.

Without that properties file present, `bundleRelease`/`assembleRelease` still
build (just unsigned) — a fresh checkout isn't broken by its absence. Override
the default path with `-PreleaseKeystoreProps=<path>` if needed.

## Roadmap

- [ ] Reliability pass on real tags (read retries, damaged-tag handling)
- [x] Write support (`WRITE 0xA2`) for re-labeling spools
- [x] Spoolman integration (create spools via its REST API)
- [x] Manual/OCR spool entry for tagless spools, with Open Filament Database lookup
- [ ] Play Store internal testing release
