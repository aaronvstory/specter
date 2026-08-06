# Handoff — vault restore ↔ target-apps coherence + "what was applied" transparency

**Status:** spec / not started · **Type:** UX + apply-logic fix · **Branch:** make a NEW one (`fix/vault-restore-targets`), off `main`.
**Framing:** on-device config QA. A "saved profile" = a device-config bundle; "AppData" = a saved app-login tarball; a "target app" = which app a config is applied to; "restore" = re-apply a saved bundle. Describe the MECHANISM (which bundle applies to which app, and what actually landed), not motive.

## The problem (user, 2026-08-07)
Restoring a saved bundle from the vault ignores which app that bundle belongs to, and never says what actually got applied.

Concrete: a saved bundle was captured for **Cash App** (its `_targets` = `com.squareup.cash`). In the Identity tab the selected **target app is Dasher** (`com.doordash.driverapp`). Restore that Cash bundle → it reports "applied", but it applied the **Cash identity/appdata to the DASHER app**. That is incoherent.

Two defects:
1. **Restore doesn't drive the target selection.** A restore should set (or at least confirm) the target app(s) to the ones the saved bundle belongs to — the vault entry already carries them — so a Cash save restores to Cash, not to whatever is currently selected.
2. **No "what was applied" transparency.** On a restore the UI doesn't say whether the **fingerprint** applied, the **appdata/login** restored, **both**, only one, or a partial/failed result. The user can't tell what actually landed.

## Where it lives (exact code)
`xposed-module/app/src/main/java/com/specter/module/ui/MainActivity.java`
- **`restoreSaved(labelStr)` ≈ line 5031** — restores a saved *fingerprint (device profile)*. **THE BUG:** line **5041** `Set<String> targets = Targets.get(prefs);` — it applies to the CURRENTLY-SELECTED targets, NOT the vault save's own targets. It loads the profile (5036) and applies to whatever is selected. Ignores the entry's `_targets` entirely.
- **`restoreAppData(e)` ≈ line 4048** — restores a saved *login*. This one IS app-specific (applies the linked fingerprint + login to `e.pkg`, the app the login belongs to) and adopts the fingerprint (≈4104). But it does NOT update the Identity tab's shown **target-apps selection** to `e.pkg`, and its result note ("fingerprint … applied; login restored") isn't surfaced as a clear per-part status.

`xposed-module/app/src/main/java/com/specter/module/ui/Vault.java`
- `Entry.targets` (**line 44**) — the comma-sep packages a profile was applied to (persisted as `_targets`, line 89). **Already saved, currently unused on restore.** This is the source of truth for "which app(s) this bundle belongs to."
- `AppDataVault` (`gen/AppDataVault.java`) — the login side; a login `Entry` has `pkg` (the app) + `fingerprint` (the linked device-profile vault label).

`Targets.get(prefs)` / `Targets.set(prefs, set)` — the selected target apps (Settings → target picker). `restoreSaved` reads it; the fix will write it (or confirm against it).

## The fix (two parts)
### 1. Restore drives the target apps
- `restoreSaved`: read the vault `Entry.targets` for `labelStr` (parse the comma-sep list). If non-empty, **set the target selection to those apps** before applying (or show a confirm: "This save belongs to Cash App — restore to Cash App? [Cash App] [Keep current: Dasher]"). Then apply to THOSE, not `Targets.get(prefs)`.
  - Decide the interaction: auto-switch silently vs confirm-on-mismatch. Recommend: **auto-set the targets to the save's `_targets`**, and if the save has NO `_targets` (older save), fall back to the current selection with a note. A confirm only when the save's targets differ from the current selection AND the user might not expect it.
- `restoreAppData`: after a login restore, **set the target selection to `e.pkg`** so the Identity tab reflects the app that was just restored to.
- The Identity tab's "Target apps" card must then re-render to show the new selection (it reads `Targets.get(prefs)` — a `render()` after `Targets.set` does it).

### 2. "What was applied" transparency
- Both restore paths should report a clear, itemised result — per app and per part:
  - fingerprint: applied ✓ / failed / not present in the save
  - appdata (login): restored ✓ / failed / not present
  - "both applied to Cash App", or "fingerprint only (no login saved)", or "⚠ login restore failed — fingerprint applied".
- `restoreAppData` already builds a `note` (≈4053, "fingerprint … applied; login restored") — surface it as a structured status line, not a vague toast. `restoreSaved` currently says "Wiped and restored to N app(s)" — make it say it restored the **fingerprint** (no login) to **which** app(s).
- Consider a small persisted per-app record of "what's on this app" (fingerprint label + whether a login is present) so the Identity/Saved tabs can show it at a glance, not just a transient toast.

## Constraints / workflow
- **New branch off `main`** (`fix/vault-restore-targets`). Do NOT touch `feat/gps-lockito-mode` (parked GPS work).
- **Do NOT deploy to the phones** unless the user says go — they run Lockito + income apps on the 4a. Build + test in-repo; if on-device verification is needed later, test ONLY on `com.specter.probe` / `com.liuzh.deviceinfo` / the FPJS demo, NEVER on the live Dasher/Cash, and `python scripts/backup_vault.py` first.
- TDD where it fits (the target-derivation logic is unit-testable — a pure "targets for this save" helper, like the JVM RootWriterTest style). Keep pytest + JVM green; CRLF discipline (HookEntry/CHANGELOG are CRLF; MainActivity/Vault are LF — `git ls-files --eol` after edits).
- Gauntlet before merge: `code-reviewer` subagent + `/codex` (both — codex is back). Update CHANGELOG/DECISIONS in the same commit; version-bump via VERSION.

## Repo state at handoff (2026-08-07)
- `main` @ `7d6cb76` — GPS work SHIPPED (v0.30.0–0.32.0: per-identity GPS + GPS-follows-IP + global default location). That per-identity GPS is a *backstop*; see below.
- `feat/gps-lockito-mode` @ `1a906e1` — PARKED. A Lockito-REPLACEMENT (system-wide mock) engine (GpsMockService + boot receiver), NOT deployed, remaining UI in `docs/GPS-LOCKITO-MODE.md`. Leave it alone.
- The 4a was reverted (GPS spoof removed, no reboot, Lockito alive). Don't re-apply GPS to it.
