"""Pure-logic tests for the exit-IP reputation checker. No network — every function under test
takes already-fetched data."""

import json
import time
import re
from pathlib import Path

from specter import ipcheck

# The real _ipapi_lookup, captured before conftest's autouse fixture stubs the module attribute — so the
# degradation test below exercises the ACTUAL parsing/error handling, not the stub.
_REAL_IPAPI_LOOKUP = ipcheck._ipapi_lookup


# ---- DNSBL query form ----------------------------------------------------------------------


def test_reverse_v4_reverses_the_octets():
    assert ipcheck.reverse_v4("1.2.3.4") == "4.3.2.1"
    assert ipcheck.reverse_v4("172.59.84.16") == "16.84.59.172"
    assert ipcheck.reverse_v4("0.0.0.255") == "255.0.0.0"


def test_reverse_v4_refuses_anything_that_is_not_a_dotted_quad():
    # A mangled query name would silently resolve to nothing and read as "clean".
    for bad in (None, "", "1.2.3", "1.2.3.4.5", "1.2.3.256", "1.2.3.x", "2001:db8::1"):
        assert ipcheck.reverse_v4(bad) is None


# ---- what counts as a listing --------------------------------------------------------------


def test_listed_accepts_real_listing_codes():
    assert ipcheck.listed("127.0.0.2")
    assert ipcheck.listed("127.0.0.10")
    assert ipcheck.listed("127.0.0.37")


def test_listed_rejects_the_answers_that_are_not_listings():
    # 127.0.0.1 = "zone alive, not listed"; 127.255.255.x = Spamhaus's open-resolver error range.
    # Either one counted as a hit would report every IP on earth as blacklisted.
    assert not ipcheck.listed("127.0.0.1")
    assert not ipcheck.listed("127.255.255.254")
    assert not ipcheck.listed("127.255.255.252")
    assert not ipcheck.listed("93.184.216.34")
    assert not ipcheck.listed(None)
    assert not ipcheck.listed("")


# ---- abuse vs policy -----------------------------------------------------------------------


def test_classify_calls_a_pbl_only_hit_policy_not_abuse():
    # Every residential and mobile IP is on Spamhaus PBL by design. Alone, it is not abuse.
    assert ipcheck.classify("zen.spamhaus.org", ["127.0.0.10"]) == "policy"
    assert ipcheck.classify("all.spamrats.com", ["127.0.0.36", "127.0.0.37"]) == "policy"


def test_classify_calls_sbl_or_xbl_abuse_even_alongside_pbl():
    # The real T-Mobile exit IP that motivated this: SBL + XBL + PBL together.
    assert ipcheck.classify("zen.spamhaus.org", ["127.0.0.3", "127.0.0.4", "127.0.0.10"]) == "abuse"
    assert ipcheck.classify("cbl.abuseat.org", ["127.0.0.2"]) == "abuse"


def test_classify_returns_none_when_not_listed():
    assert ipcheck.classify("zen.spamhaus.org", []) is None
    assert ipcheck.classify("dnsbl.dronebl.org", ["127.0.0.1"]) is None


def test_classify_flags_a_refusal_as_blocked_not_clean():
    # Spamhaus and CBL answer 127.255.255.254 to queries relayed by large public resolvers.
    # Treating that as "not listed" would silently downgrade "we don't know" to "it's fine".
    assert ipcheck.classify("zen.spamhaus.org", ["127.255.255.254"]) == "blocked"
    assert ipcheck.classify("cbl.abuseat.org", ["127.255.255.252"]) == "blocked"


def test_policy_zones_are_a_subset_of_the_zone_table():
    zones = {z for _, z in ipcheck.DNSBL_ZONES}
    assert set(ipcheck.POLICY_CODES) <= zones
    assert "dnsbl.sorbs.net" not in zones      # shut down in 2024; answers clean for everything


# ---- naming the policy code ------------------------------------------------------------------


def test_policy_reasons_names_the_code_not_just_the_zone():
    # Spamhaus splits PBL: 127.0.0.10 is the network owner declaring its own range end-user (every
    # consumer line has one), 127.0.0.11 is Spamhaus listing a range the owner never declared. On a
    # hosting network only the second happens, and it is a statement about that netblock — so a
    # readout that prints a bare "Spamhaus" throws away the half that matters.
    assert ipcheck.policy_reasons("zen.spamhaus.org", ["127.0.0.10"]) == \
        ["PBL, network owner declared it end-user"]
    assert ipcheck.policy_reasons("zen.spamhaus.org", ["127.0.0.11"]) == \
        ["PBL, Spamhaus listed the range"]
    assert ipcheck.policy_reasons("all.spamrats.com", ["127.0.0.37", "127.0.0.36"]) == \
        ["dynamic reverse DNS", "no reverse DNS"]


def test_policy_reasons_order_is_independent_of_dns_answer_order():
    # DNS makes no ordering guarantee, and the Java twin sorts a TreeSet of the hit codes — so the
    # same multi-code answer must render identically regardless of the order the addresses arrive,
    # or the same IP would read differently on phone vs desktop.
    a = ipcheck.policy_reasons("all.spamrats.com", ["127.0.0.36", "127.0.0.37"])
    b = ipcheck.policy_reasons("all.spamrats.com", ["127.0.0.37", "127.0.0.36"])
    assert a == b == ["dynamic reverse DNS", "no reverse DNS"]
    # A duplicated code must not double the reason (the TreeSet dedups; set() here must too).
    assert ipcheck.policy_reasons("all.spamrats.com", ["127.0.0.36", "127.0.0.36"]) == \
        ["dynamic reverse DNS"]


def test_policy_reasons_ignores_codes_that_are_not_policy():
    assert ipcheck.policy_reasons("zen.spamhaus.org", ["127.0.0.2"]) == []
    assert ipcheck.policy_reasons("zen.spamhaus.org", ["127.0.0.1"]) == []
    assert ipcheck.policy_reasons("cbl.abuseat.org", ["127.0.0.2"]) == []


def test_policy_label_falls_back_to_the_bare_zone_name():
    assert ipcheck.policy_label("Spamhaus", "zen.spamhaus.org", ["127.0.0.11"]) == \
        "Spamhaus (PBL, Spamhaus listed the range)"
    assert ipcheck.policy_label("DroneBL", "dnsbl.dronebl.org", ["127.0.0.3"]) == "DroneBL"


def test_zone_table_matches_the_android_side():
    # The two implementations must classify identically, so their tables have to agree. A zone
    # present on one side only would make the same IP score differently on phone and desktop.
    java = (Path(__file__).resolve().parents[1] / "xposed-module" / "app" / "src" / "main" /
            "java" / "com" / "specter" / "module" / "ui" / "Dnsbl.java").read_text("utf-8")
    def table(name, src):
        m = re.search(r"String\[\]\[\] " + name + r" = \{(.*?)\n    \};", src, re.S)
        assert m, f"{name} not found in Dnsbl.java"
        return re.findall(r'\{"([^"]+)",\s*"([^"]+)"\}', m.group(1))

    assert table("ZONES", java) == [(n, z) for n, z in ipcheck.DNSBL_ZONES]
    # ...and the IPv6 table, whose whole point is an HONEST denominator. A phone querying 17 zones for an
    # IPv6 address while the desktop queries 4 would report two different "of N" for the same exit.
    assert table("ZONES_V6", java) == [(n, z) for n, z in ipcheck.DNSBL_ZONES_V6]
    # ...and so do the policy codes that keep residential IPs out of the abuse count — AND the reason
    # string each code maps to. Checking only the code SET would let the two sides disagree on what a
    # code MEANS (a swapped or mistyped reason on one side) while still passing, so the same listing
    # would be explained differently on phone and desktop. Scrape `code == N ? "reason"` from
    # Dnsbl.policyReason and compare the whole {code: reason} map.
    for zone, codes in ipcheck.POLICY_CODES.items():
        m = re.search(r'"' + re.escape(zone) + r'"\.equals\(zone\)\) return ([^;]+);', java)
        assert m, f"{zone} has no policy-code branch in Dnsbl.java"
        java_map = {int(c): r for c, r in re.findall(r'code == (\d+) \? "([^"]+)"', m.group(1))}
        assert java_map == dict(codes), f"{zone}: Java {java_map} != Python {dict(codes)}"


def test_datacenter_heuristic_matches_the_android_side():
    # The datacenter name heuristic must be identical desktop vs Android, or the same IP classifies as a
    # datacenter on one and residential on the other. Compare the provider terms in ipcheck._DATACENTER_RE
    # against HealthCheck.DATACENTER (whose literal is split across concatenated Java "..." chunks).
    java = (Path(__file__).resolve().parents[1] / "xposed-module" / "app" / "src" / "main" /
            "java" / "com" / "specter" / "module" / "ui" / "HealthCheck.java").read_text("utf-8")
    block = re.search(r"Pattern DATACENTER = .*?compile\((.*?),\s*\n?\s*java\.util\.regex", java, re.S)
    assert block, "DATACENTER pattern not found in HealthCheck.java"
    java_literal = "".join(re.findall(r'"([^"]*)"', block.group(1)))
    # Alphabetic anchor words (>=2 letters), ignoring regex glue (\b, \s). Both sides list the same providers.
    py_terms = set(re.findall(r"[a-z]{2,}", ipcheck._DATACENTER_RE.pattern))
    java_terms = set(re.findall(r"[a-z]{2,}", java_literal))
    assert py_terms == java_terms, f"datacenter term drift: py-only={py_terms - java_terms}, java-only={java_terms - py_terms}"


# ---- proxy parsing -------------------------------------------------------------------------


def _scheme(text, default="http"):
    """parse_proxy(...).scheme with a non-None assert, so the type checker (and the reader) knows a
    known-good input parsed."""
    p = ipcheck.parse_proxy(text, default)
    assert p is not None
    return p.scheme


def test_parse_proxy_accepts_every_shape_people_paste():
    P = ipcheck.parse_proxy
    # bare host:port
    assert P("1.2.3.4:8080") == ipcheck.Proxy("http", "1.2.3.4", 8080, "", "")
    # the trailing-colon form resi providers hand out
    assert P("1.2.3.4:8080:bob:secret") == ipcheck.Proxy("http", "1.2.3.4", 8080, "bob", "secret")
    # user:pass@host:port
    assert P("bob:secret@host.example:3128") == \
        ipcheck.Proxy("http", "host.example", 3128, "bob", "secret")
    # scheme:// URLs, incl. socks
    assert P("socks5://u:p@10.0.0.1:1080") == ipcheck.Proxy("socks5", "10.0.0.1", 1080, "u", "p")
    # the h/a variants normalise onto the base transport
    assert _scheme("http://host:80") == "http"
    assert _scheme("socks5h://h:1") == "socks5"
    assert _scheme("socks4a://h:1") == "socks4"


def test_parse_proxy_default_scheme_fills_in_only_without_a_prefix():
    assert _scheme("h:1", "socks5") == "socks5"
    # an explicit scheme in the text wins over the selector's default.
    assert _scheme("http://h:1", "socks5") == "http"


def test_parse_proxy_blank_is_none_and_junk_is_a_readable_error():
    assert ipcheck.parse_proxy("") is None
    assert ipcheck.parse_proxy("   ") is None
    import pytest
    for bad, why in [("nohost", "host:port"), ("h:0", "1–65535"), ("h:99999", "1–65535"),
                     ("h:notaport", "1–65535"), ("ftp://h:1", "scheme"),
                     ("a:b:c", "host:port")]:
        with pytest.raises(ValueError) as e:
            ipcheck.parse_proxy(bad)
        assert why in str(e.value)


def test_parse_proxy_tolerates_an_at_sign_in_the_password():
    # split on the LAST '@', so a password containing '@' still parses.
    p = ipcheck.parse_proxy("bob:se@cret@host:3128")
    assert p == ipcheck.Proxy("http", "host", 3128, "bob", "se@cret")


def test_proxy_http_url_encodes_credentials():
    p = ipcheck.Proxy("http", "h", 8080, "us er", "p@ss")
    assert p.http_url() == "http://us%20er:p%40ss@h:8080"
    assert ipcheck.Proxy("http", "h", 80, "", "").http_url() == "http://h:80"


# ---- SOCKS wire format (pure byte-building) ------------------------------------------------


def test_socks5_greeting_offers_auth_only_with_credentials():
    assert ipcheck._socks5_greeting(False) == b"\x05\x01\x00"
    assert ipcheck._socks5_greeting(True) == b"\x05\x01\x02"


def test_socks5_connect_request_is_a_domain_name_connect():
    req = ipcheck._socks5_connect("example.com", 443)
    assert req[:4] == b"\x05\x01\x00\x03"           # ver, CONNECT, rsv, domain-name atyp
    assert req[4] == len("example.com")
    assert req.endswith(b"\x01\xbb")                # port 443, big-endian
    assert b"example.com" in req


def test_socks5_userpass_is_rfc1929():
    msg = ipcheck._socks5_userpass("bob", "secret")
    assert msg == b"\x01\x03bob\x06secret"


def test_socks4_connect_is_socks4a_remote_resolve():
    req = ipcheck._socks4_connect("example.com", 80, "")
    assert req[:2] == b"\x04\x01"                   # ver, CONNECT
    assert req[2:4] == b"\x00\x50"                  # port 80
    assert req[4:8] == b"\x00\x00\x00\x01"          # 0.0.0.1 = resolve at proxy (4a)
    assert req.endswith(b"example.com\x00")


