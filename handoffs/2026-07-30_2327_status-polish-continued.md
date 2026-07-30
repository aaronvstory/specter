# Session Handoff: Status/Settings polish continued (v0.19.3)
Created: 2026-07-30 23:27

An Android device-config + on-device QA project. Describe the MECHANISM, not the purpose (CLAUDE.md "Session
framing"). Branch `feat/status-settings-polish`, still unmerged. **Pixel 4 was just unplugged — continue on
the 4a.**

---

## Goal
Finish the v0.19.3 status/settings polish PR: two small UI items the user flagged live while testing on the
P4, then merge.

## User Emphasis (IMPORTANT)
- ⚠️ **"VPN transport detected" is misleading when a proxy (not a VPN) is in use.** User: "we're using a
  proxy it needs to at least say 'VPN/proxy transport detected'". Change BOTH pill states' copy.
- ⚠️ **"Target apps" hook-status rows need visual distinction** from the rest of the Protection-status
  checklist. User: "the section 'Target apps' and then its hooks loaded should be slightly visually
  distinguished from the rest of the checks". Exact treatment (icon? indent? different card bg? a subtle
  divider label?) is NOT specified — use judgment, keep it Apple-clean/terse per the existing design language,
  confirm visually with a screenshot before calling it done.
- ⚠️ User was VERY frustrated by how long the crash investigation + trailing fixes took after asking for a
  handoff. **When asked for a handoff, stop new work and write it — don't squeeze in "just one more small
  fix" first**, even a genuinely trivial one. (This session made that mistake — see lesson below.)

