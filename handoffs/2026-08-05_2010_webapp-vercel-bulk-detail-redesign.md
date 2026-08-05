# Handoff: webapp Vercel + bulk + detail breakdown + design fixes
Created: 2026-08-05 20:10

## Goal
- Ship the ipcheck **webapp on Vercel** (done, live) + finish a keys-UX refactor (WIP).
- Redesign the results UI per user critique (tiles, detail breakdown).
- Apply the same to the **Specter Android app**.

## ⚠️ USER EMPHASIS (read first)
- **NO prose paragraphs. Ever.** Chat replies AND UI copy. Bullets / one idea per line. Saved to global +
  project CLAUDE.md + memory `terse-no-paragraphs`. He is furious about "claude slop paragraphs".
- **Design must be professional.** His exact critiques on the current webapp result (screenshot in session):
  - Fraud-score tile text `IPQualityScore · strictness 1 · flags every proxy, runs high` **wraps to 3 lines**
    → makes ALL tiles that tall (they're uniform height). Tiles must be short + uniform. Cut the editorial text.
  - `Flagged as` = 3 little bubbles then empty width → looks unfinished/wasteful.
  - **"residential-ish" is meaningless** — kill that wording.
  - Verdict SUSPECT shows nothing useful. He wants a **DETAILED per-source breakdown** (collapsible, collapsed
    by default): what EACH service scored + WHAT makes it suspect, with the ability to dig deeper.
  - He told me (repeatedly) to **dig into the APIs' detailed fields** (IPQS returns ~15 fields; getIPIntel has
    Country/factors; AbuseIPDB usageType/reports) and SHOW them. NOT DONE YET.
- **Do NOT auto-run the scan on page open.** Prefill the current IP so the user can **click** Run — not auto-run.
  (Currently `boot()` prefills AND auto-clicks `#go` — remove the auto-click, keep the prefill.)
- **All of this applies to the Android app too** (same tile/detail/terse redesign).
- getIPIntel: **no email field** — server supplies the contact (env var on Vercel, config locally). A dummy
  email does NOT work (getIPIntel rejects fake contacts; my test IP is also rate-limited now). Done in WIP.
- Keys: **shared env keys should show "set/active"** in the UI with option to override. Started (WIP): `/api/config`
  + `markKeys()` show "shared active" / "your key". Verify it renders on Vercel.

## Current state
- **main = v0.25.1**, merged, both phones deployed + hook-verified this boot. Tests green.
- **WIP branch `wip/webapp-vercel-bulk-keys-ux` @ f6808ed** (pushed) holds the uncommitted webapp work.
  It is MID-REFACTOR — `webapp/build.py` was NOT updated for the new keys-UX (its `new_cfg` still references
  `#gii` and lacks the `/api/config` fetch), so the *generated* `webapp/index.html` is stale/inconsistent.
  Regenerate + verify before trusting it.
- **Vercel live: https://webapp-idanis-projects.vercel.app** — full tool, works. Env vars set (IPQS_KEY,
  ABUSEIPDB_KEY, GETIPINTEL_CONTACT). Deployment Protection OFF. Project = `webapp` (prj_wOG1cJo4e7QYLiggw9syfRgpJSTs,
  team_MqmL9mOQWaCgPHfTdiHBEOjq). AbuseIPDB verified working (185.220.101.45 → 100%). Proxy-tunnel verified
  (res.proxy-seller.com:10000 → exit 153.66.193.140 → suspect).
- **Bulk feature** built (in PAGE): textarea → parallel `/api/check` per line → comparison table w/ copy
  buttons (proxy / ip). Backend proven; table UI needs the same visual polish + the detail breakdown.

## Exact next actions (in order)
1. `boot()` in `specter/ipcheck.py` PAGE: **remove `$('#go').click()`** (keep the ipwho.is prefill). Also fix
   `webapp/build.py` `new_cfg` to match (drop `#gii`, add `fetch('/api/config').then(markKeys)`, keep prefill,
   NO auto-click).
2. **Redesign the result tiles**: uniform short height, terse labels, no wrapping. Drop "residential-ish" and
   the "flags every proxy, runs high" text. Rework "Flagged as".
3. **Build the DETAILED BREAKDOWN** (collapsed by default): per-source cards — IPQS (all returned fields),
   getIPIntel (score/BadIP/Country), AbuseIPDB (confidence/reports/usageType), DNSBL (which zones). Explain
   what makes the verdict. This is the #1 unmet ask. `check()` already returns most fields; may need to pass
   through more raw IPQS/getIPIntel fields.
4. Verify keys-UX on Vercel (shared-active status), rebuild `webapp/index.html` via `python webapp/build.py`,
   redeploy, screenshot.
5. **Apply the same redesign to the Android app** (`MainActivity.reputationRows` tiles + a detail expander;
   getIPIntel via config not a prominent field).
6. Merge WIP → main when clean; keep the 3 surfaces in sync.

## Deploy mechanics (gotchas — save time)
- Vercel CLI authed as **idanivolcan**. Token: `C:/Users/d0nbxx/AppData/Roaming/xdg.data/com.vercel.cli/auth.json`.
  **Windows Python needs `C:/...` paths, NOT `/cygdrive/...`** (that cost 2 rounds).
- **Deploy from a NON-git copy** (git author-email fails Vercel's check): `cp -r webapp/. <scratchpad>/ipcheck-deploy/`
  then `cd` there + `npx vercel deploy --prod --yes`. The `.vercel/project.json` links it to the `webapp` project.
- `vercel.json` uses the legacy `builds` config (`@vercel/python` + `@vercel/static`) with
  `includeFiles: api/ipcheck_core.py` — REQUIRED or the function gets `No module named ipcheck_core`.
- Env-key changes via API: `PATCH/POST https://api.vercel.com/.../projects/<proj>/env?teamId=<team>` with the token.
- Rename project to `specter-ipcheck` for a nicer URL if wanted (changes the URL).

## Three sync surfaces (user-flagged: keep in lockstep)
- `specter/ipcheck.py` = source of truth (PAGE + check logic).
- `webapp/` = Vercel: `build.py` vendors `ipcheck.py`→`api/ipcheck_core.py` + generates `index.html` (rewrites
  `/check`→`/api/check`, config→localStorage, adds `/api/config` fetch). Re-run after any PAGE/logic change.
- Android `HealthCheck.java` / `Dnsbl.java` = Java mirror (parity tests pin the DNSBL table + datacenter regex).
  Memory to write: "ip-check touches 3 surfaces — update all three."

## DO NOTs
- ❌ No auto-run on page open. ❌ No prose paragraphs. ❌ No 3-line tiles / "residential-ish".
- ❌ Don't deploy from inside the git repo (author-email fail). ❌ Don't pass `/cygdrive/` paths to Windows python.
- ❌ Don't commit WIP to main until the redesign is done + verified.
- ⚠️ getIPIntel: my PC IP is rate-limited (temp). Vercel (Lambda IP) still works.

## Resume Instructions
```
Read handoffs/2026-08-05_2010_webapp-vercel-bulk-detail-redesign.md and resume.
CRITICAL:
- Check "USER EMPHASIS" first (terse, no auto-run, detailed breakdown, professional tiles).
- Check "DO NOTs".
- Start with "Exact next actions".
- WIP is on branch wip/webapp-vercel-bulk-keys-ux.
```
