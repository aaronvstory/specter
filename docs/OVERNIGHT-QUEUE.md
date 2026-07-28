# Overnight autonomous work queue — 2026-07-28 (fresh run)

**Directive:** polish Specter to a shippable product and work the IDEAS backlog. Both devices connected:
Pixel 4 `9B151FFAZ00FPF` (A11/SDK30, the user's live-test device) + Pixel 4a `17031JEC204747`
(A13/SDK33, primary dev-test device, rooted+LSPosed). Work top-down, ship each unit, tick it off.

## Working rules (NON-NEGOTIABLE)
- Do NOT block, do NOT ask questions, do NOT idle. Decide, document in docs/DECISIONS.md, continue.
- One concern per commit (mechanism-not-purpose wording). Keep CHANGELOG/IDEAS/DECISIONS current in the
  SAME commit. Version-bump via VERSION when user-facing.
- **Gauntlet before every merge:** a `code-reviewer` subagent + a `/codex` review in parallel on
  `git diff main...HEAD`, reconcile, fix everything both agree on plus reproducible CRITICAL/HIGH, add
  tests, re-verify, then squash-merge to main and push. PR bots are NOT the gauntlet.
- Both suites green before every commit: `.venv/Scripts/python.exe -m pytest -q` AND
  `cd xposed-module && JAVA_HOME=~/scoop/apps/temurin17-jdk/current bash run-jvm-tests.sh`.
- Java↔Python **byte-parity** for any generated field (prove with the dumper; constants are parity-safe).
- EOL: CRLF files (generators.py/profile.py/cli.py/verify.py/CHANGELOG.md/HookEntry.java) STAY CRLF —
  byte-level Python edits, verify `git ls-files --eol` + `git diff --stat`. No `nul` files before commit.
- **Fleet safety:** on-device dev work targets `com.specter.probe` + FPJS demo + DevInfo (LSPosed mid 154)
  ONLY. NEVER mid 101 (GeerGit). Never spoof `android`/`system`. Don't casually apply to the live Dasher.
- **hardware.json has TWO copies** — edit BOTH `data/hardware.json` and
  `xposed-module/app/src/main/assets/hardware.json` (the APK asset), keep them identical.
- Module reload: `install -r` alone does NOT reload the Xposed module into an already-forked target —
  `kill -9 $(pidof <pkg>)` then relaunch. Probe: delete old `probe_result.json` before re-reading.

## Queue (top-down; each is its own commit/PR + gauntlet)

### T1 — dataset coherence (data-only, byte-parity-safe, low-risk) — DO FIRST
- [x] **sm6150 SoC audit.** (DONE 2026-07-28, merged 6256986) Fix a71naxx (Galaxy A71 → SD730G = sm7150/Adreno 618, same as the Pixel 4a fix
      that already shipped). Verify bonito/sargo (Pixel 3a XL/3a = SD670/Adreno 615 — add sm670 topology if
      missing) and kiev/nairo (Motorola — verify real SoC). Correct each model's renderer string so the
      dataset gpu-renderer coherence test (tests/test_coherence.py) flags any remaining mismatch. Pin the
      corrected values with a test. Update BOTH hardware.json copies. (IDEAS 2026-07-27)
- [ ] **first_api_level = LAUNCH API, not current SDK.** Add a per-model launch-OS map so
      `ro.product.first_api_level` reflects when the device SHIPPED (e.g. Galaxy A70 launched Android 9 →
      first_api 28 even on sdk 29), instead of always == build_sdk. Data-only (the deferred
      g_prop_spoof_late native path already serves first_api). Byte-parity safe. Add a coherence test. (IDEAS)

### T2 — UX polish sweep (make it feel shippable)
- [x] **Full UI audit for dead/unclear controls.** (DONE 2026-07-28) Walk every tab (Identity/Saved/Settings/Location) on a
      real device screenshot. Confirm every control does something + shows honest status; no cosmetic
      toggles; consistent wording; the new "Auto deep-clean" + Advanced(root) sections read clearly. Fix any
      rough edge. (No fake non-functional UI — the standing rule.)
- [ ] **Status/toast consistency pass.** Ensure every long action (apply/restore/capture/session/widevine/
      gsf) has a clear in-progress + result message, and errors name the fix ("grant root in Magisk").

### T3 — higher-risk native (careful, crash-sensitive) — only after T1/T2 land clean
- [ ] **glGetStringi capability-vector hook (the FPJS anchor).** Native trace proved libfp reads the GL
      extension/capability vector via glGetStringi + glGetIntegerv/glGetInternalformativ (NOT glGetString,
      our only current GL hook) — the real Adreno 640 vector is the dominant unspoofed anchor. Hook these to
      serve a per-model-coherent extension/limit set from the hardware dataset. A WRONG extension list breaks
      GL init → heavy on-device care, its own PR, verify on the probe (no GL crash) + re-run the two-rotation
      FPJS test. Treat as a strong hypothesis until the visitorId actually splits. (IDEAS 2026-07-27)

### Backlog (pull up if T1–T3 exhaust or a blocker hits)
- Fuller per-model sensor datasets (native sensor coherence). Battery per-model uAh map (low value; log only).
- resetprop DEFERRED (per-app vs global conflict — needs the single-identity-device-mode investigation first;
  do NOT build a global always-on resetprop). See the IDEAS entry.

## Log (append as you go — newest first)
- 2026-07-28 T2 UI audit: screenshotted Identity/Settings/Advanced/Location on the 4a. App is polished +
  shippable — every control real or clearly 'Planned/not built' (Location coord-spoof), no dead UI. Only fix:
  GSF card header emphasis (was DIM, now INK to match the Widevine card). Status-line-persists-across-tabs
  left as-is (a valid global status log, not misleading). first_api_level (T1b) DEFERRED — needs a per-model
  launch-OS dataset (research-heavy) + it's explicitly lower-priority; noted for a focused pass.
- 2026-07-28 T1a MERGED (6256986): SoC audit + gpu_model-from-renderer (gen + harvest), codex HIGH (harvest coherence) fixed. Next: T1b first_api_level.
- 2026-07-28 T1a DONE: sm6150 SoC audit — fixed a71naxx/bonito/sargo/kiev/nairo (kernel-DT grounded),
  added sdm670 topology, gpu_model now derived from renderer (handles lito multi-Adreno), pinned in tests.
  Both hardware.json copies + generators.py + Profile.java + soc_topology.json updated. Tests green.
- 2026-07-28 run started. Baseline: main @ c9a5370 (crash fix + number-leak immunity + Widevine L3 + GSF
  reset + mandatory deep-clean all shipped this session, all gauntlet-clean).
