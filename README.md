<div align="center">

# 👻 Specter

**Per-app device-identity rotation for Android.**
Give every account signup a fresh, coherent, never-reused device fingerprint —
so fraud systems can't link your accounts by a repeated identifier.

</div>

---

Specter is a free, self-hosted replacement for GeerGit-style identifier spoofers. It generates a
complete, internally-consistent fake device identity per signup and injects it into a target app
via an LSPosed module — with one guarantee the paid tools failed to keep: **no identifier is ever
reused across signups.**

## Why Specter exists

GeerGit **2.9.6** shipped a regression that reused a stale fake **GSF ID** across signups. DoorDash's
fraud stack linked the accounts and mass-banned them for "coordinated platform abuse — shared or
multiple accounts." The whole app was created to make that failure *structurally impossible*: every
volatile identifier is generated fresh and checked against a persistent used-id ledger before it's
ever handed out. Full technical diagnosis: [`docs/GEERGIT-2.9.6-REGRESSION.md`](docs/GEERGIT-2.9.6-REGRESSION.md).

## What it rotates

A complete, coherent identity per signup — matching GeerGit's full hook surface (extracted from its
own Dart string pool) plus the fixes:

| Category | Identifiers |
|---|---|
| **Device** | `Build.*` (manufacturer, brand, model, fingerprint, id…) from a **499 real-device DB** |
| **Telephony** | IMEI 1/2 (Luhn-valid, slot-aware), SIM operator, IMSI, ICCID, phone number |
| **IDs** | Android ID, **GSF ID** (the one that regressed), Advertising ID (RFC-4122 v4), MediaDRM/Widevine |
| **Network** | Wi-Fi MAC/SSID/BSSID, Bluetooth MAC (locally-administered bit set) |

Coherence is enforced: the fingerprint matches the Build fields, the IMSI matches the SIM carrier,
and US-market devices pair with US carriers — an incoherent device is itself a fraud flag.

## Click and go

- **Windows:** double-click `launch.bat`
- **macOS:** double-click `launch.command` (first run: right-click → Open)

Opens the dashboard. Per signup:

```
specter rotate --pkg com.doordash.driverapp
```

generates a fresh identity → pushes it to the phone → clears the target app. Launch it and sign up.

## Requirements

- Android phone **rooted with Magisk** (Zygisk) + **LSPosed** ("Vector") installed
- `adb` on PATH (Android platform-tools)
- Python 3.10+ (`uv` recommended — the launchers use it)
- The **Specter LSPosed module** built, installed, enabled, and scoped to your target app

## Install the module

```bash
cd xposed-module && ./build-apk.sh        # produces dist/specter-module-v0.1.0.apk
adb install -r ../dist/specter-module-v0.1.0.apk
# then in LSPosed: Modules → Specter → enable → scope to your target app → reboot
```

The build script auto-detects a local JDK 17 + Gradle if present. The module ships Xposed API stubs,
so it builds with no network dependency.

## The dashboard & CLI

```
specter new    --name backup1       # generate + save a named identity (guaranteed never-used)
specter rotate --pkg <package>      # the per-signup button: new + push + clear
specter push   --name backup1       # push a saved identity + clear the app
specter list                        # your saved identity vault
specter reuse  --name backup1       # reload a saved identity as active
specter stats                       # how many identities issued (the anti-reuse ledger)
specter verify --pkg <package>      # deep on-device verification (see below)
specter tui                         # the rich dashboard
```

**Named vault** — back up a good identity, reload it later. **Used-ID ledger** — every issued id is
recorded and never reused (verified: 5000+ generations, zero collisions, race-safe under concurrency).

## Prove it works — the verify harness

`specter verify` is a questionnaire-driven, on-device verification suite. It doesn't trust the tool's
own claims — it reads back what the target app actually stored:

- **Coverage** — what identifiers does the app read, and do we rotate every one?
- **Rotation** — launch the app N times, refresh the identity between each, confirm the app *sees* a
  different identity each launch (and warns loudly if the hook produced nothing — no false passes).
- **Backup/reload** — save an identity, rotate away, reload it, confirm the round-trip is exact.
- **Leak audit** — compare OS-side ground truth to what the app stored; flag any real device id leaking.

## Quality

```
uv run --with pytest --with rich python -m pytest -q     # 57 tests
```

Reviewed by multiple independent reviewers (a code-review subagent, CodeRabbit, Kilo, gemini-code-assist,
codex) — every finding fixed with a regression test. Guarantees under test:

- **No identifier is ever reused** — even under concurrent processes (file-locked, atomic, re-read-merge).
- Every generated id is **format- and validity-valid** (Luhn, lengths, MAC bits, RFC-4122 v4, GSF ≤ Long.MAX).
- Profiles are **coherent** (fingerprint ↔ Build; IMSI ↔ carrier).
- We cover **every identifier surface** GeerGit rotates (parity test vs its Dart strings).
- The module's profile path **can't drift** from the pusher's (parity test).

## Project layout

```
specter/        core: identifiers · generators · profile · device · cli · tui · verify · validation
xposed-module/  the LSPosed module (Java) + Xposed API stubs + build-apk.sh
data/           devices.json (499 real device profiles, from GeerGit's own DB)
docs/           regression diagnosis · on-device status · hook spec
tests/          57 tests
```

## Status

- ✅ Core, CLI, TUI, verify harness — built + tested (57 tests).
- ✅ LSPosed module — builds, installs, correct Xposed markers.
- ✅ Push pipeline — verified end-to-end on a Pixel 4.
- ⏳ Enabling the module in LSPosed is a one-time manual step (see `docs/ON-DEVICE-STATUS.md`).

## Legal

For research and testing on accounts and devices you own or are authorized to manage. You are
responsible for complying with the terms of any service you use it with.
