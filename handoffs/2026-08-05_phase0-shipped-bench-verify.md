# Phase 0 shipped — bench verification checklist
Created: 2026-08-05 (overnight) · Status: **all Phase-0 items merged; 8 module PRs need an on-device press-test**

The whole Phase-0 queue shipped tonight — **14 PRs merged (#47–#60), v0.23.0 → v0.24.4**. Each got an
adversarial `code-reviewer` branch-diff pass and real findings were fixed before merge. Desktop `ipcheck`
work is fully verified. The **module (APK) changes were code-verified only** (clean `compileDebugJavaWithJavac`
+ JVM tests) — their on-device press-test was blocked by the standing no-touch rule on the Pixel 4/4a. This
is that press-test list, so nothing ships to the fleet unverified.

## Build + install the current module
```
cd xposed-module && JAVA_HOME=~/scoop/apps/temurin17-jdk/current GRADLE_BIN=… ANDROID_HOME=… bash build-apk.sh
# -> dist/specter-module-v0.24.4.apk   (do NOT `adb install -r` on a phone whose module is registered —
#    it de-registers in LSPosed; use the UI toggle/reboot path, per never-reinstall-lsposed-module-to-fix)
```
**Build already confirmed clean (2026-08-05):** `:app:assembleDebug` succeeds, the APK is
`com.specter` versionName **0.24.4** / versionCode **2404**, and all of tonight's new symbols are present
in the multidex (`restoreForPkg`, `startMonitor`, `applyDeviceString`, `conflictingDevices`,
`autoCheckedIps`, `autosave_trace`, `monitor_on_apply`, `EXTRA_FROM_STOP`) — so the bench build + install
should be turnkey; no stale-`.class` surprise.

## On-device press-tests to run at the bench (per PR)

| PR | What to verify on-device |
|----|--------------------------|
| **#50** (restore no auto-launch) | Tap "Restore AppData" / a Saved-tab login → the target app is **NOT** launched; it stays stopped, status says "open it when ready". Apply also launches nothing. |
| **#51** (apply drift warning) | Save a login for an app under device A. Generate device B, Apply to that app → the confirm dialog appears ("… has a saved login as A … applying B won't match"). Cancel = nothing wiped. Toggle the device bundle OFF → Apply shows **no** dialog (nothing to mismatch). |
| **#54** (clean switch) | Switch identities on a **test** app (DevInfo/probe — NOT Cash): confirm the app comes back at first-install (no prior-identity residue: cookies/device-id/cache gone). |
| **#55** (monitor-on-apply) | Tick "Monitor reads on apply", Apply → the read monitor auto-starts (dot shows "Monitoring"); relaunch the app, use it, Stop → the report opens. Untick → Apply does not start a monitor. |
| **#56** (identity shows saved name) | Restore a login saved as e.g. "Petra G FL" → the Identity card **leads with that name**, model+carrier on the line below. A fresh unsaved identity still shows the model. |
| **#57** (auto-save trace) | With "Auto-save report when monitoring stops" on, Stop a monitor → a `specter-coverage-*.txt` lands in `Download/Specter` **without** tapping Export. Toggle off → only the manual Export writes it. |
| **#58** (unified restore) | Per-app "Restore AppData" with **one** saved login → restores it directly (re-applies its fingerprint). With **several** → a "Restore which … login?" picker. With **none** vaulted → falls back to the last staged capture. |
| **#60** (auto-check reputation) | Open the Status/Network card with the tunnel up → the exit-IP reputation runs **automatically** (no "Check" tap). Re-opening for the same IP shows the cached result (no second IPQS call). A rotated IP auto-checks afresh. Off-tunnel → still gated, never checks the home IP. |

## Not shipped (deliberately) — the remaining user steers
- **Off-tunnel reputation scoring + geo/VPN (steer #15) and click-to-fix timezone with no tunnel (#16)**
  were **NOT built blind**: they relax the home-IP safety gate, and a mistake would hand the phone's real
  IP to IPQualityScore/AbuseIPDB/ipwho.is. Any external lookup off-tunnel exposes the home IP, so the safe
  design is (a) show only what the phone knows **locally** (VPN-transport active, SIM carrier) with no
  external call, and (b) gate any external scoring behind an explicit "this checks your REAL IP" opt-in.
  Left in `docs/IDEAS.md` for an on-device session where the home-IP exposure can be verified deliberately.
- **Launch-free per-target hook verification (#18)** is a research problem (headless-spawning the target
  *is* launching it; per-target hook state needs a new attestation path beyond the boot heartbeat). Logged.
- **Broad app-polish pass (#19)** — open-ended; better done with the app in hand.

## Resume phrase (paste into a fresh session)
```
Read handoffs/2026-08-05_phase0-shipped-bench-verify.md. Phase 0 is fully merged (v0.24.4). Either (a) work
the bench-verification checklist on-device now that you're at the phones, or (b) take the next steer from
docs/IDEAS.md — the off-tunnel scoring / timezone-fix (steer #15/#16), designed SAFELY around the home-IP
gate (local-only signals + an explicit "checks your real IP" opt-in), verifying on-device that the home IP
is never sent to a fraud API off-tunnel.
```

## Read-only LSPosed scope audit (2026-08-05) — fleet config state

Pulled `modules_config.db` read-only from both phones and queried it locally. Findings:

- **No active Specter↔GeerGit co-scoping — the android_id-poisoning risk is ABSENT** (the important one,
  per memory `geergit-poisons-specter-android-id`). Specter targets Cash / Dasher / DevInfo; GeerGit (P4
  only, mid 101) targets the FPJS demo + `com.myapp.go2_app`. No shared target app, so GeerGit can't pin a
  constant android_id on any Specter target.
- **Specter is scoped to `android` and `system`** (the OS framework) on BOTH phones — this is INTENTIONAL,
  do NOT remove it. (Correcting an earlier draft of this note that wrongly called it "pollution": those two
  dot-less framework keys are the **app-hiding gate**, added on purpose by `LspScope.isFrameworkKey` / the
  "Set up everything" flow so the raw-binder root/module hiding can hook the framework, not just user apps.
  The `is_core_os` guard only blocks *spoofing the framework's device identity*; it does NOT mean the
  framework shouldn't be *scoped*. Removing `android`/`system` from Specter's scope would break app-hiding.)
- **P4 has orphan scope rows** for modules NOT in the `modules` table: mid 7 → {Cash, geergit, doordash},
  mid 20 → {settings}. These are leftover rows from uninstalled modules — LSPosed ignores a scope row with
  no enabled module, so they're inert DB cruft, not active hooks on Cash. Safe to ignore (or clear if you
  ever rebuild the scope DB).
- **FPJS-demo ownership differs per phone:** on the **4a** the FPJS Pro demo is in Specter's scope (and no
  GeerGit is installed); on the **P4** the demo is owned by **GeerGit**, not Specter. So Specter's own
  FPJS-demo measurement works on the 4a but not the P4 — use the 4a for a Specter-vs-FPJS test, or add the
  demo to Specter's scope on the P4 (and remove it from GeerGit) if you want to test there.

All read-only; no scope was edited (editing mid 154 needs a reboot, which the boundary blocks on these
phones). These are observations for a bench session, not changes.
