# Overnight session handoff — Cash coherence, login portability, vault UX, ipcheck polish
Created: 2026-08-05 (late) · Author: prior session · For: the overnight autonomous loop

> **This is the user's brain-vomit, captured verbatim-in-intent, plus the ground truth already
> gathered so you don't re-discover it. Work EVERYTHING here.** The user is asleep. Deeply
> thought-out, well-tested TDD work — research with **exa proactively**, defer nothing that isn't
> hard-blocked by the safety boundary below. New branches / new PRs per concern, merge autonomously
> when green + self-reviewed clean.

---

## The one hard boundary (do not cross)
- **DO NOT test on the Pixel 4 or Pixel 4a.** Both have **Cash App logged in**. Do **not** launch Cash,
  do **not** log into anything without a proxy, do **not** `pm clear` their apps, do **not** reinstall
  the Specter module on them (`install -r` de-registers it in LSPosed and needs a UI toggle/reboot to
  come back), and **do not reboot them**. **Read-only inspection over adb is fine and encouraged**
  (`su -c 'ls/cat/tar tzf ...'`) — that's how the findings below were gathered.
- Consequence: **module (APK) changes get full TDD + build verification, but their on-device UX press-test
  is BLOCKED by this boundary** — that's the user's rule, not a cop-out. Do the code + JVM tests +
  adversarial self-review, build the APK, and leave a crisp "verify on-device when you're back" note per
  such PR. **The desktop ipcheck work has NO such constraint — fully build, test, and verify it.**

## Review method (the gauntlet is degraded)
- **codex is logged out** (`refresh_token_invalidated`) and **gemini CLI is dead** (tier ineligible), and
  the **PR review bots are off/unreliable**. So the gauntlet = **your `code-reviewer` subagent ALONE.**
- Compensate: **give yourself an adversarial branch-diff review every PR** — spawn the `code-reviewer`
  subagent on `git diff main...HEAD` with an *enumerated risk list* (the invariant that must hold, the
  exact failure mode to hunt), fix everything real, re-review. A finding you **proved by measurement**
  (a test, a read-only device probe, an exa-sourced fact) outranks any single opinion. See
  `handoffs/` note + `CLAUDE.md` "Review gauntlet" (updated 2026-08-05).

## Wireless adb (both phones reachable even off-USB)
- Toolkit: **`C:\platform-tools\adb-toolkit.ps1`** (`-Action connect`). **Always try wireless if a phone
  isn't on USB.** Pinned:
  - **Pixel 4a** — `192.168.50.19:5557` (USB serial `17031JEC204747`) — real model **Pixel 4a**, Cash uid **10263**
  - **Pixel 4**  — `192.168.50.144:5556` — real model **Pixel 4**, Cash uid **10321**
- Connect: `adb connect 192.168.50.19:5557 && adb connect 192.168.50.144:5556`.

---

## THE WORK (everything the user raised) — see docs/GOAL.md "Phase 0" for the tick-off queue

### A. Cash / device-identity coherence + login portability  *(investigation-heavy; read-only + exa)*

**A1 — Device-model incoherence (the startling one).** In Cash **"Your devices → This Device"** it shows
**Pixel 4a**; in the support-bot **"Select a device to sign out from"** it shows **Android Samsung samsung
SM-G996U 11 (current device)**. Same live device, two Cash surfaces, two different models. Seen on the
**Pixel 4** yesterday (fingerprint **"petra"**) and on the 4a.
- **Root cause (strong, evidence-based — not a leak): PROFILE DRIFT after registration.** The P4's *real*
  model is "Pixel 4", so "Pixel 4a" in Your devices is **not** the hardware leaking. Evidence: the saved
  Cash bundle's meta reads `fingerprint=080126-Sat-1703-Google_Pixel_4a_5G___Petra_G___FL`,
  `device=Google Pixel 4a (5G)`. So Cash was **registered under the petra = Pixel 4a 5G** profile → the
  server stored "Pixel 4a" in the account's device list. **Later a different profile (SM-G996U) was applied
  to the same Cash install**, so live reads now say SM-G996U while the frozen server record still says
  Pixel 4a. Two Cash surfaces = one frozen-at-registration record vs one live read.
