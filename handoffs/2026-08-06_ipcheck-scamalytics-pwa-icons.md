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

A workflow measured the v3 API live (~200 lookups) and wrote a full implementation spec. Run id
`wf_33fff9a6-39a`; journal under `.claude/projects/F--claude-specter/<session>/subagents/workflows/`.
Endpoint `https://api11.scamalytics.com/v3/<user>/?key=<key>&ip=<ip>` (US node; EU is `api12`, bound at
signup). Docs <https://docs.scamalytics.com/ip-fraud-risk-api/v3/>. Account is **Premium**.

**THE HEADLINE, and it decides the design: the SCORE is noise, the FLAGS are the reason to add this.**

- The score does not saturate like IPQS — it fails the opposite way, flattening everything low and
  MIS-RANKING. Measured: Tor exit **15 "low"**, clean Comcast residential **18**, AWS **1**, Cloudflare
  **0**, Google **0**, and the highest score in the whole set is Mullvad at **44**.
- Why: `scamalytics_score` ≈ `scamalytics_isp_score` on every single IP. It is an ISP/ASN reputation
  prior, not an IP-level abuse measure — constant at 13 across three Starlink IPs with different abuse
  histories.
- No threshold orders the set correctly: catching Mullvad (44) means passing a Tor exit (15) and flagging
  clean Comcast (18). **Give it zero weight in `verdict_factors()`.** Show it labelled next to the ISP
  score, warn-only colour at high / very high, never green.
- The FLAGS are genuinely additive: `scamalytics_proxy.is_datacenter` + `ip2proxy.proxy_type` (DCH/TOR)
  caught **all four** hosting IPs our own name heuristic missed. Specific, not trigger-happy — all three
  Starlink exits and T-Mobile came back `proxy_type "0"`, `is_datacenter false`.
- **This already paid for itself:** that finding exposed a live false-benign — `8.8.8.8` and `1.1.1.1`
  read verdict CLEAN, "No datacenter signal". FIXED on this branch (commit "Fix a false all-clear"), both
  now read DIRTY. The Scamalytics classifier would have caught it independently.
- Recommendation to implement: union `is_datacenter` + `ip2proxy` DCH/VPN/PUB/WEB/SES into
  `connection_class()` (which already drives dirty), and add TOR as its own dirty factor.

**UI SPLIT — the user's explicit direction, and it matches what the API actually returns.** Scamalytics
does have an overall verdict plus the underlying per-datasource checks:

- QUICKVIEW (bulk table + the single-check tiles): **one column/tile — the overall
  `scamalytics_score` + `scamalytics_risk` band.**
- DETAIL VIEW: everything underneath it, as its own group:
  - `scamalytics_score` / `scamalytics_risk` (the overall) and `scamalytics_isp_score` /
    `scamalytics_isp_risk` (the ISP-wide score) — show them ADJACENT, because they are near-identical on
    every IP measured and seeing that is what tells the reader the score is an ISP prior.
  - `scamalytics_proxy`: `is_datacenter`, `is_vpn`, `is_apple_icloud_private_relay`, `is_amazon_aws`,
    `is_google`
  - `external_datasources`, the nitty-gritty: `x4bnet` (vpn / datacenter / tor / spambot / bot flags),
    `ip2proxy.proxy_type` (TOR / DCH / VPN / PUB / WEB / SES), `firehol` (30d / 1day / is_proxy),
    `ipsum` (blacklisted + count), `spamhaus_drop`, `google` (googlebot / crawler / cloud),
    `amazon_aws`, `apple_icloud_private_relay`, `dbip.connection_type`
  - `is_blacklisted_external`, `scamalytics_url` (a link to their page for that IP)

CAVEAT to honour while doing it, because the two pull against each other: the overall score is the part
that MIS-RANKS (Tor 15 vs clean Comcast 18 vs Mullvad 44). So put it in the quickview as asked, but
colour it WARN-ONLY — amber/red at high and very high, never green — and label it "Scamalytics" rather
than anything implying a verdict. The datacenter/Tor flags carry the actual signal and are what feeds
`connection_class()`; the score is shown because the user wants it visible, not because it decides.

**Bands** (documented + all four observed): `low` `medium` `high` `very high` — lowercase, with a SPACE.
Thresholds 0-19 / 20-59 / 60-89 / 90-100. NOT the quartiles the website uses.

**Traps, all measured — these will bite:**

- **HTTP 200 does not mean success.** Always read `scamalytics.status`. A malformed IP and a missing
  key/ip both return 200 with `status:"error"`.
- **A rejected key returns HTTP 404 with an Apache HTML body**, not JSON. Never `json.loads` unguarded.
  (The docs claim 401 + JSON. They are wrong.)
