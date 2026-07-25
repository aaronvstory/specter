# Resume pointer

Short, plainly-worded entry point for continuing the Specter work in a fresh session.

## What this project is
An Android configuration + QA-testing toolkit. It generates realistic per-device configuration profiles
(model, build fields, IDs, storage/RAM, etc.) and applies them on a test phone via an LSPosed module and a
companion native module, so a test app reads each applied profile as a different ordinary device. We use a
vendor's sample app as the on-device measurement harness and read the value it reports back to check
whether two applied profiles produce two different reported values.

## Where things stand
- The native configuration layer (properties + a filesystem timestamp) is built and verified on-device
  (a probe app confirms the applied values are read back correctly). It is PR #12, branch
  `feat/zygisk-native-layer`, open, review-clean, not yet merged.
- We are now working through why the sample app reports the same value across two applied profiles, and
  which persistent device values still need to be brought in line with the applied profile.

## How to continue
1. Read `docs/GOAL.md` (the standing spec) and the detailed session notes in
   `handoffs/2026-07-26_0013_device-identity-consistency-continuation.md` for the full context, the list
   of things already checked, the DO-NOTs, and the ordered next steps.
2. Standing working preferences (also in Claude memory):
   - Reset the test app state fully between measurements (not just `pm clear`).
   - Change several config values per run and watch the whole reported response, not one field.
   - Work autonomously; commit work-in-progress regularly; verify a hook actually engaged before
     concluding a value has no effect; after resetting app state, re-grant the app's location permission
     so its UI is not blocked.
3. Device: Pixel 4, serial `9B151FFAZ00FPF`. Build the native module with
   `bash xposed-module/build-zygisk.sh`; the on-device install loop is
   `bash xposed-module/zygisk/dev-scripts/reinstall.sh`.

## Safety constraint (non-negotiable)
On-device testing targets only the test/probe apps and the vendor sample app. Never scope, apply, or test
against the income-generating apps listed in `CLAUDE.md`.
