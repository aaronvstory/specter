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
import json
import os
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
}

# IPQualityScore's own scoring strictness, sent on every lookup. MEASURED on 23.159.216.252 (a
# Mullvad exit, AS17243) on 2026-08-05: strictness 0 returns fraud_score 20 with proxy=false —
# blind to a commercial VPN exit — while strictness 1 returns 100 with proxy, recent_abuse and
# bot_status all true. Strictness 2 matches 1. IPQS documents 0 as the recommended starting point,
# but 0 cannot answer the only question this tool asks, so 1 it is. The readout names the setting
# because the same IP scores differently elsewhere and a reader needs to be able to reconcile that.
IPQS_STRICTNESS = 1

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


def flags(rep: dict) -> list[str]:
    """The IPQS verdicts that are true, as display labels."""
    return [label for key, label in IPQS_FLAGS if rep.get(key)]


def verdict(rep: dict) -> tuple[str, str]:
    """(level, one-line reason) from the collected signals. level is clean / suspect / dirty /
    unknown. Thresholds follow IPQualityScore's own bands (75+ suspicious, 85+ high risk)."""
    fraud = rep.get("fraud_score")
    abuse = rep.get("abuse_confidence")
    hits = rep.get("blacklists") or []
    why = []
    if fraud is not None and fraud >= 85:
        why.append(f"fraud score {fraud}")
    if len(hits) >= 3:
        why.append(f"{len(hits)} blacklists")
    if abuse is not None and abuse >= 50:
        why.append(f"{abuse}% abuse confidence")
    if rep.get("frequent_abuser") or rep.get("high_risk_attacks") or rep.get("bot_status"):
        why.append("flagged for abuse by IPQS")
    if why:
        return "dirty", "Expect login friction — " + ", ".join(why)

    if fraud is not None and fraud >= 60:
        why.append(f"fraud score {fraud}")
    if hits:
        why.append(f"{len(hits)} blacklist" + ("s" if len(hits) > 1 else ""))
    if abuse is not None and abuse >= 10:
        why.append(f"{abuse}% abuse confidence")
    if rep.get("recent_abuse"):
        why.append("recent abuse reported")
    if rep.get("abuse_velocity") in ("medium", "high"):
        why.append(f"{rep['abuse_velocity']} abuse velocity")
    if why:
        return "suspect", "Usable but marked — " + ", ".join(why)

    if fraud is None and abuse is None and not rep.get("dnsbl_usable"):
        return "unknown", "No source answered — add an API key, or check the network"
    return "clean", "No fraud, abuse, or blacklist signal on this IP"


def fraud_band(score: int) -> str:
    return "high risk" if score >= 85 else "suspicious" if score >= 60 else "clean"


def format_report(rep: dict) -> str:
    """The terminal readout. Every line is omitted when its source had nothing to say, so the
    output never implies a check ran that didn't."""
    rows = [("Exit IP", rep.get("ip") or "unknown")]
    for key, label in (("isp", "ISP"), ("organization", "Organization"), ("asn", "ASN"),
                       ("connection_type", "Connection"), ("location", "Location"),
                       ("timezone", "Time zone")):
        if rep.get(key):
            rows.append((label, str(rep[key])))

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
    host, sep, port = text.rpartition(":")
    if not sep or not host:
        raise ValueError(f"proxy needs host:port — got {text!r}")
    if not port.isdigit() or not (0 < int(port) <= 65535):
        raise ValueError(f"proxy port must be 1–65535 — got {port!r}")
    return host, int(port)


