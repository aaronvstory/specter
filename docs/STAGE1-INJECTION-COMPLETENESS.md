# Stage 1 — Injection completeness proof on DevInfo (2026-07-08)

Device: Pixel 4 (`9B151FFAZ00FPF`), Android 11, Magisk root, LSPosed. Module `com.fleet.idrotate`
scoped to `com.liuzh.deviceinfo` (DevInfo). Screen lock temporarily removed by user for UI verification.

Method: launched DevInfo fresh (`[specter] active for com.liuzh.deviceinfo (27 fields)` confirmed in
LSPosed log), navigated to the **Device** tab, dumped UI (`uiautomator dump`), compared every displayed
value against the injected profile `/data/local/tmp/specter/com.liuzh.deviceinfo.json`.

## Result: 6/8 checkable identifiers SPOOFED, 2 genuinely NOT reaching DevInfo

| DevInfo field | Injected | On-screen | Verdict |
|---|---|---|---|
| Manufacturer | OnePlus | OnePlus | ✅ spoofed |
| Model | OnePlusN10 | OnePlusN10 | ✅ spoofed |
| Device | BE2026 | BE2026 | ✅ spoofed |
| Brand | OnePlus | OnePlus | ✅ spoofed |
| Hardware serial | D729D57EDC950EFB | D729D57EDC950EFB | ✅ spoofed |
| Build fingerprint | OnePlus/OnePlusN10/… | matches | ✅ spoofed |
| **Google Services Framework ID** | `7197573505246324104` (dec) | `63E2EAF7F8F86588` (hex) | ✅ **spoofed** — `int("63E2EAF7F8F86588",16)==7197573505246324104`. DevInfo shows GSF as uppercase hex of the long; a decimal-vs-hex string compare initially false-flagged this. |
| **Android Device ID** | android_id `49ee68d4b31558af` | `1cc23cd3fdb29a57` | ❌ **NOT spoofed** — real per-app value leaking. |
| **Google Advertising ID** | `e7e144a5-229d-71e9-7247-328473707a2c` | `26963559-39c3-4dc6-86ee-f843b725cbea` | ❌ **NOT spoofed** — real value leaking. |
| WiFi MAC / Bluetooth MAC | (injected) | "Unknown" / "Click to grant permission" | ⚠️ permission-gated in DevInfo, not checkable this way. |
| Board / Hardware | (not spoofed fields) | flame | expected — real, we don't rotate these. |

## Two real hooks to fix (root cause under investigation)
1. **Android Device ID** — DevInfo displays a value that is neither our injected android_id nor the shell's
   Settings.Secure android_id (`b1e2d78a800c9fe2`). Settings.Secure android_id is per-app-scoped on Android 8+,
   so DevInfo's real value differs from shell. Our `hookSettingsSecure` hooks
   `Settings.Secure.getString(ContentResolver, String)` — but DevInfo may read via a different overload/path
   (e.g. `getStringForUser`, or a GSF-derived id). Decompile pending to pin the exact call.
2. **Advertising ID** — `hookAdvertisingId` hooks `AdvertisingIdClient$Info.getId`, but DevInfo shows the real
   value, so either DevInfo reads the ad id via a different API (direct provider read of
   `content://com.google.android.gsf.gservices` "advertising_id", or the newer `AppSet` id), or the GMS class
   isn't on DevInfo's classloader. Decompile pending.

## Note
Injection is REAL and reaches the app (Build.*/serial/GSF all confirmed on-screen) — the prior "proven" claim
holds for those, but was incomplete. android_id + advertising_id hooks need fixing before the set is complete.

---

# Test targets: DevInfo (completeness) + Fingerprint Pro (stretch benchmark)

Two apps, two jobs:
- **DevInfo (`com.liuzh.deviceinfo`)** — the completeness/read-back proof: shows the raw identifier each
  Android API returns, so we can confirm every hook reaches the target and displays the injected value.
- **Fingerprint Pro (`com.fingerprintjs.android.fpjs_pro_demo`, SDK v2.17.0)** — the adversarial golden
  standard. Module is scoped to it and the hook fires (`[specter] active … (27 fields)`). Automate its
  "Tap to begin" trigger headlessly with `adb shell input tap 540 1086` (center of the fixed-layout button),
  so no manual tapping is needed on relaunch.

## FPJS Pro result (2026-07-08): NOT beaten — identifier rotation alone is insufficient
Rotated the FULL 27-field profile, cleared app data, relaunched, re-fingerprinted:
- **Visitor ID `18uu8Y2WxYks5PNLa0c7` — IDENTICAL before and after rotation.**
- **Visitor Found: Yes. Confidence: 100%.** IP `23.234.72.101` shown as a signal.

FPJS re-identified the same "device" at 100% despite every hooked identifier changing → it fingerprints via
signals our module does NOT touch (hardware/sensor/GPU/build-prop entropy, IP, timing, etc.). This is the
concrete form of "what burned the fleet before."

## DECISION (user, 2026-07-08): refocus on GeerGit parity; FPJS is a LATER stretch goal
GeerGit's identifier-surface approach is PROVEN to work for the fleet use-case — so **match what GeerGit does**
(the target this build already aims at) and do NOT block on defeating Fingerprint Pro. Keep FPJS installed +
scoped as a benchmark to chip away at afterward (deeper signal hooks: sensors, GPU/GL renderer, build props,
media codecs, etc.), but it is out of scope for the core deliverables. DevInfo remains the completeness proof;
GeerGit parity (the identifier set already speced) is the bar.
