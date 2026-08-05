# Handoff: ip-check bulk comparison, PWA, icons, Android detail view

Created 2026-08-06. Branch **`feat/bulk-comparison-and-ipv6-coverage`**, PR **#83** (open, repo is
temporarily PUBLIC so the review bots can run — set it back to private later if they never show up).

> A SECOND session is working in parallel on an unrelated Android bug (the "Identity won't match a saved
> login" dialog). Do not touch `MainActivity.confirmDriftThenApply` / `driftWarnings` / `AppDataVault`, and
> coordinate before switching branches — the working tree is shared.

## State: 3 of 8 tasks done, tests green, nothing deployed yet

Python suite green (`.venv/Scripts/python.exe -m pytest -q`). JVM suite green
(`cd xposed-module && bash run-jvm-tests.sh`). The Android module compiles.

**Deliberately NOT deployed.** The user asked for ONE batched deploy at the end of a run, not a deploy per
change. Vercel and both phones are still on the previous build.

### Done

1. **Blocklists always run, including on IPv6.** A bulk run reported an IPv6 exit with `0` blacklists and a
   CLEAN verdict claiming "no abuse or blacklist history" — nothing had been checked. Measured, not
   assumed: `res.proxy-seller.com:10000` is dual-stack (Starlink), 8 samples gave 5x IPv4 / 3x IPv6. An
   early probe using `2001:db8::2` wrongly suggested no zone supports IPv6; re-measured against 60 live
   IPv6 Tor exits, **four zones do** (s5h 39 hits, Spamhaus 24, CBL 14, DroneBL 5; the other thirteen 0).
   `DNSBL_ZONES_V6` + `reverse_v6()` + a dual-stack IPv4 fallback. Positive control: 8/12 dirty IPv6 exits
   come back listed.
2. **Android DoH resolver.** The phone showed zones with no answer because it asked Cloudflare, which
   Spamhaus/CBL refuse. Measured on `185.220.101.45` (7 listings): Cloudflare 14/17, **Google gives a FALSE
   CLEAN** (NXDOMAIN on a listed IP), dns.sb 17/17. Now dns.sb primary, Cloudflare fallback, never Google.
   Pinned by a test.
3. **Bulk comparison table.** 14 sortable columns, summary strip, chevron opens one grouped key/value
   detail table, credential copy chips on a single row. Verified in-browser against the user's real
   proxies.

### Open — this is your queue

4. **PWA + favicons** — manifest, service worker for the offline shell, apple-touch-icon, full favicon set
   (16/32/180/192/512 + maskable). Must survive `webapp/build.py`: icons are static files in `webapp/`, the
   manifest `<link>` goes in `PAGE`. `webapp/vercel.json` uses the legacy `builds` config, so any new
   static asset needs a matching build/route entry or it 404s.
5. **Asset render-test page + fix every broken icon** — the user's words: the Data Center icon "shows a
   WEIRD rectangle... this is garbage". It is `ICON.server`, two rounded rects that read as a blob at 13px.
   Build a page rendering EVERY asset at its real size — the six line-type SVGs, chevron, flag images,
   copy-chip tick, verdict pills, favicons, PWA icons, app icon — and fix anything not instantly
   recognisable. Check on Windows/Chrome, where emoji coverage is poor (this is why flags are `<img>`, not
   emoji: a regional-indicator pair renders as the two letters).
6. **Android meaningful-info-first detail view** — do not dump everything. Show what the use case needs:
   blocklists (hits/checked), fraud score, whether a proxy/VPN/Tor is DETECTED, abuse, getIPIntel, exit
   type. Mirror the IPv6/dual-stack handling, the v6 zone table + honest denominator, and the
   "not checked != clean" wording.
7. **Deploy ONCE, then verify by comparison** — live page md5 (EOL-normalised) vs `webapp/index.html`; for
   Android compare dex **marker strings**, NOT dex md5 (two builds of identical source differ on this
   toolchain). Phones: `adb connect 192.168.50.144:5556` (P4), `192.168.50.19:5557` (4a).
8. **Codex review of the whole body of work** — the user explicitly asked for this before wrapping up.
   Feed it the full diff plus the goal statement below. Pipe the prompt
   (`echo "$P" | codex exec -m gpt-5.6-terra -`), never pass it as an argument, `tee` the output, run in
   background, and verify real findings came back. Never run codex inside a Task subagent.

## The goal, in the user's words

> Make this a usable quickly functional utility where we can drop in a bunch of proxies and see a good
> overview (with the ability to dig deeper). A user should be able to check one proxy / their current exit
> IP easily, or add a bulk of 10 and get a breakdown of the most meaningful info for cleanliness /
> reputation — be able to pick which one of the bulk is best — then click to copy the hostname / port /
> username / password.

## Standing rules you will be judged against

Memory `ui-layout-rules-no-ragged-text` and `batch-deploys-not-per-change`. The ones that bit repeatedly:

- A value NEVER wraps mid-text. Truncation ALWAYS needs an escape hatch (title and/or the detail view).
- Never make the reader decode a colour — label the meaning.
- Colour is ONE-DIRECTIONAL on third-party labels: only warn, never reassure. AbuseIPDB called a NordVPN
  Tor exit "Fixed Line ISP"; painting that green was a lie.
- Never claim more than was measured. "Not obviously a datacenter" is not "residential". "0 blacklists" is
  not "0 of 17 checked".
- Buttons must not resize on click.
- Info blocks are label→value rows, not prose. No paragraphs anywhere, including UI copy.
- **Look at the screen before saying it is done.** Most of the churn in this session came from shipping UI
  changes unseen and only finding the breakage from the user's screenshots.

## Traps already paid for

- `webapp/index.html` is GENERATED. Edit `PAGE` in `specter/ipcheck.py`, re-run `python webapp/build.py`.
  build.py asserts on each rewrite because a silent mis-target once shipped a page where every button was
  dead (a non-greedy regex stopped at a `});` inside the block it was replacing → SyntaxError).
- Table columns: `max-width:0` collapsed the proxy host to one character; `table-layout:fixed` then
  squeezed ISP to "Spa...". Columns now size to content — leave it that way.
- The breakout wrapper must not also carry `.blk`: that animation ends on `transform:none` with fill-mode
  both and permanently wipes a centring translate.
- `100vw` includes the scrollbar — a too-small inset gives the whole page a horizontal scrollbar.
- Deploy from a NON-git copy: `cp -r webapp/. <scratchpad>/ipcheck-deploy/ && npx vercel deploy --prod
  --yes`. Vercel rejects the repo's git author email.
- Secrets: getIPIntel echoes the contact in EVERY response; IPQS quotes the rejected key in its failure
  message. Both are scrubbed and tested — keep it that way when touching those paths.

## Resume phrase

```
Read handoffs/2026-08-06_ipcheck-bulk-pwa-icons.md and resume. Work on branch
feat/bulk-comparison-and-ipv6-coverage (PR #83). Start with the PWA + favicons and the asset render-test
page, then the Android detail view, then ONE batched deploy + verify, then the codex review. Another
session is working in parallel on the Android "Identity won't match a saved login" dialog — do not touch
MainActivity.confirmDriftThenApply/driftWarnings or AppDataVault, and do not switch branches without
checking with me. Look at the screen before calling any UI change done.
```
