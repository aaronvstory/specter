package com.fleet.idrotate.gen;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Plain-JVM tests for the generator port (no framework). Run via run-jvm-tests.sh.
 * Mirrors the Python test contract in tests/test_generators.py: every generator's output
 * must pass its own validator, and the ban-critical properties (Luhn, gsf<=Long.MAX,
 * UUID v4, MAC locally-administered bit) hold across many seeds.
 */
public class GeneratorsTest {
    static int passed = 0, failed = 0;

    static void check(boolean cond, String name) {
        if (cond) passed++;
        else { failed++; System.out.println("FAIL: " + name); }
    }

    /**
     * Deterministic seeded RNG matching specter/profile.py::_seeded EXACTLY:
     *   r(n) = int.from_bytes(sha256(h + counter.to_bytes(8,'big'))[:8], 'big') % n
     * where h = sha256(str(seed)) and the counter increments per draw. Python uses the full
     * UNSIGNED 64-bit value, so nextLong uses Long.remainderUnsigned (never mask off the top bit).
     */
    static Generators.Rng seeded(long seed) {
        try {
            final byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(seed).getBytes(StandardCharsets.UTF_8));
            final long[] i = {0};
            return new Generators.Rng() {
                private long draw8() {
                    try {
                        i[0]++;
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        md.update(h);
                        byte[] cnt = new byte[8];
                        long v = i[0];
                        for (int k = 7; k >= 0; k--) { cnt[k] = (byte) (v & 0xFF); v >>= 8; }
                        md.update(cnt);
                        byte[] d = md.digest();
                        long acc = 0;
                        for (int k = 0; k < 8; k++) acc = (acc << 8) | (d[k] & 0xFF); // full unsigned 64-bit
                        return acc;
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
                @Override public int next(int n) { return (int) Long.remainderUnsigned(draw8(), n); }
                @Override public long nextLong(long n) { return Long.remainderUnsigned(draw8(), n); }
            };
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static void main(String[] args) {
        Generators.Rng r = seeded(42);

        // Each generator's output passes its own validator, across many seeds.
        for (int s = 0; s < 2000; s++) {
            Generators.Rng g = seeded(s);
            check(Generators.validate("android_id", Generators.hex16(g)), "android_id valid s=" + s);
            check(Generators.validate("serial", Generators.serialForBrand(g, "Samsung")), "serial(Samsung) valid s=" + s);
            check(Generators.validate("serial", Generators.serialForBrand(g, "Google")), "serial(Google) valid s=" + s);
            check(!Generators.validate("serial", Generators.hex16upper(g)), "old hex16 serial rejected s=" + s);
            check(Generators.validate("media_drm_id", Generators.hex32(g)), "media_drm valid s=" + s);
            check(Generators.validate("advertising_id", Generators.uuid(g)), "adv uuid v4 valid s=" + s);
            check(Generators.validate("bluetooth_mac", Generators.macUpper(g)), "mac_upper valid s=" + s);
            check(Generators.validate("wifi_bssid", Generators.macLower(g)), "mac_lower valid s=" + s);
            check(Generators.validate("mobile_number", Generators.phoneUs(g)), "phone valid s=" + s);
            check(Generators.validate("gmail", Generators.gmail(g)), "email valid s=" + s);

            // Email realism: contains a real name from the list, no random-consonant-soup, a known provider.
            String em = Generators.gmail(g);
            String localPart = em.substring(0, em.indexOf('@'));
            boolean hasKnownName = false;
            for (String fn : Generators.FIRST_NAMES) if (localPart.contains(fn)) { hasKnownName = true; break; }
            check(hasKnownName, "email uses a real first name s=" + s + " (" + em + ")");
            check(em.matches(".*@(gmail|outlook|yahoo|hotmail|proton|icloud)\\..*"), "email known provider s=" + s);

            // US NANP phone shape: 1 + 10 digits, area/exchange start [2-9].
            String us = Generators.phoneUs(g);
            check(us.length() == 11 && us.startsWith("1"), "us phone 1+10 digits s=" + s);

            // IMEI: 15-digit, Luhn-valid, brand TAC prefix respected (Samsung — a US-market brand).
            String imei = Generators.imei(g, "35207609"); // samsung TAC
            check(Generators.validate("imei1", imei), "imei valid s=" + s);
            check(imei.startsWith("35207609"), "imei TAC prefix s=" + s);

            // IMSI/ICCID carrier-coherent (T-Mobile 310260).
            String imsi = Generators.imsi(g, "310260");
            check(Generators.validate("sim_subscriber_imsi", imsi) && imsi.startsWith("310260"), "imsi valid+prefix s=" + s);
            String iccid = Generators.iccid(g, "310260");
            check(Generators.validate("sim_serial_iccid", iccid) && iccid.startsWith("89014103"), "iccid valid+IIN s=" + s);

            // GSF: ban-critical — decimal, positive, <= Long.MAX, parseLong never throws.
            String gsf = Generators.gsf(g);
            check(Generators.validate("gsf_id", gsf), "gsf valid s=" + s);
            long gsfN = Long.parseLong(gsf); // must not throw
            check(gsfN >= 1000000000000000000L && gsfN <= Generators.LONG_MAX, "gsf in [1e18,LongMax] s=" + s);
        }

        // Luhn primitive: a valid IMEI stays valid; flipping a digit breaks it.
        String imei = Generators.imei(r, "35815807");
        check(Generators.luhnValid(imei), "luhn: generated imei valid");
        char[] bad = imei.toCharArray();
        bad[0] = bad[0] == '0' ? '1' : (char) (bad[0] - 1);
        check(!Generators.luhnValid(new String(bad)), "luhn: corrupted imei invalid");

        // UUID v4 structure: version nibble '4', variant in [89ab].
        String u = Generators.uuid(r);
        check(u.charAt(14) == '4', "uuid version nibble is 4");
        check("89ab".indexOf(u.charAt(19)) >= 0, "uuid variant nibble 10xx");

        // MAC locally-administered + unicast bit on the first octet.
        String mac = Generators.macUpper(r);
        int firstOctet = Integer.parseInt(mac.substring(0, 2), 16);
        check((firstOctet & 0x02) != 0, "mac locally-administered bit set");
        check((firstOctet & 0x01) == 0, "mac unicast bit clear");

        // Determinism: same seed -> same value (so the port can be diffed vs Python).
        check(Generators.hex16(seeded(7)).equals(Generators.hex16(seeded(7))), "seeded determinism");

        // Unknown key passes through.
        check(Generators.validate("build_model", "whatever"), "unknown key passes");

        System.out.println("Generators: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