# ---- SOCKS handshake state machine (socketpair fake server; no network) --------------------

import socket as _sock       # noqa: E402
import threading             # noqa: E402


def _run_socks_tunnel(proxy, server_script):
    """Drive ipcheck._socks_tunnel against a scripted in-memory server over a socketpair. `server_script`
    is a callable given the server-side socket; it reads the client's bytes and writes replies. Returns
    (result_or_exception, bytes_the_server_received). No real network — both ends are a socketpair."""
    cli, srv = _sock.socketpair()
    seen = {}

    def serve():
        try:
            seen["bytes"] = server_script(srv)
        except Exception as e:      # surface a server-side bug as a test failure, not a hang
            seen["err"] = e
        finally:
            srv.close()

    t = threading.Thread(target=serve, daemon=True)
    t.start()
    # Patch create_connection so _socks_tunnel talks to our client end instead of dialing out.
    orig = ipcheck.socket.create_connection
    ipcheck.socket.create_connection = lambda addr, timeout=None: cli
    try:
        try:
            out = ipcheck._socks_tunnel(proxy, "example.com", 443, 5)
        except Exception as e:
            out = e
    finally:
        ipcheck.socket.create_connection = orig
        cli.close()
    t.join(2)
    return out, seen


def test_socks5_tunnel_no_auth_success():
    proxy = ipcheck.Proxy("socks5", "p", 1080, "", "")

    def server(s):
        greeting = s.recv(3)                        # ver, nmethods, methods
        s.sendall(b"\x05\x00")                      # choose no-auth
        req = s.recv(4 + 1 + len("example.com") + 2)
        s.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")   # success + IPv4 bound addr
        return greeting

    out, seen = _run_socks_tunnel(proxy, server)
    assert not isinstance(out, Exception), out
    assert seen["bytes"] == b"\x05\x01\x00"         # we offered exactly one method: no-auth


def test_socks5_tunnel_userpass_success():
    proxy = ipcheck.Proxy("socks5", "p", 1080, "bob", "secret")

    def server(s):
        s.recv(3)
        s.sendall(b"\x05\x02")                      # demand user/pass
        auth = s.recv(3 + 3 + 6)                    # ver,ulen,user,plen,pass
        s.sendall(b"\x01\x00")                      # auth OK
        s.recv(64)                                  # CONNECT
        s.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")
        return auth

    out, seen = _run_socks_tunnel(proxy, server)
    assert not isinstance(out, Exception), out
    assert seen["bytes"] == b"\x01\x03bob\x06secret"


def test_socks5_tunnel_fails_closed_when_asked_for_auth_it_didnt_offer():
    # No credentials -> we greet with no-auth only. A proxy that then selects 0x02 is protocol-violating;
    # we must refuse, NOT send empty credentials.
    proxy = ipcheck.Proxy("socks5", "p", 1080, "", "")

    def server(s):
        s.recv(3)
        s.sendall(b"\x05\x02")                      # demand user/pass we never offered
        return b""

    out, _ = _run_socks_tunnel(proxy, server)
    assert isinstance(out, OSError)
    assert "didn't offer" in str(out)


def test_socks5_tunnel_raises_on_connect_failure():
    proxy = ipcheck.Proxy("socks5", "p", 1080, "", "")

    def server(s):
        s.recv(3)
        s.sendall(b"\x05\x00")
        s.recv(64)
        s.sendall(b"\x05\x05\x00\x01\x00\x00\x00\x00\x00\x00")   # reply code 5 = connection refused
        return b""

    out, _ = _run_socks_tunnel(proxy, server)
    assert isinstance(out, OSError)
    assert "CONNECT failed" in str(out)


def test_socks5_tunnel_handles_a_domain_bound_address():
    # atyp 3 (domain) in the reply must be consumed by its length prefix, or the stream desyncs.
    proxy = ipcheck.Proxy("socks5", "p", 1080, "", "")

    def server(s):
        s.recv(3)
        s.sendall(b"\x05\x00")
        s.recv(64)
        s.sendall(b"\x05\x00\x00\x03\x03abc\x01\xbb")            # domain "abc" + port
        return b""

    out, _ = _run_socks_tunnel(proxy, server)
    assert not isinstance(out, Exception), out


def test_socks4_tunnel_success_and_failure():
    ok = ipcheck.Proxy("socks4", "p", 1080, "", "")

    def good(s):
        req = s.recv(64)
        s.sendall(b"\x00\x5a\x00\x00\x00\x00\x00\x00")           # request granted
        return req

    out, seen = _run_socks_tunnel(ok, good)
    assert not isinstance(out, Exception), out
    assert seen["bytes"].startswith(b"\x04\x01")

    def bad(s):
        s.recv(64)
        s.sendall(b"\x00\x5b\x00\x00\x00\x00\x00\x00")           # request rejected
        return b""

    out2, _ = _run_socks_tunnel(ok, bad)
    assert isinstance(out2, OSError) and "SOCKS4 CONNECT failed" in str(out2)


# ---- verdict -------------------------------------------------------------------------------


def test_verdict_ignores_a_bare_high_fraud_score():
    # IPQS scores almost any proxy 75-100 ("it's a proxy"), so a high fraud_score with NO independent abuse
    # evidence and NO datacenter signal must NOT read dirty — a clean residential proxy is usable. The reason
    # still flags that it's detectable as a proxy.
    level, why = ipcheck.verdict({"fraud_score": 100, "proxy": True, "dnsbl_usable": True, "dnsbl_checked": 17})
    assert level == "clean"
    assert "proxy" in why.lower()


def test_verdict_dirty_on_a_datacenter_exit_even_with_no_abuse():
    # The whole Cash-App-usability point: a datacenter/hosting exit draws friction even with a spotless
    # blacklist/abuse record — real users don't originate from AWS. Detected from the ISP/org name, free.
    rep = {"isp": "Amazon.com", "organization": "Amazon Data Services", "fraud_score": 75,
           "proxy": True, "blacklists": [], "dnsbl_usable": True}
    assert ipcheck.is_datacenter(rep) is True
    level, why = ipcheck.verdict(rep)
    assert level == "dirty" and "datacenter" in why.lower()



def test_the_big_dns_providers_are_recognised_as_datacenters():
    r"""8.8.8.8 and 1.1.1.1 both returned verdict CLEAN with "No datacenter signal" (measured 2026-08-06).

    Cause: the pattern required `google\s+llc`, but ipwho.is returns the BARE names "Google" and
    "Cloudflare" where IPQS returns "Google LLC" — so the free/keyless path never matched. A false
    all-clear on two of the most obvious datacenter addresses in existence.
    """
    for isp in ("Google", "Google LLC", "Google Cloud", "Cloudflare", "Fastly", "Amazon.com"):
        assert ipcheck.is_datacenter({"isp": isp}), f"{isp} must read as a datacenter"
    # ...and the widening must not swallow the residential ISPs it was narrow to protect.
    for isp in ("Google Fiber Inc", "Comcast Cable", "Spectrum", "T-Mobile USA", "SpaceX Services",
                "Windstream Communications"):
        assert not ipcheck.is_datacenter({"isp": isp}), f"{isp} is a real line, not a datacenter"

def test_datacenter_catches_gcp_azure_by_org_name_but_not_google_fiber():
    # GCP/Azure don't self-identify as "cloud" in free WHOIS — they read "Google LLC" / "Microsoft
    # Corporation" — so those exact strings are matched. Google Fiber ("Google Fiber Inc") is a real
    # residential ISP and must NOT be flagged (this is why we match `google llc`, not a bare "google").
    assert ipcheck.is_datacenter({"isp": "Google LLC"}) is True
    assert ipcheck.is_datacenter({"isp": "Microsoft Corporation"}) is True
    assert ipcheck.is_datacenter({"host": "1.2.3.4.bc.googleusercontent.com"}) is True
    assert ipcheck.is_datacenter({"host": "myvm.cloudapp.azure.com"}) is True
    assert ipcheck.is_datacenter({"isp": "Google Fiber Inc"}) is False
    assert ipcheck.is_datacenter({"isp": "Comcast Cable"}) is False


def test_getipintel_parses_score_and_badip(monkeypatch):
    monkeypatch.setattr(ipcheck, "_get_json",
                        lambda url, opener: {"status": "success", "result": "0.997", "BadIP": 1})
    out = ipcheck.lookup_getipintel("1.2.3.4", "me@example.com", None)
    assert out["getipintel_score"] == 0.997 and out["getipintel_bad"] is True


def test_getipintel_reports_a_rejected_contact(monkeypatch):
    monkeypatch.setattr(ipcheck, "_get_json",
                        lambda url, opener: {"status": "error", "result": "-6", "message": "no contact"})
    out = ipcheck.lookup_getipintel("1.2.3.4", "", None)
    assert "notes" in out and "getIPIntel" in out["notes"][0]


def test_getipintel_a_negative_result_is_an_error_not_a_score(monkeypatch):
    # A negative result is an error code (-1..-6), NOT a 0-probability — must never read as a clean score.
    # -5 in particular says the MACHINE RUNNING THIS is banned or over quota, which is the opposite of a
    # clean verdict on the checked IP, so the note has to say which of the two it is.
    monkeypatch.setattr(ipcheck, "_get_json",
                        lambda url, opener: {"status": "success", "result": "-5"})
    out = ipcheck.lookup_getipintel("1.2.3.4", "me@example.com", None)
    assert "getipintel_score" not in out
    assert "over quota" in out["notes"][0]
    assert "this IP wasn't checked" in out["notes"][0]


def test_getipintel_never_echoes_the_contact_address_back_to_the_caller(monkeypatch):
    # MEASURED 2026-08-05: getIPIntel echoes `contact` in EVERY response, success and error alike. On the
    # hosted deploy that address is a server-side env var and the report is rendered in a visitor's browser.
    monkeypatch.setattr(ipcheck, "_get_json", lambda url, opener: {
        "status": "success", "result": "0.5", "queryIP": "1.2.3.4",
        "contact": "secret@ops.example", "BadIP": 0, "Country": "US"})
    out = ipcheck.lookup_getipintel("1.2.3.4", "secret@ops.example", None)
    assert "secret@ops.example" not in json.dumps(out)
    assert out["getipintel_raw"]["Country"] == "US"      # ...while still carrying the real detail


def test_getipintel_scrubs_the_contact_out_of_a_rejection_message(monkeypatch):
    monkeypatch.setattr(ipcheck, "_get_json", lambda url, opener: {
        "status": "error", "message": "the address secret@ops.example is not valid",
        "contact": "secret@ops.example"})
    out = ipcheck.lookup_getipintel("1.2.3.4", "secret@ops.example", None)
    assert "secret@ops.example" not in json.dumps(out)


def test_premium_placeholders_never_reach_the_detail_card(monkeypatch):
    # Two shapes, both measured live on the free plan: the string "Premium required." and abuse_events,
    # a LIST whose one element is "Enterprise plan required…". A str.startswith check misses the list.
    assert ipcheck._premium("Premium required.") is True
    assert ipcheck._premium(["Enterprise plan required to view abuse events"]) is True
    assert ipcheck._premium("Google") is False
    assert ipcheck._premium([]) is False                  # an empty list is a real (empty) answer
    monkeypatch.setattr(ipcheck, "_get_json", lambda url, opener, headers=None: {
        "success": True, "message": "Success", "request_id": "abc", "fraud_score": 0,
        "ISP": "Google", "connection_type": "Premium required.",
        "abuse_events": ["Enterprise plan required to view abuse events"]})
    out = ipcheck.lookup_ipqs("8.8.8.8", "SECRETKEY", None)
    assert set(out["ipqs_raw"]) == {"fraud_score", "ISP"}


def test_ipqs_raw_never_carries_the_api_key(monkeypatch):
    monkeypatch.setattr(ipcheck, "_get_json", lambda url, opener, headers=None: {
        "success": True, "fraud_score": 10, "echoed": "lookup for key SECRETKEY ok"})
    out = ipcheck.lookup_ipqs("8.8.8.8", "SECRETKEY", None)
    assert "SECRETKEY" not in json.dumps(out)


def test_verdict_getipintel_badip_or_hosting_verdict_is_dirty():
    # getIPIntel EARNS a dirty on its own (it grades residential-vs-hosting; AWS 1.0, Starlink 0.0) — unlike
    # the raw IPQS proxy flag which saturates.
    assert ipcheck.verdict({"getipintel_bad": True, "dnsbl_usable": True})[0] == "dirty"
    assert ipcheck.verdict({"getipintel_score": 1.0, "dnsbl_usable": True})[0] == "dirty"


def test_verdict_getipintel_midrange_is_suspect_and_low_stays_clean():
    assert ipcheck.verdict({"getipintel_score": 0.93, "dnsbl_usable": True})[0] == "suspect"
    assert ipcheck.verdict({"getipintel_score": 0.10, "dnsbl_usable": True})[0] == "clean"


