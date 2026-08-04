# Session Handoff: IP reputation / blacklist / cleanliness checker in Specter
Created: 2026-08-05

---

## Goal
Add an **exit-IP reputation / blacklist / "cleanliness" check** to Specter, extending the existing
**Network-exit status card** (which today shows: public exit IP, ISP, city/state, and timezone-vs-IP
alignment). The card should also report how the current proxy exit IP looks to fraud/abuse data sources —
fraud score, proxy/VPN/Tor verdict, and how many DNS blacklists it's on — so a bad-reputation proxy is
visible *before* it causes login friction.

## Why (motivating finding, this session)
The resi proxy's exit IP (`172.59.84.16`, US/Kentucky, mobile) checked on **iper.one** scored **IPQS fraud
= 92** and **"Found in 6 blacklists"**, while Maxmind (2.82), Scamalytics (low/8), Getipintel (0), and Iper
risk (0) were clean. A high IPQS/blacklist reputation is a plausible cause of Cash/fintech login friction
**independent of the device fingerprint** — the device can be perfectly coherent and still get flagged on a
dirty IP. Specter currently shows IP + geo + TZ but is blind to reputation; this closes that gap.

## Goal Clarifications
- **Focus is Specter first.** The user also floated "this would be good as a quick webapp / tiny tkinter
  desktop app, or built into another of my management projects" — noted as a FUTURE/parallel option, NOT
  this task. Build it in Specter now. (If reused later, factor the lookup logic into a shared module — see
  Key Decisions.)
- Extend the **existing** Network-exit card, don't build a separate screen.

## User Emphasis (IMPORTANT)
- ⚠️ **Focus on Specter for now** — the standalone webapp/tkinter idea is a later maybe, don't get pulled into it.
- ⚠️ The check must reflect the **proxy EXIT IP the apps actually egress**, i.e. run it **through the tunnel**
  — Specter already does exactly this for geo (see DO NOTs). Never let a reputation lookup hit the home IP.

## Current State
- **Status:** not started (research + integration point + branch ready).
- **Branch:** `feat/ip-reputation-checker` — created off `main`, currently checked out, clean tree (only
  untracked `APKs/`). **PR #45 (iOS port) was squash-merged to main this session** (commit `bcd75e6`).
- **What's done:** API research (below), located the exact code to extend, this handoff.
- **What's pending:** everything in Next Action.

## Key Decisions
- **Data sources (all have usable free tiers):**
  | Source | Free tier | Gives | Key? |
  |---|---|---|---|
  | **IPQualityScore** (user has a free acct) | 1,000/mo · **35/day** | `fraud_score`, `proxy`/`vpn`/`tor`, `recent_abuse`, `bot_status`, connection_type, ASN/ISP | yes (dashboard) |
  | **AbuseIPDB** | 1,000 checks/day, free forever | `abuseConfidenceScore`, `totalReports`, `usageType`, ISP | yes (free) |
  | **DNSBL direct** (Spamhaus zen, Barracuda, SpamCop, SORBS…) | keyless, ~unlimited | the exact **"found in N blacklists"** count | **no key** |
  | proxycheck.io (optional 2nd opinion) | 1,000/day | proxy/vpn/tor/hosting + risk | free key |
- **Primary = IPQS** (it's the signal that flagged the proxy at 92). **Blacklist count = DNSBL** (keyless,
  matches the iper.one "N blacklists" line). AbuseIPDB = abuse-history reputation. proxycheck optional.
- **Reuse the existing tunnel-pinned lookup pattern** in `HealthCheck.java` — do NOT invent a new network
  path. The reputation lookups go through the same `TRANSPORT_VPN` `Network` handle as `lookupGeo(net)`.
- **API keys live in Specter settings (SharedPreferences)** — add fields for the IPQS (and AbuseIPDB) keys;
  never hardcode. If no key is set, gracefully show only the keyless DNSBL blacklist count.
- **IPQS free is 35/day** → don't auto-poll; check on-demand (button/refresh) and cache the last result per
  IP so re-opening the card doesn't burn quota.
- If reused as a standalone app later: put the raw lookup logic in a small **Python** module under
  `specter/` (mirrors the Java) so a tiny CLI/tkinter/webapp can import it — but that's future, not now.

