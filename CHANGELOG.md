# Changelog

All notable changes to Specter are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/). Versioning: [SemVer](https://semver.org/).

## [0.28.0] - 2026-08-06

### Added
- **Device-bound activation (offline).** A cogwheel and a key icon now sit top-right in the app bar. The
  key opens an Activation screen: it shows a live status (Active until <date>, N days M hours left / Expired
  / Not activated), this device's binding hash to send for a key, and a paste field. Verifies entirely
  offline against an EC P-256 public key compiled into the app — no server, no network call.
- **`scripts/make_activation.py`** — operator-side generator. `setup` mints the keypair (private key kept
  outside the public repo); `<device-hash> <1d|1w|1m>` signs a code and logs it to a local ledger.
- Keys bind to the REAL device (android_id hash), not the spoofed one, and a monotonic clock guard stops a
  rolled-back clock from resurrecting an expired key. Proven end-to-end on-device.
- **Web: bulk now accepts bare IPs, not just proxies.** A line with no port cannot be a proxy, so bulk
  checks bare IPv4/IPv6 lines directly (shown as IP in the status column) and everything else as a proxy —
  so one paste can mix both. Fixes bare IPs coming back DEAD.
- **Web: a settings cogwheel, top-right.** The API keys moved out of an inline section into a cogwheel that
  opens a native dialog (ESC / backdrop / â to close). Keys still store in the browser only.

## [0.27.0] - 2026-08-06

### Added
- **Scamalytics v3** (web + Android + CLI). Its datacenter/VPN/Tor classifier feeds the exit-type verdict;
  its SCORE is shown but has zero weight. Measured over ~200 live lookups: the score tracks
  `scamalytics_isp_score` on every IP (an ASN prior, not a measurement of the address) and mis-ranks — a Tor
  exit scored 15 "low", clean Comcast residential 18, and the highest of the set was Mullvad at 44. So it is
  rendered warn-only, labelled "shown, not scored", never green.
- **`tor` is its own exit type.** A Tor exit also reads `is_datacenter`, so it was reported as plain hosting.
  It is now named, and neither `tor` nor `datacenter` can render green in any of the three places that draw
  the exit type.
- **The dirty factor names its source** — `datacenter/hosting IP (Scamalytics DCH)` — so a third-party
  misfire is diagnosable at a glance instead of looking identical to our own name heuristic.
- **Android: IPv6 blocklist coverage.** An IPv6 exit used to return zero zones behind a clean-looking
  verdict. It now queries the four zones that hold IPv6 data and reports THAT denominator, with the /64
  granularity caveat spelled out. Mirrors the desktop table (pinned by a parity test).
- **Android: a meaningful-info-first IP breakdown.** The detail now opens with what the exit IS — exit type,
  blocklists with an honest denominator, fraud score, detectable-as, abuse, getIPIntel, Scamalytics — before
  the per-source dumps. A source that did not run says so; a missing row would read as "fine".
- **PWA + a full icon set.** Manifest, an offline shell (network-first for the page, so it can never pin a
  visitor to a stale build; `/api/*` never cached, because a cached measurement is a lie), apple-touch-icon
  and 16/32/180/192/512 + maskable — all rasterised from one `webapp/icon.svg` by `webapp/make-icons.py`,
  so the tab icon, the home-screen icon and the app icon cannot drift apart.
- **An asset render-test page** at `/assets.html`, generated from the real PAGE so it can never show a
  stale copy: every icon at the size it is actually drawn plus a 4x blow-up, each usage string next to the
  icon and colour it produces, the chevron in both states, flags, verdict pills and the copy controls.
- The Specter ghost mark is symmetric now — its body's right wall sat at x=34 while the head's arc sprang
  from x=39, which is the step that was visible on the right shoulder of the Android launcher icon.
- Generated-page guards: a test parses the emitted `<script>` (a mis-targeted rewrite once shipped a page
  whose every button was dead) and a second fails when `webapp/index.html` is stale.

### Changed
- `resolve_keys()` returns a dict, not a 3-tuple — Scamalytics is the first source that is a USER + KEY pair.
- The local server's config key list is one tuple instead of two copies, so a source can no longer be
  saveable but not loadable.

### Removed
- **The apply-time "Identity won't match a saved login" confirm.** Generating an identity and applying it IS
  the new-account flow: not matching an old login is the point, and apply force-wipes each target before
  writing, so no session survives to be incoherent with. Reopening an account is the Saved tab's job.
  `AppDataVault.conflictingDevices()` went with it.

### Fixed
- **A blocklist sweep where every zone REFUSED read as clean.** Spamhaus and CBL answer 127.255.255.254 to
  queries relayed by large public resolvers, and a refusal is correctly not counted as checked — but
  `dnsbl_usable` was set from the sentinel probes alone, so that run reported "no abuse or blacklist
  history" having obtained nothing. Coverage now requires at least one zone to have answered, and the
  report says WHY there is none.
- **Android said "no abuse history" when no zone answered at all.** The clean verdict now emits
  "blocklists NOT checked", the same wording the desktop uses, from one shared expression.
- **Reputation was measured on one address and reported as another.** On a dual-stack exit the address was
  switched to IPv4 *after* IPQS/AbuseIPDB/getIPIntel/Scamalytics had already been asked about the IPv6 one.
  The family is now settled before any source is queried.
- **`scripts/backup_vault.py`** — backs up every connected device's saved fingerprints AND saved logins
  (plus the LSPosed-redirected prefs) to `backups/`, md5-verified through a shell rather than `adb pull`,
  which silently no-ops on a rooted device. `--check` reports backup age. Written after a `pm clear` run
  destroyed a device's vault; the first run found the Pixel 4 holding 33 fingerprints and 20 saved logins
  with no backup at all.
- **A SOCKS proxy addressed as HTTP is retried instead of reported DEAD.** An entire vendor list (SOCKS5
  on :1080) read dead, indistinguishable from genuinely down. When the line carried no explicit
  `scheme://`, the other transport is tried once and the report SAYS what happened — "no answer as HTTP —
  it responded as SOCKS5". A genuinely dead proxy still reads DEAD and says both were tried.
- **getIPIntel is now the last-resort datacenter classifier** (`>= 0.99`). Mullvad's exit ISP "Byte Node
  LLC" matches no name rule and Scamalytics reported it `is_datacenter false`, so a known commercial VPN
  exit rendered "unclassified"; getIPIntel called it 1.00. The threshold is the one that already earns a
  DIRTY on its own, so this adds no new verdict — only the name of what the exit is — and the factor reads
  `datacenter/hosting IP (getIPIntel)` so a wrong call is attributable.
- **Dev builds can pre-seed the API keys.** `python xposed-module/make-dev-keys.py` writes a GITIGNORED
  `dev-keys.properties` from `~/.specter-ipcheck.json`; the build bakes those into `BuildConfig` and the
  app writes them into prefs once, on the first launch that finds them. A DISTRIBUTABLE build is simply
  one made without that file — every field is empty and the app shows "Not set", which is already the
  right UI for a user bringing their own keys. The build prints which of the two states it is in, and an
  existing on-device value is never overwritten.
- **Scamalytics is coloured in its own four-band scale** — low green, medium amber, high orange, very
  high red — at the user's request, rather than warn-only. Safe because the score is fenced off from the
  verdict entirely (zero weight at every tier, locked in both directions by a test), so a green "8 · low"
  beside a DIRTY verdict is Scamalytics' opinion next to ours, and every row still says "shown, not scored".
- **The Scamalytics page link is a real link now**, on both web and Android. It was rendering as plain
  text, so tapping it did nothing. (It 403s a bot user-agent and 200s a browser, so it only ever worked
  from the page — it just was not clickable.)
- **Android classified a mobile exit as a datacenter.** `connectionClass()` was added without the
  `mobile` branch that `connection_class()` has, and `Reputation` never read IPQS's `mobile` flag at all —
  so a mobile exit whose ISP string contains a hosting term read `mobile` on the desktop and `datacenter`
  on the phone, for the same IP. Both the class and the verdict now check it first, pinned by a test.
- **The bulk table sorted unmeasured rows to the TOP on a descending sort.** `Infinity` was multiplied by
  the sort direction, so clicking a score heading twice to find the worst exit put every `n/k` / `n/a` row
  above the dirtiest real result. Absent now sorts last in both directions.
- **A dead proxy crashed the page.** `check()` returned early with no `verdict` key when the exit-IP
  lookup failed, and the page did `r.verdict.toUpperCase()` on it — so an ordinary unreachable proxy read
  "FAILED · TypeError: Cannot read properties of undefined". Every return carries a verdict now, and the
  renderer defaults rather than assumes.
- **The bulk table no longer scrolls horizontally** at a normal window width. Column headings name their
  SOURCE (IPQS · GII · AbuseDB · SCAM · DNSBL) so a bare number is never anonymous; IPQS's flags are
  three-letter codes with the meaning on hover and a `+N` overflow instead of "VPN Proxy Recent abuse Bot";
  the policy-listing count moved to a sub-line; locations read "Redmond, US" rather than
  "Redmond, Washington, United States"; the proxy host truncates while its port never does.
- **Scrollbars follow the theme.** The default light-grey OS bar under a dark panel read as a rendering
  fault, on a table that is horizontally scrollable by design.
- getIPIntel's `n/a` now says WHY — it meters 15 lookups a minute per contact, so a bulk run can genuinely
  exhaust it mid-batch, and a bare "n/a" left no way to tell that from a failure.
- **Three of six line icons rendered blank**, and a fourth as a featureless rectangle. `rx=1.2/>` unquoted
  parses as the value `1.2/` with no self-close, so the element swallowed its siblings — `ban` drew
  nothing at all. Every inline-SVG attribute is quoted, the shapes are redrawn for 13px, and
  `webapp/check-icons.py` now measures ink, spread, interior detail and pairwise distinctness so this
  cannot recur silently.
- `Corporate` and `Content Delivery Network` matched no usage rule, so they rendered with no icon.
- An unbracketed IPv6 proxy (`2001:db8::1`) parsed as host `2001:db8:` port `1` and was accepted. Bracketed
  IPv6 is now supported; unbracketed multi-colon input is refused instead of guessed at.
- The `dnsbl_skipped==='ipv6'` branch in all three UI copies was unreachable and its message was stale.
- Android: the blocklist group count sat at the end of a fixed-width label that truncates, so it was the
  first thing lost. It leads the value now.

## [0.26.0] - 2026-08-05

### Added
- **Per-source detail breakdown, collapsed by default** (web + Android). Every field each source returned,
  grouped by source, so a verdict can be audited instead of taken on faith: IPQualityScore's full response,
  getIPIntel's score/BadIP/country, AbuseIPDB's usage type, reporters and last report, and every blocklist
  zone with its own outcome.
- **Blocklist zones grouped by what the answer MEANS** — Listed / Policy only / Clean / No answer, each
  labelled with its meaning, so a colour never has to be decoded. Zones that refused or never replied are
  kept out of "clean": a zone that didn't answer proved nothing.
- **The verdict names its own evidence.** `verdict_factors()` exposes the individual signals behind a
  level, shown as chips under the verdict instead of a bare "SUSPECT".
- **Proxy liveness and latency.** The geo lookup is the first request through the tunnel, so it doubles as
  the probe: `proxy_alive` and `proxy_ms`, with Alive and Latency columns in the bulk table.
- **getIPIntel: country, plain-language errors, and contact rotation.** `oflags=bc` also returns the
  country; negative results map to one-line meanings (-5 is "over quota from here", not a verdict on the
  checked IP); several contact addresses can be configured and rotate when one is refused for quota.

### Changed
- **One layout rule everywhere: a value never wraps mid-text, and nothing is truncated without a way to
  read the rest.** Tile captions are one line with the full text on hover; detail rows are one line each;
  info blocks are label -> value rows instead of prose; copy buttons flash a colour instead of resizing.
- **No auto-run on open.** The page prefills the visitor's IP and waits to be asked — opening it no longer
  spends an API quota or a getIPIntel rate-limit slot. A second lookup source covers ipwho.is throttling.
- Form reordered: IP above proxy (they are alternatives, not a pair), Run check on its own inputs, accepted
  proxy formats collapsed, API keys last and stacked one per line.
- Country flags, and an icon plus colour for the line type (hosting / mobile / consumer / institutional).
- Dropped "residential-ish" and the editorial fraud-score captions from all three surfaces. A low getIPIntel
  score now reads "no proxy signal": it means no proxy evidence was seen, not that a real ISP line was proven.

### Fixed
- **The generated Vercel page was one regenerate away from being completely inert.** `webapp/build.py`'s
  config rewrite used a non-greedy regex that stopped at a `});` INSIDE the block, leaving orphan lines --
  a JavaScript SyntaxError that kills the whole `<script>`, so the page renders but every button is dead.
  Anchored the match and added a tripwire assert. Also dropped a selector for an element that no longer
  exists and restored the `/api/config` fetch, so shared server-side keys show as "shared active" again.
- **getIPIntel echoes the contact address in every response** (measured). It is stripped from the raw body
  and from any rejection message, so a server-side contact can never reach a visitor's browser.
- IPQS's `abuse_events` is a LIST holding an "Enterprise plan required" notice; the premium-placeholder
  filter only checked strings, so the notice was being shown as data.
- The local web UI silently stopped running getIPIntel once the page stopped sending a contact; the server
  now falls back to its own config/env, as the hosted function already did.

## [0.25.1] - 2026-08-05

### Added
- **getIPIntel as a keyless reputation source.** Free, no signup — it only needs a contact email — and it
  DISCRIMINATES where IPQS saturates: a 0-1 probability that grades residential-vs-hosting (measured live:
  AWS 1.0, Starlink 0.0) plus a BadIP flag for malicious behaviour. It also catches hosting/VPN exits the
  name-based datacenter check misses (e.g. Cloudflare). A near-1 score or the BadIP flag drives the verdict;
  shown as its own tile/row on the web UI and the Android card, and it leads the “sharpen detection” settings.

### Changed
- **IPQS's abuse flags no longer condemn on their own.** recent_abuse/bot/frequent_abuser saturate on shared
  and residential-proxy IPs the same way the score does, so alone they now read SUSPECT, not DIRTY — a
  reliable independent source (blacklist, AbuseIPDB, getIPIntel) is what escalates to dirty.
- **Web UI: IPQS is no longer the visual hero.** The giant fraud meter is gone; the score is one tile among
  the signals (Blacklists · Fraud · getIPIntel · Exit type), matching the de-weighted model. The legend is
  accurate (17 blocklists, datacenter + getIPIntel, keyless-first) and the Blacklists tile turns red at 2+.

## [0.25.0] - 2026-08-05

### Changed
- **IP reputation now scores for real proxy usability, not mail-spam reputation.** Two problems with the
  old scoring: (1) IPQualityScore's fraud_score saturates — it scores almost any proxy/VPN 75-100 because
  “is this a proxy?” dominates it, so it can't tell a fresh residential proxy from a burned one; (2) the
  verdict leaned on email DNSBLs, which measure mail spam, not what a strict app checks. The verdict is now
  driven by the signals that actually predict friction: whether the exit is a **datacenter/hosting IP**
  (detected free from the ISP/org/host names — real users don't originate from AWS/OVH) and **independent
  abuse evidence** (blacklists, AbuseIPDB, IPQS's *abuse* sub-flags — not the bare proxy flag). A clean
  residential exit now reads CLEAN even at IPQS 100; a datacenter exit reads high-friction even with a
  spotless blacklist record. The raw IPQS score and proxy flag are still shown as their own signals, and a
  new **Exit type** appears on the web UI, terminal, and the Android network card.

### Fixed
- **Datacenter detection works with no API key** — it reads the free ipwho.is ISP name, so an AWS/OVH/GCP/
  Azure exit is flagged even without an IPQualityScore key. GCP/Azure are matched by their “Google LLC” /
  “Microsoft Corporation” WHOIS names (which don't contain “cloud”); Google Fiber stays unflagged.
- **The off-tunnel “use your real IP” confirm can't be bypassed by a tunnel flap** — the consent decision is
  captured in the dialog and passed through, never re-inferred from later VPN state, so a tunnel flapping
  up-then-down can't run a reputation check or timezone-align on the real IP without the dialog showing.

### Added
- **Five more blacklist zones** (0SPAM, SpamEatingMonkey, Backscatterer, UCEPROTECT L2/L3) — 12 → 17,
  closing a coverage gap (an IP reading 1 here matched 6 on other tools). UCEPROTECT L2/L3 are netblock/ASN
  listings, shown as policy — not folded into the per-IP abuse count. Desktop + Android tables kept in sync.

## [0.24.9] - 2026-08-05

### Added
- **A target now reads READY without having to be launched.** The Protection-status per-app check had only
  GREEN (this app's hooks proven running this boot) or a nag to “open the app, then re-check.” Now, once ANY
  scoped app has proven the module loads on this boot (a fresh heartbeat), every other scoped app that has an
  identity applied reads a blue **READY · hooks on launch** — you launch one app per boot to confirm the
  layer is live, not every target. GREEN still means that specific app's hooks are proven running this boot
  (no false-GREEN), and the hero summary gets its own blue “Ready” tier between amber “Not verified” and green
  “All good.” Just after a reboot with nothing launched yet, targets still show amber (open any one to confirm).

## [0.24.8] - 2026-08-05

### Added
- **Exit-IP reputation and timezone-fix now work off-tunnel, on the device's real IP, behind an explicit
  confirm.** Previously both were hard-gated to a VPN/proxy tunnel. Now, with no tunnel active, the Status
  card offers a “Check this IP anyway” button and a timezone-vs-IP fix that run on your real public IP after a
  “Use your real IP?” confirmation dialog. The AUTOMATIC paths stay tunnel-only — the reputation auto-check on
  open and the on-apply timezone alignment never touch the real IP — and a new `allowRealIp` guard closes the
  tunnel-flap window so a dropped tunnel can never silently query the real IP. Geolocation of the real IP was
  already shown off-tunnel (unchanged).

## [0.24.7] - 2026-08-05

### Changed
- **ipcheck web UI now loads and scores the current exit IP on open** — no need to hit Run first;
  an empty proxy checks this machine's own exit. The proxy hint is the colons-only `host:port:user:pass`
  form resi providers hand out (the `user:pass@host:port` and scheme-URL forms still parse). Added a
  one-line legend under the button naming what each score comes from: **ipwho.is** geo/ISP and
  **~120 DNSBLs** are free/no-key; **IPQualityScore** and **AbuseIPDB** enrich the verdict when a key is set.

## [0.24.6] - 2026-08-05

### Added
- **Profile validation now catches four more incoherent field pairs.** `validate()` already checked
  formats, the fingerprint, and the SIM's IMSI/ICCID; it now also flags a SoC that disagrees with its
  GPU vendor (a Qualcomm/Adreno chip reporting an ARM GPU, or vice-versa), a carrier NAME that doesn't
  match its MCC/MNC, a `build_board`/`build_hardware` codename disagreement, and a security-patch date
  that predates the OS release. Proven false-positive-free over 500 generated profiles; catches the
  class of generator regression the coherence sweep exists to prevent. Validation-only — no seeded
  draw changes, so Java↔Python byte-parity is unaffected.

## [0.24.5] - 2026-08-05

### Fixed
- **Stopping a monitor now leaves a clean, valid-JSON profile.** A read-only audit of the profiles
  applied across the fleet found one carrying a stale, unquoted `{trace:1,` prefix — written by an
  older version of the monitor's arm step, and un-removable by the disarm step (which only stripped
  the quoted form). It never affected spoofing (the profile reader skips to the first quoted field),
  but it left the file as invalid JSON. Disarm now strips the legacy form too, so a monitor→stop
  self-heals it; freshly applied profiles were never affected.

## [0.24.4] - 2026-08-05

### Changed
- **The Network card checks the exit IP's reputation automatically.** You no longer have to tap "Check
  IP reputation" every time you open the Status view — the first time the card shows a given exit IP
  with the tunnel up, it runs the lookup on its own. It runs exactly once per distinct IP (the result
  is cached for the session, and IPQualityScore's free tier is only 35/day), so a stable IP never
  re-spends a lookup; a rotated IP checks itself afresh. The manual "Re-check reputation" button
  stays for a forced refresh, and the tunnel gate is unchanged — off-tunnel it still never touches the
  home IP.

## [0.24.3] - 2026-08-05

### Changed
- **The per-app "Restore AppData" button now uses the same coherent restore as the Saved tab.** It used
  to do a bare restore of the last staged capture without re-applying the login's fingerprint — which
  could leave the app on a mismatched device. It now restores from the vault: one saved login for the
  app restores straight away, several let you pick which (fingerprint↔login is one-to-many), and the
  restore re-applies that login's own device — exactly what the Saved-tab picker does. If nothing is
  vaulted for the app it still falls back to the last staged capture, so no path is lost. The two
  restore entry points are one flow now.

## [0.24.2] - 2026-08-05

### Added
- **Auto-save the read-capture report when monitoring stops.** Stopping a monitor opens the live-trace
  tab, which previously needed a manual Export to keep the readable coverage report — easy to forget.
  A new "Auto-save report when monitoring stops" toggle (in the trace tab, default on) writes the
  report to Download/Specter automatically on stop, reusing the same Export path. Manual Export still
  works, and the raw capture was already archived on stop regardless.

## [0.24.1] - 2026-08-05

### Changed
- **The Identity card leads with the live identity's saved NAME.** After a restore or a vault apply,
  the current-identity card showed only the device model, so it wasn't clear *which* saved identity
  was live. It now leads with the saved name ("Petra G FL") and drops the model + carrier to a
  secondary line — so "which identity is live" is answerable at a glance, and the device-vs-name
  drift the apply warning guards against is legible right on the card. A freshly generated,
  unsaved identity (or a date-only label) still shows the model, as before.

## [0.24.0] - 2026-08-05

### Added
- **"Monitor reads on apply" checkbox**, next to "Save to vault on apply" on the Identity tab. When
  on, a successful Apply auto-starts the per-app read monitor on the first applied target, so the
  read-capture is already armed for the relaunch you're about to do — no separate "Monitor reads"
  tap to forget. Off by default (the capture runs a logcat, heavier than a vault save). The per-app
  Monitor/Stop button still works exactly as before, and the same trace bookkeeping and 30-minute
  auto-stop apply to both paths.

## [0.23.6] - 2026-08-05

### Changed
- **The checker's web UI was redesigned into a "signal desk."** The old dark-card layout read like a
  bootstrap default. It's now a monospace network instrument: a colour-banded fraud-score meter with
  the score marked against the 0/60/85/100 thresholds, a verdict strip with a semantic left edge,
  compact signal tiles, and a clean data table — with a light and a dark theme (follows the OS,
  toggle to override) and staggered reveals. Self-contained as ever: no web fonts, no CDN, still a
  double-click that works offline.

### Added
- **A Copy button on the detected exit IP** in the web UI — one tap to grab the IP (the CLI already
  prints it).

## [0.23.5] - 2026-08-05

### Added
- **Apply warns before it makes a saved login incoherent.** A saved login binds an app to the device
  it was captured under. Applying a *different* device over that app leaves it mismatched — the
  server still remembers the old model (this is why Cash's "Your devices" showed Pixel 4a while the
  live reads said SM-G996U). Apply now checks each target for a saved login under a different device
  and, if it finds one, asks first: "<app> has a saved login as <device> — applying <new device>
  won't match it." The coherent move it points you to is restoring that login from Saved (which
  re-applies its own device); applying a mismatched identity is now a deliberate, confirmed choice.

## [0.23.4] - 2026-08-05

### Added
- **The proxy field takes whatever shape you paste.** The checker (CLI and web UI) now parses
  `host:port`, the trailing-colon `host:port:user:pass` a lot of residential providers hand out,
  `user:pass@host:port`, and full `http://` / `socks5://` / `socks4://` URLs — no more forcing one
  exact form. A scheme selector (HTTP / SOCKS5 / SOCKS4) sits next to the field and `--proxy-type`
  does the same on the CLI; an explicit `scheme://` in the text still wins.
- **SOCKS5 and SOCKS4a proxies, with zero dependencies.** urllib speaks only HTTP proxies, so the
  tool now carries a small stdlib SOCKS CONNECT tunnel (no PySocks) — the "no dependencies" promise
  holds. Proven end-to-end: an HTTPS lookup completes through a live SOCKS5 proxy.

### Fixed
- A blank or malformed proxy now fails with a readable reason (`proxy needs host:port`, `port must
  be 1–65535`, `unknown proxy scheme`) instead of a stack trace or a silent wrong transport.

## [0.23.3] - 2026-08-05

### Changed
- **Restoring a login no longer launches the app.** Both restore paths — the per-app "Restore
  AppData" button and the Saved-tab login picker — used to relaunch the target the instant the
  restore finished. That was startling, and it could fire a login-bearing app (Cash, etc.) before
  you meant to touch it. Restore now leaves the app stopped and says "open it when ready"; nothing
  launches a target app without your explicit tap. (Apply already never launched anything.)

## [0.23.1] - 2026-08-05

### Fixed
- **A policy listing no longer hides behind "none of N lists".** Measured on the Mullvad exit
  `23.159.216.252`: it *is* on Spamhaus (PBL, `127.0.0.11`), and the readout still led with "none of
  12 lists", so checking it against a tool that counts every listing looked like a coverage gap.
  Splitting abuse from policy is right; keeping the policy hit out of the headline was not. The
  count line now ends with "plus N policy listing" whenever one stands.
- **A policy listing says which code fired, and stops calling itself normal.** It read "normal for
  residential and mobile IPs" — false on a hosting address, and reassurance pointed the wrong way.
  Spamhaus splits PBL into `127.0.0.10`, an entry the network owner declared themselves, and
  `127.0.0.11`, one Spamhaus added because the owner never did. Every consumer line carries the
  first; a hosting range carries the second only when Spamhaus decided that range should not be
  emitting mail — which is exactly what a proxy is being vetted for. Both readouts now name the
  reason ("Spamhaus (PBL, Spamhaus listed the range)") and describe it as a mail-sending policy
  listing rather than an abuse report.
- **The fraud score names the strictness it was scored at.** The same IP returns 20 with
  `proxy: false` at IPQualityScore strictness 0 and 100 with `proxy`, `recent_abuse` and
  `bot_status` all true at strictness 1 — measured, not assumed. Strictness stays at 1, because 0 is
  blind to a commercial VPN exit and that is the only question this tool asks; but the number is
  meaningless without the setting, so both readouts now print it.

### Notes
- **The blacklist coverage gap was traced, not patched over.** ~120 DNSBL zones were swept for
  `23.159.216.252`; exactly one lists it — Spamhaus PBL — and we already query it. Adding zones
  would not have found anything, so none were added. Three zones that answer outside `127.0.0.0/8`
  (`*.anti-spam.org.cn`, wildcarding to `208.98.43.x`) would each have been a phantom hit; the
  existing 127/8 guard rejects them.

## [0.23.0] - 2026-08-05

### Added
- **Exit-IP reputation in the Network-exit card.** The card showed the exit IP, ISP, location, and
  timezone alignment but was blind to how that IP itself scores. It now reports an IPQualityScore
  fraud score, what the IP is flagged as (proxy / VPN / Tor / abuse), its connection type and ASN, an
  AbuseIPDB report history, and a blacklist count from twelve DNSBL zones. A coherent device on a
  burned proxy still draws login friction, and no amount of fingerprint work fixes that.
  The lookup is user-triggered (IPQualityScore's free tier is 35/day), cached per IP for the process
  lifetime, and — like the timezone alignment — runs only through the VPN/proxy tunnel, so it can
  never check or expose the phone's home IP.
- **API key fields in Settings → IP reputation** for IPQualityScore and AbuseIPDB. Optional: with no
  key set the keyless blacklist count still works. Keys are never hardcoded or shipped.
- **`python -m specter.ipcheck` — the same checker as a standalone tool.** A terminal readout, a
  `--json` mode, and `--serve` for a local web UI (`ipcheck.bat` double-click opens it). It can check
  through an HTTP proxy (`--proxy`) or an IP directly (`--ip`), so a proxy can be vetted before it is
  ever assigned to a device. Stdlib only, no dependencies.

### Fixed
- **Blacklist results distinguish abuse from policy listings.** Spamhaus PBL and SpamRATS Dyna/NoPtr
  list *every* dynamic consumer address by design, so a residential or mobile exit is always on them.
  Folding those into the count would mark every good resi proxy dirty. Policy listings are now shown
  separately and kept out of the verdict.
- **A blocklist that refuses the query no longer reads as clean.** Spamhaus and CBL answer
  `127.255.255.x` to queries relayed by large public resolvers; that is a refusal, not an all-clear,
  and those zones are now excluded from the count instead of counted as clear. `127.0.0.1` answers
  (some zones' "alive, not listed" reply) are no longer counted as listings either.
- **SORBS removed from the zone list.** It shut down in 2024 and now answers "not listed" for every
  IP, which silently inflated the clean count.
- **A partial request to the local web UI no longer erases a saved API key.** The `/check` handler
  defaulted every absent field to `""` and wrote all three back, so any request that carried only
  some of them silently blanked the rest — which is how a stored IPQualityScore key went missing and
  took the fraud score with it. Only fields the request actually carries are written now; sending
  `""` explicitly still clears a key, which is how the page clears one.
- **`--ip` gets ISP, location, and time zone like any other check.** Naming an IP directly skipped
  the geo lookup, so the readout was a bare address with a dash under it. Where an IP sits is half of
  judging it, so an explicit IP now takes the same lookup as a discovered exit IP; if that lookup
  fails, the address given is still checked.
- **Blocklist lookups resolve over DNS-over-HTTPS.** The proxy apps this feature exists for hijack
  DNS — SuperProxy answers every hostname from its own fake-IP pool (measured on-device: every DNSBL
  zone returned `10.207.x.x`), so a plain resolve could never see a listing code through the tunnel.
  DoH rides the proxy like any HTTPS request and returns the real answer.

## [0.22.10] - 2026-08-03

### Fixed
- **Native prop reads no longer serve a backslash-mangled build fingerprint.** org.json writes `/` as
  `\/`, so the profile on disk holds `lge\/mh2lm\/...`. The Java layer unescaped it; the native
  layer's flat-JSON parser did not, so any app reading `ro.build.fingerprint` through
  `__system_property_get` got a value containing backslashes — wrong, and a giveaway no real device
  produces. The native parser now unescapes the same set the Java one does. The self-test fixture spelled
  the fingerprint unescaped, which is why this went unseen; it now uses the real on-disk form.
- **`ro.build.type` is spoofed on the native path again.** It was derived by searching the fingerprint
  for `:user/`, which could never match the escaped bytes (`:user\/release-keys`), so the prop silently
  fell through to the host value. The same unescaping fixes it.
- **Native and Java now agree on `\uXXXX` escapes and on truncated values.** The native parser decodes
  `\uXXXX` to UTF-8 (combining surrogate pairs) instead of emitting `u0041`, and drops a value with no
  closing quote instead of storing a partial — both matching the Java parser, so an imported or
  hand-edited profile cannot make the two read differently.

## [0.22.9] - 2026-08-02

### Fixed
- **Apply no longer wipes an app that already carries the identity.** Applied state is now tracked per
  target app instead of as one "identity + whole target set" pair. With several target apps selected, the
  status pill dropped to "Ready" after restoring a login to one of them — and tapping Apply from there
  re-wiped every target, destroying the login just restored. Apply now skips apps already carrying exactly
  those bytes, and the pill can say "On 1 of 3 apps".

## [0.22.8] - 2026-08-02

### Fixed
- **Restoring a saved login now updates the Identity tab.** A Vault restore re-applies the login's linked
  fingerprint to the device, but the Identity tab kept showing the previously generated identity as
  "Applied". It now shows the restored fingerprint — under its vault name — and the correct applied state.
- **A login restore now honours the identifier toggles.** It pushed the raw saved fingerprint, overriding
  identifiers switched off in Settings; it now pushes the same filtered map the Apply button does.

## [0.22.7] - 2026-08-02

### Changed
- **Specter Probe now has its own distinct launcher icon.** It used a generic Android system icon
  (ic_menu_info_details); it now shows the Specter ghost logo in INDIGO (#7C6BFF) — visually distinct from
  the gold main app (#E7B94E) and the teal Specter Lite (#3FD0C9), so the three are easy to tell apart in
  the app drawer.

## [0.22.6] - 2026-08-02

### Fixed
- **Specter's own launcher icon no longer disappears.** v0.22.0 hid com.specter/.lite/.probe from EVERY app's
  package enumeration to keep a fingerprinter from seeing the module — but the launcher is a normal app, so it
  hit the hide too and the home-screen/app-drawer icon vanished (the app became unlaunchable without ADB).
  com.specter* is now hidden ONLY from SCOPED apps (the ones being spoofed), via the same caller-gate as every
  other sensitive package — the launcher, Settings, and the user see it normally. The anti-detection value (an
  app you're spoofing can't enumerate the module) is preserved; the usability break is gone.

## [0.22.5] - 2026-08-02

### Fixed
- **Bluetooth/factory MAC carries the device maker's real IEEE OUI.** The BT MAC was a locally-administered
  (0x02) random address — but a factory Bluetooth address exposes the manufacturer via its OUI (unlike a
  WiFi MAC, which Android randomizes per-network, so that one correctly stays locally-administered). Added a
  per-brand table of real IEEE-registered OUIs (Google 3C:5A:B4/…, Samsung 00:1A:8A/…, LG A0:39:F7/…,
  Motorola 50:16:F4/…) and use one for the BT MAC. Byte-parity Python<->Java; new OUI-grounded test.

## [0.22.4] - 2026-08-02

### Fixed
- **Kernel version base is the SoC's real Linux kernel, not a random pick.** `kernel_version` drew the base
  uniformly from 4.9..5.15, so an Android-11 Snapdragon 855 profile could claim a 5.15 kernel — impossible
  for that silicon/OS. Keyed the base on the SoC (device-proven: a real Pixel 4 / SD855 reads 4.14; sourced
  per SoC from NIST CAVP + Sony SODP + AOSP redbull — SD765G/730G 4.19, SD888/Exynos2100 5.4, ...). The old
  base draw is kept (discarded) so the -androidN-tag clamp and git-hash suffix stay byte-parity-identical.
  New SoC-grounded test.

## [0.22.3] - 2026-08-02

### Fixed
- **Screen resolution/density is the model's real spec, not a random pool pick.** 7 US-pool models (incl. the
  live Galaxy S21+, LG G8, moto g pro) had a `build_device` slot absent from the known-screen table, so they
  fell to a hash-picked pool value — a Galaxy S21+ generated as 720x1520 (a budget-phone screen), a hard
  model tell. Added their real specs (S21+ 1080x2400@394, LG G8 1440x3120@564, moto g pro 1080x2300@399, ...)
  and made the lookup longest-prefix so a suffixed device (`sofiap_sprout` -> `sofiap`) resolves. Byte-parity
  Python<->Java; new model-grounded test.
- **Storage capacity is the model's real SKU, not a RAM-tier-random 32/64/256 GB.** All current-pool models
  are 128 GB; the generator could pair any of 32/64/256 with them. Added a per-model base-storage table
  (longest-prefix, byte-parity); the reported capacity still carries the realistic format reserve. New test.

### Notes
- A fresh coherence audit (codex) surfaced further gaps to work down next: GL_VERSION/extension coherence,
  kernel-version-by-SoC, model-specific IMEI TAC, and hardware/factory MAC OUI. Logged in docs/IDEAS.md.

## [0.22.2] - 2026-08-02

### Changed
- **Hardware-anchor identifiers are locked ON.** Widevine `media_drm_id` (+ its security level) and the build
  `serial` can no longer be toggled off. Turning one off re-opened the intermittent-leak failure mode: a
  device-intelligence SDK reads the STABLE hardware id, which survives an id rotation and re-links every
  identity (the non-deterministic-ban shape traced in ANTI-FINGERPRINT-STRATEGY 2026-07-29). `Toggles`
  ignores the pref for these keys and the identity list renders them as a locked, always-on row.

## [0.22.1] - 2026-08-02

### Fixed
- **Battery design capacity is the model's real value, not a hash bucket.** `battery_uah_for` derived the
  capacity by hashing the codename into a plausible range, so a moto g pro (real 5000mAh) or a Pixel 5a
  (4680mAh) reported an arbitrary in-range number a per-model battery DB would flag. Added a per-model mAh
  table (longest-prefix on codename, byte-parity Python↔Java) pinning each pool model to its real retail
  capacity; unmapped codenames still fall back to the stable hash. New model-grounded test. Codename
  lowercasing for the model lookups is `Locale.ROOT` so a non-ASCII device locale can't break Python↔Java
  parity (Turkish `I`).

### Changed
- **Tightened three multi-sentence UI strings** to one short line each (guided-setup intro, the post-setup
  reboot dialog, and the "Reset Google identity" description) — the terse-copy rule the v0.19.3 pass applied
  elsewhere, closing a pre-existing gauntlet nit.

## [0.22.0] - 2026-08-02

### Fixed
- **CPU /proc/cpuinfo now reports the REAL silicon each SoC ships — the emulator / "device or software isn't
  supported" tell.** Every Snapdragon Kryo chip emitted generic ARM Cortex part ids, or the wrong core: SD855
  claimed Cortex-A77 `0xd0d` when a real SD855 is Cortex-A76-class Kryo 485. Verified against the REAL
  connected Pixel 4a, whose cpuinfo reports Qualcomm implementer `0x51`, part `0x804` (Kryo 4xx Gold) + `0x805`
  (Silver) — NOT ARM `0x41`. Corrected `SOC_SPECS` per Kryo generation: msmnile/sdm855/lito/sm7150/sm6150
  (Kryo 4xx) to `0x51:0x804/0x805`; sdm670 (Kryo 360 = 3xx) to `0x51:0x802/0x803`; **kona/SD865 (Kryo 585) to
  the MIXED form its kernel actually reports — gold as ARM Cortex-A77 `0x41:0xd0d`, silver as Qualcomm
  `0x51:0x805`**; lahaina/exynos2100 to Cortex-A78 `0xd41`; Tensor/exynos990 to A76 `0xd0b`; exynos9820/25 to
  A75 `0xd0a` — all sourced from pytorch/cpuinfo + the kernel `cputype.h`. Regenerated `data/hardware.json`.
  Grounded by a new authoritative-MIDR test so no impossible core can be generated again.
- **Never claim an OS newer than the real host.** The device pool had a floor (Android 11) but no ceiling, so
  ~43% of generated profiles picked an Android-12 device (e.g. the S22 `SM-S901U` added in 0.21.0) on the
  Android-11 fleet host — a self-contradiction (`ro.build.version.sdk` leaks the real host SDK) that tripped
  the OS kill-switch. Added `MAX_ANDROID_MAJOR` (mirrored Python↔Java, byte-parity) so selection stays at or
  below the host.
- **Sensor list no longer reads as an emulator.** The native composite-sensor derivation matched sensor names
  case-sensitively, so Pixel-family profiles (lowercase "accelerometer") derived ZERO composite sensors and
  shipped ~6 total where a real phone exposes ~30-40. Made the match case-insensitive and added the standard
  AOSP gesture/virtual sensors. The Java `getSensorList` hook also truncated the real sensor list to the
  profile's ~5-7 rows; it now relabels the physical sensors and PASSES THROUGH the rest, and sets `mType` so
  `getType()` agrees with the relabeled name.
- **RAM/storage is keyed on the MODEL, not just the SoC.** One SoC serves many SKUs, so ~72% of profiles
  claimed a RAM size the specific model never shipped (a Pixel 5 as 4GB; an S22 flagship as 3.8GB because
  `taro`/`sdm670` were missing from the SoC map and fell to a 3/4/6GB default). Added a per-model RAM table
  (longest-prefix on codename, byte-parity Python↔Java) pinning each real US model to its true SKU, plus the
  missing SoCs. New model-grounded + fail-closed missing-SoC tests replace the old self-referential RAM test.
- **Baseband is keyed on the SoC, not drawn at random.** `Build.getRadioVersion()` picked a modem prefix
  uniformly at random, so ~5/6 of profiles reported a baseband that contradicts the claimed silicon (a
  Pixel 6 reporting the SD855 modem). Each SoC now maps to its one real modem family; the old selection draw
  is kept (discarded) so downstream fields stay byte-identical.
- **ARM-GPU (Mali/Tensor) profiles hide the Adreno kgsl node.** `/sys/class/kgsl/kgsl-3d0/gpu_model` leaked
  the host's real Adreno number under a Mali `GL_RENDERER`, and a Mali device having a kgsl node at all is
  incoherent. An ARM-GPU profile now makes the whole `/sys/class/kgsl` tree read `ENOENT`, like real Mali
  hardware. (Latent in today's all-Adreno US pool, closed at the root.)
- **Dropped decommissioned Sprint and a filler Motorola TAC.** Sprint's network shut down in 2022 (T-Mobile
  merger), so a live Sprint SIM in 2026 is a temporal tell; and `35123456` was sequential filler, not a real
  GSMA TAC. Both removed from Python and Java in lockstep (byte-parity preserved).
- **`com.specter`/`.lite`/`.probe` are hidden from every app, not just scoped callers.** Their presence
  reveals the module regardless of who queries the package list, so a non-scoped fingerprinter could
  enumerate them. Added the missing test that `com.specter.lite` is treated as sensitive.
- **Budget Samsung devices no longer claim a barometer + grip sensor** (flagship/mid-only parts), tiered the
  same way cameras already are.

## [0.21.0] - 2026-08-01

### Added
- **US device pool expanded 7 -> 11, and Samsung is no longer absent from it.** Every Samsung row already in
  `data/devices.json` was a Europe/Korea F/N/B variant, which `_is_us_model` correctly rejects (a US carrier
  paired with an international model is itself a coherence tell), so a US profile could only ever be a Pixel,
  an LG G8 or a Moto G Pro. Added four real US-carrier Samsung phones — Galaxy S21 (`SM-G991U`), S21+
  (`SM-G996U`), S21 Ultra (`SM-G998U`) and S22 (`SM-S901U`) — taking the pool to google 5 / samsung 4 /
  lge 1 / motorola 1. Android-11 devices went 5 -> 6, which is what the SDK-match rule actually rotates
  through on the A11 fleet.
- Every field of the new rows (product, codename, release, build ID, incremental, security patch) is verbatim
  from a real dumped `build.prop`. Nothing was interpolated: the research turned up 245 verified US Samsung
  builds, but only four came with a real security-patch date, and the PDA date-code rule that would have
  filled the rest decoded just two of three known-good samples — so the other nine were left out rather than
  shipped with an invented field. See `docs/DECISIONS.md`.
- Snapdragon 8 Gen 1 (`taro`) and 8 Gen 2 (`kalama`) SoC profiles — GPU renderer, CPU part layout, per-core
  capacity/frequency/cache vectors — in the hardware dataset, the SoC topology, and the Java mirror.
- Real launch APIs for the US Samsung models in both generators, so `ro.product.first_api_level` is the
  device's actual launch API rather than defaulting to the running SDK (S21 launched on Android 11 and runs
  12; first_api 30 vs sdk 31, as on a real handset).

### Fixed
- **Six devices silently reverted to the wrong SoC on every hardware-dataset regeneration.** An earlier audit
  hand-corrected `data/hardware.json` (Pixel 4a is SD730G/`sm7150`, not the `sm6150` default; Pixel 3a/3a XL
  are `sdm670`; Moto G 5G / One 5G are `lito`; Galaxy A71 is `sm7150`) but never updated `CODENAME_SOC` in
  the generator that produces the file — so regenerating undid all six, exactly the "sunfish bug" that
  `test_known_device_socs` pins. Fixed at the source; the generated file and the pinned facts now agree.
  `data/hardware.json` must never be hand-edited.
- **`/proc/cpuinfo` and `cpu_capacity` disagreed about which core is which, on every device.** A real
  big.LITTLE Android phone enumerates little cores first — CPU0 is the efficiency core, the last CPU is the
  prime core — which is how the SoC topology encodes capacity. The generator emitted CPU parts big-first, so
  a profile's `/proc/cpuinfo` said CPU0 was the Cortex-X while its `cpu_capacity` said CPU0 was the little
  core: one device asserting two contradictory things about the same core, to anything that reads both.
  Pre-existing for all SoCs; now emitted little-first, with a coherence test pinning the agreement.

## [0.20.0] - 2026-07-31

### Changed
- **Live trace now tells the story "checked → faked → the app still works".** The screen used to headline
  "20 spoofed / 256 real / 124 unknown", which read as "the app is broken" — but an audit of a real Cash App
  run showed the 256 "real" reads were ~99% non-identifying noise (237 font-file stats, library loads, the
  app's own process bookkeeping). Coverage now classifies each read as **faked / leaked / not-checked /
  harmless**: the headline counts only what bears on the spoof, harmless reads are counted but not listed,
  and the list is grouped by what a read MEANS rather than by which syscall fetched it. The verdict line
  never claims a clean sweep while reads remain unclassified.
- **New `LEAK` state surfaces identifiers that SHOULD be faked but aren't** (build date/user, boot device,
  expected baseband/bootloader, SoC device-tree nodes, block-device identity, kernel osrelease) — these are
  the only alarm the screen raises. Real leak detection is unchanged; only non-identifying noise was
  reclassified.
- Exported coverage report follows the same grouping and states.
- **Per-thread `/proc` reads collapse into one counted row.** A measured Cash App run touched 69 distinct
  thread ids; as one row each they exhausted the 400-row list cap and pushed real signals off the screen.
  The pid is now shown as `/proc/<pid>/…` with the hit count carrying the volume — app enumeration (reading
  ANOTHER process's `cmdline`) is still surfaced, just not once per transient id.
- **GPU string queries now count as the wins they are.** `glGetString`/`glGetStringi` reads of GL_VENDOR,
  GL_RENDERER, GL_VERSION and GL_EXTENSIONS were reported as unclassified even though the native layer
  rewrites all four — one measured run had ~400 of them. They now read as faked, and the per-index
  extension walk collapses to a single counted row instead of ~100 near-identical ones.
- Measured end to end on a fresh capture of a real Cash App launch (Pixel 4a): **18 faked · 0 leaked ·
  22 unchecked · 12,160 reads**, no cap hit, where the same screen previously read "20 spoofed / 256 real /
  124 unknown". One of the remaining unknowns is a genuine find: Cash App reads `persist.vmos.root.enable`,
  a virtualization/root probe — exactly what this screen exists to surface.
- **No conditional prop is ever claimed as faked.** `ro.boot.warranty_bit` / `ro.warranty_bit` were listed
  as covered, but the hook only sets them for Samsung profiles — so every Google/LG/Motorola profile would
  have shown a false "faked" while the app read the real value. They are UNKNOWN now, and the legacy
  (non-indexed) GL_EXTENSIONS read is likewise unclaimed because it falls back to the real list when the
  GL hooks don't both land.
- **One Specter folder.** Specter Lite harvests now export to `Download/Specter` like every other Specter
  export instead of creating a second `Download/Specter-exports` folder. The importer still scans the old
  path, so files exported before this release keep importing.

### Fixed
- **Export button lag on the live trace.** The report was built on the UI thread at tap time, and the 2s
  refresh loop's own `su -c tail` competed with the export's root shell. The report is now built on the
  worker, the poll pauses for the duration, the button shows "Exporting…" immediately, and double-taps are
  ignored. The poll no longer restarts on a screen the user has left.
- **"Remove from targets" now looks and behaves like a control.** It was a bare red text label with
  hand-patched padding sitting among real buttons, and it removed the target with no confirmation. It is
  now a full-width destructive button that confirms first, naming the app and stating that saved logins and
  profiles are kept.
- **"Clear" on the live trace confirms before discarding the capture** and is styled as destructive — it
  previously looked identical to Refresh, one tap from throwing away the session's reads.
- Live-trace controls use the Theme type/spacing/radius tokens with ripples and 44dp touch targets (they
  hardcoded 13sp/6dp flat rectangles); the Live/Paused pill is now reachable by screen reader and describes
  the action it performs.

## [0.19.5] - 2026-07-31

### Fixed
- **Coherence: never claim an Android version older than the real host (Cash App failure fix).** Generated
  profiles no longer claim a device older than Android 11, closing the likely cause of Cash App's
  "unavailable to you at this time" on its newer (5.62) build — the app was seeing a too-old claimed SDK.
- **OS-version coherence via a per-apply policy flag (`os_version_spoof_enabled`).**
  `ro.build.version.sdk` / `ro.product.first_api_level` can only be spoofed via a DEFERRED native map
  (spoofing them at process init SIGSEGVs the zygote), so during a brief startup window the native path
  returns the REAL host value — a claimed-vs-host mismatch there is a contradiction. Fix: the OS-version
  family (SDK_INT / ro.build.version.sdk / first_api / RELEASE) is only spoofed when the profile's claimed
  SDK EXACTLY matches the host; otherwise it reports the real host. Enforced at the apply boundary via one
  flag both the native layer and the Java hooks read, so generated / restored / imported / edited profiles
  all obey it and the two layers can never disagree. `ro.product.first_api_level` is pinned to the real
  host value when spoofing so it stays coherent regardless of the claimed device's launch API. Verified
  on-device (Pixel 4a): SDK-30 profiles on the SDK-30 host stamp the flag on; model rotation preserved.

### Changed
- `MIN_ANDROID_MAJOR` raised 9 -> 11 (Python + Java, byte-parity preserved). No profile ever claims a
  pre-Android-11 device again.

## [0.19.4] - 2026-07-31

### Changed
- **Status/Settings polish pass.** Settings now splits into distinct Setup / Status / Protections /
  Diagnostics / Advanced sections (was one clunky combined card); the first-run banner no longer shows on
  the Settings tab itself. Every description in Settings and the status screen is now one short line or a
  bulleted list — no multi-sentence copy anywhere.
- **Network card redesign.** Dropped the emoji glyphs (mismatched sizes were misaligning city vs timezone)
  and the misleading "No VPN" claim — the card now leads with the public IP/geo (the real signal), states
  only a VPN *transport* detection (never implies knowledge of an upstream VPN or a plain proxy it can't
  see), and spells out that detection boundary in a footer line.
- **Widevine L3 defaults ON for new installs** (max protection by default — fleet phones don't watch HD
  Netflix/Prime). An install that predates this default gets its real state seeded once, checked live
  against the on-device Magisk module (not inferred from an unrelated setup flag). Setup can skip the step
  entirely when the user's setting is off.
- **Mock-location hiding is now its own protection (`hide_mock`, default ON)**, independent of `hide_root`.
  The status check no longer warns just because a GPS mocker (e.g. Lockito) is installed or selected — it
  reflects whether Specter's own hook is armed, which is the thing that actually matters.
- **Persistent "Reboot required" banner.** Setup, the native-layer installer, and the Widevine toggle now
  arm a marker (keyed to the real Android boot count, immune to clock changes) whenever they install a
  change that needs a reboot; the banner stays up (surviving a dismissed dialog or app relaunch) until the
  device actually reboots, then auto-clears.

### Fixed
- **Identity/applied-state lost on relaunch.** `MainActivity` had no `launchMode`, so every launcher
  relaunch — even with the app still resident in Recents, no process death involved — pushed a brand-new
  Activity instance on top instead of resuming the existing one; `onCreate()` then unconditionally
  regenerated a new identity and discarded the applied state. Fixed with `launchMode="singleTop"` (the
  common-case fix) plus a durable current-identity/applied-state persist+restore in SharedPreferences (for
  genuine process death, which `singleTop` doesn't cover) so `onCreate()` only regenerates when nothing can
  be restored. Verified on-device: state survives both a launcher relaunch and `adb shell am kill`.
- Toast/button copy mismatch: the "already applied" toast said "tap Randomize" while the actual button
  reads "Generate another identity" — now consistent.
- `reboot_pending_since` could throw `ClassCastException` on a device that armed the marker under the
  prior (pre-gauntlet) build, which stored it as a `Long` instead of the current `Integer` boot count.
- `save_on_apply` still defaulted to `false` at the actual apply-time read site (only the checkbox's
  initial rendering had been updated to default checked) — a fresh install showed the box checked but Apply
  silently never saved to the vault.
- Protection-status screen's "Target apps" hook-attestation rows are now visually set apart (a raised card
  background + each row's real app icon) from the device/config-level checks around them.
- Network-status pill now reads "VPN/proxy transport detected/not detected" (was "VPN transport…") —
  matches the actual signal (a VpnService-based transport, which is how a proxy app like SuperProxy routes).

### Added
- Copy-guard check (`xposed-module/check_copy_guard.py`, wired into `run-jvm-tests.sh`) scans every
  `Protections.ALL` description for the one-line/no-paragraph rule.

## [0.19.0] â 2026-07-30

### Added
- **Protection-status: live Network card (public IP + geolocation).** The status screen now fetches the
  current public (proxy exit) IP and its geolocation off the UI thread and shows it as a rich card â IP, ISP,
  city/region/country, the IPâs timezone, and a Proxy/VPN-vs-Direct routing pill. New INTERNET +
  ACCESS_NETWORK_STATE permissions (used only by this screen).
- **Timezone follows the proxy IP, not the phone number.** A new âTimezone vs IPâ check compares each applied
  profileâs timezone against the exit IPâs zone; a one-tap fix (and auto-alignment on Apply) rewrites the
  profile timezone to match the IP â killing detectme.proâs âTimezone Mismatchâ flag. GATED on being routed
  through a VPN/proxy (NetworkCapabilities.TRANSPORT_VPN): it will NEVER align to the phoneâs own home/carrier
  IP. Identity fields are untouched â only the timezone key changes. Verified on-device: exit IP
  67.9.12.215 (Birmingham AL) â routing pill âProxy/VPNâ, timezone matches America/Chicago.
- **WebRTC IP-leak fix (âFix WebRTC leakâ protection, default on).** WebRTC is NOT blocked (a blocked WebRTC is
  itself a fraud flag) â instead a JS ICE-candidate filter is injected into a scoped appâs WebViews that drops
  only the real local/private/mDNS (RFC1918, 169.254, fe80::, .local) candidates while the proxyâs public
  candidate passes through. WebRTC keeps working and reports the proxy IP, not the deviceâs. WebView-based
  targets only â native Chrome isnât hookable from a scoped module.

### Notes
- detectme.proâs network-layer flags (WSS/TCP latency, HTTP/3 QUIC reachability, DNS resolver) are the
  PROXYâs responsibility, not a device-config moduleâs â use a residential proxy that forwards UDP/QUIC and a
  home/ISP DNS resolver. Datacenter-IP reputation is likewise an IP-selection concern. Specterâs job is
  device coherence + aligning timezone/WebRTC/VPN-visibility to the proxy.

## [0.18.5] — 2026-07-30

### Added
- **Native VPN masking via getifaddrs.** The Java hide_vpn hook covers Android-API VPN detection; this adds
  the native path. getifaddrs() (netlink-backed interface enumeration) is what an NDK fingerprinter calls
  directly, bypassing the Java hook — the Zygisk layer now inline-hooks it and unlinks+frees tun/ppp/wg
  entries. (The earlier /proc/net/dev idea was rejected — SELinux-denies those files to apps — but getifaddrs
  IS the reachable native path.) Verified on-device: a direct C getifaddrs call in a scoped app finds no tunnel.

### Fixed
- **Closed a GPU-vendor coherence leak: ro.hardware.egl / ro.hardware.vulkan are now
  spoofed.** These read the REAL host GPU driver (adreno on a Qualcomm Pixel) — so a Samsung/Exynos profile
  whose GL renderer is Mali-G76 still reported ro.hardware.egl=adreno, a direct GPU contradiction a
  fingerprinter catches by comparing the renderer against these props. A new derived profile field gpu_hw
  (adreno/mali/powervr, from the renderer string, byte-parity Java↔Python) drives egl + vulkan via PROP_ALIASES
  in BOTH the Java hook and the native Zygisk layer. Verified on the 4a: an exynos9825 profile reads egl + vulkan = "mali" (was "adreno"). gralloc is left REAL — real devices report a
  gralloc VENDOR (qcom/gbm), never the GPU family, so forcing it would be an impossible-value tell (gauntlet).
## [0.18.4] — 2026-07-30

### Fixed
- **Closed the last CPU-fingerprint leak: the full per-core CACHE tree is now spoofed.** Cash App reads
  /sys/.../cpu<N>/cache/index<K>/{size,level,shared_cpu_list} — the L1/L2/L3 cache signature, which differs
  per SoC (e.g. the SD855 prime core has a distinctive 512K L2 vs 256K on the gold cores; SD845 is a uniform
  256K). It leaked the real device's cache while a profile claimed another SoC. The native layer now redirects
  the WHOLE cache tree coherently (size + level + shared_cpu_list together — spoofing only sharing while
  size/level stayed real would fabricate an inconsistent topology) from a new per-SoC cache dataset (L1i/L1d/
  per-tier L2/shared L3 for all 29 SoCs, byte-parity Java↔Python). Verified on the 4a: an SD855 profile on the
  sm7150 host reads 64K L1i, 128K little-L2, 512K prime-L2, 2048K L3 — the exact SD855 1+3+4 cache signature.
  With cpufreq + topology (v0.18.3) + cache (this), the ENTIRE CPU signature now matches the claimed SoC.
## [0.18.3] — 2026-07-30

### Fixed
- **CLOSED the CPU-coherence leak that flagged an account.** A live trace of Cash App showed it reads every
  core's cpufreq (cpuinfo_max/min_freq) AND topology (physical_package_id, core_siblings_list,
  cluster_cpus_list) — none of which Specter spoofed. So a profile claiming an LG G7 (Snapdragon 845, a 4+4
  two-cluster layout) still leaked the REAL Pixel 4's Snapdragon 855 signature (1+3+4 three-cluster:
  1785600/2419200/2841600 kHz, packages 0/1/2 with sibling ranges 0-3/4-6/7). A fingerprinter reading those
  sees an SD855 masquerading as an SD845 — the coherence tell. Now the native Zygisk layer redirects, per
  core: cpuinfo_{max,min}_freq + scaling_{max,min}_freq (from new per-SoC cpu_max_freq/cpu_min_freq profile
  fields, byte-parity Java↔Python) and the topology cluster grouping (derived from the cpu_capacity vector),
  plus the top-level online/possible/kernel_max. All 29 SoCs carry coherent stock frequency tables.
- **Native-layer auto-sync now compares the .so HASH, not just the version.** A same-version rebuild changed
  the .so bytes but not module.prop's version, so the version-gated sync left a STALE native layer on device
  (on-device behavior silently didn't match the latest build). status() now md5-compares the on-disk .so vs
  the bundled asset. /proc/modules spoof made vendor-neutral (no Qualcomm-specific names that would reveal
  incoherence on an Exynos/Tensor profile); write_spoof loops over EINTR/short writes.

### Added
- **Hide VPN & proxy (hide_vpn protection, default ON).** Masks every in-process Java surface an SDK uses to
  detect a tunnel: NetworkCapabilities.hasTransport(TRANSPORT_VPN)→false, hasCapability(NOT_VPN)→true,
  getTransportTypes() strips VPN; NetworkInterface.getNetworkInterfaces() drops tun/ppp/wg/ipsec/l2tp;
  legacy ConnectivityManager getNetworkInfo(TYPE_VPN)/getAllNetworkInfo() drop the VPN entry; and
  http(s)/socks proxyHost/Port System properties return null. So a scoped app reads the device as not behind
  a VPN/proxy even when routed through one.
## [0.18.2] — 2026-07-30

### Fixed
- **Closed a SoC-name coherence leak: ro.chipname + ro.mediatek.platform are now spoofed.** A live trace of
  Cash App showed it reads ro.chipname and ro.mediatek.platform, which Specter did NOT alias — so while
  ro.board.platform / ro.hardware.chipname / ro.soc.model all read the profile's SoC, ro.chipname leaked the
  REAL SoC codename (an internal contradiction that is itself a fingerprint). Both now alias to soc_platform,
  in BOTH the Java hook (HookEntry.PROP_ALIASES) and the native Zygisk layer (spoof_logic.h), so the whole
  SoC-name set is coherent on any host device (empty on the Pixel 4, but Specter targets any Android).
- **Live-trace: input-tuning props read REAL, not "unknown".** ro.input.* / persist.input.* (touch/velocity
  tuning, not device-identifying) are now classified REAL so the trace's read tally isn't muddied by them.
## [0.18.1] — 2026-07-30

### Changed
- **"Save AppData" now prompts for a name** (parity with the fingerprint "Save to vault" flow, which
  always asked). After the capture succeeds the saved login is named via a dialog prefilled with the app
  label; the date/time is still prepended to the stored label (and shown as the row's subtitle), so the
  name is just the human tag. Blank uses the date/time alone. Backing out leaves the capture staged and
  unsaved (re-tap to name it) rather than silently auto-naming.
- **Fingerprint↔AppData linking is more robust.** When saving AppData, the fingerprint the app is running
  under is matched to an already-saved fingerprint by android_id and REUSED (never duplicated) — now with
  an activeVaultLabel fast-path so a just-applied identity links even when a fresh vault scan would miss. The
  AppData entry records that fingerprint label, so the Saved tab shows them joined.
## [0.18.0] — 2026-07-30

### Added
- **Guided first-run setup — "Set up everything" (Settings → Set up everything).** One tap installs every
  layer a virgin phone needs and reboots, so an end user never hand-installs 5+ pieces: the Zygisk native
  layer, the OTA block (keeps the device on its current OS version), and Widevine L3 (software DRM,
  device-wide) — plus it **writes the target apps into Specter's LSPosed scope from inside the app**, the
  one step that used to require the PC (`scripts/scope_probe.py`). Shows a live per-step checklist, is
  idempotent (already-done steps just report so), and ends at the reboot every layer needs. A first-run
  banner points new users at it until it's been run once; the Protection status screen verifies it worked.
- **In-app LSPosed scope writer (`LspScope`).** Adds packages to Specter's own module scope via the same
  root SQLite-copy route the Protection status screen already uses to read the config DB — `INSERT OR
  IGNORE` scoped to Specter's `mid` only (never another module's scope), reboot to apply.
- **OTA-block Magisk module (`OtaBlock`).** Bundles the proven 4-layer OTA block (hosts blackhole of the
  OTA CDN + framework auto-update off + staged-payload purge + GMS update components disabled) as one
  removable Magisk module, installed atomically (staging dir → rename, with .bak rollback) like the
  Widevine L3 module. Reversible: removing it restores updates on reboot.

## [0.17.8] — 2026-07-30

### Added
- **Protection status screen (Settings → Check protection status).** Self-verifies every layer so a
  misconfiguration shows as a red row instead of a silent false sense of security: root granted, LSPosed
  module enabled, the system-framework app-hiding gate loaded, the Zygisk native layer installed+current,
  and per-target-app scoped + identity-applied. Green/amber/red with a one-tap Fix for the actionable ones
  and inline guidance for the rest — no pop-ups. The LSPosed checks are STRUCTURAL (a read-only SQLite
  query of modules_config.db copied into the app dir to dodge the /data/local/tmp SELinux denial), so
  “enabled” and scope membership are actually verified, not byte-grepped.

### Fixed
- **US profiles now pick US-market Samsung models.** The generator filtered devices by BRAND only, so a
  US profile could get an international Samsung (e.g. SM-A525F) paired with a US carrier — an internal
  coherence tell. Samsung models are now constrained to US suffixes (U/U1/V/A/T/P, W=Canada); other US
  brands (Google/Motorola/LGE) are unaffected. Byte-parity mirrored in Java (Profile.isUsModel).
- **The system_server app-hiding gate now installs regardless of LSPosed’s framework-scope key.** Some
  LSPosed builds deliver the framework process as “android”, others as “system”; PmsHook now triggers on
  BOTH, and both are in the scope suggestion.

## [0.17.7] — 2026-07-29

### Added
- **Fuller mock-location hiding (HideMyMock parity).** On top of forcing Location.isFromMockProvider()/
  isMock() to false, we now also return the legacy Settings.Secure/System “mock_location” flag as 0 (getInt)
  / null (getString), so a detector probing the old ALLOW_MOCK_LOCATION path sees a pristine consumer phone.
- **Broader app-hiding — closed the enumeration bypasses.** App-list hiding now also filters intent
  resolution (queryIntentActivities/Services/BroadcastReceivers/ContentProviders + resolveActivity/Service),
  UID→name lookups (getPackagesForUid/getNameForUid), and getInstallSourceInfo — not just the installed-
  package list and direct getPackageInfo lookups. An SDK can no longer infer a hidden app is present by
  resolving a known intent or walking UIDs. Keyed off the same category denylist (root/hook/GPS/proxy tells),
  so it works for any user, not a fixed app set.
- **System-server app-hiding gate closes the raw-binder bypass.** A new PackageManagerService hook
  (AppsFilter.shouldFilterApplication, API 30+) hides sensitive packages from our scoped targets at the
  framework visibility gate — so an SDK calling IPackageManager directly (skipping the app-side hooks)
  is also filtered. Requires enabling “System Framework” scope in LSPosed. Safe by design: fail-open kill
  switch on any error, never filters system/privileged callers or critical packages, derives the caller
  from the hook arg (no re-entrant PMS call — no deadlock), and only hides from OUR scoped apps.

## [0.17.6] — 2026-07-29

### Changed
- **One consistent back control everywhere.** Every sub-screen (Target Apps, Live trace, Import, the
  vault app drill-down) now uses the same gold left-chevron in a proper 44dp touch target, via a shared
  {@code Nav.backRow} — replacing the old mix of a “‹ Back” pill, a tiny “←”, and rotated chevrons.
- **The live-trace record indicator moved onto the live toggle itself:** it now reads “● Live” with a
  flashing-red dot while capturing (“Paused” + steady dim dot when stopped), instead of a stray dot by
  the title.
- **The live-trace coverage export now lands in Download/Specter** — the same folder as every other
  Specter export — as does the per-app monitor read-capture archive.
- **Clearer Anti-fingerprinting header:** “Core spoofing — always on” became “Device identity — always
  applied” with copy that says the model/build/hardware/sensors always match and the toggles below add
  extra protections on top (the old wording read like a blanket “everything is on” claim).
- **The Identity status dot got a subtle same-hue halo ring** so it reads as a polished indicator, not a
  flat circle (state colours unchanged: Ready / Applying / Applied).

## [0.17.5] — 2026-07-29

### Changed
- **Every Fingerprint row now shows an app icon.** A Fingerprint is always saved against at least one app
  (“Save current to vault” requires an applied target; an AppData capture saves it against that app), so
  the row’s icon cluster is now the UNION of linked-AppData apps and applied-to targets — icons first for
  apps with real saved AppData. Multiple apps render as an OVERLAPPING avatar-stack (up to 4, then “+N”),
  so 3/4/5 apps stay tight and never break the row width. The unlinked tile now only shows for a
  legacy/imported entry that carries no apps at all.
- **Shorter captions:** “Import a Fingerprint or AppData.” and “Fingerprint = saved identity. AppData =
  saved login.”

## [0.17.4] — 2026-07-29

### Added
- **Fingerprint rows show the app(s) they’re tied to.** A saved Fingerprint is device-level, but the
  AppData captured against it is per-app — so each Fingerprint row now shows the icon(s) of the app(s)
  whose saved AppData links to it (up to 3, then “+N”), with the app name(s) on the tie-line. A bare
  Fingerprint saved via “Save current to vault” (no linked AppData) shows a neutral unlinked tile.

### Changed
- **Tighter caption copy** so it stops wrapping a stray word onto a second line: the Import hint is now
  “Import a Fingerprint or AppData someone shared.” and the Saved legend “A Fingerprint is a saved
  device. AppData is a saved app login.”

## [0.17.3] — 2026-07-29

### Changed
- **Consistent AppData vocabulary on the Identity tab.** A target app’s per-app actions now read
  **Save AppData / Restore AppData** (were “Save login / Restore login”), matching the Vault.
- **Read logging vs Monitor reads, made one mental model.** Settings’ diagnostics toggle is renamed
  **Read logging** (the global on/off); tapping **Monitor reads** on a target app now flips that same
  switch on automatically, so the two controls can never disagree. Each explains the other in-copy.
- **Live trace names what it is watching.** The trace screen shows **Watching <app>** — the app you just
  monitored, or the full scoped-target set (with a note that their reads are mixed) when opened globally.
- **Live trace stats are now KPI tiles** (signals / spoofed / real / reads) instead of a run-on line, and
  the **Live** indicator is a **flashing-red dot** while capturing (steady-dim when paused).

### Fixed
- **Restore/Save errors are now clean human sentences** (e.g. “No saved AppData to restore for Dasher
  yet.”) using the app label, not a raw “exited 3: no staged session for com.…” shell error, and no
  longer double-echo the same failure as both a red inline message and a toast.
- **Menu/row-expand flicker gone.** Removing the whole-tree LayoutTransition stops the flash that fired
  every time a dropdown or an inline row-actions menu opened (each rebuilt the tab).

## [0.17.2] — 2026-07-29

### Changed
- **The status line no longer sits pinned as raw text under the app bar.** It's now a subtle rounded banner
  (inset like a card) that shows the last operation briefly and **auto-dismisses after ~6s** — the real
  feedback is the toast + the hero status pill, so this is just a quiet, transient echo.

## [0.17.1] — 2026-07-29

### Added
- **Export / import a Fingerprint and its AppData together, as one file.** All exports now go to an
  auto-created `Download/Specter` folder. From a Fingerprint or an AppData row you can export it alone or
  as a **combined bundle** (`specter-combo-*.tar`) carrying both. A dedicated, in-app **Import screen**
  (proper back button + styled type cards, not an OS pop-up) lists everything importable and handles a
  Fingerprint (`.json`), an AppData bundle (`.tar`), or a combined bundle; a combined import relinks the
  two halves on the destination device. Root tar extraction is TOCTOU-safe (validates + extracts an
  app-owned copy) and reuses the existing regular-file + exact-member guards.

### Changed
- **Vault polish + consistent vocabulary.** Standardized on two terms, explained once on the Saved page:
  a **Fingerprint** is a saved device config; **AppData** is a saved app login. “Device profiles” /
  “login” wording is gone from the UI.
  - Facet chips are now **All / Fingerprints / AppData / Both** (Both = entries that pair a fingerprint with
    linked AppData). Selecting a chip no longer shifts its size (fill-only, no contrasting border).
  - Each saved row’s overflow is now an **inline chevron-expand** strip (Rename · Export · Delete) inside
    the card, replacing the raw pop-up list. Restore stays the dominant action.
  - **Every app row has an icon**: uninstalled apps get a generated monogram tile + a clean title-cased name
    (e.g. “Ubercab Driver”) instead of a raw “com.ubercab.driver” string.
- **Identity hero tightened.** “Save to vault on apply” is a compact one-line checkbox (was a tall titled
  pane). “Generate another identity” is an outlined secondary button paired under Apply (was floating grey
  text). The Target-apps card now shows a clear divider + “Selected” caption between the Change control and
  the app list.
- **Native layer updates silently.** An out-of-date native layer is now re-written on launch automatically
  (no reboot needed for the file), so the “Native layer update available” banner no longer nags on every
  launch — it appears only when the layer isn’t installed at all.

## [0.17.0] — 2026-07-29

### Changed
- **Vault reorganized app-first, to scale.** The Saved tab had two flat top-level lists (“Saved logins” +
  “Saved fingerprints”), which broke down at many apps × many saved logins each. It is now a two-level
  drill-down with a persistent type facet (All / Logins / Device profiles) + search:
  - **Top level** lists the apps that have saved logins (icon · name · “N saved logins”), plus a
    **Device profiles** section for fingerprint-only saved identities (date-grouped).
  - **Tap an app** to drill into just its logins — date-grouped, searchable, each with its own
    Restore / Rename / Export / Delete. This is where you pick WHICH saved login to bring back (restoring
    the newest by default is wrong — the newest is usually the currently-live session).
  - **Restore** on a login re-applies its linked device profile too, so the app comes up signed in; a
    login with no linked profile restores on its own. Every saved login stays reachable under its app —
    nothing is hidden or orphaned.
- **“Save to vault on apply” has a home again** on the Identity hero (a toggle), next to a one-tap
  “Save this identity to the vault” that appears once an identity is applied but unsaved. The old checkbox
  had been orphaned in the v0.16.0 redesign and did nothing.

### Removed
- Dead code from the redesign: the pre-redesign `actionBar()` and `targetHeader()` screens (and with them a
  second, unreachable “Save/Restore AppData” control), plus the now-unused `thirdButton`/`fmtDate`/
  `promptRenameAppData` helpers.

## [0.16.0] — 2026-07-29

- **UI polish (round 2)**: bright pastel-yellow accent (#FFD54A) not dim orange; tight corners (no
  over-rounded look); real drawn-icon tinting (icons were rendering white); grouped identifiers AND
  protections each into ONE card with hairline rows (last card-soup surfaces); Identity hero status pill
  (Ready/Applying…/Applied) + disabled busy state; native-layer banner rebuilt (clean card + inline Update);
  per-app actions renamed Save login/Restore login, the remove-target moved into the expanded actions
  (no accidental delete), 44dp touch targets, subtle expand/tab motion, screen-reader descriptions.
### Changed
- **Professional UI redesign** toward a real-product feel (DoorDash/Cash App level), from a dedicated design
  review that called the old UI “card soup” + “developer control panel”:
  - **Bottom navigation** (Identity / Vault / Settings) with drawn line icons, replacing the four gold pill
    tabs and the congested top stack (header + Randomize/Apply bar + checkbox + wipe line + status strip).
    The Location tab is gone (its only real control is governed by Hide-root in Settings; the “not built
    yet” placeholder made the app read as unfinished).
  - **Identity is summary-first**: a hero “Current identity” card (device · carrier, applied state, one
    primary “Apply to N apps” + a quiet “Generate another”), then Target apps as ONE group card with plain
    expandable rows, then the full field editor collapsed behind a “Show all fields” disclosure.
  - **Design system**: an 8pt spacing scale, a 5-step type scale, and radius tokens in Theme; a component
    toolkit (one card, hairline rows, quiet section headers, four 48dp button types with ripple, DRAWN
    vector icons replacing 10 emoji glyphs, gold-tinted switches). Card-soup replaced by grouped cards with
    hairline-separated rows; the redundant ON/OFF chips next to switches removed; gold reserved for the
    primary action + active nav instead of decorating every heading.

## [0.15.0] — 2026-07-29

### Changed
- **App-data capture is now app-AGNOSTIC and login-complete.** SessionMigrator used to grab only
  `databases` + `shared_prefs`; it now captures the WHOLE `/data/data/<pkg>` minus a junk deny-list
  (`cache`, `code_cache`, `oat`, `app_textures`, `lib`) and our own `.specter_*` probe files. That carries
  whatever actually holds a login for ANY app — databases (with -wal/-shm), shared_prefs, `files/`,
  `no_backup/`, `app_webview` cookies — without hardcoding per-app dirs. Restore’s security guard changed
  from a two-dir allow-list to an absolute/`..` traversal guard; the safe move-aside/rollback swap now
  covers exactly the archive’s top-level entries.

- **Rebuilt the per-target card as a collapsible section.** Collapsed by default (icon + name + a chevron),
  so a target’s three actions can never overflow the row — the old flat row split “Paste login” in half once
  the “Monitoring…” label widened. Expanded, “Monitor reads” gets its own full-width row and Save/Restore
  AppData share the next as equal halves. A collapsed card with a live monitor still shows a “● Monitoring” hint.
- **Renamed “Copy login” / “Paste login” → “Save AppData” / “Restore AppData”** — clearer, and matches the
  deepened app-data capture (it moves the whole logged-in data, not just a “login”).
- Dropped the emoji from the “wiped before every apply” line and the apply/restore toasts (Apple-clean copy).

### Added
- **Vault now stores logins (AppData), linked to fingerprints.** “Save AppData” on a target snapshots the
  WHOLE logged-in state in one tap: the login tarball AND the fingerprint the app is currently running under
  (read from its live on-device profile, so an app logged in before its identity was ever saved still works).
  If that identity is already a saved fingerprint it links to it, no duplicate. The Saved tab shows a
  “Saved logins” section with an app-filter, each row showing date · size · linked fingerprint. “Restore
  login” re-applies the linked fingerprint AND the login together, then relaunches — the app opens signed in
  on the matching device identity. New AppDataVault store (durable, app-owned; tarball copied out of volatile
  tmp), 22 JVM tests. Verified end-to-end on Dasher: save → wipe → restore → authenticated home.

- **Rename + export/import for vault items.** Saved fingerprints and saved logins can both be renamed
  (keeps the timestamp prefix; renaming a fingerprint relinks its logins so the bundle stays intact).
  Saved logins now export as a portable `specter-login-<label>.tar` bundle (tarball + link metadata) to
  Download and re-import from there — import refuses any bundle entry that isn’t a `.tgz`/`.meta` (root
  extraction guard). Fingerprint export/import already existed; the import picker now handles both kinds.

- **Apple-clean UI pass.** Removed the emoji/broom banner; primary buttons and copy use sentence case
  (Randomize / Apply / Restore); the per-target card is a tidy collapsible section. Saved-tab logins and
  fingerprints read as coherent cards.

### Security
- Hardened app-data restore against a tampered archive (extraction runs as root): refuse symlink/hardlink
  entries (a name-only tar listing hides a symlink target), keep the absolute/.. path guard, swap the whole
  data dir via two atomic renames with a single rollback point (no per-entry window that could strand a
  login), and capture atomically (tar to a temp, verify readable, then rename over the final path).

### Verified
- **A real logged-in DoorDash Dasher account survived a full save → `pm clear` wipe → restore → relaunch**
  — the app came back up on its authenticated home. App-data migration proven on-device.

## [0.14.7] — 2026-07-29

### Added
- **Read captures are archived + auto-saved.** Stopping a monitor now copies the raw capture to
  `/sdcard/Download/specter-reads-<pkg>-<ts>.log` — logcat writes one fixed `diag.log`, so back-to-back
  captures used to overwrite each other. Empty captures write no file.
- **Applying a new identity auto-finalizes an in-progress capture.** APPLY and Restore-saved wipe the target,
  which ends the session being monitored — so the monitor is now stopped and archived BEFORE the wipe
  instead of being lost to it. The flush runs on the wipe thread itself, so it genuinely completes first.
- Stopping a monitor now reports a failed trace-disarm instead of silently claiming the monitor stopped, and
  says so plainly when a session recorded no reads.

## [0.14.6] â 2026-07-29

### Added
- **Per-app âMonitor readsâ toggle** (in-app version of the manual logcat trace). On a target's card, tap
  âMonitor readsâ â it arms trace on that app's live profile + starts the capture; the button shows
  âMonitoringâ¦ (tap to stop)â. Use the app (login/session), then tap Stop â trace disarms, capture stops,
  and the read report opens (what the app read + spoofed/real per signal). 30-min auto-stop safety net. You
  decide the window (start on tap, stop on tap) since a login can take 1 min or 1 hour.
### Changed
- **Renamed the session-migration buttons** âCapture/Restore sessionâ â âCopy login / Paste loginâ â the old
  name collided with read-monitoring; these move a LOGIN between devices, not a trace.

## [0.14.5] â 2026-07-29

### Fixed
- **Hide GPS-spoofer + proxy/tunnel apps from installed-app enumeration.** The `hide_apps` sensitive-package
  list covered root/Xposed/hook tools but NOT fake-GPS apps (Lockito, GPS Joystick, Fake GPS) or proxy/tunnel
  helpers (SuperProxy, tun2socks/tun2tap, shadowsocks/v2ray/clash) or MITM capture tools (HTTP Toolkit) â a
  fraud/KYC SDK that can enumerate (QUERY_ALL_PACKAGES) treats an installed GPS-spoofer as a strong risk
  signal even when the mock flag itself is hidden. Now hidden. Mainstream VPNs (Mullvad, etc.) are
  deliberately KEPT visible â their presence is common/benign and hiding them is itself a tell. Found while
  analyzing a live Cash App application trace (Lockito was installed + unhidden).

## [0.14.4] â 2026-07-28

### Changed
- **Saved-profile UI cleanup.** Saved entries now read like a phone, not a filename: the title is the
  device/name and the subtitle is a readable time (âMon 07/26 Â· 2:53 PMâ) instead of the raw
  â072726-Mon-1453-â¦â label. The Save dialog prefills the device name (blank = date/time) with a hint.
### Fixed
- **Save-name doubling bug.** If the clock rolled to a new minute between opening the Save dialog and
  tapping Save, the prefilled timestamp was mistaken for a custom name and doubled into
  â072726-Mon-1453-072726_Mon_1452___nameâ. The dialog no longer round-trips the timestamp as a name.

## [0.14.3] â 2026-07-28

### Changed
- **Robustness pass â the 4 deferred /codex findings, fixed.**
  - **No-reuse ledger now uses `android.util.AtomicFile`.** The old save did `delete()` then `renameTo()`; a
    rename failure lost the on-disk ledger for the next launch. AtomicFile keeps the previous file as a
    `.bak` until the new one is durable and auto-recovers on read â the ban-critical no-reuse guarantee no
    longer has a lossy window.
  - **Vault import runs off the UI thread** via a new single-read `importOnce()` (was two blocking `su cat`
    calls back-to-back on the main thread â an ANR risk, plus a double-read TOCTOU). One read, worker thread.
  - **Diagnostics âClearâ runs off the UI thread** (was `su â¦ waitFor()` inline on the click).
  - **Background-completion dialogs are lifecycle-guarded** (`!isFinishing() && !isDestroyed()`), so an
    APPLY/import/zygisk-install su task finishing after the user rotated or backed out can no longer throw
    BadTokenException on `.show()`.

## [0.14.2] — 2026-07-28

### Fixed
- **Whole-app hardening pass (from a full /codex review).** Six real defects fixed:
  - **APPLY now re-applies after ANY edit.** The already-applied guard signed only `android_id + targets`,
    so editing a device field, flipping an identifier toggle, or changing a protection gate left the
    signature unchanged — the next APPLY said "Already applied" and pushed nothing. It now hashes the exact
    applied map + sorted targets, so any change re-applies.
  - **Malformed profile can no longer crash a target.** A non-hex `media_drm_id` (e.g. from a hand-edited /
    imported profile) reached `hexToBytes()` inside the MediaDrm hook, throwing an uncaught exception into
    the scoped app. Parsing is now fail-safe (`hexToBytesOrNull`): on bad input the hook leaves the real
    result instead of crashing.
  - **Atomic profile write.** The su write did `cat > final.json`, truncating the live file before stdin
    finished — a killed su / full disk left an empty or partial profile the target then loaded (real-value
    leak). It now writes a `.tmp`, verifies it non-empty, then `mv -f`s it over the final path (atomic
    same-dir rename); on any failure the `.tmp` is dropped and the live file is untouched. Proven on-device.
  - **No concurrent APPLY/RESTORE.** Both `pm clear` + write a profile; two at once could clear/overwrite
    each other's target. A single `opBusy` guard serializes them (a second tap is refused with a message).
  - **Vault reports the truth.** `save()` swallowed all exceptions and always returned a label (UI always
    said "Saved"); it now returns null on write failure and the UI reports it. Delete now uses its real
    success boolean instead of always claiming "Deleted".
  - **su streams drained + process destroyed.** `SuShell` didn't drain stderr or destroy the process — a
    command printing enough to an unread pipe could deadlock su. Both streams are now drained concurrently
    and the process is destroyed in `finally`.

## [0.14.1] — 2026-07-28

### Changed
- **UI declutter — Apple-clean copy.** Cut every multi-line control description down to one short,
  plain-language line (no OkHttp/OEMCrypto/GLES/first_api jargon in the primary text). Shortened the
  native-layer banner, the Auto deep-clean line, the Advanced-root (Widevine/GSF) cards, the Protections
  rows, the Location + Saved cards, the apply/restore toasts, and the confirm dialogs. Every control stays
  fully functional; only the surface copy changed.

## [0.14.0] — 2026-07-28

### Added
- **The app self-installs the Zygisk native layer — no manual Magisk flash.** The APK now bundles the
  native `.so` + module.prop + sepolicy.rule as assets; on launch the app checks whether the
  `specter_zygisk` Magisk module is present AND version-current, and shows an amber banner (“Native layer
  not installed / out of date”) with a one-tap **Install/Update** button when it isn't. Install writes the
  module atomically via su (stage + rename, rollback on failure) and prompts a reboot to activate it. So a
  fresh device or an app update carrying a newer native layer is handled automatically instead of leaving
  the native read-paths silently unhooked. VERIFIED on-device (Pixel 4): removed the module → app detected
  it missing → one-tap install wrote a byte-identical module (perms/owner match the proven flash script) +
  reboot prompt. (Full load-after-reboot via the probe dual-read is the remaining on-device check.)

### Fixed
- **ro.product.first_api_level now reflects the device's LAUNCH API, not the current SDK.** Specter set
  first_api_level == build_sdk, but a device that shipped on an older OS and updated has first_api < sdk —
  first_api==sdk is a subtle coherence tell for an SDK that reads both. Added a per-model launch-API map
  (Build.MODEL → launch API, GSMArena-sourced, Samsung set; careful cases Note8=25, Note9=27, S8=24) and a
  new build_first_api profile field the native deferred prop path serves. Unmapped models fall back to sdk
  (unchanged), and first_api is clamped ≤ sdk. PROVEN on-device (Pixel 4): a Galaxy A50s profile (launched
  Android 9, dataset release 10) now reads ro.product.first_api_level=28 while ro.build.version.sdk=29 — the
  real relationship. Byte-parity (Python+Java), pinned by coherence tests. Launch-OS coverage: 61 models
  across Samsung, Xiaomi/Redmi/POCO, Motorola and OnePlus (GSMArena-sourced; only models whose launch OS <
  dataset release are mapped, the rest correctly default to first_api==sdk).
- **5 devices' SoC/GPU corrected (dataset audit, kernel-DT grounded).** a71naxx (Galaxy A71), bonito/sargo
  (Pixel 3a XL/3a), kiev/nairo (Moto G 5G / One 5G) were all mislabelled to the sm6150/Adreno-612 default.
  Real: a71naxx=sm7150/618, bonito+sargo=sdm670/615 (added sdm670 topology), kiev=lito/619, nairo=lito/620.
  Since “lito” ships MULTIPLE Adrenos (619 vs 620), gpu_model is now derived from the per-model GL renderer
  at generate time (a pure constant, byte-parity-safe both languages) so /sys gpu_model always == the renderer
  Adreno number — the exact /sys-vs-GL coherence a fingerprinter cross-checks. Pinned in test_known_device_socs;
  the dataset gpu-renderer coherence test now understands multi-Adreno SoCs. The renderer-derived gpu_model
  applies to BOTH the fresh-generation and the harvest/import (backfillHardware) paths, so a harvested/cloned
  kiev reads Adreno 619 (not the lito default 620) — pinned by a JVM test. Regex is ASCII [0-9] on both sides
  (byte-parity). Both hardware.json copies updated identically.

### Changed
- **Deep clean is now MANDATORY on every APPLY and RESTORE** (was an opt-in checkbox). Writing an identity
  onto an install that still holds a PRIOR identity's data links the two accounts (the app carries over
  ids/session) — the worst cross-identity leak — so each target's storage + cache is `pm clear`ed before the
  profile is written, unconditionally. A toast confirms the deep-clean each time, and the Identity tab shows
  a fixed “Auto deep-clean” note (the old toggle was removed — it would be a footgun now that it's required).
  If a target's clear FAILS, its apply is SKIPPED (better un-spoofed than a new identity written onto dirty,
  linkable data), the “no carry-over” confirmation only shows when EVERY target was cleaned, and the applied
  signature is recorded only on full success so a partial failure stays retryable.
- **Saved profiles: the most-recent date group starts EXPANDED** (older groups still collapse by default), so
  the latest profiles are visible without a tap; a freshly-saved profile's day auto-opens too.

### Fixed
- **Profile load is now immune to another LSPosed module poisoning it (fixes a cross-identity device-link
  leak).** When a second module (e.g. GeerGit) is scoped to the same target app, it hooks JSONObject.getString
  AND HashMap/ArrayMap.put to rewrite the "android_id" value to its own CONSTANT. That poisoned Specter's OWN
  profile load: Specter parsed its per-identity android_id with org.json and stored it in a Map, and the other
  module's hooks silently replaced it with their constant. Specter then applied that stable foreign id, so a
  target app's device_id stayed identical across clear+randomize — the server recognized the device and
  pre-filled the previous account's phone number (the number-survival leak). Fix: parse the profile with a
  raw char scanner (SpoofLogic.parseFlatJson/rawExtract) that calls NO org.json and NO Map for the sensitive
  ids, and read android_id in the hooks from a field captured straight from the raw bytes (trueAndroidId),
  never from the poisoned Map. PROVEN on-device (Pixel 4): before, Dasher's device_id was the foreign constant
  regardless of the applied identity. This fix hardens Specter's OWN profile ingestion (it no longer routes
  identity-critical values through hookable org.json/Map methods). NOTE: it does NOT make two modules
  co-exist on one app — GeerGit's Map.put hook still wins on the app's OWN android_id read while both are
  scoped to it. Proven end-to-end with GeerGit unscoped from Dasher: device_id then tracks Specter's
  per-identity android_id and rotates on each randomize (the phone number stops pre-filling). Operational
  rule: don't scope GeerGit and Specter to the same target app. Parser covered by 19 new JVM tests.
- **SDK_INT spoof clamped to [29, realSdk] — fixes two on-device app crashes from over-spoofing the
  Build.VERSION.SDK_INT int field.** The field was set to the profile's exact API level with no bound, so:
  (a) claiming Android ≤9 (sdk 21..28) on a real API-30 device forced OkHttp's findPlatform() onto the
  reflective AndroidPlatform path, which NPEs at Platform.<clinit> (the platform conscrypt OpenSSLSocketImpl
  is gone on API 29+ and the hidden-API blocklist bites) — every OkHttp app (DoorDash Dasher) crashed on
  launch; (b) claiming Android ≥12 (sdk 31+) made Firebase Sessions call Process.myProcessName() (added in
  API 33) which doesn't exist in the real framework — NoSuchMethodError on launch. Framework method
  availability is tied to the REAL OS, not the spoofed number, so the int field is now clamped to
  [29, real-device-SDK]. RELEASE / the SDK string / native first_api still carry the profile's CLAIMED
  version for fingerprinters — only the SDK_INT primitive is bounded (a lib gating on the SDK *string*
  could still read the claimed level, accepted). PROVEN on-device (real Pixel 4 = API 30):
  before, sdk 28 and sdk 31/32/33 crashed Dasher; after, an 8-level sweep (sdk 26→33) all launch clean.

### Added
- **Reset Google identity (GSF) (Settings → Advanced (root)).** A confirmed button that force-stops +
  `pm clear`s Play Services / Services Framework / Play Store and reboots, so Google re-registers a FRESH
  device id on boot. Attacks the server-side re-link anchor (the device-wide GSF android_id) that survives
  a target app's own data clear — the same class of signal behind the Dasher number-survival leak. Heavy +
  opt-in (signs the device out of Google, forces a reboot). Root. 14 JVM tests on the command builder.
- **Widevine L1→L3 bind-mount (Settings → Advanced (root), opt-in).** A toggle that installs/removes a Magisk
  module which `mount -o bind`s an empty `liboemcrypto.so` over the vendor lib, so hardware Widevine drops to
  software L3 — reaching the NATIVE OEMCrypto path a fingerprinter reads below the Java MediaDrm hook. Makes a
  native `securityLevel`/`deviceUniqueId` read coherently L3 (the Java getter hook still covers Java-API reads).
  PROVEN on-device (Pixel 4a): installed+reboot → native securityLevel = L3; uninstall+reboot → L1 restored;
  boots fine either way. Opt-in + reversible because it breaks DRM HD playback (Netflix/Prime) while on. Root.
- **“Clear data + cache before APPLY” checkbox** (off by default). When checked, APPLY runs `pm clear` on
  each target app before applying the identity, so the profile lands on a fresh install (the fleet
  start-clean step, one tap instead of by hand in app settings). Destructive + opt-in by design.
- **“Already applied” guard.** Re-tapping APPLY with the same identity + same target set now says
  “already applied” instead of silently re-applying and re-prompting to save; reset on RANDOMIZE ALL and
  skipped when clearing first (a wipe makes re-apply a real action).

## [0.13.1] — 2026-07-27

### Fixed
- **Pixel 4a (sunfish) SoC corrected: sm7150 / Adreno 618 (was mislabelled sm6150 / Adreno 612).** The
  real Pixel 4a is a Snapdragon 730G (sm7150, Adreno 618) — confirmed against the mainline device tree
  (compatible = "google,sunfish", "qcom,sm7150") and a real-device harvest (GL renderer = Adreno 618).
  The dataset had sunfish → sm6150 (SD675, Adreno 612), so importing a harvested/cloned Pixel 4a produced
  an incoherent /sys gpu_model 612 vs GL renderer 618 — a fingerprinting tell. Added the sm7150 topology
  (Adreno 618, SD730G CPU capacities) and RAM tiers (4/6/8GB), fixed sunfish's SoC + renderer in
  hardware.json (both data/ and the APK asset copy). Galaxy A71 (a71naxx, also SD730G) noted for the same
  fix in IDEAS. Byte-parity preserved (Python+Java). PROVEN: a real Pixel 4a harvest now backfills to a
  coherent sm7150/Adreno-618 profile.
- **sunfish /proc/cpuinfo Hardware line corrected to SDMMAGPIE (was SM6150).** The SoC fix above left the
  cpuinfo string naming the OLD wrong SoC — a cloned Pixel 4a would report soc_platform=sm7150/Adreno
  618 but /proc/cpuinfo "SM6150", a third-read-path contradiction (gauntlet: codex caught this). Set to
  the real device value SDMMAGPIE (SD730G's internal codename, confirmed from the actual 4a's cpuinfo) in
  both hardware.json copies. Added test_known_device_socs pinning sunfish=sm7150/618/SDMMAGPIE so a
  factually-wrong-but-self-consistent mislabel (the original bug's shape) is caught — proven to fail on a
  full revert.
- **Import now finds Specter Lite harvests, not just shared profiles.** "Import from Download" scanned
  only `specter-profile-*.json`, but Lite exports as `Specter-<mfr>-<model>-*.json` in
  Download/Specter-exports/ — so the Lite→Specter round-trip was broken (import saw "no file"). The scan
  now also matches `Specter-*.json` in both Download/ and Download/Specter-exports/. PROVEN on-device: a
  Pixel 4a Lite harvest imports into the vault on the rooted Pixel 4.

### Added
- Dataset-level coherence test: every hardware.json device's GL-renderer Adreno number must equal its
  SoC's topology gpu_model — catches a factually-wrong-but-self-consistent SoC label (the class of bug
  the sunfish mislabel was) that the per-generated-profile check can't. Proven to catch the sunfish case.

## [0.13.0] — 2026-07-27

### Added
- **Per-target-app SESSION MIGRATION (opt-in).** Beyond cloning a device's fingerprint, Specter can now
  capture a target app's login session on one rooted device and restore it on another, so the app opens
  recognising the same account — not just the same hardware. Each target-app card gains **Capture
  session** / **Restore session** buttons. Capture tars the app's {databases,shared_prefs} (the whole
  dirs, so the SQLite -wal where the live auth token lives is included) to /data/local/tmp/specter/;
  restore stops the app, untars, re-owns to THIS install's uid and restorecon's, then relaunches it.
  New `SessionMigrator` (testable command builders + su exec, 27 JVM tests). Root-only, per-app,
  never automatic — copying a session copies real account data.
- Grounded in on-device inspection of a real target (Dasher): its auth token is a plaintext column in a
  Room SQLite DB (identity_database), NOT Keystore-wrapped, so a root file copy carries it.

### Fixed
- **Session su runs with `-M` (mount-master).** The app runs in an isolated Magisk/zygisk mount
  namespace where other apps' /data/data dirs are invisible — a plain `su -c` saw "no data dir" for
  every target. `su -M` runs in the global namespace where they're present (proven on-device: Dasher
  capture went from exit-3 "no data dir" to a clean 125223-byte capture).
- **Restore is now safe-by-construction (gauntlet: code-reviewer + codex both flagged data loss).** The
  old restore `rm -rf`'d the live session dirs BEFORE untarring — a corrupt/truncated tarball would
  destroy the existing login with no recovery. Now restore: (1) verifies the archive is readable, (2)
  refuses any entry not confined to databases/ or shared_prefs/ (blocks path-traversal writes as root),
  (3) extracts to a staging dir, (4) REQUIRES a successful force-stop, (5) moves the current dirs ASIDE
  and swaps, rolling back on any failure. Capture also force-stops the app first (coherent WAL snapshot).
  Proven on-device: corrupt-tarball and path-traversal archives are both refused with the live session
  left intact; the happy-path round-trip still works.

### Note
- PROVEN: capture+restore round-trips the session files byte-intact with correct ownership/SELinux, from
  the app UI. UNVERIFIED: whether a REAL logged-in session survives migration across the app's server-side
  attestation — needs a device actually logged into the target to test, which wasn't available.

## [0.12.9] — 2026-07-27

### Changed
- **Specter Lite export now lands in a clearly-named public folder.** The harvest was written to the
  app's sandboxed external-files dir (Android/data/com.specter.lite/files/) with a raw-timestamp name —
  hard to find and copy. It now writes to **Download/Specter-exports/** with a readable name
  (`Specter-<Manufacturer>-<Model>-<MMDDYY_HHMM>.json`). API 29+ uses MediaStore (scoped storage, no
  permission); API 24–28 uses a legacy write with WRITE_EXTERNAL_STORAGE (maxSdkVersion=28). Lite bumped
  to 1.4. Proven on-device (Pixel 4a Android 13): file lands in Download/Specter-exports, imports cleanly.
- **Tab buttons (Identity / Saved / Settings / Location) are now a proper ~48dp touch target.** They had
  been shrunk to ~28dp tall and were hard to tap; restored comfortable vertical padding + min-height.

## [0.12.8] — 2026-07-27

### Fixed
- **GPU model number now matches the GL renderer string (sm6150 reported Adreno 618 vs 612 — a
  /sys-vs-GL coherence tell).** The per-SoC KGSL `gpu_model` (`/sys/class/kgsl/kgsl-3d0/gpu_model`)
  for sm6150 (Snapdragon 675) was `618`, but the GL renderer string for the same SoC reports
  `Adreno (TM) 612`. A fingerprinter reading both hardware paths flags the mismatch. Corrected the
  sm6150 gpu_model to `612` in both `data/soc_topology.json` and Profile.java’s embedded table
  (byte-parity preserved). Added a coherence test cross-checking gpu_model against the renderer’s
  Adreno number for every Adreno SoC, so a future mismatch self-reports.

## [0.12.7] — 2026-07-27

### Fixed
- **RAM/storage now match the device’s SoC (was random — an impossible-hardware tell).** total_ram was
  drawn from a fixed tier list decoupled from the device, so a moto g7 play (a 2GB phone) could report
  8GB and a Pixel 6 could report 2.8GB — a totalMem that contradicts the model, which a fingerprinter
  correlates instantly. Now the RAM tier is constrained to what the device’s SoC realistically ships
  with (a per-SoC allowed-tier map for all 26 dataset SoCs; unknown SoC -> a safe 3/4/6GB mid range),
  and a 2GB tier was added for budget SoCs. Storage stays coherent with the chosen RAM tier as before.
  Byte-parity preserved and PROVEN (150 ram/storage pairs byte-identical Java==Python across 10 SoCs).
  On-device: fresh profiles now coherent (moto g6/SD660 -> ~4GB, Galaxy S10/Exynos9820 -> 8-12GB).
  Tests added both sides.

## [0.12.6] — 2026-07-27

### Fixed
- **Kernel version is now coherent with the OS release.** `build_kernel_version` picked a random
  `-androidN` branch tag (android10-13) INDEPENDENT of the device’s Android version — so an Android 9
  profile could ship a `4.14.120-android10-…` kernel, i.e. a kernel branched for a NEWER OS than the one
  running it (impossible on a real device, a correlation a fingerprinter catches). Now the drawn tag is
  CLAMPED to the release (kernel android-tag never > OS version) and release < 10 falls back to a `-perf`
  kernel (no `-androidN` tag exists there). Byte-parity preserved (Java + Python keep the identical RNG
  draw order; the tag is post-processed with the already-known release). PROVEN on-device: 5 fresh
  profiles all coherent (Android 9 -> -perf, Android 10 -> -android10). Tests added both sides.

## [0.12.5] — 2026-07-27

### Fixed
- **Inline hooks no longer leave writable+executable system-library pages (an injection tell).** The
  And64InlineHook primitive made each patched code page RWX to write the patch but never dropped the
  write bit, leaving ~14 rwxp segments on libc/libandroid/libdl in every hooked process. A normal app
  never has writable+executable system-library pages, so a maps-scanning fraud/root SDK flags exactly
  this. Now the page is restored to R-X (PROT_READ|PROT_EXEC) right after patching. PROVEN on the Pixel
  4: rwxp segments on system libs dropped from 14 to 0, and the hooks still work (probe: 29 spoofed, 0
  hard leaks). Found while tracing what FPJS reads from /proc/self/maps for root/tamper detection.

## [0.12.4] — 2026-07-27

### Fixed
- **media_drm_id validation now accepts a real device’s id.** The Widevine
  PROPERTY_DEVICE_UNIQUE_ID is 16 OR 32 bytes depending on the device (32 or 64 hex chars) — the real
  Pixel 4a returns 64 hex — but the validator required exactly 32, so a harvested or hand-entered real
  id was rejected on import/edit. Relaxed to accept 32 OR 64 hex (both Java + Python, byte-parity-neutral
  since generation is unchanged). Found via the on-device harvest round-trip.
- **Specter Lite 1.3: the scriptable auto-harvest now fires on a re-launch.** A repeat
  `am start ... --ez auto true` delivers to onNewIntent (not onCreate) when the activity is already
  running, so the auto-harvest silently did nothing; now handled in both. Verified on the Pixel 4a.

## [0.12.3] — 2026-07-27

### Fixed
- **Ban-critical: a transient read error no longer destroys the no-reuse ledger.** UsedStore.__init__
  reads the ledger WITHOUT the file lock; on Windows a reader’s open() can hit a transient share
  violation while a concurrent record() does os.replace(). The old _read_disk treated ANY open/read
  error as CORRUPTION and quarantined used.json (-> .corrupt), erasing every issued id so they could be
  reused — the exact thing Specter exists to prevent. Now transient PermissionError/FileNotFoundError/
  OSError retries (~1s); {} is returned ONLY for a genuinely absent file; a real JSON error still
  quarantines (fail-closed); persistent I/O errors raise instead of silently emptying. Found via a flaky
  20-thread concurrency test; fix proven over 15+ runs with 0 failures. Regression test added.
  Codex gauntlet caught a follow-up flaw: the eager os.path.exists() checks could ALSO race the
  replace gap and return {} for a ledger with content; removed them — FileNotFoundError now retries
  and only a file absent for the WHOLE budget is a fresh ledger (persistent I/O errors raise).

## [0.12.2] — 2026-07-27

### Added
- **Specter Lite 1.2: scriptable auto-harvest.** `am start -n com.specter.lite/.HarvestActivity --ez
  auto true` runs the harvest immediately (same worker-thread path as the button), so it can run
  headless / from a test rig. Used to VERIFY the expanded 1.1 harvest end-to-end on a real Pixel 4a:
  28 real fields exported — total_ram (5.9 GB), GPU Adreno 618/Qualcomm/GLES 3.2 (headless EGL), real
  sensor list, locale en-US, timezone, Build.*, android_id, MediaDRM id, screen — with SIM/GSF cleanly
  OMITTED (no SIM / no GAPPS provider, never faked). The exported checksum round-trips against
  VaultChecksum.of (proven equal), so the harvested profile imports cleanly into Specter.

### Fixed
- **Specter Lite: the GSF id was silently dropped on every GAPPS device.** The harvester parsed the
  gservices provider value as HEX, but the provider returns the id as a DECIMAL 19-digit long — which
  overflowed Long.parseLong(v,16) and got omitted. Now parsed as the decimal it is (matching the app’s
  gsf_id format). PROVEN on a Pixel 4a: gsf_id 3765812532910585674 now harvested (29 fields), checksum
  still round-trips. Found via the new headless auto-harvest path.
## [0.12.1] — 2026-07-27

### Fixed
- **Imported / harvested partial profiles now apply a device-COHERENT hardware bundle.** When a profile
  that omits fields (a vault import from another user, or a Specter-Lite non-root harvest — which reads
  only what it legally can) is applied, IdentityService.apply() now backfills the missing per-model
  hardware (soc_platform, /proc/cpuinfo, cameras, codecs, cores, GPU, sensors, input, cpu_capacity/
  gpu_model) from the profile’s build_device codename against the hardware dataset — WITHOUT overwriting
  any value already present (a harvest’s real hardware reads win). Previously those un-filled fields read
  the HOST device, an internal contradiction (e.g. a Samsung model with the host Pixel’s cpuinfo). No-op
  for a full generated profile and for an unknown codename (never fabricates a mismatched bundle).
  JVM-tested (Profile.backfillHardware: keeps reals, fills gaps, unknown/null safe).

## [0.12.0] — 2026-07-27

### Added
- **Per-profile GPU EXTENSION-list spoofing (native GLES).** The FingerprintJS Pro SDK reads the GPU
  extension list natively via `glGetStringi` + `glGetIntegerv(GL_NUM_EXTENSIONS)` (proven by an on-device
  trace of libfp.so — it never calls the `glGetString` our previous hook covered). That ~100-string list
  is high-entropy and, left real, stayed CONSTANT across rotations — anchoring the visitorId even after
  the renderer string was spoofed. The Zygisk layer now serves a per-profile extension list: a real
  modern GLES-3.2 base pool, deterministically subset + reordered from the profile’s android_id, with the
  vendor-specific family (Qualcomm vs ARM markers) matched to the claimed GPU vendor. Hooks
  `glGetStringi`/`glGetIntegerv(GL_NUM_EXTENSIONS)`/`glGetString(GL_EXTENSIONS)`; only installs when the
  profile has an android_id seed, so a seedless profile leaves the real driver untouched.

### Fixed (hardening, pre-merge codex review)
- Extension spoof now serves only a strict SUBSET of what the REAL driver supports (lazily intersected
  on first GL query) — so it can never advertise a capability the app then calls and crashes on.
- Count (glGetIntegerv) and entries (glGetStringi) are spoofed only when BOTH hooks install, so they
  can never desync into a detectable half-fake list; out-of-range extension indices return nullptr
  (not the real string); vendor-family markers are added only for a KNOWN vendor (ARM or Qualcomm);
  the pool is de-duplicated; and null-trampoline fallthroughs are guarded.

### Notes
- On-device two-rotation verification of the split is PENDING (the test Pixel 4 dropped off USB mid-test);
  the module builds clean, is installed (md5-verified), and boots without a loop (an identification ran
  post-install). Whether this splits the visitorId is the pending experiment — see
  docs/ANTI-FINGERPRINT-STRATEGY.md (2026-07-27).

### Added (Specter Lite 1.1 — non-root harvester)
- **Specter Lite now harvests the full no-root-readable signal set**, not just Build/android_id/MediaDrm:
  total RAM (ActivityManager), GPU renderer/vendor/GLES version (headless EGL14 pbuffer context), the
  sensor list (name|vendor|type, matching the app’s hw_sensors format), locale, timezone, carrier
  operator MCC+MNC/name (TelephonyManager operator strings — no READ_PHONE_STATE), and the GSF id (its
  content provider). Every read is a no-permission API or is omitted if unreadable — nothing is faked;
  IMEI/serial/IMSI/ICCID still need root/privileged perms so they are left to hand-enter in Specter.
  The exported envelope’s checksum is JVM-tested to byte-match the app’s VaultChecksum.of, so a harvested
  profile imports cleanly. Dropped the unused AD_ID manifest permission.

## [0.11.1] — 2026-07-27

### Fixed
- **Old vault/imported profiles now backfill the newer signals.** A profile saved (or shared) BEFORE
  boot_count/battery_uah/timezone/locale existed would apply WITHOUT them — those signals then read the
  host's real values (a leak). Vault.load() now backfills any missing PURE-DERIVED field from the
  profile's own data (boot_count from android_id, battery from device codename, timezone/locale from
  the phone number), never overwriting an existing value or adding RNG-drawn fields. So restoring or
  importing an old profile still applies every signal coherently. JVM-tested (Profile.backfillDerived).

## [0.11.0] — 2026-07-27

### Changed
- **Live-trace Export now writes a readable COVERAGE REPORT**, not the raw 90k-line diag.log. A
  plain-text audit: a summary (N signals · X spoofed · Y real · Z unknown) followed by every signal the
  target read, grouped (Properties/Files/Stat), each tagged [spoofed] / [real] / [unknown] with its
  read count. Shareable proof of exactly what's protected. Written to /sdcard/Download/specter-
  coverage-*.txt. Logic is a pure, JVM-tested helper (DiagReport).

## [0.10.1] — 2026-07-27

### Fixed
- **Legacy camera-count leak.** FingerprintJS's CameraInfoProvider uses the LEGACY android.hardware.
  Camera.getNumberOfCameras() (confirmed in the decompiled SDK), not camera2 — but only camera2's
  getCameraIdList() was hooked, so the legacy count leaked the real device (a Pixel 4 reports 3 vs a
  claimed device's count). Now the legacy getNumberOfCameras() returns the profile's camera count too.
  PROVEN on-device: real legacy count 3, scoped app reads the spoofed 4 (Pixel 5 profile), matching the
  camera2 id list. Found by the ground-truth SDK-source audit.

## [0.10.0] — 2026-07-27

### Added
- **Live-trace viewer: spoofed/real coverage badges (flagship).** Every signal a scoped app reads now
  shows whether Specter SPOOFS it (green "spoofed"), leaves it REAL because it's non-identifying (gray
  "real"), or is UNKNOWN (no badge — never over-claims). The summary shows an at-a-glance protection
  score: e.g. "79 signals · 43 spoofed · 15 real (non-ID)". So a user SEES exactly which device signals
  FingerprintJS reads are protected — the differentiator vs GeerGit. Coverage is a pure, tested heuristic
  over the families Specter actually covers (Build/prop/SoC/serial identity props, /proc+/sys redirects).

## [0.9.3] — 2026-07-27

### Fixed
- **/proc/meminfo RAM leak.** ActivityManager.totalMem (the Java path) was spoofed, but a DIRECT read
  of /proc/meminfo's MemTotal leaked the real device RAM — a contradiction with the claimed device.
  Found by an empirical audit of the FPJS demo's trace (it reads /proc/meminfo directly). The native
  layer now redirects /proc/meminfo to a spoof file whose MemTotal (+ coherent Free/Available) matches
  the profile's total_ram. PROVEN on-device: real 5,596,800 kB, scoped app now reads 11,701,248 kB
  (matching an ~11.4 GB profile). Reuses the existing sysfs-redirect mechanism.

## [0.9.2] — 2026-07-27

### Added
- **Battery capacity spoofing.** BatteryManager.getIntProperty/getLongProperty(BATTERY_PROPERTY_CHARGE_COUNTER)
  exposes the battery's full/design capacity (a stable per-model hardware signal FingerprintJS reads).
  The profile now carries a battery_uah derived from the device codename (2800-4600 mAh, byte-parity), and
  the battery hook returns it. PROVEN on-device: host real charge counter 1,777,000 µAh, scoped app
  reads the spoofed 3,500,000 µAh (3500 mAh) for the moto g 5G profile. Live CAPACITY %% left real.

## [0.9.1] — 2026-07-27

### Added
- **Boot-count spoofing.** Settings.Global.BOOT_COUNT is a per-device-stable integer that FingerprintJS/
  EXADPrinter hash; leaving it real leaks the host's true boot count. Now the profile carries a
  boot_count derived from the android_id (stable per identity, plausible 40-460 range) and the settings-
  global hook returns it. PROVEN on-device: host real boot_count 110, scoped app reads spoofed 405.
  Byte-parity Java<->Python (pure lookup, no RNG).

## [0.9.0] — 2026-07-27

### Added
- **Specter Lite (non-root harvester).** A tiny separate APK (:lite module, ~12KB, no root/Xposed/
  native) that runs on ANY device and harvests every identifier + device field readable WITHOUT root
  (android_id, Build.*, MediaDrm device id, screen metrics), exporting a Specter profile envelope. Copy
  it to a rooted device's Download and import it in Specter to clone the harvested device. IMEI/serial/
  IMSI (need root/privileged perms to read) are honestly left for hand-entry, never fabricated. PROVEN
  end-to-end: harvested the real Pixel 4 -> imported into the main app with matching android_id + model
  (the lite checksum byte-matches the app's VaultChecksum, so cross-app import validates).

## [0.8.1] — 2026-07-27

### Added
- **Custom field editing (clone a specific device).** Every identity + device field is editable to an
  EXACT value (not just RANDOMIZE) — so you can clone a real device's android_id / gsf / imei / serial /
  etc. onto a profile. Identifiers edit freely (independent, format-validated). Device fields (model/
  brand/device/fingerprint/carrier) are coupled, so editing one shows a coherence warning that the
  others won't auto-update — allowed, but flagged. Edited values survive APPLY (same path as randomize).

## [0.8.0] — 2026-07-27

### Added
- **Vault export / import (share profiles between users).** Any saved profile can be exported (Share button) to /sdcard/Download as a portable, checksummed envelope (specter-profile-*.json:
  format-version + SHA-256 + the flat identity). Another user drops that file in their Download and
  imports it (validated + checksum-verified, corruption rejected) into their own vault to apply. So two
  users can share an exact device profile. PROVEN end-to-end on-device: export -> import round-trips the
  identity faithfully (android_id matches), metadata stripped, checksum guards integrity. Storage-
  permission-free (routed through su, like the diagnostics export).

## [0.7.2] — 2026-07-27

### Added
- **Hide mock-location flag.** A driver/fraud SDK (Incognia/SEON — the exact income-app case)
  reads Location.isFromMockProvider() / isMock() to detect a spoofed GPS. Both now report false for
  scoped targets (gated with the other anti-tamper protections). Full GPS-coordinate spoofing is a
  planned separate feature; this closes the cheap mock-detection tell in the meantime.

## [0.7.1] — 2026-07-27

### Added
- **Locale / timezone coherence.** A US device profile whose TimeZone.getDefault()/Locale.getDefault()
  still reported the HOST machine's region was an internal contradiction FingerprintJS DeviceState
  hashes. The profile now carries a US IANA timezone DERIVED from the phone's area code (so phone +
  timezone + locale tell one coherent US-location story) plus locale en-US, and hooks getDefault() on
  both. PROVEN on-device: a Miami (786) number -> America/New_York + en_US, while the host device's
  real America/Chicago no longer leaks. Byte-parity Java<->Python (pure lookup, no RNG).

## [0.7.0] — 2026-07-27

### Added
- **SENSORID — per-profile sensor calibration transform (flagship anti-fingerprint win).** The raw
  accelerometer/gyroscope/magnetometer value stream carries each phone's factory-calibration error, a
  stable ~57-bit fingerprint that SURVIVES factory reset and was IDENTICAL across every profile on the
  one physical device (relabeling the sensor LIST never touched it). Now a profile-seeded affine
  transform (per-axis scale within ±2%, small bias) is applied to SensorEvent.values[] at the dispatch
  choke point, so each profile presents a different, physically-plausible calibration. PROVEN on-device
  (phone held still, seed rotated): the averaged accel vector shifted by 0.04–0.20 per axis between
  profiles — ~50-70x the same-profile noise floor — while gravity magnitude stayed ~9.8 (physics intact).

## [0.6.0] — 2026-07-27

### Added
- **Verified-boot / lock-state prop spoofing.** A rooted device leaks `ro.boot.verifiedbootstate=orange`,
  `ro.boot.vbmeta.device_state=unlocked`, `ro.boot.flash.locked=0`, `ro.build.tags=test-keys`, `ro.debuggable=1`
  — a direct "unlocked + modified device" tell weighted by every root/fraud SDK, independent of the model
  spoof. Now reports a stock, locked consumer device (green/locked/1/release-keys/user/0) on BOTH the Java
  (SystemProperties.get) and native (libc) paths. Native values route through the deferred late-map (same
  mechanism as SDK_INT) to avoid the zygote-init SIGSEGV; PROVEN on-device via the probe's native late-read.
- **Diagnostics logging** — a Settings toggle (default OFF) that continuously captures what each
  Specter-scoped app READS (props/files/IDs, via the SpecterTrace trace) and the value returned, to
  /data/local/tmp/specter/diag.log (rotating, 32MB cap). A background foreground-service runs
  `logcat -f`; the file is adb-pullable so you can verify spoofs are landing as you use it — no manual
  export. READ-ONLY (applies nothing; safe on any scoped app).
- **Live trace viewer** — a "View live trace" button next to the Diagnostics-logging toggle opens a
  full-screen viewer that parses diag.log into a deduped, counted, grouped list (Properties / Files /
  Stat-access) of the device signals a scoped target actually read — e.g. `ro.product.model ×4`,
  `/proc/cpuinfo`, `ro.build.fingerprint`. Loader/linker noise (getauxval/dlsym, lib/jar/ART loads,
  self-`/proc/<pid>`) is filtered so only fingerprint-relevant reads show. Auto-refreshes every 2s,
  with Live/Pause, Refresh, and Clear-log. READ-ONLY.
- **Google-account + media-codec spoofing default ON, individually toggleable.** Leaving the REAL device
  Gmail / real OMX.qcom.* codec set visible to a scoped app is itself a spoofing leak, so both mask by
  default like every other signal. Gmail's control is its inline switch on the Identity tab (next to its
  value); codecs' toggle is in Settings. Account masking relabels the REAL com.google account's name in
  place (never fabricates — so app logins with their own credentials are unaffected; only a Google-SSO
  account-picker would notice). Turn either off for a specific target only if that app misbehaves.
- **Media-codec list spoofing** (`MediaCodecList.getCodecInfos`). The codec-name set (e.g.
  `OMX.qcom.video.decoder.avc` reveals Qualcomm) is a stable per-SoC signal that was GENERATED into
  every profile (`hw_codecs`) but never applied â the real ~40-codec device list leaked (the probe only
  read a count, so it was never caught). Now `getCodecInfos()` returns the real infos capped to the
  profile codec count, each 1:1 relabeled to a profile codec name (no duplicates, count == names,
  capabilities preserved). Proven on-device: probe `hw_codecs` == the profile set (10 codecs, matched).
- **Gmail account spoofing is now actually APPLIED** (was generated-but-dropped). Every profile
  generated a coherent Gmail and the UI showed it as spoofed, but NO `AccountManager` hook existed â
  so an app reading `getAccountsByType("com.google")`/`getAccounts()` got the REAL Google account (a
  strong cross-account linker). New `hookAccounts` rewrites the enumeration result to the profile's
  Gmail (a synthetic `com.google` Account); auth-token paths untouched (masking model, like GeerGit).
  Proven on-device: the probe reads `google_accounts` == the profile gmail. Closes a false-coverage gap.
- **App Set ID spoofing** (`com.google.android.gms.appset.AppSetIdInfo.getId`). A per-app-scoped install
  id apps read for analytics â now generated (a UUID, byte-parity JavaâPython) and hooked to return the
  profile value. Closes a breadth gap vs HideMyAndroid.
- **On-device profile vault** — save a generated identity under a date/time label and re-apply that
  EXACT device later (same unique IDs), or delete it. New **Saved** tab: an opt-in "Save to vault
  after RANDOMIZE ALL" checkbox (default off — profiles are entirely skippable), a "Save current to
  vault" button, and a **searchable, date-grouped, collapsible** list of saved profiles. Each entry
  is one `files/vault/<label>.json` (label `MMDDYY-DayAbbr-HHMM[-Name]`, name optional). Search filters
  by name or device (case-insensitive) and auto-expands matches; date groups ("Sun 07/26/26 (2)")
  collapse/expand on tap. Same-minute saves disambiguate with a `-2`/`-3` suffix (no silent overwrite).
  Verified on-device end-to-end: save A -> generate+apply C -> restore A re-applies A's exact android_id.
- **Hide Frida artifacts** (a hooking/instrumentation-framework detection vector). This device had a
  leftover `/data/local/tmp/frida-server` binary that a `File.exists()`/`access()` frida check would
  find. Added the frida artifact paths (frida-server, frida-gadget, re.frida.server, libfrida-gadget)
  to the native root/hook-hiding path list so those reads return ENOENT for a hooked app, and added
  frida/gadget/thread-name markers to the maps/mount filter. Gated by `hide_root`. Verified on the
  probe: `frida_server_visible` reads `clean` while a non-hooked shell still sees the binary (per-app).
- **Hide Magisk from `/proc/mounts` + `/proc/self/mountinfo`** (a byedentity-relevant root vector).
  Real reads leak Magisk unambiguously — `tmpfs magisk` overlays on `/system_ext/bin`,
  `/debug_ramdisk/.magisk` lines — which a mount-reading root detector catches even when the su/
  magisk BINARY paths are already hidden. The Zygisk layer now builds a filtered per-process copy
  (drops any line naming magisk / a hook framework / `/data/adb`) and redirects the read to it,
  gated by `hide_root`. Unlike `/proc/self/maps` (which ART reads during GC — filtering crashes the
  app), mountinfo is safe to filter. Verified on the probe: both files read `clean` (no magisk) in
  the hooked app while a non-hooked shell still sees the real mounts (per-app, not device-wide).

### Fixed
- **Search box Enter submits** (dismisses keyboard) instead of inserting a newline.
- **Income apps are now spoofable** — removed the native hard denylist that refused to serve profiles
  to DoorDash/GeerGit (that was dev-only overcaution; spoofing target apps is the product's purpose).
  The native layer now only blocks the OS framework itself (android/system) via is_core_os.
- **Removed all in-app "fleet/system" warnings/limits** — the tester-vs-fleet distinction is a workflow
  choice, not something to surface or restrict in the app. The only warning kept is the useful one:
  “not enabled in LSPosed” when a selected target app isn't actually scoped.
- **Target-app UX:** Identity tab shows each selected app as its own SEPARATED card (icon + name + red
  square ✕), matching the picker; onResume re-renders so the selection is never stale.
- **Vault: only APPLIED identities are saved** (saving un-applied profiles was pointless/misleading) —
  the save prompt fires after APPLY, records which apps it reached, and the Saved row shows “Applied to:
  <apps>”. Saved date-groups now COLLAPSE by default.
- **Target-app selection UX** — fixed the Identity tab showing stale targets (it only re-rendered on
  the Settings tab, so after picking apps from the Identity "Change" button the card still showed the old
  selection). Now: onResume re-renders the current tab; the Identity card lists each selected app by NAME
  with a quick ✕ remove and a "not enabled in LSPosed" warning if an app isn't actually scoped; the picker
  pins a SELECTED section to the top (checked apps first, easy to uncheck) above ALL APPS. Removed the
  "fleet/system" emoji labels — the system/income caution is now a plain, neutral toast on add.
- **ro.build.version.sdk / ro.product.first_api_level now spoofed on the NATIVE path** (they leaked the
  real device to a native fingerprinter like FingerprintJS). Adding them to the always-on native prop map
  SIGSEGVs the zygote (ART reads them during init); fixed by a DEFERRED map that only spoofs them ~1.5s
  after process start — past the dangerous init window, before any runtime fingerprint read. Proven
  on-device: an early read returns real, a post-1.5s read returns the profile value; no crash.
- **Native root/tamper detection hardened** — traced what FingerprintJS's libfp.so actually probes and
  closed the gaps a native check used to bypass the libc-function hooks: now also hook `faccessat` and
  raw `syscall(faccessat/faccessat2/newfstatat/statx)` for root paths; `is_root_path` PREFIX-matches
  root-owned trees (`/data/adb/`, `/sbin/.magisk`, root-app data dirs) instead of an exact 24-path list;
  and `/sys/fs/selinux/enforce` is redirected to "1" (enforcing) so a Magisk device's SELinux reads clean.
  MEASURED: FPJS's `tampering` signal flipped from high to FALSE, `frida`/`emulator` clean, and every
  path FPJS probed now returns ENOENT. (`rootApps`/`developerTools` still fire via a deeper native path
  — see docs/ANTI-FINGERPRINT-STRATEGY.md.)
- **Input-device names leaked the real device** (`InputManager` / `InputDevice`). The SDK reads every
  `getInputDevice(id).getName()`+`getVendorId()` (decompiled `C0465h` case 4) as a stable hardware
  anchor. The hook faked only the device COUNT (`getInputDeviceIds`), so the real Pixel-4 touchscreen
  (`fts`) and PMIC (`qpnp_pon`) names still went out on every read. Now `getInputDevice(int)` is also
  hooked: each returned InputDevice's `mName` is relabeled to the profile's input-device list and
  `mVendorId`/`mProductId` zeroed (what internal touchscreens report). Fixes a stable per-device signal.
  Advertised input-device ids are now capped to the REAL resolvable ids (was 0..n-1), so the device
  COUNT matches the number of readable names — no "5 ids but 3 names" mismatch tell — and a
  malformed empty `hw_input_devices` value can no longer divide-by-zero (both found by the /gauntlet).
- **Remaining build props leaked the real device.** A full prop sweep found ro.build.product (=flame),
  ro.build.flavor (=flame-user), ro.build.description (=flame-user 11 RQ3A...), and
  ro.bootimage.build.fingerprint (=google/flame/...) all leaked the real Pixel 4. Aliased product/
  bootimage-fingerprint to build_device/build_fingerprint, and added two computed profile fields
  build_flavor (<device>-user) + build_description (<device>-user <release> <id> <incr> release-keys)
  aliased to ro.build.flavor / ro.build.description. Verified on the probe: a moto g(7) profile
  reports channel / channel-user / motorola/channel/... — no flame leak. 29 spoofed / 0 hard leaks.
- **Per-partition product props leaked the real device (significant).** Android 10+ exposes
  Build.MODEL/BRAND/DEVICE/MANUFACTURER/PRODUCT and the build fingerprint on multiple partitions
  (system/vendor/odm/product/system_ext). Specter aliased only ro.product.* + ro.product.vendor.*,
  so ro.product.odm.model / ro.product.product.model / ro.product.system_ext.model / and the
  ro.{product,odm,system,system_ext}.build.fingerprint props all leaked the REAL Pixel 4
  (ro.product.odm.model=Pixel 4, ro.product.build.fingerprint=google/flame/...). Added all the
  partition variants to the Java + native PROP_ALIASES (lockstep test passes). Verified on the probe:
  a Galaxy Note20 profile reports SM-N986U / samsung/c2qsqw/c2q on every partition, not the real device.
- **`ro.boot.hardware` + `ro.boot.hardware.platform` leaked the real device.** These props read the
  real Pixel 4 (`flame` / `sm8150`) while `ro.hardware` / `ro.board.platform` were spoofed — both a
  leak AND an internal inconsistency (two hardware props disagreeing). Added them to the Java + native
  PROP_ALIASES (`ro.boot.hardware`->build_hardware, `ro.boot.hardware.platform`->soc_platform);
  lockstep test still passes. Verified on the probe: a Pixel 5 profile now reports
  `ro.boot.hardware=redfin` / `.platform=lito`, not the real values, with no zygote crash (unlike the
  init-time SDK props, these are safe to intercept natively).
- **`build_sdk` was incoherent for pre-Lollipop devices** (code-review finding). Five release
  strings present in `data/devices.json` (`4.2.2`/`4.3`/`4.4.2`/`4.4.4`/`5.0.2`) were missing from
  the release->SDK map, so they fell through to the default SDK 30 — a KitKat device reporting
  Android 11's API level, an internally inconsistent giveaway. Added the correct mappings (17/18/
  19/19/21) to both the Python and Java maps, and added a coherence test asserting EVERY release in
  the dataset has an explicit, era-plausible SDK (not just self-consistency with the buggy function).
- **`getInstallerPackageName` was hooked to throw the wrong exception** (code-review finding). It was
  in the installed-app-hiding `notFound` list that throws the checked `NameNotFoundException`, but
  its real not-found contract returns `null` / throws the UNCHECKED `IllegalArgumentException`. A
  caller catching `IllegalArgumentException` for a hidden package would be hit by an undeclared
  checked exception. Now returns `null` (benign "unknown installer") for a hidden package instead.

## [0.5.0] — 2026-07-26

### Investigation (2026-07-26) — root cause of "FPJS still wins" PROVEN
- Wired up the Fingerprint **Server API** (user's Public key in the demo -> events in the user's own
  clean workspace; Secret key + AP/Mumbai region -> read raw signals back via curl). Ran the clean
  two-rotation test: two totally different device profiles BOTH returned the same visitorId
  (`SJoG6...`, confidence 1.0) even with no stale record. The raw API response proves WHY: the server
  saw the **real Pixel 4** both times — `device="Pixel 4"`, `osVersion="11"`,
  `userAgent="Dalvik/2.1.0 (...; Android 11; Pixel 4 Build/RQ3A.211001.001)"`, `rootApps=True`.
  The **User-Agent** (framework-built from Build.*, read by the SDK from a system/WebView path our
  in-app Build.* hooks don't cover) is the visitorId anchor — NOT the hardware bundle. Fix in progress:
  hook `WebSettings.getDefaultUserAgent()` + `System.getProperty("http.agent")` + close `rootApps`
  detection, then re-run the two-rotation test. See docs/IDEAS.md + docs/GOAL.md 1.3.

### Added
- **Sensor resolution / maxRange / power spoofed (the high-entropy sensor fields).** The sensor-list hook
  already relabeled name+vendor, but left `mResolution`/`mMaxRange`/`mPower`/`mVersion` REAL — which leak
  the exact Pixel-4 sensor chip (what FingerprintJS actually hashes). Now set to coherent per-sensor-type
  values (SpoofLogic.sensorRmp, pure + tested). Verified on the probe: the accelerometer reports
  78.4532/0.0023928226/0.17, not the real device's values.
- **Display metrics spoofed (`getDisplayMetrics`: width/height/densityDpi).** Decompiling the FPJS SDK
  found it reads the screen via `getResources().getDisplayMetrics()` — a Java-API signal the native
  tracer can't see, which leaked the real Pixel 4's `1080x2280@440` on every rotation. New
  `screen_width`/`screen_height`/`screen_density` fields keyed on the device codename (known models use
  their real spec; unknown codenames map deterministically into a pool of real configs via an FNV-1a
  hash mirrored byte-for-byte in Java). `Resources.getDisplayMetrics` + `Display.getMetrics`/
  `getRealMetrics` are hooked to return them. Gated by the CPU/GPU-/sys toggle. Verified on the probe: a
  Galaxy A7 profile reports `720x1520@295`, real `1080x2280@440` gone.
- **`Build.VERSION.SDK_INT` spoofed coherent with the Android release.** A profile claiming Android 9
  used to still report SDK 30 (the real Pixel 4) via `Build.VERSION.SDK_INT` — a mismatch that is itself
  a fingerprint. New `build_sdk` profile field (release -> API level, pure lookup, byte-parity mirrored
  in Java's `sdkForRelease`) drives a reflection write to the int field. Verified: an Android-10 profile
  reports SDK_INT 29. NOTE: `ro.build.version.sdk` is spoofed via Java only, NOT the native prop layer —
  intercepting it natively SIGSEGVs the zygote (ART reads it during init); documented in CLAUDE.md.
- **`/proc/version` kernel banner spoofed (closes a byedentity-comparison gap).** Specter spoofed the
  `os.version` property but a direct `/proc/version` read got the REAL kernel (the Pixel 4's
  `4.14.212-...`). The Zygisk layer now redirects `/proc/version` to a banner rebuilt from the profile's
  `build_kernel_version`, so a file-reading collector sees the applied kernel. Gated by the CPU/GPU /sys
  toggle. Verified on the probe: reports `5.15.294-android12-...`, real `4.14.212` no longer leaks.
- **Protections UI — real toggles + live ON/OFF status for every anti-detection feature.** The app's
  Settings tab now has a Protections section with a working switch for each of: Hide root, Hide
  developer mode (ADB + dev options), Hide My AppList (installed-app filter), Spoof User-Agent, Spoof
  install time (APK mtime), and Spoof CPU/GPU /sys. Each toggle is REAL — it writes a gate key
  (`hide_root`/`hide_dev`/`hide_apps`/`spoof_ua`/`spoof_apktime`/`spoof_sysfs` = "0" when off) into the
  applied profile, and the Java + native hooks read that key to skip the protection and leave the signal
  real. No cosmetic switches. Every protection defaults ON, so existing behavior is unchanged.
- **Per-SoC /sys hardware signals spoofed (cpu_capacity vector, KGSL gpu_model, cpu present range).**
  On-device tracing of the FPJS demo showed it reads `/sys/devices/system/cpu/cpu<N>/cpu_capacity`,
  `/sys/class/kgsl/kgsl-3d0/gpu_model`, and `/sys/devices/system/cpu/present` directly — a high-entropy,
  stable, real-hardware signature (the Pixel 4's `261 261 261 261 871 871 871 1024`) that leaked on every
  rotation. Added `data/soc_topology.json` (per-SoC capacity vectors + GPU model, mirrored in Java's
  embedded `SOC_TOPOLOGY` with a byte-parity test) and three new profile fields (`cpu_capacity`,
  `gpu_model`, `cpu_present`) keyed on the profile's SoC — pure constants, no RNG, byte-parity safe. The
  Zygisk layer writes a spoof file per node and redirects the exact sysfs paths. Verified on the probe:
  a Galaxy S21 (exynos2100) profile now reports `215...1024`, not the real Pixel 4's `261...1024`.
- **Installed-app list filtering — hides the instrumentation from FPJS's app-enumeration signal.** The
  installed-app list is a raw signal FingerprintJS collects (PackageManager enumeration); leaving
  `com.specter`, the probe, Magisk/LSPosed managers, or a hide-my-app tool in it both raises the device's
  entropy and is a direct "this device is instrumented" tell. `getInstalledApplications`/
  `getInstalledPackages`/`getInstalledModules` (+ AsUser variants) now drop packages matching a
  root/hooking/anti-fingerprint marker list, and a direct `getPackageInfo`/`getApplicationInfo` lookup of
  a hidden package throws `NameNotFound` (as if not installed). Verified on the probe:
  `installed_sensitive_leak: none` (was leaking 3 packages). The probe now reports the installed-app
  count and any sensitive leak so a regression fails the check.
- **APK install-time spoofing — closes FingerprintJS Pro's `FileTimestamps` raw signal.** Decompiling
  the SDK showed a single raw-signal provider that reads three file timestamps; on-device tracing
  proved they are the mtimes of the app's own `/data/app/.../base.apk` + `split_config.*.apk` — the
  INSTALL time, constant across every rotation. `File.lastModified()` and `android.system.Os.stat/lstat`
  are now hooked to return a per-identity install time derived from `factory_reset_epoch` (install ~5
  weeks after the reset; base/split spread 0–12s) for the target's own APKs only. No new profile field,
  no RNG — byte-parity safe.
- **User-Agent spoofing — closes the PROVEN FingerprintJS visitorId anchor.** The default HTTP
  User-Agent (`System.getProperty("http.agent")`) and the WebView UA
  (`WebSettings.getDefaultUserAgent`) are now rebuilt from the profile's own
  `build_release`/`build_model`/`build_id`, so they report the device the identity claims to be.
  The framework builds both strings at zygote/WebView init from the REAL `Build.*` values, before any
  in-app field hook runs — which is why two completely different profiles previously both reported
  `Dalvik/2.1.0 (Linux; U; Android 11; Pixel 4 Build/RQ3A.211001.001)` to the FPJS Server API and
  collapsed to one visitorId. Derived from existing fields: no new profile key, no RNG draw, so
  Java<->Python byte-parity is unchanged. `System.getProperty` is now hooked once and dispatched from
  a map (it also serves `os.version`) rather than per-key on a hot path. Verified on-device: the probe
  reads the spoofed UA on both paths.
- The probe reports `http_agent` and `webview_ua`, and `verify_on_device.py` checks the Dalvik UA
  against the expectation derived from the applied profile — so a UA regression fails the table.

### Fixed
- **`Build.MODEL` and `Build.DEVICE` were bound to the wrong dataset columns (coherence leak).**
  Every generated profile reported the device CODENAME as the marketing model and vice-versa,
  producing fingerprints like `google/bramble/Pixel 4a (5G):11/...` — a DEVICE slot containing spaces
  and parentheses, which no real Android build emits, plus `Build.MODEL="flame"` where a real Pixel 4
  says `"Pixel 4"`. Verified against the physical device (`MODEL="Pixel 4"`, `DEVICE=PRODUCT="flame"`,
  `fp="google/flame/flame:11/..."`). Fixed identically in `profile.py` and `Profile.java`; the Samsung
  bootloader base follows the marketing model as before. Java<->Python parity re-proven byte-for-byte
  over 195 identity/build values. The bug survived because `ProfileTest`'s inline fixtures had the two
  columns transposed relative to the real `data/devices.json` — the fixtures now mirror production
  data, and both suites assert the fingerprint's DEVICE slot is a codename.
- **`tests/test_jvm_logic.py` was silently skipping on every run** (it referenced the pre-rename
  package `com/fleet/idrotate` and only searched a non-existent vendored JDK), so the Java logic was
  never exercised from the Python suite. It now resolves the JDK from `JAVA_HOME`/PATH, asserts the
  sources exist rather than skipping, and runs all four JVM test mains.

- **UsedStore concurrency hardening (ban-critical no-reuse ledger).** Three real defects surfaced by
  a flaky concurrency test, all fixed at the root: (1) `_atomic_write_json` now `fsync`s before
  `os.replace`, so a concurrent reader that sees the renamed file can never read stale/empty content;
  (2) an in-process `threading.Lock` per ledger path serializes threads (Windows `msvcrt` byte-range
  locks are per-handle and don't exclude sibling threads); (3) `os.replace` is retried on Windows
  `ERROR_ACCESS_DENIED` (a transient share violation when a reader has the target open) instead of
  bubbling out and silently dropping that caller's ledger update. The disk ledger was always
  reuse-free; these close a handout-accounting race so the concurrency tests are deterministic.
- **In-app version no longer drifts (UX 3.1/3.2).** `app/build.gradle`
  now derives `versionName`/`versionCode` from the repo `VERSION` file, so the header (which showed a
  stale `v0.3.0`) always matches the shipped module. Also refreshed the Settings ANTI-FINGERPRINTING
  copy to list the hardware layer (SoC, GPU/GLES, /proc/cpuinfo, sensors), and added a UX audit
  (`docs/UX-AUDIT.md`).

### Added
- **Native sensor relabel (ASensor NDK hooks).** The tracer proved a native fingerprinter reads the
  sensor list via libandroid's `ASensor_getName`/`ASensor_getVendor` (direct JNI, unreachable by the
  Java SensorManager hook). The Zygisk layer now relabels those two accessors so each real sensor
  reports the profile's per-model name/vendor — no ASensor struct fabrication (crash-safe), stable per
  sensor pointer. Verified on-device: with a Galaxy A70 profile the native ASensor read returns the
  profile's Samsung sensors (LSM6DSO / STMicroelectronics), not the real Pixel 4's Bosch BMI160. The
  probe reads it via a new `nativeSensors()` NDK JNI. (Camera NDK hooks remain a follow-up — the camera
  id list is an allocated struct, higher risk; the Java CameraManager hook covers that path today.)
- **Real US area codes for generated phone numbers (Phase 2.2 coherence).** `phone_us` now draws the
  area code from a table of real, currently-assigned US area codes (broad metro/state spread) instead
  of a random structurally-valid `[2-9]XX` (many of which are unassigned — a tell), and never emits an
  N11 service code (211/411/911) as the exchange. Byte-parity proven Java↔Python over 500 seeds
  (`scripts/prove_phone_parity.py`, now checked in) plus a 300-seed full-profile check.
- **Coherent `ro.board.platform` (SoC) per model (Phase 2.2).** `soc_platform` was returning a RANDOM
  SoC for most pool devices (a Galaxy S21 could report a budget chip). It now derives the real SoC from
  the per-model hardware bundle, so the reported platform agrees with the GPU/`/proc/cpuinfo` the same
  profile carries. Made PURE (no RNG) — a real SoC is a fact of the model, not a draw — which also keeps
  byte-parity trivial. Verified on-device: Moto Z3 Play reports msm8998 across soc_platform, the native
  GPU (Adreno 540), and cpuinfo (MSM8998), all coherent.

### Fixed
- **Thread-safe hardware-dataset cache.** `_load_hardware()` cached the dataset with an unlocked lazy
  read; under concurrent profile generation each thread could parse the 200KB JSON, perturbing timing
  (it surfaced a flaky concurrency test). Now loaded exactly once under a lock (double-checked). No
  behavior change to generated profiles.
- **Per-model hardware-descriptor layer — the profile now carries a coherent hardware bundle
  (GOAL 1.3, data + generation).** A new `data/hardware.json` (built by
  `scripts/build_hardware_dataset.py`) maps each selectable device codename to real, coherent
  hardware descriptors — GPU/GLES renderer, `/proc/cpuinfo`, sensor list, camera list, codec list,
  input devices, core count — grounded in the model's actual SoC (e.g. Pixel 4 -> Adreno 640 /
  SM8150; Galaxy S10e -> Mali-G76 / Exynos 9820). `specter/profile.py` and the Java `Profile.build`
  now inject these 9 flat fields into every generated profile, keyed on the picked device codename.
  They are CONSTANTS (a lookup, no seeded RNG), so byte-parity is preserved by construction; the
  Java `Profile.KEYS` order still matches the Python dict (guarded by the parity test), and a new
  asset-sync test asserts `data/*.json` == the bundled APK assets so the PC and on-device paths can
  never read different data. The Java hooks (`hookHardwareSignals`) return these per-model values
  (GLES version, GPU renderer via GLES20.glGetString, core count, camera ids, sensor relabel); the
  native Zygisk layer inline-hooks `glGetString` for the direct-JNI GPU read; and the existing
  /proc/cpuinfo redirect is now fed by the generated `proc_cpuinfo` key. The verification probe reads
  every descriptor both ways (framework API + a native EGL/GLES read). PROVEN on-device (Pixel 4, two
  identities): a Galaxy Note 9 reports Mali-G72 / Exynos 9810 and a Moto G7 reports Adreno 512 /
  SDM660 — two coherent, DIFFERENT bundles read back on the probe, NOT the real Pixel 4's Adreno 640 /
  SM8150, with 0 hard leaks. Native sensor/camera NDK inline hooks and the FPJS-demo end-to-end
  readout (confounded by the demo's fixed-key server record) remain follow-ups; see docs/IDEAS.md.
- **Zygisk native layer — closes the native read paths (GOAL 1.2).** A new self-contained Zygisk
  companion module (`xposed-module/zygisk/`) that INLINE-hooks libc per-app, from the SAME
  `/data/local/tmp/specter/<pkg>.json` the Xposed module reads (one source of truth). It spoofs:
  (a) system properties via `__system_property_read_callback` AND `__system_property_get`, and
  (b) the factory-reset mtime via `stat`/`lstat`/`fstatat`/`statx`. PROVEN on-device: the dual-read
  probe now shows native == Java (19/19 props spoofed) where before 10/19 leaked the real device.
  This closes the libc blind spot the property probe (PR #7) and the FPJS factoryReset test (PR #8)
  proved Xposed's Java-only hooks could not reach.
  - **Mechanism:** PLT hooking was tried first and does NOT work — bionic's internal
    `__system_property_get`->`__system_property_read_callback` call never goes through libc's PLT, so
    a PLT hook reports a backup yet intercepts nothing (proven on-device). Switched to an INLINE hook
    (vendored And64InlineHook, single-file MIT) — the same class of hook PlayIntegrityFork uses. Must
    be self-contained: ZygiskNext's builtin linker refuses a module with an unresolved external
    `DT_NEEDED` (`open module with builtin linker failed: not preloaded`), so the hooker is compiled in.
  - **Fleet safety (NON-NEGOTIABLE):** a hard denylist in the root companion refuses to serve a profile
    for `com.doordash.driverapp` / `com.dd.doordash` / `com.pyshivam.geergit` / `android` / `system`
    even if a stray profile file exists, so the native hooks can NEVER touch a GeerGit fleet app.
    Verified on-device: only `com.specter.probe` was hooked; no fleet app ever was.
  - Build: `bash build-zygisk.sh` -> `dist/specter-zygisk-v<VERSION>.zip` (flashable module). Logic
    unit-tested via `run-zygisk-tests.sh` (cross-compiled for arm64, run on-device).

### Known limitation
- **Native spoofing did NOT change the FPJS Pro `visitorId` — root cause is unspoofed HARDWARE
  signals, not the native layer or the IP (GOAL 1.2 device-side done; end-to-end not).** With props +
  factory-reset spoofed natively AND via Java, two fully different identities (Motorola kiev ->
  Samsung o1s) STILL returned the same `visitorId` (`confidenceScore 1.0`). Reading the
  fingerprintjs-android SDK source shows why: the Pro visitorId is a server-side FUZZY MATCH over ~50
  signals, and we spoof none of the stable HARDWARE-characteristic ones — `/proc/cpuinfo`, sensor list,
  camera list, GLES/GPU version, codec list, input devices, core count — nor generate any data for them.
  FPJS reads them off the real Pixel 4 unchanged every rotation, so the match locks on. (The IP
  `datacenter_result:true` flag is a separate fraud smart-signal, NOT the identity anchor.) Spoofing the
  hardware signals coherently is the real next step — see GOAL 1.3 / IDEAS.md.

### Changed
- **Device pool filtered to plausible phones (GOAL 2.1).** Generation now excludes tablets/TV boxes
  (Galaxy Tab, Nexus 7/9/10, Nexus Player, Shield, Pixel C) and any device below Android 9 — a fresh
  account claiming a WiFi-only tablet with a SIM + IMEI, or a 2015-era OS, is itself a fingerprint. The
  US-brand pool goes from 173 rows (95 pre-A9, 26 tablets/TV) to 68 real phones on Android 9-12. Filter
  logic (`_is_plausible_phone` / Java `isPlausiblePhone`, floor `MIN_ANDROID_MAJOR = 9`) is mirrored
  byte-for-byte on both sides; this changes the seeded device draw, so it is a byte-parity change —
  RE-PROVEN identical over 300 seeds with the standalone Java-vs-Python dumper.


### Added
- **`factory_reset_epoch` — spoof the FPJS Pro `factoryReset` smart signal.** New generator
  (`factory_reset_epoch`, Java `factoryResetEpoch`) producing a plausible reset time, derived as a
  1..540-day offset from the profile's own `build_security_patch` so the pair is coherent by
  construction — a device cannot be reset before its own OS was built. It reads NO wall clock (a
  code-review catch: an earlier clamp sampled `now()` independently in Python and Java, which would
  silently break byte-parity if it ever fired); "never in the future" is instead enforced by a test
  that fails loudly if the device pool gains a too-recent patch. Byte-parity PROVEN against Python
  across 200 seeds with a standalone Java dumper.
  Appended LAST in the profile dict, so the new draw does not shift any existing field's value.
- **`HookEntry.hookFactoryResetTime`** spoofs the reset time on BOTH Java read paths:
  `java.io.File.lastModified()` AND `android.system.Os.stat/lstat` (rewriting `st_mtime`/`st_ctime`/
  `st_atime` on the returned `StructStat`). Matches an EXPLICIT path set (`isResetMarker`, exact
  equality — never a prefix) so target apps' own file bookkeeping is untouched. Verified on-device:
  all 6 reset-marker dirs return the spoofed time via both paths (real `1773120233` → `1636101883`).
- **Probe: `mtime_*` / `osstat_*` pairs** — reads each reset-marker dir via `File.lastModified` AND
  `Os.stat`, which is what isolated the leak to `Os.stat` after the File-only hook proved insufficient.

### Fixed
- **`media_drm_security_level` was missing from the Java side entirely** (caught by a new
  `test_module_keys_match_python_profile_keys` parity guard). `profile.py` emitted it but Java's
  `Profile.KEYS`/`build()` did not, so a Java-generated profile carried no `L3` field and last
  session's Widevine coherence fix silently did not apply on that path — leaving the exact
  incoherence (a changing DRM id at hardware L1) it was meant to close. Both keys now mirror Python.

### Known-unfixed (measured, not speculation)
- **FPJS Pro still does not rotate.** With both Java read paths provably spoofed in-process (probe
  confirms `Os.stat().st_mtime` → spoofed), FingerprintJS Pro still reports the REAL
  `factoryReset: 1773120233` and the same `visitorId`. It therefore reads the reset time **natively**
  (`stat()` via NDK), the same blind spot already PROVEN for system properties. This makes the root
  `resetprop`/native layer a prerequisite rather than an optional extra — see `docs/GOAL.md` item 1.2.


### Changed
- **Module renamed `com.fleet.idrotate` → `com.specter`** (Java package `com.specter.module`, LSPosed entry
  `com.specter.module.HookEntry`). The old id leaked the internal codename in LSPosed's UI and update
  notifications. On-device this is a migration (LSPosed sees a new module id), so scope is re-established;
  GeerGit's LSPosed module is never touched. `scope_probe.py` updated to the new package.

### Added
- **FPJS Pro lab test run (Test B) — result: the fingerprint does NOT rotate, root cause identified.**
  Applied three fully distinct coherent identities (Google Pixel → Samsung Note 20 Ultra → Nexus 7) to the
  FingerprintJS Pro demo, `pm clear`ing between runs. All three returned the SAME `visitorId` with a fresh
  `eventId` per call, `visitorFound: true`, `confidenceScore: 1.0`, and `firstSeenAt` 17 days earlier.
  Root cause: the `factoryReset` smart signal — a timestamp Specter does not spoof, readable from
  directory mtimes (`/data/misc/profiles`, `/data/bootchart` are readable WITHOUT root; verified the
  reported `1773120233` matches them exactly). Ruled out local persistence (`pm clear`), Keystore-backed
  encrypted prefs (deleted `10302_USRPKEY__androidx_security_master_key_`, ID unchanged), and any
  file dated near `firstSeenAt`. Documented in `docs/IDEAS.md`; the fix is deliberately a separate PR
  (see `docs/DECISIONS.md` for why hooking `File.lastModified` vs root `touch` both need their own review).

- **Native-read blind-spot probe** (`probe/src/main/cpp/native-probe.cpp`, NDK 27 + CMake). A JNI function
  calls libc `__system_property_get` **in-process**, so the probe reads 19 system properties BOTH ways —
  Java `SystemProperties.get` (which Specter hooks) and native libc (which it does not) — and
  `verify_on_device.py`-style comparison shows exactly where the two disagree. `getprop` via exec is a
  false proxy for this (separate, unhooked process); the read must be in-process JNI.
  **Result (PROVEN on-device, Pixel 4):** the Java side returns the spoofed value for all 19 props while
  the native side returns the REAL device value for 10 of them (`ro.product.model` → `Pixel 4`,
  `ro.board.platform` → `msmnile`, `ro.hardware`/`ro.product.board`/`ro.product.device`/`ro.product.name`
  → `flame`, `ro.build.fingerprint` → `google/flame/flame:11/RQ…`, `ro.bootloader`/`ro.boot.bootloader`,
  `gsm.version.baseband`). An NDK-based fingerprinter reading props natively sees the real hardware.
  This is the one axis byedentity's root `resetprop` layer beats us on — see `docs/IDEAS.md`.

- **byedentity 3-way analysis** (`docs/BYEDENTITY-ANALYSIS.md`): decompiled `com.byedentity` v3.0.1 and
  compared GeerGit vs Specter vs byedentity. byedentity is a root/Magisk + native-JNI, server-validated
  changer that spoofs system-wide via `resetprop` + `pm clear` + a Widevine `liboemcrypto.so` bind-mount
  (L1→L3). Findings carry PROVEN/HYPOTHESIS labels (adversarially verified). Adoption candidates in
  `docs/IDEAS.md`; do-not-adopt calls (server/kill-switch/anti-tamper) in `docs/DECISIONS.md`.
- **Probe: Widevine `securityLevel`** — the probe now reads `MediaDrm.getPropertyString("securityLevel")`
  alongside `deviceUniqueId`, and `verify_on_device.py` prints a Widevine-coherence line.

### Fixed
- **`ro.*` property aliases leaked the real device (PROVEN, found by the new dual-read probe).** Specter
  spoofed each `Build.*` field but only 6 property keys (`os.version`, baseband, SoC). Every other Build
  field has a `ro.*` property alias that a fingerprinter can read directly, and those returned the real
  hardware: `SystemProperties.get("ro.product.model")` → `"Pixel 4"` and `("ro.boot.bootloader")` →
  the real bootloader, while `Build.MODEL`/`Build.BOOTLOADER` were correctly spoofed. `HookEntry` now
  dispatches a `PROP_ALIASES` table covering 30 keys (model/brand/manufacturer/device/name/board/hardware/
  bootloader/serialno/fingerprint/id/display/release/incremental/security_patch/host, plus the `vendor.`
  variants) from the SAME profile values as the fields — coherent by construction, consumes no RNG, so
  Java↔Python byte-parity is unaffected. Verified on-device: **19/19 props spoofed, 0 Java-layer leaks**
  (was 2). Note this closes the *Java* path only; native `__system_property_get` still reads real (above).
- **Widevine DRM coherence (no root).** Specter value-spoofed `deviceUniqueId` but left `securityLevel`
  reporting the real **L1** — a *changing* device id at hardware-L1 is itself a fingerprint. Confirmed
  on-device (Pixel 4: spoofed id @ L1), then fixed: `profile.py` emits `media_drm_security_level: "L3"`
  (software Widevine, where a changing id is coherent) and `HookEntry` hooks
  `getPropertyString("securityLevel")` to return it. Re-verified coherent on-device (@ L3). The value is a
  constant → consumes no RNG → Java↔Python byte-parity unchanged. Achieves byedentity's L1→L3 outcome
  without its root `liboemcrypto` bind-mount.
- **StatFs storage leak closed + RAM/storage made coherent (device-linking signal).** `total_storage` was
  generated but never injected, so real internal storage leaked — a stable value that links accounts. Added
  a coherent `StatFs` hook (`getTotalBytes` and `getBlockCountLong`×`getBlockSizeLong` multiply to the same
  spoofed total; available/free ~35-55%). Also replaced the independent RAM and storage draws with a single
  coherent pair (`ram_storage_bytes`): storage is derived from the chosen RAM tier, so an incoherent combo
  (e.g. 12GB RAM + 32GB storage) can no longer occur. Java↔Python byte-parity re-proven; verified on-device.
- **Brand-plausible serial format (was detectably synthetic).** `serial` was `hex16upper` — 16 pure-hex
  chars, but a real Pixel serial is 14 alphanumeric incl non-hex letters (`9B151FFAZ00FPF`) and a Samsung
  is `R`+10 (11 chars). Added `serial_for_brand` (Base34 alphabet, brand prefix + correct length for
  Samsung/Google/Motorola/LGE). Java↔Python byte-parity proven; verified on-device (Pixel profile →
  `A6X71GDYHX9WC3`). A device claiming to be a Pixel no longer reports an impossible pure-hex serial.

## [0.3.0] — 2026-07-08

UI/UX polish + real multi-app targeting, per-country SIM, and realistic emails. The app now
carries one name (Specter), a logo, and the warm-dark charcoal theme.

### Added
- **Multi-app targeting**: an app picker (PackageManager) with a Show-system-apps toggle
  (user apps by default), search, Select/Deselect all, and multi-select. APPLY writes the
  profile to every selected target. Fleet-safety warning on Dasher/system packages.
- **Per-country SIM**: USA + UK, with an extensible Country structure (carriers, phone format,
  ICCID IIN, brand bias). Settings has a country picker; UK generates EE/O2/Vodafone/Three
  carriers + +44 7 phone numbers. `specter --country` on the CLI.
- **Realistic emails**: real first/last names in common patterns (first.last, firstlast+year, …)
  across gmail/outlook/yahoo/hotmail/icloud, replacing the old random-letter `xxxx###@gmail.com`.
  (GeerGit's own 'normal emails' are server-side and byte-identical across its versions — this is
  an independent implementation, not a port.)
- **Per-identifier on/off toggles**: each id card has a switch; disabled ids are omitted from the
  applied profile so the hook leaves them REAL (GeerGit's *_switch parity).
- **Branding**: gold ghost logo (vector) as header mark + adaptive launcher icon; charcoal/gold
  theme via a single Theme token class; version shown small next to the wordmark.

### Fixed
- **Tab active-state**: switching Identity/Settings/Location now highlights the active tab (gold).
  Previously the tab bar was built once and never re-tinted.
- One name only: the LSPosed module + app label is **Specter** (was 'Fleet ID Rotate').

### Notes
- Java + Python generation stay byte-parity (same seeded emails/phones). JVM 44,063 asserts +
  Python 75 tests green.
- Deferred (2.9.7-beta parity, later): Hide Airplane Mode, Randomize Battery Level, Spoof Battery
  Cycle Count, i18n, profile-transfer (server feature).

## [0.2.0] — 2026-07-08

Pivot from a PC-tethered CLI to a **standalone, no-PC Android app**: Specter now generates
identities on-device and self-applies them via Magisk `su`, with a native 3-tab UI — the same
package is BOTH an LSPosed module and a launchable app (like GeerGit). Proven end-to-end on a
real Pixel 4 against the DevInfo test app.

### Added
- **Standalone Android app** (`com.fleet.idrotate.ui.MainActivity`): on-device identity
  generation + native Identity/Settings/Location UI (RANDOMIZE ALL, per-card EDIT/RANDOMIZE,
  APPLY). No PC required.
- **On-device generation core** ported from the Python reference to pure Java
  (`gen/Generators`, `gen/Profile`, `gen/UsedStore`) — byte-parity with Python at a fixed seed;
  34k+ JVM assertions.
- **`RootWriter`**: writes the profile to `/data/local/tmp/specter/<pkg>.json` via `su`
  (shell-injection-guarded, JSON via stdin), fail-loud on root denial.
- **`IdentityService`**: bundled 499-device asset, on-device no-reuse ledger in app-private
  storage (fail-closed on corruption), thread-safe generate/randomize.
- PC TUI upgraded to a questionnaire menu (questionary + rich fallback); `specter --version`.
- Version surfaced everywhere: app header, TUI header, APK badging, `VERSION` single-source file.

### Fixed
- **android_id + advertising_id now actually reach the target app.** Prior builds spoofed
  Build.*/serial/GSF but leaked the REAL android_id and ad id (proven via DevInfo + two
  dexdumps). Now hook all `Settings.Secure`/`System` getString overloads and the
  `AdvertisingIdClient` static factory.
- **Ban-critical**: per-field RANDOMIZE now records to the no-reuse ledger (a randomized-then-
  applied id could previously be reissued to another account).
- Ledger thread-safety (static lock), fail-closed persistence (checked `renameTo`), empty-
  profile guards on APPLY/EDIT/RANDOMIZE (an empty APPLY would leak real ids), missing-key
  validation, and a data race on the shared profile map.

### Changed
- Fleet-safety: CLI/TUI/verify default target is now DevInfo (`com.liuzh.deviceinfo`), never a
  real fleet app. The Python CLI/TUI is retained as the trusted spec + dev tool.

### Known / out of scope
- Fingerprint Pro (`com.fingerprintjs.android.fpjs_pro_demo`) re-identifies at 100% confidence
  after a full rotation — it fingerprints via hardware/sensor/IP signals this module does not
  hook. Deprioritized to a later stretch goal; GeerGit identifier-level parity is the bar and is met.

## [0.1.0] — 2026-07-08

First complete release: builds, installs, push-verified on device; 73 tests; 6 review passes applied.

### Added
- Hook coverage hardening: ContentProviderClient.query, cursor getBlob + copyStringToBuffer.
- IMEI TAC coherence (brand-plausible TAC, shared across dual-SIM IMEIs).
- Fail-closed used-id ledger (quarantine + refuse on corruption).
- Build.VERSION.* spoofing; Gservices.getLong; executable JVM tests for hook logic.
- CI workflow; release builder (dist zip); dual-OS launchers.
- Polished README (feature table, quality section, project layout).
- Deepened `verify` questionnaire: pre-flight device/module summary, module-active detection,
  per-check error isolation, and a final results summary table.
- RFC-4122 v4 advertising IDs; GSF clamped to Java Long.MAX; slot-aware IMEI; GSF cursor getLong.
- Core identity generator: coherent, US-biased device profiles from a 499-device DB.
- Per-identifier generators with validators (Luhn IMEI/ICCID, MAC bits, 19-digit GSF).
- Global used-id ledger — no identifier is ever reused across signups (the anti-ban core).
- Named profile vault: save / list / reuse identities (backup a good one, reload later).
- `device.py` adb layer: push profile, clear app, read live identifiers + hook log.
- CLI: `new` · `push` · `rotate` · `list` · `show` · `stats` · `verify` · `tui`.
- Rich TUI dashboard (light/dark safe): active identity, vault, issued-ledger, device status.
- **Deep on-device verification harness** (`verify.py`, questionnaire-driven): coverage,
  rotation (launch N×, confirm fresh identity each), backup/reload round-trip, leak audit.
- LSPosed module (`xposed-module/`) hooking the full identifier surface incl. GSF.
- `scripts/compare_with_geergit.py`: on-device coverage + rotation comparison vs GeerGit.
- 49 tests: generators, coherence, uniqueness, GeerGit parity, device, CLI, TUI, verify, module parity.

### Context
- Built after diagnosing GeerGit 2.9.6's GSF-rotation regression (`docs/GEERGIT-2.9.6-REGRESSION.md`)
  that reused a stale fake GSF across signups → DoorDash coordinated-account bans.
