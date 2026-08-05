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
            // Added 2026-08-05 to close a coverage gap. Each verified live + keyless. The first three are
            // per-IP abuse lists; UCEPROTECT L2/L3 are netblock/ASN listings (broad — a /24 or whole ASN with
            // spam history), classified POLICY so they show but don't inflate the per-IP abuse count. Mirrors
            // specter/ipcheck.py; keep the two in sync.
            {"0SPAM", "bl.0spam.org"},
            {"SpamEatingMonkey", "bl.spameatingmonkey.net"},
            {"Backscatterer", "ips.backscatterer.org"},
            {"UCEPROTECT-L2", "dnsbl-2.uceprotect.net"},
            {"UCEPROTECT-L3", "dnsbl-3.uceprotect.net"},
    };

    /** The zones that actually hold IPv6 data — the denominator for an IPv6 exit.
     *
     *  <p>MEASURED against 60 live IPv6 Tor exits 2026-08-06: only these four ever answer; the other
     *  thirteen returned nothing for any of them. Do NOT probe support with a mapped address like
     *  {@code ::ffff:7f00:2} — rbldnsd's RECOGNIZE_IP4IN6 rewrites that into the plain IPv4 lookup, so a
     *  zone with no IPv6 data at all answers it. (That aliasing, plus a probe against the
     *  {@code 2001:db8::} documentation range nothing lists, is what earlier read as "no zone supports
     *  IPv6" and left every IPv6 exit with a clean-looking verdict backed by zero checks.)
     *
     *  <p>The verdict is WEAKER than the IPv4 one in both directions: Spamhaus, CBL and s5h list /64
     *  PREFIXES, so a clean address can sit in a listed /64 and a listed one may never have sent anything
     *  itself. DroneBL lists exact /128s. Mirrors DNSBL_ZONES_V6 in specter/ipcheck.py. */
    static final String[][] ZONES_V6 = {
            {"Spamhaus", "zen.spamhaus.org"},
            {"CBL", "cbl.abuseat.org"},
            {"s5h", "all.s5h.net"},
            {"DroneBL", "dnsbl.dronebl.org"},
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

    /** {@code 2001:db8::1} -> the 32-nibble reversed query name RFC 5782 §2.4 requires, or null unless it
     *  parses as IPv6. Query the full /128 even though zen/CBL/s5h list /64s — DNS resolves the nibble tree
     *  from the most significant end, so a /64 listing is matched by any address beneath it for free.
     *  Mirrors reverse_v6() in specter/ipcheck.py.
     *
     *  <p>{@code getByName} is safe here despite its name: the ':' guard means the input is always an IPv6
     *  literal, and a literal is parsed rather than resolved — no DNS, no Android API, so this still runs in
     *  the JVM test harness. */
    static String reverseV6(String ip) {
        if (ip == null || ip.indexOf(':') < 0) return null;
        byte[] packed;
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(ip);
            if (!(a instanceof java.net.Inet6Address)) return null;
            packed = a.getAddress();
        } catch (Throwable t) { return null; }
        if (packed.length != 16) return null;
        StringBuilder nib = new StringBuilder(63);
        for (int i = packed.length - 1; i >= 0; i--) {
            int b = packed[i] & 0xFF;
            nib.append(Character.forDigit(b & 0xF, 16)).append('.')
               .append(Character.forDigit(b >> 4, 16));
            if (i > 0) nib.append('.');
        }
        return nib.toString();
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
        // UCEPROTECT L2/L3 list a whole /24 or ASN when someone in it spams — a netblock characteristic, not
        // per-IP abuse. Their listing code is 127.0.0.2, mapped here to keep it out of the abuse count.
        if ("dnsbl-2.uceprotect.net".equals(zone)) return code == 2 ? "/24 netblock listed, a neighbour spammed" : null;
        if ("dnsbl-3.uceprotect.net".equals(zone)) return code == 2 ? "ASN listed, spam elsewhere in the network" : null;
        return null;
    }

    /** A policy listing as one display string: the zone, and why it lists this IP.
     *
     *  <p>Reasons are emitted in ASCENDING CODE order, NOT the order the addresses arrived — DNS makes no
     *  ordering guarantee, and the Python twin ({@code ipcheck.policy_reasons}) iterates its code table, so
     *  ordering off {@code addrs} would render the same multi-code answer differently on phone vs desktop. We
     *  collect the hit codes into a sorted set first, exactly as {@code dnsbl_check} sorts zones by a fixed
     *  order before joining. */
    static String policyLabel(String name, String zone, List<String> addrs) {
        java.util.TreeSet<Integer> codes = new java.util.TreeSet<>();
        if (addrs != null) for (String a : addrs) {
            int c = lastOctet(a);
            if (policyReason(zone, c) != null) codes.add(c);
        }
        StringBuilder why = new StringBuilder();
        for (int c : codes) why.append(why.length() == 0 ? "" : "; ").append(policyReason(zone, c));
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
