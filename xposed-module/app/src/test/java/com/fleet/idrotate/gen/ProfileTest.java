package com.fleet.idrotate.gen;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plain-JVM tests for Profile assembly/coherence + UsedStore no-reuse. Run via run-jvm-tests.sh. */
public class ProfileTest {
    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    static Generators.Rng seeded(long seed) {
        try {
            final byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(seed).getBytes(StandardCharsets.UTF_8));
            final long[] i = {0};
            return new Generators.Rng() {
                long draw8() { try {
                    i[0]++; MessageDigest md = MessageDigest.getInstance("SHA-256"); md.update(h);
                    byte[] cnt = new byte[8]; long v = i[0];
                    for (int k = 7; k >= 0; k--) { cnt[k] = (byte)(v & 0xFF); v >>= 8; }
                    md.update(cnt); byte[] d = md.digest();
                    long acc = 0; for (int k = 0; k < 8; k++) acc = (acc << 8) | (d[k] & 0xFF); return acc;
                } catch (Exception e) { throw new RuntimeException(e); } }
                public int next(int n) { return (int) Long.remainderUnsigned(draw8(), n); }
                public long nextLong(long n) { return Long.remainderUnsigned(draw8(), n); }
            };
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // A few real device rows (same positional shape as data/devices.json).
    static List<List<String>> devices() {
        List<List<String>> d = new ArrayList<>();
        d.add(Arrays.asList("Google Pixel 5", "Google", "google", "redfin", "redfin", "Pixel 5:11", "RQ3A.210805.001.A1", "7474174", "2021-08-05"));
        d.add(Arrays.asList("Samsung Galaxy S21", "Samsung", "samsung", "o1s", "o1sxxx", "SM-G991U:11", "RP1A.200720.012", "G991USQU4AUDA", "2021-04-01"));
        d.add(Arrays.asList("OnePlus 8T", "OnePlus", "oneplus", "kebab", "OnePlus8T", "KB2005:11", "RP1A.201005.001", "2107220042", "2021-07-01"));
        return d;
    }

    public static void main(String[] args) {
        List<List<String>> devs = devices();

        // build() produces a valid, coherent profile across many seeds.
        for (int s = 0; s < 1000; s++) {
            Map<String, String> p = Profile.build(seeded(s), devs, true);
            List<String> errs = Profile.validate(p);
            check(errs.isEmpty(), "profile valid s=" + s + " " + errs);
            // all 27 keys present
            check(p.size() == Profile.KEYS.length, "27 keys s=" + s);
            // coherence spot-checks
            check(p.get("build_fingerprint").contains(p.get("build_brand")), "brand in fp s=" + s);
            check(p.get("sim_subscriber_imsi").startsWith(p.get("sim_operator_mccmnc")), "imsi carrier s=" + s);
            // dual-SIM: imei1 != imei2 but share the TAC (first 8 digits)
            check(!p.get("imei1").equals(p.get("imei2")), "imei1 != imei2 s=" + s);
            check(p.get("imei1").substring(0, 8).equals(p.get("imei2").substring(0, 8)), "imeis share TAC s=" + s);
        }

        // Determinism: same seed -> same profile.
        Map<String, String> a = Profile.build(seeded(77), devs, true);
        Map<String, String> b = Profile.build(seeded(77), devs, true);
        check(a.equals(b), "profile deterministic for a seed");

        // us_bias picks a US-common brand.
        Map<String, String> us = Profile.build(seeded(3), devs, true);
        check(Profile.US_COMMON_BRANDS.contains(us.get("build_brand").toLowerCase()), "us_bias -> US brand");

        // ---- UsedStore: ban-critical no-reuse ----
        UsedStore store = new UsedStore();
        Set<String> gsfsSeen = new HashSet<>();
        int recorded = 0;
        for (int s = 0; s < 200; s++) {
            Map<String, String> p = Profile.build(seeded(1000 + s), devs, true);
            if (store.collides(p)) continue;
            if (store.record(p)) { recorded++; gsfsSeen.add(p.get("gsf_id")); }
        }
        check(store.count() == recorded, "store count matches recorded");
        check(gsfsSeen.size() == recorded, "no gsf repeated among recorded");

        // record() rejects a second attempt to claim the same profile.
        Map<String, String> dup = Profile.build(seeded(5000), devs, true);
        check(store.record(dup), "first record of new profile succeeds");
        check(!store.record(dup), "duplicate record rejected (no reuse)");
        check(store.collides(dup), "collides() true after record");

        // fromParsed restores prior ids (so reuse is caught across restarts).
        Map<String, List<String>> parsed = new HashMap<>();
        parsed.put("android_id", Arrays.asList("deadbeefdeadbeef"));
        UsedStore restored = UsedStore.fromParsed(parsed);
        Map<String, String> collide = new HashMap<>();
        for (String k : Profile.UNIQUE_KEYS) collide.put(k, "x");
        collide.put("android_id", "deadbeefdeadbeef");
        check(restored.collides(collide), "fromParsed restores prior unique ids");

        System.out.println("Profile+UsedStore: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
