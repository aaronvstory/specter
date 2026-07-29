# Handoff: Unify the Vault (one list — fingerprints, each showing if it has a login) + fixes

Created 2026-07-29 ~00:20. Written because we hit the context limit mid-polish. The app UI was fully
redesigned this session (v0.16.0 — bottom nav, summary-first Identity, grouped cards, bright pastel yellow
#FFD54A, tight corners, drawn icons). It LOOKS good now. This handoff is the NEXT chunk: the **Vault
information architecture is wrong** and a couple of buttons are broken.

## Framing (keep the model on the top tier)
Android device-configuration + on-device QA project. Describe the MECHANISM, not the purpose. Neutral
terms: profile/fingerprint = a generated device-configuration; app-data/login = a captured app data-dir
tarball; "verify a sample app reads the values back". See CLAUDE.md.

---

## ⚠️ THE CORE PROBLEM (user's words, 2026-07-29)
The Vault tab currently has **TWO separate lists**: a "Saved logins (AppData)" section AND a "Saved
fingerprints" section (date-grouped, searchable). This is redundant and doesn't scale. The user's exact
asks:

1. **"Save login should automatically save the fingerprint too, so it should be PART of the fingerprint
   vault."** → There should be ONE list (fingerprints). A login is not its own top-level list.
2. **"Each fingerprint should show if it is one with a login or if it is fingerprint-only."** → A badge/tag
   on each fingerprint row: "has login (Dasher)" vs "fingerprint only".
3. **"Viewable by: saved-fingerprint-WITH-appdata, or fingerprint-WITHOUT."** → A filter/segment: All /
   With login / Fingerprint only. Plus the existing search + date grouping MUST stay (the whole reason we
   date-grouped was to handle 100+ entries — a flat list of 100 is exactly what we're avoiding).
4. **"With 10 (or 100) saved logins, how do we choose which to restore?"** → Restore must come from the
   unified, grouped, searchable, filterable list — NOT a flat "Saved logins" section showing a lone
   "Restore login" with no way to pick among many.
5. **"How can we easily choose to save a fingerprint to vault or NOT?"** → On the Identity screen / after
   apply, make saving explicit and easy (the old "Save to vault after applying" checkbox was dropped in the
   redesign and has no home now — see DO-NOT / KNOWN GAPS).

## ⚠️ SECONDARY (visible bugs in the screenshot the user sent)
- **Broken button sizing**: in the "Saved logins" card, "Restore login" is a full-width button but
  Rename/Export/Delete below it are `thirdButton`s — and in the screenshot the Restore button renders
  SHORTER/narrower than the row beneath it. The `appDataRow()` layout (MainActivity ~L1876) is the culprit.
  This whole section is going AWAY in the unification, so fixing it = deleting it, not patching it.

---

## HOW THE DATA ALREADY SUPPORTS THIS (good news — minimal new storage)
The link ALREADY EXISTS. No schema change needed for the join:
- **Fingerprint vault** (`ui/Vault.java`): `Entry{ label, device, savedAt, targets }`. Files:
  `filesDir/vault/<label>.json`. `label` = `MMDDYY-Day-HHMM[-name]`.
- **AppData vault** (`gen/AppDataVault.java`): `Entry{ label, pkg, savedAt, sizeBytes, fingerprint, device }`.
  Files: `filesDir/appdata/<label>.tgz` + `<label>.meta`. Crucially **`fingerprint` = the vault-label of the
  fingerprint the login was captured under** (the LINK). Set in `ensureFingerprintSaved()` in MainActivity,
  which ALREADY saves the live fingerprint to the vault when you "Save login" and links the appdata to it.
- **So: a fingerprint `Vault.Entry` HAS a login iff some `AppDataVault.Entry.fingerprint == that entry's
  label`.** That's the entire join. `appDataVault.list(null)` gives all logins; index them by `.fingerprint`.

## TARGET DESIGN (what to build)
ONE unified Saved list on the Vault tab, replacing both current sections:

```
[ All ]  [ With login ]  [ Fingerprint only ]      <- filter segment (chips)
Search saved (name or device)…                     <- keep existing search
▾ Tue 07/28/26  (3)                                <- keep existing date grouping/collapse
   ┌────────────────────────────────────────────┐
   │ driverapp            🔒 Login · Dasher · 681KB│  <- badge when it has a login
   │ Tue 07/28 · 3:33 PM · Samsung SM-A525F        │
   │ Applied to: Dasher                            │
   │ [ Restore ]  ⋯                                │  <- Restore = fingerprint (+login if present); ⋯ = overflow
   └────────────────────────────────────────────┘
   ┌────────────────────────────────────────────┐
   │ pixel4-clean          Fingerprint only        │  <- no badge / muted "Fingerprint only"
   │ Tue 07/28 · 1:10 PM · Google Pixel 4          │
   │ [ Restore ]  ⋯                                │
   └────────────────────────────────────────────┘
```
- **Restore semantics**: if the fingerprint has a linked login → restore does BOTH (re-apply fingerprint +
  restore login, the existing `restoreAppData()` flow, which already stages-before-wipe). If it's
  fingerprint-only → restore just the fingerprint (existing `restoreSaved()`). ONE "Restore" button either
  way; the row's badge tells the user which they'll get.
- **Overflow (⋯)** per row: Rename, Export, Delete (and for login rows, these act on BOTH the fingerprint and
  its linked login as a bundle — renaming a fingerprint already relinks its appdata via
  `AppDataVault.relinkFingerprint`, keep that). This kills the 3-across `thirdButton` row that's mis-sizing.
- **Badge**: `🔒 Login · <AppLabel> · <size>` (use a drawn lock icon, NOT emoji — there's an `icChevron`/
  `iconButton` system; add an `icLock` StrokeIcon or reuse the nav lock glyph). Fingerprint-only rows show a
  muted "Fingerprint only" caption.
- **Filter segment**: reuse the existing `filterChip()` helper (already in MainActivity, used for the
  per-app appdata filter). Three chips: All / With login / Fingerprint only. Wire to a field like
  `savedFilter` and re-render the list.

## IMPLEMENTATION NOTES / WHERE THINGS ARE
- `renderSaved()` (MainActivity ~L1940) currently: Save-current card, Import card, then
  `renderSavedAppData()` (the "Saved logins" section to DELETE), then "Saved fingerprints" + search +
  `renderSavedList(all)` (date-grouped). **Plan: delete `renderSavedAppData()`/`appDataRow()`; enrich
  `savedRow(e)` with the login badge; add the filter segment above the search; filter `all` by
  All/WithLogin/Only before grouping.**
- Build a `Map<String,AppDataVault.Entry> loginByFp` once per render from `appDataVault.list(null)` keyed by
  `.fingerprint`; pass it (or a `hasLogin(label)` lookup) into `savedRow`/`renderSavedList`.
- Restore button in `savedRow`: `if (loginByFp.containsKey(e.label)) restoreAppData(loginByFp.get(e.label));
  else restoreSaved(e.label);`
- Keep search (`vaultQuery`), date grouping (`expandedGroups`/`seededRecentGroup`), and the "most recent
  group auto-expands" logic — all already there in `renderSavedList`.
- DELETE the now-orphaned old per-app "Save AppData/Restore AppData" block at MainActivity ~L1118 (dead code
  from the pre-redesign card; the LIVE per-app actions are in `targetAppRow` ~L885 as "Save login"/"Restore
  login"). Confirm ~L1118 is unreachable before deleting.

## "SAVE FINGERPRINT — YES/NO" (user ask #5) — the Identity side
The old `saveOnRandomize` "Save to vault after applying" CheckBox was built in the now-uncalled `actionBar()`
and is currently DEAD (field is null; the `if (saveOnRandomize != null …)` guard in `apply()` just skips it).
Give it a home:
- Simplest: after a successful Apply, show a one-tap "Save this identity to vault" affordance (snackbar
  action or a row on the hero). OR a small toggle on the hero card: "Save to vault on apply".
- Also: "Save login" on a target ALREADY auto-saves the fingerprint (via `ensureFingerprintSaved`) — good,
  that's the user's ask #1. Just make sure the unified list then shows it with the login badge.

## DO NOTs / CONSTRAINTS
- ❌ Don't reintroduce a second top-level list. ONE saved list.
- ❌ Don't lose search or date-grouping (they're the scale story for 100+ entries).
- ❌ Don't break the fleet (P4 = income device). App-data testing on the 4a or the P4-with-backups only.
- ✅ Full account backups are on the PC: `handoffs/dasher-backup/{dasher,cash}-full-backup.tgz` (md5-verified,
  gitignored). Restore from these if a test loses a login.
- ✅ EOL: MainActivity/AppDataVault/Vault are LF; CHANGELOG/HookEntry CRLF; VERSION no trailing newline.
  Verify `git ls-files --eol` + `git diff --stat` after every edit.
- ✅ `find . -name nul -type f -delete` before every commit. Run `/gauntlet` (code-reviewer + /codex)
  before merging. FOCUSED single-file codex prompts work; broad ones drown in skill-file context.
- ✅ Screenshots: pre-grant su (launch once, wait ~13s for the "granted superuser" toast to clear) AND keep
  the screen awake (`adb shell svc power stayon true`, and unlock — the P4 AOD locks during long waits).

## Current state (all committed + pushed, tree clean, both devices on v0.16.0)
- This session shipped: app-data (login) vault PROVEN on Dasher+Cash (save→wipe→restore→logged-in), one-tap
  login+fingerprint snapshot, rename/export/import, hardening (symlink/regular-file import guards, atomic
  swaps, stage-before-wipe), and the full v0.16.0 UI redesign. 27 commits.
- Devices: **P4 (fleet) `9B151FFAZ00FPF`** (USB this session; Dasher+Cash logged in, healthy).
  **4a (test) `17031JEC204747`** (+ Lite v1.5). Both v0.16.0.
- Full context in `handoffs/2026-07-29_morning-report.md`.

## Next action
1. Build the unified Saved list per TARGET DESIGN (delete `renderSavedAppData`/`appDataRow`; add login badge
   + filter segment to the existing date-grouped `renderSavedList`/`savedRow`; unify Restore). This fixes the
   broken buttons by removing that section entirely.
2. Give "save fingerprint yes/no" a real home on the Identity/apply flow.
3. Verify on-device (4a): save a login → see it in the ONE list badged "has login"; save a fingerprint-only →
   see it unbadged; filter With-login / Fingerprint-only; restore each; screenshot clean and send to user.
4. Version-bump (v0.17.0), CHANGELOG, /gauntlet, merge.