## Files to touch (integration point, verified this session)
- `xposed-module/app/src/main/java/com/specter/module/ui/HealthCheck.java`
  - Model: `static final class Geo { String ip, city, region, country, tz, isp; }` (line ~320) — add a
    parallel `static final class Reputation { Integer fraudScore; Boolean proxy, vpn, tor; Integer
    abuseConfidence; int blacklistHits; List<String> blacklistNames; ... }`.
  - Fetch: `static Geo lookupGeo(android.net.Network net)` (line ~336) — HTTP GET pinned to `net`
    (`net.openConnection(u)`), 5s timeouts, best-effort null-on-fail. **Copy this exact shape** for
    `lookupReputation(android.net.Network net, String ip, keys...)`: call IPQS
    `https://ipqualityscore.com/api/json/ip/<KEY>/<ip>`, AbuseIPDB `https://api.abuseipdb.com/api/v2/check`
    (Key header), and DNSBL via `net.getAllByName("<reversed-ip>.zen.spamhaus.org")` etc. (listed = resolves).
  - VPN gate: `vpnNetwork()` / the TRANSPORT_VPN helper (~line 272-284) returns the tunnel `Network` or null.
- `xposed-module/app/src/main/java/com/specter/module/ui/MainActivity.java`
  - Network-exit card (`line ~2277`, "the public IP big, ISP, location + timezone, and a transport…") — add
    reputation rows (Fraud score, Blacklists N, Proxy/VPN verdict), color-coded like the existing TZ verdict
    (green/red). Add a small "check reputation" refresh action if you don't want it auto-fetched on open.
  - Add settings fields for the IPQS/AbuseIPDB API keys (wherever the app keeps prefs).
- Docs to update on completion (project rule): `CHANGELOG.md` (CRLF-committed — byte-level edit),
  `docs/IDEAS.md` (already has a line for this — mark shipped), `docs/DECISIONS.md` (why IPQS+DNSBL).

## Active PRs
- **PR #45** "Specter-iOS: feasibility, deep-dive, coherence engine + tracer" — **MERGED** to main
  (squash `bcd75e6`), branch deleted. User chose merge-now / skip-gauntlet (their call; gauntlet was offered).
- New work goes on **`feat/ip-reputation-checker`** → new PR.

## DO NOTs & Constraints
- ❌ **DO NOT run the reputation lookup un-pinned.** It MUST go through the `TRANSPORT_VPN` `Network` (like
  `lookupGeo(net)`), or be gated on being on a proxy/VPN — else it checks the **home IP**, which both leaks
  the real IP to the API and reports the wrong reputation. This is the same safety gate the TZ feature uses.
- ❌ **DO NOT hardcode API keys** — SharedPreferences fields; degrade to keyless DNSBL if unset.
- ❌ **DO NOT auto-poll IPQS** — 35 lookups/day free. On-demand + cache per IP.
- ⚠️ Network calls are blocking/best-effort and must never throw or block the UI thread (match the existing
  off-thread pattern around the geo card).
- ⚠️ EOL discipline: `CHANGELOG.md` + the CRLF-pinned files use byte-level edits; `.java`/`IDEAS.md` are LF.
- Version-bump everywhere (VERSION drives it) when shipping, per project workflow.

## Relevant Artifacts
Existing geo fetch to mirror (`HealthCheck.java` ~336):
```java
static Geo lookupGeo(android.net.Network net) {
    java.net.URL u = new java.net.URL("https://ipwho.is/");
    HttpURLConnection c = (HttpURLConnection)(net != null ? net.openConnection(u) : u.openConnection());
    c.setConnectTimeout(5000); c.setReadTimeout(5000);
    // ... read JSON, parse ip/city/region/country/timezone.id/connection.isp, null on any failure
}
```
iper.one reference readout that motivated this: IP 172.59.84.16 · IPQS fraud **92** · **6 blacklists** ·
Maxmind 2.82 · Scamalytics low/8 · Getipintel 0 · connection Mobile.

## Next Action
1. `git branch --show-current` → confirm `feat/ip-reputation-checker`.
2. Read `HealthCheck.java` `lookupGeo` + the `Geo` class + `vpnNetwork()` gate, and `MainActivity.java`
   around line 2277 (the Network-exit card).
3. Add `Reputation` model + `lookupReputation(net, ip, ipqsKey, abuseKey)` in `HealthCheck.java`, copying
   the tunnel-pinned pattern. Start with **DNSBL (keyless) + IPQS**; AbuseIPDB/proxycheck next.
4. Add IPQS/AbuseIPDB key fields to settings; wire reputation rows into the Network-exit card (color-coded,
   on-demand refresh, cache per IP).
5. Build (see CLAUDE.md build steps), verify on a test device with the proxy up, update CHANGELOG/IDEAS/
   DECISIONS, open a PR on `feat/ip-reputation-checker`.

---

## Resume Instructions

To continue this work in a fresh session:

```
Read handoffs/2026-08-05_ip-reputation-checker.md and resume the work.

CRITICAL:
- Check "User Emphasis (IMPORTANT)" first — these are things I had to repeat.
- Check "DO NOTs & Constraints" to avoid regressions.
- Start with "Next Action".
```
