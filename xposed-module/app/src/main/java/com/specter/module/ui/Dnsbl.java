package com.specter.module.ui;

import java.util.List;

/**
 * Pure DNSBL logic: the zone table, the reversed-IP query name, and what a zone's answer means.
 * Deliberately free of Android APIs so it runs in the JVM test harness ({@code run-jvm-tests.sh}) —
 * the DNS resolution itself lives in {@link HealthCheck}, which has to pin it to the tunnel.
 *
 * <p>Mirrors {@code specter/ipcheck.py}; keep the two tables in sync.
 */
final class Dnsbl {
    private Dnsbl() {}

    /** The zones behind the "found in N blacklists" count — keyless, no quota, no account. Every zone here was
     *  verified live (its sentinel answers). SORBS is deliberately absent: it shut down in 2024 and now answers
     *  "not listed" for every IP, which reads as a silent all-clear. */
    static final String[][] ZONES = {
            {"Spamhaus", "zen.spamhaus.org"},
            {"CBL", "cbl.abuseat.org"},
            {"Barracuda", "b.barracudacentral.org"},
            {"SpamCop", "bl.spamcop.net"},
            {"UCEPROTECT", "dnsbl-1.uceprotect.net"},
            {"blocklist.de", "bl.blocklist.de"},
            {"PSBL", "psbl.surriel.com"},
            {"DroneBL", "dnsbl.dronebl.org"},
            {"SpamRATS", "all.spamrats.com"},
            {"GBUdb", "truncate.gbudb.net"},
            {"InterServer", "rbl.interserver.net"},
            {"s5h", "all.s5h.net"},
    };

    // ponytail: Spamhaus and CBL refuse queries relayed by large public resolvers (they answer 127.255.255.254),
    // which is what a DoH lookup looks like to them — so on-device those two report BLOCKED rather than a
    // listing. Upgrade path if their coverage is wanted: a free Spamhaus DQS key and the private
    // <key>.zen.dq.spamhaus.net zone, which answers from anywhere. The other ten zones answer fine.

    /** A listing answer. */
    static final String ABUSE = "abuse";
    /** A "this is a dynamic consumer address" listing — every residential and mobile IP carries one. */
    static final String POLICY = "policy";
    /** The zone refused the query (127.255.255.x — Spamhaus returns this to large public resolvers). NOT a
     *  clean result: this zone told us nothing, so it must not be counted as "checked and clear". */
    static final String BLOCKED = "blocked";

    /** {@code 1.2.3.4} -> {@code 4.3.2.1}, the DNSBL query form. Null unless {@code ip} is a dotted-quad IPv4
     *  address — IPv6 DNSBL needs nibble-format queries and few of these zones serve them. */
    static String reverseV4(String ip) {
        if (ip == null) return null;
        String[] p = ip.split("\\.");
        if (p.length != 4) return null;
        for (String s : p) {
            if (s.isEmpty() || s.length() > 3) return null;
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return null;
            if (Integer.parseInt(s) > 255) return null;
        }
        return p[3] + "." + p[2] + "." + p[1] + "." + p[0];
    }

    /** True iff a resolved answer is a real listing. Answers live in 127.0.0.0/8 with the last octet >= 2 —
     *  127.0.0.1 is the "zone is alive, this IP isn't on it" reply some zones give, and 127.255.255.x is
     *  Spamhaus's ERROR range (query via a public/open resolver, or a blocked account). Counting either would
     *  report clean IPs as blacklisted. */
    static boolean listed(String hostAddress) {
        return lastOctet(hostAddress) >= 2;
    }

    /** What a zone's answers mean for this IP: {@link #ABUSE}, {@link #POLICY}, {@link #BLOCKED}, or null when
     *  it answered and this IP simply isn't listed. Abuse wins when both are present — a mobile IP is always on
     *  PBL, so only the SBL/XBL codes alongside it are news. Splitting these is what stops every residential
     *  proxy from reading as blacklisted. */
    static String classify(String zone, List<String> addrs) {
        if (addrs == null) return null;
        boolean any = false, allPolicy = true;
        for (String a : addrs) {
            if (a != null && a.startsWith("127.255.255.")) return BLOCKED;
            int code = lastOctet(a);
            if (code < 2) continue;
            any = true;
            if (!isPolicyCode(zone, code)) allPolicy = false;
        }
        return !any ? null : allPolicy ? POLICY : ABUSE;
    }

    /** What a policy code actually says, or null when that code isn't a policy code on this zone.
     *
     *  <p>Spamhaus splits PBL: 127.0.0.10 is an entry the network owner declared themselves, 127.0.0.11 is one
     *  Spamhaus added because the owner never did (docs.spamhaus.com, Available Zones). Every consumer line
     *  carries the first; a hosting range carries the second only when Spamhaus decided that range shouldn't be
     *  emitting mail — a statement about the netblock, not the routine consumer case. Printing a bare zone name
     *  throws away the half that matters. Mirrors POLICY_CODES in {@code specter/ipcheck.py}. */
    static String policyReason(String zone, int code) {
        if ("zen.spamhaus.org".equals(zone)) return code == 10 ? "PBL, network owner declared it end-user"
                : code == 11 ? "PBL, Spamhaus listed the range" : null;
        if ("all.spamrats.com".equals(zone)) return code == 36 ? "dynamic reverse DNS"
                : code == 37 ? "no reverse DNS" : null;
        return null;
    }

    /** A policy listing as one display string: the zone, and why it lists this IP. */
    static String policyLabel(String name, String zone, List<String> addrs) {
        StringBuilder why = new StringBuilder();
        if (addrs != null) for (String a : addrs) {
            String r = policyReason(zone, lastOctet(a));
            if (r == null || why.indexOf(r) >= 0) continue;
            why.append(why.length() == 0 ? "" : "; ").append(r);
        }
        return why.length() == 0 ? name : name + " (" + why + ")";
    }

    /** Codes that mean "should not be sending mail directly", not abuse: Spamhaus PBL, SpamRATS Dyna/NoPtr. */
    private static boolean isPolicyCode(String zone, int code) {
        return policyReason(zone, code) != null;
    }

    /** The last octet of a 127.0.0.0/8 answer, or -1 if this isn't one (or is the error range). */
    private static int lastOctet(String hostAddress) {
        if (hostAddress == null
                || !hostAddress.startsWith("127.")
                || hostAddress.startsWith("127.255.255.")) return -1;
        String[] p = hostAddress.split("\\.");
        if (p.length != 4) return -1;
        try { return Integer.parseInt(p[3]); } catch (NumberFormatException e) { return -1; }
    }
}
