# Session Handoff: Status/Settings polish continued (v0.19.3)
Created: 2026-07-30 23:27

An Android device-config + on-device QA project. Describe the MECHANISM, not the purpose (CLAUDE.md "Session
framing"). Branch `feat/status-settings-polish`, still unmerged. **Pixel 4 was just unplugged — continue on
the 4a.**

---

## Goal
Finish the v0.19.3 status/settings polish PR: two small UI items the user flagged live while testing on the
P4 (Item A/B below), PLUS a new cluster of apply/identity-state bugs the user found on the 4a (Item C below,
NOT YET INVESTIGATED beyond a first-pass code trace — see "New bug cluster"). Then merge.

**User's explicit instruction for next session: launch TWO /codex reviews in parallel** — one on the recent
crash-fix diff (sanity-check `aab1d4d` + `ccc5389`, already gauntlet-passed but a fresh pair of eyes doesn't
hurt given how the P4 crash slipped through the first gauntlet), and a SECOND one dedicated to the new bug
cluster in "New bug cluster" below — hand codex the user's raw description verbatim (quoted below) plus this
session's own code trace, and let it figure out the actual root cause(s) and propose a fix. Don't do the fix
work yourself first — that's explicitly what the user asked to hand to codex.

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

## New bug cluster (found on the 4a, NOT YET FIXED — hand to a dedicated /codex, see Goal)

User's raw report (verbatim, for handing to codex as-is so nothing gets lost in translation):

> when a currently offered fingerprint is already applied to an app, and we click apply again, the toast
> (which is ugly — up top it shows up — should have a slightly distinctive look) says "randomize" but the
> button to randomize says "generate identity" so those are incoherent.. the popup should say "generate ..."
> also, I now tried to apply to an already applied and it did a new popup asking for a new name, then I closed
> it, then i clicked apply again and only then did it recognize it's already been applied ... it might've
> missed that it's already applied when i closed and reopened the app or idk ... and between when we close
> (soft close) and reopen the app - up top it gives that same toast and says "Identity not applied ..." then
> goes away and says applied.. so ya we need a better toast there looking better and perhaps say something
> like "Checking" or something like that instead of defaulting to identity not applied then going away.. and
> then some weird issue between this happened where we didn't even generate a new identity and somehow a new
> identity appeared there

This session did a FIRST-PASS code trace (no on-device repro yet — P4 was already unplugged when this was
reported) that found three concrete, well-anchored leads. Hand these to codex as a starting point, not a
final diagnosis — codex should verify/extend, not just rubber-stamp:

1. **Toast/button copy incoherence (the easy, confirmed one).**
   `MainActivity.java:519` — `String msg = "Already applied. Relaunch the app(s), or tap Randomize for a new
   one.";` — says "Randomize", but the actual button at `MainActivity.java:955` is labeled
   `"Generate another identity"`. Straightforward fix: make the toast say "Generate another identity" (or
   whatever the button's final wording is), so they match. Low risk, do this one directly.

2. **`appliedSig`/`appliedTargets` are in-memory-only, never persisted — likely the root cause of BOTH the
   "already applied" being missed after close/reopen AND the "Identity not applied" flash on resume.**
   `MainActivity.java:86-91` declares:
   ```java
   private String appliedTargets = "";   // comma-sep pkgs the CURRENT profile was applied to
   private String appliedSig = "";       // signature of the LAST successful apply
   ```
   Both are plain instance fields — NOT written to `SharedPreferences`, NOT restored via
   `onSaveInstanceState`/a bundle, nothing. If Android kills the `com.specter` process while backgrounded
   (a "soft close" that's actually a process death — very possible, this session saw Xposed-injection-timing
   evidence that com.specter's process lifecycle is already fragile, see the P4 crash fix above) and the user
   reopens, `onCreate()` runs fresh with `appliedSig=""` — even though the identity WAS actually pushed to the
   target app's profile file on disk (root-owned, in `/data/local/tmp/specter/` per `HookConstants.
   HEARTBEAT_DIR_PARENT`). This would explain:
   - "click apply again and only then did it recognize it's already been applied" — the FIRST apply-after-
     reopen re-did a real apply (since `appliedSig` was reset to `""`), which itself SET `appliedSig` again
     (see `MainActivity.java:568`), so the SECOND apply-again correctly saw "already applied". One real
     apply happened invisibly in between.
   - The "Identity not applied ... then goes away and says applied" toast/status flash on resume — likely
     `onResume()` (`MainActivity.java:305-311`) calling `render()` synchronously reads the reset (empty)
     `appliedSig` BEFORE any async status re-check catches up (if one exists — worth checking whether
     `render()`'s "applied" logic at `MainActivity.java:940` triggers any follow-up work, or whether it's
     truly synchronous/stale until the next explicit action).
   - Confirm/deny by checking: does `Targets`/`Vault`/anything ELSE persist an "applied" marker anywhere
     (SharedPreferences, a file) that `onCreate` could restore `appliedSig`/`appliedTargets` FROM? If not,
     that's the actual gap — this state needs to survive process death, either by reading it back from the
     on-device profile files (root/su, same as HealthCheck already does for target-app hook attestation) or
     by persisting the signature+targets pair to SharedPreferences on every successful apply.

3. **"a new identity appeared there" without the user generating one — likely the SAME root cause as #2.**
   `MainActivity.java:179` (`onCreate`) calls `regenerate()` UNCONDITIONALLY on every Activity creation —
   including a process-death-triggered recreation after a soft-close, not just a genuinely fresh install.
   `regenerate()` (`MainActivity.java:478`) generates a brand-new random profile. So: process dies while
   backgrounded → user reopens → `onCreate` → `regenerate()` fires → a NEW identity appears, discarding
   whatever was showing (and possibly already applied) before, with NO explicit user action. This likely
   needs the SAME fix direction as #2: don't blindly `regenerate()` on every `onCreate` — check whether a
   profile already exists (in-memory-lost-but-recoverable, or read back from wherever #2's investigation
   finds durable state) before generating a fresh one. Ties into #2 for one coherent fix, not two.

**Framing for whoever picks this up:** all three symptoms plausibly trace to ONE structural gap — this
Activity treats "current identity" + "applied state" as pure in-memory session state with no durability
across process death, which on Android is NOT a rare edge case (background apps get killed routinely under
memory pressure). The fix is likely "persist the minimum durable state (current profile? applied
signature+targets? both?) so `onCreate` can restore instead of always starting fresh" — but let codex verify
this diagnosis against the actual full flow before committing to an approach; this session's trace is a
lead, not a confirmed root cause. NOT reproduced on-device this session (P4 already unplugged) — the next
session should reproduce on the 4a first (soft-close via home button or `am kill com.specter` to simulate
process death, not `force-stop` which is a harder kill) before proposing a fix, so codex has real evidence
to work from, not just static analysis.

## Current State
- **Status:** All CRITICAL/blocking work is DONE and pushed. Two small cosmetic items (A/B) plus one
  not-yet-diagnosed bug cluster (C, see "New bug cluster" above) remain (see Next Action).
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
2. **Launch TWO /codex reviews in parallel** (user's explicit instruction — see Goal):
   - **codex #1**: sanity-check the crash-fix diff (`git diff 7ba41ba..ccc5389` covers the gauntlet fixes +
     crash fix + save_on_apply default) — a fresh pair of eyes, since the P4 crash slipped past the FIRST
     gauntlet pass. Low-stakes, quick.
   - **codex #2**: the new bug cluster in "New bug cluster" above. Feed it the user's raw quote VERBATIM +
     this session's 3-lead trace + the actual current source (MainActivity.java, focusing on lines
     ~86-91, ~478-500, ~510-570, ~940, ~179, ~305-311). Ask it to (a) confirm or refute the "no durable
     applied/identity state across process death" diagnosis by tracing the FULL flow itself, (b) identify
     the exact minimal fix, (c) flag anything else in the same area it notices while it's in there. Do NOT
     pre-empt this with your own fix — let codex drive the diagnosis per the user's instruction.
3. **Reproduce the bug cluster on the 4a FIRST** (before or alongside launching codex #2 — whichever is
   faster) — soft-close via home button, or `adb shell am kill com.specter` to simulate a real process death
   (NOT `force-stop`, which is a harder kill than what actually happens in the field), then reopen and watch
   for: the toast/status flash, whether a new identity appears unprompted, whether "already applied" is
   missed on the first apply after reopen. Real evidence makes codex's job much easier and catches any
   difference between this session's static trace and actual behavior.
4. **Item A — Network pill copy.** File: `xposed-module/app/src/main/java/com/specter/module/ui/MainActivity.java`
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
5. **Item B — Visually distinguish Target-apps hook rows.** In the Protection-status screen (`renderHealth()`
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
6. **Item C — the bug cluster fix**, once codex #2 + on-device repro converge on a diagnosis. This is
   probably the biggest chunk of next-session work — budget for it accordingly, don't treat it as a quick
   add-on alongside A/B. Also fix the toast/button copy incoherence (lead #1 above, "Randomize" vs "Generate
   another identity") while in this area — it's simple and directly user-confirmed, no need to wait on codex
   for that specific piece. The "toast should have a slightly distinctive look" / "say Checking instead of
   flashing not-applied" requests are UI-polish riding on top of the same investigation — fold them in once
   the state bug itself is understood, not before (a nicer-looking toast that still flashes wrong info is a
   half-fix).
7. Bump nothing version-wise unless the user asks — v0.19.3 already covers this whole PR arc; these are
   still-v0.19.3 polish items, not a new version. (If Item C turns out to be a substantial enough fix that it
   feels wrong to lump into v0.19.3, ask the user first — don't decide unilaterally.)
8. Run `run-jvm-tests.sh` + `pytest -q`, rebuild, deploy + screenshot-verify on the 4a, commit, push.
9. **Then offer to merge** (don't merge without asking). Given Item C may be a real behavioral fix (not just
   cosmetic like A/B), consider whether it warrants its own gauntlet pass (code-reviewer + codex on the fix
   diff) before merging — use judgment based on how invasive the eventual fix turns out to be.

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