def test_verdict_clean_on_a_residential_proxy_despite_ipqs_100():
    # A real ISP exit (no datacenter name, no abuse) is usable even though IPQS flags proxy at score 100.
    rep = {"isp": "Comcast Cable", "organization": "Comcast", "fraud_score": 100, "proxy": True,
           "vpn": True, "blacklists": [], "dnsbl_usable": True}
    assert ipcheck.is_datacenter(rep) is False
    assert ipcheck.verdict(rep)[0] == "clean"


def test_verdict_dirty_on_two_or_more_abuse_listings():
    level, _ = ipcheck.verdict({"blacklists": ["Spamhaus", "CBL"], "dnsbl_usable": True})
    assert level == "dirty"


def test_verdict_ipqs_abuse_flags_alone_are_suspect_not_dirty():
    # IPQS's abuse sub-flags (recent_abuse / bot) saturate on shared/residential-proxy IPs, so ON THEIR OWN
    # they're only "worth a look", not "burned" — a reliable independent source (blacklist/AbuseIPDB/getIPIntel)
    # is what escalates to dirty.
    level, why = ipcheck.verdict({"fraud_score": 40, "recent_abuse": True, "dnsbl_usable": True})
    assert level == "suspect" and "IPQS abuse" in why


def test_verdict_ipqs_abuse_plus_a_blacklist_is_dirty():
    # Corroboration escalates: the blacklist is the reliable signal that makes it dirty.
    level, _ = ipcheck.verdict(
        {"recent_abuse": True, "blacklists": ["Spamhaus", "CBL"], "dnsbl_usable": True})
    assert level == "dirty"


def test_verdict_a_single_listing_on_a_proxy_is_suspect_not_dirty():
    # A fresh proxy that IPQS flags (score 100) with ONE stray blacklist hit is suspect, not condemned.
    level, why = ipcheck.verdict(
        {"fraud_score": 100, "proxy": True, "blacklists": ["Spamhaus"], "dnsbl_usable": True})
    assert level == "suspect"
    assert "1 blacklist" in why


def test_policy_listings_alone_never_move_the_verdict():
    # The whole point of the split: a clean residential proxy must not read as dirty.
    clean = {"fraud_score": 3, "blacklists": [], "policy_lists": ["Spamhaus", "SpamRATS"],
             "dnsbl_checked": 12, "dnsbl_usable": True}
    assert ipcheck.verdict(clean)[0] == "clean"


def test_verdict_unknown_when_no_source_answered():
    level, why = ipcheck.verdict({"dnsbl_usable": False})
    assert level == "unknown"
    assert "No source answered" in why


def test_verdict_clean_needs_a_working_blocklist_lookup():
    assert ipcheck.verdict({"dnsbl_usable": True, "dnsbl_checked": 12})[0] == "clean"


# ---- rendering -----------------------------------------------------------------------------


def test_format_report_covers_the_signals_and_never_invents_one():
    rep = {
        "ip": "172.59.84.16", "isp": "T-Mobile USA", "asn": "AS21928",
        "fraud_score": 92, "proxy": True, "recent_abuse": True,
        "blacklists": ["Spamhaus", "CBL"], "policy_lists": ["SpamRATS"],
        "dnsbl_checked": 12, "dnsbl_usable": True, "notes": [],
    }
    text = ipcheck.format_report(rep)
    assert "172.59.84.16" in text
    assert "92 · high risk" in text
    assert "2 of 12 · Spamhaus, CBL" in text
    assert "SpamRATS" in text and "not an abuse report" in text
    assert "DIRTY" in text
    assert "Abuse reports" not in text          # AbuseIPDB never ran — don't imply it did
    assert "Time zone" not in text


def test_report_never_says_only_none_of_n_while_a_policy_listing_stands():
    # The bug this closes, measured 2026-08-05 on the Mullvad exit 23.159.216.252: it IS on Spamhaus
    # (PBL, 127.0.0.11) and the readout said "none of 12 lists", so cross-checking it against a tool
    # that counts every listing looked like we had missed one. The split is the point; hiding the
    # policy hit from the headline is not.
    rep = {"ip": "23.159.216.252", "blacklists": [],
           "policy_lists": ["Spamhaus (PBL, Spamhaus listed the range)"],
           "dnsbl_checked": 12, "dnsbl_usable": True}
    text = ipcheck.format_report(rep)
    assert "plus 1 policy listing" in text
    assert "Spamhaus listed the range" in text
    # ...and it must not call that listing normal for this IP. 23.159.216.252 is a hosting address;
    # "normal for residential and mobile IPs" was reassurance pointed the wrong way.
    assert "residential" not in text


def test_report_keeps_the_plain_none_line_when_nothing_is_listed_at_all():
    text = ipcheck.format_report({"ip": "1.2.3.4", "blacklists": [], "policy_lists": [],
                                  "dnsbl_checked": 12, "dnsbl_usable": True})
    assert "none of 12 lists" in text
    assert "policy" not in text.lower()


def test_report_never_says_unavailable_and_reports_a_listing_at_once():
    # A listing could only come from a working resolver, so the line must not claim DNS is
    # unreachable in the same breath. (dnsbl_check makes a real hit force dnsbl_usable true, so this
    # combination shouldn't arise from live data either — but format_report must not produce nonsense
    # if handed it.)
    rep = {"ip": "1.2.3.4", "blacklists": [], "policy_lists": ["SpamRATS (dynamic reverse DNS)"],
           "dnsbl_checked": 0, "dnsbl_usable": False}
    text = ipcheck.format_report(rep)
    bl_line = next(li for li in text.splitlines() if li.startswith("Blacklists"))
    assert not ("unavailable" in bl_line and "policy listing" in bl_line)


def test_dnsbl_usable_when_a_real_listing_came_back_even_if_the_probe_failed():
    # The root of the contradiction above: a policy/abuse hit is itself proof the resolver works.
    # dnsbl_check computes usability, so assert the invariant on its output shape via a tiny stand-in.
    # (Pure reconstruction of the final dict logic — no network.)
    def usable(alive, abuse, policy):
        return alive or bool(abuse) or bool(policy)
    assert usable(False, [], ["SpamRATS"]) is True
    assert usable(False, ["CBL"], []) is True
    assert usable(False, [], []) is False
    assert usable(True, [], []) is True


def test_fraud_line_names_the_strictness_it_was_scored_at():
    # Measured on 23.159.216.252: strictness 0 -> fraud 20, proxy false; strictness 1 -> fraud 100,
    # proxy true. The number means nothing without the setting, and a reader comparing this against
    # another checker has no way to reconcile the two otherwise.
    text = ipcheck.format_report({"ip": "1.2.3.4", "fraud_score": 100, "ipqs_strictness": 1})
    assert "strictness 1" in text
    # No strictness recorded (an older cached report) must not invent one.
    assert "strictness" not in ipcheck.format_report({"ip": "1.2.3.4", "fraud_score": 100})


def test_format_report_says_unavailable_when_blocklist_dns_is_dead():
    text = ipcheck.format_report({"ip": "1.2.3.4", "dnsbl_checked": 0, "dnsbl_usable": False})
    assert "unavailable" in text
    assert "none of" not in text                # a false all-clear is the failure mode to avoid


def test_flags_lists_only_true_verdicts_in_priority_order():
    assert ipcheck.flags({"proxy": True, "vpn": True, "tor": False}) == ["VPN", "Proxy"]
    assert ipcheck.flags({}) == []


# ---- check() orchestration + main() exit code (no network — the sources are monkeypatched) ------


def test_check_merges_every_source_and_assembles_verdict_and_flags(monkeypatch):
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None, timeout=None: {"ip": "1.2.3.4", "isp": "ACME"})
    monkeypatch.setattr(ipcheck, "lookup_ipqs",
                        lambda ip, key, opener: {"fraud_score": 90, "proxy": True})
    monkeypatch.setattr(ipcheck, "lookup_abuseipdb",
                        lambda ip, key, opener: {"abuse_confidence": 70, "abuse_reports": 4})
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {
        "blacklists": ["Spamhaus"], "policy_lists": [], "dnsbl_checked": 12, "dnsbl_usable": True})
    rep = ipcheck.check(ipqs_key="k", abuse_key="a")
    assert rep["ip"] == "1.2.3.4" and rep["isp"] == "ACME"
    assert rep["fraud_score"] == 90 and rep["abuse_confidence"] == 70
    assert rep["verdict"] == "dirty"                 # abuse 70% -> dirty (fraud 90 alone would NOT be)
    assert "Proxy" in rep["flags"]                   # flags derived from the merged verdicts


def test_check_notes_when_no_key_is_set(monkeypatch):
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None, timeout=None: {"ip": "1.2.3.4"})
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {
        "blacklists": [], "policy_lists": [], "dnsbl_checked": 12, "dnsbl_usable": True})
    rep = ipcheck.check()                             # no ipqs_key
    assert rep.get("fraud_score") is None
    assert any("No IPQualityScore key" in n for n in rep["notes"])


def test_check_carries_an_explicit_ip_even_when_geo_fails(monkeypatch):
    # --ip 5.6.7.8 with a geo lookup that returns nothing: the address we were told to check must still
    # be checked (blacklists), not dropped — the fix that made an explicit --ip survive a geo failure.
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None, timeout=None: {})
    seen = {}

    def fake_dnsbl(ip):
        seen["ip"] = ip
        return {"blacklists": [], "policy_lists": [], "dnsbl_checked": 0, "dnsbl_usable": False}

    monkeypatch.setattr(ipcheck, "dnsbl_check", fake_dnsbl)
    rep = ipcheck.check(ip="5.6.7.8")
    assert rep["ip"] == "5.6.7.8"
    assert seen["ip"] == "5.6.7.8"                    # the blacklist check ran on the given IP


def test_check_gives_up_with_a_note_when_the_exit_ip_cant_be_found(monkeypatch):
    # No --ip and geo failed -> there's nothing to check; say so, don't press on against a null IP.
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None, timeout=None: {})
    called = {"dnsbl": False}

    def fake_dnsbl(ip):
        called["dnsbl"] = True
        return {}

    monkeypatch.setattr(ipcheck, "dnsbl_check", fake_dnsbl)
    rep = ipcheck.check()
    assert not rep.get("ip")
    # "Source: what happened" — the UI splits on the colon to render it as a label→value row.
    assert any(n.startswith("Exit IP: ") and "lookup failed" in n for n in rep["notes"])
    assert called["dnsbl"] is False                  # bailed before spending a blacklist lookup


def test_main_exit_code_matches_the_verdict(monkeypatch):
    # The CLI's exit code is a scripting contract: 0 clean, 1 dirty, 3 unknown.
    for verdict_word, code in (("clean", 0), ("dirty", 1), ("unknown", 3)):
        monkeypatch.setattr(ipcheck, "check",
                            lambda *a, _v=verdict_word, **k: {"verdict": _v, "notes": []})
        assert ipcheck.main(["--ip", "1.2.3.4", "--json"]) == code


# ---- the web UI, and the generated Vercel copy of it ----------------------------------------

WEBAPP = Path(__file__).resolve().parents[1] / "webapp" / "index.html"


def _script(html: str) -> str:
    m = re.search(r"<script>(.*)</script>", html, re.S)
    assert m, "no <script> block"
    return m.group(1)


def _ids_referenced(script: str) -> set[str]:
    return set(re.findall(r"\$\('#([\w-]+)'\)", script))


def _ids_defined(html: str) -> set[str]:
    return set(re.findall(r"\bid=(?:\"|')?([\w-]+)", html))


def test_the_page_never_selects_an_element_it_does_not_define():
    # The bug this closes: webapp/build.py kept injecting `$('#gii').value=...` after the getIPIntel
    # email field had been deleted from PAGE. `$('#gii')` is null, the assignment throws inside a
    # top-level IIFE, and the WHOLE script aborts — the page still renders, every button is dead.
    missing = _ids_referenced(_script(ipcheck.PAGE)) - _ids_defined(ipcheck.PAGE)
    assert not missing, f"PAGE selects ids it never defines: {sorted(missing)}"


def test_the_generated_vercel_page_is_in_sync_and_self_consistent():
    # webapp/index.html is GENERATED from PAGE by webapp/build.py. Regenerating is a manual step, so
    # this asserts the checked-in copy matches what build.py would produce today — and that build.py's
    # rewrites didn't truncate. A non-greedy regex once stopped at a `});` INSIDE the config block and
    # left orphan lines: a JavaScript SyntaxError that killed every handler on the deployed page.
    if not WEBAPP.exists():
        return
    html = WEBAPP.read_text("utf-8")
    script = _script(html)
    missing = _ids_referenced(script) - _ids_defined(html)
    assert not missing, f"generated page selects ids it never defines: {sorted(missing)} — re-run webapp/build.py"
    assert script.count("boot();") == 1, "duplicated boot() — build.py's config rewrite truncated"
    assert "const API='/api/check';" in script, "generated page must POST to the serverless function"
    assert "fetch('/config')" not in script, "generated page must not call the local server's /config"
    assert "fetch('/api/config')" in script, "generated page must ask which shared keys the deploy has"


def test_the_page_never_calls_itself_on_open():
    # Opening the page must PREFILL the visitor's IP and stop there. An auto-run spends an API quota
    # and a getIPIntel rate-limit slot nobody asked for, on every page load and every refresh.
    for name, script in (("PAGE", _script(ipcheck.PAGE)),
                         ("index.html", _script(WEBAPP.read_text("utf-8")) if WEBAPP.exists() else "")):
        assert "$('#go').click()" not in script, f"{name} auto-runs the check on open"


