# Specter — running ideas / backlog log

Append new ideas here with a date, one-line rationale, and status. Don't lose ideas in chat.
Status: `idea` · `researching` · `building` · `shipped` · `rejected (why)`.

## Active / open

- **2026-07-25 · Decompile `byedentity.apk` (a.k.a. deidentify) & 3-way compare** — status: `idea`.
  A new anti-identity APK (7 MB, ~8× smaller than GeerGit → likely native/Xposed, not Flutter). Decompile
  it, map what it spoofs/hides, and compare GeerGit vs Specter vs byedentity. Pull any features worth
  adopting into Specter. (Planned as a fresh session via /handoff — this conversation is full.)

- **2026-07-25 · App-list spoofing (HideMyAppList-style)** — status: `idea (deprioritized)`.
  Hook `PackageManager.getInstalledApplications/getInstalledPackages` to return a coherent subset per
  identity. Real linking signal, BUT it's a STABLE signal → cannot explain the fleet's *intermittent*
  bans (would flag all or none). Completeness item, not the fleet fix. Reasonably easy (same hook pattern
  we already use). Revisit after the intermittent-detection hypothesis is confirmed/refuted.

- **2026-07-25 · CONFIRM the intermittent-detection hypothesis** — status: `researching`.
  Hypothesis (strong, code-grounded, UNPROVEN): GeerGit's IMEI-increment mode / manual "should be unique"
  burden yields sequential/duplicate IDs in some accounts → intermittent clustering. To confirm: diff the
  actual identifiers of one flagged vs one passed GeerGit account, OR measure Specter's live flag rate.
  See docs/ANTI-FINGERPRINT-STRATEGY.md. Until confirmed, "Specter's enforced uniqueness helps" is an
  expectation, not a guarantee.

- **2026-07-25 · Prove fingerprint actually rotates on FingerprintJS Pro demo** — status: `idea`.
  The FPJS Pro demo is installed on the Pixel (safe non-fleet test app). Scope Specter to it, apply two
  identities, and confirm the *computed fingerprint hash* differs — closest lab proof to "beats detection".

## Deferred (documented, low priority)
- Installed-apps list (above). · /proc/cpuinfo file-hook (risky, SoC already covered via ro.board.platform).
- Sensors/cameras/codecs coherence (needs a real per-model dataset or it's a *worse* signal than leaving real).
- Profile-file hook-artifact hiding (no real stack checks for it today).

## Shipped (see CHANGELOG.md for detail)
- Full GeerGit 2.7.0 identifier parity + Build.BOOTLOADER (PR #4).
- Deep fingerprint-signal spoofing: SoC/radio/kernel/HARDWARE/BOARD/HOST/DISPLAY/RAM, device-coherent (PR #5).
- Dev-mode-tell hiding (adb_enabled/dev-settings → 0); Settings.Secure bluetooth_address leak closed.
- USA-only (US carriers, NANP phones, US-market brands); realistic emails. Autonomous probe verifier.
