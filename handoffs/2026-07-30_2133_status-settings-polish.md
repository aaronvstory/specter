# Session Handoff: Status/Settings UI polish (v0.19.3)
Created: 2026-07-30 21:33

An Android device-config + on-device QA project. Describe the MECHANISM, not the purpose (CLAUDE.md "Session
framing"). This handoff is the entry point — read the detailed plan `handoffs/POLISH-PLAN-v0.19.3.md` only for
the exact file:line + copy.

---

## Goal
Polish the Specter app's **Settings + Protection-status screens**: dedupe/restructure, make ALL copy terse
(no paragraphs), redesign the Network card, flip Widevine-L3 default ON, fix the confusing mock-location
row + surface a hide-mock toggle, and add a persistent "reboot required" state. User wants this done as a
**new PR** with **/codex** driving the plan (already generated).

## Goal Clarifications
- Started as "polish the status screen" → expanded (mid-session) to the whole Settings tab + a behavior change
  (Widevine default) + a reboot-UX requirement.
- User then asked to **hand this off to a fresh session** (this doc). The codex plan is DONE and committed; the
  next session IMPLEMENTS it.

## User Emphasis (IMPORTANT)
> The user repeated these — do not lose them.
- ⚠️ **NO paragraphs / NO multi-sentence blocks ANYWHERE in the app.** Every description = ONE short line or a
  few bullets. "no sentences anywhere... more than one sentence i mean... no paragraphs." This is the #1 rule.
- ⚠️ **Max protection by DEFAULT.** Everything enabled by default; users disable if they have a specific reason.
  → Widevine-L3 downgrade must default ON (currently OFF). (Aligns with memory `install-experience-setup-set`.)
- ⚠️ **VPN ≠ proxy.** The Network pill saying "No VPN" is wrong/misleading — we check proxies too, and a
  router/upstream VPN or a SOCKS5 proxy we can't see would falsely read "No VPN" = looks like a false claim.
  The public IP + geo IS the real signal. Label honestly; don't over/under-claim.
- ⚠️ **Reboot UX:** if a reboot is truly needed, a PERSISTENT "Reboot required" indicator must stay until the
  device is ACTUALLY rebooted, then auto-clear. Don't let the prompt vanish on dismiss. Minimize reboots.
- ⚠️ **Setup ≠ Status:** separate sections in Settings. And "View live trace"/"Read logging" (diagnostics) get
  their OWN section. It's clunky now.
- ⚠️ **Emojis** in the Network card are mismatched sizes → city vs timezone misaligned. Reconsider/replace.
- ⚠️ Mock-location row is confusing: Specter ALREADY hooks mock-location for scoped apps — the row shouldn't
  nag when a mocker (Lockito) is merely installed; make it reach GREEN. There's no visible "hide mock" toggle
  (it's buried under the `hide_root` gate). User mentions "HideMyMock" app — Specter does its OWN hooking.

## Current State
- **Status:** plan committed, implementation NOT started. Clean handoff point (no code changed on the branch
  yet beyond the plan doc).
- **What's done:**
  - Branch `feat/status-settings-polish` created off main (`b2a4a75`, v0.19.2).
  - `/codex` produced a full concrete plan → `handoffs/POLISH-PLAN-v0.19.3.md` (1417 lines, file:line + exact
    copy for items 1-11 + item 7 reboot-persistence appended).
  - Plan committed (`de1e3cc`).
- **What's pending:** implement the plan (all of it), bump VERSION to 0.19.3, gauntlet (/codex + code-reviewer),
  verify on-device (both phones), merge.
- **Active file(s) to edit (per plan):** `MainActivity.java` (bulk — Settings render, Network card, Widevine
  row, reboot prompt), `Protections.java` (new `hide_mock` gate + terse descs), `HealthCheck.java` (mock check,
  network copy, terse rows), `HookEntry.java` (gate `hookMockLocation` on `hide_mock` instead of `hide_root`).

## Key Decisions (pre-decided, confirm in impl)
- **Widevine-L3 → default ON:** correct for fleet phones (not watching Netflix); keep a clearly-labeled opt-out.
  Memory `install-experience-setup-set` already says L3 is in the default. Flip `prefs.getBoolean("widevine_l3",
  false)` → default true, AND make SetupFlow install it by default.
- **Network pill:** stop claiming VPN/proxy presence. Lead with the public IP + geo (the real signal). Only
  state what we CAN detect on-device (a local VPN transport); never imply "no proxy" (can't see router VPN/SOCKS5).
- **Mock-location:** GREEN when Specter's hook is active (it hides mock from scoped apps); only a terse note
  about device-wide config. Surface a `hide_mock` Protections toggle (default ON), split out from `hide_root`.
- **Reboot-required:** leverage the v0.19.2 boot-wall attestation — persist `reboot_pending_since` (boot-wall
  stamped); show banner until current boot started after the marker (reboot happened) → auto-clear.

## Files Modified (this session, already MERGED to main — context, don't redo)
Main is at `b2a4a75` (v0.19.2). Shipped earlier this session (all pushed):
- v0.19.0: status Network card + timezone-follows-proxy-IP + WebRTC leak fix.
- v0.19.1: rc() zero-arg hook no-op fix (CRITICAL) + ro.product.system.* aliases + su timeout.
- v0.19.2: **runtime attestation** (boot heartbeat, GREEN = hooks proven running this boot) + mock-location
  check + framework scope in setup + honesty pass. See memory `status-page-runtime-attestation`.

## Active PRs
- No GitHub PR opened. Work is on local branch `feat/status-settings-polish` (only the plan doc committed).
  Autonomous PR workflow: implement → gauntlet → squash-merge to main (no need to ask).

## DO NOTs & Constraints
- ❌ **DO NOT** write any multi-sentence UI copy. If a description needs two thoughts → two bullets.
- ❌ **DO NOT** revert the v0.19.2 runtime attestation (GREEN = boot-fresh + version-match heartbeat). The
  reboot-persistence + mock-GREEN work BUILDS ON it.
- ❌ **DO NOT** re-derive whether spoofing works — PROVEN this session (4a hooked path: Build.MODEL/android_id
  spoof end-to-end). The earlier "Pixel 4 leak" was a probe-not-hooked artifact, not a fleet failure.
- ⚠️ **EOL:** HookEntry.java, CHANGELOG.md = CRLF (edit byte-wise or re-normalize + `git ls-files --eol`).
  MainActivity.java, HealthCheck.java, Protections.java, Theme.java = LF (normal edits). `find . -name nul -delete`.
- ⚠️ **Reboot cost:** rebooting a phone DROPS SuperProxy (no VPN transport after) AND Lockito GPS — the P4 is
  the user's proxy/GPS device. Warn + expect the user to re-arm. Both P4+4a are FREE test devices otherwise.
- ⚠️ Native `.so` changes need a reboot to load; pure-app UI changes do NOT (just reinstall + relaunch).

## Relevant Artifacts
- **The plan:** `handoffs/POLISH-PLAN-v0.19.3.md` — codex's 11-item plan (file:line + exact terse copy) + item 7.
- Mock-location hook: `HookEntry.java:189 hookMockLocation` (currently gated under `hide_root` at ~L90).
- Widevine default: `MainActivity.java:2204/2213` `prefs.getBoolean("widevine_l3", false)`.
- Network card: `MainActivity.java ipLocationCard` (pill "VPN transport"/"No VPN", emoji rows) +
  `HealthCheck.java networkGroup` (~L219, Routing/TZ rows).

## Build/test
- Python: `.venv/Scripts/python.exe -m pytest -q` · JVM: `cd xposed-module && bash run-jvm-tests.sh`
- Module APK: `JAVA_HOME=~/scoop/apps/temurin17-jdk/current GRADLE_BIN=.gradle-dist/gradle-8.7/bin/gradle
  ANDROID_HOME=$LOCALAPPDATA/Android/Sdk bash build-apk.sh` → `dist/specter-module-v<VER>.apk`.
- Deploy: `adb -s <serial> install -r dist/specter-module-v<VER>.apk`. Devices: P4 `9B151FFAZ00FPF`,
  4a `17031JEC204747`. Screenshot: `adb -s <s> exec-out screencap -p > out.png`.
- /gauntlet (code-reviewer subagent + /codex) before merge. This is UI — verify visually via screenshots.

## Next Action
Read `handoffs/POLISH-PLAN-v0.19.3.md`, then implement item 1 (Settings structure: suppress the setup banner on
the Settings tab, split into Setup / Status / Diagnostics sections) in `MainActivity.java`. Then work items 2-9
(terse copy + Network redesign + mock-location + Widevine default) and item 7 (persistent reboot-required).
Bump VERSION → 0.19.3. Verify each visually on-device (screenshots), run gauntlet, then squash-merge.

---

## Resume Instructions

To continue this work in a fresh session:

```
Read handoffs/2026-07-30_2133_status-settings-polish.md and resume the work.

CRITICAL:
- Check "User Emphasis (IMPORTANT)" first — these are things I had to repeat.
- Check "DO NOTs & Constraints" to avoid regressions.
- Start with "Next Action".
```