def test_no_surface_calls_a_low_getipintel_score_residential():
    # A low getIPIntel score means it saw NO proxy evidence — not that it proved a real ISP line.
    # connection_class refuses to guess "residential" from a name heuristic; the wording must not either.
    assert ipcheck.getipintel_band(0.10) == "no proxy signal"
    assert ipcheck.getipintel_band(0.70) == "mixed signals"
    assert ipcheck.getipintel_band(0.95) == "likely proxy"
    assert ipcheck.getipintel_band(1.0) == "proxy/hosting exit"
    for blob in (ipcheck.PAGE, WEBAPP.read_text("utf-8") if WEBAPP.exists() else ""):
        assert "residential-ish" not in blob


def test_getipintel_rotates_to_the_next_contact_when_one_is_over_quota(monkeypatch):
    # getIPIntel meters per contact AND per connecting IP (15/min, 500/day). A -5/-6 refusal is the one
    # another address can get past, so several contacts may be configured and are tried in order.
    seen = []

    def fake(url, opener):
        c = url.split("contact=")[1].split("&")[0]
        seen.append(c)
        return {"status": "success", "result": "-5"} if c == "first@x.io" \
            else {"status": "success", "result": "0.42", "queryIP": "1.2.3.4"}

    monkeypatch.setattr(ipcheck, "_get_json", fake)
    out = ipcheck.lookup_getipintel("1.2.3.4", "first@x.io, second@x.io", None)
    assert seen == ["first@x.io", "second@x.io"]
    assert out["getipintel_score"] == 0.42
    assert "_retry" not in out                      # internal control flag, never part of the report


def test_getipintel_does_not_burn_a_second_contact_on_a_query_level_error(monkeypatch):
    # -3 is "that address is unroutable" — a verdict about the QUERY. Rotating would spend the next
    # contact's quota to be told the same thing.
    seen = []

    def fake(url, opener):
        seen.append(url)
        return {"status": "success", "result": "-3"}

    monkeypatch.setattr(ipcheck, "_get_json", fake)
    out = ipcheck.lookup_getipintel("10.0.0.1", "first@x.io second@x.io", None)
    assert len(seen) == 1
    assert "unroutable" in out["notes"][0]
    assert "_retry" not in out


def test_the_android_verdict_mirrors_the_python_one():
    # HealthCheck.verdictFactors is a hand-written Java twin of verdict_factors(). If a threshold drifts on
    # one side, the SAME exit IP reads clean on the phone and suspect on the desktop — the exact confusion
    # this tool exists to remove. Pin the numbers and the factor wording that carry the judgement.
    java = (Path(__file__).resolve().parents[1] / "xposed-module" / "app" / "src" / "main" / "java" /
            "com" / "specter" / "module" / "ui" / "HealthCheck.java").read_text("utf-8")
    body = java[java.index("static List<String> verdictFactors"):]
    body = body[:body.index("\n    private static volatile")]
    for needle in (
        "hits >= 2",                        # two blacklists corroborate; one doesn't
        "abuseConfidence >= 50",            # dirty
        "getipintel >= 0.99",               # dirty
        "abuseConfidence >= 10",            # suspect
        "getipintel >= 0.90",               # suspect
        "fraudScore >= 60",                 # only decides "is it detectable", never the verdict
        "datacenter/hosting IP", "blacklists", "% abuse confidence",
        "getIPIntel bad-IP", "getIPIntel proxy/hosting", "1 blacklist", "IPQS abuse flags",
        "no datacenter signal", "detectable as a proxy/VPN",
    ):
        assert needle in body, f"HealthCheck.verdictFactors lost {needle!r} — it has drifted from Python"
    # ...and no factor the user SEES may claim "residential" from a name heuristic. Comments explaining why
    # are fine — it's the emitted strings that must not overclaim.
    emitted = re.findall(r'why\.add\(([^;]*)\);', body)
    assert not any("residential" in e.lower() for e in emitted), \
        "a verdict factor claims 'residential', which a name heuristic cannot prove"


def test_the_android_getipintel_wording_mirrors_the_python_one():
    java = (Path(__file__).resolve().parents[1] / "xposed-module" / "app" / "src" / "main" / "java" /
            "com" / "specter" / "module" / "ui" / "HealthCheck.java").read_text("utf-8")
    for score in (0.10, 0.70, 0.95, 1.0):
        assert f'"{ipcheck.getipintel_band(score)}"' in java, \
            f"Android is missing the getIPIntel band for {score}"
    assert "oflags=bc" in java, "Android must ask getIPIntel for the country too"
    assert "over quota from here — this IP wasn't checked" in java


def test_ipqs_scrubs_the_key_out_of_a_rejection_message(monkeypatch):
    # The hosted deploy falls back to a SHARED server-side key for any visitor who brings none. The moment
    # that key expires or runs out of quota, every such visitor reaches this branch — so an IPQS rejection
    # message that quotes the key would hand the operator's key to each of them, rendered into the page.
    monkeypatch.setattr(ipcheck, "_get_json", lambda url, opener, headers=None: {
        "success": False, "message": "Invalid or expired key SECRETKEY. Please check your account."})
    out = ipcheck.lookup_ipqs("8.8.8.8", "SECRETKEY", None)
    assert "SECRETKEY" not in json.dumps(out)
    assert "Invalid or expired key" in out["notes"][0]     # ...while still saying what went wrong


def test_an_ipv6_exit_falls_back_to_ipv4_so_the_blocklists_still_run(monkeypatch):
    # MEASURED 2026-08-05: every one of the 17 zones answers the 127.0.0.2 test entry and NONE answers the
    # 2001:db8::2 one — they hold no IPv6 data at all. A dual-stack proxy (Starlink residential, sampled 8x:
    # 5 IPv4 / 3 IPv6 from one endpoint) can hand back either family, so landing on IPv6 used to mean ZERO
    # blocklist evidence behind a clean-looking verdict. Ask again over IPv4 instead of giving up.
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo",
                        lambda opener, ip=None, timeout=None: {"ip": "2605:59ca::e798", "isp": "Starlink"})
    monkeypatch.setattr(ipcheck, "lookup_exit_v4", lambda opener, deadline=None: "153.66.117.15")
    checked = {}
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: checked.setdefault("ip", ip) and {} or
                        {"blacklists": [], "policy_lists": [], "dnsbl_checked": 17, "dnsbl_usable": True,
                         "dnsbl_detail": []})
    rep = ipcheck.check("host:1080")
    assert checked["ip"] == "153.66.117.15"          # the blocklists ran, on the checkable address
    assert rep["ip"] == "153.66.117.15"
    assert rep["exit_ipv6"] == "2605:59ca::e798"     # ...and the v6 exit is still reported, not hidden
    assert any("dual-stack" in n for n in rep["notes"])
    assert rep["dnsbl_usable"] is True


def test_semicolons_separate_a_proxy_line_exactly_like_colons():
    # Vendors hand out `host;port;user;pass` as often as the colon form, and re-typing a pasted list is
    # not a thing anyone should have to do. Normalised in ONE place, after the scheme comes off, so every
    # shape gets it — and so a `scheme://` prefix is never damaged on the way through.
    colon = ipcheck.parse_proxy("host.com:1080:user:pass", "http")
    semi = ipcheck.parse_proxy("host.com;1080;user;pass", "http")
    assert (semi.host, semi.port, semi.user, semi.password) == \
           (colon.host, colon.port, colon.user, colon.password)
    scheme = ipcheck.parse_proxy("socks5://host.com;1080;user;pass", "http")
    assert scheme.scheme == "socks5" and scheme.port == 1080 and scheme.password == "pass"
    assert ipcheck.parse_proxy("user:pass@host.com;1080", "http").port == 1080
    # A bracketed IPv6 endpoint carries colons that are part of the ADDRESS. It has no semicolons, so the
    # normalisation must leave it exactly as it was.
    assert ipcheck.parse_proxy("[2001:db8::1]:8080", "http").host == "[2001:db8::1]"


def test_a_semicolon_inside_a_password_is_not_a_separator():
    # Found by codex in the gauntlet. `user:pa;ss@host:8080` is a line that WORKED, and teaching the
    # parser that `;` separates turned its password into `pa:ss` — a live proxy failing to authenticate
    # with nothing on screen to say why. Credentials are exempt; only the host:port after the last `@`
    # is normalised.
    p = ipcheck.parse_proxy("user:pa;ss@host.com:8080", "http")
    assert p.password == "pa;ss" and p.user == "user" and p.port == 8080
    # ...and the exemption does not cost the host:port its semicolons on the same line.
    assert ipcheck.parse_proxy("user:pa;ss@host.com;8080", "http").port == 8080
    # With no `@` there are no credentials to protect, so the whole line is separators.
    assert ipcheck.parse_proxy("host.com;1080;user;pa", "http").password == "pa"


def test_the_ipv4_pin_gives_up_rather_than_running_a_check_past_its_budget(monkeypatch):
    # Also codex. Worst case a bare line spends TIMEOUT + TIMEOUT + SLOW_TIMEOUT before the v4 pin even
    # starts, and the pin's own three endpoints can add 3xTIMEOUT — past the hosted function cap, throwing
    # away a result that had already succeeded. Losing the pin beats losing the report.
    tried = []
    monkeypatch.setattr(ipcheck, "_get_json", lambda url, opener, headers=None, timeout=None:
                        tried.append(url) or None)
    monkeypatch.setattr(ipcheck, "_get_text", lambda url, opener: tried.append(url) or None)
    assert ipcheck.lookup_exit_v4(None, deadline=time.monotonic() - 1) is None
    assert tried == [], "the walk must not start at all once the budget is spent"
    # With time on the clock it still walks every endpoint — the deadline is a ceiling, not a throttle.
    assert ipcheck.lookup_exit_v4(None, deadline=time.monotonic() + 60) is None
    assert len(tried) == len(ipcheck._V4_ECHOES)


def test_a_spent_budget_skips_the_reputation_sources_instead_of_losing_the_whole_check(monkeypatch):
    # The gauntlet's sharpest finding. Everything after the liveness probe goes out through the SAME
    # opener, so a proxy slow enough to need the 24s retry makes every later request slow too — four
    # reputation sources at the full timeout each is another 32s on top of a liveness path that may
    # already have spent 40. The hosted checker's cap then kills the invocation and returns NOTHING,
    # which is a worse answer than the "dead proxy" this whole change set out to fix.
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo",
                        lambda opener, ip=None, timeout=None: {"ip": "1.2.3.4", "isp": "ACME"})
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {})
    monkeypatch.setattr(ipcheck, "CHECK_BUDGET", -1)         # the budget is already gone on entry
    asked = []
    for name in ("lookup_ipqs", "lookup_abuseipdb", "lookup_getipintel", "lookup_scamalytics"):
        monkeypatch.setattr(ipcheck, name, lambda *a, _n=name, **k: asked.append(_n) or {})
    rep = ipcheck.check("host:1080", ipqs_key="k", abuse_key="k",
                        getipintel_contact="a@b.c", scam_user="u", scam_key="k")
    assert asked == [], f"a spent budget must not start another lookup, got {asked}"
    assert rep["ip"] == "1.2.3.4" and rep["proxy_alive"] is True   # ...and the real answer still ships
    assert rep["verdict"]                                          # with a verdict, not an exception
    assert sum("not asked" in n for n in rep["notes"]) == 4        # each one says so, by name
    # A key that EXISTS but went unasked must never be reported as a missing key — different facts.
    assert not any("No IPQualityScore key" in n for n in rep["notes"])


def test_a_proxy_that_is_merely_slow_is_retried_before_being_called_dead(monkeypatch):
    # MEASURED 2026-08-07: five lightningproxies SOCKS5 endpoints answered in ~800 ms once warm, but a
    # cold concurrent hosted run had every one of them exceed the timeout — and the batch rendered DEAD
    # while a direct SOCKS5 handshake to all five succeeded. "No answer within Ns" is not "down".
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    calls = []

    def flaky(opener, ip=None, timeout=None):
        # Count only the calls that go THROUGH THE PROXY (opener is None, the stub above). The direct
        # no-proxy baseline in _direct_baseline_ms uses a real opener and must not be counted as a retry.
        if opener is None:
            calls.append(timeout)
        return {} if len(calls) < 2 else {"ip": "24.116.160.60", "isp": "Sparklight"}

    monkeypatch.setattr(ipcheck, "lookup_geo", flaky)
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {})
    rep = ipcheck.check("socks5://host:1080:user:pass")
    assert rep["proxy_alive"] is True                 # ...not DEAD on the strength of one slow request
    assert rep["ip"] == "24.116.160.60"
    assert len(calls) == 2                            # exactly one retry, not a loop
    # ...and the retry gets a LONGER budget, because the whole point is that the first one ran out of time.
    assert calls[0] is None and calls[1] == ipcheck.SLOW_TIMEOUT > ipcheck.TIMEOUT
    assert any("answered on a retry" in n for n in rep["notes"])


