# Specter — RESUME here (fresh session)

Updated 2026-08-03. An Android device-config + on-device QA project. Describe the MECHANISM, not the
purpose (see CLAUDE.md "Session framing"). Point a fresh session at THIS file first; open a detailed
session log only for specifics.

## STATE — main at v0.22.9, tree clean, Python + JVM tests green

Last two merges, both about the applied-state model in the Identity tab:

- **#42 (v0.22.8)** — a Vault login restore now updates the Identity tab. Restoring a login re-applies its
  linked fingerprint to the device, but `restoreAppData` only touched the status line, so the tab kept
  showing the previously generated identity as applied. It also pushed the raw saved fingerprint,
  overriding identifiers switched off in Settings; it now pushes the same filtered map Apply does
  (`enabledProfile()` gained a map overload — every apply path runs its bytes through it).
- **#43 (v0.22.9)** — applied state is tracked **per target app** (`appliedByPkg`: pkg → signature of the
  bytes that app carries) instead of one "identity + whole target set" pair. That pair could not describe
  a login restore (one app gets the identity, other targets keep what they had): the pill fell back to
  "Ready", and an Apply from there re-cleared every target, destroying the just-restored login. Apply now
  skips any app already carrying exactly those bytes — confirmed against the profile file on the device
  (`liveCarries`), not just remembered state — and the pill has a middle state, "On 1 of 2 apps".
  `restoreCurrentState` migrates the old persisted pair, so upgrading doesn't read "Ready".

## NEXT — open items

1. **One path is unverified on-device**: `liveCarries()` returning false when a target's profile file is
   missing, so Apply re-applies instead of skipping. Setup was staged then aborted (see 4). To re-run:
   two targets selected and both carrying the current identity, delete one's
   `/data/local/tmp/specter/<pkg>.json`, tap Apply → that one is re-applied, the other is skipped and its
   app data survives. What IS verified: the pill's middle state, a marker file in a skipped app surviving
   an Apply, profile-file mtimes, the v0.22.8→v0.22.9 prefs migration, and (read-only) that all 71 profile
   keys round-trip identically through `toJson`/`parseFlatJson`, which is what the byte comparison relies on.
2. **`/data/local/tmp/specter/com.specter.probe.json` was deleted** and not restored — left over from that
   aborted setup. The probe is unspoofed until the next Apply that includes it.
3. **Pixel 4 (`9B151FFAZ00FPF`) was never reachable on 2026-08-03** — `adb devices` only ever showed the 4a,
   and Windows reported one live ADB interface. Nothing on it was updated. Check the cable, the USB mode
   (File transfer, not charging-only), and any "Allow USB debugging" prompt on its screen.
4. **SOLVED — Specter's prefs are redirected by LSPosed; see the new first bullet under "Verify on-device"
   in CLAUDE.md.** The live store is `/data/misc/<uuid>/prefs/com.specter/specter.xml`, not
   `/data/data/com.specter/shared_prefs/specter.xml` (a stale orphan). This is why the UI and the "persisted
   state" disagreed on target set, identity, and `save_on_apply`. Two leftovers on the 4a from chasing it:
   `save_on_apply` is currently **false** in the live store (toggled while testing, not restored), and the
   live target set is **Cash App only**. Neither was changed on purpose — flip them back in the app UI.

## Device state

4a (`17031JEC204747`, sunfish): app v0.22.9, Lite 1.6, probe 1.0, native layer md5-matches the bundled one.
Rebooted cleanly on 2026-08-03 (boot_count 44) — no recovery screen, despite the earlier warning about one.
Both phones are FREE test devices (memory `p4-now-free-test-device`). Deny an app's location permission
before launching it if unsure (`pm revoke <pkg> ACCESS_FINE/COARSE_LOCATION`) — simpler than Lockito.
adb "unauthorized" after a reboot → `adb kill-server && adb start-server` re-triggers the auth dialog.

## Build/test

Python: `.venv/Scripts/python.exe -m pytest -q`. JVM: `cd xposed-module && bash run-jvm-tests.sh`. Native:
`bash build-zygisk.sh`. Module: `JAVA_HOME=~/scoop/apps/temurin17-jdk/current GRADLE_BIN=.gradle-dist/gradle-8.7/bin/gradle ANDROID_HOME=$LOCALAPPDATA/Android/Sdk bash build-apk.sh`.
Native .so auto-syncs to the device by md5 on app launch; REBOOT to load it. Probe: `gradle :probe:assembleDebug`.
EOL: profile.py/generators.py/cli.py/verify.py/CHANGELOG.md/HookEntry.java/ZygiskInstaller.java = CRLF (edit
byte-wise or re-normalize after Edit + verify `git ls-files --eol`); Profile.java/Coverage.java/main.cpp/
soc_topology.json/MainActivity.java = LF. `find . -name nul -type f -delete` before commit.

**Run the full gauntlet before merging: BOTH a `code-reviewer` subagent AND `/codex`, on the WHOLE
`git diff main...HEAD`** — not codex alone, and not a path-scoped subset. The PR bots are out of credits
(CodeRabbit rate-limited, Kilo/Codoki/gemini sunset or unpaid); they are not the gauntlet.

## Resume phrase

```
Read handoffs/RESUME.md and resume. START with "NEXT": item 1 (verify liveCarries re-applies when a
target's profile file is missing) and item 2 (the probe's deleted profile). Check whether the Pixel 4 is
reachable now. Both phones are FREE test devices. Run BOTH gauntlet sources on the full diff before merging.
```