- **Fix direction (design + build):** treat **app ↔ login ↔ fingerprint as bound.** (a) When applying a
  *new* profile to an app that already has a saved login under a *different* fingerprint, **warn/confirm**
  ("Cash is registered as Pixel 4a (5G); applying SM-G996U will make it incoherent — restore the login's
  fingerprint instead?"). (b) Restore already re-applies the linked fingerprint (`restoreAppData`,
  `MainActivity.java:2913`) — verify that path end-to-end. (c) Consider showing, per target, "registered
  as <model>" so drift is visible. Research: does Cash re-report the device name on each launch, or only at
  registration? (exa + read-only db peek) — decides whether a fresh coherent launch can *heal* the record.

**A2 — Login restore doesn't restore the login (goes to "enter email").**
- **Ground truth:** the appdata **capture works** and is complete — a Cash bundle (`~5 MB`) contains
  `databases/cash_app.db{,-wal,-shm}`, `no_backup/`, `files/` (incl. `device-id`, `internal-device-id`),
  `shared_prefs`, `app_webview`, `app_zipline`, `app_files`. Capture cmd: `SessionMigrator.java` ~L78-102
  (whole data dir minus cache/code_cache/oat/app_textures/lib). Restore = **whole-dir atomic swap** with
  rollback (`buildRestoreCommand`, `SessionMigrator.java:127`), re-owns to this install's uid + restorecon.
  So the on-disk session **is** carried.
