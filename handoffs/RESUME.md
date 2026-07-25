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

## The objective for THIS run — beat the sample app's reported id (UA leak now CLOSED)
**FIRST READ `handoffs/2026-07-26_ua-fix-shipped-server-match-anchor.md`** — current state, the proven
findings, and the one manual step that unblocks clean measurement. Short version:

The User-Agent leak (the previously-proven anchor) is **fixed and confirmed**: the sample app's server
now reports the applied profile's device + UA, not the real one. Two more real fixes shipped alongside:
the inverted MODEL/DEVICE dataset columns (every profile had been shipping an impossible fingerprint),
and the APK install-mtime signal the sample reads (`FileTimestamps`). All on branch `feat/ua-spoof`.

BUT the sample's reported id still doesn't split across applied profiles, and we proved WHY: deleting the
sample's ENTIRE local cache and re-reading returns the identical id → the id is computed **100%
server-side** from the signal payload. The remaining anchor is a signal (or set) still sent truthfully —
NOT the UA, device fields, file timestamps, or IP (all ruled out). Prime suspects, in order: the
installed-app set, then whatever corroborates the server's `rootApps`/`developerTools` Smart Signals
(both are server-computed — the client-side dev-settings hook is confirmed working but not sufficient).

The blocker for clean measurement: a valid two-rotation test must run in the USER's own measurement space
(sample-app keys present), and `pm clear`/`rotate` wipes those keys, dropping into a shared public
workspace whose ids are meaningless bucket artifacts. The keys can only be re-entered via the sample's UI
(encrypted, unscriptable) — see the handoff for the exact one-line manual step. Until then, use
`push --no-clear` and pursue the signal-elimination hunt; do not treat shared-workspace ids as the gate.

--- (prior objective, DONE + merged — kept for context) ---
Built GOAL 1.3: the hardware-descriptor configuration layer, coherent per device model, verified on the
probe app. That work is complete and merged; it just wasn't the anchor. Concretely it:

1. **Assembled a per-model hardware dataset.** For each device row in the pool, coherent hardware
   descriptors the sample apps read: sensor list, camera list, GPU/GLES renderer string, `/proc/cpuinfo`
   contents, codec list, core count, input devices.
2. **Applied them** — extended the Java (LSPosed) hooks and the native (Zygisk) layer so an app reading these
   descriptors gets the profile's values, coherently, per applied identity. Replaced the current
   threshold-probe placeholders in `HookEntry.hookHardwareSignals()` with real per-model values.
3. **Byte-parity** — mirror any seeded-draw changes in Python and Java and prove parity with the dumper
   (see `CLAUDE.md`), since these become part of generated profiles.
4. **Verify on the probe app** (`com.specter.probe`): the probe reads every descriptor both ways and
   writes JSON; confirm each descriptor now reports the profile's coherent value, per identity. This is
   the completable, measurable success criterion for this run — it does not depend on the vendor demo's
   server record.
5. As a secondary read-out, run the vendor sample-app harness and record the full reported response
   (`suspectScore`, all smart-signal fields, `firstSeenAt`, and the reported id) for two applied profiles,
   noting which fields move. Do NOT treat the sample-demo's main reported id as the pass/fail gate for
   this run — it is confounded by a fixed-key server record (documented in `docs/IDEAS.md`); use the
   probe-app verification as the gate instead.

Definition of done for this run: the probe app reports coherent, per-identity hardware descriptors for
every field added; byte-parity proven; tests green (`.venv/Scripts/python.exe -m pytest -q` and
`cd xposed-module && bash run-jvm-tests.sh`); CHANGELOG/GOAL/IDEAS/DECISIONS updated; work committed +
pushed. Then continue to the next unblocked queue item in `docs/GOAL.md` and keep going.

## Context you'll want
- Full prior investigation + DO-NOTs + device/build details:
  `handoffs/2026-07-26_0013_device-identity-consistency-continuation.md`.
- Standing spec + queue: `docs/GOAL.md`. Running log: `docs/IDEAS.md`. Decisions: `docs/DECISIONS.md`.
- Device: Pixel 4, serial `9B151FFAZ00FPF`. Build native: `bash xposed-module/build-zygisk.sh`;
  build the LSPosed APK: `bash xposed-module/build-apk.sh`; on-device install loop:
  `bash xposed-module/zygisk/dev-scripts/reinstall.sh`.
- Already proven this session set: every device-identifier read path is applied correctly on the sample
  app, yet its main reported id is frozen by a fixed-key server record — hence the objective above targets
  the probe app as the measurable gate.

## The one thing that needs the user (do NOT block on it)
A fresh vendor trial key would give a clean measurement space for the sample-demo's main reported id. It
needs the user's signup. Leave a labelled `docs/IDEAS.md` note when you reach the point where it would
help, and CONTINUE with the probe-app-verified work above — do not idle.