def parse_proxy(text: str, default_scheme: str = "http") -> Proxy | None:
    """Parse the many shapes people actually paste into one Proxy, or None if blank. Accepts, in any
    combination: a `scheme://` prefix (http/https/socks5[h]/socks4[a]); credentials as either
    `user:pass@host:port` or the trailing-colon `host:port:user:pass` a lot of resi providers hand
    out; or a bare `host:port`. `default_scheme` (from the UI's selector) fills in when there's no
    `://`. Raises ValueError with a readable reason on anything it can't make sense of.

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
    if scheme not in _PROXY_SCHEMES:
        raise ValueError(f"unknown proxy scheme {scheme!r} — use http, socks5, or socks4")

    user = password = ""
    if "@" in text:
        # user:pass@host:port — split on the LAST '@' so a '@' inside the password is tolerated.
        creds, _, hostport = text.rpartition("@")
        user, _, password = creds.partition(":")
        host, port = _host_port(hostport)
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


def _opener(proxy: str | None, default_scheme: str = "http"):
    p = parse_proxy(proxy, default_scheme) if isinstance(proxy, str) else proxy
    if not p:
        return urllib.request.build_opener()
    if p.scheme in ("socks5", "socks4"):
        return _socks_opener(p)
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": p.http_url(), "https": p.http_url()}))


def _get_json(url: str, opener, headers: dict | None = None) -> dict | None:
    """GET a JSON document. None on a transport failure; an API's own error body is returned as-is
    so the caller can surface *why* (bad key, quota spent) instead of a generic failure."""
    try:
        req = urllib.request.Request(url, headers=headers or {})
        with opener.open(req, timeout=TIMEOUT) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            return None
    except Exception:
        return None


def lookup_geo(opener, ip: str | None = None) -> dict:
    """ISP/location/timezone for ``ip``, or for this connection's own exit IP when ``ip`` is None
    (in which case it also discovers what that exit IP is, as seen through ``opener``'s proxy)."""
    o = _get_json(f"https://ipwho.is/{urllib.parse.quote(ip, safe='') if ip else ''}", opener)
    if not o or not o.get("success"):
        return {}
    where = ", ".join(x for x in (o.get("city"), o.get("region"), o.get("country")) if x)
    return {
        "ip": o.get("ip"),
        "isp": (o.get("connection") or {}).get("isp"),
        "location": where or None,
        "timezone": (o.get("timezone") or {}).get("id"),
    }


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
        return {"notes": ["IPQualityScore: " + (o.get("message") or "lookup rejected")]}

    out: dict = {"fraud_score": o.get("fraud_score"), "ipqs_strictness": IPQS_STRICTNESS}
    for key_name, _ in IPQS_FLAGS:
        out[key_name] = bool(o.get(key_name))
    for src, dst in (("connection_type", "connection_type"), ("abuse_velocity", "abuse_velocity"),
                     ("organization", "organization"), ("ISP", "isp"), ("host", "host")):
        v = o.get(src)
        # The free tier answers "Premium required." for the paid fields — that's not a value.
        if v and not (isinstance(v, str) and v.lower().startswith("premium")):
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
    }


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
    rev = reverse_v4(ip)
    if not rev:
        return {"blacklists": [], "policy_lists": [], "dnsbl_checked": 0, "dnsbl_usable": False}

    # Every DNSBL lists 127.0.0.2 by convention, so these MUST resolve. Several zones, not one:
    # gating on a single zone means that zone's outage (or its refusal to answer this resolver)
    # silently reports every IP as "unavailable".
    probes = [f"2.0.0.127.{z}" for _, z in DNSBL_ZONES[:4]]
    jobs = [(None, None, p) for p in probes]
    jobs += [(name, zone, f"{rev}.{zone}") for name, zone in DNSBL_ZONES]

    abuse, policy, checked, alive = [], [], 0, False
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
                continue        # the zone refused — it told us nothing, so don't count it as clear
            checked += 1
            if kind == "abuse":
                abuse.append(name)
            elif kind == "policy":
                policy.append((name, policy_label(name, zone, addrs)))
    order = [z[0] for z in DNSBL_ZONES]
    abuse.sort(key=order.index)
    policy = [label for _, label in sorted(policy, key=lambda p: order.index(p[0]))]
    # A real listing — abuse OR policy — is itself proof the resolver works, independent of the
    # sentinel probe. Without this, a run where the 4 probe zones all fail but another zone returns a
    # listing would report the result as "unavailable" AND carry that listing, a contradiction. (This
    # also aligns the desktop with the Android side, whose usable flag is "any zone answered".)
    usable = alive or bool(abuse) or bool(policy)
    return {"blacklists": abuse, "policy_lists": policy,
            "dnsbl_checked": checked if usable else 0, "dnsbl_usable": usable}


