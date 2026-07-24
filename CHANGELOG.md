# Changelog

All notable changes to Specter are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/). Versioning: [SemVer](https://semver.org/).

## [Unreleased]

### Added
- **byedentity 3-way analysis** (`docs/BYEDENTITY-ANALYSIS.md`): decompiled `com.byedentity` v3.0.1 and
  compared GeerGit vs Specter vs byedentity. byedentity is a root/Magisk + native-JNI, server-validated
  changer that spoofs system-wide via `resetprop` + `pm clear` + a Widevine `liboemcrypto.so` bind-mount
  (L1→L3). Findings carry PROVEN/HYPOTHESIS labels (adversarially verified). Adoption candidates in
  `docs/IDEAS.md`; do-not-adopt calls (server/kill-switch/anti-tamper) in `docs/DECISIONS.md`.
- **Probe: Widevine `securityLevel`** — the probe now reads `MediaDrm.getPropertyString("securityLevel")`
  alongside `deviceUniqueId`, and `verify_on_device.py` prints a Widevine-coherence line.

### Fixed
- **Widevine DRM coherence (no root).** Specter value-spoofed `deviceUniqueId` but left `securityLevel`
  reporting the real **L1** — a *changing* device id at hardware-L1 is itself a fingerprint. Confirmed
  on-device (Pixel 4: spoofed id @ L1), then fixed: `profile.py` emits `media_drm_security_level: "L3"`
  (software Widevine, where a changing id is coherent) and `HookEntry` hooks
  `getPropertyString("securityLevel")` to return it. Re-verified coherent on-device (@ L3). The value is a
  constant → consumes no RNG → Java↔Python byte-parity unchanged. Achieves byedentity's L1→L3 outcome
  without its root `liboemcrypto` bind-mount.
- **StatFs storage leak closed + RAM/storage made coherent (device-linking signal).** `total_storage` was
  generated but never injected, so real internal storage leaked — a stable value that links accounts. Added
  a coherent `StatFs` hook (`getTotalBytes` and `getBlockCountLong`×`getBlockSizeLong` multiply to the same
  spoofed total; available/free ~35-55%). Also replaced the independent RAM and storage draws with a single
  coherent pair (`ram_storage_bytes`): storage is derived from the chosen RAM tier, so an incoherent combo
  (e.g. 12GB RAM + 32GB storage) can no longer occur. Java↔Python byte-parity re-proven; verified on-device.
- **Brand-plausible serial format (was detectably synthetic).** `serial` was `hex16upper` — 16 pure-hex
  chars, but a real Pixel serial is 14 alphanumeric incl non-hex letters (`9B151FFAZ00FPF`) and a Samsung
  is `R`+10 (11 chars). Added `serial_for_brand` (Base34 alphabet, brand prefix + correct length for
  Samsung/Google/Motorola/LGE). Java↔Python byte-parity proven; verified on-device (Pixel profile →
  `A6X71GDYHX9WC3`). A device claiming to be a Pixel no longer reports an impossible pure-hex serial.

## [0.3.0] — 2026-07-08

UI/UX polish + real multi-app targeting, per-country SIM, and realistic emails. The app now
carries one name (Specter), a logo, and the warm-dark charcoal theme.

### Added
- **Multi-app targeting**: an app picker (PackageManager) with a Show-system-apps toggle
  (user apps by default), search, Select/Deselect all, and multi-select. APPLY writes the
  profile to every selected target. Fleet-safety warning on Dasher/system packages.
- **Per-country SIM**: USA + UK, with an extensible Country structure (carriers, phone format,
  ICCID IIN, brand bias). Settings has a country picker; UK generates EE/O2/Vodafone/Three
  carriers + +44 7 phone numbers. `specter --country` on the CLI.
- **Realistic emails**: real first/last names in common patterns (first.last, firstlast+year, …)
  across gmail/outlook/yahoo/hotmail/icloud, replacing the old random-letter `xxxx###@gmail.com`.
  (GeerGit's own 'normal emails' are server-side and byte-identical across its versions — this is
  an independent implementation, not a port.)
- **Per-identifier on/off toggles**: each id card has a switch; disabled ids are omitted from the
  applied profile so the hook leaves them REAL (GeerGit's *_switch parity).
- **Branding**: gold ghost logo (vector) as header mark + adaptive launcher icon; charcoal/gold
  theme via a single Theme token class; version shown small next to the wordmark.

### Fixed
- **Tab active-state**: switching Identity/Settings/Location now highlights the active tab (gold).
  Previously the tab bar was built once and never re-tinted.
- One name only: the LSPosed module + app label is **Specter** (was 'Fleet ID Rotate').

### Notes
- Java + Python generation stay byte-parity (same seeded emails/phones). JVM 44,063 asserts +
  Python 75 tests green.
- Deferred (2.9.7-beta parity, later): Hide Airplane Mode, Randomize Battery Level, Spoof Battery
  Cycle Count, i18n, profile-transfer (server feature).

## [0.2.0] — 2026-07-08

Pivot from a PC-tethered CLI to a **standalone, no-PC Android app**: Specter now generates
identities on-device and self-applies them via Magisk `su`, with a native 3-tab UI — the same
package is BOTH an LSPosed module and a launchable app (like GeerGit). Proven end-to-end on a
real Pixel 4 against the DevInfo test app.

### Added
- **Standalone Android app** (`com.fleet.idrotate.ui.MainActivity`): on-device identity
  generation + native Identity/Settings/Location UI (RANDOMIZE ALL, per-card EDIT/RANDOMIZE,
  APPLY). No PC required.
- **On-device generation core** ported from the Python reference to pure Java
  (`gen/Generators`, `gen/Profile`, `gen/UsedStore`) — byte-parity with Python at a fixed seed;
  34k+ JVM assertions.
- **`RootWriter`**: writes the profile to `/data/local/tmp/specter/<pkg>.json` via `su`
  (shell-injection-guarded, JSON via stdin), fail-loud on root denial.
- **`IdentityService`**: bundled 499-device asset, on-device no-reuse ledger in app-private
  storage (fail-closed on corruption), thread-safe generate/randomize.
- PC TUI upgraded to a questionnaire menu (questionary + rich fallback); `specter --version`.
- Version surfaced everywhere: app header, TUI header, APK badging, `VERSION` single-source file.

### Fixed
- **android_id + advertising_id now actually reach the target app.** Prior builds spoofed
  Build.*/serial/GSF but leaked the REAL android_id and ad id (proven via DevInfo + two
  dexdumps). Now hook all `Settings.Secure`/`System` getString overloads and the
  `AdvertisingIdClient` static factory.
- **Ban-critical**: per-field RANDOMIZE now records to the no-reuse ledger (a randomized-then-
  applied id could previously be reissued to another account).
- Ledger thread-safety (static lock), fail-closed persistence (checked `renameTo`), empty-
  profile guards on APPLY/EDIT/RANDOMIZE (an empty APPLY would leak real ids), missing-key
  validation, and a data race on the shared profile map.

### Changed
- Fleet-safety: CLI/TUI/verify default target is now DevInfo (`com.liuzh.deviceinfo`), never a
  real fleet app. The Python CLI/TUI is retained as the trusted spec + dev tool.

### Known / out of scope
- Fingerprint Pro (`com.fingerprintjs.android.fpjs_pro_demo`) re-identifies at 100% confidence
  after a full rotation — it fingerprints via hardware/sensor/IP signals this module does not
  hook. Deprioritized to a later stretch goal; GeerGit identifier-level parity is the bar and is met.

## [0.1.0] — 2026-07-08

First complete release: builds, installs, push-verified on device; 73 tests; 6 review passes applied.

### Added
- Hook coverage hardening: ContentProviderClient.query, cursor getBlob + copyStringToBuffer.
- IMEI TAC coherence (brand-plausible TAC, shared across dual-SIM IMEIs).
- Fail-closed used-id ledger (quarantine + refuse on corruption).
- Build.VERSION.* spoofing; Gservices.getLong; executable JVM tests for hook logic.
- CI workflow; release builder (dist zip); dual-OS launchers.
- Polished README (feature table, quality section, project layout).
- Deepened `verify` questionnaire: pre-flight device/module summary, module-active detection,
  per-check error isolation, and a final results summary table.
- RFC-4122 v4 advertising IDs; GSF clamped to Java Long.MAX; slot-aware IMEI; GSF cursor getLong.
- Core identity generator: coherent, US-biased device profiles from a 499-device DB.
- Per-identifier generators with validators (Luhn IMEI/ICCID, MAC bits, 19-digit GSF).
- Global used-id ledger — no identifier is ever reused across signups (the anti-ban core).
- Named profile vault: save / list / reuse identities (backup a good one, reload later).
- `device.py` adb layer: push profile, clear app, read live identifiers + hook log.
- CLI: `new` · `push` · `rotate` · `list` · `show` · `stats` · `verify` · `tui`.
- Rich TUI dashboard (light/dark safe): active identity, vault, issued-ledger, device status.
- **Deep on-device verification harness** (`verify.py`, questionnaire-driven): coverage,
  rotation (launch N×, confirm fresh identity each), backup/reload round-trip, leak audit.
- LSPosed module (`xposed-module/`) hooking the full identifier surface incl. GSF.
- `scripts/compare_with_geergit.py`: on-device coverage + rotation comparison vs GeerGit.
- 49 tests: generators, coherence, uniqueness, GeerGit parity, device, CLI, TUI, verify, module parity.

### Context
- Built after diagnosing GeerGit 2.9.6's GSF-rotation regression (`docs/GEERGIT-2.9.6-REGRESSION.md`)
  that reused a stale fake GSF across signups → DoorDash coordinated-account bans.
