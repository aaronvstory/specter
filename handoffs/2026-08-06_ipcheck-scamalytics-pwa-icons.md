# Handoff: ip-check (Scamalytics, PWA, icons) + the pointless apply-time dialog

Created 2026-08-06. Supersedes `2026-08-06_ipcheck-bulk-pwa-icons.md`.

Branch **`feat/bulk-comparison-and-ipv6-coverage`**, PR **#83** open. The repo is **temporarily PUBLIC** so
the review bots (Sourcery / CodeRabbit) can run — check whether they ever commented, then set it back to
private. **One session does all of this; there is no parallel session.**

## Rule zero: the repo is public, so no credential may enter the tree

Keys live in `~/.specter-ipcheck.json` (outside the repo) and in the Vercel env. Already set, do not
re-add: `SCAMALYTICS_USER`, `SCAMALYTICS_KEY`, `CHECKERNET_KEY`, plus the existing `IPQS_KEY`,
`ABUSEIPDB_KEY`, `GETIPINTEL_CONTACT`.
`test_no_api_credential_is_ever_committed` scans every tracked file for the live values and fails the
suite if one lands there. Verified clean, including git history.

## State: tests green, NOTHING deployed

Python + JVM suites green; the Android module compiles. Vercel and both phones are still on the previous
build — deliberately. The user wants **one batched deploy at the end of a run**, not a deploy per change
(memory `batch-deploys-not-per-change`).

### Landed on this branch

1. **Blocklists always run, including on IPv6.** A bulk run showed an IPv6 exit with `0` blacklists and a
   CLEAN verdict claiming "no abuse or blacklist history" — nothing had been checked. Measured:
   `res.proxy-seller.com:10000` is dual-stack (Starlink), 8 samples gave 5×IPv4 / 3×IPv6. An early probe
   using `2001:db8::2` wrongly suggested no zone supports IPv6; re-measured against 60 live IPv6 Tor
   exits, **four do** (s5h 39, Spamhaus 24, CBL 14, DroneBL 5; the other thirteen 0). `DNSBL_ZONES_V6` +
   `reverse_v6()` + a dual-stack IPv4 fallback. Positive control: 8/12 dirty IPv6 exits come back listed.
2. **Android DoH resolver.** Zones showed "no answer" because it asked Cloudflare, which Spamhaus/CBL
   refuse. Measured on `185.220.101.45` (7 listings): Cloudflare 14/17, **Google gives a FALSE CLEAN**
   (NXDOMAIN on a listed IP), dns.sb 17/17. Now dns.sb primary, Cloudflare fallback, never Google.
3. **Bulk comparison table.** 14 sortable columns, summary strip, chevron opens one grouped key/value
   detail table, credential copy chips on a single row. Verified in-browser on the user's real proxies.

## The queue

### 1. Scamalytics — "beautifully integrated" (the user's priority)

A **workflow measured the v3 API live and produced an implementation spec** — read it before writing code
(run id `wf_33fff9a6-39a`; the journal is under
`.claude/projects/F--claude-specter/<session>/subagents/workflows/`). Endpoint
`https://api11.scamalytics.com/v3/<user>/?key=<key>&ip=<ip>`, docs
<https://docs.scamalytics.com/ip-fraud-risk-api/v3/>.

- Credential is a **USER + KEY pair** — a first. `resolve_keys()`, the CLI flags, the local server's
  config fallback, the Vercel function's env fallback and `/api/config` all currently assume one value per
  source. Expect to touch all five.
- Keys UI: two fields, stacked, beside IPQualityScore and AbuseIPDB, showing "shared active" when the
  deploy already has them.
- Bulk table: **one** new column. Detail view: the full field set.
- **The question the measurement answers:** does its score DISCRIMINATE residential-proxy vs hosting the
  way getIPIntel does, or SATURATE the way IPQS's `fraud_score` does? A second saturating score is noise —
  if it saturates, show it but never let it move `verdict_factors()`.
- Strip anything echoing the user id or key from the raw body AND from error messages, exactly as
  `lookup_getipintel` and `lookup_ipqs` already do. Write the leak test.

### 2. Kill the apply-time "Identity won't match a saved login" dialog

`MainActivity.java` ~601 `driftWarnings()`, ~613 `confirmDriftThenApply()`, and
`AppDataVault.conflictingDevices()`.

The user's model, which the dialog contradicts:

- Generate a new fingerprint → this **is** the new-account flow, the whole point is a new identity.
- Apply it → this force-wipes the target app's cache and storage.
- Register a fresh account in the app.
- **Afterwards** optionally save appdata, which is tied to whatever is applied at that moment.
- Later, to reopen an account: go to the Vault, pick that fingerprint (with or without saved appdata) and
  restore it — which wipes what is applied and restores that pair.