def check(proxy: str | None = None, ip: str | None = None,
          ipqs_key: str = "", abuse_key: str = "", proxy_scheme: str = "http") -> dict:
    """Run every available source and return one flat report dict. Blocking (network). ``proxy`` is
    parsed leniently (see ``parse_proxy``); ``proxy_scheme`` fills in the transport when ``proxy``
    carries no ``scheme://`` of its own."""
    opener = _opener(proxy, proxy_scheme)
    rep: dict = {"notes": []}

    def merge(part: dict) -> None:
        rep["notes"].extend(part.pop("notes", []))
        rep.update({k: v for k, v in part.items() if v is not None})

    # An explicit --ip still gets ISP/location/timezone — the readout would otherwise show a bare
    # address with a dash under it, and where an IP sits is half of judging it.
    merge(lookup_geo(opener, ip))
    if not rep.get("ip"):
        if not ip:
            rep["notes"].append("Exit-IP lookup failed — proxy down, or no route out?")
            return rep
        rep["ip"] = ip      # geo failed, but we were told which IP to check — carry on without it

    if ipqs_key:
        merge(lookup_ipqs(rep["ip"], ipqs_key, opener))
    else:
        rep["notes"].append("No IPQualityScore key — no fraud score (set it in the Keys row)")
    if abuse_key:
        merge(lookup_abuseipdb(rep["ip"], abuse_key, opener))

    # ponytail: the blocklist queries go out the LOCAL resolver, not the proxy — an HTTP proxy
    # can't carry DNS. That's fine here: the query names the exit IP explicitly, so the answer is
    # about that IP either way. (On-device it must go through the tunnel, because there the lookup
    # is also how the IP itself is learned.)
    rep.update(dnsbl_check(rep["ip"]))
    rep["verdict"], rep["verdict_reason"] = verdict(rep)
    rep["flags"] = flags(rep)
    return rep


# ---- config --------------------------------------------------------------------------------


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


def resolve_keys(args, cfg: dict) -> tuple[str, str]:
    ipqs = args.ipqs_key or os.environ.get("IPQS_KEY") or cfg.get("ipqs_key") or ""
    abuse = args.abuse_key or os.environ.get("ABUSEIPDB_KEY") or cfg.get("abuse_key") or ""
    return ipqs.strip(), abuse.strip()


# ---- local web UI --------------------------------------------------------------------------

