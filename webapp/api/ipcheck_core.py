"""Exit-IP reputation checker — the same lookups Specter runs on-device, standalone.

Point it at a proxy and it reports how that exit IP looks to fraud/abuse data sources: an
IPQualityScore fraud score, AbuseIPDB abuse history, and the keyless DNSBL blacklist count.
Useful for vetting a proxy *before* assigning it, without touching a phone.

    python -m specter.ipcheck                                   # this machine's exit IP
    python -m specter.ipcheck --proxy host:port:user:pass       # or user:pass@host:port, or a URL
    python -m specter.ipcheck --proxy 10.0.0.1:1080 --proxy-type socks5
    python -m specter.ipcheck --ip 172.59.84.16                 # an IP directly, no proxy needed
    python -m specter.ipcheck --serve                           # local web UI, opens a browser
    python -m specter.ipcheck --json                            # machine-readable

Proxies are parsed leniently — `host:port`, `host:port:user:pass`, `user:pass@host:port`, or a
`http://`/`socks5://`/`socks4://` URL — and SOCKS5/4a are tunnelled with stdlib only (no PySocks).

API keys are optional (the blacklist count is keyless). They are read from --ipqs-key /
--abuse-key, then the env (IPQS_KEY / ABUSEIPDB_KEY), then ~/.specter-ipcheck.json. Never
committed, never hardcoded.

Stdlib only — no dependencies. Mirrors the Android side (HealthCheck.java + Dnsbl.java) so a
finding here means the same thing as a finding on the phone.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import socket
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import NamedTuple

CONFIG = Path.home() / ".specter-ipcheck.json"
TIMEOUT = 8
# The budget for the RETRY of a proxy's liveness probe, after the normal one ran out of time. Generous on
# purpose: MEASURED 2026-08-07, five live lightningproxies SOCKS5 endpoints answered in ~800 ms once warm
# but took 13-19 s each on a cold concurrent hosted run, so an 8 s retry would have failed for the same
# reason the first attempt did and reported five working proxies as dead. Only ever paid once, and only by
# a proxy that already missed the fast path.
SLOW_TIMEOUT = 24
# Soft ceiling on ONE check()'s wall clock. Not a hard kill — it only stops the OPTIONAL late work (the
# IPv4 pin's endpoint walk, the direct-latency baseline) from starting when there is no time left for it.
# The arithmetic it exists for: a bare `host:port` line can spend TIMEOUT on the liveness probe, TIMEOUT
# again retrying it as the other transport, SLOW_TIMEOUT on the slow retry, and then, if the exit is
# dual-stack, 3xTIMEOUT walking the v4 chain plus another TIMEOUT re-measuring geo. That is ~80s, and the
# hosted checker's function cap is 60 — so the worst case threw away a result that had ALREADY succeeded.
# Losing the v4 pin is a much smaller loss than losing the whole report.
CHECK_BUDGET = 45

# The blocklists behind the "found in N blacklists" count — keyless, no quota, no account. Every
# zone here was verified live (queried for 127.0.0.2, which each one lists by convention). SORBS is
# deliberately absent: it shut down in 2024 and now answers "not listed" for everything, which reads
# as a silent all-clear. Same table as xposed-module/.../ui/Dnsbl.java; keep the two in sync.
DNSBL_ZONES = [
    ("Spamhaus", "zen.spamhaus.org"),
    ("CBL", "cbl.abuseat.org"),
    ("Barracuda", "b.barracudacentral.org"),
    ("SpamCop", "bl.spamcop.net"),
    ("UCEPROTECT", "dnsbl-1.uceprotect.net"),
    ("blocklist.de", "bl.blocklist.de"),
    ("PSBL", "psbl.surriel.com"),
    ("DroneBL", "dnsbl.dronebl.org"),
    ("SpamRATS", "all.spamrats.com"),
    ("GBUdb", "truncate.gbudb.net"),
    ("InterServer", "rbl.interserver.net"),
    ("s5h", "all.s5h.net"),
    # Added 2026-08-05 to close a coverage gap (an IP that reads 1 here showed 6 on iper.one). Each verified
    # live + keyless (lists 127.0.0.2, clean on 8.8.8.8). The first three are per-IP abuse lists; UCEPROTECT
    # L2/L3 are netblock/ASN listings (a /24 or whole ASN with spam history) — real signal that an IP sits in
    # a burned network, but broad, so they're POLICY (shown, not folded into the per-IP abuse verdict).
    ("0SPAM", "bl.0spam.org"),
    ("SpamEatingMonkey", "bl.spameatingmonkey.net"),
    ("Backscatterer", "ips.backscatterer.org"),
    ("UCEPROTECT-L2", "dnsbl-2.uceprotect.net"),
    ("UCEPROTECT-L3", "dnsbl-3.uceprotect.net"),
]

# A DNSBL answers in 127.0.0.0/8 and the LAST OCTET says why. Most of them mean abuse, but a few
# are pure POLICY listings — "nothing here should be sending mail directly" — which every
# residential and mobile address carries by design. Counting those as abuse would mark every resi
# proxy dirty, so they're tracked separately and kept out of the verdict.
#
# The code is worth spelling out rather than collapsing to the zone name. Spamhaus splits PBL into
# 127.0.0.10, an entry the network owner declared themselves, and 127.0.0.11, one Spamhaus added
# because the owner never did (docs.spamhaus.com, Available Zones). Every consumer line carries the
# first; a hosting range carries the second only when Spamhaus decided that range shouldn't be
# emitting mail — which is a statement about the netblock, not the routine consumer case, and is
# exactly what a proxy is being vetted for.
POLICY_CODES = {
    "zen.spamhaus.org": {
        10: "PBL, network owner declared it end-user",
        11: "PBL, Spamhaus listed the range",
    },
    "all.spamrats.com": {36: "dynamic reverse DNS", 37: "no reverse DNS"},
    # UCEPROTECT L2/L3 list a whole /24 or ASN when someone in it spams — a netblock characteristic, not
    # per-IP abuse (a clean IP inherits the listing from a noisy neighbour). Their listing code is 127.0.0.2,
    # so it's mapped here to keep it OUT of the per-IP abuse count while still showing WHY.
    "dnsbl-2.uceprotect.net": {2: "/24 netblock listed, a neighbour spammed"},
    "dnsbl-3.uceprotect.net": {2: "ASN listed, spam elsewhere in the network"},
}

# IPQualityScore's own scoring strictness, sent on every lookup. MEASURED on 23.159.216.252 (a
# Mullvad exit, AS17243) on 2026-08-05: strictness 0 returns fraud_score 20 with proxy=false —
# blind to a commercial VPN exit — while strictness 1 returns 100 with proxy, recent_abuse and
# bot_status all true. Strictness 2 matches 1. IPQS documents 0 as the recommended starting point,
# but 0 cannot answer the only question this tool asks, so 1 it is. The readout names the setting
# because the same IP scores differently elsewhere and a reader needs to be able to reconcile that.
IPQS_STRICTNESS = 1

# Scamalytics v3. api11 is the US node; EU accounts answer on api12 — an account is BOUND to the node
# picked at signup, so this is not a failover pair.
SCAM_HOST = "api11.scamalytics.com"

# getIPIntel returns a NEGATIVE `result` instead of a 0-1 probability when the query failed, always with
# HTTP 200 — so the code is the only signal that a "score" isn't one. Spelled out rather than printed raw:
# a bare "error -5" sends the reader to the docs, and -5 in particular is the one worth acting on (the
# CONNECTING IP is banned or over quota, which is a property of where you ran this, not of the IP checked).
# Meanings from getipintel.net's API page, -2/-3/-6 confirmed live 2026-08-05.
GETIPINTEL_ERRORS = {
    -1: "invalid query",
    -2: "invalid IP address",
    -3: "unroutable or private address",
    -4: "database unreachable — mid-update",
    -5: "over quota from here — this IP wasn't checked",
    -6: "no valid contact address",
}
# -5 and -6 are the two that another contact address might get past: getIPIntel meters per contact as well
# as per connecting IP, so a second address is worth one attempt. The rest are verdicts about the QUERY
# (bad IP, private range) and retrying them with a different contact would just burn quota.
GETIPINTEL_RETRYABLE = (-5, -6)

# ponytail: Spamhaus and CBL refuse queries relayed by large public resolvers (they answer
# 127.255.255.254), which is what a DoH lookup looks like to them — so those two report "blocked"
# rather than a listing. Upgrade path if their coverage is wanted: a free Spamhaus DQS key and the
# private <key>.zen.dq.spamhaus.net zone, which answers from anywhere. The other ten answer fine.

# IPQS boolean verdicts worth showing, in the order they matter. active_* means the anonymising
# service is live on that IP right now, not merely that it was one historically.
IPQS_FLAGS = [
    ("tor", "Tor"),
    ("active_tor", "Tor (active)"),
    ("vpn", "VPN"),
    ("active_vpn", "VPN (active)"),
    ("proxy", "Proxy"),
    ("recent_abuse", "Recent abuse"),
    ("frequent_abuser", "Frequent abuser"),
    ("high_risk_attacks", "High-risk attacks"),
    ("bot_status", "Bot"),
    ("is_crawler", "Crawler"),
    ("security_scanner", "Security scanner"),
    ("shared_connection", "Shared connection"),
    ("mobile", "Mobile"),
]


# ---- pure logic (unit-tested; no network) --------------------------------------------------


# The zones that actually hold NATIVE IPv6 data. Only four of the seventeen do — MEASURED 2026-08-05
# against 60 live IPv6 Tor exit relays: s5h listed 39, Spamhaus 24, CBL 14, DroneBL 5, and every other
# zone listed ZERO of them. Querying the other thirteen over IPv6 spends thirteen lookups to learn
# nothing AND inflates the denominator, turning "0 of 17" into a false all-clear on an address no list
# could ever have flagged.
#
# Do NOT probe these with `::ffff:7f00:2` to decide support: rbldnsd's RECOGNIZE_IP4IN6 rewrites a
# mapped-IPv4 query into the plain IPv4 lookup, so Spamhaus/CBL/DroneBL answer it whether or not they
# hold any IPv6 data at all. (That aliasing is also why an earlier probe with `2001:db8::2` — a
# documentation range nothing lists — wrongly read as "no zone supports IPv6".)
#
# Listing granularity differs and the verdict is weaker than the IPv4 one: Spamhaus, CBL and s5h list
# /64 PREFIXES, so a clean address can sit in a listed /64 and a listed address may never have sent
# anything itself. DroneBL lists exact /128s.
DNSBL_ZONES_V6 = [
    ("Spamhaus", "zen.spamhaus.org"),
    ("CBL", "cbl.abuseat.org"),
    ("s5h", "all.s5h.net"),
    ("DroneBL", "dnsbl.dronebl.org"),
]


def reverse_v6(ip: str | None) -> str | None:
    """``2001:db8::1`` -> the 32-nibble reversed query name RFC 5782 §2.4 requires. None unless it parses
    as IPv6. Query the full /128 even though zen/CBL/s5h list /64s — DNS resolves the nibble tree from the
    most significant end, so a /64 listing is matched by any address beneath it for free."""
    if not ip or ":" not in ip:
        return None
    try:
        packed = socket.inet_pton(socket.AF_INET6, ip)
    except (OSError, ValueError):
        return None
    return ".".join(reversed("".join(f"{b:02x}" for b in packed)))


def reverse_v4(ip: str | None) -> str | None:
    """``1.2.3.4`` -> ``4.3.2.1``, the DNSBL query form. None unless it's a dotted-quad IPv4
    address — IPv6 DNSBL needs nibble-format queries and few of these zones serve them."""
    if not ip:
        return None
    parts = ip.split(".")
    if len(parts) != 4:
        return None
    for p in parts:
        if not p.isdigit() or len(p) > 3 or int(p) > 255:
            return None
    return ".".join(reversed(parts))


def listed(addr: str | None) -> bool:
    """True iff a resolved answer is a real listing. Answers live in 127.0.0.0/8 with the last octet
    >= 2 — 127.0.0.1 is the "zone is alive, this IP isn't on it" reply some zones give, and
    127.255.255.x is Spamhaus's ERROR range (query via a public/open resolver, or a blocked
    account). Counting either would report clean IPs as blacklisted."""
    if not addr or not addr.startswith("127.") or addr.startswith("127.255.255."):
        return False
    parts = addr.split(".")
    return len(parts) == 4 and parts[3].isdigit() and int(parts[3]) >= 2


def classify(zone: str, addrs: list[str]) -> str | None:
    """What a zone's answers mean for this IP: "abuse", "policy" (a dynamic/consumer-range listing
    every residential IP carries), "blocked" (the zone refused the query), or None when it answered
    and this IP simply isn't listed. Abuse wins when both are present — a mobile IP is always on
    PBL, so only the SBL/XBL codes alongside it are news."""
    if any(a.startswith("127.255.255.") for a in addrs):
        # Spamhaus and CBL answer this to queries relayed by large public resolvers. It is a
        # refusal, not a clean result — counting it would turn "we don't know" into "it's fine".
        return "blocked"
    hits = [a for a in addrs if listed(a)]
    if not hits:
        return None
    policy = POLICY_CODES.get(zone, {})
    return "policy" if all(int(a.split(".")[3]) in policy for a in hits) else "abuse"


def policy_reasons(zone: str, addrs: list[str]) -> list[str]:
    """What a zone's policy listing actually says, in words, in ASCENDING CODE order — not the order
    the addresses arrived (DNS makes no ordering guarantee). The Java twin sorts a TreeSet of the hit
    codes for exactly this reason; the two must render the same multi-code answer identically."""
    known = POLICY_CODES.get(zone, {})
    hit = sorted({int(a.split(".")[3]) for a in addrs if listed(a)})   # set() dedups, like the TreeSet
    return [known[c] for c in hit if c in known]


def policy_label(name: str, zone: str, addrs: list[str]) -> str:
    """A policy listing as one display string: the zone, and why it lists this IP."""
    why = policy_reasons(zone, addrs)
    return f"{name} ({'; '.join(why)})" if why else name


# Hosting/datacenter fingerprints in the ISP / org / reverse-DNS host. Whether an exit is a datacenter or a
# real residential/mobile line is the single strongest signal for whether a proxy survives a strict app's
# checks — real users don't originate from AWS/OVH, so those exits draw the most friction. It's free to read
# from the ISP/org/host names ipwho.is + IPQS already return (IPQS's own connection_type is premium-gated).
# ponytail: name-based heuristic with a known ceiling — it catches the major hosts by name, not every hosting
# ASN. Upgrade path if it matters: a datacenter-ASN dataset. Unknown names stay unclassified, never guessed.
# GCP and Azure don't self-identify as "cloud" in free WHOIS — they read "Google LLC" / "Microsoft
# Corporation" — so those org strings are matched (and the compute reverse-DNS hosts googleusercontent /
# cloudapp). "microsoft" is safe — no residential ISP is named that.
#
# `google(?!\s+fiber)`, NOT `google\s+llc`: MEASURED 2026-08-06, ipwho.is returns the bare names "Google"
# and "Cloudflare" where IPQS returns "Google LLC", so 8.8.8.8 and 1.1.1.1 both read verdict CLEAN with
# "No datacenter signal" — a false all-clear on two of the most obvious datacenter addresses there are.
# The negative lookahead keeps Google Fiber ("Google Fiber Inc", a real residential ISP) unmatched, which
# is what the old `google\s+llc` was protecting. cloudflare/fastly added for the same reason: no
# residential product carries those names, so they cannot cause the Google Fiber problem.
_DATACENTER_RE = re.compile(
    r"\b(amazon|aws|ec2|amazonaws|google(?!\s+fiber)|gcp|googleusercontent|azure|microsoft|cloudapp|"
    r"cloudflare|fastly|digitalocean|linode|akamai|vultr|choopa|ovh|hetzner|contabo|leaseweb|m247|datacamp|"
    r"hostwinds|scaleway|oracle\s+cloud|alibaba|tencent|quadranet|psychz|nforce|serverius|frantech|buyvm|"
    r"colocrossing|hosting|datacenter|data\s?center|colocation|colo|dedicated\s+server|virtual\s+server|"
    r"cloud\s+server)\b", re.I)


# ip2proxy's taxonomy for "this is not a real line". PUB/WEB/SES bucket into `datacenter` because the
# friction at a strict app is identical; the exact code renders on its own detail row, so the coarse
# bucket never hides what was actually measured. Mirrored in HealthCheck.SCAM_DC_TYPES (pinned by a test).
_SCAM_DC_TYPES = {"DCH", "VPN", "PUB", "WEB", "SES"}


def _scam_dc(rep: dict) -> bool:
    """Scamalytics says hosting/proxy — the classifier half of the integration, and the half that earns it."""
    return bool(rep.get("scam_datacenter")) or rep.get("scam_proxy_type") in _SCAM_DC_TYPES


# getIPIntel's near-certain verdict, used as a datacenter signal of LAST resort.
#
# MEASURED: getIPIntel grades residential-vs-hosting rather than flagging every proxy — AWS 1.0, Starlink
# 0.0 — which is exactly the discrimination `connection_class` needs and neither of the other two had.
# It closes a real gap: Mullvad's exit ISP "Byte Node LLC" matches nothing in _DATACENTER_RE, and
# Scamalytics reported it `is_datacenter false` with no ip2proxy record, so a known commercial VPN exit
# rendered "unclassified". getIPIntel called it 1.00.
#
# 0.99 and not 0.90: at 0.99 it already earns a DIRTY on its own in verdict_factors (that threshold was
# chosen from the same measurements), so this adds no new verdict, only the NAME of what the exit is.
# Anything looser would start classifying residential proxies as hosting on a probability, which is the
# one direction this tool must not guess in.
_GII_HOSTING = 0.99


def _gii_dc(rep: dict) -> bool:
    gii = rep.get("getipintel_score")
    return gii is not None and gii >= _GII_HOSTING


def _ipapi_dc(rep: dict) -> bool:
    """ip-api.com's `hosting` boolean — a KEYLESS datacenter signal. Measured 2026-08-06: hosting=true on
    Google/Cloudflare/OpenDNS and on the 31173 VPN range, false on residential; it lets a no-key user get a
    real exit-type verdict where before connection_class returned None (only the name regex + getIPIntel)."""
    return bool(rep.get("ipapi_hosting"))


def connection_class(rep: dict) -> str | None:
    """"tor" / "mobile" / "datacenter" / None. None = couldn't tell — deliberately not guessed
    "residential", since "not obviously a datacenter" is all a name heuristic can honestly claim.

    Scamalytics' classifier is consulted BEFORE the name regex: measured, it caught all four hosting IPs
    the regex missed (including Mullvad's "Byte Node LLC") and stayed quiet on all four real residential
    exits. Tor is checked first because a Tor exit also reads is_datacenter, and "Tor" is the more useful
    claim. `mobile` keeps its precedence over the datacenter signal — no measured case conflicts."""
    if rep.get("scam_tor"):
        return "tor"
    if rep.get("mobile"):
        return "mobile"
    blob = " ".join(str(rep.get(k) or "") for k in ("isp", "organization", "host")).strip()
    if _scam_dc(rep) or (blob and _DATACENTER_RE.search(blob)) or _gii_dc(rep) or _ipapi_dc(rep):
        return "datacenter"
    return None


def is_datacenter(rep: dict) -> bool:
    return connection_class(rep) == "datacenter"


def flags(rep: dict) -> list[str]:
    """The IPQS verdicts that are true, as display labels."""
    return [label for key, label in IPQS_FLAGS if rep.get(key)]


_VERDICT_LEAD = {"dirty": "High friction — ", "suspect": "Some risk — "}


def verdict_factors(rep: dict) -> tuple[str, list[str]]:
    """(level, the individual signals that decided it) from the collected signals. level is
    clean / suspect / dirty / unknown.

    The factor LIST, not a joined sentence, is the real output — the UI shows each signal as its own
    line under the verdict, which is the whole answer to "what makes this suspect?". ``verdict()``
    joins the same list for the terminal readout.

    The verdict answers "how much friction will this exit draw at a strict app", not "is this a mail spammer".
    Two facts shape it: (1) a DATACENTER/hosting exit is the strongest negative — strict apps expect real users,
    who don't originate from AWS/OVH; (2) IPQS's fraud_score is NOT the decider — it scores almost any proxy/VPN
    75-100 because "is this a proxy?" dominates it, and vetting proxies is the whole point, so the bare proxy
    flag is EXPECTED, not damning. What actually separates a usable residential exit from a burned one is
    datacenter-vs-residential plus INDEPENDENT abuse evidence (blacklists, AbuseIPDB, IPQS's ABUSE sub-flags —
    not the proxy flag). So a clean residential exit reads CLEAN even at fraud_score 100; the proxy flag and the
    score are shown as their own signals, never folded into the verdict.

    Scamalytics is read the same way, and for the same reason: its CLASSIFIER (datacenter / proxy_type /
    Tor) feeds ``connection_class`` and so can decide, while its SCORE gets zero weight at every tier. The
    score tracks ``scamalytics_isp_score`` on every measured IP — an ASN prior — and no threshold orders
    the set (catching Mullvad at 44 means passing a Tor exit at 15 and flagging clean Comcast at 18)."""
    hits = rep.get("blacklists") or []
    abuse = rep.get("abuse_confidence")
    fraud = rep.get("fraud_score")
    dc = is_datacenter(rep)
    gii = rep.get("getipintel_score")            # getIPIntel 0-1: near 1 = hosting/VPN/Tor exit
    gii_bad = rep.get("getipintel_bad")          # getIPIntel: the IP behaved maliciously
    # IPQS ABUSE history — distinct from the mere proxy/vpn detection, which is expected on every proxy.
    ipqs_abuse = bool(rep.get("recent_abuse") or rep.get("frequent_abuser")
                      or rep.get("high_risk_attacks") or rep.get("bot_status"))
    why = []
    # DIRTY (high friction): a datacenter exit, corroborated abuse (two blacklists, a heavy AbuseIPDB score, or
    # IPQS's own abuse flags), or a getIPIntel near-certain hosting/VPN verdict / bad-IP flag. getIPIntel earns a
    # dirty on its own here (unlike the raw IPQS proxy flag) because it grades residential-vs-hosting rather than
    # flagging every proxy — proven live (AWS 1.0, Starlink 0.0). A single stray blacklist isn't enough alone.
    if rep.get("connection_class") == "tor" or rep.get("scam_tor"):
        why.append("Tor exit")
    elif dc:
        # Name the SOURCE when Scamalytics is what promoted it. Its specificity on residential pools is
        # proven on only four IPs — if it ever misfires, "datacenter/hosting IP (Scamalytics DCH)" is
        # diagnosable at a glance, where a bare factor line would look identical to the name-regex verdict
        # that has been trusted for months.
        _blob = " ".join(str(rep.get(k) or "") for k in ("isp", "organization", "host"))
        why.append("datacenter/hosting IP" +
                   (f" (Scamalytics {rep.get('scam_proxy_type') or 'is_datacenter'})" if _scam_dc(rep)
                    else " (getIPIntel)" if _gii_dc(rep) and not _DATACENTER_RE.search(_blob)
                    else " (ip-api)" if _ipapi_dc(rep) and not _DATACENTER_RE.search(_blob)
                    else ""))
    if len(hits) >= 2:
        why.append(f"{len(hits)} blacklists")
    if abuse is not None and abuse >= 50:
        why.append(f"{abuse}% abuse confidence")
    if gii_bad:
        why.append("getIPIntel bad-IP")
    if gii is not None and gii >= 0.99:
        why.append("getIPIntel proxy/hosting")
    if why:
        return "dirty", why

    # SUSPECT: a single weaker signal worth knowing about. IPQS's abuse flags (recent_abuse/bot) live HERE, not
    # in dirty — they saturate on shared/residential-proxy IPs the same way its score does, so on their own they
    # mark "worth a look", not "burned". A reliable independent source (a blacklist, AbuseIPDB, getIPIntel) is
    # what escalates to dirty above.
    if len(hits) == 1:
        why.append("1 blacklist")
    if abuse is not None and 10 <= abuse < 50:
        why.append(f"{abuse}% abuse confidence")
    if ipqs_abuse:
        why.append("IPQS abuse flags")
    if rep.get("abuse_velocity") in ("medium", "high"):
        why.append(f"{rep['abuse_velocity']} abuse velocity")
    if gii is not None and 0.90 <= gii < 0.99:
        why.append(f"getIPIntel {int(gii * 100)}% proxy")
    if why:
        return "suspect", why

    if fraud is None and abuse is None and gii is None and not rep.get("dnsbl_usable"):
        return "unknown", ["No source answered — add an API key, or check the network"]

    # CLEAN: not a datacenter, no abuse or blacklist history. Note when IPQS still flags it as a proxy — some
    # checkers reject ALL detected proxies, so the user should know it's detectable even though it's unburned.
    # Deliberately NOT called "residential": connection_class refuses to guess that from a name heuristic, so
    # the verdict must not claim it either. "No datacenter signal" is what was actually measured.
    # ...and never claim a blocklist record that was never obtained. Coverage is missing when NO zone
    # answered — a dead resolver, or every zone refusing this resolver — or when the address parsed as
    # neither family. Saying "no blacklist history" there turns "we didn't look" into "it's clean", which
    # is the worst thing this tool can do. An IPv6 exit is no longer one of those cases: it is checked
    # against the four zones that hold IPv6 data.
    #
    # ONE expression, read by both branches. They used to re-derive the same decision separately and
    # disagree on the wording, which is how a difference in MEANING hides as a difference in phrasing.
    blocklists = "no abuse or blacklist history" if rep.get("dnsbl_usable") else "blocklists NOT checked"
    if rep.get("proxy") or rep.get("vpn") or rep.get("tor") or (fraud is not None and fraud >= 60):
        return "clean", ["No datacenter signal", blocklists, "detectable as a proxy/VPN"]
    return "clean", ["No datacenter signal", "no proxy flag", blocklists]


def verdict(rep: dict) -> tuple[str, str]:
    """(level, one-line reason) — ``verdict_factors`` with its signals joined, for the terminal."""
    level, why = verdict_factors(rep)
    return level, _VERDICT_LEAD.get(level, "") + ", ".join(why)


def fraud_band(score: int) -> str:
    return "high risk" if score >= 85 else "suspicious" if score >= 60 else "clean"


def getipintel_band(score: float) -> str:
    """What a getIPIntel 0-1 probability means, in words. Deliberately NOT "residential" at the low end:
    a low score means getIPIntel saw no proxy evidence, which is not the same as proving a real ISP line."""
    return ("proxy/hosting exit" if score >= 0.99 else "likely proxy" if score >= 0.90
            else "mixed signals" if score >= 0.50 else "no proxy signal")


def format_report(rep: dict) -> str:
    """The terminal readout. Every line is omitted when its source had nothing to say, so the
    output never implies a check ran that didn't."""
    rows = [("Exit IP", rep.get("ip") or "unknown")]
    for key, label in (("isp", "ISP"), ("organization", "Organization"), ("asn", "ASN"),
                       ("connection_type", "Connection"), ("location", "Location"),
                       ("timezone", "Time zone")):
        if rep.get(key):
            rows.append((label, str(rep[key])))
    # Exit type is the strongest usability signal — a datacenter exit draws friction real ISPs don't.
    if rep.get("connection_class"):
        note = {"datacenter": " · real ISPs pass more easily",
                "tor": " · an instant deny at most apps"}.get(rep["connection_class"], "")
        rows.append(("Exit type", rep["connection_class"] + note))

    fraud = rep.get("fraud_score")
    if fraud is not None:
        strict = rep.get("ipqs_strictness")
        band = f"{fraud} · {fraud_band(fraud)}"
        rows.append(("Fraud risk",
                     f"{band} · IPQS strictness {strict}" if strict is not None else band))
        on = flags(rep)
        rows.append(("Flagged as", " · ".join(on) if on else "not flagged as proxy or VPN"))
    if rep.get("abuse_velocity"):
        rows.append(("Abuse velocity", rep["abuse_velocity"]))
    gii = rep.get("getipintel_score")
    if gii is not None:
        line = f"{gii:.2f} · {getipintel_band(gii)}" + (" · bad IP" if rep.get("getipintel_bad") else "")
        rows.append(("getIPIntel", line))
    # Shown next to the ISP score on purpose: the two are near-identical on every IP measured, and seeing
    # that is what tells a reader the score is an ASN prior rather than a judgement about this address.
    if rep.get("scam_risk"):
        line = f"{rep.get('scam_score')} · {rep['scam_risk']}"
        if rep.get("scam_isp_risk"):
            line += f" · ISP {rep.get('scam_isp_score')} {rep['scam_isp_risk']}"
        rows.append(("Scamalytics", line + "  (shown, not scored)"))
        on = [n for k, n in (("scam_datacenter", "datacenter"), ("scam_vpn", "VPN"), ("scam_tor", "Tor"),
                             ("scam_blacklisted_external", "external blocklist")) if rep.get(k)]
        if rep.get("scam_proxy_type"):
            on.append(f"ip2proxy {rep['scam_proxy_type']}")
        rows.append(("Scamalytics flags", " · ".join(on) if on else "none raised"))

    hits = rep.get("blacklists") or []
    pol = rep.get("policy_lists") or []
    checked = rep.get("dnsbl_checked", 0)
    available = bool(hits) or (rep.get("dnsbl_usable") and checked)
    if hits:
        line = f"{len(hits)} of {checked} · " + ", ".join(hits)
    elif rep.get("dnsbl_usable") and checked:
        line = f"none of {checked} lists"
    else:
        line = "unavailable · blocklist DNS unreachable"
    # A policy listing is still a listing. Reporting a bare "none of 12" beside one is how this read
    # as a clean IP next to a checker that counts every hit — the split is the point, hiding it isn't.
    # But only when the lookup actually worked: "unavailable … plus 1 policy listing" would claim DNS
    # was both unreachable and answered. (dnsbl_check already makes a real hit force usable=True, so
    # live data never lands here; this keeps format_report honest if handed the combination anyway.)
    if pol and available:
        line += f" · plus {len(pol)} policy listing" + ("s" if len(pol) > 1 else "")
    rows.append(("Blacklists", line))
    if pol and available:
        rows.append(("Policy lists", ", ".join(pol)
                     + " — a mail-sending policy listing, not an abuse report"))

    if rep.get("abuse_confidence") is not None:
        n = rep.get("abuse_reports") or 0
        rows.append(("Abuse reports", f"{n} in 90 days · {rep['abuse_confidence']}% confidence"))

    level, why = verdict(rep)
    rows.append(("Verdict", f"{level.upper()} — {why}"))
    rows.extend(("Note", n) for n in rep.get("notes", []))

    width = max(len(k) for k, _ in rows)
    return "\n".join(f"{k.ljust(width)}   {v}" for k, v in rows)


# ---- proxy input (tolerant parser; unit-tested) --------------------------------------------

# The schemes we understand. `https`/`socks5h`/`socks4a` normalise onto their base handler — the
# `h`/`a` variants only mean "resolve the destination at the proxy", which is what we already do.
_PROXY_SCHEMES = {"http": "http", "https": "http",
                  "socks5": "socks5", "socks5h": "socks5",
                  "socks4": "socks4", "socks4a": "socks4"}


class Proxy(NamedTuple):
    scheme: str          # http | socks5 | socks4  (already normalised)
    host: str
    port: int
    user: str
    password: str

    def http_url(self) -> str:
        """The `http://[user:pass@]host:port` form urllib's ProxyHandler wants."""
        auth = ""
        if self.user:
            auth = (urllib.parse.quote(self.user, safe="") + ":"
                    + urllib.parse.quote(self.password, safe="") + "@")
        return f"http://{auth}{self.host}:{self.port}"


def _host_port(text: str) -> tuple[str, int]:
    # An IPv6 endpoint has to be BRACKETED, and unbracketed multi-colon input is refused rather than
    # guessed at. `rpartition(':')` on a bare `2001:db8::1` yields host `2001:db8:` and port `1`, both of
    # which pass every check below — a silent misparse that dials a nonsense host and reports it as the
    # proxy the user typed. Refusing is the only honest answer: `host:port` is ambiguous for IPv6 and
    # RFC 3986 brackets exist precisely to resolve it.
    if text.startswith("["):
        close = text.find("]")
        if close < 0:
            raise ValueError(f"unclosed [ in IPv6 proxy address — got {text!r}")
        host, rest = text[:close + 1], text[close + 1:]
        if not rest.startswith(":"):
            raise ValueError(f"proxy needs host:port — got {text!r}")
        port = rest[1:]
    else:
        host, sep, port = text.rpartition(":")
        if not sep or not host:
            raise ValueError(f"proxy needs host:port — got {text!r}")
        if ":" in host:
            raise ValueError(f"IPv6 proxy address must be bracketed, as [{host}:{port}]:port")
    if not port.isdigit() or not (0 < int(port) <= 65535):
        raise ValueError(f"proxy port must be 1–65535 — got {port!r}")
    return host, int(port)


def parse_proxy(text: str, default_scheme: str = "http") -> Proxy | None:
    """Parse the many shapes people actually paste into one Proxy, or None if blank. Accepts, in any
    combination: a `scheme://` prefix (http/https/socks5[h]/socks4[a]); credentials as either
    `user:pass@host:port` or the trailing-colon `host:port:user:pass` a lot of resi providers hand
    out; or a bare `host:port`. `default_scheme` (from the UI's selector) fills in when there's no
    `://`. Raises ValueError with a readable reason on anything it can't make sense of.

    Separators: `;` is accepted anywhere `:` is, because vendors hand out `host;port;user;pass` as often
    as the colon form and re-typing a pasted list is not a thing anyone should have to do. The two can be
    mixed. Same caveat as below — a password containing `;` can't use that form.

    Limitation, stated rather than papered over: the `host:port:user:pass` form splits on colons, so a
    password that itself contains a colon can't be expressed that way — use the `user:pass@host:port`
    or `scheme://` form for those (percent-encoding not required)."""
    text = (text or "").strip()
    if not text:
        return None

    scheme = default_scheme.lower()
    if "://" in text:
        scheme, _, text = text.partition("://")
        scheme = scheme.lower()
    # AFTER the scheme is off, so `socks5://` survives. Everything below is colon-separated, so one
    # normalisation here teaches every shape to accept `;` instead of four places learning it separately.
    #
    # But NOT inside credentials. `user:pa;ss@host:8080` is a valid line today whose password genuinely
    # contains a semicolon, and a blanket replace silently turned it into `pa:ss` — a working proxy failing
    # to authenticate, with nothing on screen to say why. Only the host:port after the last `@` is
    # normalised; before it, `;` is a password character and stays one. With no `@` there are no
    # credentials to protect and the whole line is separators.
    creds, at, hostport = text.rpartition("@")
    text = creds + at + hostport.replace(";", ":") if at else text.replace(";", ":")
    if scheme not in _PROXY_SCHEMES:
        raise ValueError(f"unknown proxy scheme {scheme!r} — use http, socks5, or socks4")

    user = password = ""
    if "@" in text:
        # user:pass@host:port — split on the LAST '@' so a '@' inside the password is tolerated.
        creds, _, hostport = text.rpartition("@")
        user, _, password = creds.partition(":")
        host, port = _host_port(hostport)
    elif text.startswith("["):
        # A bracketed IPv6 endpoint has to be recognised BEFORE the colon count below, which would see
        # `[2001:db8::1]:8080` as five parts and reject it as an unknown shape. Brackets make the
        # host/port boundary unambiguous, which is exactly why they exist.
        host, port = _host_port(text)
    else:
        parts = text.split(":")
        if len(parts) == 2:
            host, port = _host_port(text)
        elif len(parts) == 4:
            host, port_s, user, password = parts
            _, port = _host_port(f"{host}:{port_s}")
        else:
            raise ValueError("proxy must be host:port, host:port:user:pass, "
                             "user:pass@host:port, or a scheme:// URL")
    return Proxy(_PROXY_SCHEMES[scheme], host, port, user, password)


# ---- SOCKS (stdlib only — no PySocks) -------------------------------------------------------
# A minimal SOCKS4a/5 CONNECT tunnel so the "no dependencies" promise holds. The byte-building is
# factored into pure helpers so the wire format is unit-tested without a live proxy.


def _socks5_greeting(has_auth: bool) -> bytes:
    """Client hello: version 5, offering user/pass auth when we have credentials, else no-auth."""
    return b"\x05\x01\x02" if has_auth else b"\x05\x01\x00"


def _socks5_userpass(user: str, password: str) -> bytes:
    """RFC 1929 username/password auth message."""
    u, p = user.encode(), password.encode()
    if len(u) > 255 or len(p) > 255:
        raise ValueError("SOCKS5 username/password max 255 bytes each")
    return b"\x01" + bytes([len(u)]) + u + bytes([len(p)]) + p


def _socks5_connect(host: str, port: int) -> bytes:
    """CONNECT request with the domain-name address type, so the PROXY resolves the host (socks5h
    semantics) — the desktop resolves DNSBL locally anyway, but API hosts should resolve proxy-side."""
    h = host.encode()
    if len(h) > 255:
        raise ValueError("hostname too long for SOCKS5")
    return b"\x05\x01\x00\x03" + bytes([len(h)]) + h + struct.pack(">H", port)


def _socks4_connect(host: str, port: int, user: str) -> bytes:
    """SOCKS4a CONNECT (0.0.0.x sentinel + trailing hostname = resolve proxy-side)."""
    return (b"\x04\x01" + struct.pack(">H", port) + b"\x00\x00\x00\x01"
            + user.encode() + b"\x00" + host.encode() + b"\x00")


def _recvn(sock, n: int, deadline: float | None) -> bytes:
    """Read exactly n bytes, but never past `deadline` (a monotonic timestamp). The socket's own
    timeout is a PER-CALL budget, so a proxy trickling one byte per interval could otherwise hold a
    handshake open for unbounded wall-clock; the deadline caps the whole negotiation."""
    buf = b""
    while len(buf) < n:
        if deadline is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise OSError("SOCKS proxy handshake timed out")
            sock.settimeout(remaining)
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise OSError("SOCKS proxy closed the connection early")
        buf += chunk
    return buf


def _socks_tunnel(proxy: Proxy, dest_host: str, dest_port: int, timeout: float | None):
    """Open a socket to `proxy`, negotiate a CONNECT tunnel to (dest_host, dest_port), return it. The
    whole negotiation shares one `timeout` budget (see _recvn) so a slow-loris proxy can't hang it."""
    deadline = time.monotonic() + timeout if timeout else None
    s = socket.create_connection((proxy.host, proxy.port), timeout)

    def recvn(n):
        return _recvn(s, n, deadline)

    try:
        if proxy.scheme == "socks5":
            s.sendall(_socks5_greeting(bool(proxy.user)))
            method = recvn(2)[1]
            if method == 0x02:
                # Fail closed: only do user/pass auth if we actually OFFERED it. A proxy selecting 0x02
                # when we sent a no-auth-only greeting is protocol-violating; sending empty credentials
                # would be worse than refusing.
                if not proxy.user:
                    raise OSError("SOCKS5 proxy demanded credentials we didn't offer")
                s.sendall(_socks5_userpass(proxy.user, proxy.password))
                if recvn(2)[1] != 0:
                    raise OSError("SOCKS5 proxy rejected the username/password")
            elif method != 0x00:
                raise OSError("SOCKS5 proxy wants an auth method we don't offer "
                              "(credentials required?)")
            s.sendall(_socks5_connect(dest_host, dest_port))
            rep = recvn(4)
            if rep[1] != 0:
                raise OSError(f"SOCKS5 CONNECT failed (reply code {rep[1]})")
            # Consume the bound address + port so the socket is left at the start of the tunnelled
            # stream. Address length depends on the type byte: 4 for IPv4, 16 for IPv6, or a
            # length-prefixed domain name.
            atyp = rep[3]
            if atyp == 1:
                recvn(4)
            elif atyp == 4:
                recvn(16)
            elif atyp == 3:
                recvn(recvn(1)[0])
            else:
                raise OSError(f"SOCKS5 sent an unknown address type ({atyp})")
            recvn(2)   # bound port
        else:   # socks4a
            s.sendall(_socks4_connect(dest_host, dest_port, proxy.user))
            rep = recvn(8)
            if rep[1] != 0x5a:
                raise OSError(f"SOCKS4 CONNECT failed (reply code {rep[1]})")
        return s
    except Exception:
        s.close()
        raise


def _socks_opener(proxy: Proxy):
    """A urllib opener whose HTTP(S) connections tunnel through a SOCKS proxy. Only what this tool
    needs — HTTPS GETs — so it overrides the connection's socket and lets http.client do the rest."""
    import http.client
    import ssl

    ctx = ssl.create_default_context()

    class _HTTPSConn(http.client.HTTPSConnection):
        def connect(self):
            sock = _socks_tunnel(proxy, self.host, self.port, self.timeout)
            self.sock = ctx.wrap_socket(sock, server_hostname=self.host)

    class _HTTPConn(http.client.HTTPConnection):
        def connect(self):
            self.sock = _socks_tunnel(proxy, self.host, self.port, self.timeout)

    class _HTTPSHandler(urllib.request.HTTPSHandler):
        def https_open(self, req):
            return self.do_open(_HTTPSConn, req)

    class _HTTPHandler(urllib.request.HTTPHandler):
        def http_open(self, req):
            return self.do_open(_HTTPConn, req)

    return urllib.request.build_opener(_HTTPSHandler, _HTTPHandler)


# ---- network -------------------------------------------------------------------------------


def default_scheme_is_http(scheme: str) -> bool:
    """True when the selected transport is the HTTP family, so the retry picks the OTHER one. Named rather
    than inlined because "not socks" and "is http" stop being the same thing the moment a third family
    exists."""
    return _PROXY_SCHEMES.get((scheme or "http").lower(), "http") == "http"


def _opener(proxy: str | None, default_scheme: str = "http"):
    p = parse_proxy(proxy, default_scheme) if isinstance(proxy, str) else proxy
    if not p:
        return urllib.request.build_opener()
    if p.scheme in ("socks5", "socks4"):
        return _socks_opener(p)
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": p.http_url(), "https": p.http_url()}))


def _secret_forms(p) -> list[str]:
    """Every form a credential can wear inside an error string, longest first.

    Replacing the literal ``user`` and ``password`` is not enough, because the credential does not
    necessarily appear literally. urllib builds ``Proxy-Authorization: Basic <base64(user:pass)>``, and
    a proxy URL percent-encodes. Each of those is the SAME secret in a costume, and an error that echoes
    the header or the URL carries it straight through a literal scrub.

    Longest first: a password that CONTAINS the username would otherwise be cut down to a fragment
    (``***pass``) with the rest of the real password still in the clear."""
    forms: set[str] = set()
    user, password = getattr(p, "user", "") or "", getattr(p, "password", "") or ""
    for secret in filter(None, (user, password)):
        forms.update((secret, urllib.parse.quote(secret, safe=""), urllib.parse.quote_plus(secret)))
    if user or password:
        pair = f"{user}:{password}"
        forms.update((pair, urllib.parse.quote(pair, safe=""),
                      base64.b64encode(pair.encode("utf-8")).decode("ascii")))
    return sorted((f for f in forms if f), key=len, reverse=True)


def _safe_reason(reason: str, proxy) -> str:
    """A proxy's failure reason with the credentials taken back out, then capped for display.

    The error branch is the one that leaks. A proxy library is free to put the host, the username, the
    whole ``user:pass@host:port``, or the Proxy-Authorization header into the message it raises, and
    this string is on its way to a report the user copies into a public issue.

    Order is the whole point: scrub FIRST, cap LAST. Capping first can cut a secret in half, and a half
    secret survives every replace below."""
    if not reason:
        return ""
    try:
        p = parse_proxy(proxy, "http") if isinstance(proxy, str) else proxy
    except ValueError:
        p = None
    if p:
        for secret in _secret_forms(p):
            if len(secret) >= 4:
                reason = reason.replace(secret, "***")
            else:
                # A one- or two-character credential is still a credential, but a blind replace of it
                # rewrites every innocent occurrence of those letters and shreds the message —
                # "Proxy-Authorization" became "Proxy-A***thorization" for a username of "u". Require a
                # word boundary so the standalone secret is still redacted and prose survives.
                reason = re.sub(rf"\b{re.escape(secret)}\b", "***", reason)
    # Whatever follows an Authorization header is a credential BY DEFINITION, whatever scheme it names.
    # Redacting only basic/bearer/digest left `Proxy-Authorization: Custom <token>` in the clear. Take
    # the value to end of line: losing a few words of trailing prose is cheaper than leaking a token.
    reason = re.sub(r"(?im)^(.*?(?:proxy-)?authorization\s*:\s*).*$", r"\1***", reason)
    # ...and the same for a bare scheme with no header name around it.
    reason = re.sub(r"(?i)\b(basic|bearer|digest)\s+\S+", r"\1 ***", reason)
    return reason[:160]


def _effective_scheme(proxy, default_scheme: str) -> str:
    """The transport a check ACTUALLY went out on. A line carrying its own ``scheme://`` overrides the UI
    selector, so reporting the selector describes a request that was never made."""
    try:
        p = parse_proxy(proxy, default_scheme) if isinstance(proxy, str) else proxy
        return p.scheme if p else default_scheme
    except ValueError:
        return default_scheme


def _why(exc: BaseException) -> str:
    """A transport failure as one short, quotable phrase — or "" when there is nothing worth repeating.

    A proxy that refuses you usually SAYS why, and throwing that away is how a working credential set
    with an empty vendor pool ends up rendered as a bare "DEAD". MEASURED 2026-08-08: proxy-seller
    answered CONNECT with ``503 No exit node`` — the account was fine, the vendor simply had no
    residential exit to hand out. "Dead" is the one thing that was NOT true.

    urllib wraps the useful part in ``<urlopen error ...>``; unwrap it and keep the inside.

    Deliberately does NOT truncate. Length is capped in _safe_reason AFTER scrubbing, because cutting
    the string first can slice a credential in half, and a half-credential is exactly what a literal
    replace can no longer match — the trim would create the leak it looks like it prevents."""
    text = str(getattr(exc, "reason", None) or exc).strip()
    m = re.match(r"<urlopen error (.*)>$", text)
    if m:
        text = m.group(1).strip()
    return text


def _get_json(url: str, opener, headers: dict | None = None,
              timeout: float | None = None, errbox: list | None = None) -> dict | None:
    """GET a JSON document. None on a transport failure; an API's own error body is returned as-is
    so the caller can surface *why* (bad key, quota spent) instead of a generic failure.

    ``errbox``, when given, collects the transport failure's reason so a caller can repeat it rather
    than reporting a generic silence. Nothing is scrubbed here — see _safe_reason at the call site."""
    try:
        req = urllib.request.Request(url, headers=headers or {})
        with opener.open(req, timeout=TIMEOUT if timeout is None else timeout) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            # A proxy refusal can arrive as an HTTPError rather than a URLError; dropping it here
            # would silently lose the one sentence that explains the failure.
            if errbox is not None:
                errbox.append(_why(e))
            return None
    except Exception as e:
        if errbox is not None:
            errbox.append(_why(e))
        return None


def _get_text(url: str, opener) -> str | None:
    """GET a plain-text body, stripped. None on any failure.

    Separate from _get_json because two of the three IPv4-only echoes answer with a bare address and
    ``json.loads("23.159.216.252")`` raises — routed through _get_json they could never succeed. The strip
    is load-bearing too: checkip.amazonaws.com returns a trailing newline, and `reverse_v4` rejects
    ``"252\\n"`` because ``"252\\n".isdigit()`` is False."""
    try:
        with opener.open(urllib.request.Request(url), timeout=TIMEOUT) as r:
            return r.read().decode("utf-8", "replace").strip() or None
    except Exception:
        return None


# IPv4-ONLY echo endpoints, in order. A hostname with no AAAA record is the only lever that pins the
# family when an HTTP proxy does its own DNS and outbound connect — we never see that socket, so
# `AF_INET` is not available to us. MEASURED 2026-08-07 via DNS-over-HTTPS (this machine's own resolver
# strips AAAA from every answer, even google.com, so `nslookup`/`getaddrinfo` prove nothing here): all
# three below answer with A records and NO AAAA, while `ipwho.is` — the ordinary exit lookup — has two.
#
# THREE OPERATORS, not one: ipify sits behind Cloudflare, so a single endpoint made "pin the family" fail
# whenever an exit could not reach Cloudflare, silently dropping the whole report onto IPv6. amazonaws is
# AWS and ident.me is Hetzner, so no two share a fate. Ordered by measured latency (0.7s / 1.8s / 1.2s
# direct); the second and third are only ever paid when the one before it gave nothing.
_V4_ECHOES: tuple[tuple[str, bool], ...] = (
    ("https://api4.ipify.org?format=json", True),    # True  -> JSON {"ip": ...}
    ("https://checkip.amazonaws.com", False),        # False -> bare text (this one has a trailing \n)
    ("https://v4.ident.me", False),
)


def lookup_exit_v4(opener, deadline: float | None = None) -> str | None:
    """This connection's exit IP, forced over IPv4.

    A dual-stack proxy answers on whichever family the connection happens to use, so the ordinary exit
    lookup can return an IPv6 address — MEASURED on a Starlink residential exit: 8 consecutive samples of
    one endpoint gave ``153.66.117.15`` five times and ``2605:59ca:...:e798`` three times. That matters
    because every DNSBL zone we query is IPv4-only (measured 2026-08-05: all 17 answer the 127.0.0.2 test
    entry, none answer the 2001:db8::2 one), so an IPv6 sample means ZERO blocklist evidence.

    Asking an IPv4-only host pins the family, so a checkable address always exists. Returns None only when
    NO endpoint had an IPv4 route — a genuinely v6-only exit, which is worth reporting rather than
    hiding. One endpoint failing no longer looks like that; see _V4_ECHOES.

    ``deadline`` (a monotonic timestamp) stops the walk early. Three endpoints at the full timeout each is
    24s that only a badly-behaved proxy ever spends, and it lands at the END of a check that may already
    have paid a slow retry — enough, in the worst case, to run a HOSTED check past its function cap and
    lose a result that had already succeeded. Stopping early costs the v4 pin, not the whole report."""
    for url, is_json in _V4_ECHOES:
        if deadline is not None and time.monotonic() >= deadline:
            return None
        raw = _get_json(url, opener) if is_json else _get_text(url, opener)
        ip = raw.get("ip") if isinstance(raw, dict) else raw
        # Strip HERE, where the address is accepted — not only in _get_text where it is fetched.
        # checkip.amazonaws.com answers with a trailing newline and `reverse_v4` rejects "252\n"
        # (`"252\n".isdigit()` is False), so an endpoint that works reads as one that is down. A
        # whitespace-padded value can arrive from the JSON branch too; one normalisation covers both.
        ip = ip.strip() if isinstance(ip, str) else None
        if reverse_v4(ip):
            return ip
    return None


def lookup_geo(opener, ip: str | None = None, timeout: float | None = None,
               errbox: list | None = None) -> dict:
    """ISP/location/timezone for ``ip``, or for this connection's own exit IP when ``ip`` is None
    (in which case it also discovers what that exit IP is, as seen through ``opener``'s proxy)."""
    o = _get_json(f"https://ipwho.is/{urllib.parse.quote(ip, safe='') if ip else ''}", opener,
                  timeout=timeout, errbox=errbox)
    if not o or not o.get("success"):
        return {}
    where = ", ".join(x for x in (o.get("city"), o.get("region"), o.get("country")) if x)
    return {
        "ip": o.get("ip"),
        "isp": (o.get("connection") or {}).get("isp"),
        "location": where or None,
        # Carried so the UI can show the country's flag beside the location, not just its name.
        "country_code": o.get("country_code"),
        "timezone": (o.get("timezone") or {}).get("id"),
    }


def _ipapi_lookup(ip: str, opener) -> dict:
    """ip-api.com — KEYLESS (no signup, 45 req/min): `hosting`/`proxy`/`mobile` booleans + the ASN name. A
    free classifier exactly where IPQS/AbuseIPDB need a key. HTTP-only on the free tier, which is fine — the
    call is server-side, never from the browser. Returns {} on any failure or rate-limit (an ABSENT signal,
    never an error that fails the whole check). Queried DIRECTLY about the exit IP, like the blocklists."""
    if not ip:
        return {}
    o = _get_json("http://ip-api.com/json/" + urllib.parse.quote(ip, safe="")
                  + "?fields=status,proxy,hosting,mobile,asname", opener)
    if not isinstance(o, dict) or o.get("status") != "success":
        return {}
    return {"ipapi_hosting": bool(o.get("hosting")), "ipapi_proxy": bool(o.get("proxy")),
            "ipapi_mobile": bool(o.get("mobile")), "ipapi_asname": o.get("asname") or None}


def _premium(v) -> bool:
    """True for an IPQS field that is a paywall notice rather than a value. Showing one in a detail card
    would read as data. Two shapes, both measured live on the free plan 2026-08-05: the string
    ``"Premium required."`` (connection_type, abuse_velocity), and ``abuse_events``, which is a LIST whose
    one element is ``"Enterprise plan required to view abuse events…"`` — a plain string check misses it."""
    if isinstance(v, list):
        return bool(v) and all(_premium(x) for x in v)
    return isinstance(v, str) and bool(re.match(r"\s*(premium|enterprise)\b", v, re.I))


def lookup_ipqs(ip: str, key: str, opener) -> dict:
    """IPQualityScore proxy/VPN detection. Returns the fields worth showing, or a note explaining
    why there's no score (their error body says whether it's the key or the quota)."""
    url = ("https://ipqualityscore.com/api/json/ip/"
           f"{urllib.parse.quote(key, safe='')}/{urllib.parse.quote(ip, safe='')}"
           f"?strictness={IPQS_STRICTNESS}")
    o = _get_json(url, opener)
    if not o:
        return {"notes": ["IPQualityScore unreachable — check the network or proxy"]}
    if not o.get("success"):
        # Scrub the key out of the echoed message. IPQS takes the key in the URL PATH and its rejection
        # messages can quote what was rejected — and on the hosted deploy this branch is reached by every
        # visitor who brings no key of their own the moment the shared server-side key expires or runs out
        # of quota. That would hand the operator's key to each of them, rendered into the page.
        msg = str(o.get("message") or "lookup rejected")
        return {"notes": ["IPQualityScore: " + (msg.replace(key, "<key>") if key else msg)]}

    out: dict = {"fraud_score": o.get("fraud_score"), "ipqs_strictness": IPQS_STRICTNESS}
    # The whole response, for the per-source detail card — this is what IPQS actually saw, and reading it is
    # the only way to reconcile a verdict with the raw signals. Premium placeholders are dropped (they're not
    # values), and anything echoing the API key is dropped as a matter of principle: this dict goes to the
    # browser, and a shared server-side key must never ride along with it.
    out["ipqs_raw"] = {k: v for k, v in o.items()
                       if k not in ("success", "message", "request_id", "api_version")
                       and not _premium(v) and not (isinstance(v, str) and key and key in v)}
    for key_name, _ in IPQS_FLAGS:
        out[key_name] = bool(o.get(key_name))
    for src, dst in (("connection_type", "connection_type"), ("abuse_velocity", "abuse_velocity"),
                     ("organization", "organization"), ("ISP", "isp"), ("host", "host")):
        v = o.get(src)
        if v and not _premium(v):
            out[dst] = v
    if o.get("ASN"):
        out["asn"] = f"AS{o['ASN']}"
    if not out.get("connection_type") and o.get("mobile"):
        out["connection_type"] = "Mobile"
    return out


def lookup_abuseipdb(ip: str, key: str, opener) -> dict:
    o = _get_json(f"https://api.abuseipdb.com/api/v2/check?maxAgeInDays=90&ipAddress="
                  f"{urllib.parse.quote(ip, safe='')}", opener,
                  {"Key": key, "Accept": "application/json"})
    data = (o or {}).get("data")
    if not data:
        errs = (o or {}).get("errors") or []
        why = errs[0].get("detail") if errs and isinstance(errs[0], dict) else "lookup failed"
        return {"notes": ["AbuseIPDB: " + str(why)]}
    return {
        "abuse_confidence": data.get("abuseConfidenceScore"),
        "abuse_reports": data.get("totalReports"),
        "usage_type": data.get("usageType"),
        "abuse_reporters": data.get("numDistinctUsers"),
        "abuse_last_report": data.get("lastReportedAt"),
        "abuseipdb_raw": data,      # the whole record, for the detail card
    }


def lookup_getipintel(ip: str, contact: str, opener) -> dict:
    """getIPIntel through one or more contact addresses. ``contact`` may hold SEVERAL, separated by commas
    or whitespace: getIPIntel meters per contact as well as per connecting IP (15/min, 500/day), so a
    second address gets one more attempt when the first is refused for quota. Only the quota-shaped
    refusals rotate — a verdict about the query itself (private range, bad IP) would just burn the next
    address's budget. Returns the first real answer, or the LAST refusal so the note explains what happened."""
    contacts = [c for c in re.split(r"[,;\s]+", contact or "") if c]
    if not contacts:
        out = _getipintel_once(ip, "", opener)
        out.pop("_retry", None)         # internal control flag, never part of the report
        return out
    out: dict = {}
    for c in contacts:
        out = _getipintel_once(ip, c, opener)
        if "getipintel_score" in out or not out.pop("_retry", False):
            return out
    return out


def _getipintel_once(ip: str, contact: str, opener) -> dict:
    """getIPIntel proxy/VPN/hosting probability (0-1) + a BadIP flag. Free, NO signup — it only needs a contact
    email (for abuse contact, not auth). A value near 1 is a hosting/VPN/Tor exit (high friction for a strict
    app); BadIP means the IP itself behaved maliciously. Unlike IPQS it grades residential-vs-hosting — measured
    live: AWS 1.0, Starlink 0.0 — so it DISCRIMINATES where IPQS saturates. Rate-limited (15/min, 500/day); the
    docs say do NOT URL-encode the params, and reject any query without a valid contact.

    ``oflags`` is a character SET, not a single flag — ``bc`` asks for the BadIP verdict AND the country
    getIPIntel resolved, which is worth showing next to the score. Verified live 2026-08-05 that the
    combined form is accepted and echoed back in ``queryOFlags``."""
    o = _get_json(f"https://check.getipintel.net/check.php?ip={ip}&contact={contact}&format=json&oflags=bc", opener)
    if not o:
        return {"notes": ["getIPIntel unreachable"]}
    # `result` carries the error code on BOTH the success and error statuses, so read it first: its mapped
    # one-line meaning beats getIPIntel's own `message`, which is a three-sentence paragraph of questions.
    try:
        score = float(str(o.get("result")))
    except (TypeError, ValueError):
        score = None
    if score is not None and score < 0:
        code = int(score)
        return {"notes": ["getIPIntel: " + GETIPINTEL_ERRORS.get(code, f"error {o.get('result')}")],
                "_retry": code in GETIPINTEL_RETRYABLE}
    if o.get("status") != "success" or score is None:
        # Fall back to their message, but only its first sentence, and with the contact scrubbed — on the
        # hosted deploy that address is a server-side env var and this note renders in a visitor's browser.
        msg = str(o.get("message") or "rejected — is the contact address valid?").split(". ")[0].strip()
        return {"notes": ["getIPIntel: " + (msg.replace(contact, "<contact>") if contact else msg)]}
    out: dict = {"getipintel_score": round(score, 3)}
    if o.get("BadIP"):
        out["getipintel_bad"] = True
    # The raw answer for the detail card, MINUS the contact address — getIPIntel echoes the contact it was
    # queried with, and on the hosted deploy that is a server-side env var. It must never reach the browser.
    out["getipintel_raw"] = {k: v for k, v in o.items()
                             if k != "contact" and not (isinstance(v, str) and contact and contact in v)}
    return out


def _echoes(v, *creds: str) -> bool:
    """True if a value carries one of our credentials. The Scamalytics key rides in the QUERY STRING, so
    any string field it echoes back — or any exception carrying the URL — would publish it to the browser."""
    return isinstance(v, str) and any(c and c in v for c in creds)


def lookup_scamalytics(ip: str, user: str, key: str, opener) -> dict:
    """Scamalytics v3: a datacenter/VPN/Tor classifier, plus a score we deliberately do not act on.

    MEASURED over ~200 live lookups 2026-08-06. The SCORE is noise: it tracks ``scamalytics_isp_score``
    on every sample, i.e. it is an ISP/ASN reputation prior rather than an IP-level abuse measure, and it
    MIS-RANKS — a Tor exit scored 15 "low", clean Comcast residential 18, and the highest in the whole set
    was Mullvad at 44. No threshold orders that set, so the score gets ZERO weight in ``verdict_factors``;
    it is shown, labelled, warn-only, because seeing it next to the ISP score is what tells a reader it is
    a prior. The FLAGS are what earn the integration: ``is_datacenter`` + ip2proxy's ``proxy_type`` caught
    all four hosting IPs the name heuristic missed, and were quiet on all four real residential exits."""
    o = _get_json(f"https://{SCAM_HOST}/v3/{urllib.parse.quote(user, safe='')}/"
                  f"?key={urllib.parse.quote(key, safe='')}&ip={urllib.parse.quote(ip, safe='')}", opener)
    # A rejected key answers HTTP 404 with an Apache HTML body (NOT the 401 + JSON the docs promise), so
    # _get_json returns None for both "unreachable" and "bad credentials". The note names both rather than
    # guessing. ponytail: no second request just to read a status code — add one if the ambiguity ever costs
    # a session.
    if not o:
        return {"notes": ["Scamalytics: no answer — unreachable, or the user/key was rejected"]}
    s = o.get("scamalytics") or {}
    # HTTP 200 does not mean success, and the guard ORDER is load-bearing: on every error shape
    # `external_datasources` flips from an object to an empty ARRAY, so reading it first raises.
    if s.get("status") != "ok":
        msg = str(s.get("error") or "lookup rejected")
        return {"notes": ["Scamalytics: " + ("<redacted>" if _echoes(msg, user, key) else msg)]}
    # Our quota, not the visitor's business — and an exhausted balance must SAY so rather than quietly
    # degrade every verdict to "no datacenter signal". `remaining` is int-or-str depending on mode.
    try:
        left = int(str((o.get("credits") or {}).get("remaining", 1)))
    except (TypeError, ValueError):
        left = 1
    if left <= 0:
        return {"notes": ["Scamalytics: credits exhausted — not measured"]}

    ext = s.get("external_datasources") or {}
    prox = s.get("scamalytics_proxy") or {}
    ip2 = ext.get("ip2proxy") or {}
    x4b = ext.get("x4bnet") or {}
    out: dict = {}
    if isinstance(s.get("scamalytics_score"), int):
        out["scam_score"] = s["scamalytics_score"]
    # scam_risk is the "did it run?" sentinel — nothing else is guaranteed present.
    for src, dst in (("scamalytics_risk", "scam_risk"), ("scamalytics_isp_risk", "scam_isp_risk")):
        if s.get(src) and not _premium(s[src]):
            out[dst] = s[src]
    if isinstance(s.get("scamalytics_isp_score"), int):
        out["scam_isp_score"] = s["scamalytics_isp_score"]
    out["scam_datacenter"] = bool(prox.get("is_datacenter"))
    out["scam_vpn"] = bool(prox.get("is_vpn"))
    # x4bnet read is_tor FALSE on a real Tor exit that ip2proxy typed "TOR" — union them, don't trust either.
    ptype = ip2.get("proxy_type")
    out["scam_tor"] = bool(x4b.get("is_tor")) or ptype == "TOR"
    # An EMPTY proxy_type is "no ip2proxy record", not "clean" — drop it so the UI can say so.
    if ptype and ptype != "0" and not _premium(ptype):
        out["scam_proxy_type"] = ptype
    out["scam_blacklisted_external"] = bool(s.get("is_blacklisted_external"))

    # FLAT — kv()/fmtv() in PAGE render a nested object as "[object Object]". `ip2proxy_lite` is
    # deliberately absent: measured empty on all 8 IPs, so rendering it would read as "checked and clean".
    fh, ips = ext.get("firehol") or {}, ext.get("ipsum") or {}
    raw = {"score": s.get("scamalytics_score"), "risk": s.get("scamalytics_risk"),
           "isp_score": s.get("scamalytics_isp_score"), "isp_risk": s.get("scamalytics_isp_risk"),
           "isp_name": s.get("scamalytics_isp"), "org_name": s.get("scamalytics_org"),
           "is_datacenter": prox.get("is_datacenter"), "is_vpn": prox.get("is_vpn"),
           "is_apple_icloud_private_relay": prox.get("is_apple_icloud_private_relay"),
           "is_amazon_aws": prox.get("is_amazon_aws"), "is_google": prox.get("is_google"),
           "ip2proxy_type": ptype or None, "x4bnet_tor": x4b.get("is_tor"), "x4bnet_vpn": x4b.get("is_vpn"),
           "x4bnet_datacenter": x4b.get("is_datacenter"), "x4bnet_spambot": x4b.get("is_spambot"),
           "firehol_30d": fh.get("is_blacklisted_30d"), "firehol_1day": fh.get("is_blacklisted_1day"),
           "ipsum_blacklisted": ips.get("is_blacklisted"), "ipsum_blacklists": ips.get("num_blacklists"),
           "spamhaus_drop": (ext.get("spamhaus_drop") or {}).get("is_blacklisted"),
           "dbip_connection_type": (ext.get("dbip") or {}).get("connection_type"),
           "blacklisted_external": s.get("is_blacklisted_external"),
           "url": s.get("scamalytics_url")}
    out["scamalytics_raw"] = {k: v for k, v in raw.items()
                              if v is not None and v != "" and not _premium(v)
                              and not _echoes(v, user, key)}
    return out


def resolve_a(host: str) -> list[str]:
    """Every A record for ``host``; empty when it doesn't resolve.

    The system resolver, deliberately — not DNS-over-HTTPS. Spamhaus and CBL refuse queries relayed
    by large public resolvers, so asking Cloudflare loses the two most valuable zones; a normal ISP
    resolver gets real answers from all twelve. (The Android side has no choice: the proxy apps it
    runs behind hijack DNS with a fake-IP pool, so it must use DoH and accepts losing those two.)"""
    try:
        return [str(i[4][0]) for i in socket.getaddrinfo(host, None, socket.AF_INET)]
    except socket.gaierror:
        return []               # NXDOMAIN, or a resolver failure — see the liveness probe below


def dnsbl_check(ip: str) -> dict:
    """Query every zone in parallel. Zones that refuse are excluded rather than counted clear, and
    a liveness probe guards the whole result — ``socket.gaierror`` cannot tell "not listed" from
    "resolver is broken", so without it a dead resolver would report a confident "none of 12"."""
    # Pick the table by address family. IPv6 gets the four zones that actually hold IPv6 data, so the
    # denominator this reports is the number of lists that could ever have flagged the address.
    rev = reverse_v4(ip)
    zones, family = DNSBL_ZONES, "ipv4"
    if not rev:
        rev, zones, family = reverse_v6(ip), DNSBL_ZONES_V6, "ipv6"
    if not rev:
        return {"blacklists": [], "policy_lists": [], "dnsbl_checked": 0, "dnsbl_usable": False,
                "dnsbl_detail": [], "dnsbl_skipped": "unparseable", "dnsbl_family": "unknown"}

    # Every DNSBL lists 127.0.0.2 by convention, so these MUST resolve. Several zones, not one:
    # gating on a single zone means that zone's outage (or its refusal to answer this resolver)
    # silently reports every IP as "unavailable".
    # The liveness probe stays on the IPv4 test entry even for an IPv6 lookup: every zone lists
    # 127.0.0.2, and rbldnsd's mapped-IPv4 aliasing means an IPv6-form probe would answer on zones
    # holding no IPv6 data at all. It proves the RESOLVER works, which is what it is for.
    probes = [f"2.0.0.127.{z}" for _, z in zones[:4]]
    jobs = [(None, None, p) for p in probes]
    jobs += [(name, zone, f"{rev}.{zone}") for name, zone in zones]

    abuse, policy, checked, alive = [], [], 0, False
    # Per-zone outcome for the detail card: every zone in the table appears, so "12 clean, 3 refused" is
    # visible instead of only the count of hits. "no answer" is the default — a zone that never returned
    # is not a clean result and must not read as one.
    detail = {name: "no answer" for name, _ in zones}
    with ThreadPoolExecutor(max_workers=len(jobs)) as ex:
        futs = {ex.submit(resolve_a, host): (name, zone) for name, zone, host in jobs}
        try:
            done = list(as_completed(futs, timeout=TIMEOUT + 2))
        except TimeoutError:
            done = [f for f in futs if f.done()]
        for f in done:
            name, zone = futs[f]
            try:
                addrs = f.result()
            except Exception:
                continue        # this zone did not answer
            if name is None or zone is None:
                alive = alive or any(listed(a) for a in addrs)
                continue
            kind = classify(zone, addrs)
            if kind == "blocked":
                detail[name] = "refused"
                continue        # the zone refused — it told us nothing, so don't count it as clear
            checked += 1
            if kind == "abuse":
                abuse.append(name)
                detail[name] = "listed"
            elif kind == "policy":
                policy.append((name, policy_label(name, zone, addrs)))
                detail[name] = "policy"
            else:
                detail[name] = "clean"
    order = [z[0] for z in zones]
    abuse.sort(key=order.index)
    policy = [label for _, label in sorted(policy, key=lambda p: order.index(p[0]))]
    # `dnsbl_usable` means "this sweep produced EVIDENCE", and that needs both halves:
    #
    #  * the resolver has to be working — `alive`, or a real listing, which proves it independently. (A
    #    dead resolver makes every zone look like a clean NXDOMAIN, which is the whole reason the sentinel
    #    probes exist; a run where the probes fail but another zone returns a listing would otherwise
    #    report "unavailable" AND carry that listing, a contradiction.)
    #  * and at least ONE zone has to have actually answered.
    #
    # The second half was missing, and it is not hypothetical: Spamhaus and CBL answer 127.255.255.254 —
    # a refusal — to queries relayed by large public resolvers, and `classify` correctly declines to count
    # a refusal. A run where the sentinels resolve but every real zone refuses gave usable=True with
    # checked=0, and `verdict_factors` then said "no abuse or blacklist history" about a sweep that
    # obtained nothing. A false all-clear is the one thing this tool must never produce.
    usable = checked > 0 and (alive or bool(abuse) or bool(policy))
    return {"blacklists": abuse, "policy_lists": policy,
            "dnsbl_checked": checked if usable else 0, "dnsbl_usable": usable,
            "dnsbl_family": family, "dnsbl_zones_total": len(zones),
            # WHY there is no coverage, so the UI can say which — a resolver that never answered and an
            # address no zone can be asked about are different problems. Absent when the sweep worked.
            **({} if usable else {"dnsbl_skipped": "no answer" if rev else "unparseable"}),
            "dnsbl_detail": [{"name": n, "zone": z, "status": detail[n]} for n, z in zones]}


# The direct-path baseline (this machine's own latency to the geo endpoint) is MACHINE-CONSTANT — it does
# not vary per row — so a bulk run must not re-measure it N times. That doubled the request rate to one
# shared free endpoint and a throttled reply (empty geo) dropped a row's baseline or failed another row's
# primary lookup, rendering a live proxy as DEAD. Measure it once and reuse for a short TTL. The race under
# the server's concurrent workers is benign: the worst case is a few extra measurements during warm-up.
_DIRECT_BASELINE = {"ms": None, "at": 0.0}
_DIRECT_BASELINE_TTL = 60.0


def _direct_baseline_ms(ip: str | None) -> int | None:
    """Milliseconds for a DIRECT (no-proxy) geo lookup from here, cached ~60s. None if the endpoint didn't
    answer (so proxy_added_ms is simply omitted rather than attributing the whole round trip to the proxy)."""
    now = time.monotonic()
    cached = _DIRECT_BASELINE
    if cached["ms"] is not None and now - cached["at"] < _DIRECT_BASELINE_TTL:
        return cached["ms"]
    started = time.monotonic()
    if not lookup_geo(urllib.request.build_opener(), ip):
        return None
    ms = int((time.monotonic() - started) * 1000)
    _DIRECT_BASELINE["ms"], _DIRECT_BASELINE["at"] = ms, now
    return ms


def check(proxy: str | None = None, ip: str | None = None,
          ipqs_key: str = "", abuse_key: str = "", proxy_scheme: str = "http",
          getipintel_contact: str = "", scam_user: str = "", scam_key: str = "") -> dict:
    """Run every available source and return one flat report dict. Blocking (network). ``proxy`` is
    parsed leniently (see ``parse_proxy``); ``proxy_scheme`` fills in the transport when ``proxy``
    carries no ``scheme://`` of its own."""
    opener = _opener(proxy, proxy_scheme)
    budget_ends = time.monotonic() + CHECK_BUDGET
    rep: dict = {"notes": []}

    def merge(part: dict) -> None:
        rep["notes"].extend(part.pop("notes", []))
        rep.update({k: v for k, v in part.items() if v is not None})

    def budget_left(source: str = "") -> float:
        """Seconds left in this check's wall-clock budget, 0 when spent (and a note saying which source
        paid for it). Everything after the liveness probe goes out through the SAME opener, so a proxy
        slow enough to need the retry makes every later request slow too — four reputation sources at the
        full timeout each is another 32s, on top of a liveness path that may already have spent 40. The
        hosted checker's function cap kills the whole invocation at that point and returns NOTHING, which
        is a worse answer than the one this PR set out to fix."""
        left = budget_ends - time.monotonic()
        if left <= 1:
            if source:
                rep["notes"].append(
                    f"{source}: not asked — this check had already spent its {CHECK_BUDGET}s on a slow "
                    "proxy. The exit IP, blocklists and verdict above are unaffected.")
            return 0.0
        return left

    # An explicit --ip still gets ISP/location/timezone — the readout would otherwise show a bare
    # address with a dash under it, and where an IP sits is half of judging it.
    #
    # This is also the liveness + latency probe when a proxy is in play: it is the first request that has to
    # traverse the tunnel, so whether it answers IS whether the proxy works, and how long it took is the
    # round trip a real request would pay. ponytail: one timed HTTPS round trip, not a separate TCP dial —
    # it measures usable latency (connect + TLS + fetch) rather than a raw handshake. Upgrade path if the
    # split ever matters: time the CONNECT separately to tell "proxy slow" from "upstream slow".
    # Every probe drops its failure reason in here. The FIRST attempt is the one most likely to carry
    # the vendor's own sentence ("503 No exit node"); the retry after it frequently just times out, so
    # collecting only the last one would trade the useful answer for a generic one.
    probe_errs: list[str] = []
    started = time.monotonic()
    geo = lookup_geo(opener, ip, errbox=probe_errs)
    latency_ms = int((time.monotonic() - started) * 1000)
    # A SOCKS proxy addressed as HTTP just reads DEAD — indistinguishable from one that is genuinely down.
    # MEASURED 2026-08-06: an entire vendor's list (lightningproxies, SOCKS5 on :1080) reported DEAD until
    # it was retried as SOCKS5, which is a trap for anyone pasting a list they were handed. So when a proxy
    # produced NO answer and its transport was only ASSUMED (no explicit `scheme://`), try the other family
    # once before calling it dead. The retry is silent when it works and named in the report when it does,
    # because "your proxy is fine, you picked the wrong transport" is the whole point.
    retried_scheme = None
    if proxy and not geo and isinstance(proxy, str) and "://" not in proxy:
        alt = "socks5" if default_scheme_is_http(proxy_scheme) else "http"
        alt_opener = None
        try:
            alt_opener = _opener(proxy, alt)
            started = time.monotonic()
            geo = lookup_geo(alt_opener, ip, errbox=probe_errs)
            latency_ms = int((time.monotonic() - started) * 1000)
        except ValueError:
            geo = {}                    # the line doesn't even parse as the other family — not a retry case
        if geo and alt_opener is not None:
            opener, retried_scheme = alt_opener, alt
            rep["proxy_scheme_used"] = alt
            rep["notes"].append(f"Proxy: no answer as {proxy_scheme.upper()} — it responded as "
                                f"{alt.upper()}. Set the transport to {alt.upper()} to avoid the retry.")
    # A first request through a proxy can exceed TIMEOUT while the proxy is perfectly ALIVE, and calling
    # that "dead" is a confident wrong answer. MEASURED 2026-08-07: five lightningproxies SOCKS5 endpoints
    # answered in ~800 ms once warm, but on a cold, concurrent hosted run the same five took 13-19 s each
    # and the whole batch rendered DEAD — while a direct SOCKS5 handshake to every one of them succeeded.
    # So retry ONCE on the same transport before drawing any conclusion. This costs nothing on a genuinely
    # dead proxy: a refused connection or an unresolvable host fails in well under a second, it does not
    # burn the timeout.
    if proxy and not geo:
        started = time.monotonic()
        geo = lookup_geo(opener, ip, timeout=SLOW_TIMEOUT, errbox=probe_errs)
        latency_ms = int((time.monotonic() - started) * 1000)
        if geo:
            rep["notes"].append(
                f"Proxy: no answer within {TIMEOUT}s, but it answered on a retry with a {SLOW_TIMEOUT}s "
                "budget — the proxy is up, just slow to get going. The latency shown is the retry's.")
    merge(geo)
    if proxy:
        rep["proxy_alive"] = bool(geo)
        if geo:
            rep["proxy_ms"] = latency_ms
            # A raw number is not interpretable on its own. MEASURED 2026-08-06 from this machine (+0800):
            # the SAME endpoint takes 889 ms direct and 3077 ms through a US residential proxy — and the
            # endpoint barely matters (gstatic 610/3125, cloudflare 608/3172, ipify 686/3203, all within
            # ~100 ms of each other out of ~3100). So the number is dominated by the PROXY, not by the
            # observer's distance: the hosted check runs from Vercel's iad1 in US-East and still reports
            # ~3400 ms on the same proxies.
            #
            # Timing the same request WITHOUT the proxy, from wherever the check happens to be running,
            # separates the two. `proxy_added_ms` is the honest figure — "what this proxy costs on top of
            # this machine's own path" — and it is comparable between a laptop in Asia and a Lambda in
            # Virginia, which the raw round trip is not. One extra request, only when a proxy is in play.
            # Skipped when the budget is gone: this is a COMPARISON nicety (what the proxy adds over this
            # machine's own path), and spending the last seconds on it can cost the report itself.
            base_ms = _direct_baseline_ms(ip) if time.monotonic() < budget_ends else None
            if base_ms is not None:
                rep["direct_ms"] = base_ms
                rep["proxy_added_ms"] = max(0, latency_ms - base_ms)
        else:
            # Say what was ACTUALLY tried, and no more. Two things were wrong here. (1) It named
            # `proxy_scheme` — the UI SELECTOR — when a line carrying its own `socks5://` overrides the
            # selector entirely, so a SOCKS5 line checked with the selector on HTTP reported "no answer as
            # HTTP" about a request that went out as SOCKS5. (2) It claimed "the other transport did not
            # answer either" even when no such retry was attempted, which is exactly the case for a line
            # with an explicit scheme. Both statements read as evidence and neither was measured.
            used = (rep.get("proxy_scheme_used") or _effective_scheme(proxy, proxy_scheme)).upper()
            tried_both = retried_scheme is not None or "://" not in str(proxy)
            # If the proxy SAID why, repeat it. A vendor that answers CONNECT with "503 No exit node"
            # has told us the account is fine and its pool is empty — reporting that as a bare "it is
            # down, or the credentials are wrong" sends the user to re-check the one thing that was
            # never broken. Only fall back to the list of possibilities when nothing was said.
            spoken = next((e for e in probe_errs
                           if e and not re.search(r"(?i)tim(ed )?out|timeout", e)), "")
            reason = _safe_reason(spoken, proxy)
            if reason:
                rep["proxy_error"] = reason
                why = "the proxy answered: " + reason
            else:
                why = (f"nothing came back within {TIMEOUT}s, then nothing within {SLOW_TIMEOUT}s on a "
                       "retry. It is down, unreachable, out of plan quota, or the credentials are wrong.")
            rep["notes"].append(
                f"Proxy: no answer as {used}"
                + (", and the other transport did not answer either" if tried_both else "")
                + " — " + why)
    if not rep.get("ip"):
        if not ip:
            # EVERY return carries a verdict. This one used to return a report with no `verdict` key at
            # all, and the page did `r.verdict.toUpperCase()` on it — so a proxy that simply didn't answer
            # rendered as "FAILED · TypeError: Cannot read properties of undefined". A dead proxy is an
            # ordinary, expected outcome and has to read like one.
            rep["notes"].append("Exit IP: lookup failed — proxy down, or no route out?")
            rep["verdict"] = "unknown"
            rep["verdict_factors"] = ["No exit IP — the proxy did not answer, so nothing could be checked"]
            rep["verdict_reason"] = rep["verdict_factors"][0]
            rep["flags"] = []
            return rep
        rep["ip"] = ip      # geo failed, but we were told which IP to check — carry on without it

    # Settle WHICH address this report is about BEFORE any source is asked about it. A dual-stack exit can
    # answer over either family, and the IPv4 one is the address 17 blocklist zones can speak to (the IPv6
    # table holds four), so it is the one we report. Doing this after the reputation lookups — as it was —
    # measured IPQS/AbuseIPDB/getIPIntel/Scamalytics against the IPv6 address and then relabelled the whole
    # report with the IPv4 one: a set of measurements attributed to an address they were never taken on.
    if not reverse_v4(rep["ip"]) and not ip:
        v4 = lookup_exit_v4(opener, deadline=budget_ends)
        if v4:
            rep["exit_ipv6"] = rep["ip"]
            rep["ip"] = v4
            # Re-ask about the IPv4 address. `merge(geo)` above filled isp/location/country_code/timezone
            # from the IPv6 record, and swapping only rep["ip"] would leave those four attributed to an
            # address they were never measured on — the same mis-attribution this block's own comment
            # documents fixing for IPQS/AbuseIPDB. A dual-stack exit usually agrees with itself, but
            # "usually" is not a measurement, and country_code paints the flag while timezone drives the
            # device-vs-IP alignment. Costs one request, and only on the rare dual-stack path.
            # Same grace the liveness probe got: a proxy that needed the slow retry to answer at all will
            # miss a plain 8s budget here too, and this call failing costs the report its ISP/location/
            # timezone (dropped, correctly, rather than relabelled just below).
            v4_left = budget_left()
            v4_geo = lookup_geo(opener, v4, timeout=min(SLOW_TIMEOUT, v4_left)) if v4_left else {}
            merge(v4_geo)
            # merge() writes every non-None field INCLUDING "ip", so the re-lookup's own echo of the
            # address would silently become the reported one. rep["ip"] is settled here and nowhere else.
            rep["ip"] = v4
            if not v4_geo:
                # The re-lookup FAILED (timeout, rate-limit). merge({}) is a no-op, so the IPv6 record's
                # fields would survive and be presented as facts about the IPv4 address — the exact
                # mis-attribution this block exists to prevent, reintroduced on the error path. DROP them:
                # a dash is honest, a wrong ISP or timezone is not, and `timezone` here drives the
                # device-vs-IP alignment a user acts on.
                for k in ("isp", "location", "country_code", "timezone"):
                    rep.pop(k, None)
                rep["notes"].append("Exit: dual-stack — also reachable at " + rep["exit_ipv6"]
                                    + ". The IPv4 address could not be geolocated, so ISP/location/"
                                    "timezone are omitted rather than carried over from the IPv6 record")
            else:
                rep["notes"].append("Exit: dual-stack — also reachable at " + rep["exit_ipv6"]
                                    + "; every check ran on the IPv4 address, which 17 blocklist zones "
                                      "cover")
        else:
            rep["notes"].append("Exit: IPv6 only — checked against the 4 zones that hold IPv6 data")

    if not ipqs_key:
        rep["notes"].append("No IPQualityScore key — no fraud score (set it in the Keys row)")
    elif budget_left("IPQualityScore"):
        merge(lookup_ipqs(rep["ip"], ipqs_key, opener))
    if abuse_key and budget_left("AbuseIPDB"):
        merge(lookup_abuseipdb(rep["ip"], abuse_key, opener))
    if getipintel_contact and budget_left("getIPIntel"):
        merge(lookup_getipintel(rep["ip"], getipintel_contact, opener))
    # Must run BEFORE connection_class() below, or its classifier is dead code. No "no key" note (unlike
    # IPQS): the tool is fully useful without it, and half a pair is a config mistake, not a measurement.
    if scam_user and scam_key and budget_left("Scamalytics"):
        merge(lookup_scamalytics(rep["ip"], scam_user, scam_key, opener))
    bl_ip = rep["ip"]

    # ponytail: the blocklist queries go out the LOCAL resolver, not the proxy — an HTTP proxy
    # can't carry DNS. That's fine here: the query names the exit IP explicitly, so the answer is
    # about that IP either way. (On-device it must go through the tunnel, because there the lookup
    # is also how the IP itself is learned.)
    rep.update(dnsbl_check(bl_ip))
    # ip-api.com — keyless hosting/proxy/mobile classifier. Runs BEFORE connection_class so a no-key user
    # still gets a real exit-type verdict (hosting→datacenter, mobile→mobile). Its own opener, queried
    # directly about the exit IP; failure/rate-limit just yields no signal.
    rep.update(_ipapi_lookup(rep["ip"], urllib.request.build_opener()))
    if rep.get("ipapi_mobile"):
        rep["mobile"] = True
    if rep.get("ipapi_proxy"):
        rep["notes"].append("ip-api.com: flagged this exit as a proxy/VPN")
    cc = connection_class(rep)
    if cc:
        rep["connection_class"] = cc     # "datacenter" / "mobile" — the strongest usability signal
    rep["verdict"], rep["verdict_factors"] = verdict_factors(rep)
    rep["verdict_reason"] = _VERDICT_LEAD.get(rep["verdict"], "") + ", ".join(rep["verdict_factors"])
    rep["flags"] = flags(rep)
    return rep


# ---- config --------------------------------------------------------------------------------


# What the local UI reads back and writes. ONE tuple, because the same list was duplicated across the
# GET and the POST and a source added to only one of them saves but never loads.
CONFIG_KEYS = ("proxy", "proxy_scheme", "ipqs_key", "abuse_key", "getipintel_contact",
               "scamalytics_user", "scamalytics_key")


def load_config() -> dict:
    try:
        return json.loads(CONFIG.read_text("utf-8"))
    except Exception:
        return {}


def save_config(cfg: dict) -> None:
    try:
        CONFIG.write_text(json.dumps(cfg, indent=2), "utf-8")
        os.chmod(CONFIG, 0o600)     # it holds API keys
    except Exception:
        pass


def resolve_keys(args, cfg: dict) -> dict:
    """flag -> env -> config, for every source. A dict rather than a tuple because Scamalytics is a
    USER + KEY pair — the first source that isn't one value, and a positional tuple would keep growing."""
    def pick(flag: str, env: str, key: str) -> str:
        return (getattr(args, flag, "") or os.environ.get(env) or cfg.get(key) or "").strip()
    return {
        "ipqs": pick("ipqs_key", "IPQS_KEY", "ipqs_key"),
        "abuse": pick("abuse_key", "ABUSEIPDB_KEY", "abuse_key"),
        # getIPIntel needs a contact email, not a key — free, no signup.
        "contact": pick("getipintel_contact", "GETIPINTEL_CONTACT", "getipintel_contact"),
        "scam_user": pick("scamalytics_user", "SCAMALYTICS_USER", "scamalytics_user"),
        "scam_key": pick("scamalytics_key", "SCAMALYTICS_KEY", "scamalytics_key"),
    }


# ---- local web UI --------------------------------------------------------------------------

# Static assets the page's <head> links to. Served from the repo checkout by the LOCAL server only; on
# Vercel they are ordinary static files (see webapp/vercel.json). sw.js is deliberately NOT here — an
# offline shell for a localhost dev server would just cache a stale page over the one being edited.
WEBAPP = Path(__file__).resolve().parent.parent / "webapp"
STATIC = {
    "icon.svg": "image/svg+xml",
    "icon-maskable.svg": "image/svg+xml",
    "favicon-16.png": "image/png",
    "favicon-32.png": "image/png",
    "apple-touch-icon.png": "image/png",
    "icon-192.png": "image/png",
    "icon-512.png": "image/png",
    "icon-maskable-512.png": "image/png",
    "manifest.webmanifest": "application/manifest+json",
}

PAGE = r"""<!doctype html>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Specter · exit-IP signal desk</title>
<!-- Icons + install metadata. The SVG is the master and every browser worth counting takes it; the PNGs
     exist because iOS ignores an SVG favicon entirely and the manifest needs raster sizes. All of them are
     rasterised from webapp/icon.svg by webapp/make-icons.py, so they cannot drift apart. -->
<link rel="icon" href="/icon.svg" type="image/svg+xml">
<link rel="icon" href="/favicon-32.png" sizes="32x32" type="image/png">
<link rel="icon" href="/favicon-16.png" sizes="16x16" type="image/png">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="manifest" href="/manifest.webmanifest">
<meta name="theme-color" content="#16161A">
<style>
/* "Signal desk": a monospace network instrument. One accent, semantic bands, works light + dark. */
:root{
  --bg:#0b0c0f; --grid:#12141a; --panel:#131519; --panel2:#181b21; --line:#242832;
  --ink:#e9ebef; --soft:#a3a8b2; --dim:#666c78;
  --accent:#57e3bf; --accent-ink:#04110d;
  --clean:#5fd39a; --suspect:#f2c04b; --warn:#f0954e; --dirty:#f07070; --info:#63b8ea;
  --glow:0 0 0 1px rgba(87,227,191,.14), 0 8px 30px -12px rgba(0,0,0,.7);
  --mono:"Cascadia Code","JetBrains Mono","SFMono-Regular",ui-monospace,"Menlo",Consolas,monospace;
  --sans:-apple-system,"Segoe UI",system-ui,"Helvetica Neue",Arial,sans-serif;
}
:root[data-theme=light]{
  --bg:#efece3; --grid:#e6e2d6; --panel:#fbfaf6; --panel2:#f2efe6; --line:#e0dbcd;
  --ink:#191b1f; --soft:#565a62; --dim:#8b8f97;
  --accent:#0e9c81; --accent-ink:#ffffff;
  --clean:#1f9d63; --suspect:#b8860a; --warn:#c26a1c; --dirty:#d0483f; --info:#2a7fb8;
  --glow:0 0 0 1px rgba(14,156,129,.14), 0 10px 30px -16px rgba(60,50,20,.35);
}
@media(prefers-color-scheme:light){:root:not([data-theme=dark]){
  --bg:#efece3; --grid:#e6e2d6; --panel:#fbfaf6; --panel2:#f2efe6; --line:#e0dbcd;
  --ink:#191b1f; --soft:#565a62; --dim:#8b8f97;
  --accent:#0e9c81; --accent-ink:#ffffff;
  --clean:#1f9d63; --suspect:#b8860a; --warn:#c26a1c; --dirty:#d0483f; --info:#2a7fb8;
  --glow:0 0 0 1px rgba(14,156,129,.14), 0 10px 30px -16px rgba(60,50,20,.35);
}}
*{box-sizing:border-box}
body{margin:0;color:var(--ink);font:15px/1.55 var(--sans);
  background:
    linear-gradient(var(--grid) 1px,transparent 1px) 0 0/100% 46px,
    radial-gradient(1200px 500px at 78% -8%, color-mix(in srgb,var(--accent) 8%,transparent), transparent 70%),
    var(--bg);
  -webkit-font-smoothing:antialiased;}
.wrap{max-width:880px;margin:0 auto;padding:30px 20px 72px}
.top{display:flex;align-items:center;justify-content:space-between;margin:0 0 20px}
.brand{display:flex;align-items:center;gap:10px;font:600 12px/1 var(--mono);letter-spacing:.18em;text-transform:uppercase;color:var(--soft)}
.pulse{width:8px;height:8px;border-radius:50%;background:var(--accent);box-shadow:0 0 0 0 color-mix(in srgb,var(--accent) 60%,transparent);animation:pulse 2.4s infinite}
@keyframes pulse{0%{box-shadow:0 0 0 0 color-mix(in srgb,var(--accent) 55%,transparent)}70%{box-shadow:0 0 0 7px transparent}100%{box-shadow:0 0 0 0 transparent}}
.theme{background:none;border:1px solid var(--line);color:var(--soft);border-radius:8px;width:34px;height:30px;cursor:pointer;font-size:14px;padding:0}
.theme:hover{border-color:var(--accent);color:var(--accent)}
.actions{display:flex;align-items:center;gap:8px}
.theme svg{display:block;width:16px;height:16px;margin:auto}
/* Settings sheet — a native <dialog>, so ESC + backdrop-click + focus-trap come free. */
dialog#settings{background:var(--panel);color:var(--ink);border:1px solid var(--line);border-radius:14px;padding:0;width:min(440px,calc(100vw - 32px));box-shadow:var(--glow)}
dialog#settings::backdrop{background:rgba(0,0,0,.55)}
.sheet{padding:18px}
.sheethead{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0 0 4px}
.sheethead h2{font:600 11px/1 var(--mono);letter-spacing:.16em;text-transform:uppercase;color:var(--soft);margin:0}
.sheetclose{background:none;border:1px solid var(--line);color:var(--soft);border-radius:8px;width:30px;height:28px;cursor:pointer;font-size:15px;line-height:1;padding:0}
.sheetclose:hover{border-color:var(--accent);color:var(--accent)}
.panel{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:18px}
.field label{display:block;font:600 10px/1 var(--mono);letter-spacing:.14em;text-transform:uppercase;color:var(--dim);margin:0 0 7px}
.kst{font:600 9px/1 var(--mono);letter-spacing:.04em;text-transform:none;color:var(--clean)}
input,select{width:100%;background:var(--panel2);border:1px solid var(--line);border-radius:8px;
  color:var(--ink);padding:11px 12px;font:14px/1.2 var(--mono)}
input::placeholder{color:var(--dim)}
input:focus,select:focus{outline:none;border-color:var(--accent);box-shadow:0 0 0 3px color-mix(in srgb,var(--accent) 18%,transparent)}
.grid{display:grid;grid-template-columns:1fr 210px;gap:12px}
@media(max-width:600px){.grid{grid-template-columns:1fr}}
.proxyrow{display:flex;gap:8px}.proxyrow select{width:108px;flex:none;cursor:pointer}
details{margin-top:13px}summary{cursor:pointer;color:var(--soft);font:600 11px/1 var(--mono);letter-spacing:.08em;text-transform:uppercase;list-style:none}
summary::-webkit-details-marker{display:none}summary::before{content:"+ ";color:var(--accent)}
details[open] summary::before{content:"− "}
.go{width:100%;margin-top:16px;background:var(--accent);color:var(--accent-ink);border:0;border-radius:9px;
  padding:14px;font:600 13px/1 var(--mono);letter-spacing:.1em;text-transform:uppercase;cursor:pointer;transition:transform .08s,filter .15s}
.go:hover{filter:brightness(1.06)}.go:active{transform:translateY(1px)}
.go:disabled{background:var(--panel2);color:var(--dim);cursor:default;filter:none}
#out{margin-top:16px;display:flex;flex-direction:column;gap:13px}
.blk{animation:rise .42s cubic-bezier(.2,.7,.2,1) both}
@keyframes rise{from{opacity:0;transform:translateY(9px)}to{opacity:1;transform:none}}
.verdict{position:relative;overflow:hidden;background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:17px 18px 17px 22px;box-shadow:var(--glow)}
.verdict::before{content:"";position:absolute;left:0;top:0;bottom:0;width:4px;background:var(--vc,var(--dim))}
.verdict .v{display:flex;align-items:baseline;gap:11px}
.verdict b{font:700 20px/1 var(--mono);letter-spacing:.06em;color:var(--vc,var(--ink))}
.verdict .dot{width:9px;height:9px;border-radius:50%;background:var(--vc);align-self:center}
.verdict span{display:block;color:var(--soft);font-size:13px;margin-top:6px}
.v-dirty{--vc:var(--dirty)}.v-suspect{--vc:var(--suspect)}.v-clean{--vc:var(--clean)}.v-unknown{--vc:var(--dim)}
.iprow{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.ip{font:600 30px/1.1 var(--mono);letter-spacing:-.02em;word-break:break-all}
.copy{background:var(--panel2);border:1px solid var(--line);color:var(--soft);border-radius:7px;padding:6px 10px;
  font:600 10px/1 var(--mono);letter-spacing:.1em;text-transform:uppercase;cursor:pointer;transition:.15s}
.copy:hover{border-color:var(--accent);color:var(--accent)}
/* Copy confirmation is a colour flash, never a label swap — a wider "Copied ✓" resizes the button. */
.copy.done,.cc.done{border-color:var(--clean);color:var(--clean);
  background:color-mix(in srgb,var(--clean) 18%,transparent)}
.sub{color:var(--soft);font:13px/1.5 var(--mono);margin-top:8px}
.meter{margin-top:16px}
.meter .head{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:8px}
.meter .score{font:700 34px/1 var(--mono);color:var(--mc)}
.meter .cap{font:600 10px/1 var(--mono);letter-spacing:.12em;text-transform:uppercase;color:var(--dim);text-align:right}
.track{position:relative;height:9px;border-radius:6px;background:
  linear-gradient(90deg,color-mix(in srgb,var(--clean) 30%,transparent) 0 60%,
  color-mix(in srgb,var(--suspect) 32%,transparent) 60% 85%,
  color-mix(in srgb,var(--dirty) 34%,transparent) 85% 100%)}
.track .mk{position:absolute;top:-4px;width:3px;height:17px;border-radius:2px;background:var(--mc);
  box-shadow:0 0 8px var(--mc);transform:translateX(-50%);transition:left .5s cubic-bezier(.2,.7,.2,1)}
.scale{display:flex;justify-content:space-between;margin-top:6px;font:10px/1 var(--mono);color:var(--dim)}
/* Flex, not grid: a grid column count is fixed per row, so a 5th tile lands alone in column 1 with three
   dead columns beside it. Flex lets the last row's tiles grow into the space instead of stranding one. */
.tiles{display:flex;flex-wrap:wrap;gap:12px}
/* Basis 120px so the realistic tile counts (4-5) all fit ONE row at the 760px page width and grow to fill
   it; anything left over on a final row grows too, rather than sitting at a quarter width beside dead
   space. min-width:0 is required — a flex item otherwise floors at its CONTENT width, which both widens
   the tile with the longest caption and stops that caption's ellipsis from ever engaging. */
.tile{flex:1 1 min(190px, calc(50% - 6px));min-width:0;background:var(--panel);border:1px solid var(--line);border-radius:11px;padding:15px}
.tile em{font-style:normal;display:block;font:600 10px/1 var(--mono);letter-spacing:.12em;text-transform:uppercase;color:var(--dim)}
.tile strong{display:block;font:700 27px/1 var(--mono);margin:9px 0 5px}
/* One line, always. Tiles share a grid row, so a caption that wraps to three lines makes EVERY tile that
   tall — the caption is clipped instead, and the full text lives in the detail breakdown below. */
.tile small{display:block;color:var(--soft);font-size:12px;line-height:1.4;
  white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.chips{display:flex;flex-wrap:wrap;gap:7px}
.chip{font:600 11px/1 var(--mono);letter-spacing:.04em;padding:6px 11px;border-radius:7px;
  background:color-mix(in srgb,var(--suspect) 16%,transparent);color:var(--suspect);border:1px solid color-mix(in srgb,var(--suspect) 30%,transparent)}
.chip.ok{background:color-mix(in srgb,var(--clean) 15%,transparent);color:var(--clean);border-color:color-mix(in srgb,var(--clean) 28%,transparent)}
.lbl{font:600 10px/1 var(--mono);letter-spacing:.12em;text-transform:uppercase;color:var(--dim);margin-bottom:11px}
/* Flags read as an unfinished box when a label sits above three small chips in a full-width panel.
   Inline the label instead, so the row fills its width by construction. */
.flagbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.flagbar .lbl{margin:0;flex:none}
.vfx{display:flex;flex-wrap:wrap;gap:6px;margin-top:11px}
.vfx span{font:600 10px/1 var(--mono);letter-spacing:.04em;padding:5px 9px;border-radius:6px;
  background:color-mix(in srgb,var(--vc) 14%,transparent);color:var(--vc);
  border:1px solid color-mix(in srgb,var(--vc) 28%,transparent)}
/* Detail breakdown: collapsed by default — every field every source returned, for when a verdict needs auditing. */
details.deep{margin-top:0}
details.deep>summary{background:var(--panel);border:1px solid var(--line);border-radius:11px;padding:13px 16px}
details.deep[open]>summary{border-bottom-left-radius:0;border-bottom-right-radius:0}
.src{background:var(--panel);border:1px solid var(--line);border-top:0;padding:14px 16px}
.src:last-child{border-radius:0 0 11px 11px}
.src h4{margin:0 0 11px;display:flex;justify-content:space-between;align-items:baseline;gap:12px;
  font:600 11px/1.3 var(--mono);letter-spacing:.12em;text-transform:uppercase;color:var(--accent)}
.src h4 em{font-style:normal;font-weight:400;letter-spacing:.02em;text-transform:none;color:var(--soft);text-align:right}
.kv{display:grid;grid-template-columns:repeat(auto-fit,minmax(235px,1fr));gap:0 22px}
/* ONE LINE per field, no exceptions. A value that wraps mid-word ragged-ends its neighbours and the whole
   grid stops lining up; the full text is on the element's title and survives a copy. */
.kv>div{display:flex;justify-content:space-between;align-items:baseline;gap:14px;padding:7px 0;
  border-bottom:1px solid var(--line);font:12px/1.5 var(--mono);white-space:nowrap;overflow:hidden}
.kv i{font-style:normal;color:var(--dim);flex:none}
.kv b{font-weight:600;overflow:hidden;text-overflow:ellipsis;min-width:0}
.dimnote{color:var(--dim);font-size:11px}
img.flag{width:14px;height:10px;border-radius:1px;vertical-align:0;margin-right:6px;
  box-shadow:0 0 0 1px color-mix(in srgb,var(--ink) 15%,transparent)}
svg.ico{width:13px;height:13px;margin-right:7px;vertical-align:-2px;flex:none}
/* Blocklists GROUPED by what the answer means — the group label carries the meaning, so a colour never has
   to be decoded. A flat rainbow of 17 chips is unreadable. */
.zgrp{padding:9px 0;border-bottom:1px solid var(--line)}
.zgrp:last-child{border-bottom:0}
.zgrp em{font-style:normal;display:block;margin-bottom:7px;font:600 10px/1.4 var(--mono);letter-spacing:.1em;text-transform:uppercase}
.zgrp em span{letter-spacing:.02em;text-transform:none;font-weight:400;color:var(--dim)}
.zones{display:flex;flex-wrap:wrap;gap:6px}
.z{font:600 10px/1 var(--mono);padding:6px 9px;border-radius:6px;border:1px solid var(--line);color:var(--soft)}
.g-listed em{color:var(--dirty)} .g-listed .z{background:color-mix(in srgb,var(--dirty) 15%,transparent);color:var(--dirty);border-color:color-mix(in srgb,var(--dirty) 30%,transparent)}
.g-policy em{color:var(--info)} .g-policy .z{background:color-mix(in srgb,var(--info) 14%,transparent);color:var(--info);border-color:color-mix(in srgb,var(--info) 28%,transparent)}
.g-clean em{color:var(--clean)} .g-clean .z{color:var(--clean);border-color:color-mix(in srgb,var(--clean) 26%,transparent)}
.g-none em{color:var(--dim)} .g-none .z{opacity:.6}
/* Form hints reuse the label→meaning row: scannable in one pass, never a paragraph to decipher. */
.hint{margin-top:12px}
.hint .rw{padding:7px 0;font-size:12.5px}
.hint .rw i{width:84px}
.hint .rw div{color:var(--soft);word-break:normal;overflow-wrap:break-word;line-height:1.55}
.rows{display:flex;flex-direction:column}
.rw{display:flex;gap:14px;padding:10px 0;border-top:1px solid var(--line);font-size:13px}
.rw:first-child{border-top:0}
.rw i{font-style:normal;color:var(--dim);width:118px;flex:none;font:600 10px/1.4 var(--mono);letter-spacing:.08em;text-transform:uppercase;padding-top:2px}
.rw div{font-family:var(--mono);overflow-wrap:anywhere;word-break:normal}
.note{color:var(--soft);font-size:12.5px;padding:4px 0}.note+.note{border-top:1px solid var(--line);margin-top:2px;padding-top:8px}
textarea{width:100%;background:var(--panel2);border:1px solid var(--line);border-radius:8px;color:var(--ink);
  padding:11px 12px;font:13px/1.5 var(--mono);resize:vertical;margin-top:12px}
textarea:focus{outline:none;border-color:var(--accent);box-shadow:0 0 0 3px color-mix(in srgb,var(--accent) 18%,transparent)}
/* A comparison table earns far more width than the form column: the whole point is reading every proxy's
   signals side by side. Break out of the 760px wrap and centre on the viewport. overflow-x remains as the
   small-screen fallback, but on a desktop the columns should simply fit. */
/* The breakout element must NOT also carry `.blk`: that class's rise animation ends on `transform:none`
   with fill-mode both, which permanently wipes the centring translate and leaves the panel hanging off the
   right edge of the page. So `.bulkwide` is a plain outer wrapper and `.blk` stays on the inner panel.
   The 48px gutter is not decoration either — 100vw INCLUDES the vertical scrollbar, so a smaller inset
   makes the panel wider than the viewport and the whole page picks up a horizontal scrollbar. */
.bulkwide{width:min(1440px,calc(100vw - 48px));margin-left:50%;transform:translateX(-50%)}
/* Sortable headers — comparing a batch means reordering it by whichever signal you care about. */
table.bulk th.s{cursor:pointer;user-select:none;white-space:nowrap}
table.bulk th.s:hover{color:var(--accent)}
table.bulk th.s::after{content:"";display:inline-block;width:9px;color:var(--accent)}
table.bulk th.s[data-dir="1"]::after{content:"▲"}
table.bulk th.s[data-dir="-1"]::after{content:"▼"}
/* Batch summary strip: the whole-run answer, above the per-row detail. */
.bsum{display:flex;flex-wrap:wrap;gap:8px;padding:0 0 12px}
.bsum span{font:600 10.5px/1 var(--mono);letter-spacing:.05em;padding:7px 10px;border-radius:7px;
  border:1px solid var(--line);color:var(--soft);background:var(--panel2)}
.bsum span b{font-weight:700;color:var(--ink)}
.bsum .s-dirty b{color:var(--dirty)} .bsum .s-suspect b{color:var(--suspect)}
.bsum .s-clean b{color:var(--clean)} .bsum .s-dead b{color:var(--dirty)}
.tablewrap{overflow-x:auto}
/* Scrollbars follow the theme. The default light-grey OS bar sits under a dark panel looking like a
   rendering fault, and this table is horizontally scrollable BY DESIGN, so its bar is on screen the whole
   time. `scrollbar-color` covers Firefox; the ::-webkit rules cover Chrome and Safari. */
*{scrollbar-color:var(--line) transparent;scrollbar-width:thin}
::-webkit-scrollbar{width:10px;height:10px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--line);border-radius:6px;border:2px solid transparent;
  background-clip:content-box}
::-webkit-scrollbar-thumb:hover{background:var(--dim);background-clip:content-box;border:2px solid transparent}
::-webkit-scrollbar-corner{background:transparent}
/* A column heading names its SOURCE, so a number is never anonymous. The full name is on the title, and
   the sub-line under a value carries the secondary fact (a policy listing, a band) instead of widening the
   column with it. */
table.bulk th em{font-style:normal;color:var(--dim);font-weight:400}
.sub{display:block;font-size:9.5px;line-height:1.35;color:var(--dim);letter-spacing:.02em}
/* A verdict's reason is prose, and prose has no natural width — unbounded it made Verdict the second
   widest column in the table and pushed the measurements off to the right. Same cap-and-hover idiom as
   the exit IP: two lines of reason is enough to recognise WHY, the rest is on the title. */
table.bulk td .sub{max-width:210px;overflow:hidden;text-overflow:ellipsis}
/* Detected-as codes: three letters each, full meaning on hover. Spelling them out
   ("VPN Proxy Recent abuse Bot") made this the widest column on the table for the least information. */
.fx{display:inline-block;padding:2px 4px;margin-right:3px;border-radius:4px;font-size:10px;font-weight:700;
  background:color-mix(in srgb,var(--suspect) 15%,transparent);color:var(--suspect)}
/* Copy chip: one click copies, a tick confirms. The tick's slot is ALWAYS reserved, so confirming never
   changes the chip's width and never nudges the row. */
.cp{position:relative;display:inline-block;cursor:pointer;border:1px solid var(--line);border-radius:6px;
  background:var(--panel2);color:var(--soft);font:600 10.5px/1 var(--mono);padding:6px 20px 6px 8px;
  margin:0 5px 0 0;max-width:190px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;vertical-align:middle}
.cp:hover{border-color:var(--accent);color:var(--accent)}
.cp::after{content:"";position:absolute;right:7px;top:50%;transform:translateY(-50%);font-size:11px;line-height:1}
.cp.done{border-color:var(--clean);color:var(--clean);background:color-mix(in srgb,var(--clean) 16%,transparent)}
.cp.done::after{content:"✓"}
/* A copy that FAILED must not look like one that worked — the reserved slot means neither state resizes
   the chip, so the mark is the only thing that changes. */
.cp.failed,.copy.failed{border-color:var(--dirty);color:var(--dirty);
  background:color-mix(in srgb,var(--dirty) 16%,transparent)}
.cp.failed::after{content:"✕"}
.cp i{font-style:normal;color:var(--dim);margin-right:5px;font-weight:400}
.cp:hover i{color:inherit;opacity:.7}
/* Details chevron: right when collapsed, down when open. One click, no second step. */
.chev{background:none;border:0;cursor:pointer;padding:6px;line-height:0;color:var(--soft)}
.chev:hover{color:var(--accent)}
.chev svg{width:13px;height:13px;transition:transform .15s}
.chev[aria-expanded=true] svg{transform:rotate(90deg)}
/* The expanded row: a plain two-column table, same language as the rest of the page. */
table.det{width:100%;border-collapse:collapse;font:12px/1.5 var(--mono);background:var(--panel2)}
table.det td{padding:6px 12px;border-bottom:1px solid var(--line);vertical-align:top}
table.det tr:last-child td{border-bottom:0}
table.det td:first-child{width:150px;color:var(--dim);white-space:nowrap;
  font:600 9.5px/1.6 var(--mono);letter-spacing:.09em;text-transform:uppercase}
table.det td:last-child{word-break:break-all}
table.det .grp td{background:color-mix(in srgb,var(--accent) 7%,transparent);color:var(--accent);
  font:600 9.5px/1.6 var(--mono);letter-spacing:.11em;text-transform:uppercase}
/* Comparison-table cells. Every value is ONE line: the table is for scanning down a column, and a cell
   that wraps destroys the alignment that makes that possible. Long text truncates with the full string on
   the title. */
/* Columns size to their CONTENT (auto layout), they are not rationed out of a fixed budget. Two earlier
   attempts failed the other way: `max-width:0` made a percentage max-width on a child resolve against ~0
   and collapsed the proxy host to one character; `table-layout:fixed` then squeezed whatever was left
   after the declared widths, so ISP read "Spa..." and Location "Lousvil...". Everything is nowrap, so a
   column is exactly as wide as its widest value, and only the two genuinely unbounded fields are capped
   (with the full text on the title). If the total still exceeds the panel, the TABLE scrolls — never the
   page. */
table.bulk{table-layout:auto;width:100%}
table.bulk td,table.bulk th{white-space:nowrap}
table.bulk td.cap{max-width:230px;overflow:hidden;text-overflow:ellipsis}
table.bulk td .trunc{display:block;overflow:hidden;text-overflow:ellipsis}
table.bulk th.cw,table.bulk td.cw{width:30px;padding-left:4px;padding-right:0}
/* The HOST truncates; the PORT never does. The port is the only thing distinguishing one line from the
   next, so it must survive whatever the column width is. */
/* The host truncates, the PORT never does — a vendor hands out one hostname and N ports, so the port is
   the only thing telling one row from the next. Full line on the title. */
.pxh{color:var(--ink);display:inline-block;max-width:112px;overflow:hidden;text-overflow:ellipsis;
  white-space:nowrap;vertical-align:bottom}
.pxp{color:var(--accent);font-weight:700}
/* Same idiom as .pxh above. `table-layout:auto` sizes each column to its widest value and every cell is
   nowrap, so ONE IPv6-only exit (39 chars ≈ 280px against ~108px for an IPv4) widens the Exit IP column
   and shoves every column after it out of alignment. 132px clears the longest IPv4 (15 chars ≈ 108px at
   12px in the --mono stack, all of whose members are ≤0.6em advance) with room to spare, so the common
   case never ellipsises. Full address on the title, and in the row's detail chip. */
.ipv{font-size:12px;display:inline-block;max-width:132px;overflow:hidden;text-overflow:ellipsis;
  white-space:nowrap;vertical-align:bottom}
.ms{font-variant-numeric:tabular-nums}
.dim{color:var(--dim)}
.c-clean{color:var(--clean)} .c-suspect{color:var(--suspect)} .c-warn{color:var(--warn)} .c-dirty{color:var(--dirty)}
/* A link inside a readout. Underlined on hover only, so a table of values doesn't turn into a page of
   underlines, but it must LOOK clickable — a bare URL rendered as text is not a link, however valid. */
a.lnk{color:var(--accent);text-decoration:none;border-bottom:1px dotted color-mix(in srgb,var(--accent) 45%,transparent)}
a.lnk:hover{border-bottom-style:solid}
.c-info{color:var(--info)}
.tag{margin-left:5px;padding:2px 5px;border-radius:4px;font:600 9px/1.4 var(--mono);
  background:color-mix(in srgb,var(--info) 16%,transparent);color:var(--info);vertical-align:1px}
.detrow>td{padding:0 0 10px !important;white-space:normal !important;max-width:none !important}
table.bulk tr.open>td{background:var(--panel2)}
table.bulk{width:100%;border-collapse:collapse;font:12.5px/1.4 var(--mono)}
/* 7px of horizontal padding, not 10. Across fifteen columns that is ~90px of the horizontal scroll,
   bought back without dropping a single value. */
table.bulk th{text-align:left;font:600 9px/1 var(--mono);letter-spacing:.1em;text-transform:uppercase;color:var(--dim);padding:9px 7px;border-bottom:1px solid var(--line);white-space:nowrap}
table.bulk td{padding:9px 7px;border-bottom:1px solid var(--line);vertical-align:middle}
table.bulk tr:last-child td{border-bottom:0}
table.bulk tbody tr:hover td{background:var(--panel2)}
.vpill{display:inline-block;padding:3px 9px;border-radius:6px;font:600 10px/1.5 var(--mono);letter-spacing:.04em}
.v-queued-p{background:color-mix(in srgb,var(--dim) 16%,transparent);color:var(--dim)}
/* A running row's pill breathes, so "still going" is visible without reading the clock beside it. */
.vpill.live{animation:livepulse 1.4s ease-in-out infinite}
@keyframes livepulse{0%,100%{opacity:1}50%{opacity:.45}}
.v-dirty-p{background:color-mix(in srgb,var(--dirty) 16%,transparent);color:var(--dirty)}
.v-suspect-p{background:color-mix(in srgb,var(--suspect) 16%,transparent);color:var(--suspect)}
.v-clean-p{background:color-mix(in srgb,var(--clean) 15%,transparent);color:var(--clean)}
.v-unknown-p{background:color-mix(in srgb,var(--dim) 16%,transparent);color:var(--soft)}
.cc{cursor:pointer;color:var(--soft);border:1px solid var(--line);border-radius:6px;padding:4px 8px;
  font:600 9px/1 var(--mono);background:none;letter-spacing:.06em;text-transform:uppercase}
.cc:hover{border-color:var(--accent);color:var(--accent)} .cc.done{border-color:var(--clean);color:var(--clean)}
.pxcell{max-width:230px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--soft)}
.loading{color:var(--soft);font:13px/1 var(--mono);letter-spacing:.06em}
.loading::after{content:"";animation:dots 1.4s steps(4,end) infinite}
@keyframes dots{0%{content:""}25%{content:"·"}50%{content:"··"}75%{content:"···"}}
</style>
<div class=wrap>
  <div class=top>
    <div class=brand><span class=pulse></span>Specter · exit-IP signal desk</div>
    <div class=actions>
      <button class=theme id=gear title="Settings — API keys" aria-label="Settings"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg></button>
      <button class=theme id=theme title="Toggle theme">◐</button>
    </div>
  </div>
  <div class=panel>
    <!-- Stacked, not side by side: these are two ALTERNATIVES, and a proxy overrides the IP field rather
         than complementing it. Reading top-to-bottom says that; a two-column row implies they combine. -->
    <div class=field><label for=ip>Check an IP</label><input id=ip placeholder="prefilled with your own"></div>
    <div class=field style="margin-top:14px"><label for=proxy>Or through a proxy — checks its exit IP instead</label>
      <div class=proxyrow>
        <select id=ptype><option value=http>HTTP</option><option value=socks5>SOCKS5</option><option value=socks4>SOCKS4</option></select>
        <input id=proxy placeholder="host:port:user:pass">
      </div></div>
    <details><summary>Accepted proxy formats</summary>
      <div class="rows hint">
        <div class=rw><i>No auth</i><div>host:port</div></div>
        <div class=rw><i>With auth</i><div>host:port:user:pass</div></div>
        <div class=rw><i>Or</i><div>user:pass@host:port</div></div>
        <div class=rw><i>With scheme</i><div>socks5://user:pass@host:port — wins over the selector</div></div>
        <div class=rw><i>Separator</i><div>; works anywhere : does — host;port;user;pass. A ; inside a
          password is left alone.</div></div>
      </div>
    </details>
    <!-- The button acts on the two fields directly above it, so nothing goes between them. Bulk is its own
         self-contained mode with its own input and its own button; keys are set-once config, so they go last. -->
    <button class=go id=go>Run check</button>
    <details><summary>Bulk — compare many proxies or IPs</summary>
      <textarea id=bulk rows=5 placeholder="one proxy or IP per line"></textarea>
      <button class=go id=bulkgo style="margin-top:10px">Check all</button>
      <div class="rows hint">
        <div class=rw><i>Input</i><div>one per line — a proxy (any format above) or a bare IP</div></div>
        <div class=rw><i>Bare IP</i><div>checked directly, shown as IP in the status column</div></div>
        <div class=rw><i>Transport</i><div>the selector above (proxies only)</div></div>
      </div>
    </details>
    <dialog id=settings><div class=sheet>
      <div class=sheethead><h2>API keys — optional</h2><button class=sheetclose id=settingsclose title="Close" aria-label="Close">✕</button></div>
      <div class=field style="margin-top:12px"><label for=ipqs>IPQualityScore <span id=ipqs-st class=kst></span></label><input id=ipqs type=password placeholder="your key · optional"></div>
      <div class=field style="margin-top:11px"><label for=abuse>AbuseIPDB <span id=abuse-st class=kst></span></label><input id=abuse type=password placeholder="your key · optional"></div>
      <!-- One credential, two fields. The username is type=text so you can see WHICH account is in use;
           the key is masked. One label and one status badge, because a half-set pair never runs. -->
      <div class=field style="margin-top:11px"><label for=scamuser>Scamalytics <span id=scam-st class=kst></span></label><input id=scamuser type=text placeholder="username · optional"></div>
      <div class=field style="margin-top:7px"><input id=scamkey type=password placeholder="API key"></div>
      <div class="rows hint">
        <div class=rw><i>Scamalytics</i><div>datacenter/VPN/Tor classifier · its score is shown, never scored</div></div>
        <div class=rw><i>No keys</i><div>datacenter · 17 blocklists · getIPIntel still run</div></div>
        <div class=rw><i>Blank field</i><div>use the server's key, if it has one</div></div>
        <div class=rw><i>Your key</i><div>overrides it</div></div>
        <div class=rw><i>Stored</i><div>~/.specter-ipcheck.json</div></div>
      </div>
    </div></dialog>
  </div>
  <div id=out></div>
</div>
<script>
const $=s=>document.querySelector(s), out=$('#out');
const q=new URLSearchParams(location.search);

// Install the offline shell. Failure is silent ON PURPOSE — a service worker is a convenience here (the
// tool needs the network to measure anything), so a registration error must never surface as a scary
// banner on a page that works fine. The LOCAL server serves no /sw.js, so this simply no-ops there.
if('serviceWorker' in navigator)
  addEventListener('load',()=>navigator.serviceWorker.register('/sw.js').catch(()=>{}));

// Theme: follow the OS, remember an explicit toggle.
const root=document.documentElement;
try{const sv=localStorage.getItem('specter-theme'); if(sv)root.dataset.theme=sv;}catch(e){}
$('#theme').onclick=()=>{
  const dark=root.dataset.theme?root.dataset.theme==='dark':matchMedia('(prefers-color-scheme:dark)').matches;
  root.dataset.theme=dark?'light':'dark';
  try{localStorage.setItem('specter-theme',root.dataset.theme);}catch(e){}
};

// Settings sheet: the cogwheel opens the API-key dialog; the ✕, ESC and a backdrop click all close it.
const settings=$('#settings');
$('#gear').onclick=()=>settings.showModal();
$('#settingsclose').onclick=()=>settings.close();
settings.addEventListener('click',e=>{ if(e.target===settings)settings.close(); });   // click the backdrop to dismiss

fetch('/config').then(r=>r.json()).then(c=>{
  $('#proxy').value=q.get('proxy')||c.proxy||'';
  $('#ptype').value=q.get('ptype')||c.proxy_scheme||'http';
  $('#ipqs').value=c.ipqs_key||''; $('#abuse').value=c.abuse_key||'';
  $('#scamuser').value=c.scamalytics_user||''; $('#scamkey').value=c.scamalytics_key||'';
  markKeys({});
  $('#ip').value=q.get('ip')||'';
  boot();
});
// Key status: "your key" when the field has a value, else "shared active" when the server (env) has one.
// _kst is initialised INSIDE markKeys, not above it: the config block runs before this line, and the
// Vercel build rewrites that block into a synchronous IIFE — a bare `window._kst={}` here would leave the
// first call assigning onto undefined.
// Field-list driven, because Scamalytics is a USER + KEY pair behind one badge: "your key" only once BOTH
// are filled, since half a pair never runs.
//
// The list lives INSIDE the function, like _kst above and for the same reason: the config block runs before
// this line, so a `const KEYFIELDS` at module scope is in the temporal dead zone when markKeys({}) is
// called from it — "Cannot access 'KEYFIELDS' before initialization", which kills the whole <script> and
// leaves a page that renders perfectly with every button dead. `node --check` cannot see it; only loading
// the page can, which is what the smoke test does.
function markKeys(st){const s=window._kst=Object.assign(window._kst||{},st||{});
  [['ipqs',['ipqs']],['abuse',['abuse']],['scam',['scamuser','scamkey']]].forEach(([id,fields])=>{
    const e=$('#'+id+'-st'); if(!e)return;
    e.textContent=fields.every(f=>$('#'+f).value)?'· your key':(s[id==='scam'?'scamalytics':id]?'· shared active':'');});}
['ipqs','abuse','scamuser','scamkey'].forEach(id=>$('#'+id).addEventListener('input',()=>markKeys()));
// On open: PREFILL "check directly" with the visitor's OWN public IP (client-side; ipwho.is is CORS-open),
// so checking your own IP is one click. Prefill only — the check never runs by itself; opening the page
// must not spend an API quota or a getIPIntel rate-limit slot the visitor didn't ask for.
// Two sources: ipwho.is answers HTTP 200 with {success:false} when it rate-limits a caller, so a single
// endpoint leaves the field blank exactly when the tool is being used a lot. It is the FALLBACK, not the
// first try, because it is dual-stack (AAAA measured 2026-08-07) — an IPv6 visitor would get a v6 address
// typed into the box and their very first check would run on the family only 4 blocklist zones cover.
// api4.ipify.org has no AAAA at all, so it always answers with the IPv4 address.
async function boot(){
  if($('#ip').value || $('#proxy').value)return;
  for(const [url,pick] of [['https://api4.ipify.org?format=json',o=>o&&o.ip],
                           ['https://ipwho.is/',o=>o&&o.success!==false&&o.ip]]){
    // Re-check emptiness AFTER the await: the fetch takes ~1s, and a user who typed an IP (or proxy) during
    // that window must NOT have it silently clobbered by the prefill. The upfront guard alone missed this.
    try{const ip=pick(await (await fetch(url)).json());
        if(ip && !$('#ip').value && !$('#proxy').value){$('#ip').value=ip; return;}}catch(e){}
  }
}
const esc=s=>String(s).replace(/[<>&"]/g,c=>({'<':'&lt;','>':'&gt;','&':'&amp;','"':'&quot;'}[c]));
// ONE vocabulary for IPQS's flags, shared by the bulk table and the single-check card. It used to live
// inside the bulk handler, so the single view fell back to printing the raw API value and the same signal
// read three different ways across the page — `PRX` in a table cell, `proxy` on a chip, `bot_status` on
// another. A reader should not have to learn that those are the same thing.
//
// The underscore in the pattern is load-bearing: IPQS sends `recent_abuse`, and the old `/recent abuse/`
// (space) never matched it, so the table silently printed the raw key next to properly abbreviated
// neighbours. A code table that can't name a signal must be obvious about it, not quietly inconsistent.
const SIGNALS=[[/tor/i,'TOR','Tor'],[/vpn/i,'VPN','VPN'],[/proxy/i,'PRX','Proxy'],
               [/recent[ _]?abuse|frequent/i,'ABU','Recent abuse'],[/bot/i,'BOT','Bot'],
               [/crawler|spider/i,'CRW','Crawler'],[/scanner/i,'SCN','Security scanner'],
               [/high.risk/i,'ATK','High-risk attacks'],
               // Found by test_every_ipqs_flag_label_has_a_short_code: these two are in IPQS_FLAGS and had
               // no abbreviation, so they rendered as full text wedged between three-letter neighbours.
               [/shared/i,'SHR','Shared connection'],[/mobile/i,'MOB','Mobile']];
// [shortCode, fullName] for a raw flag key. An unknown flag keeps its own text rather than being dropped —
// a code table must never silently swallow a signal it has no abbreviation for.
const signalOf=s=>{const m=SIGNALS.find(([re])=>re.test(s));
  return m?[m[1],m[2]]:[String(s),String(s)];};

// How long THIS row has been waiting on its proxy. Seconds, because a bulk check of cold residential
// exits runs 5-20s a row and the whole point is being able to see which one is dragging.
const elapsed=x=>x.startedAt?((Date.now()-x.startedAt)/1000).toFixed(0)+'s':'';
// One honest line about the run: how many are done, how many are in flight, how long it has taken.
const runSummary=rows=>{
  const done=rows.filter(x=>!x.busy).length, running=rows.filter(x=>x.busy&&x.startedAt).length;
  const started=rows.map(x=>x.startedAt).filter(Boolean);
  const secs=started.length?((Date.now()-Math.min(...started))/1000).toFixed(0):0;
  return done===rows.length?`${done} checked in ${secs}s`
    :`${done}/${rows.length} checked · ${running} running · ${secs}s`;};

const band=s=>s>=85?'dirty':s>=60?'suspect':'clean';
const bandWord=s=>s>=85?'high risk':s>=60?'suspicious':'clean';
// Mirrors getipintel_band() in ipcheck.py. Low is "no proxy signal", never "residential" — a low score
// means getIPIntel saw no proxy evidence, which does not prove a real ISP line.
const giiBand=g=>g>=0.99?'proxy/hosting exit':g>=0.90?'likely proxy':g>=0.50?'mixed signals':'no proxy signal';
// Exit type: only `mobile` is a real line. `datacenter` and `tor` both draw friction, so NEITHER may render
// green. The same ternary used to be copy-pasted in the tile, the detail row and the bulk column — it lives
// here once, so adding a class can't leave one of the three painting a Tor exit as clean.
const ccColour=c=>c==='mobile'?'clean':'dirty';
const ccCap=c=>c==='mobile'?'real network line':c==='tor'?'Tor exit — denied by most apps':'hosting network';
// Scamalytics' OWN four-band scale, in their own colours: low green, medium amber, high orange, very high
// red. Shown this way at the user's explicit request.
//
// It is safe to paint this green ONLY because the score is fenced off from the verdict entirely — it has
// zero weight at every tier of verdict_factors(), locked in both directions by
// test_scamalytics_score_never_moves_the_verdict. So a green "8 · low" beside a DIRTY verdict is not a
// contradiction, it is Scamalytics' opinion next to ours, and the caption says "shown, not scored".
// That fencing is what makes the colour a report of their number rather than a claim of our own —
// MEASURED, `low` came back for a Tor exit and for 127.0.0.1, so it must never decide anything.
const scamColour=b=>b==='very high'?'dirty':b==='high'?'warn':b==='medium'?'suspect':'clean';
// Anything that is plainly a URL becomes a real link. `scamalytics_url` was rendering as escaped text in
// the raw card — a valid address nobody could click. (It 403s a bot user-agent and 200s a browser, so it
// only ever worked from the page; it just was not a link.)
const linkify=(v)=>/^https?:\/\//i.test(String(v))
  ?`<a class=lnk href="${esc(v)}" target="_blank" rel="noopener noreferrer">${esc(v)}</a>`:null;
// Why a blocklist sweep produced nothing, in the words of the state dnsbl_check ACTUALLY reports. The
// previous version branched on `dnsbl_skipped==='ipv6'`, a value nothing ever emitted — so all three
// copies of it were dead code telling the reader an IPv6 exit has no coverage, which stopped being true
// when the IPv6 zone table landed. One function, so a fourth copy can't drift off on its own.
const noCoverage=r=>r.dnsbl_skipped==='unparseable'
  ?'not run — this address parsed as neither IPv4 nor IPv6, so no zone could be asked'
  :'not run — no blocklist zone answered (the resolver is down, or every zone refused it)';
function row(k,v){return `<div class=rw><i>${esc(k)}</i><div>${esc(v)}</div></div>`}
// A row whose value carries pre-built markup (a flag image, an icon) and an optional colour.
function richRow(k,html,colour){
  return `<div class=rw><i>${esc(k)}</i><div${colour?` style="color:var(--${colour})"`:''}>${html}</div></div>`}

// Line-type icons. Drawn inline in currentColor, NOT emoji: emoji render differently on every platform,
// carry their own colour, and look out of place next to monospace readouts.
const SVG=p=>`<svg class="ico" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" `+
  `stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${p}</svg>`;
//
// EVERY attribute value is QUOTED, and that is not style. Unquoted, `rx=1.2/>` parses as the VALUE
// `1.2/` with no self-close, so the element swallows its siblings: `server` lost its second rack unit,
// `build` and `bot` rendered as a bare square, and `ban` — a circle followed by a path — drew NOTHING at
// all. Three of six icons were dead in the shipped page and it took the render-test page to see it, since
// a missing icon looks like a value that simply has no icon. Keep the quotes.
//
// Shapes are also drawn for THIRTEEN PIXELS, not for the 4x view: no detail smaller than ~2 units of the
// 16 viewBox survives, which is why there are no window dots, no LED dots, and wide gaps between parts.
const ICON={
  // ONE cabinet, TWO bays. The arithmetic is the whole point: at 13px a 1.3-unit stroke covers ~1.06px,
  // so the 10.8 units of height are 8.8px, of which the outline eats 2.1 and the shelf another 1.1 —
  // leaving 2.8px per bay. Three bays leave 1.5px each and the strokes close over them, which is how this
  // icon spent weeks as "a WEIRD rectangle". Two stacked boxes fail the same way, for the same reason.
  server: SVG('<rect x="2.4" y="2.8" width="11.2" height="10.4" rx="1.4"/>'+
              '<path d="M2.4 8h11.2"/>'),
  signal: SVG('<path d="M3 13.4v-3M6.3 13.4V8.2M9.6 13.4V5.6M12.9 13.4V3"/>'),
  home:   SVG('<path d="M2.4 7.4 8 2.7l5.6 4.7"/><path d="M3.9 6.9v6.4h8.2V6.9"/>'),
  // An institution: pediment, three columns, a base. The old version was a rectangle with six 1-unit
  // window dashes — at 13px the dashes vanish and it was indistinguishable from `bot`.
  build:  SVG('<path d="M1.9 6.2 8 2.6l6.1 3.6"/><path d="M4.2 7.6v4.6M8 7.6v4.6M11.8 7.6v4.6"/>'+
              '<path d="M2.4 13.4h11.2"/>'),
  // A head with two eyes and an antenna. The eyes are 1.6-wide strokes, not `h.01` dots — a zero-length
  // segment with round caps is one stroke-width across, i.e. gone at this size.
  bot:    SVG('<rect x="2.8" y="5.4" width="10.4" height="7.4" rx="2.2"/>'+
              '<path d="M5.9 8.9h1.2M8.9 8.9h1.2M8 5.4V2.9"/>'),
  ban:    SVG('<circle cx="8" cy="8" r="5.4"/><path d="M4.2 11.8 11.8 4.2"/>'),
};
// What kind of line an exit sits on, from the free-text IPQS connection_type or AbuseIPDB usageType.
//
// The icon is for scanning; the COLOUR is deliberately one-directional. These are the vendor's own labels
// and they are not reliable in the reassuring direction — measured on 185.220.101.45, a NordVPN-operated
// Tor exit (`tor-exit-45.for-privacy.net`): AbuseIPDB calls it "Fixed Line ISP". Painting that green said
// "consumer line" about an IP with 100% abuse confidence and seven proxy flags. So only the HOSTING match
// is coloured, because a warning that turns out to be wrong is survivable and a false all-clear is not.
// Whether an exit is really a datacenter is answered by connection_class + the verdict, from our own
// name heuristic, not by trusting this field.
// Order matters: "Data Center/Web Hosting/Transit" must match before the generic ISP rule.
// Gaps found by the asset render test, which shows what a real vendor string maps to: "Corporate" (the
// literal AbuseIPDB value) matched NOTHING, and "Content Delivery Network" matched nothing either — a CDN
// edge is hosting infrastructure, so it belongs with the datacenter rule, and colouring it is in the
// warning direction, which is the only direction a vendor's own label may be coloured.
const USAGE=[
  [/data ?cent|hosting|transit|colo|content delivery|\bcdn\b/i, ICON.server,'dirty'],
  [/mobile|cellular|wireless/i,                  ICON.signal,''],
  [/fixed line|residential|cable|dsl|fiber|isp/i,ICON.home,  ''],
  [/university|college|school|library/i,          ICON.build, ''],
  [/government|military/i,                        ICON.build, ''],
  [/search engine|spider|crawler/i,               ICON.bot,   'dirty'],
  [/commercial|corporate|organization|business/i, ICON.build, ''],
  [/reserved/i,                                   ICON.ban,   ''],
];
const usageOf=v=>USAGE.find(([re])=>re.test(String(v||'')))||null;

// ---- detail breakdown: every field every source returned ------------------------------------
// Friendly names for the raw API keys. A key that isn't here still shows, under its own name — the point
// is to show EVERYTHING each source answered, not a curated subset that hides the inconvenient field.
const LBL={fraud_score:'Fraud score',country_code:'Country',countryCode:'Country',region:'Region',city:'City',
 zip_code:'ZIP',ISP:'ISP',isp:'ISP',ASN:'ASN',organization:'Organization',host:'Reverse DNS',
 connection_type:'Connection',abuse_velocity:'Abuse velocity',latitude:'Latitude',longitude:'Longitude',
 timezone:'Time zone',is_crawler:'Crawler',recent_abuse:'Recent abuse',bot_status:'Bot',vpn:'VPN',
 active_vpn:'VPN active now',tor:'Tor',active_tor:'Tor active now',proxy:'Proxy',mobile:'Mobile',
 frequent_abuser:'Frequent abuser',high_risk_attacks:'High-risk attacks',security_scanner:'Security scanner',
 shared_connection:'Shared connection',dynamic_connection:'Dynamic IP',trusted_network:'Trusted network',
 result:'Proxy probability',BadIP:'Bad IP',queryIP:'Queried IP',Country:'Country',status:'Status',
 abuseConfidenceScore:'Abuse confidence',totalReports:'Reports (90d)',numDistinctUsers:'Distinct reporters',
 lastReportedAt:'Last report',usageType:'Usage type',domain:'Domain',hostnames:'Host names',isTor:'Tor exit',
 isWhitelisted:'Whitelisted',ipAddress:'IP',isPublic:'Public IP',ipVersion:'IP version',
 // Scamalytics. Its raw dict is FLAT (kv() renders a nested object as "[object Object]"), so every leaf
 // needs its own label or the card prints raw snake_case.
 score:'Score',risk:'Risk band',isp_score:'ISP score',isp_risk:'ISP risk band',isp_name:'ISP (Scamalytics)',
 org_name:'Organization (Scamalytics)',is_datacenter:'Datacenter',is_vpn:'VPN',
 is_apple_icloud_private_relay:'iCloud Private Relay',is_amazon_aws:'Amazon AWS',is_google:'Google',
 ip2proxy_type:'ip2proxy type',x4bnet_tor:'x4bnet: Tor',x4bnet_vpn:'x4bnet: VPN',
 x4bnet_datacenter:'x4bnet: datacenter',x4bnet_spambot:'x4bnet: spambot',
 firehol_30d:'FireHOL (30d)',firehol_1day:'FireHOL (1d)',ipsum_blacklisted:'IPsum listed',
 ipsum_blacklists:'IPsum lists',spamhaus_drop:'Spamhaus DROP',dbip_connection_type:'db-ip connection',
 blacklisted_external:'External blocklist',url:'Scamalytics page'};
const fmtv=v=>v===true?'yes':v===false?'no':Array.isArray(v)?(v.length?v.join(', '):'—')
  :(v===''||v==null?'—':String(v));
// A two-letter country code gets a real flag icon. NOT the flag emoji: Windows ships no flag glyphs, so a
// regional-indicator pair falls back to drawing the two letters — which next to the code reads as "DE DE".
// A tiny image works on every platform, and if it can't load (offline, blocked) it removes itself and the
// code alone remains.
const CC_KEYS=new Set(['country_code','countryCode','Country','reporterCountryCode']);
const flagImg=cc=>/^[A-Za-z]{2}$/.test(cc)
  ?`<img class=flag src="https://flagcdn.com/32x24/${cc.toLowerCase()}.png" alt="" onerror="this.remove()">`:'';
// Which fields earn a colour, and which way. Flags that mean trouble go red when true and green when false;
// everything else stays neutral, because colouring every field is as unreadable as colouring none.
const RISKY=new Set(['proxy','vpn','tor','active_vpn','active_tor','recent_abuse','bot_status','is_crawler',
  'frequent_abuser','high_risk_attacks','security_scanner','dynamic_connection','isTor','BadIP']);
const REASSURING=new Set(['isWhitelisted','trusted_network']);
// WARN-ONLY: red when true, NOTHING when false. Scamalytics' classifier flags go here rather than in RISKY
// because RISKY paints `false` green — and "Datacenter: no" in green reads as "residential", which is a
// claim nobody made. A false warning is survivable; a false all-clear is not.
const WARN_ONLY=new Set(['is_datacenter','is_vpn','is_apple_icloud_private_relay','is_amazon_aws','is_google',
  'x4bnet_tor','x4bnet_vpn','x4bnet_datacenter','x4bnet_spambot','firehol_30d','firehol_1day',
  'ipsum_blacklisted','spamhaus_drop','blacklisted_external']);
function vcolour(k,v){
  if(v===true)return WARN_ONLY.has(k)?'dirty':RISKY.has(k)?'dirty':REASSURING.has(k)?'clean':'';
  if(v===false)return WARN_ONLY.has(k)?'':RISKY.has(k)?'clean':'';
  // Scamalytics' bands in Scamalytics' own colours — the same scale as the tile and the column, so the
  // raw card cannot disagree with them about their own number.
  if(k==='risk'||k==='isp_risk')return scamColour(v);
  if(k==='ip2proxy_type')return 'dirty';
  if(k==='fraud_score')return band(+v);
  if(k==='abuseConfidenceScore')return +v>=50?'dirty':+v>=10?'suspect':'clean';
  if(k==='result'){const g=+v;return g>=0.99?'dirty':g>=0.90?'suspect':'clean';}
  // AbuseIPDB's usageType is the datacenter-vs-real-line call the verdict leans on, so it gets the same
  // colour language as the Exit type tile.
  if(k==='usageType'||k==='connection_type'){const u=usageOf(v);return u?u[2]:'';}
  return '';
}
// The icon that goes in front of a line-type value in the detail grid.
const kvIcon=(k,v)=>{if(k!=='usageType'&&k!=='connection_type')return '';
  const u=usageOf(v);return u?u[1]:'';};
function kv(obj){const ks=Object.keys(obj||{});if(!ks.length)return '';
  return `<div class=kv>${ks.map(k=>{const raw=obj[k], v=fmtv(raw), c=vcolour(k,raw);
    // esc() the value, then prepend our own markup — the img/span tags are ours, the value never is.
    // A URL becomes a real anchor (linkify escapes it itself); everything else is escaped text.
    const disp=linkify(v)||((CC_KEYS.has(k)?flagImg(String(raw)):kvIcon(k,v))+esc(v));
    return `<div title="${esc(LBL[k]||k)}: ${esc(v)}"><i>${esc(LBL[k]||k)}</i>`+
      `<b${c?` style="color:var(--${c})"`:''}>${disp}</b></div>`;}).join('')}</div>`;}
const srcCard=(t,sub,body)=>`<div class=src><h4>${esc(t)}<em>${esc(sub||'')}</em></h4>${body}</div>`;

// Blocklist zones grouped by what the answer MEANS, each group labelled with its meaning — a reader should
// never have to work out what a colour stands for.
const ZGROUPS=[
  ['listed','g-listed','Listed','abuse reports against this IP'],
  ['policy','g-policy','Policy only','a mail-sending policy listing, not abuse'],
  ['clean','g-clean','Clean','answered, not listed'],
  ['none','g-none','No answer','the zone refused or never replied — not a clean result'],
];
function zoneGroups(d,reasons){
  const by={listed:[],policy:[],clean:[],none:[]};
  d.forEach(z=>(by[z.status] || by.none).push(z));
  return ZGROUPS.filter(([k])=>by[k].length).map(([k,cls,title,meaning])=>
    `<div class="zgrp ${cls}"><em>${esc(title)} · ${by[k].length} <span>— ${esc(meaning)}</span></em>`+
    `<div class=zones>${by[k].map(z=>`<span class=z title="${esc(reasons[z.name]||z.zone)}">${esc(z.name)}</span>`)
      .join('')}</div></div>`).join('');
}

function deep(r){
  // No verdict card here — the verdict and its signals are already at the top of the result. This section is
  // strictly "what each source answered".
  let s='';
  if(r.ipqs_raw)s+=srcCard('IPQualityScore',
    `fraud ${esc(r.fraud_score)}${r.ipqs_strictness!=null?' · strictness '+esc(r.ipqs_strictness):''}`,kv(r.ipqs_raw));
  if(r.getipintel_raw)s+=srcCard('getIPIntel',
    `${r.getipintel_score.toFixed(3)} · ${giiBand(r.getipintel_score)}`,kv(r.getipintel_raw));
  if(r.scamalytics_raw)s+=srcCard('Scamalytics',
    `${esc(r.scam_score)} · ${esc(r.scam_risk)} — score shown, never scored`,kv(r.scamalytics_raw));
  if(r.abuseipdb_raw)s+=srcCard('AbuseIPDB',
    `${esc(r.abuse_confidence)}% · ${esc(r.abuse_reports||0)} reports in 90d`,kv(r.abuseipdb_raw));
  const d=r.dnsbl_detail||[];
  if(d.length){
    // policy_lists carries "Zone (why it lists this IP)" — hang the reason on the chip's tooltip.
    const reasons={};(r.policy_lists||[]).forEach(p=>{const i=p.indexOf(' (');if(i>0)reasons[p.slice(0,i)]=p;});
    s+=srcCard('Blocklists',`${d.length} zones queried`,zoneGroups(d,reasons));
  }
  if(!s)return '';
  return `<details class=deep><summary>Detailed breakdown — every field each source returned</summary>${s}</details>`;
}

function render(r){
  if(r.error){out.innerHTML=`<div class="blk verdict v-dirty"><div class=v><span class=dot></span><b>FAILED</b></div><span>${esc(r.error)}</span></div>`;return}
  let t='', i=0;
  const blk=(h)=>`<div class=blk style="animation-delay:${(i++)*55}ms">${h}</div>`;

  // The verdict names its own evidence: one chip per signal that decided it. A bare "SUSPECT" says nothing.
  // `verdict` is defaulted rather than assumed: a report that arrives without one must degrade to UNKNOWN,
  // not throw a TypeError over the whole page and turn an ordinary dead proxy into "FAILED".
  const lvl=r.verdict||'unknown';
  const vf=(r.verdict_factors||[]).map(x=>`<span>${esc(x)}</span>`).join('');
  t+=blk(`<div class="verdict v-${lvl}"><div class=v><span class=dot></span><b>${esc(lvl.toUpperCase())}</b></div>`+
    (vf?`<div class=vfx>${vf}</div>`:`<span>${esc(r.verdict_reason||'')}</span>`)+`</div>`);

  // Exit-IP hero + copy, with the WHO/WHERE rows attached to it rather than floating in a second panel as an
  // unlabelled run-on line ("Stiftung Erneuerbare Freiheit · Berlin, Brandenburg, Germany"). Same label→value
  // row as everywhere else, so it scans in one pass.
  let meta='';
  [['ISP','isp'],['Organization','organization'],['ASN','asn'],['Host','host'],
   ['Connection','connection_type'],['Usage','usage_type'],['Location','location'],
   ['Time zone','timezone']].forEach(([k,v])=>{
    if(!r[v])return;
    // Location gets its country flag; the two line-type fields get an icon + colour, so what kind of
    // network this is reads at a glance instead of out of a phrase.
    if(v==='location'&&r.country_code){meta+=richRow(k,flagImg(r.country_code)+esc(r[v]));return;}
    const u=(v==='connection_type'||v==='usage_type')&&usageOf(r[v]);
    if(u){meta+=richRow(k,u[1]+esc(r[v]),u[2]);return;}
    meta+=row(k,r[v]);});
  let hero=`<div class=panel><div class=iprow><span class=ip>${esc(r.ip||'unknown')}</span>`;
  if(r.ip)hero+=`<button class=copy data-ip="${esc(r.ip)}">Copy</button>`;
  hero+=`</div>`+(meta?`<div class="rows meta">${meta}</div>`:'')+`</div>`;
  t+=blk(hero);
  // IPQS's fraud_score is deliberately NOT the visual hero — it saturates on proxies, so it's one tile among
  // the signals below, not a giant meter that reads as "the verdict".

  // Signal tiles.
  const pol=(r.policy_lists||[]).length, hits=(r.blacklists||[]).length;
  let tiles='';
  // Exit type leads — a datacenter/hosting exit is the strongest usability signal (real ISPs pass more easily).
  // Every caption is ONE short line — tiles share a grid row, so a caption that wraps makes every tile tall.
  // A clipped caption stays readable: tile() hangs the full text on the title, and the detail breakdown below
  // lists it in full. Never truncate without leaving a way to read the rest.
  // `full` overrides the hover text when the visible caption is a shortened form of it — a caption that
  // ellipsises must still have somewhere to say the whole thing.
  const tile=(label,value,cap,colour,small,full)=>
    `<div class=tile title="${esc(label)}: ${esc(full||cap)}"><em>${esc(label)}</em>`+
    `<strong style="${small?'font-size:20px;':''}color:var(--${colour})">${esc(value)}</strong>`+
    `<small>${esc(cap)}</small></div>`;
  // Latency leads when a proxy was used — a proxy that works but takes 4s is a different problem from a
  // dirty one, and neither shows up in any reputation source.
  // The tile grades what the PROXY costs, not the raw round trip — the raw number folds in this machine's
  // own distance to the internet and is not comparable between a laptop in Asia and a function in
  // Virginia. MEASURED: the same endpoint is 889 ms direct here and 3077 ms through a US residential
  // proxy, so 2.2s of that 3.1s is the proxy. The caption shows both halves so the grade is auditable.
  if(r.proxy_ms!=null){const add=r.proxy_added_ms!=null?r.proxy_added_ms:r.proxy_ms;
    tiles+=tile('Latency', add+' ms',
      (add<400?'fast':add<1200?'usable':'slow')
      + (r.direct_ms!=null?` · ${r.proxy_ms} ms total`:''),
      add<400?'clean':add<1200?'suspect':'dirty', true,
      r.direct_ms!=null?`${add} ms added by the proxy · ${r.proxy_ms} ms total round trip · `
        +`${r.direct_ms} ms without it`:null);}
  if(r.connection_class)
    tiles+=tile('Exit type', r.connection_class==='tor'?'Tor'
        :r.connection_class[0].toUpperCase()+r.connection_class.slice(1),
      ccCap(r.connection_class), ccColour(r.connection_class), true);
  const bl=hits>=2?'dirty':hits?'suspect':(r.dnsbl_usable?'clean':'dim');
  tiles+=tile('Blacklists', r.dnsbl_usable||hits?hits:'—',
    hits?r.blacklists.join(', '):r.dnsbl_usable?`none of ${r.dnsbl_checked}`:'DNS unreachable', bl);
  if(pol)tiles+=tile('Policy lists', pol, r.policy_lists.join(', '), 'info');
  if(r.abuse_confidence!=null)tiles+=tile('Abuse', r.abuse_confidence+'%',
    `${r.abuse_reports||0} reports · 90d`,
    r.abuse_confidence>=50?'dirty':r.abuse_confidence>=10?'suspect':'clean');
  if(r.abuse_velocity)tiles+=tile('Abuse velocity', r.abuse_velocity, 'IPQualityScore', 'ink', true);
  // Fraud score as ONE tile among the signals — not a hero meter; it saturates on any proxy.
  if(r.fraud_score!=null)tiles+=tile('Fraud score', r.fraud_score,
    `IPQS · strictness ${r.ipqs_strictness!=null?r.ipqs_strictness:'—'}`, band(r.fraud_score));
  if(r.getipintel_score!=null){const g=r.getipintel_score;
    tiles+=tile('getIPIntel', g.toFixed(2), giiBand(g)+(r.getipintel_bad?' · bad IP':''),
      g>=0.99?'dirty':g>=0.90?'suspect':'clean');}
  // Scamalytics' overall score, labelled as what it is. It gets zero weight in the verdict — it tracks the
  // ISP score on every IP measured and mis-ranks (Tor 15, clean Comcast 18, Mullvad 44) — so the caption
  // says so and the colour never goes green. What Scamalytics actually contributes is the Exit type above.
  if(r.scam_risk)tiles+=tile('Scamalytics', r.scam_score!=null?r.scam_score:'—',
    r.scam_risk+' · not scored', scamColour(r.scam_risk), true);
  t+=blk(`<div class=tiles>${tiles}</div>`);

  if(r.fraud_score!=null){
    // Same SIGNALS table the bulk column uses, spelled out here because there is room for it. The raw
    // API key stays on the hover so nothing is hidden from someone cross-checking against IPQS.
    const chips=(r.flags||[]).length
      ?r.flags.map(f=>`<span class=chip title="${esc(f)}">${esc(signalOf(f)[1])}</span>`).join('')
      :'<span class="chip ok">Not flagged as proxy or VPN</span>';
    t+=blk(`<div class="panel flagbar"><div class=lbl>Flagged as</div><div class=chips>${chips}</div></div>`);
  }

  const dp=deep(r); if(dp)t+=blk(dp);
  // Notes are "Source: what happened" — split them onto the same label→value row as everything else
  // rather than printing a sentence per line.
  if((r.notes||[]).length)t+=blk(`<div class=panel><div class="rows hint">${r.notes.map(n=>{
    const i=String(n).indexOf(': ');
    return i>0?row(n.slice(0,i),n.slice(i+2)):row('Note',n);}).join('')}</div></div>`);
  out.innerHTML=t;
}

// Copy (delegated — renders rebuild the buttons). Handles the hero .copy (data-ip) and the bulk table .cc
// (data-copy: a proxy string or an exit IP).
// `.cp` is the credential chip (host / port / user / pass / whole line) and `.copy` the hero button.
// This selector used to read `.copy,.cc` — `.cc` is emitted NOWHERE, and every `.cp` chip therefore had
// no handler at all: clicking a password copied nothing and did not even flash. That is the tool's whole
// point ("click to copy the hostname / port / username / password"), silently broken, which is why
// test_every_delegated_selector_matches_something_the_page_emits now pins the two lists to each other.
out.addEventListener('click',async e=>{
  const b=e.target.closest('.copy,.cp'); if(!b)return;
  const txt=b.dataset.copy!=null?b.dataset.copy:b.dataset.ip; if(!txt)return;
  let ok=false;
  try{await navigator.clipboard.writeText(txt); ok=true;}
  catch(err){
    // execCommand is the fallback for a denied permission or a non-secure origin. It reports whether it
    // worked, and that answer is used: flashing "done" after a FAILED copy tells the user their password
    // is on the clipboard when it is not.
    const ta=document.createElement('textarea');
    ta.value=txt; ta.style.position='fixed'; ta.style.opacity='0';
    document.body.appendChild(ta); ta.select();
    try{ok=document.execCommand('copy');}catch(e2){ok=false;}
    ta.remove();
  }
  if(!ok){b.classList.add('failed'); setTimeout(()=>b.classList.remove('failed'),1400); return;}
  // Flash the colour, never the label — swapping "Copy" for "Copied ✓" resizes the button and shoves its
  // neighbours around. Confirmation must not move the layout.
  b.classList.add('done');
  setTimeout(()=>b.classList.remove('done'),1100);
});

// API endpoint — the local server serves /check; build.py rewrites this to /api/check for the Vercel deploy.
const API='/check';
const saveKeys=()=>{try{localStorage.setItem('ipqs_key',$('#ipqs').value.trim());
  localStorage.setItem('abuse_key',$('#abuse').value.trim());
  localStorage.setItem('scamalytics_user',$('#scamuser').value.trim());
  localStorage.setItem('scamalytics_key',$('#scamkey').value.trim());}catch(e){}};
// getipintel_contact is NOT sent — the server supplies it (env var on Vercel, config file locally).
function checkBody(proxy){return JSON.stringify({proxy:proxy, proxy_scheme:$('#ptype').value,
  ip:proxy?'':$('#ip').value.trim(), ipqs_key:$('#ipqs').value.trim(), abuse_key:$('#abuse').value.trim(),
  scamalytics_user:$('#scamuser').value.trim(), scamalytics_key:$('#scamkey').value.trim()});}
// Bulk body: a bare IP is checked directly (ip), everything else as a proxy — so one paste can mix both.
function bulkBody(line){
  const ip=bareIp(line);
  if(!ip)return checkBody(line);
  return JSON.stringify({ip:ip, proxy:'', ipqs_key:$('#ipqs').value.trim(), abuse_key:$('#abuse').value.trim(),
    scamalytics_user:$('#scamuser').value.trim(), scamalytics_key:$('#scamkey').value.trim()});}

$('#go').onclick=async()=>{
  const b=$('#go'); b.disabled=true; b.textContent='Checking…'; saveKeys();
  out.innerHTML='<div class="blk panel"><span class=loading>Running lookups</span></div>';
  try{
    const r=await fetch(API,{method:'POST',body:checkBody($('#proxy').value.trim())});
    render(await r.json());
  }catch(e){render({error:String(e)})}
  b.disabled=false; b.textContent='Run check';
};

// Bulk: check many proxies (one per line) and show them in one copyable comparison table.
const vpill=v=>`<span class="vpill v-${v||'unknown'}-p">${esc((v||'—').toUpperCase())}</span>`;

// Split a proxy line into its parts so each one can be copied on its own. Mirrors parse_proxy() in
// ipcheck.py and accepts the same four shapes; returns nulls rather than throwing, because this only
// drives the copy chips — the SERVER's parse is the one that decides whether a line is valid.
function proxyParts(line){
  let s=(line||'').trim(), scheme=null;
  const i=s.indexOf('://'); if(i>0){scheme=s.slice(0,i).toLowerCase(); s=s.slice(i+3);}
  // `;` is accepted anywhere `:` is — normalised in the same place, the same order, and with the same
  // credential exemption as parse_proxy(). If only the server learned this, a `host;port;user;pass` line
  // would check correctly while the copy chips beside it showed one unsplit blob; and if only the server
  // learned the exemption, a `user:pa;ss@host` line would be CHECKED with the right password while the
  // chip offered a corrupted one to copy.
  {const a=s.lastIndexOf('@');
   s = a<0 ? s.split(';').join(':') : s.slice(0,a+1)+s.slice(a+1).split(';').join(':');}
  let user=null, pass=null, hostport=s;
  const at=s.lastIndexOf('@');
  if(at>=0){const creds=s.slice(0,at); hostport=s.slice(at+1);
    const c=creds.indexOf(':'); user=c<0?creds:creds.slice(0,c); pass=c<0?null:creds.slice(c+1);}
  else{const p=s.split(':');
    if(p.length===4){hostport=p[0]+':'+p[1]; user=p[2]; pass=p[3];}}
  // IPv6 needs its own case, matching _host_port() on the server: a BRACKETED address keeps its brackets
  // and any `:port` after the `]`; an unbracketed multi-colon value is host-only, never split on its last
  // colon. Splitting `2001:db8::1` there would show the user a "host" of `2001:db8:` and a "port" of `1`
  // in the copy chips — and those chips exist to be pasted somewhere else.
  let host=hostport, port=null;
  if(hostport.startsWith('[')){
    const rb=hostport.indexOf(']');
    if(rb>0){host=hostport.slice(0,rb+1);
      const rest=hostport.slice(rb+1);
      if(rest.startsWith(':'))port=rest.slice(1);}
  }else{
    const col=hostport.lastIndexOf(':');
    if(col>=0&&col===hostport.indexOf(':')){host=hostport.slice(0,col); port=hostport.slice(col+1);}
  }
  return {scheme,host:host||null,port:port||null,user:user||null,pass:pass||null};
}
// A line that is a BARE IP (v4 or v6, no port, no scheme, no credentials) is NOT a proxy — you cannot route
// through an IP with no port — so bulk checks it DIRECTLY. This is the "compare a batch of IPs" case the
// tool is also for; a proxy always carries a port, so it is never mistaken for one. Returns the IP (brackets
// stripped) or null.
function bareIp(line){
  const p=proxyParts(line);
  if(p.port||p.user||p.pass||p.scheme)return null;
  let h=(p.host||'').trim();
  if(h.startsWith('[')&&h.endsWith(']'))h=h.slice(1,-1);
  const v4=/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h);
  if(v4)return v4.slice(1).every(o=>+o<=255)?h:null;
  if(h.includes(':')&&/^[0-9a-fA-F:]+$/.test(h)&&(h.match(/:/g)||[]).length>=2)return h;   // IPv6 literal
  return null;
}
// The current bulk run, so the row expander can be registered ONCE — re-registering per run would stack
// listeners that each redraw a stale table.
let bulk=null;
out.addEventListener('click',e=>{
  if(!bulk)return;
  const s=e.target.closest('[data-sort]');
  if(s){bulk.sort(s.dataset.sort);return;}
  const b=e.target.closest('[data-deep]');
  if(!b)return;
  const x=bulk.rows[+b.dataset.deep]; if(!x)return;
  x.open=!x.open; bulk.draw();});
// ---- the expanded row: ONE table, grouped, every field we hold for this proxy -----------------
const dRow=(k,v)=>v==null||v===''?'':`<tr><td>${esc(k)}</td><td>${v}</td></tr>`;
const dTxt=(k,v)=>dRow(k,v==null||v===''?null:esc(String(v)));
const dGrp=t=>`<tr class=grp><td colspan=2>${esc(t)}</td></tr>`;
// A one-click copy chip. The label stays put and a tick appears in a slot that is always reserved,
// so confirming never changes the chip's width.
const chip=(label,value,titleText)=>value==null||value===''?'<span class=dim>—</span>'
  // The VALUE goes on the title, not just "Copy <label>". `.cp` is max-width:190px with an ellipsis, so a
  // long value (a full IPv6 exit, a whole `host:port:user:pass` line) renders clipped — and a title that
  // only says "Copy ip" is no escape hatch at all. This is the detail row that the truncated bulk-table
  // cell defers to, so it has to actually hold the whole string. One helper, so every chip gets it.
  // `titleText` overrides the value when a chip's hover text should say something other than the value.
  :`<button class=cp data-copy="${esc(value)}" title="${esc(titleText!=null?titleText:value)}&#10;Click to copy"><i>${esc(label)}</i>${esc(value)}</button>`;

function bulkDetail(x){
  const r=x.r||{}, p=x.parts||{};
  if(r.error)return `<table class=det><tbody>${dTxt('Error',r.error)}</tbody></table>`;
  let t='';

  t+=dGrp('Proxy');
  // All the credentials on ONE row of chips. One click copies just that part; a tick confirms in a slot
  // that is always reserved, so nothing moves. The password is shown IN FULL, deliberately: this is a
  // proxy list the user pasted in themselves, on their own screen, and dots made the one field you most
  // often need to eyeball against the vendor's dashboard the only one you could not read.
  t+=dRow('Copy',
    chip('host',p.host)+chip('port',p.port)+
    (p.user?chip('user',p.user):'')+
    (p.pass?chip('pass',p.pass):'')+
    chip('whole line',x.line));
  t+=dTxt('Transport',(p.scheme||$('#ptype').value).toUpperCase());
  t+=dTxt('Reachable',r.proxy_alive===false?'no — no route out':r.ip?'yes':null);
  // What the proxy SAID, when it said anything. "503 No exit node" means the credentials are fine and
  // the vendor's pool is empty — a completely different action from "your proxy is down", and the row
  // is the difference between the user re-checking their password and going to the vendor.
  t+=dTxt('Proxy said',r.proxy_error||null);
  if(r.proxy_ms!=null){const add=r.proxy_added_ms!=null?r.proxy_added_ms:r.proxy_ms;
    t+=dTxt('Latency',add+' ms added by the proxy · '+(add<400?'fast':add<1200?'usable':'slow'));
    if(r.direct_ms!=null)
      t+=dTxt('','of a '+r.proxy_ms+' ms round trip; the same request without the proxy took '
              +r.direct_ms+' ms from wherever this check ran');}

  if(r.ip){
    t+=dGrp('Exit');
    t+=dRow('Exit IP',chip('ip',r.ip));
    t+=dTxt('IP version',r.ip.includes(':')?'IPv6':'IPv4');
    if(r.exit_ipv6)t+=dRow('Also exits at',chip('ipv6',r.exit_ipv6)+
      '<span class=dimnote> dual-stack — which address a site sees varies per connection</span>');
    if(r.country_code)t+=dRow('Country',flagImg(r.country_code)+esc(r.country_code));
    t+=dTxt('Location',r.location);
    t+=dTxt('Time zone',r.timezone);
    t+=dTxt('ISP',r.isp);
    t+=dTxt('Organization',r.organization);
    t+=dTxt('ASN',r.asn);
    t+=dTxt('Reverse DNS',r.host);
  }

  t+=dGrp('Verdict');
  t+=dRow('Verdict',vpill(r.error?'unknown':r.verdict));
  (r.verdict_factors||[]).forEach((f,i)=>{t+=dTxt(i?'':'Because',f);});
  const u=usageOf(r.connection_type||r.usage_type||'');
  // "Not classified" is source-aware: whether Scamalytics ran changes how much the absence is worth.
  t+=dRow('Exit type',r.connection_class
    ?`<span class="c-${ccColour(r.connection_class)}">${esc(r.connection_class)}</span>`
    :'<span class=dim>unclassified — '+(r.scam_risk!=null
        ?'Scamalytics raised no datacenter or proxy record and no hosting name matched'
        :'no hosting name matched, and Scamalytics did not run')+', which is not proof of a real line</span>');
  if(r.connection_type)t+=dRow('Connection',(u?u[1]:'')+esc(r.connection_type));
  if(r.usage_type)t+=dRow('Usage type',(u?u[1]:'')+esc(r.usage_type));

  t+=dGrp('Reputation');
  t+=dRow('Fraud score',r.fraud_score!=null
    ?`<span class="c-${band(r.fraud_score)}">${r.fraud_score}</span> · ${esc(bandWord(r.fraud_score))}`+
     (r.ipqs_strictness!=null?` · IPQS strictness ${esc(r.ipqs_strictness)}`:'')
    :'<span class=dim>no IPQualityScore key — not measured</span>');
  // Same SIGNALS vocabulary as the table cell above it and the single-check card. This row was the third
  // spelling of one thing — the cell said `PRX ABU BOT`, the card said `Proxy Recent abuse Bot`, and this
  // said `proxy · recent_abuse · bot_status`. The raw API key stays on the hover.
  t+=dRow('Detected as',(r.flags||[]).length
    ?r.flags.map(f=>`<span title="${esc(f)}">${esc(signalOf(f)[1])}</span>`).join(' · ')
    :(r.fraud_score!=null?'<span class=c-clean>no proxy/VPN/Tor flag</span>':'<span class=dim>not measured</span>'));
  t+=dRow('getIPIntel',r.getipintel_score!=null
    ?`<span class="c-${r.getipintel_score>=0.99?'dirty':r.getipintel_score>=0.90?'suspect':'clean'}">`+
     `${r.getipintel_score.toFixed(3)}</span> · ${esc(giiBand(r.getipintel_score))}`+
     (r.getipintel_bad?' · <span class=c-dirty>bad IP</span>':'')
    :'<span class=dim>did not answer — not measured</span>');
  // Scamalytics, in decisiveness order. The overall score sits ADJACENT to the ISP score on purpose: they
  // are near-identical on every IP measured, and seeing that is what tells the reader the score is an ASN
  // prior rather than a judgement about this address.
  t+=dRow('Scamalytics',r.scam_risk
    ?`<span class="c-${scamColour(r.scam_risk)}">${esc(r.scam_score)}</span> · ${esc(r.scam_risk)}`+
     (r.scam_isp_risk?` · ISP ${esc(r.scam_isp_score)} ${esc(r.scam_isp_risk)}`:'')+
     '<span class=dimnote> shown, not scored — it tracks the ISP score, not this IP</span>'
    :'<span class=dim>no Scamalytics credentials — not measured</span>');
  if(r.scam_risk){
    const PT={DCH:'datacenter',TOR:'Tor exit',VPN:'VPN',PUB:'public proxy',WEB:'web proxy',
              SES:'search-engine spider',RES:'residential proxy'};
    t+=dRow('Proxy type',r.scam_proxy_type
      ?`<span class="c-dirty" title="ip2proxy code ${esc(r.scam_proxy_type)}">`+
       `${esc(PT[r.scam_proxy_type]||r.scam_proxy_type)}</span>`+
       `<span class=dimnote> ip2proxy ${esc(r.scam_proxy_type)}</span>`
      :'<span class=dim>no ip2proxy record — empty is not a clean result</span>');
    const sf=[['scam_datacenter','datacenter'],['scam_vpn','VPN'],['scam_tor','Tor'],
              ['scam_blacklisted_external','external blocklist']].filter(([k])=>r[k]).map(([,n])=>n);
    t+=dRow('Scamalytics flags',sf.length
      ?`<span class=c-suspect>${esc(sf.join(' · '))}</span>`
      :'<span class=dim>none raised</span>');
    const su=r.scamalytics_raw&&r.scamalytics_raw.url;
    if(su)t+=dRow('Scamalytics page',linkify(su)||esc(su));
  }
  t+=dRow('Abuse confidence',r.abuse_confidence!=null
    ?`<span class="c-${r.abuse_confidence>=50?'dirty':r.abuse_confidence>=10?'suspect':'clean'}">`+
     `${r.abuse_confidence}%</span>`+
     (r.abuse_reports!=null?` · ${esc(r.abuse_reports)} reports in 90d`:'')+
     (r.abuse_reporters!=null?` · ${esc(r.abuse_reporters)} distinct reporters`:'')
    :'<span class=dim>no AbuseIPDB key — not measured</span>');
  t+=dTxt('Last report',r.abuse_last_report);
  t+=dTxt('Abuse velocity',r.abuse_velocity);

  t+=dGrp('Blocklists');
  if(!r.dnsbl_usable){
    t+=dRow('Coverage','<span class=dim>'+esc(noCoverage(r))+'</span>');
    t+=dRow('','<span class=dim>This is NOT a clean result. Nothing was checked.</span>');
  }else{
    const hits=(r.blacklists||[]).length;
    t+=dTxt('Coverage',`${r.dnsbl_checked} of ${r.dnsbl_zones_total||r.dnsbl_checked} `+
      `${r.dnsbl_family==='ipv6'?'IPv6 ':''}zones answered`);
    t+=dRow('Listed by',hits?`<span class=c-dirty>${esc(r.blacklists.join(', '))}</span>`
      :'<span class=c-clean>none</span>');
    if((r.policy_lists||[]).length)
      t+=dRow('Policy listings',`<span class=c-info>${esc(r.policy_lists.join(', '))}</span>`+
        '<span class=dimnote> mail-sending policy, not abuse</span>');
    if(r.dnsbl_family==='ipv6')
      t+=dRow('','<span class=dimnote>IPv6 listings are /64-scoped on Spamhaus, CBL and s5h, so this is '+
        'weaker evidence in both directions than the IPv4 equivalent.</span>');
    const d=r.dnsbl_detail||[];
    if(d.length){
      const reasons={};(r.policy_lists||[]).forEach(s=>{const i=s.indexOf(' (');if(i>0)reasons[s.slice(0,i)]=s;});
      t+=`<tr><td>Per zone</td><td>${zoneGroups(d,reasons)}</td></tr>`;
    }
  }

  if((r.notes||[]).length){
    t+=dGrp('Notes');
    (r.notes||[]).forEach(n=>{const i=String(n).indexOf(': ');
      t+=dTxt(i>0?n.slice(0,i):'Note',i>0?n.slice(i+2):n);});
  }
  return `<table class=det><tbody>${t}</tbody></table>`;
}

$('#bulkgo').onclick=async()=>{
  const lines=$('#bulk').value.split('\n').map(s=>s.trim()).filter(Boolean);
  if(!lines.length)return;
  saveKeys();
  const b=$('#bulkgo'); b.disabled=true; b.textContent='Checking…';
  const rows=lines.map((line,i)=>({line, i, parts:proxyParts(line), isIp:!!bareIp(line), r:null, busy:true, open:false}));

  // ---- the comparison columns ---------------------------------------------------------------
  // Every column answers "which of these do I use?". `get` is the sort key, `cell` the markup. Anything
  // that is context rather than a comparison lives in the expanded detail instead.
  const num=v=>v==null?Infinity:v;                 // absent sorts last, in either direction
  const COLS=[
    {k:'proxy', h:'Proxy', get:x=>x.line,
     cell:x=>`<span title="${esc(x.line)}"><span class=pxh>${esc(x.parts.host||x.line)}</span>`+
             (x.parts.port?`<span class=pxp>:${esc(x.parts.port)}</span>`:'')+`</span>`},
    {k:'status', h:'Status',
     get:x=>x.busy?3:(x.r&&(x.r.error||(!x.isIp&&x.r.proxy_alive===false)))?0:1,
     cell:x=>x.busy
       ?(x.startedAt
         ?`<span class="vpill v-unknown-p live">checking</span>`+
          `<span class=sub data-elapsed="${x.i}">${elapsed(x)}</span>`
         :'<span class="vpill v-queued-p" title="Waiting for a free slot — 4 checks run at a time">queued</span>')
       :(x.r.error||(!x.isIp&&x.r.proxy_alive===false))?'<span class="vpill v-dirty-p">DEAD</span>'
       :x.isIp?'<span class="vpill v-unknown-p" title="Checked directly as an IP, not through a proxy">IP</span>'
       :'<span class="vpill v-clean-p">UP</span>'},
    // Grades what the PROXY adds, not the raw round trip — the raw number folds in the checking machine's
    // own distance and is not comparable across observers. Sub-line carries the total so nothing is hidden.
    {k:'ms', h:'Latency', ttl:'What the proxy adds over this machine’s own path to the same endpoint',
     get:x=>num(x.r&&(x.r.proxy_added_ms!=null?x.r.proxy_added_ms:x.r.proxy_ms)),
     cell:x=>{if(!x.r||x.r.proxy_ms==null)return '<span class=dim>—</span>';
       const add=x.r.proxy_added_ms!=null?x.r.proxy_added_ms:x.r.proxy_ms;
       return `<span class="ms c-${add<400?'clean':add<1200?'suspect':'dirty'}" title="${add} ms added by `+
         `the proxy; ${x.r.proxy_ms} ms total round trip${x.r.direct_ms!=null?`, ${x.r.direct_ms} ms `+
         `without it`:''}">${add} ms</span>`+
         (x.r.direct_ms!=null?`<span class=sub>${x.r.proxy_ms} total</span>`:'');}},
    {k:'verdict', h:'Verdict',
     get:x=>{const m={dirty:0,suspect:1,unknown:2,clean:3};const v=x.r&&x.r.verdict;
             return v in m?m[v]:4;},
     cell:x=>{if(x.busy)return '<span class="vpill v-unknown-p">…</span>';
       const why=x.r.error||(x.r.verdict_factors||[]).join(' · ')||x.r.verdict_reason||'';
       return vpill(x.r.error?'unknown':x.r.verdict)+
         // The single-check card names the evidence behind its verdict; the table used to show the
         // conclusion alone, which is the one thing a reader can't act on. Same data, one line, full
         // text on hover — the table's own footnote already promises hover-for-the-rest.
         (why?`<span class=sub title="${esc(why)}">${esc(why)}</span>`:'');}},
    {k:'ip', h:'Exit IP', get:x=>(x.r&&x.r.ip)||'',
     cell:x=>x.r&&x.r.ip
       ?flagImg(x.r.country_code||'')+`<span class=ipv title="${esc(x.r.ip)}">${esc(x.r.ip)}</span>`+
        (x.r.exit_ipv6?'<span class=tag title="Dual-stack — this proxy also exits over IPv6">+v6</span>':'')
       :'<span class=dim>—</span>'},
    // Headings name the SOURCE, so a bare number is never anonymous. Full name on the title.
    {k:'lists', h:'DNSBL', ttl:'Public blocklist zones — hits / zones that answered',
     get:x=>num(x.r&&(x.r.blacklists||[]).length), cell:x=>listsCell(x.r)},
    {k:'detected', h:'Flags', ttl:'What IPQualityScore detected — hover a code for its meaning',
     get:x=>num(x.r&&(x.r.flags||[]).length), cell:x=>detectedCell(x.r)},
    {k:'fraud', h:'IPQS', ttl:'IPQualityScore fraud score, 0-100', get:x=>num(x.r&&x.r.fraud_score),
     cell:x=>x.r&&x.r.fraud_score!=null
       ?`<span class="c-${band(x.r.fraud_score)}">${x.r.fraud_score}</span>`
       :'<span class=dim title="No IPQualityScore key — not measured">n/k</span>'},
    {k:'gii', h:'GII', ttl:'getIPIntel proxy/hosting probability, 0-1',
     get:x=>num(x.r&&x.r.getipintel_score),
     cell:x=>x.r&&x.r.getipintel_score!=null
       ?`<span class="c-${x.r.getipintel_score>=0.99?'dirty':x.r.getipintel_score>=0.90?'suspect':'clean'}">`+
        x.r.getipintel_score.toFixed(2)+`</span>`
       // WHY it has no score, not just that it hasn't. getIPIntel meters 15/min per contact AND per
       // connecting IP, so a bulk run can genuinely exhaust it mid-batch — and "n/a" with no reason sent
       // the user asking why one row was blank. The note it returned is the answer; show it.
       :`<span class=dim title="${esc(giiWhy(x.r))}">n/a</span>`},
    {k:'abuse', h:'AbuseDB', ttl:'AbuseIPDB confidence — reports in the last 90 days',
     get:x=>num(x.r&&x.r.abuse_confidence),
     cell:x=>x.r&&x.r.abuse_confidence!=null
       ?`<span class="c-${x.r.abuse_confidence>=50?'dirty':x.r.abuse_confidence>=10?'suspect':'clean'}">`+
        x.r.abuse_confidence+`%</span>`
       :'<span class=dim title="No AbuseIPDB key — not measured">n/k</span>'},
    // Scamalytics: the OVERALL score + band, and nothing more. Sorting by it is offered because the user
    // asked to see it, but the colour never goes green and the band sits on the sub-line — the score
    // MIS-RANKS (Tor 15 "low", clean Comcast 18, Mullvad 44 highest), so it must not read as a ranking.
    {k:'scam', h:'SCAM', ttl:'Scamalytics score — shown, not scored: it tracks the ISP score, not this IP',
     get:x=>num(x.r&&x.r.scam_score),
     cell:x=>x.r&&x.r.scam_risk
       ?`<span class="c-${scamColour(x.r.scam_risk)}" title="Scamalytics ${esc(x.r.scam_score)} · `+
        `${esc(x.r.scam_risk)}. Shown, not scored: it tracks the ISP score, not this IP.">`+
        `${esc(x.r.scam_score)}</span><span class=sub>${esc(x.r.scam_risk)}</span>`
       :'<span class=dim title="No Scamalytics credentials — not measured">n/k</span>'},
    {k:'type', h:'Exit', ttl:'Exit type — hosting, Tor or a mobile line, where it could be determined',
     get:x=>(x.r&&x.r.connection_class)||'zz',
     cell:x=>x.r&&x.r.connection_class
       ?`<span class="c-${ccColour(x.r.connection_class)}" title="${esc(ccCap(x.r.connection_class))}">`+
        `${esc({datacenter:'hosting',tor:'Tor',mobile:'mobile'}[x.r.connection_class]||x.r.connection_class)}</span>`
       :`<span class=dim title="${x.r&&x.r.scam_risk!=null
           ?'Scamalytics raised no datacenter or proxy record, and no hosting name matched — not proof of a real line'
           :'No hosting name matched, and Scamalytics did not run — not proof of a real line'}">—</span>`},
    {k:'isp', h:'ISP', get:x=>(x.r&&(x.r.isp||x.r.organization))||'',
     cell:x=>{const v=x.r&&(x.r.isp||x.r.organization);
       return v?`<span class=trunc title="${esc(v)}">${esc(v)}</span>`:'<span class=dim>—</span>';}},
    // City, then the COUNTRY CODE — not "Redmond, Washington, United States", which made this the second
    // widest column on a table that already scrolls. The full string is on the title.
    {k:'loc', h:'Location', get:x=>(x.r&&x.r.location)||'',
     cell:x=>{const v=x.r&&x.r.location;
       return v?`<span class=trunc title="${esc(v)}">${esc(shortLoc(v,x.r.country_code))}</span>`
              :'<span class=dim>—</span>';}},
  ];

  // "Redmond, Washington, United States" -> "Redmond, US". Keeps the city (the part that differs between
  // two exits from the same provider) and drops the two that a country flag already told you.
  function shortLoc(v,cc){
    const p=String(v).split(',').map(s=>s.trim()).filter(Boolean);
    if(!p.length)return v;
    return cc?p[0]+', '+cc:(p.length>2?p[0]+', '+p[p.length-1]:v);
  }

  // Blocklists: a real sweep, a partial sweep and "never checked" must never look alike.
  // The policy count goes on a SUB-LINE, not inline: "+2 policy" repeated down every row made this column
  // four times wider than the "0/17" it exists to show.
  function listsCell(r){
    if(!r||!r.ip)return '<span class=dim>—</span>';
    if(!r.dnsbl_usable){
      return `<span class=dim title="${esc(noCoverage(r))}. This is NOT a clean result.">not run</span>`;}
    const hits=(r.blacklists||[]).length, pol=(r.policy_lists||[]).length;
    const total=r.dnsbl_checked, fam=r.dnsbl_family==='ipv6'?' IPv6':'';
    const t=(hits?'Listed by '+(r.blacklists||[]).join(', ')+'. ':'')+
      (pol?'Plus '+pol+' policy listing: '+(r.policy_lists||[]).join(', ')+'. ':'')+
      total+' of '+(r.dnsbl_zones_total||total)+fam+' zones answered.';
    return `<span class="c-${hits>=2?'dirty':hits?'suspect':'clean'}" title="${esc(t)}">${hits}/${total}</span>`+
      (pol?`<span class=sub title="${esc((r.policy_lists||[]).join(', '))}">+${pol} policy</span>`:'');
  }

  // IPQS's flags as three-letter codes (SIGNALS, shared with the single-check card). Spelling them out
  // made this the widest column on the table for the least information; the full name is on the hover.

  // "Is it detectable as a proxy at all" — the question the fraud score can't answer, because it saturates.
  function detectedCell(r){
    if(!r||!r.ip)return '<span class=dim>—</span>';
    if(r.fraud_score==null&&r.getipintel_score==null)
      return '<span class=dim title="No source that detects proxies answered">n/k</span>';
    const f=r.flags||[];
    if(!f.length)return '<span class=c-clean title="IPQualityScore raised no proxy/VPN/Tor flag">none</span>';
    // Three codes, then "+N" — six flags on a row made this the widest column on the table. The overflow
    // count is never silent: the full list is on the cell, and the detail row spells every one of them out.
    const CAPN=3;
    const codes=f.map(s=>{const [code,full]=signalOf(s);
      return `<span class=fx title="${esc(full)}">${esc(code)}</span>`;});
    return `<span title="${esc(f.map(x=>signalOf(x)[1]).join(' · '))}">`+codes.slice(0,CAPN).join('')+
      (codes.length>CAPN?`<span class=fx>+${codes.length-CAPN}</span>`:'')+`</span>`;
  }

  // WHY getIPIntel has no number for this row. It meters 15/min per contact AND per connecting IP, so a
  // bulk run really can exhaust it part-way — and a bare "n/a" left the user asking why one row was blank.
  function giiWhy(r){
    const n=((r&&r.notes)||[]).find(n=>String(n).startsWith('getIPIntel'));
    return n?n+' — not measured'
            :'getIPIntel did not answer — not measured (it allows 15 lookups a minute per contact)';
  }

  let sortKey='', sortDir=1;
  const draw=()=>{
    const done=rows.filter(x=>!x.busy).length;
    const view=rows.slice();
    if(sortKey){const c=COLS.find(c=>c.k===sortKey);
      // ABSENT sorts last in BOTH directions, which is why Infinity is resolved before sortDir is applied.
      // Multiplying the raw difference by -1 sent every unmeasured row to the TOP on a descending sort —
      // so clicking a score header twice to find the worst exit put "n/k" and "n/a" above the dirtiest
      // real result. A row with no measurement is not a ranking position.
      view.sort((a,b)=>{const A=c.get(a),B=c.get(b);
        if(typeof A==='number'&&typeof B==='number'){
          if(A===B)return 0;
          if(A===Infinity)return 1;
          if(B===Infinity)return -1;
          return (A-B)*sortDir;}
        return String(A).localeCompare(String(B))*sortDir;});}

    const tally=k=>rows.filter(x=>!x.busy&&x.r&&!x.r.error&&x.r.verdict===k).length;
    const dead=rows.filter(x=>!x.busy&&x.r&&(x.r.proxy_alive===false||x.r.error)).length;
    const live=rows.filter(x=>x.r&&x.r.proxy_ms!=null).map(x=>x.r.proxy_ms).sort((a,b)=>a-b);
    let h=`<div class=bulkwide><div class="blk panel"><div class=bsum>`+
      `<span data-runsum>${esc(runSummary(rows))}</span>`+
      (tally('clean')?`<span class=s-clean>Clean <b>${tally('clean')}</b></span>`:'')+
      (tally('suspect')?`<span class=s-suspect>Suspect <b>${tally('suspect')}</b></span>`:'')+
      (tally('dirty')?`<span class=s-dirty>Dirty <b>${tally('dirty')}</b></span>`:'')+
      (dead?`<span class=s-dead>Dead <b>${dead}</b></span>`:'')+
      (live.length?`<span>Median <b>${live[Math.floor(live.length/2)]} ms</b></span>`:'')+
      `</div><div class=tablewrap><table class=bulk><thead><tr><th class=cw></th>`+
      COLS.map(c=>`<th class=s data-sort="${c.k}"`+
        (c.ttl?` title="${esc(c.ttl)}"`:'')+
        (sortKey===c.k?` data-dir="${sortDir}"`:'')+`>${esc(c.h)}</th>`).join('')+
      `</tr></thead><tbody>`;
    for(const x of view){
      h+=`<tr class="${x.open?'open':''}"><td class=cw><button class=chev data-deep="${x.i}" `+
        `aria-expanded="${x.open}" title="Show every field for this proxy">`+
        `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" `+
        `stroke-linejoin="round"><path d="M6 3.5 10.5 8 6 12.5"/></svg></button></td>`+
        // A row that has not been measured yet must not render a REASON for having no value. Every
        // score cell falls back to "n/k = no key" when its number is missing, which is true for a
        // finished row and a lie for one still in flight — the key is fine, the check simply has not
        // happened. Guarded once here rather than in each of the ten cells that could get it wrong.
        COLS.map(c=>`<td${c.k==='isp'||c.k==='loc'?' class=cap':''}>`+
          ((x.busy&&c.k!=='proxy'&&c.k!=='status')?'<span class=dim>·</span>':c.cell(x))+
          `</td>`).join('')+`</tr>`;
      if(x.open)h+=`<tr class=detrow><td colspan="${COLS.length+1}">${bulkDetail(x)}</td></tr>`;
    }
    h+=`</tbody></table></div><p class=note style="padding:10px 2px 0">`+
      `Click a heading to sort · the chevron opens a proxy's full detail · hover a heading, code or `+
      `truncated value for the full text · a dot = not checked yet, n/k = no key, n/a = the source `+
      `didn't answer — none of the three is clean`+
      `</p></div></div>`;
    out.innerHTML=h;
  };
  bulk={rows, draw, sort:k=>{sortDir=(sortKey===k?-sortDir:1);sortKey=k;draw();}};
  draw();
  let idx=0; const CAP=4;
  // Tick the elapsed counters WITHOUT redrawing: draw() rebuilds the whole table, which would collapse
  // any detail row the user has open and fight their sort every 500ms. Only the timers change, so only
  // the timers are touched.
  const tick=setInterval(()=>{
    for(const x of rows){
      if(!x.busy||!x.startedAt)continue;
      const el=out.querySelector(`[data-elapsed="${x.i}"]`);
      if(el)el.textContent=elapsed(x);
    }
    const sum=out.querySelector('[data-runsum]');
    if(sum)sum.textContent=runSummary(rows);
  },500);
  const worker=async()=>{ while(idx<rows.length){const x=rows[idx++];
    x.startedAt=Date.now(); draw();          // "queued" -> "checking", with its own clock running
    try{const resp=await fetch(API,{method:'POST',body:bulkBody(x.line)}); x.r=await resp.json();}
    catch(e){x.r={error:String(e)};}
    x.busy=false; draw();
  }};
  // finally, not a trailing statement: if anything in the run throws (a draw() bug, a JSON parse), the
  // interval would otherwise tick forever against a table nobody is updating, and the button would stay
  // disabled with no way back short of a reload.
  try{ await Promise.all(Array.from({length:Math.min(CAP,rows.length)},worker)); }
  finally{ clearInterval(tick); draw(); b.disabled=false; b.textContent='Check all'; }
};

// LAST statement in the script, and the tripwire for a whole class of failure: if ANY top-level statement
// above threw, execution never reaches here and the stamp is absent. Loading the page and checking for it
// is the only thing that catches a runtime error — `node --check` parses the file happily, and a page that
// died on line 3 still renders its full markup with every button inert. That is exactly what shipped once
// (a `const` read from an earlier line, "Cannot access 'KEYFIELDS' before initialization"), and the page
// looked completely normal. tests/test_ipcheck.py asserts this attribute.
document.documentElement.dataset.specterReady='1';
</script>
"""


def serve(port: int, open_browser: bool = True) -> None:
    """A tiny localhost-only web UI over the same check(). Bound to 127.0.0.1 because the POST body
    carries API keys — this is a desktop tool, not a service."""
    import http.server
    import webbrowser

    allowed_hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
    allowed_origins = {f"http://{h}" for h in allowed_hosts}

    class Handler(http.server.BaseHTTPRequestHandler):
        def local_only(self) -> bool:
            """Reject requests that aren't from our own page.

            Binding to 127.0.0.1 stops remote connections but not a browser the user already has
            open. Two separate holes, both of which matter because this server stores API keys:

            * DNS rebinding — a page resolves its own domain to 127.0.0.1 and talks to us. The Host
              check closes that (a rebound request carries the attacker's hostname).
            * CSRF — any site can make the browser POST here cross-origin. Host does NOT help: a
              cross-origin form posts the real `127.0.0.1:<port>` Host. Origin does, and browsers
              always send it on cross-origin requests. Absent means a non-browser client (curl, a
              script), which was never the attack.
            """
            if self.headers.get("Host") not in allowed_hosts:
                self.send_error(403, "Host not allowed")
                return False
            origin = self.headers.get("Origin")
            if origin and origin not in allowed_origins:
                self.send_error(403, "Cross-origin request refused")
                return False
            return True

        def _send(self, body: bytes, ctype: str) -> None:
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if not self.local_only():
                return
            path = urllib.parse.urlparse(self.path).path      # "/?ip=..." must still serve the page
            if path == "/config":
                cfg = load_config()
                self._send(json.dumps({k: cfg.get(k, "") for k in
                                       CONFIG_KEYS}).encode(),
                           "application/json")
            elif path in ("/", "/index.html"):
                self._send(PAGE.encode("utf-8"), "text/html; charset=utf-8")
            elif path.lstrip("/") in STATIC and (WEBAPP / path.lstrip("/")).is_file():
                # The icons + manifest the <head> links to, straight off disk in a dev checkout. Without
                # this the local UI shows a blank tab icon and four 404s in the console — enough to send
                # someone debugging the deploy for a problem that only exists locally. The name is checked
                # against a fixed set, never joined from the request, so the path cannot escape webapp/.
                name = path.lstrip("/")
                self._send((WEBAPP / name).read_bytes(), STATIC[name])
            else:
                self.send_error(404)

        def do_POST(self):
            if not self.local_only():
                return
            n = int(self.headers.get("Content-Length") or 0)
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
                assert isinstance(req, dict)
            except Exception:
                # Never fall back to an empty request: that path would persist "" over the saved
                # keys, so a malformed body would silently erase them.
                self.send_error(400, "Expected a JSON object")
                return
            cfg = load_config()
            # Only fields the request actually carried. Defaulting a missing one to "" would let any
            # partial request erase a saved key — the page always sends all three, but nothing else
            # has to. Sending "" explicitly still clears, which is how the UI clears a key.
            cfg.update({k: req[k] for k in CONFIG_KEYS
                        if k in req})
            save_config(cfg)
            # The page has no getIPIntel field any more — the server supplies the contact, exactly as the
            # hosted function does from its env var. Without this fallback getIPIntel silently never runs
            # in the local UI. (cfg is the post-update copy, so a key the page DID send still wins; it just
            # never sends this one, which is why the saved value survives here.)
            contact = (req.get("getipintel_contact") or os.environ.get("GETIPINTEL_CONTACT")
                       or cfg.get("getipintel_contact") or "")
            # Same fallback for the Scamalytics pair, and for the same reason — the page sends whatever the
            # fields hold, but a saved/env credential must still work when they're blank.
            scam_user = (req.get("scamalytics_user") or os.environ.get("SCAMALYTICS_USER")
                         or cfg.get("scamalytics_user") or "")
            scam_key = (req.get("scamalytics_key") or os.environ.get("SCAMALYTICS_KEY")
                        or cfg.get("scamalytics_key") or "")
            try:
                rep = check(req.get("proxy") or None, req.get("ip") or None,
                            req.get("ipqs_key") or os.environ.get("IPQS_KEY", ""),
                            req.get("abuse_key") or os.environ.get("ABUSEIPDB_KEY", ""),
                            req.get("proxy_scheme") or "http", contact, scam_user, scam_key)
            except Exception as exc:
                rep = {"error": str(exc)}
            self._send(json.dumps(rep).encode(), "application/json")

        def log_message(self, format, *args):     # keep the console to our own output
            pass

    url = f"http://127.0.0.1:{port}/"
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"exit-IP check: {url}  (ctrl-c to stop)")
    if open_browser:
        webbrowser.open(url)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=(__doc__ or "").split("\n")[0])
    ap.add_argument("--proxy", default="", help="proxy to check through; accepts host:port, "
                    "host:port:user:pass, user:pass@host:port, or a scheme:// URL")
    ap.add_argument("--proxy-type", default="http", choices=["http", "socks5", "socks4"],
                    help="transport when --proxy has no scheme:// of its own (default http)")
    ap.add_argument("--ip", default="", help="check this IP directly (skips the exit-IP lookup)")
    ap.add_argument("--ipqs-key", default="", help="IPQualityScore API key")
    ap.add_argument("--abuse-key", default="", help="AbuseIPDB API key")
    ap.add_argument("--getipintel-contact", default="",
                    help="contact email for getIPIntel proxy/VPN detection (free, no signup). Several, "
                         "comma-separated, rotate when one is over quota (15/min, 500/day per contact)")
    ap.add_argument("--scamalytics-user", default="", help="Scamalytics account username (pairs with the key)")
    ap.add_argument("--scamalytics-key", default="", help="Scamalytics API key — its datacenter/VPN/Tor "
                    "classifier feeds the exit type; its score is shown but never scored")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--serve", nargs="?", const=8787, type=int, metavar="PORT",
                    help="open the local web UI (default port 8787)")
    ap.add_argument("--no-browser", action="store_true", help="with --serve, don't open a browser")
    ap.add_argument("--save-keys", action="store_true",
                    help=f"write the given keys to {CONFIG} for next time")
    args = ap.parse_args(argv)

    cfg = load_config()
    k = resolve_keys(args, cfg)
    if args.save_keys:
        cfg.update({"ipqs_key": k["ipqs"], "abuse_key": k["abuse"], "getipintel_contact": k["contact"],
                    "scamalytics_user": k["scam_user"], "scamalytics_key": k["scam_key"]})
        save_config(cfg)
        print(f"keys saved to {CONFIG}")

    if args.serve:
        serve(args.serve, not args.no_browser)
        return 0

    try:
        rep = check(args.proxy or None, args.ip or None, k["ipqs"], k["abuse"], args.proxy_type,
                    k["contact"], k["scam_user"], k["scam_key"])
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    print(json.dumps(rep, indent=2) if args.json else format_report(rep))
    return {"dirty": 1, "unknown": 3}.get(rep.get("verdict", "unknown"), 0)


if __name__ == "__main__":
    raise SystemExit(main())
