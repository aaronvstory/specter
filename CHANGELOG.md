# Changelog

All notable changes to Specter are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/). Versioning: [SemVer](https://semver.org/).

## [Unreleased]

### Added
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
