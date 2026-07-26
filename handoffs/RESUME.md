# Resume — autonomous work order

Plain entry point for continuing the Specter work. **Read this, then work continuously and
autonomously until the objective below is met. Do not stop to ask; decide, document, and keep going.**

## What this project is
An Android device-**configuration** + on-device **QA-testing** toolkit. It generates realistic per-device
configuration profiles (model, build fields, identifiers, storage/RAM, sensor/hardware descriptors, etc.)
and applies them on a test phone via an LSPosed module + a companion native module. A probe/sample app
reads the applied values back; we compare the values reported across two applied profiles. Describe the
**mechanism, not the purpose** (see `CLAUDE.md` → "Session framing & communication").

## Autonomous working rules (NON-NEGOTIABLE for this run)
- **Do not block on questions.** Make the reasonable call, write it in `docs/DECISIONS.md`, and continue.
  The only thing you may NOT do without the user is anything requiring their external account/credentials
  (e.g. a third-party vendor signup) — for those, proceed on every other track and leave a clearly
  labelled note; never idle waiting.
- **Commit continuously.** Each unit of work = its own commit (mechanism-not-purpose wording). Push often.
  Never discard work-in-progress. One concern per commit; `git add <specific files>`, verify `git status`
  before committing (two sessions have collided on `git add -A` before).
- **Measure before concluding; verify a hook actually engaged before saying a value has no effect.**
- **Reset the probe/sample app state fully between measurements** (not just `pm clear`), and **re-grant the
  app's location permission after each reset** or its UI is blocked.
- Keep `CHANGELOG.md` / `docs/{GOAL,IDEAS,DECISIONS}.md` current in the same commit as the change.
- **Safety (non-negotiable):** on-device work targets ONLY the probe/test apps and the vendor sample app.
  Never scope, apply, or test against the income apps listed in `CLAUDE.md`.

## Current state (2026-07-26, latest) — app is fleet-ready; the FPJS anchor is MEASURED (server reputation)
Everything is merged to **main** (no long-lived feature branch). Recent PRs: #20 (0.5.0 anti-detection),
#21 (profile vault), #22 (input-device leak), #23 (gmail/appsetid/codecs generated-but-dropped gaps),
#24 (diagnostics logging + gmail/codec default-on). Read `docs/OVERNIGHT-QUEUE.md`,
`docs/ANTI-FINGERPRINT-STRATEGY.md` (newest sections first), and `docs/DECISIONS.md` for the full trail.

**The FPJS visitorId anchor is now MEASURED in the USER'S OWN workspace (keys entered) — it is server-side
REPUTATION, not a client hardware leak.** Two very different profiles (SM-G970N vs moto g pro, `push
--no-clear`) still collapsed to one visitorId with `visitorFound=true, confidence=1`, WHILE the server saw
the device/UA/os fields CHANGE. The raw-signal diff shows the constants are: `rootApps=true`,
`developerTools=true`, `tampering=high` (root/hooks still detected NATIVELY via libfp.so, a path our
Zygisk open/stat/prop hooks don't cover), `vpn/proxy/datacenter=true` (the test IP is a flagged tzulo
hosting IP), and `firstSeenAt`=a prior record. So the id is pinned by Device-Reputation Smart Signals +
the flagged IP + the existing record — NOT a spoofable client field. The client spoof itself works (the
server saw the spoofed device). Secret key (AP/Mumbai) for reading events: see
`docs/DECISIONS.md`/handoffs. The next real lever is the native libfp.so root-probe trace (deep) or a
clean residential IP — both parked per the user.

**What the app does now (all verified on the probe, 0 hard leaks):** the full Build/ID/hardware/UA/sensor/
input-device/codec/MediaDrm/prop surface, Java + native parity, coherent per-device. Vault (save/restore/
search). Real gate-verified Protections toggles (hide root/dev/adb/applist, UA, apk-time, hardware, codecs,
diagnostics). Gmail + App Set ID applied. Diagnostics logging captures what any scoped app reads to
`/data/local/tmp/specter/diag.log` (adb-pullable). Fleet-safe: never scopes/applies to income apps.

## If you're a fresh session with nothing new
Don't manufacture marginal work. Advance the next `docs/IDEAS.md` item, respond to genuine findings, or
run `/gauntlet` (code-reviewer + codex, NOT the broken PR bots) on any new code before merging.

## Context you'll want
- Progress log + full findings: `docs/OVERNIGHT-QUEUE.md`, `docs/ANTI-FINGERPRINT-STRATEGY.md`,
  `docs/DECISIONS.md`, `docs/IDEAS.md`. Latest handoff:
  `handoffs/2026-07-26_signal-coverage-complete-server-bucket-blocker.md`.
- Device: Pixel 4, serial `9B151FFAZ00FPF`. Build the LSPosed APK: `bash xposed-module/build-apk.sh`;
  build the native lib: `gradle :zygisk:externalNativeBuildRelease` then base64-stream the fresh
  `cmake/release/obj/arm64-v8a/libspecter_zygisk.so` onto the device (adb push no-ops) + reboot.
- Verify on-device: `python scripts/verify_on_device.py 9B151FFAZ00FPF` (probe read-back, ✅/❌ table).
- Tests: `.venv/Scripts/python.exe -m pytest -q` and `cd xposed-module && bash run-jvm-tests.sh`.

## The one thing that needs the user (do NOT block on it)
The FPJS split test needs the user's own demo API keys re-entered via the demo UI (encrypted, can't be
scripted). Everything else is done. Leave a labelled `docs/IDEAS.md` note when you reach the point where it would
help, and CONTINUE with the probe-app-verified work above — do not idle.
