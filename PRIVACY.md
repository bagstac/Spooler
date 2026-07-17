# Privacy Policy — Spooler

**Last updated:** 2026-07-17

Spooler is a local-first Android app for reading and writing Anycubic filament
NFC tags. It does not have its own backend and does not collect, store, or
transmit personal data to the developer or any third party controlled by the
developer.

## What data Spooler accesses, and why

- **NFC** — reads and writes the filament tag held against your phone. Tag
  data (SKU, material, color, temperatures, diameter, length) stays on your
  device unless you explicitly send it elsewhere (see below).
- **Camera** — only used if you tap the camera icon to capture a field from a
  photo of a filament label (OCR). Photos are processed on-device and are not
  saved or uploaded anywhere.
- **Network** — Spooler makes outbound requests only when you take an action
  that needs one:
  - **Send to Spoolman**: sends the scanned/entered filament data to the
    Spoolman server URL *you* configure in Settings. This is typically a
    self-hosted instance on your own network; Spooler has no relationship
    with that server beyond what you configure.
  - **Open Filament Database lookup**: when using manual entry, filling in
    Brand/Material/Color name triggers a lookup against the public
    [Open Filament Database](https://openfilamentdatabase.org) API to
    autofill temperatures, diameter, and length. Only those three text
    fields are sent as the query — no device identifiers.
- **Local storage**: scan history (up to 50 recent scans) and your app
  settings (Spoolman URL, feature toggles) are stored only in the app's
  private storage on your device, and are deleted if you uninstall the app.

## What Spooler does not do

- No analytics, crash reporting, or advertising SDKs.
- No account creation, sign-in, or user tracking.
- No data is sold or shared with third parties.

## Third-party services

If you enable Spoolman integration or the Open Filament Database lookup, data
you send is subject to the privacy practices of that server/service — for a
self-hosted Spoolman instance, that's under your own control.

## Contact

Questions about this policy: open an issue at
https://github.com/bagstac/Spooler/issues
