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

## Current state (2026-07-26, updated) — client work COMPLETE, one user-gated step remains
**FIRST READ `docs/OVERNIGHT-QUEUE.md` (progress log at the bottom) and
`handoffs/2026-07-26_signal-coverage-complete-server-bucket-blocker.md`.** Everything below is on branch
`feat/ua-spoof` / PR #20 (mergeable, all tests green, two code-review passes done).

**What shipped (all verified on the probe):** UA spoof (the proven anchor), the inverted MODEL/DEVICE
column fix, APK install-mtime (FileTimestamps), installed-app hiding, per-SoC /sys cpu_capacity+gpu_model+
present, /proc/version kernel banner, Build.VERSION.SDK_INT, display metrics (getDisplayMetrics), the full
sensor tuple (name+vendor+resolution+maxRange+power), MediaDrm deviceUniqueId — plus a polished Protections
UI with REAL gate-verified toggles. Native `__system_property_get` parity is CLOSED (probe dual-read shows
_java==_native for every aliased prop) — Specter now matches byedentity's one former edge.

**The FPJS visitorId does NOT split in the demo's SHARED public workspace — and that is PROVEN not to be a
client leak.** The definitive test: pushed IMPOSSIBLE-garbage device values that VERIFIABLY reached the SDK
(the demo rebuilt its UA as "EXTREME-TEST-9000") — the id did not move. Also: the fully UNSPOOFED real
device gets the same id; deleting the SDK's entire cache/keystore/external-data doesn't change it. So the
shared demo workspace ignores client device signals entirely (it's a coarse per-IP/per-device bucket). Do
NOT keep attacking it — that's settled.

**THE ONE REMAINING STEP (user-gated, unscriptable):** a valid split test needs the USER's own FPJS
workspace. `pm clear`/`rotate` wipes the demo's user API keys (encrypted, device-bound prefs), dropping
into the shared workspace. The user must, ONCE, open the demo -> Settings -> "Use your API keys" = ON ->
paste the Public key. THEN run: **`python scripts/fpjs_split_test.py`** — it applies two different profiles
with `push --no-clear`, identifies each, and reports WIN (ids differ) or SAME. Every client signal is
already spoofed to make the split succeed in a real workspace.

## If you're a fresh session with NOTHING new to do on the gate
The client-side engineering is complete and exhaustively verified (3 independent proofs: garbage test,
native dual-read, persistence audit). Don't manufacture marginal work. Useful things you CAN do: respond
to genuine new findings, run more adversarial `code-reviewer` passes on any new code, keep the UI polished,
or — only if the user asks — squash-merge PR #20 (it's a large FPJS-research PR; the user should review it
first, so do NOT auto-merge without their go-ahead).

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
