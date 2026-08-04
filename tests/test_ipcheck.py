"""Pure-logic tests for the exit-IP reputation checker. No network — every function under test
takes already-fetched data."""

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
    # ...and so do the policy codes that keep residential IPs out of the abuse count.
    for zone, codes in ipcheck.POLICY_CODES.items():
        m = re.search(r'"' + re.escape(zone) + r'"\.equals\(zone\)\) return ([^;]+);', java)
        assert m, f"{zone} has no policy-code branch in Dnsbl.java"
        assert {int(c) for c in re.findall(r"code == (\d+)", m.group(1))} == set(codes)


# ---- verdict -------------------------------------------------------------------------------


def test_verdict_dirty_on_a_high_fraud_score():
    level, why = ipcheck.verdict({"fraud_score": 92, "dnsbl_usable": True})
    assert level == "dirty"
    assert "92" in why


def test_verdict_dirty_on_three_or_more_abuse_listings():
    level, _ = ipcheck.verdict(
        {"blacklists": ["Spamhaus", "CBL", "Barracuda"], "dnsbl_usable": True})
    assert level == "dirty"


def test_verdict_suspect_on_a_single_listing():
    level, why = ipcheck.verdict({"blacklists": ["Spamhaus"], "dnsbl_usable": True})
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