- **On any error, `external_datasources` flips from an object to an empty ARRAY `[]`** — so
  `.get("firehol", {})` raises. Check `status` FIRST; the guard order is load-bearing.
- **Reserved/unroutable IPs are not errors**: `127.0.0.1`, `10.0.0.1`, `0.0.0.0` all return ok / score 0 /
  `low`. A "0 low" can mean "not a real exit" and must never render as reassurance.
- **Never render `ip2proxy_lite`** — measured empty on all 8 IPs; it would read as "checked and clean".
- `ipsum.num_blacklists` is int `0` when clean but the STRING `"3"` when listed. `credits.*` change type
  in test mode. On the Essential tier the Premium fields hold the literal
  `"PREMIUM FIELD - upgrade to view"` — the existing `_premium()` helper already matches it, reuse it.
- `scamalytics_raw` must be **FLAT**: `kv()`/`fmtv()` in `PAGE` render a nested object as
  `[object Object]`. Add `LBL` entries or the card prints raw snake_case.
- **No credential echo anywhere in the response** (substring-checked, both values). But the key rides in
  the QUERY STRING, so never put the request URL — or an exception carrying it — into a note, a log, or a
  `*_raw`. Strip `credits.*` from client output too: that is our quota state, not the visitor's business.
- Cost ~2 credits/lookup, ~2500 lookups left of 5000. Surface `credits.remaining` so an exhausted balance
  says so instead of silently degrading the verdict.

**Plumbing — it is a USER + KEY pair, a first.** `resolve_keys()` returns a 3-tuple today (one caller,
`main()`); make it a dict. `check()` gains `scam_user`/`scam_key` as trailing keyword params so existing
positional calls are unaffected. The config key tuple appears TWICE in the local server (GET `/config`
and POST). Also: the Vercel function's env fallback, `/api/config`'s booleans, the CLI flags, and two
stacked fields in the keys UI.

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

### 2b. Small Android UI items already FIXED (verified on the 4a) — do not redo

The "Use your real IP?" confirm is gone, `networkMetaRow`'s caption is single-line (it was breaking
mid-word into "IPQUALITYSCO / RE"), and a source that could not run now shows an `n/a` TILE instead of a
sentence of advice. Apply the same `n/a` treatment to any source added later, Scamalytics included.

### 2c. Test proxies and what they showed

`~/.specter-testproxies.txt` (OUTSIDE the repo — live credentials, and the repo is public). Three tiers:
proxy-seller (Starlink, sticky per port), lightningproxies (rotating, **SOCKS5 on :1080**), and the host
machine's own Mullvad exit.

Measured 2026-08-06:

| Vendor | Exit | Verdict | Blocklists | ISP |
|---|---|---|---|---|
| proxy-seller :10000 | 153.66.193.140 | clean | 0/17 | SpaceX Starlink |
| proxy-seller :10001 | 153.66.195.55 | clean | 0/17 | SpaceX Starlink |
| proxy-seller :10002 | 153.66.193.3 | suspect | 0/17 | SpaceX Starlink |
| lightning :1080 | 24.26.39.144 | dirty | 2/17 | Spectrum |
| lightning :1080 | 69.40.189.83 | dirty | 3/17 | Windstream |
| lightning :1080 | 23.252.131.181 | dirty | 2/17 | Barbourville Utility |
| mullvad (host) | 23.159.216.252 | suspect | 0/17 | Byte Node |

Two things to act on:

- **A SOCKS proxy run as HTTP just reads DEAD**, indistinguishable from a genuinely dead one. Lightning is
  :1080 and silently failed until retried as SOCKS5. Either auto-retry the other transport on a
  connect failure, or say "no response as HTTP — try SOCKS5" rather than DEAD. This is a trap for anyone
  pasting a vendor list.
- **The datacenter heuristic misses Mullvad.** "Byte Node LLC" is not in `_DATACENTER_RE`, so a known
  commercial VPN exit renders Exit type `—` (unclassified). Worth widening, or leaning on getIPIntel /
  Scamalytics for that call instead of the name regex.

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

### 6. Deploy ONCE, verify by comparison — Pixel 4a ONLY

**The Pixel 4 is IN ACTIVE USE by the user. Do not install to it, do not send input events, do not
force-stop anything on it.** A blind `adb shell input tap` sequence already landed inside a live DoorDash
onboarding flow on that device this session. Always check
`adb -s <dev> shell "dumpsys window | grep mCurrentFocus"` before touching ANY phone.

Live page md5 (EOL-normalised) vs `webapp/index.html`. For Android compare dex **marker strings**, NOT dex
md5 — two builds of identical source differ on this toolchain. Pixel 4a: `adb connect 192.168.50.19:5557`. The P4 (`192.168.50.144:5556`) is off-limits for now — the user will say when it is free.

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
