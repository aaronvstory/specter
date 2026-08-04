# What else is out there — iOS device-identity tooling (discovery, 2026-08-04)

Two research fan-outs (exa + GitHub deep-dives) surveyed everything beyond the known set. Bottom line:
everything collapses into three classes — **injection** (WeaponX/ProjectX, MGSpoof, Titanox, Specter),
**containers** (Crane, LiveContainer), **real-device** (farms/eSIM) — with ONE genuinely different
architecture: the MobileGestalt on-disk patch family.

## The one new architecture — MobileGestalt on-disk patch (NO injection, NO jailbreak)
- **Nugget / Nugget-Mobile** (leminlimez, ~7k★, AGPL-3, main repo archived; active forks Tender,
  TrollStore-Pro/Nugget-Revamped) · **misaka26** (straight-tamago) · **iEscaper** (GeoSn0w, Swift-rewrite,
  widest range through iOS 26.2b1) · **Picasso/OpenPicasso** (sourcelocation, MIT, arm64e 15.0–16.6) ·
  **autoPatcher-mobilegestalt** (Rust505, MIT — the raw CacheData/plist patch engine).
- Mechanism: a backup/restore sandbox-escape (SparseRestore / TrollRestore / bookrestore) rewrites the
  device's own `com.apple.MobileGestalt.plist` cache → every process reads a coherent changed hardware
  identity **at the OS source**. Nothing is injected into the target → nothing in its image list to detect.
- Limits: **device-wide** (one identity for the whole phone, no per-app rotation); **MG keys only**
  (model/hardware capability — NOT IDFV/IDFA/serial, which live in keychain/IOKit); delivery exploit is
  **patched on iOS 26.2+** (works iOS 15–26.1, incl. the SE2/8 on 16.3.1).
- Best use: the **device-wide hardware-coherence layer *under* per-app injection** — the closest thing to
  Specter-Android's BOARD/HARDWARE/composite alignment, and invisible to a target's image scan.

## Other new/relevant
- **Titanox** (Ragekill3377, MIT, active) — no-JB in-app hooking (fishhook + MemX) inside a TrollStore/
  sideloaded app. Same hooking class as Specter, but a **non-jailbreak distribution path**. Future lever.
- **fingerprintjs-ios** + **trustdevice-ios** (adversary-side fingerprint libs) — the exact spec of which
  signals a spoofer must cover + the coherence traps. **Read first**; zero cost.
- **iOSVersionSpoofer** (Fadexz) — single-signal; reference only.
- **ProjectX/WeaponX** (waruhachi, GPL-3) — the open-source WeaponX = the direct iOS analog of Specter
  (per-app scoped IDFV/IDFA/model/name/boot-time + IORegistry). Peer/reference; base the port's scoping on it.

## The blunt community reality (hardened US fintech)
- No reliable software spoof. The working method is **hardware multiplication**: one real, un-jailbroken
  iPhone per account + OS-level proxy + own eSIM, driven via Appium/XCUITest farms.
- **App Attest / DeviceCheck** is the wall (Secure-Enclave key, server-verified — unforgeable in software).
- New detection vector: hardened fintech uses a **sandbox-escape 0-day (CVE-2025-31207)** to enumerate
  installed app IDs → detects TrollStore/JB even when hidden (Verichains-confirmed). The JB itself is a risk.

## Dead ends
LiveContainer for anti-linking (isolation regressed 2026 #1443/#1154; shared signing cert); anti-detect
"browsers" (web fingerprints); emulator/Simulator (no SEP → instant attest fail); farmed/replayed attest
tokens (dead vs a nonce+timing backend).

Full report: `docs/ios/ios-tools-report.html`. Sources: workflows wot5in6t2 (internals) + wv4kztejl (discovery).
