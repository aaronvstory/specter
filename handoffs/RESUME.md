# RESUME — Specter next session (start here)

Fresh session: read this first, then open the detailed handoff for specifics. Android device-config +
on-device QA project; describe the mechanism (what a profile sets, what a sample app reads back), keep the
engineering exact. Full framing: `CLAUDE.md`.

## Read next
**`handoffs/2026-07-28_2028_polish-zygisk-autoinstall-a13-pairip.md`** — the current detailed handoff. It has
the full state, the in-flight branch to finish, the A13 blocker, and the next-session queue.

## One-paragraph state
main @ `9f77c4f`, all pushed. This session shipped the Dasher crash fix (SDK_INT clamp), the number-survival
leak fix (root cause = GeerGit co-scoped, now Specter is immune), Widevine L1->L3 + GSF reset, mandatory
deep-clean on apply/restore, dataset SoC coherence fixes, and first_api_level = launch-API (61 models). All
gauntlet-clean, tests green, on-device verified. **In flight (uncommitted, branch `feat/zygisk-self-install`):
the app self-installs the Zygisk native layer + detects if missing — proven on the P4; a gauntlet was running
at handoff time. Finish that first** (read the verdicts, merge).

## Two standing user rules (do not violate)
1. **Never wait to reboot; always sync the latest build to EVERY connected device before testing/reporting**
   (md5-compare module APK + zygisk .so + lite vs dist/; deploy stale; reboot if the .so changed). Memory:
   [[reboot-freely-and-always-sync-latest]].
2. **Test across Android 11 (P4) AND 13 (4a).** This surfaced the A13 PairIP blocker (Dasher's integrity lib
   crashes on the rooted A13 4a, Specter-independent — see the handoff + `docs/PAIRIP-CONSTRAINT.md`).

## Devices
- **P4 `9B151FFAZ00FPF`** (Android 11) — Dasher works; the verified test device. Fleet-safe to experiment on.
- **4a `17031JEC204747`** (Android 13, rooted + LSPosed) — Dasher PairIP-blocked; use for A11/A13 consistency
  + probe/dataset tests. (The old "NO ROOT" note is outdated — it's rooted now.)

## First actions
1. **UI DECLUTTER is the #1 ask** — the app is too text-heavy/cluttered; make it Apple-clean (short lines,
   whitespace, no jargon in primary text). See the handoff's "TOP UI PRIORITY".
2. `adb devices` (both should show). 3. Finish + merge `feat/zygisk-self-install` (read the running gauntlet).
3. The user asked to run **/codex on the whole app** for a fresh full-app review. 4. A13 PairIP experiment
   (PlayIntegrityFork + custom.pif) — attended. See the handoff's queue for the rest.