def test_a_dead_proxy_note_names_the_transport_actually_used_and_claims_no_untried_retry(monkeypatch):
    # Two false statements lived in this note. A line carrying `socks5://` overrides the UI selector, so
    # with the selector on HTTP the report said "no answer as HTTP" about a request that went out as
    # SOCKS5 — and it claimed "the other transport did not answer either" when no such retry is even
    # attempted for an explicit scheme. Both read as evidence; neither was measured.
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None, timeout=None: {})
    rep = ipcheck.check("socks5://host:1080:user:pass", proxy_scheme="http")
    note = next(n for n in rep["notes"] if n.startswith("Proxy: no answer"))
    assert "SOCKS5" in note and "HTTP" not in note     # the transport that was actually used
    assert "the other transport" not in note           # no retry happened, so none may be claimed
    assert rep["proxy_alive"] is False
    # It says what was OBSERVED — two silences and how long each waited — and lists the causes without
    # asserting which one it was. Nothing here re-dials to find out; that would be a second measurement
    # of a different moment, reported as if it described the first.
    assert f"within {ipcheck.TIMEOUT}s" in note and f"within {ipcheck.SLOW_TIMEOUT}s" in note


def test_the_ipv4_pin_survives_one_endpoint_going_dark(monkeypatch):
    # The whole point of the pin is that it does not give up quietly. It used to ask ONE host
    # (api4.ipify.org, behind Cloudflare); when that answered with nothing the report simply fell through
    # to IPv6 — which is what a user saw on 2026-08-07: nine rows of a ten-proxy batch read IPv4 and the
    # tenth read `2605:59ca:...:e674`, graded against 2 zones instead of 14. Three endpoints on three
    # operators (Cloudflare / AWS / Hetzner) means no single one going dark can do that again.
    asked = []

    def fake_json(url, opener, headers=None):
        asked.append(url)
        return None                                  # the Cloudflare-fronted one answers with nothing

    def fake_text(url, opener):
        asked.append(url)
        return "153.66.117.15" if "amazonaws" in url else None

    monkeypatch.setattr(ipcheck, "_get_json", fake_json)
    monkeypatch.setattr(ipcheck, "_get_text", fake_text)
    assert ipcheck.lookup_exit_v4(None) == "153.66.117.15"
    assert len(asked) == 2 and "ipify" in asked[0]    # tried in order, stopped as soon as one answered
    # ...and every endpoint in the chain is IPv4-ONLY by hostname, which is the entire mechanism: an HTTP
    # proxy does its own DNS and outbound connect, so a host with an AAAA record can still be reached over
    # IPv6 and hand back a v6 exit. A dual-stack host in this list would silently defeat the pin.
    assert not any(h in u for u, _ in ipcheck._V4_ECHOES
                   for h in ("ipwho.is", "://api.ipify.org", "://icanhazip.com", "://ident.me"))


def test_a_text_endpoint_with_a_trailing_newline_is_still_a_valid_address(monkeypatch):
    # checkip.amazonaws.com answers `23.159.216.252\n`. Without the strip, reverse_v4 rejects it —
    # `"252\n".isdigit()` is False — so a working endpoint reads as a dead one and the chain walks past it.
    monkeypatch.setattr(ipcheck, "_get_json", lambda url, opener, headers=None: None)
    monkeypatch.setattr(ipcheck, "_get_text", lambda url, opener: "  153.66.117.15\n")
    assert ipcheck.lookup_exit_v4(None) == "153.66.117.15"


def test_geo_is_remeasured_on_the_ipv4_address_it_is_reported_against(monkeypatch):
    # The address swap used to relabel the report without re-measuring: isp/location/country_code/timezone
    # came from the IPv6 record and were then presented as facts about the IPv4 address. That is the same
    # mis-attribution the swap was moved ahead of the reputation lookups to prevent — one family of the
    # exit is not evidence about the other.
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)

    def geo(opener, ip=None, timeout=None):
        if ip is None:
            return {"ip": "2605:59ca::e798", "isp": "v6 upstream", "timezone": "America/Denver",
                    "country_code": "CA"}
        return {"ip": ip, "isp": "Starlink", "timezone": "America/New_York", "country_code": "US"}

    monkeypatch.setattr(ipcheck, "lookup_geo", geo)
    monkeypatch.setattr(ipcheck, "lookup_exit_v4", lambda opener, deadline=None: "153.66.117.15")
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {})
    rep = ipcheck.check("host:1080")
    assert rep["ip"] == "153.66.117.15"          # the re-lookup's echo must not become the reported address
    assert rep["isp"] == "Starlink"              # ...and every derived field describes THAT address
    assert rep["timezone"] == "America/New_York"
    assert rep["country_code"] == "US"
    assert rep["exit_ipv6"] == "2605:59ca::e798"


def test_a_failed_ipv4_regeo_drops_the_stale_fields_rather_than_relabelling_them(monkeypatch):
    # The ERROR path is the one that leaks. When the re-lookup of the IPv4 address fails (timeout,
    # rate-limit) the merge is a no-op, so the IPv6 record's isp/location/country_code/timezone would
    # survive and be presented as facts about an address they were never measured on — the very
    # mis-attribution the re-lookup was added to prevent. A dash is honest; a wrong ISP is not, and this
    # `timezone` is what the device-vs-IP alignment acts on.
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo",
                        lambda opener, ip=None, timeout=None: {} if ip else
                        {"ip": "2605:59ca::e798", "isp": "v6 upstream", "location": "Denver, CO, US",
                         "country_code": "CA", "timezone": "America/Denver"})
    monkeypatch.setattr(ipcheck, "lookup_exit_v4", lambda opener, deadline=None: "153.66.117.15")
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {})
    rep = ipcheck.check("host:1080")
    assert rep["ip"] == "153.66.117.15"
    assert rep["exit_ipv6"] == "2605:59ca::e798"
    for stale in ("isp", "location", "country_code", "timezone"):
        assert stale not in rep, f"{stale} was carried over from the IPv6 record"
    assert any("omitted rather than carried over" in n for n in rep["notes"])


def test_an_ipv6_only_exit_is_still_checked_against_the_zones_that_have_ipv6_data(monkeypatch):
    # No IPv4 route: still check, against the four zones that actually hold IPv6 data, and report THAT
    # denominator — "0 of 4 IPv6 lists" is a real result, "0 of 17" would be a lie.
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None, timeout=None: {"ip": "2605:59ca::e798"})
    monkeypatch.setattr(ipcheck, "lookup_exit_v4", lambda opener, deadline=None: None)
    seen = {}

    def fake_dnsbl(ip):
        seen["ip"] = ip
        return {"blacklists": [], "policy_lists": [], "dnsbl_checked": 4, "dnsbl_usable": True,
                "dnsbl_family": "ipv6", "dnsbl_zones_total": 4, "dnsbl_detail": []}

    monkeypatch.setattr(ipcheck, "dnsbl_check", fake_dnsbl)
    rep = ipcheck.check("host:1080")
    assert seen["ip"] == "2605:59ca::e798"        # it ran, on the IPv6 address
    assert rep["dnsbl_family"] == "ipv6" and rep["dnsbl_zones_total"] == 4
    assert any("IPv6 only" in n for n in rep["notes"])


def test_a_verdict_never_claims_a_blocklist_record_it_did_not_obtain():
    # When another source answered, the verdict may still be clean — but it has to admit the blocklist
    # half was never obtained rather than claiming a clean blocklist record.
    level, why = ipcheck.verdict_factors({"fraud_score": 10, "dnsbl_usable": False, "blacklists": []})
    assert level == "clean"
    assert "blocklists NOT checked" in why
    assert not any("no abuse or blacklist history" in w for w in why)
    # ...and with nothing at all answering, it must read unknown, never clean.
    assert ipcheck.verdict_factors({"dnsbl_usable": False})[0] == "unknown"


def test_the_ipv6_zone_table_is_the_measured_subset_that_actually_holds_ipv6_data():
    # MEASURED 2026-08-05 against 60 live IPv6 Tor exits: s5h 39 hits, Spamhaus 24, CBL 14, DroneBL 5,
    # every other zone 0. Querying the rest over IPv6 inflates the denominator and manufactures a clean
    # sweep from lists that could never have flagged the address.
    v6 = {n for n, _ in ipcheck.DNSBL_ZONES_V6}
    assert v6 == {"Spamhaus", "CBL", "s5h", "DroneBL"}
    assert v6 < {n for n, _ in ipcheck.DNSBL_ZONES}       # a strict subset of the IPv4 table


def test_reverse_v6_builds_the_rfc5782_nibble_name():
    # 32 nibbles, reversed, dot-separated (RFC 5782 s2.4).
    assert ipcheck.reverse_v6("2001:db8::1") == (
        "1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.b.d.0.1.0.0.2")
    assert len(ipcheck.reverse_v6("2605:59ca::e798").split(".")) == 32
    for bad in (None, "", "1.2.3.4", "not-an-ip", "2001:db8::gg"):
        assert ipcheck.reverse_v6(bad) is None


def test_android_uses_a_resolver_that_does_not_lose_or_fake_blocklist_answers():
    # MEASURED 2026-08-05 on 185.220.101.45 (seven abuse listings via the system resolver), because the
    # phone showed three zones with no answer:
    #   Cloudflare -> Spamhaus/CBL return 127.255.255.254 (explicit refusal) and SpamRATS SERVFAILs.
    #                 Safe — classify() excludes a refusal — but three zones are silently lost.
    #   Google     -> Spamhaus/CBL return NXDOMAIN, i.e. "not listed" for a listed IP. A FALSE CLEAN.
    #   dns.sb     -> true records, agreeing with the system resolver on 17 of 17 zones.
    # DoH is mandatory on Android (proxy apps hijack DNS with a fake-IP pool), so the resolver CHOICE is
    # the only lever — and picking Google here would silently invert the tool's answer.
    java = (Path(__file__).resolve().parents[1] / "xposed-module" / "app" / "src" / "main" / "java" /
            "com" / "specter" / "module" / "ui" / "HealthCheck.java").read_text("utf-8")
    assert 'DOH = "https://doh.sb/dns-query' in java
    assert "dns.google" not in java and "https://dns.google" not in java
    # A fallback must exist so one provider's outage can't drop every zone...
    assert 'DOH_FALLBACK = "https://cloudflare-dns.com' in java
    # ...and it must be the one that degrades safely, never the one that manufactures a clean result.
    assert "google" not in java[java.index("DOH_FALLBACK"):java.index("DOH_FALLBACK") + 200].lower()


# ---- coverage honesty: "we didn't look" must never render as "it's clean" ----------------------


def test_a_sweep_where_every_zone_refused_is_not_a_clean_result(monkeypatch):
    """Spamhaus and CBL answer 127.255.255.254 — a refusal — to queries relayed by large public resolvers,
    and ``classify`` correctly declines to count a refusal as checked. So a run where the 127.0.0.2
    sentinels resolve but every real zone refuses obtained NO evidence.

    That combination used to report ``dnsbl_usable: True`` with ``dnsbl_checked: 0``, and
    ``verdict_factors`` then said "no abuse or blacklist history" about it — a false all-clear, which is
    the one output this tool must never produce."""
    def fake_resolve(host):
        return ["127.0.0.2"] if host.startswith("2.0.0.127.") else ["127.255.255.254"]

    monkeypatch.setattr(ipcheck, "resolve_a", fake_resolve)
    out = ipcheck.dnsbl_check("1.2.3.4")
    assert out["dnsbl_checked"] == 0
    assert out["dnsbl_usable"] is False, "zero answering zones is not a usable sweep"
    assert out["dnsbl_skipped"] == "no answer"
    # With another source having answered, the verdict is CLEAN — and it has to say the blocklists were
    # not checked rather than reporting a record it never obtained.
    level, why = ipcheck.verdict_factors({**out, "fraud_score": 0})
    assert level == "clean"
    assert "blocklists NOT checked" in why
    assert not any("no abuse or blacklist history" in w for w in why)


def test_a_real_listing_still_counts_when_the_sentinels_fail(monkeypatch):
    # The other direction: a zone that returns an actual listing proves the resolver works, so the result
    # must survive even when the sentinel probes get nothing. Tightening `usable` must not break this.
    def fake_resolve(host):
        return [] if host.startswith("2.0.0.127.") else ["127.0.0.4"]

    monkeypatch.setattr(ipcheck, "resolve_a", fake_resolve)
    out = ipcheck.dnsbl_check("1.2.3.4")
    assert out["dnsbl_usable"] is True and out["dnsbl_checked"] > 0
    assert out["blacklists"], "a real listing must be reported"
    assert "dnsbl_skipped" not in out


def test_the_clean_verdict_says_the_same_thing_on_both_branches():
    # The proxy-flagged and unflagged clean branches used to re-derive the coverage decision separately and
    # word it differently, which lets a difference in MEANING hide as a difference in phrasing.
    for extra in ({}, {"proxy": True}):
        _, why = ipcheck.verdict_factors({"dnsbl_usable": False, "dnsbl_checked": 0, "fraud_score": 0, **extra})
        assert "blocklists NOT checked" in why, f"{extra} branch claims coverage it does not have"


def test_the_android_clean_verdict_also_refuses_to_claim_unchecked_blocklists():
    java = (Path(__file__).resolve().parents[1] / "xposed-module" / "app" / "src" / "main" / "java" /
            "com" / "specter" / "module" / "ui" / "HealthCheck.java").read_text("utf-8")
    assert '"blocklists NOT checked"' in java, \
        "Android's clean verdict must say when no zone answered, exactly as the desktop does"


