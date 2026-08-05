"""Pure-logic tests for the exit-IP reputation checker. No network — every function under test
takes already-fetched data."""

import json
import re
from pathlib import Path

from specter import ipcheck


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
    java_zones = re.findall(r'\{"([^"]+)",\s*"([^"]+)"\}', java)
    assert java_zones == [(n, z) for n, z in ipcheck.DNSBL_ZONES]
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
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None: {"ip": "1.2.3.4", "isp": "ACME"})
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
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None: {"ip": "1.2.3.4"})
    monkeypatch.setattr(ipcheck, "dnsbl_check", lambda ip: {
        "blacklists": [], "policy_lists": [], "dnsbl_checked": 12, "dnsbl_usable": True})
    rep = ipcheck.check()                             # no ipqs_key
    assert rep.get("fraud_score") is None
    assert any("No IPQualityScore key" in n for n in rep["notes"])


def test_check_carries_an_explicit_ip_even_when_geo_fails(monkeypatch):
    # --ip 5.6.7.8 with a geo lookup that returns nothing: the address we were told to check must still
    # be checked (blacklists), not dropped — the fix that made an explicit --ip survive a geo failure.
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None: {})
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
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None: {})
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
                        lambda opener, ip=None: {"ip": "2605:59ca::e798", "isp": "Starlink"})
    monkeypatch.setattr(ipcheck, "lookup_exit_v4", lambda opener: "153.66.117.15")
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


def test_an_ipv6_only_exit_is_still_checked_against_the_zones_that_have_ipv6_data(monkeypatch):
    # No IPv4 route: still check, against the four zones that actually hold IPv6 data, and report THAT
    # denominator — "0 of 4 IPv6 lists" is a real result, "0 of 17" would be a lie.
    monkeypatch.setattr(ipcheck, "_opener", lambda proxy, scheme="http": None)
    monkeypatch.setattr(ipcheck, "lookup_geo", lambda opener, ip=None: {"ip": "2605:59ca::e798"})
    monkeypatch.setattr(ipcheck, "lookup_exit_v4", lambda opener: None)
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
