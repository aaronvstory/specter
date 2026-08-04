package com.specter.module.ui;

import java.util.Arrays;
import java.util.Collections;

/** Hand-rolled asserts (no framework, matching the rest of the JVM suite). */
public class DnsblTest {
    static int checks = 0;

    static void eq(Object want, Object got, String what) {
        checks++;
        if (want == null ? got != null : !want.equals(got))
            throw new AssertionError(what + ": want " + want + ", got " + got);
    }

    public static void main(String[] args) {
        // ---- the query form: octets reversed, zone appended by the caller ----
        eq("4.3.2.1", Dnsbl.reverseV4("1.2.3.4"), "reverse dotted-quad");
        eq("16.84.59.172", Dnsbl.reverseV4("172.59.84.16"), "reverse a real exit IP");
        eq("255.0.0.0", Dnsbl.reverseV4("0.0.0.255"), "reverse keeps every octet");

        // Anything that isn't a dotted-quad must be refused, not silently mangled into a bogus query
        // that resolves to nothing and reads as "clean".
        eq(null, Dnsbl.reverseV4(null), "null ip");
        eq(null, Dnsbl.reverseV4(""), "empty ip");
        eq(null, Dnsbl.reverseV4("1.2.3"), "too few octets");
        eq(null, Dnsbl.reverseV4("1.2.3.4.5"), "too many octets");
        eq(null, Dnsbl.reverseV4("1.2.3.256"), "octet out of range");
        eq(null, Dnsbl.reverseV4("1.2.3.x"), "non-numeric octet");
        eq(null, Dnsbl.reverseV4("2001:db8::1"), "ipv6 is refused (nibble format unsupported)");

        // ---- what counts as a listing ----
        eq(true, Dnsbl.listed("127.0.0.2"), "127.0.0.2 is a listing");
        eq(true, Dnsbl.listed("127.0.0.10"), "127.0.0.10 is a listing");
        eq(true, Dnsbl.listed("127.0.0.37"), "127.0.0.37 is a listing");
        // 127.0.0.1 = "zone alive, not listed"; 127.255.255.x = Spamhaus's open-resolver error range.
        // Counting either would report every IP on earth as blacklisted.
        eq(false, Dnsbl.listed("127.0.0.1"), "127.0.0.1 is NOT a listing");
        eq(false, Dnsbl.listed("127.255.255.254"), "open-resolver error is NOT a listing");
        eq(false, Dnsbl.listed("127.255.255.252"), "blocked-query error is NOT a listing");
        eq(false, Dnsbl.listed("93.184.216.34"), "a public address is not a listing");
        eq(false, Dnsbl.listed(null), "no answer is not a listing");

        // ---- abuse vs policy: the split that keeps residential proxies from reading as dirty ----
        // Every consumer/mobile IP is on Spamhaus PBL and SpamRATS Dyna/NoPtr by design.
        eq(Dnsbl.POLICY, Dnsbl.classify("zen.spamhaus.org", Collections.singletonList("127.0.0.10")),
                "PBL alone is policy");
        eq(Dnsbl.POLICY, Dnsbl.classify("all.spamrats.com", Arrays.asList("127.0.0.36", "127.0.0.37")),
                "SpamRATS Dyna/NoPtr is policy");
        // The real T-Mobile exit IP that motivated this feature: SBL + XBL + PBL together.
        eq(Dnsbl.ABUSE, Dnsbl.classify("zen.spamhaus.org",
                        Arrays.asList("127.0.0.3", "127.0.0.4", "127.0.0.10")),
                "SBL/XBL alongside PBL is abuse");
        eq(Dnsbl.ABUSE, Dnsbl.classify("cbl.abuseat.org", Collections.singletonList("127.0.0.2")),
                "a plain listing is abuse");
        eq(null, Dnsbl.classify("zen.spamhaus.org", Collections.emptyList()), "no answer, no listing");
        eq(null, Dnsbl.classify("dnsbl.dronebl.org", Collections.singletonList("127.0.0.1")),
                "an alive-but-unlisted reply is not a listing");
        eq(null, Dnsbl.classify("zen.spamhaus.org", null), "null answers are safe");
        // Spamhaus and CBL answer this to queries relayed by large public resolvers (which is what a DoH
        // lookup looks like). Treating it as "not listed" would downgrade "we don't know" to "it's fine".
        eq(Dnsbl.BLOCKED, Dnsbl.classify("zen.spamhaus.org", Collections.singletonList("127.255.255.254")),
                "a refusal is BLOCKED, not clean");
        eq(Dnsbl.BLOCKED, Dnsbl.classify("cbl.abuseat.org",
                        Arrays.asList("127.0.0.2", "127.255.255.252")),
                "a refusal anywhere in the answer wins");
        // A policy code on a zone that has no policy semantics is a plain listing.
        eq(Dnsbl.ABUSE, Dnsbl.classify("bl.spamcop.net", Collections.singletonList("127.0.0.10")),
                "code 10 is only policy on zones that define it that way");

        // ---- naming the policy code, not just the zone ----
        // Spamhaus splits PBL: .10 is the network owner declaring its own range end-user, .11 is Spamhaus
        // listing a range the owner never declared. On a hosting network only the second happens, and it is
        // a statement about that netblock — a bare "Spamhaus" throws away the half that matters.
        eq("PBL, network owner declared it end-user", Dnsbl.policyReason("zen.spamhaus.org", 10),
                "PBL 10 is the ISP's own declaration");
        eq("PBL, Spamhaus listed the range", Dnsbl.policyReason("zen.spamhaus.org", 11),
                "PBL 11 is Spamhaus listing the range");
        eq("dynamic reverse DNS", Dnsbl.policyReason("all.spamrats.com", 36), "SpamRATS Dyna");
        eq("no reverse DNS", Dnsbl.policyReason("all.spamrats.com", 37), "SpamRATS NoPtr");
        eq(null, Dnsbl.policyReason("zen.spamhaus.org", 2), "SBL is not a policy code");
        eq(null, Dnsbl.policyReason("cbl.abuseat.org", 10), "code 10 is only policy where defined");

        eq("Spamhaus (PBL, Spamhaus listed the range)",
                Dnsbl.policyLabel("Spamhaus", "zen.spamhaus.org", Collections.singletonList("127.0.0.11")),
                "the label carries the reason");
        eq("SpamRATS (dynamic reverse DNS; no reverse DNS)",
                Dnsbl.policyLabel("SpamRATS", "all.spamrats.com", Arrays.asList("127.0.0.36", "127.0.0.37")),
                "several reasons join, no duplicates");
        eq("DroneBL", Dnsbl.policyLabel("DroneBL", "dnsbl.dronebl.org", Collections.singletonList("127.0.0.3")),
                "a zone with no policy semantics keeps the bare name");
        eq("Spamhaus", Dnsbl.policyLabel("Spamhaus", "zen.spamhaus.org", null), "null answers are safe");
        // The label is embedded in a "kind:name" string that HealthCheck splits on the FIRST ':', so a
        // label containing one would silently truncate the zone name.
        eq(-1, Dnsbl.policyLabel("Spamhaus", "zen.spamhaus.org",
                Arrays.asList("127.0.0.10", "127.0.0.11")).indexOf(':'), "no colon in a policy label");

        // ---- the zone table ----
        boolean sorbs = false;
        for (String[] z : Dnsbl.ZONES) if (z[1].contains("sorbs")) sorbs = true;
        eq(false, sorbs, "SORBS is gone (shut down 2024; it answers clean for everything)");
        eq(true, Dnsbl.ZONES.length >= 10, "zone table is populated");

        System.out.println("DnsblTest: " + checks + " checks passed");
    }
}
