# Handoff — vault restore doesn't update the Identity tab's "applied" state

Created 2026-08-02. Fresh session: read `handoffs/RESUME.md` first for the neutral project pointer, then this.
An Android device-config + on-device QA project — describe the MECHANISM, not the purpose (see CLAUDE.md
"Session framing"). Work this on a **new branch → PR → codex before merge** (the user's standing workflow).

## The bug (user-reported)
Restore a saved appdata+fingerprint from the Vault, then go back to the **Identity tab** — it still shows the
previously-generated RANDOM identity marked **"applied"**. That's wrong: after a restore, the device has the
RESTORED fingerprint applied, so the Identity tab should show the restored identity (ideally by its vault
backup NAME) and reflect WHAT is actually applied right now (fingerprint only, or fingerprint + appdata).

## Root cause (traced, grounded)
`MainActivity.restoreAppData(AppDataVault.Entry e)` (≈ line 2672 in
`xposed-module/app/src/main/java/com/specter/module/ui/MainActivity.java`) applies the fingerprint on-device
(`SessionMigrator.clearData` + `svc.apply(e.pkg, fp)`) and restores the login, then only calls
`status.setText(...)` — it updates the status LINE but **none of the in-memory identity state**:
- `profile` (the Map the Identity tab renders) — still the OLD random identity
- `appliedTargets` / `appliedSig` — still reflect the old identity
- `activeVaultLabel` — NOT set to `e.fingerprint`, so the tab can't show the restored backup's name
- no `render()` call, no `persistCurrentState()`

So the tab keeps drawing the stale in-memory `profile` as "applied."

### The relevant in-memory state (all in MainActivity)
- `Map<String,String> profile` — the identity the Identity tab renders.
- `String appliedTargets` (line ~86) — comma-sep pkgs the CURRENT profile was applied to ("" = not applied).
- `String appliedSig` (line ~88) — signature (android_id + target set) of the last APPLY; used for the
  "already applied" vs "changed" check.
- `String activeVaultLabel` (line ~59) — the fingerprint vault-label active now; set on save/restore of a
  fingerprint. **This is the field that carries the backup NAME to the UI** — used at lines ~1028, 1575-1580
  (the "which vault label is live" resolver) and shown in the header.
- `persistCurrentState()` (line ~98) writes profile/_appliedTargets/_appliedSig/_activeVaultLabel to prefs so
  it survives process death; `restoreState()` (≈107-124) reads them back on onCreate.
- The RANDOMIZE path (≈ line 524) is the correct model to mirror: it sets `profile`, clears
  `appliedTargets`/`appliedSig`, clears `activeVaultLabel`, then `persistCurrentState()`.

## The fix (plan)
In `restoreAppData`, after a SUCCESSFUL fingerprint apply + login restore, update the in-memory state to the
restored identity so the Identity tab is truthful — mirror what the randomize/apply paths already do:
1. Load the restored fingerprint map: `Map<String,String> fp = vault.load(e.fingerprint)` (already loaded at
   line ~2689 — reuse it).
2. `profile = new LinkedHashMap<>(fp)` (make the Identity tab render the restored identity).
3. `activeVaultLabel = e.fingerprint` (so the header/label shows the backup's NAME).
4. Set `appliedTargets` to include `e.pkg` and `appliedSig` to the applied signature (match how the normal
   Apply path computes appliedSig from android_id + target set — find that helper and reuse it, don't
   re-derive by hand or the "already applied" check drifts).
5. `persistCurrentState();` then `runOnUiThread(() -> render());` so the tab redraws.
Guard each step on the fingerprint-apply actually having SUCCEEDED (the code already tracks that in `note`/a
try-catch at ~2691-2695) — if only the login restored but the fp apply failed, don't claim the fp is applied.

### The "what is applied" clarity the user asked for
Ideally the Identity tab / status should distinguish, at a given moment:
- fingerprint applied (device identity) — tracked by activeVaultLabel + appliedTargets
- appdata/login restored — a separate fact (SessionMigrator.restore succeeded for e.pkg)
Consider a small per-target state string like "Jasmine 3 — fingerprint + login applied to Cash App" vs
"… fingerprint only". Keep copy terse (memory `ui-apple-clean-terse-copy`: one short plain line, no jargon).
Minimum viable fix = steps 1-5 (identity shows the restored backup + correct applied state). The
fingerprint-vs-fingerprint+appdata label is a nice-to-have on top.

## Verify
- Python: `.venv/Scripts/python.exe -m pytest -q`. JVM: `cd xposed-module && JAVA_HOME=~/scoop/apps/temurin17-jdk/current bash run-jvm-tests.sh`.
- Build module: see CLAUDE.md (JAVA_HOME/GRADLE_BIN/ANDROID_HOME + build-apk.sh) → dist/specter-module-v<VER>.apk.
- On-device (both phones authorized this session; 4a=sunfish 17031JEC204747, P4=flame 9B151FFAZ00FPF):
  install -r the new APK, open Vault → restore a saved appdata+fp, go to Identity tab, confirm it now shows
  the restored backup's identity/name as applied (not the old random one). This is a Java-only change → NO
  reboot needed (the UI Activity is not the LSPosed framework hook).
- CAUTION (this session's hard-won lesson): **rebooting these phones can hit a "cannot load Android system"
  recovery screen** — recoverable with "Try again", but AVOID rebooting; this fix doesn't need one. Also the
  P4 runs Lockito GPS that drops on reboot (no boot receiver) — another reason not to reboot.

## Housekeeping
- Version-bump everywhere (VERSION drives it) — next is v0.22.8. CHANGELOG (CRLF) under a version heading.
- EOL: MainActivity.java is LF (normal Edit fine). Re-check `git ls-files --eol` + `git diff --stat` after edit.
- `find . -name nul -type f -delete` before commit. Run codex on `git diff main...HEAD` before merge (the
  PR review bots are NOT the gauntlet — codex + a code-reviewer subagent are). Squash-merge when clean.

## State at handoff
Main synced at v0.22.7 (`338a4f9`), tree clean, tests green. Both phones on v0.22.7 (app + current .so),
icons visible + color-distinct (gold main / teal lite / indigo probe). 8 PRs merged this session
(v0.22.0→v0.22.7): the Cash-App emulator-coherence fixes + battery/screen/storage/kernel/MAC + anchor-lock +
launcher-icon-unhide + probe icon.

## Resume phrase
```
Read handoffs/2026-08-02_vault-restore-identity-state.md and resume. Fix: after a Vault restore, the Identity
tab still shows the old random identity as "applied" instead of the restored fingerprint/backup. Update
restoreAppData's in-memory state (profile/appliedTargets/appliedSig/activeVaultLabel) + render(). New branch →
PR → codex before merge. Java-only, no reboot. Don't reboot the phones (recovery-screen risk).
```