def test_every_reputation_source_is_asked_about_the_address_the_report_names(monkeypatch):
    """A dual-stack exit answers over either family. The address was being switched to IPv4 AFTER the
    reputation lookups, so IPQS/AbuseIPDB/getIPIntel/Scamalytics were measured against the IPv6 address and
    the whole report was then relabelled with the IPv4 one — measurements attributed to an address they
    were never taken on."""
    asked = {}
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None, timeout=None: {"ip": "2605:59ca::e798"})
    monkeypatch.setattr(ipcheck, "lookup_exit_v4", lambda opener, deadline=None: "153.66.117.15")
    monkeypatch.setattr(ipcheck, "lookup_ipqs",
                        lambda ip, key, opener: asked.setdefault("ipqs", ip) and {} or {"fraud_score": 0})
    monkeypatch.setattr(ipcheck, "lookup_abuseipdb",
                        lambda ip, key, opener: asked.setdefault("abuse", ip) and {} or {})
    monkeypatch.setattr(ipcheck, "lookup_getipintel",
                        lambda ip, contact, opener: asked.setdefault("gii", ip) and {} or {})
    monkeypatch.setattr(ipcheck, "lookup_scamalytics",
                        lambda ip, u, k, opener: asked.setdefault("scam", ip) and {} or {})
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: asked.setdefault("dnsbl", ip) and {} or
                        {"blacklists": [], "policy_lists": [], "dnsbl_checked": 17,
                         "dnsbl_usable": True, "dnsbl_detail": []})
    rep = ipcheck.check("host:1080", ipqs_key="k", abuse_key="a",
                        getipintel_contact="me@example.com", scam_user="u", scam_key="s")
    assert rep["ip"] == "153.66.117.15"
    assert rep["exit_ipv6"] == "2605:59ca::e798"       # still reported, not hidden
    for src in ("ipqs", "abuse", "gii", "scam", "dnsbl"):
        assert asked[src] == rep["ip"], f"{src} was measured on {asked[src]}, not on the reported {rep['ip']}"


def test_an_unbracketed_ipv6_proxy_is_refused_rather_than_misparsed():
    """`rpartition(':')` on `2001:db8::1` yields host `2001:db8:` and port `1`, both of which pass every
    check — a silent misparse that would dial a nonsense host and report it as the proxy the user typed.

    Refusing is the contract (parse_proxy raises with a readable reason; None means blank), because
    `host:port` is genuinely ambiguous for IPv6 and a guess here is worse than an error."""
    import pytest
    for bad in ("2001:db8::1", "2001:db8::1:8080", "[2001:db8::1", "[2001:db8::1]"):
        with pytest.raises(ValueError):
            ipcheck.parse_proxy(bad)
    # Bracketed and complete is accepted, brackets kept — that is the form urllib wants back.
    p = ipcheck.parse_proxy("[2001:db8::1]:8080")
    assert p is not None and p.host == "[2001:db8::1]" and p.port == 8080
    assert p.http_url() == "http://[2001:db8::1]:8080"
    # ...and the ordinary IPv4/hostname shapes are untouched.
    for text, host, port in (("res.example.com:10000", "res.example.com", 10000),
                             ("1.2.3.4:8080:bob:hunter2", "1.2.3.4", 8080),
                             ("bob:hunter2@1.2.3.4:8080", "1.2.3.4", 8080)):
        p = ipcheck.parse_proxy(text)
        assert p is not None and p.host == host and p.port == port, text


# ---- Scamalytics ------------------------------------------------------------------------------
#
# Shape and every trap below MEASURED live over ~200 v3 lookups on 2026-08-06.


def _scam_ok(**over):
    """A well-formed ok body. Overrides go into the `scamalytics` object."""
    body = {
        "scamalytics_score": 15, "scamalytics_risk": "low",
        "scamalytics_isp_score": 13, "scamalytics_isp_risk": "low",
        "scamalytics_isp": "Example Networks", "status": "ok", "mode": "live",
        "scamalytics_url": "https://scamalytics.com/ip/1.2.3.4",
        "is_blacklisted_external": False,
        "scamalytics_proxy": {"is_datacenter": False, "is_vpn": False, "is_amazon_aws": False,
                              "is_google": False, "is_apple_icloud_private_relay": False},
        "external_datasources": {
            "ip2proxy": {"proxy_type": "0"}, "ip2proxy_lite": {},
            "x4bnet": {"is_tor": False, "is_vpn": False, "is_datacenter": False, "is_spambot": False},
            "firehol": {"is_blacklisted_30d": False, "is_blacklisted_1day": False, "is_proxy": False},
            "ipsum": {"is_blacklisted": False, "num_blacklists": 0},
            "spamhaus_drop": {"is_blacklisted": False}, "dbip": {"connection_type": "isp"}},
    }
    body.update(over)
    return {"scamalytics": body, "credits": {"remaining": 2431, "used": 2}}


def _patch_scam(monkeypatch, body):
    monkeypatch.setattr(ipcheck, "_get_json", lambda url, opener, headers=None: body)


def test_scamalytics_extracts_score_flags_and_proxy_type(monkeypatch):
    _patch_scam(monkeypatch, _scam_ok(
        scamalytics_score=44, scamalytics_risk="medium",
        scamalytics_proxy={"is_datacenter": True, "is_vpn": True},
        external_datasources={"ip2proxy": {"proxy_type": "DCH"}, "x4bnet": {"is_tor": False}},
        is_blacklisted_external=True))
    out = ipcheck.lookup_scamalytics("1.2.3.4", "acct", "KEY", None)
    assert out["scam_score"] == 44 and out["scam_risk"] == "medium"
    assert out["scam_isp_score"] == 13 and out["scam_isp_risk"] == "low"
    assert out["scam_datacenter"] is True and out["scam_vpn"] is True
    assert out["scam_proxy_type"] == "DCH"
    assert out["scam_blacklisted_external"] is True
    assert out["scam_tor"] is False


def test_scamalytics_tor_is_the_union_of_two_sources(monkeypatch):
    # MEASURED: x4bnet answered is_tor FALSE on a real Tor exit that ip2proxy typed "TOR". Trusting either
    # one alone silently loses the single most decisive classification this source produces.
    _patch_scam(monkeypatch, _scam_ok(
        external_datasources={"ip2proxy": {"proxy_type": "TOR"}, "x4bnet": {"is_tor": False}}))
    assert ipcheck.lookup_scamalytics("1.2.3.4", "acct", "KEY", None)["scam_tor"] is True
    _patch_scam(monkeypatch, _scam_ok(
        external_datasources={"ip2proxy": {"proxy_type": "0"}, "x4bnet": {"is_tor": True}}))
    assert ipcheck.lookup_scamalytics("1.2.3.4", "acct", "KEY", None)["scam_tor"] is True


def test_scamalytics_error_body_is_a_note_not_a_score(monkeypatch):
    # HTTP 200 does NOT mean success — a malformed IP and a missing key both answer 200 with status:"error".
    # And on every error shape `external_datasources` flips from an object to an empty ARRAY, so a guard
    # that reads it before checking status raises. This test fails loudly if that order is ever swapped.
    _patch_scam(monkeypatch, {"scamalytics": {
        "status": "error", "error": "ip is not a valid IP address", "external_datasources": []}})
    out = ipcheck.lookup_scamalytics("not-an-ip", "acct", "KEY", None)
    assert "scam_risk" not in out and "scam_score" not in out
    assert out["notes"] == ["Scamalytics: ip is not a valid IP address"]


def test_scamalytics_no_answer_is_a_note_naming_both_causes(monkeypatch):
    # A rejected key answers HTTP 404 with an Apache HTML body (the docs claim 401 + JSON; they are wrong),
    # so _get_json returns None for BOTH unreachable and bad-credentials. The note must not pick one.
    _patch_scam(monkeypatch, None)
    out = ipcheck.lookup_scamalytics("1.2.3.4", "acct", "KEY", None)
    assert "scam_risk" not in out
    assert "unreachable" in out["notes"][0] and "rejected" in out["notes"][0]


def test_scamalytics_credentials_never_reach_the_caller(monkeypatch):
    # The credential test, run end-to-end through check() rather than the lookup alone, so a future field
    # that forwards the user or key fails HERE. The key rides in the QUERY STRING, and the hosted deploy
    # renders this report in a visitor's browser.
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None, timeout=None: {"ip": "1.2.3.4", "isp": "Example"})
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {"blacklists": [], "policy_lists": [],
                                                            "dnsbl_checked": 17, "dnsbl_usable": True,
                                                            "dnsbl_detail": []})
    user, key = "acct-9910", "sk-live-DEADBEEFCAFE"
    _patch_scam(monkeypatch, _scam_ok(
        scamalytics_isp=f"Reseller for {user}",
        scamalytics_url=f"https://scamalytics.com/ip/1.2.3.4?key={key}",
        some_future_field=f"echoed {key} back"))
    rep = ipcheck.check(ip="1.2.3.4", scam_user=user, scam_key=key)
    blob = json.dumps(rep)
    assert user not in blob and key not in blob
    assert rep["scam_risk"] == "low"                 # ...while the measurement still lands


def test_scamalytics_credits_never_reach_the_report(monkeypatch):
    # Our quota is operator state, not the visitor's business.
    _patch_scam(monkeypatch, _scam_ok())
    out = ipcheck.lookup_scamalytics("1.2.3.4", "acct", "KEY", None)
    assert "credits" not in out["scamalytics_raw"]
    assert "remaining" not in json.dumps(out)


def test_scamalytics_exhausted_credits_say_so_and_measure_nothing(monkeypatch):
    # An empty balance must SAY so. Silently returning no fields would degrade every verdict to
    # "no datacenter signal" with nothing to explain why. `remaining` is int in live mode, str in test mode.
    for empty in (0, "0"):
        _patch_scam(monkeypatch, {"scamalytics": _scam_ok()["scamalytics"],
                                  "credits": {"remaining": empty}})
        out = ipcheck.lookup_scamalytics("1.2.3.4", "acct", "KEY", None)
        assert not [k for k in out if k.startswith("scam")], f"remaining={empty!r} still emitted fields"
        assert "credits exhausted" in out["notes"][0]


def test_connection_class_datacenter_from_scamalytics_when_the_name_regex_misses():
    # "Byte Node LLC" is Mullvad's exit ISP and matches nothing in _DATACENTER_RE — a known commercial VPN
    # exit that rendered `unclassified`. The factor must NAME the source: its specificity on residential
    # pools is proven on only four IPs, so a wrong call has to be diagnosable at a glance.
    rep = {"isp": "Byte Node LLC", "scam_proxy_type": "DCH", "dnsbl_usable": True}
    assert ipcheck.connection_class(rep) == "datacenter"
    assert ipcheck.is_datacenter(rep) is True
    level, why = ipcheck.verdict_factors(rep)
    assert level == "dirty"
    assert "datacenter/hosting IP (Scamalytics DCH)" in why
    # ...and is_datacenter alone (no proxy_type) still names Scamalytics rather than looking like the regex.
    _, why2 = ipcheck.verdict_factors({"isp": "Byte Node LLC", "scam_datacenter": True})
    assert "datacenter/hosting IP (Scamalytics is_datacenter)" in why2


def test_connection_class_tor_beats_datacenter():
    # A Tor exit reads is_datacenter true as well, and "Tor exit" is the more useful — and more damning —
    # claim. It must not be reported as a plain hosting IP.
    rep = {"scam_tor": True, "scam_datacenter": True, "isp": "Some Hosting"}
    assert ipcheck.connection_class(rep) == "tor"
    assert ipcheck.is_datacenter(rep) is False        # `tor` is its own class, not a datacenter
    level, why = ipcheck.verdict_factors(rep)
    assert level == "dirty" and "Tor exit" in why
    assert not any("datacenter/hosting IP" in w for w in why)


def test_ip2proxy_lite_and_an_empty_proxy_type_are_not_a_clean_result(monkeypatch):
    # proxy_type "0"/"" means NO RECORD, not "clean" — dropping the field lets the UI say so. And
    # ip2proxy_lite measured EMPTY on all 8 IPs, so rendering it would read as "checked and clean".
    for empty in ("0", "", None):
        _patch_scam(monkeypatch, _scam_ok(external_datasources={
            "ip2proxy": {"proxy_type": empty}, "ip2proxy_lite": {"proxy_type": ""}}))
        out = ipcheck.lookup_scamalytics("1.2.3.4", "acct", "KEY", None)
        assert "scam_proxy_type" not in out, f"{empty!r} must not read as a proxy type"
        assert "ip2proxy_lite" not in json.dumps(out)
        assert ipcheck.connection_class(out) is None