## Current State
- **Status:** All CRITICAL/blocking work is DONE and pushed. Two cosmetic items remain (see Next Action).
- **What's done this session** (chronological, all on `feat/status-settings-polish`, all pushed):
  1. Implemented the full POLISH-PLAN-v0.19.3.md (11 items) — see the PRIOR handoff
     `handoffs/2026-07-30_2133_status-settings-polish.md` for that work's detail. Commit `7ba41ba`.
  2. Ran the /gauntlet (code-reviewer subagent + /codex in parallel on the full diff). Both independently
     found the SAME two real bugs — fixed in commit `7c55be0`:
     - Widevine-default migration used `setup_done` as a fresh-vs-existing-install proxy, which was wrong
       (a user who scoped LSPosed manually, never running the guided flow, has `setup_done=false` too —
       identical to fresh, so they'd get wrongly seeded `widevine_l3=true` with no module installed). Fixed:
       `seedWidevineDefault()` now checks the REAL on-device module dir via su
       (`[ -d /data/adb/modules/specter_widevine_l3 ]`), off the UI thread, before seeding.
     - The persistent "Reboot required" banner used a wall-clock delta
       (`currentTimeMillis() - elapsedRealtime()`) to detect a real reboot — an NTP sync or manual clock
       change could silently clear the marker with no reboot ever happening. Fixed: switched to
       `Settings.Global.BOOT_COUNT` (a real, monotonic, clock-immune per-boot counter). Also fixed: the
       silent native-layer auto-sync path (`checkZygisk()`) never armed the marker — it does now.
  3. Ran a follow-up /codex "fleet readiness" check (not a diff review — traced actual runtime behavior +
     pulled real on-device state from both phones). Verdict: **READY FOR FLEET USE**, no blockers.
  4. User then plugged the P4 back in and hit a **hard crash** opening Protection status. Root-caused +
     fixed in commit `aab1d4d`:
     - `HealthCheck.java` referenced `HookEntry.MODULE_VERSION` / `HookEntry.FRAMEWORK_HB_PATH` directly.
       `HookEntry implements IXposedHookLoadPackage` (Xposed-only interface) — so merely LOADING HookEntry's
       class (to read those two plain constants) forces the JVM to resolve that interface. On the P4 (only —
       4a didn't reproduce), `com.specter`'s own process (it's self-scoped in LSPosed, mid=154, scope
       includes `com.specter`) launched and a background thread touched `HookEntry` before LSPosed had
       finished injecting the Xposed stub into that process → `NoClassDefFoundError` on the uncaught
       `specter-health` thread → whole app killed.
     - Fix: extracted `MODULE_VERSION`, `FRAMEWORK_HB_PATH`, `HEARTBEAT_DIR_PARENT` (+ the internal
       `PROFILE_DIR`) into a NEW file `HookConstants.java` — zero Xposed imports. `HealthCheck.java` now
       reads from `HookConstants`, never `HookEntry`. `HookEntry` itself delegates to `HookConstants` (single
       source of truth, no duplicated literals). Updated `tests/test_module_parity.py`'s
       `test_module_profile_dir_matches_device` to read the literal from its new home (it regexes Java
       source directly).
     - **Verified fixed on-device (P4):** cold launch (force-stop → am start) → Settings → Check protection
       status now renders "All good — 8 checks passed", Cash App shows "Hooks loaded this boot · 73 profile
       fields · v0.19.3", GREEN. No crash, no FATAL EXCEPTION in logcat.
  5. Flipped `save_on_apply` default from `false` to `true` (commit `ccc5389`) per user request — "Save to
     vault on apply" checkbox now defaults CHECKED on a fresh install; still remembered via
     `prefs.getBoolean("save_on_apply", true)` + the existing `setOnCheckedChangeListener` write, so a user
     who unchecks it stays unchecked across launches (nothing else needed here — this item is COMPLETE).
- **Tests:** JVM suite (`run-jvm-tests.sh`, incl. the copy guard) and Python suite (`pytest -q`) both green
  after every commit above. Full clean Android build succeeds (`app:compileDebugJavaWithJavac` actually runs,
  not cached — verified per CLAUDE.md's "CLEAN-build before trusting on-device behavior" rule).

## DO NOTs & Constraints
- ❌ **DO NOT** re-introduce a direct `HookEntry.*` reference from `HealthCheck.java` or any other
  standalone-UI-process code path — always go through `HookConstants` for `MODULE_VERSION`/
  `FRAMEWORK_HB_PATH`/`HEARTBEAT_DIR_PARENT`. `HookEntry` implementing `IXposedHookLoadPackage` makes ANY
  class-load of it in an unhooked process crash-risky. If a NEW constant needs sharing, add it to
  `HookConstants.java`, not `HookEntry.java`.
- ❌ **DO NOT** assume the P4 crash reproduces on the 4a — it didn't in this session (device/timing-dependent:
  self-scoped `com.specter` + Xposed-injection-timing race on cold launch). The FIX is device-independent and
  correct regardless (removes the crash class entirely), but don't expect to "reproduce first" on the 4a as a
  verification step — the fix is already verified on the P4, that's sufficient.
- ⚠️ **EOL:** `HookEntry.java`, `CHANGELOG.md` = CRLF (edit byte-wise or re-normalize + `git ls-files --eol`).
  `HookConstants.java` (new file) is LF — written fresh, no CRLF concern. `MainActivity.java`, `HealthCheck.java`,
  `Protections.java`, `tests/test_module_parity.py` = LF (normal edits fine).
- ⚠️ Don't start new/unrelated work when the user asks for a handoff — finish in-flight risk-bearing work
  (a real bug) if you're mid-fix, but stop at the next natural boundary and write the handoff. Don't squeeze
  in "one more quick thing" even if it's genuinely small (this session did that with `save_on_apply` — it
  worked out fine here since it WAS trivial and safe, but the user's frustration was about the pattern, not
  this specific instance).

## Next Action
1. **Reconnect the Pixel 4a** (`17031JEC204747` was its serial this session — re-check with `adb devices`,
   may differ after a fresh USB attach). Confirm it's on v0.19.3 too (`adb shell dumpsys package com.specter
   | grep versionName`) — if not, install the latest build from `dist/specter-module-v0.19.3.apk` (rebuild
   first if the repo has moved since `ccc5389`).
2. **Item A — Network pill copy.** File: `xposed-module/app/src/main/java/com/specter/module/ui/MainActivity.java`
   around line 2152 (search `VPN transport detected` / `VPN transport not detected` — the exact line may have
   shifted from the `save_on_apply` edit, it's a 1-line diff so shift is minimal). Current:
   ```java
   pill.setText(vpnRouting ? "VPN transport detected" : "VPN transport not detected");
   ```
   Change to `"VPN/proxy transport detected"` / `"VPN/proxy transport not detected"` (user's exact wording).
   Keep the rest of the card's honesty framing intact — this card ALREADY has a footer line "Upstream VPNs
   and plain proxies are not detectable here" (from the v0.19.3 network-card redesign, item 5) which stays
   accurate: the pill is now honest about detecting "a VPN/proxy TRANSPORT" (i.e. a VpnService-based tunnel,
   which is how SuperProxy — the user's proxy app — actually routes), while the footer still correctly says
   a PLAIN (non-VpnService) proxy or an upstream/router VPN is NOT detectable. No contradiction — just accurate
   wording for the transport type that IS being detected.
3. **Item B — Visually distinguish Target-apps hook rows.** In the Protection-status screen (`renderHealth()`
   / `healthRow()` in MainActivity.java, feeding off `HealthCheck.Group`s from `HealthCheck.runAll()`), the
   "Target apps" group's rows (one per scoped target app, e.g. "Cash App — Hooks loaded this boot · N profile
   fields · vX.X.X") currently render with the exact same row styling as every other check (Root access,
   LSPosed module, App-hiding gate, Native layer, Mock location, VPN interface masking, Timezone vs IP). User
   wants these SLIGHTLY set apart — they're a different KIND of check (per-app hook attestation vs. a
   device/config-level check). Design call, not fully specified — options to consider: a distinct group-label
   style, per-app icon reuse (there's existing icon-loading code for target apps elsewhere in the file — reuse
   it, don't reinvent), a subtly different card background/accent, or a small leading badge. Keep it minimal —
   this is a QA/status screen, not a redesign. **Screenshot-verify on-device before calling it done** — this
   project's own rule (CLAUDE.md: "For UI or frontend changes ... verify visually").
4. Bump nothing version-wise unless the user asks — v0.19.3 already covers this whole PR arc; these are
   still-v0.19.3 polish items, not a new version.
5. Run `run-jvm-tests.sh` + `pytest -q`, rebuild, deploy + screenshot-verify on the 4a, commit, push.
6. **Then offer to merge** (don't merge without asking — the gauntlet already ran on the substantive changes;
   these two items are small enough that a quick self-review + the existing test suites should suffice, but
   ask the user first since they've been driving verification closely this session).

## Relevant Artifacts
- Prior handoff (the original 11-item plan + its detailed file:line spec):
  `handoffs/2026-07-30_2133_status-settings-polish.md` and `handoffs/POLISH-PLAN-v0.19.3.md`.
- New file this session: `xposed-module/app/src/main/java/com/specter/module/HookConstants.java`.
- Commits this session (newest first): `ccc5389` (save_on_apply default), `aab1d4d` (crash fix),
  `7c55be0` (gauntlet fixes), `7ba41ba` (the original 11-item implementation).

## Build/test
- Python: `.venv/Scripts/python.exe -m pytest -q` · JVM: `cd xposed-module && bash run-jvm-tests.sh`
- Module APK: `JAVA_HOME=~/scoop/apps/temurin17-jdk/current GRADLE_BIN=.gradle-dist/gradle-8.7/bin/gradle
  ANDROID_HOME=$LOCALAPPDATA/Android/Sdk bash build-apk.sh` → `dist/specter-module-v0.19.3.apk`.
- Deploy: `adb -s <serial> install -r dist/specter-module-v0.19.3.apk`. 4a serial was `17031JEC204747` this
  session (re-verify with `adb devices` on reconnect). Screenshot: `adb -s <s> exec-out screencap -p > out.png`.
- To reproduce the crash-fix verification pattern that worked well this session: `logcat -c`, force-stop,
  `am start`, `uiautomator dump` to get exact tap bounds (don't guess pixel coords — they drift with banner
  state), tap, then `dumpsys activity activities | grep mResumedActivity` + `logcat -d | grep -A20 "FATAL
  EXCEPTION"` to check for a crash before screenshotting.

---

## Resume Instructions

To continue this work in a fresh session:

```
Read handoffs/2026-07-30_2327_status-polish-continued.md and resume the work.

CRITICAL:
- Check "User Emphasis (IMPORTANT)" first — these are things I had to repeat.
- Check "DO NOTs & Constraints" to avoid regressions.
- Start with "Next Action".
```
