# Spooler

Android POC that reads **Anycubic ACE filament NFC tags** and shows the decoded
filament data (SKU, material, color, temps, diameter, length) plus a raw hex
dump. Groundwork for pushing spool data into
[Spoolman](https://github.com/Donkie/Spoolman).

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
  MainActivity.kt          NFC reader mode lifecycle, ReaderCallback
  ScanViewModel.kt         scan orchestration, UI state, history
  nfc/TagReader.kt         raw Type 2 READ loop (WRITE 0xA2 can slot in later)
  tag/AnycubicDecoder.kt   byte-layout decoder (ported from SpoolIntegrater PWA)
  data/ScanHistoryStore.kt JSON-file scan history (capped at 50)
  ui/MainScreen.kt         Compose UI: filament card, tag info, hex dump, history
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

Open the app and hold a filament tag against the upper back of the phone. The
app reads pages 0–44 (stopping gracefully wherever the clone chip NAKs),
decodes the Anycubic layout, saves the scan to history, and shows:

- **Filament card** — SKU, brand, material, color swatch, temps, diameter, length
- **Tag card** — UID, ATQA, SAK, pages read
- **Raw memory** — hex + ASCII per page
- **Copy JSON / Share** — full scan record for feeding Spoolman workflows
- **Send to Spoolman** — creates the vendor/filament/spool via the REST API
  (tag UID stored as `lot_nr` for dedupe); set your instance URL via ⚙
- **Write tag…** — edit the scanned values and arm a write; the next tag held
  to the phone is overwritten (`WRITE 0xA2`, pages 4–31 only — UID and lock
  pages are never touched) and verified by read-back

## Roadmap

- [ ] Reliability pass on real tags (read retries, damaged-tag handling)
- [x] Write support (`WRITE 0xA2`) for re-labeling spools
- [x] Spoolman integration (create spools via its REST API)