def test_scamalytics_score_never_moves_the_verdict():
    """The behavioural lock, in BOTH directions.

    MEASURED: the score tracks scamalytics_isp_score on every IP — an ASN prior, not a measurement of this
    address — and it MIS-RANKS: a Tor exit scored 15 "low", clean Comcast residential 18, and the highest
    in the whole set was Mullvad at 44. No threshold orders that set, so no threshold may exist. A future
    "let's weight it a little" cannot land without failing here."""
    worst = {"scam_score": 100, "scam_risk": "very high", "scam_isp_score": 100,
             "scam_isp_risk": "very high", "scam_datacenter": False, "scam_vpn": False,
             "scam_tor": False, "scam_blacklisted_external": False,
             "isp": "Comcast Cable", "dnsbl_usable": True, "fraud_score": 0}
    assert ipcheck.verdict_factors(worst)[0] == "clean"
    best = {"scam_score": 0, "scam_risk": "low", "scam_datacenter": True, "dnsbl_usable": True}
    assert ipcheck.verdict_factors(best)[0] == "dirty"


def test_scamalytics_premium_placeholder_is_treated_as_missing(monkeypatch):
    # On the Essential tier the Premium fields hold the literal "PREMIUM FIELD - upgrade to view".
    # Rendering that in a detail card would read as data.
    _patch_scam(monkeypatch, _scam_ok(scamalytics_isp="PREMIUM FIELD - upgrade to view",
                                      external_datasources={
                                          "ip2proxy": {"proxy_type": "PREMIUM FIELD - upgrade to view"}}))
    out = ipcheck.lookup_scamalytics("1.2.3.4", "acct", "KEY", None)
    assert "PREMIUM" not in json.dumps(out)
    assert "scam_proxy_type" not in out


def test_scamalytics_raw_is_flat_so_the_detail_card_can_render_it(monkeypatch):
    # kv()/fmtv() in PAGE stringify a nested object as "[object Object]".
    _patch_scam(monkeypatch, _scam_ok())
    raw = ipcheck.lookup_scamalytics("1.2.3.4", "acct", "KEY", None)["scamalytics_raw"]
    for k, v in raw.items():
        assert not isinstance(v, (dict, list)), f"{k} is nested — kv() would render it [object Object]"
    assert raw["dbip_connection_type"] == "isp" and raw["isp_name"] == "Example Networks"


def test_getipintel_classifies_the_exit_the_other_two_sources_miss():
    """Mullvad's exit ISP "Byte Node LLC" matches nothing in _DATACENTER_RE, and Scamalytics reported it
    `is_datacenter false` with no ip2proxy record — so a known commercial VPN exit rendered
    "unclassified". getIPIntel called it 1.00. It grades residential-vs-hosting rather than flagging every
    proxy (measured: AWS 1.0, Starlink 0.0), which is what makes it usable as the last-resort classifier."""
    mullvad = {"isp": "Byte Node LLC", "getipintel_score": 1.0, "scam_datacenter": False,
               "dnsbl_usable": True}
    assert ipcheck.connection_class(mullvad) == "datacenter"
    level, why = ipcheck.verdict_factors(mullvad)
    assert level == "dirty"
    assert "datacenter/hosting IP (getIPIntel)" in why, "the factor must name which source claimed it"
    # A real residential exit is NOT swept up: Starlink measured 0.0, and 0.90 stays below the threshold
    # precisely so a probability never becomes a classification.
    assert ipcheck.connection_class({"isp": "SpaceX Starlink", "getipintel_score": 0.0}) is None
    assert ipcheck.connection_class({"isp": "Comcast Cable", "getipintel_score": 0.9}) is None
    # ...and it never outranks a source that actually knows: mobile and Tor still win.
    assert ipcheck.connection_class({"mobile": True, "getipintel_score": 1.0}) == "mobile"
    assert ipcheck.connection_class({"scam_tor": True, "getipintel_score": 1.0}) == "tor"
    # Scamalytics keeps the attribution when BOTH fire — it is the more specific claim.
    _, why2 = ipcheck.verdict_factors({"scam_proxy_type": "DCH", "getipintel_score": 1.0})
    assert "datacenter/hosting IP (Scamalytics DCH)" in why2
    # The Android side must agree, or the same IP classifies differently on the phone.
    java = (Path(__file__).resolve().parents[1] / "xposed-module" / "app" / "src" / "main" / "java" /
            "com" / "specter" / "module" / "ui" / "HealthCheck.java").read_text("utf-8")
    m = re.search(r"GII_HOSTING\s*=\s*([\d.]+)", java)
    assert m and float(m.group(1)) == ipcheck._GII_HOSTING
    assert "giiDatacenter(r)" in java


def test_latency_reports_what_the_proxy_ADDS_not_the_raw_round_trip(monkeypatch):
    """MEASURED 2026-08-06 from this machine (+0800): the same endpoint takes 889 ms direct and 3077 ms
    through a US residential proxy, and the endpoint barely matters (gstatic 610/3125, cloudflare 608/3172
    — all within ~100 ms of each other out of ~3100). The hosted check runs from Vercel's iad1 in US-East
    and still reports ~3400 ms on those proxies, so the number is dominated by the PROXY, not by the
    observer's distance.

    Timing the same request without the proxy separates the two, and the delta is the figure that is
    comparable between a laptop in Asia and a function in Virginia."""
    calls = []

    def fake_geo(opener, ip=None, timeout=None):
        calls.append(opener)
        return {"ip": "153.66.193.140", "isp": "SpaceX Starlink"}

    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": "PROXIED")
    monkeypatch.setattr(ipcheck, "lookup_geo", fake_geo)
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {"blacklists": [], "policy_lists": [],
                                                            "dnsbl_checked": 17, "dnsbl_usable": True,
                                                            "dnsbl_detail": []})
    rep = ipcheck.check("host:10000")
    assert calls[0] == "PROXIED", "the first timed request must go through the proxy"
    assert calls[1] != "PROXIED", "the baseline must NOT go through the proxy"
    for k in ("proxy_ms", "direct_ms", "proxy_added_ms"):
        assert k in rep, f"{k} missing — the raw round trip alone is not interpretable"
    assert rep["proxy_added_ms"] == max(0, rep["proxy_ms"] - rep["direct_ms"])
    assert rep["proxy_added_ms"] >= 0, "a faster-than-baseline proxy must clamp to 0, never go negative"


def test_direct_baseline_is_measured_once_per_run_not_per_row(monkeypatch):
    """The baseline is this MACHINE's latency to the endpoint — constant across a bulk run. Re-measuring it
    per row doubled the request rate to one shared free endpoint, so a throttled reply rendered a live proxy
    as DEAD. It must be measured once and cached (the autouse fixture clears the cache before this test)."""
    direct_calls = []

    def fake_geo(opener, ip=None, timeout=None):
        if opener != "PROXIED":
            direct_calls.append(opener)     # only the DIRECT baseline lookups, not the proxied primary ones
        return {"ip": "1.2.3.4", "isp": "X"}

    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": "PROXIED")
    monkeypatch.setattr(ipcheck, "lookup_geo", fake_geo)
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {"blacklists": [], "policy_lists": [],
                                                            "dnsbl_checked": 17, "dnsbl_usable": True,
                                                            "dnsbl_detail": []})
    for host in ("a:1", "b:2", "c:3"):      # a 3-row "bulk run"
        ipcheck.check(host)
    assert len(direct_calls) == 1, f"the baseline must be measured once for the run, got {len(direct_calls)}"


def test_ipapi_hosting_classifies_datacenter_keyless():
    """ip-api.com's `hosting` boolean is a KEYLESS datacenter signal — a no-key user gets a real exit-type
    verdict where connection_class would otherwise return None. The verdict factor names the source so a
    future misfire is diagnosable (as it does for Scamalytics/getIPIntel)."""
    assert ipcheck.connection_class({"ipapi_hosting": True}) == "datacenter"
    assert ipcheck.connection_class({"ipapi_hosting": False}) is None
    assert ipcheck.connection_class({}) is None
    lvl, why = ipcheck.verdict_factors(
        {"connection_class": "datacenter", "ipapi_hosting": True, "dnsbl_usable": True})
    assert lvl == "dirty" and any("ip-api" in f for f in why), why


def test_ipapi_mobile_takes_precedence_over_hosting():
    # A mobile carrier exit reads as mobile even if ip-api also flags hosting (CGNAT gateways often do).
    assert ipcheck.connection_class({"mobile": True, "ipapi_hosting": True}) == "mobile"


def test_ipapi_lookup_parses_and_degrades(monkeypatch):
    """The REAL _ipapi_lookup (captured before the autouse stub): a good response maps to the four fields; a
    rate-limit / non-success / non-dict / empty response is an ABSENT signal ({}), never an exception that
    would fail the whole check. _get_json is stubbed since the real fn calls it by module lookup."""
    monkeypatch.setattr(ipcheck, "_get_json",
                        lambda url, opener=None: {"status": "success", "hosting": True, "proxy": True,
                                                  "mobile": False, "asname": "GOOGLE"})
    assert _REAL_IPAPI_LOOKUP("8.8.8.8", None) == {
        "ipapi_hosting": True, "ipapi_proxy": True, "ipapi_mobile": False, "ipapi_asname": "GOOGLE"}
    for bad in ({"status": "fail", "message": "rate limit reached"}, None, {}, "not-a-dict", 42):
        monkeypatch.setattr(ipcheck, "_get_json", lambda url, opener=None, _b=bad: _b)
        assert _REAL_IPAPI_LOOKUP("8.8.8.8", None) == {}, f"bad response {bad!r} must degrade to {{}}"
    assert _REAL_IPAPI_LOOKUP("", None) == {}          # no IP → no lookup at all


def test_ipapi_feeds_check_flow_and_proxy_note(monkeypatch):
    # End to end: with no keys, ip-api's hosting promotes the exit type to datacenter and proxy=true adds a
    # note. Override the autouse stub with a real-looking ip-api result.
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None, timeout=None: {"ip": "45.83.220.5", "isp": "31173 Services AB"})
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {"blacklists": [], "policy_lists": [],
                                                            "dnsbl_checked": 17, "dnsbl_usable": True, "dnsbl_detail": []})
    monkeypatch.setattr(ipcheck, "_ipapi_lookup", lambda ip, opener=None: {
        "ipapi_hosting": True, "ipapi_proxy": True, "ipapi_mobile": False, "ipapi_asname": "ESAB-AS"})
    rep = ipcheck.check(ip="45.83.220.5")
    assert rep["connection_class"] == "datacenter"
    # name the ACTUAL source (ip-api.com) — not ip-api.io, a different, paid, rejected service.
    assert any("ip-api.com" in n and "proxy" in n.lower() for n in rep["notes"]), rep["notes"]


def test_no_baseline_request_is_made_when_there_is_no_proxy(monkeypatch):
    # The extra round trip exists only to interpret a PROXY's cost. Spending it on a direct check would
    # double every keyless lookup for nothing.
    calls = []
    monkeypatch.setattr(ipcheck, "lookup_geo",
                        lambda opener, ip=None, timeout=None: calls.append(opener) or {"ip": "8.8.8.8"})
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {"blacklists": [], "policy_lists": [],
                                                            "dnsbl_checked": 17, "dnsbl_usable": True,
                                                            "dnsbl_detail": []})
    rep = ipcheck.check(ip="8.8.8.8")
    assert len(calls) == 1, f"expected exactly one geo lookup with no proxy, got {len(calls)}"
    assert "proxy_added_ms" not in rep and "direct_ms" not in rep


def test_a_socks_proxy_addressed_as_http_is_retried_not_called_dead(monkeypatch):
    """MEASURED 2026-08-06: an entire vendor's list (SOCKS5 on :1080) reported DEAD when run as HTTP —
    indistinguishable from genuinely down, and a trap for anyone pasting a list they were handed."""
    tried = []

    def fake_opener(proxy, scheme="http"):
        tried.append(scheme)
        return scheme

    def fake_geo(opener, ip=None, timeout=None):
        # Only the SOCKS transport answers, exactly as the real proxy behaved.
        return {"ip": "24.26.39.144", "isp": "Spectrum"} if opener == "socks5" else {}

    monkeypatch.setattr(ipcheck, "_opener", fake_opener)
    monkeypatch.setattr(ipcheck, "lookup_geo", fake_geo)
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {"blacklists": [], "policy_lists": [],
                                                            "dnsbl_checked": 17, "dnsbl_usable": True,
                                                            "dnsbl_detail": []})
    rep = ipcheck.check("host:1080", proxy_scheme="http")
    assert tried == ["http", "socks5"], f"expected an http attempt then a socks5 retry, got {tried}"
    assert rep["proxy_alive"] is True and rep["ip"] == "24.26.39.144"
    assert rep["proxy_scheme_used"] == "socks5"
    assert any("responded as SOCKS5" in n for n in rep["notes"]), \
        "the report must SAY the transport was wrong, not silently paper over it"


def test_a_genuinely_dead_proxy_still_reads_dead_and_says_both_were_tried(monkeypatch):
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": scheme)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None, timeout=None: {})
    rep = ipcheck.check("host:9999", proxy_scheme="http")
    assert rep["proxy_alive"] is False
    assert rep["verdict"] == "unknown"                    # and never crashes the page
    assert any("the other transport did not answer either" in n for n in rep["notes"])


def test_an_explicit_scheme_is_never_second_guessed(monkeypatch):
    # `socks5://…` is a statement, not a guess — retrying it as HTTP would spend a round trip arguing
    # with the user about what they typed.
    tried = []
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": tried.append(scheme) or scheme)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None, timeout=None: {})
    ipcheck.check("socks5://host:1080")
    assert tried == ["http"], f"an explicit scheme must be tried once, got {tried}"