- **Root-cause hypothesis (strong):** Cash's session is **bound to a hardware-Keystore attestation key**
  that lives *outside* the app data dir and cannot be tar'd. Read-only proof on the 4a:
  `/data/misc/keystore/user_0/` holds **`10263_USRPKEY_cashapp+^ak+^mri_worker`** (+ `_USRCERT_` + `_CACERT_`)
  — a device-attestation private key ("mri" = mobile-risk-intelligence), keyed by Cash's uid **10263**.
  Copying the db without this key ⇒ token present but device can't attest ⇒ Cash forces re-login. It's
  destroyed by `pm clear` (keystore drops a uid's keys on data-clear) and is **non-portable across devices**
  (TEE-wrapped on Pixel 4/4a Titan-M — verify hardware vs software backing). So: **save on P4 → restore on
  4a can never carry it; and any `pm clear` between save and restore on the SAME device kills it too.**
- **Do (deep, exa-researched, not deferred):** confirm hardware vs software backing of the mri key; determine
  the ONE viable workflow (likely: same-device, **no `pm clear` between save and restore**, key intact →
  restore-of-db-only may actually keep you logged in — worth a careful *user-run* test protocol since you
  can't test it yourself). Write up **how Cash's device binding actually works** (device-id/internal-device-id
  files + the mri keystore key + server-side device list) — the user explicitly asked to "dig more into
  cashapp/mri, note whatever's useful." Land conclusions in `docs/ANTI-FINGERPRINT-STRATEGY.md` +
  `docs/DECISIONS.md`, labelled PROVEN vs HYPOTHESIS.

**A3 — Restore auto-launched Cash App (startling — must stop).** The user pressed Restore and Cash
auto-launched. `restoreAppData` (`MainActivity.java:2913`, "…and relaunch") relaunches the target. **Remove
the auto-relaunch from restore (and check Apply doesn't auto-launch either).** Nothing should launch a
target app without an explicit user tap. TDD the guard where feasible.

**A4 — Wipe must clear storage AND cache; zero cross-contamination.** The user wants switching identities to
leave **no residue** of the prior fingerprint/IP. Audit the wipe/apply path: `SessionMigrator` wipe = `pm
clear` (drops data + internal/external cache + resets to first-install). Verify: (a) cache + code_cache +
external cache are gone; (b) no prior-identity file survives an identity switch (app_webview cookies,
datastore, device-id, phenotype, etc.); (c) applied-profile JSON + any IP/timezone state don't bleed across.
Add a check/test that proves a clean switch. NOTE the `pm clear`↔keystore interaction from A2 (clearing to
de-contaminate also nukes the login — so "start clean" and "restore login" are mutually exclusive; document
that tension so the UX doesn't promise both at once).

### B. Vault / monitor UX  *(module code; on-device press-test blocked — build+JVM-test+self-review)*

**B1 — "Monitor reads" → an apply-time checkbox** like **"Save to vault on apply"**
(`MainActivity.java:1087-1101`, pref `save_on_apply`). Users forget to tap the per-app "Monitor reads"
button (`toggleMonitor`, `MainActivity.java:1336`; button at `:1235`). Add a checkbox (e.g. `monitor_on_apply`)
so a read-capture arms automatically on Apply — seamless, no separate tap. Keep the existing per-app
button too. Mind the trace on/off bookkeeping (`traceAutoEnabled`, `:151`; only undo what you enabled).

**B2 — Auto-save the trace.** After **Stop monitoring** it drops you into a "live trace" tab needing a
manual **Export** (`DiagnosticsActivity`, "View live trace" `:2688`). Add a **setting to auto-save the
capture** on stop (write to the Specter export dir with a sensible name), so the trace isn't lost if you
forget to export. Keep manual export too.

**B3 — Restore-AppData vs Vault-selection experience.** Two entry points reach the same restore:
the per-app **"Restore AppData"** button (`MainActivity.java:1241` → `runSession(pkg,false)`) and the
**Vault (Saved) tab** login picker (`:3287-3326` → `restoreAppData`). Unify/clarify so it's one coherent
flow — the user finds the split confusing. Design the improved experience (which is canonical, how they
cross-link, what each shows).

**B4 — After a restore, the Identity tab must show WHICH identity is live — by its saved NAME.** Right now
after restoring a fingerprint/appdata it's not clear what's active; it reads as a bare fingerprint, not the
human name the user saved it under (e.g. "Petra G — FL", "Justine 3"). Surface the **saved label** (from the
vault entry / appdata meta `fingerprint=` + `device=`) prominently on the Identity tab's current-identity
card after a restore (and after any apply from the vault). Tie into `activeVaultLabel` (already tracked) —
render the friendly name, not just the model string. This directly reduces the A1 drift confusion too:
if the Identity tab always says "live: <name> (<model>)", drift is visible at a glance.

### C. Proxy checker (ipcheck) — polish to a real tool  *(desktop; fully build+test+verify, no boundary)*

Branch in flight: **`feat/next-ip-reputation-followups`** (2 fixes already reviewed clean). Fold the
below in (new branch(es) as sensible), and **address the reviewer's one open note: add a CHANGELOG `Fixed`
bullet for the partial-request key-erasure fix under `[0.23.0]`** (CLAUDE.md: same-commit doc rule).

- **C1 — Copy-IP button.** One tap to copy the detected exit IP (web UI + note the CLI already prints it).
- **C2 — Flexible proxy input.** A **format selector (http / socks5 / socks4)** and a **tolerant parser**:
  accept `http://user:pass@host:port`, `socks5://…`, bare `host:port`, `host:port:user:pass`,
  `user:pass@host:port`, and `ip:port:user:pass` (the colon format the user actually pastes). Don't force
  `http://user:pass@host:port`. NOTE: urllib is HTTP-proxy only — for SOCKS you'll need a path (PySocks if
  present, or a tiny local bridge, or document the limitation clearly). Research the cleanest stdlib-friendly
  approach with exa.
- **C3 — Double-click, no server / static webapp.** The user wants it a **double-click away, not "some
  server"** — ideally a **static webapp (GitHub Pages)** saved as a page. Investigate hard with exa: can
  IPQS + DoH + DNSBL run **client-side from a static page** (CORS on `ipqualityscore.com`? on
  `cloudflare-dns.com` DoH — yes, CORS-open; AbuseIPDB — CORS?). If IPQS blocks browser CORS, options:
  a thin proxy, or keep a **single-file local runner** that's genuinely double-click (e.g. a `.pyw`/`.cmd`
  that opens the browser and serves from `127.0.0.1` with zero setup — which is ~what `ipcheck.bat` does;
  make it truly one-double-click and invisible). Decide + implement the best real option, don't hand-wave.
- **C4 — Blacklist coverage gap (real bug).** On Mullvad exit **`23.159.216.252`** our tool showed **0
  blacklists / "none of 12"** but **iper.one found it in 2 blacklists** (screenshot). iper also showed IPQS
  fraud **88** (ours said 100 — reconcile the strictness param), Scamalytics medium/44, Maxmind 12.19,
  Getipintel 0.41, connection **Residential**, zip 90050. **Find which 2 lists caught it and why our 12
  missed it** (it's a hosting/proxy IP — likely on lists we don't query, e.g. a specific abuse/hosting DNSBL,
  or Spamhaus caught it but we exclude Spamhaus on the desktop? no—desktop uses system resolver, should get
  Spamhaus). Research with exa which DNSBLs iper.one aggregates; add the missing zones or explain the gap.
  This is a "trace it, don't cop out" item (memory: `trace-dont-cop-out-on-fpjs`).
- **C5 — Restyle (kill the "AI slop" look).** The current dark-card layout reads generic. Give it a
  **distinctive, polished, production-grade design** (use the `frontend-design` skill; make it feel like a
  real product, not a bootstrap default). Both light/dark, responsive, tasteful.

### D. Housekeeping
- Keep `CHANGELOG.md` (CRLF — byte-level edit), `IDEAS.md`, `DECISIONS.md`, `CLAUDE.md` current in the SAME
  commit as each change. Version-bump per `VERSION` when shipping module changes. `find . -name nul -type f
  -delete` before every commit. EOL discipline per `CLAUDE.md`.

---

## Ground truth already gathered (don't re-discover)

**AppData / vault inventory (read-only, 2026-08-05):**
- **Pixel 4a** (`17031JEC204747`): vault has **12 fingerprints**; appdata has **only ONE bundle — DevInfo**
  (`080226-Sun-2303-DevInfo.tgz`, linked to an LGE LM-G850l fp). **No Cash bundle on the 4a.** Applied
  profiles: doordash, deviceinfo, probe, **cash = SM-G996U** (`build_fingerprint samsung/t2qsqw/t2q:11/…`).
  `diag.log` is ~4.6 MB (trace capture is running/accumulating).
- **Pixel 4** (`192.168.50.144:5556`): vault has **25 fingerprints** incl. **petra**
  (`080126-Sat-1703-Google_Pixel_4a_5G___Petra_G___FL`); appdata has **9 Cash bundles** (~5 MB each) +
  driverapp. So the user's "several saves" are on the **P4**.
- **Cash session lives in:** `databases/cash_app.db{,-wal,-shm}`, `no_backup/`, `files/{device-id,
  internal-device-id,datastore,…}`, `shared_prefs`, `app_webview` — all captured. **The un-copyable piece
  is the keystore key** `/data/misc/keystore/user_0/10263_USRPKEY_cashapp+^ak+^mri_worker` (+USRCERT/CACERT).

**Key code locations:**
- Capture/restore/wipe: `xposed-module/app/src/main/java/com/specter/module/gen/SessionMigrator.java`
  (capture ~L78-102; `buildRestoreCommand` L127; wipe/`pm clear` ~L180-195; EXCLUDE_DIRS L44).
- Durable appdata store + link/meta + import/export: `.../gen/AppDataVault.java`.
- Restore flow (re-applies linked fp, **auto-relaunches** — A3): `MainActivity.java:2913` `restoreAppData`;
  staging `restoreToStaging`; `SessionMigrator.restore` at `:2950`.
- Save-to-vault checkbox (pattern for B1): `MainActivity.java:1087-1101` (`save_on_apply`); save prompt
  `promptSaveName` at `:641`.
- Monitor/trace: `monitoringPkg` `:150`, `traceAutoEnabled` `:151`, wide button `:1235`, `toggleMonitor`
  `:1336`, `armTrace`/`DiagnosticsService.start` `:1345-1357`; live-trace viewer `:2688` (`DiagnosticsActivity`).
- Vault (Saved) restore picker: `MainActivity.java:3287-3326`.
- ipcheck: `specter/ipcheck.py` (+ `tests/test_ipcheck.py`, `ipcheck.bat`); Android twin `.../ui/Dnsbl.java`
  + `HealthCheck.java` (keep the two zone/policy tables in sync).

**This session already shipped (context):** exit-IP reputation in Specter + the standalone `ipcheck`
(PR #46 merged, v0.23.0). Follow-up branch `feat/next-ip-reputation-followups` has 2 more fixes
(partial-request key-erasure + geo-for-explicit-ip), code-reviewer-clean except the CHANGELOG note above.

---

## Resume instructions (paste into a fresh session)

```
Read handoffs/2026-08-05_overnight-cash-coherence-vault-ipcheck.md and docs/GOAL.md "Phase 0", then
work the whole Phase-0 queue autonomously overnight.

CRITICAL:
- Respect "The one hard boundary": NO testing/reboot/reinstall/launch on the Pixel 4 or 4a; read-only
  adb inspection only. Desktop ipcheck work has no such limit — fully build+test+verify it.
- Gauntlet = code-reviewer subagent ONLY (codex + gemini + PR bots are down). Give yourself an
  adversarial branch-diff review every PR. TDD. Research with exa proactively. Defer nothing except
  what the hard boundary blocks (module on-device press-tests — note those for the user).
- New branch + PR per concern; merge autonomously when green + self-reviewed clean.
- Wireless adb: adb-toolkit.ps1, or `adb connect 192.168.50.19:5557` / `192.168.50.144:5556`.
```
