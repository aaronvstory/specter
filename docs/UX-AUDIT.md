# UX audit — the Specter app as it stands (GOAL 3.1)

Walked the real app on-device (Pixel 4, `com.specter/.module.ui.MainActivity`) on 2026-07-26 and
screenshotted every screen. Overall: the app is in **good shape** — clean dark theme, per-field
controls that all do something, and non-functional areas are honestly labeled (no fake toggles). The
findings below are mostly polish + one real version bug. Ranked by severity.

## Screens
- **Identity tab** (default): RANDOMIZE ALL / APPLY at top; DEVICE SIMULATION (Manufacturer, Model,
  Brand, Device, Fingerprint, Carrier — read-only) then IDENTIFIERS (Advertising ID, IMEI×2, Serial,
  MediaDRM ID, Wi-Fi MAC/BSSID/SSID, Phone number, IMSI, …) each with an enable toggle + EDIT + RANDOMIZE.
- **Settings tab**: TARGET APPS (current list + "Select target apps") and an ANTI-FINGERPRINTING info card.
- **Location tab**: a single info card — honestly labeled "UI only — no location hook yet (planned)".

## Findings

### 1. Version in the header is WRONG (`v0.3.0`) — real bug · **high**
The header shows `v0.3.0`, read via `PackageManager.versionName` (`MainActivity.java:113`). But
`app/build.gradle:13` hardcodes `versionName "0.3.0"` and is NOT wired to the repo `VERSION` file
(now `0.5.0`). So the app under-reports its version by two minors, and the value drifts every release.
FIX: drive `versionName` from `VERSION` in `build.gradle` (read the file), so it can never disagree.

### 2. Location tab is a dead placeholder occupying top-level nav · **medium**
It's honestly labeled (good — no fake controls), but it takes one of only three top-level tab slots for
a feature that doesn't exist. Options: (a) hide the tab until the LocationManager hook ships, or (b)
keep it but move the "planned" note somewhere less prominent. Leaning (a) — a top-level tab that only
says "not built yet" reads as unfinished to a paying user. (Ties to GOAL: location is a real later PR.)

### 3. ANTI-FINGERPRINTING copy is out of date · **low**
The Settings info card lists "Build, bootloader, radio, kernel, HARDWARE/BOARD" but not the hardware
layer shipped 2026-07-26 (GPU/GLES, `/proc/cpuinfo`, sensors, cameras, codecs, core count). Update the
copy so it reflects what's actually spoofed now.

### 4. Phone number is unformatted (`16019842949`) · **low**
Displayed as a raw 11-digit string. A `+1 (601) 984-2949` format would read as a real number. Cosmetic.

## Not a UI bug (logged elsewhere)
- **"Model" shows `sofiap_sprout`** for a Moto G Pro. This is the real `Build.MODEL` stored in
  `data/devices.json` (`sofiap_sprout:11`), not a display error — Motorola's DB entry uses an internal
  model string. It's a device-DB data-quality item (the retail model is "moto g pro"), so it belongs to
  the Phase-2 coherence sweep, not the UI. Noted here for traceability.

## Verdict
No fake/cosmetic controls, no crashes, no confusing dead buttons. The only real bug is the version
string (#1). #2–#4 are polish. 3.2 will fix #1 and #3 (cheap, clear wins), address #2 by decision, and
leave #4 as optional.