PAGE = r"""<!doctype html>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Specter · exit-IP check</title>
<style>
:root{--bg:#16161a;--card:#212129;--card2:#262630;--line:#34343f;--ink:#f1f1f4;--soft:#b9b9c4;
--dim:#7d7d8a;--gold:#ffd54a;--sage:#7fb58c;--red:#ef8a8a;--amber:#f0b562;--blue:#6cc4e8}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
.wrap{max-width:780px;margin:0 auto;padding:28px 20px 64px}
h1{font-size:12px;letter-spacing:.14em;text-transform:uppercase;color:var(--dim);font-weight:600;margin:0 0 18px}
.card{background:var(--card);border-radius:10px;padding:18px;margin-bottom:14px}
label{display:block;font-size:11px;letter-spacing:.09em;text-transform:uppercase;color:var(--dim);margin-bottom:5px}
input{width:100%;background:var(--card2);border:1px solid var(--line);border-radius:6px;
color:var(--ink);padding:10px 12px;font:inherit}
input:focus{outline:none;border-color:var(--gold)}
.grid{display:grid;grid-template-columns:1fr 200px;gap:12px}
@media(max-width:620px){.grid{grid-template-columns:1fr}}
details{margin-top:12px}summary{cursor:pointer;color:var(--dim);font-size:13px}
button{width:100%;margin-top:14px;background:var(--gold);color:#211b02;border:0;border-radius:6px;
padding:13px;font:600 15px/1 system-ui;cursor:pointer}
button:disabled{background:var(--card2);color:var(--dim);cursor:default}
.verdict{border-left:4px solid var(--dim);padding:14px 16px;border-radius:8px;background:var(--card)}
.verdict b{display:block;font-size:19px;letter-spacing:.04em}
.verdict span{color:var(--soft);font-size:13px}
.dirty{border-color:var(--red)}.dirty b{color:var(--red)}
.suspect{border-color:var(--amber)}.suspect b{color:var(--amber)}
.clean{border-color:var(--sage)}.clean b{color:var(--sage)}
.unknown{border-color:var(--dim)}.unknown b{color:var(--dim)}
.ip{font:600 27px/1.2 ui-monospace,SFMono-Regular,Consolas,monospace;letter-spacing:-.01em}
.sub{color:var(--soft);font-size:13px;margin-top:4px}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:14px}
.tile{background:var(--card);border-radius:10px;padding:16px}
.tile em{font-style:normal;display:block;font-size:11px;letter-spacing:.09em;text-transform:uppercase;color:var(--dim)}
.tile strong{display:block;font-size:26px;font-weight:600;margin:6px 0 2px}
.tile small{color:var(--soft);font-size:12px}
.chips{display:flex;flex-wrap:wrap;gap:7px;margin-top:12px}
.chip{font-size:12px;padding:4px 10px;border-radius:99px;background:#f0b56222;color:var(--amber)}
.chip.ok{background:#7fb58c22;color:var(--sage)}
.rows{margin-top:6px}
.row{display:flex;gap:14px;padding:9px 0;border-top:1px solid var(--line);font-size:13px}
.row:first-child{border-top:0}
.row i{font-style:normal;color:var(--dim);width:120px;flex:none;text-transform:uppercase;font-size:11px;letter-spacing:.07em;padding-top:2px}
.note{color:var(--dim);font-size:12px;margin-top:4px}
.hide{display:none}
</style>
<div class=wrap>
<h1>Specter · exit-IP check</h1>
<div class=card>
  <div class=grid>
    <div><label for=proxy>Proxy</label>
      <div style="display:flex;gap:8px">
        <select id=ptype style="width:104px;flex:none;background:var(--card2);border:1px solid var(--line);border-radius:6px;color:var(--ink);padding:10px 8px;font:inherit">
          <option value=http>HTTP</option><option value=socks5>SOCKS5</option><option value=socks4>SOCKS4</option>
        </select>
        <input id=proxy placeholder="host:port  ·  host:port:user:pass  ·  user:pass@host:port">
      </div></div>
    <div><label for=ip>IP (optional)</label><input id=ip placeholder="check directly"></div>
  </div>
  <details><summary>API keys</summary>
    <div class=grid style="margin-top:10px">
      <div><label for=ipqs>IPQualityScore key</label><input id=ipqs type=password></div>
      <div><label for=abuse>AbuseIPDB key</label><input id=abuse type=password></div>
    </div>
    <p class=note>Stored locally in ~/.specter-ipcheck.json. The blacklist count needs no key.</p>
  </details>
  <button id=go>Check</button>
</div>
<div id=out></div>
</div>
<script>
const $=s=>document.querySelector(s), out=$('#out');
const q=new URLSearchParams(location.search);
fetch('/config').then(r=>r.json()).then(c=>{
  $('#proxy').value=q.get('proxy')||c.proxy||'';
  $('#ptype').value=q.get('ptype')||c.proxy_scheme||'http';
  $('#ipqs').value=c.ipqs_key||''; $('#abuse').value=c.abuse_key||'';
  $('#ip').value=q.get('ip')||'';
  // ?ip=… or ?proxy=… runs on load, so a bookmark is a one-click check.
  if(q.get('ip')||q.get('proxy'))$('#go').click();
});
const esc=s=>String(s).replace(/[<>&]/g,c=>({'<':'&lt;','>':'&gt;','&':'&amp;'}[c]));
function row(k,v){return `<div class=row><i>${esc(k)}</i><div>${esc(v)}</div></div>`}
function render(r){
  if(r.error){out.innerHTML=`<div class="card verdict dirty"><b>FAILED</b><span>${esc(r.error)}</span></div>`;return}
  const band=s=>s>=85?'dirty':s>=60?'suspect':'clean';
  let t='';
  t+=`<div class="card verdict ${r.verdict}"><b>${r.verdict.toUpperCase()}</b><span>${esc(r.verdict_reason)}</span></div>`;
  t+=`<div class=card><div class=ip>${esc(r.ip||'unknown')}</div><div class=sub>${
      esc([r.isp,r.connection_type,r.location].filter(Boolean).join(' · ')||'—')}</div>`;
  if(r.timezone)t+=`<div class=sub>${esc(r.timezone)}</div>`;
  t+=`</div>`;
  t+='<div class=tiles>';
  const pol=(r.policy_lists||[]).length;
  if(r.fraud_score!=null)t+=`<div class=tile><em>Fraud risk</em><strong class="${band(r.fraud_score)
      }" style="color:var(--${band(r.fraud_score)==='dirty'?'red':band(r.fraud_score)==='suspect'?'amber':'sage'})">${
      esc(r.fraud_score)}</strong><small>IPQualityScore${r.ipqs_strictness!=null?` · strictness ${esc(r.ipqs_strictness)}`:''} · ${esc(r.fraud_score>=85?'high risk':r.fraud_score>=60?'suspicious':'clean')}</small></div>`;
  const hits=(r.blacklists||[]).length, col=hits>=3?'red':hits?'amber':(r.dnsbl_usable?'sage':'dim');
  // A policy listing is still a listing: say so here, or this tile reads "0" beside a checker that counts it.
  t+=`<div class=tile><em>Blacklists</em><strong style="color:var(--${col})">${
      r.dnsbl_usable||hits?hits:'—'}</strong><small>${
      hits?esc(r.blacklists.join(', ')):r.dnsbl_usable?`none of ${esc(r.dnsbl_checked)} lists`:'blocklist DNS unreachable'}${
      pol?` · plus ${pol} policy listing${pol>1?'s':''}`:''}</small></div>`;
  if(pol)t+=`<div class=tile><em>Policy lists</em><strong style="font-size:20px;color:var(--blue)">${
      pol}</strong><small>${esc(r.policy_lists.join(', '))} · a mail-sending policy listing, not an abuse report</small></div>`;
  if(r.abuse_confidence!=null){const ac=r.abuse_confidence>=50?'red':r.abuse_confidence>=10?'amber':'sage';
    t+=`<div class=tile><em>Abuse</em><strong style="color:var(--${ac})">${esc(r.abuse_confidence)}%</strong><small>${
      esc(r.abuse_reports||0)} reports in 90 days</small></div>`}
  if(r.abuse_velocity)t+=`<div class=tile><em>Abuse velocity</em><strong style="font-size:20px">${
      esc(r.abuse_velocity)}</strong><small>IPQualityScore</small></div>`;
  t+='</div>';
  if(r.fraud_score!=null){
    t+='<div class=card><em style="font-style:normal;font-size:11px;letter-spacing:.09em;text-transform:uppercase;color:var(--dim)">Flagged as</em><div class=chips>';
    t+=(r.flags||[]).length?r.flags.map(f=>`<span class=chip>${esc(f)}</span>`).join('')
        :'<span class="chip ok">Not flagged as proxy or VPN</span>';
    t+='</div></div>';
  }
  let d='';
  [['ISP','isp'],['Organization','organization'],['ASN','asn'],['Host','host'],
   ['Connection','connection_type'],['Usage','usage_type'],['Location','location'],
   ['Time zone','timezone']].forEach(([k,v])=>{if(r[v])d+=row(k,r[v])});
  if(d)t+=`<div class=card><div class=rows>${d}</div></div>`;
  if((r.notes||[]).length)t+=`<div class=card>${r.notes.map(n=>`<div class=note>${esc(n)}</div>`).join('')}</div>`;
  out.innerHTML=t;
}
$('#go').onclick=async()=>{
  const b=$('#go'); b.disabled=true; b.textContent='Checking…';
  out.innerHTML='<div class="card"><span class=sub>Running lookups…</span></div>';
  try{
    const r=await fetch('/check',{method:'POST',body:JSON.stringify({
      proxy:$('#proxy').value.trim(), proxy_scheme:$('#ptype').value, ip:$('#ip').value.trim(),
      ipqs_key:$('#ipqs').value.trim(), abuse_key:$('#abuse').value.trim()})});
    render(await r.json());
  }catch(e){render({error:String(e)})}
  b.disabled=false; b.textContent='Check';
};
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
                                       ("proxy", "proxy_scheme", "ipqs_key", "abuse_key")}).encode(),
                           "application/json")
            elif path in ("/", "/index.html"):
                self._send(PAGE.encode("utf-8"), "text/html; charset=utf-8")
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
            cfg.update({k: req[k] for k in ("proxy", "proxy_scheme", "ipqs_key", "abuse_key")
                        if k in req})
            save_config(cfg)
            try:
                rep = check(req.get("proxy") or None, req.get("ip") or None,
                            req.get("ipqs_key", ""), req.get("abuse_key", ""),
                            req.get("proxy_scheme") or "http")
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
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--serve", nargs="?", const=8787, type=int, metavar="PORT",
                    help="open the local web UI (default port 8787)")
    ap.add_argument("--no-browser", action="store_true", help="with --serve, don't open a browser")
    ap.add_argument("--save-keys", action="store_true",
                    help=f"write the given keys to {CONFIG} for next time")
    args = ap.parse_args(argv)

    cfg = load_config()
    ipqs, abuse = resolve_keys(args, cfg)
    if args.save_keys:
        cfg.update({"ipqs_key": ipqs, "abuse_key": abuse})
        save_config(cfg)
        print(f"keys saved to {CONFIG}")

    if args.serve:
        serve(args.serve, not args.no_browser)
        return 0

    try:
        rep = check(args.proxy or None, args.ip or None, ipqs, abuse, args.proxy_type)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    print(json.dumps(rep, indent=2) if args.json else format_report(rep))
    return {"dirty": 1, "unknown": 3}.get(rep.get("verdict", "unknown"), 0)


if __name__ == "__main__":
    raise SystemExit(main())