So warning that a freshly generated identity "won't match a saved login" is backwards: not matching is the
intended state, and the wipe means there is no surviving session to contradict. **Verify the wipe happens
on every apply path before deleting**, then remove the dialog from the apply path. If the check has a
legitimate home it is the RESTORE path (restoring a login under a device that isn't its own), not here.

Wiping between identities is critical and must stay: generate-and-apply wipes, and restore wipes before
restoring, so one identity's data can never attach to another.

Also: that dialog is prose slop. Rewrite any surviving copy terse — short labelled lines, one idea per
line, never a paragraph. Check sibling dialogs for the same.

### 3. PWA + favicons

Manifest, service worker for the offline shell, apple-touch-icon, full favicon set (16/32/180/192/512 +
maskable). Must survive `webapp/build.py`: icons are static files in `webapp/`, the manifest `<link>` goes
in `PAGE`. `webapp/vercel.json` uses the legacy `builds` config, so a new static asset needs a matching
build/route entry or it 404s.

### 4. Asset render-test page, and fix every broken icon

The user's words: the Data Center icon "shows a WEIRD rectangle... this is garbage". It is `ICON.server`,
two rounded rects that read as a blob at 13px. Build a page rendering EVERY asset at its real size — the
six line-type SVGs, chevron, flag images, copy-chip tick, verdict pills, favicons, PWA icons, app icon —
and fix anything not instantly recognisable. Check on Windows/Chrome, where emoji coverage is poor (that
is why flags are `<img>` and not emoji: a regional-indicator pair renders as the two letters).

### 5. Android meaningful-info-first detail view

Do not dump everything. Show what the use case needs: blocklists (hits/checked), fraud score, whether a
proxy/VPN/Tor is DETECTED, abuse, getIPIntel, Scamalytics, exit type. Mirror the IPv6/dual-stack handling,
the v6 zone table + honest denominator, and the "not checked ≠ clean" wording.

### 6. Deploy ONCE, verify by comparison

Live page md5 (EOL-normalised) vs `webapp/index.html`. For Android compare dex **marker strings**, NOT dex
md5 — two builds of identical source differ on this toolchain. Phones:
`adb connect 192.168.50.144:5556` (P4), `192.168.50.19:5557` (4a).

### 7. Codex review

The user explicitly asked for this before wrapping. Feed the full diff plus the goal statement below.
Pipe the prompt (`echo "$P" | codex exec -m gpt-5.6-terra -`), never as an argument; `tee` the output; run
in background; verify real findings came back. Never run codex inside a Task subagent.

## The goal, in the user's words

> Make this a usable quickly functional utility where we can drop in a bunch of proxies and see a good
> overview (with the ability to dig deeper). A user should be able to check one proxy / their current exit
> IP easily, or add a bulk of 10 and get a breakdown of the most meaningful info for cleanliness /
> reputation — be able to pick which one of the bulk is best — then click to copy the hostname / port /
> username / password.

## Standing rules you will be judged against

Memory `ui-layout-rules-no-ragged-text`, `batch-deploys-not-per-change`, `terse-no-paragraphs`.

- A value NEVER wraps mid-text. Truncation ALWAYS needs an escape hatch.
- Never make the reader decode a colour — label the meaning.
- Colour is ONE-DIRECTIONAL on third-party labels: warn, never reassure. AbuseIPDB called a NordVPN Tor
  exit "Fixed Line ISP"; painting that green was a lie.
- Never claim more than was measured. "Not obviously a datacenter" ≠ "residential". "0 blacklists" ≠ "0 of
  17 checked".
- Buttons must not resize on click.
- Label→value rows, never prose — in the UI and in chat.
- **Look at the screen before saying it is done.** Most of the churn in this session came from shipping UI
  changes unseen and only learning they were broken from the user's screenshots.

## Traps already paid for

- `webapp/index.html` is GENERATED. Edit `PAGE`, re-run `python webapp/build.py`. build.py asserts on each
  rewrite because a silent mis-target once shipped a page where every button was dead.
- Table columns: `max-width:0` collapsed the proxy host to one character; `table-layout:fixed` then
  squeezed ISP to "Spa...". Columns size to content now — leave it.
- The breakout wrapper must not carry `.blk`: that animation ends on `transform:none` with fill-mode both
  and permanently wipes a centring translate.
- `100vw` includes the scrollbar — too small an inset gives the page a horizontal scrollbar.
- Deploy from a NON-git copy: `cp -r webapp/. <scratchpad>/ipcheck-deploy/ && npx vercel deploy --prod
  --yes`. Vercel rejects the repo's git author email.
- getIPIntel echoes the contact in EVERY response; IPQS quotes the rejected key in its failure message.
  Both scrubbed and tested — keep it that way, and do the same for Scamalytics.

## Resume phrase

```
Read handoffs/2026-08-06_ipcheck-scamalytics-pwa-icons.md and resume, on branch
feat/bulk-comparison-and-ipv6-coverage (PR #83). Order: Scamalytics integration first (a workflow already
measured the v3 API live — read its spec before coding), then kill the pointless apply-time "Identity
won't match a saved login" dialog, then PWA + favicons, the asset render-test page, the Android detail
view, then ONE batched deploy + verify, then the codex review. The repo is temporarily PUBLIC — no
credential may ever enter the tree. Look at the screen before calling any UI change done.
```
