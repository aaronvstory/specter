# Changelog

All notable changes to Specter are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/). Versioning: [SemVer](https://semver.org/).

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