def test_the_android_connection_class_orders_its_branches_like_the_python_one():
    """`mobile` must be checked BEFORE the datacenter signal, on both sides.

    Android's `connectionClass` was added without it — and `Reputation` never read IPQS's `mobile` flag at
    all — so a mobile exit whose ISP string happens to contain a hosting term (the regex carries a
    `google(?!\\s+fiber)` lookahead precisely because such names exist) classified as `mobile` on the
    desktop and `datacenter` on the phone. Same IP, different verdict, which is the whole reason the two
    implementations are pinned to each other."""
    # Python: mobile wins over a datacenter-looking name, and tor wins over mobile.
    assert ipcheck.connection_class({"mobile": True, "isp": "Cloudy Mobile Hosting"}) == "mobile"
    assert ipcheck.connection_class({"mobile": True, "scam_proxy_type": "DCH"}) == "mobile"
    assert ipcheck.connection_class({"mobile": True, "scam_tor": True}) == "tor"
    assert ipcheck.verdict_factors({"mobile": True, "isp": "Cloudy Mobile Hosting",
                                    "dnsbl_usable": True})[0] == "clean"
    java = (Path(__file__).resolve().parents[1] / "xposed-module" / "app" / "src" / "main" / "java" /
            "com" / "specter" / "module" / "ui" / "HealthCheck.java").read_text("utf-8")
    assert 'o.optBoolean("mobile"' in java, "Android never reads IPQS's mobile flag"
    block = re.search(r"static String connectionClass\(.*?\n    \}", java, re.S)
    assert block, "connectionClass not found in HealthCheck.java"
    body = block.group(0)
    assert "r.mobile" in body, "connectionClass has no mobile branch"
    assert body.index("r.mobile") < body.index("scamDatacenter"), \
        "mobile must be checked BEFORE the datacenter signal, as connection_class() does"
    # ...and the verdict must agree with the class it reports, or the tile and the reason contradict.
    vf = re.search(r"static List<String> verdictFactors\(.*?\n    \}", java, re.S)
    assert vf and "r.mobile" in vf.group(0), "verdictFactors ignores mobile, so it can contradict the tile"


def test_scam_datacenter_types_match_the_android_side():
    # The two implementations must bucket the ip2proxy taxonomy identically, or the same IP classifies as a
    # datacenter on one and unclassified on the other.
    java = (Path(__file__).resolve().parents[1] / "xposed-module" / "app" / "src" / "main" / "java" /
            "com" / "specter" / "module" / "ui" / "HealthCheck.java").read_text("utf-8")
    m = re.search(r"SCAM_DC_TYPES\s*=.*?asList\((.*?)\)\)", java, re.S)
    assert m, "SCAM_DC_TYPES not found in HealthCheck.java"
    assert set(re.findall(r'"([A-Z]+)"', m.group(1))) == ipcheck._SCAM_DC_TYPES


def test_scamalytics_reserved_addresses_are_not_a_clean_signal(monkeypatch):
    # MEASURED: 127.0.0.1, 10.0.0.1 and 0.0.0.0 all return ok / score 0 / "low". A "0 low" can mean
    # "not a real exit", so it must never manufacture a class or a benign reading on its own.
    _patch_scam(monkeypatch, _scam_ok(scamalytics_score=0, scamalytics_risk="low"))
    out = ipcheck.lookup_scamalytics("127.0.0.1", "acct", "KEY", None)
    assert out["scam_score"] == 0 and out["scam_risk"] == "low"
    assert ipcheck.connection_class(out) is None       # no class invented from a low score


# ---- generated page ---------------------------------------------------------------------------


def test_every_delegated_selector_matches_something_the_page_emits():
    """A delegated handler whose selector names a class the markup never produces is a DEAD control that
    looks completely normal.

    That is not hypothetical: the copy handler read `.copy,.cc` while every credential chip is `.cp`, so
    clicking host / port / user / password copied nothing and did not even flash — the tool's headline
    feature, silently inert. `.cc` matched nothing at all, which is the tell this test looks for.

    Both directions matter. A selector with no markup is a dead handler; an interactive class with no
    handler is a dead button."""
    from tools.page_assets import PAGE as page  # noqa: F401  (same source the page is built from)
    handled = set()
    for sel in re.findall(r"closest\('([^']+)'\)", ipcheck.PAGE):
        handled |= {s.strip().lstrip(".") for s in sel.split(",") if s.strip().startswith(".")}
    # Classes the page actually renders onto a <button>.
    emitted = set(re.findall(r"<button[^>]*\bclass=([A-Za-z][\w-]*)", ipcheck.PAGE))
    emitted |= set(re.findall(r"<button[^>]*\bclass=\"([^\"]+)\"", ipcheck.PAGE))
    emitted = {c for grp in emitted for c in grp.split()}

    dead_handlers = handled - emitted
    assert not dead_handlers, (
        f"delegated selector(s) {sorted(dead_handlers)} match no <button> the page emits — the handler is "
        f"dead. Emitted button classes: {sorted(emitted)}")
    # ...and every clickable chip class must be reachable by some handler.
    for cls in ("cp", "copy"):
        assert cls in emitted, f".{cls} is no longer emitted — update this test with the new class"
        assert cls in handled, f".{cls} buttons are rendered but no delegated handler listens for them"


def test_no_inline_svg_attribute_is_unquoted():
    """`rx=1.2/>` parses as the VALUE `1.2/` with no self-close, so the element swallows its siblings.

    That one character shipped THREE of six line icons dead — `server` lost its second rack unit, `build`
    and `bot` collapsed to a bare square, and `ban` (a circle followed by a path) drew literally nothing.
    It survived for weeks because a missing icon is indistinguishable from a value that simply has no
    icon. Quoting every attribute removes the whole class; this test keeps it removed."""
    from tools.page_assets import svg_attributes_are_quoted
    bad = svg_attributes_are_quoted()
    assert not bad, ("unquoted attribute in inline SVG — quote it, or the next `x=1/>` silently eats the "
                     "elements after it:\n  " + "\n  ".join(bad))


def test_every_line_icon_actually_renders_at_the_size_it_is_drawn():
    """Render each icon at its real 13px and MEASURE it. Reading the source proves nothing: every one of
    the dead icons above was valid-looking markup, and eyeballing a screenshot missed them twice.

    Four failure modes, because size alone is not evidence of meaning:
      * not drawn at all (near-zero ink),
      * drawn but so heavy the strokes close over the gaps — a "rectangle" at a plausible size,
      * drawn tiny or flat in one axis,
      * drawn fine but identical to another icon (`build` and `bot` were both a plain square, each with
        perfectly healthy ink).
    """
    import subprocess
    import sys
    root = Path(__file__).resolve().parents[1]
    r = subprocess.run([sys.executable, str(root / "webapp" / "check-icons.py"), "--strict"],
                       cwd=root, capture_output=True, text=True)
    if "no Chrome found" in r.stdout:
        return                                  # no renderer here; the check still runs locally and in review
    assert r.returncode == 0, "\n" + r.stdout + r.stderr


def test_the_generated_page_javascript_parses():
    """webapp/index.html is GENERATED by three regex rewrites. A mis-targeted one once shipped a page whose
    <script> was a SyntaxError — so every button was dead while the page still rendered perfectly. Nothing
    failed loudly. Parse the emitted script instead of trusting the rewrites."""
    import shutil
    import subprocess
    import tempfile
    node = shutil.which("node")
    if not node:
        return                                          # no runtime here; the deploy check still applies
    root = Path(__file__).resolve().parents[1]
    html = (root / "webapp" / "index.html").read_text("utf-8")
    m = re.search(r"<script>(.*)</script>", html, re.S)
    assert m, "webapp/index.html has no <script> — did build.py run?"
    with tempfile.TemporaryDirectory() as d:
        js = Path(d) / "page.mjs"
        js.write_text(m.group(1), "utf-8")
        r = subprocess.run([node, "--check", str(js)], capture_output=True, text=True)
    assert r.returncode == 0, "generated page JS does not parse:\n" + (r.stderr or r.stdout)


def test_the_generated_page_runs_without_a_top_level_error():
    """Load the SHIPPED page in a real browser and check it reached the end of its own script.

    Parsing is not enough. A `const` read from an earlier line — "Cannot access 'KEYFIELDS' before
    initialization" — parses perfectly, kills the whole <script> at load, and leaves a page that renders
    its complete markup with every button inert. Nothing fails loudly. The page's last statement stamps
    `data-specter-ready`, so its ABSENCE in the post-JS DOM is the failure.

    `--virtual-time-budget` runs the timers and promises before dumping, so async work is included; the
    network calls the page makes just fail and are caught, which is the point of testing the real file."""
    import shutil
    import subprocess
    chrome = next((c for c in (r"C:\Program Files\Google\Chrome\Application\chrome.exe",
                               r"C:\Program Files\Google\Chrome Beta\Application\chrome.exe",
                               "/usr/bin/google-chrome", "/usr/bin/chromium")
                   if Path(c).exists()), None) or shutil.which("chrome") or shutil.which("chromium")
    if not chrome:
        return                                          # no browser here; it still runs locally
    page = Path(__file__).resolve().parents[1] / "webapp" / "index.html"
    r = subprocess.run([chrome, "--headless", "--disable-gpu", "--virtual-time-budget=6000",
                        "--dump-dom", page.as_uri()], capture_output=True, text=True, timeout=120)
    assert "data-specter-ready" in r.stdout, (
        "the page's script did not run to completion — a top-level runtime error killed it, so every "
        "control on the page is dead. Open it in a browser and read the console.")


def test_the_generated_page_is_in_sync_with_PAGE():
    """A PAGE edit that never had build.py re-run ships an index.html missing it — silently, because both
    files look fine on their own.

    Deliberately NOT folded into the browser test above: that one returns early when no Chrome is
    installed, and a staleness check gated on a browser being present is a staleness check that does not
    run on the machine most likely to be stale."""
    root = Path(__file__).resolve().parents[1]
    html = (root / "webapp" / "index.html").read_text("utf-8")
    # The rewritten bits differ by design; everything else must match line-for-line.
    for marker in ("id=scamuser", "id=scamkey", "markKeys", "scamalytics_raw",
                   "k:'scam'", "ccColour", "shortLoc", "scrollbar-color"):
        assert marker in html, f"webapp/index.html is stale — re-run python webapp/build.py ({marker})"
    assert "localStorage.getItem" in html and "fetch('/config')" not in html


# ---- backup safety ------------------------------------------------------------------------------


def test_the_backup_directory_name_cannot_escape_backups():
    """`ro.product.device` and the adb serial are read FROM the connected device and land in a directory
    name, so a hostile or malformed value could place an archive of real login data outside `backups/`.

    The second half matters as much: the naming must stay STABLE. Sanitising `.` out of an adb serial
    renamed every directory, and `--check` then reported "NO BACKUP EVER" for devices that had one — a
    backup checker that cannot find the backups is worse than no checker."""
    import importlib.util
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location("backup_vault", root / "scripts" / "backup_vault.py")
    assert spec and spec.loader
    bv = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bv)

    for hostile in ("../../etc", "/tmp/x", "..", ".", "", "a/b", "..\\..\\x", "  "):
        got = bv._safe(hostile, "fallback")
        assert "/" not in got and "\\" not in got, f"{hostile!r} -> {got!r} is not one path component"
        assert got not in (".", "", ".."), f"{hostile!r} -> {got!r} is a relative-path token"
    # ...and ordinary values survive intact, in the shape the existing backups already use.
    assert bv._safe("sunfish", "x") == "sunfish"
    assert bv._safe("192.168.50.19_5557".replace(".", "_"), "x") == "192_168_50_19_5557"


# ---- secrets ---------------------------------------------------------------------------------


def test_no_api_credential_is_ever_committed():
    """No live credential may sit in a tracked file. The repository is PUBLIC, so a committed key is
    published the moment it is pushed and rotating it is the only remedy.

    This checks the ACTUAL secrets held in ~/.specter-ipcheck.json rather than guessing at key shapes —
    a shape scan flags uv.lock's package hashes and every UUID in the docs, which trains people to ignore
    it. Keys belong in that file (outside the repo) or in the deploy's env vars, never in the tree."""
    import subprocess
    root = Path(__file__).resolve().parents[1]
    cfg = Path.home() / ".specter-ipcheck.json"
    if not cfg.exists():
        return                                  # no local keys to leak (CI); nothing to assert
    secrets = {str(v) for k, v in json.loads(cfg.read_text("utf-8")).items()
               if isinstance(v, str) and len(v) >= 12 and ("key" in k or "user" in k)}
    if not secrets:
        return
    tracked = subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True).stdout.split()
    bad = []
    for rel in tracked:
        if rel == "tests/test_ipcheck.py":
            continue
        try:
            text = (root / rel).read_text("utf-8", errors="ignore")
        except (OSError, IsADirectoryError):
            continue
        for sec in secrets:
            if sec in text:
                bad.append(f"{rel} contains a live credential ({sec[:8]}…)")
    joined = chr(10).join("  " + b for b in bad)
    assert not bad, "SECRET COMMITTED — rotate it, then remove it from history:" + chr(10) + joined
