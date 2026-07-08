package com.fleet.idrotate.gen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Per-identifier value generators + validators — a 1:1 port of the trusted Python
 * {@code specter/generators.py} (73-test reference). Pure logic, no Android imports, so it
 * runs under the same javac-only JVM test harness as SpoofLogic (run-jvm-tests.sh).
 *
 * Every generator takes a random-int source {@link Rng} r where r.next(n) -> [0, n). Production
 * passes a SecureRandom-backed source; tests pass a seeded deterministic one, so generated
 * values are reproducible and the port can be diffed byte-for-byte against the Python output.
 */
public final class Generators {
    private Generators() {}

    /**
     * Random source. {@code next(n)} returns an int in [0, n); {@code nextLong(n)} returns a
     * long in [0, n). The seeded (test) RNG overrides {@code nextLong} to match Python's
     * {@code int.from_bytes(8 bytes, 'big') % n} single-draw semantics; the default suffices
     * for the production SecureRandom source (gsf parity is only asserted for the seeded RNG).
     */
    public interface Rng {
        int next(int n);
        default long nextLong(long n) {
            // Two 31-bit int draws → a non-negative 62-bit value, then mod n. Adequate for
            // production entropy; the seeded RNG replaces this for exact Python parity.
            long hi = next(Integer.MAX_VALUE);
            long lo = next(Integer.MAX_VALUE);
            long v = (hi << 31) | lo; // >= 0
            return v % n;
        }
    }

    public static final long LONG_MAX = 9223372036854775807L; // Java signed 64-bit max

    // ---------- primitives ----------
    static String hexs(Rng r, int nbytes) {
        StringBuilder sb = new StringBuilder(nbytes * 2);
        for (int i = 0; i < nbytes * 2; i++) sb.append("0123456789abcdef".charAt(r.next(16)));
        return sb.toString();
    }

    static String digits(Rng r, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append((char) ('0' + r.next(10)));
        return sb.toString();
    }

    /** Luhn check digit for {@code num} (as if it were appended). */
    public static String luhnCheckDigit(String num) {
        int s = 0;
        for (int i = 0; i < num.length(); i++) {
            int d = num.charAt(num.length() - 1 - i) - '0';
            if (i % 2 == 0) { d *= 2; if (d > 9) d -= 9; }
            s += d;
        }
        return String.valueOf((10 - s % 10) % 10);
    }

    public static boolean luhnValid(String num) {
        int s = 0;
        for (int i = 0; i < num.length(); i++) {
            int d = num.charAt(num.length() - 1 - i) - '0';
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9; }
            s += d;
        }
        return s % 10 == 0;
    }

    // ---------- generators ----------
    public static String hex16(Rng r)      { return hexs(r, 8); }               // android_id
    public static String hex16upper(Rng r) { return hexs(r, 8).toUpperCase(); } // serial
    public static String hex32(Rng r)      { return hexs(r, 16); }              // media_drm (16 bytes)

    /**
     * Real 8-digit TAC prefixes by manufacturer — an IMEI's first 8 digits identify make/model,
     * so a brand-coherent TAC survives checks that validate TAC-against-brand.
     */
    static final Map<String, String[]> TAC_BY_BRAND = new LinkedHashMap<>();
    static {
        TAC_BY_BRAND.put("samsung",  new String[]{"35207609", "35316805", "35847909", "35692106"});
        TAC_BY_BRAND.put("google",   new String[]{"35815807", "35854108", "35161511"});
        TAC_BY_BRAND.put("motorola", new String[]{"35462106", "35404007", "35123456"});
        TAC_BY_BRAND.put("oneplus",  new String[]{"86293403", "86891303", "86651004"});
        TAC_BY_BRAND.put("lge",      new String[]{"35295406", "35878705"});
        TAC_BY_BRAND.put("xiaomi",   new String[]{"86412604", "86734703"});
        TAC_BY_BRAND.put("huawei",   new String[]{"86188403", "86544603"});
        TAC_BY_BRAND.put("sony",     new String[]{"35643606", "35128907"});
        TAC_BY_BRAND.put("asus",     new String[]{"35316906", "35847008"});
        TAC_BY_BRAND.put("oppo",     new String[]{"86234503"});
        TAC_BY_BRAND.put("poco",     new String[]{"86412604"});
        TAC_BY_BRAND.put("redmi",    new String[]{"86734703"});
    }

    public static String tacForBrand(Rng r, String brand) {
        String[] tacs = TAC_BY_BRAND.get(brand == null ? "" : brand.toLowerCase());
        if (tacs == null) tacs = new String[]{"35000000"};
        return tacs[r.next(tacs.length)];
    }

    /** 15-digit Luhn-valid IMEI; if a valid 8-digit TAC is given, use it as the first 8 digits. */
    public static String imei(Rng r, String tac) {
        String body;
        if (tac != null && tac.length() == 8 && tac.chars().allMatch(Character::isDigit)) {
            body = tac + digits(r, 6);   // TAC(8) + serial(6) = 14, then check digit
        } else {
            body = digits(r, 14);
        }
        return body + luhnCheckDigit(body);
    }

    /** RFC 4122 v4 UUID with version + variant bits set explicitly. */
    public static String uuid(Rng r) {
        int[] b = new int[16];
        for (int i = 0; i < 16; i++) b[i] = r.next(256);
        b[6] = (b[6] & 0x0F) | 0x40; // version 4
        b[8] = (b[8] & 0x3F) | 0x80; // variant 10xx
        StringBuilder h = new StringBuilder(32);
        for (int x : b) h.append(String.format("%02x", x));
        String s = h.toString();
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                + "-" + s.substring(16, 20) + "-" + s.substring(20, 32);
    }

    public static String macUpper(Rng r) {
        int[] b = new int[6];
        for (int i = 0; i < 6; i++) b[i] = r.next(256);
        b[0] = (b[0] & 0xFE) | 0x02; // locally-administered, unicast
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) { if (i > 0) sb.append(':'); sb.append(String.format("%02X", b[i])); }
        return sb.toString();
    }

    public static String macLower(Rng r) { return macUpper(r).toLowerCase(); }

    /** NANP US phone: 1 + area [2-9]XX + exchange [2-9]XX + 4 digits. */
    public static String phoneUs(Rng r) {
        String area = String.valueOf(2 + r.next(8)) + digits(r, 2);
        String exch = String.valueOf(2 + r.next(8)) + digits(r, 2);
        return "1" + area + exch + digits(r, 4);
    }

    public static String imsi(Rng r, String mccmnc) {
        return mccmnc + digits(r, 15 - mccmnc.length());
    }

    /** ICCID issuer-identifier prefixes keyed by MCC+MNC so the SIM serial is carrier-coherent. */
    static final Map<String, String> ICCID_IIN = new LinkedHashMap<>();
    static {
        ICCID_IIN.put("310260", "89014103"); // T-Mobile
        ICCID_IIN.put("310160", "89014103"); // T-Mobile
        ICCID_IIN.put("311480", "89148000"); // Verizon
        ICCID_IIN.put("310410", "89014104"); // AT&T
        ICCID_IIN.put("310030", "89014104"); // AT&T
        ICCID_IIN.put("310120", "89011201"); // Sprint
        ICCID_IIN.put("311580", "89011580"); // US Cellular
        ICCID_IIN.put("311870", "89011870"); // Boost
    }

    /** 20-digit Luhn-valid ICCID with a carrier-consistent issuer prefix when known. */
    public static String iccid(Rng r, String mccmnc) {
        String iin = ICCID_IIN.getOrDefault(mccmnc, "890114");
        String body = iin + digits(r, 19 - iin.length());
        return body + luhnCheckDigit(body);
    }

    /**
     * GSF android_id: signed 64-bit long as decimal, in [1e18, Long.MAX] so parseLong never throws.
     * Matches Python {@code gsf()} exactly: a SINGLE draw {@code r.nextLong(LONG_MAX - lo)}.
     */
    public static String gsf(Rng r) {
        long lo = 1000000000000000000L;
        return String.valueOf(lo + r.nextLong(LONG_MAX - lo));
    }

    public static String gmail(Rng r) {
        int len = 3 + r.next(5);
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < len; i++) first.append("abcdefghijklmnopqrstuvwxyz".charAt(r.next(26)));
        return first + digits(r, 3) + "@gmail.com";
    }

    public static String ssid(Rng r) {
        String[] nets = {"NETGEAR", "ATT", "xfinitywifi", "Linksys", "TP-Link_", "SpectrumSetup-"};
        return nets[r.next(nets.length)] + digits(r, 2);
    }

    // ---------- validators ----------
    private static final Pattern P_ANDROID_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern P_SERIAL     = Pattern.compile("[0-9A-F]{16}");
    private static final Pattern P_MEDIA_DRM  = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern P_ADV        = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern P_MAC_UP     = Pattern.compile("([0-9A-F]{2}:){5}[0-9A-F]{2}");
    private static final Pattern P_MAC_LOW    = Pattern.compile("([0-9a-f]{2}:){5}[0-9a-f]{2}");
    private static final Pattern P_PHONE      = Pattern.compile("1[2-9]\\d{2}[2-9]\\d{6}");
    private static final Pattern P_GMAIL      = Pattern.compile("[a-z]{3,}\\d{3}@gmail\\.com");

    private static boolean allDigits(String v) {
        if (v.isEmpty()) return false;
        for (int i = 0; i < v.length(); i++) if (!Character.isDigit(v.charAt(i))) return false;
        return true;
    }

    /** True if {@code value} has the right format for {@code key} (unknown keys pass through). */
    public static boolean validate(String key, String value) {
        switch (key) {
            case "android_id":          return P_ANDROID_ID.matcher(value).matches();
            case "serial":              return P_SERIAL.matcher(value).matches();
            case "media_drm_id":        return P_MEDIA_DRM.matcher(value).matches();
            case "imei1":
            case "imei2":               return value.length() == 15 && allDigits(value) && luhnValid(value);
            case "advertising_id":      return P_ADV.matcher(value).matches();
            case "bluetooth_mac":
            case "wifi_mac":            return P_MAC_UP.matcher(value).matches();
            case "wifi_bssid":          return P_MAC_LOW.matcher(value).matches();
            case "mobile_number":       return P_PHONE.matcher(value).matches();
            case "sim_subscriber_imsi": return value.length() == 15 && allDigits(value);
            case "sim_serial_iccid":    return value.length() == 20 && allDigits(value) && luhnValid(value);
            case "gsf_id":              return allDigits(value) && parsePositiveLong(value);
            case "gmail":               return P_GMAIL.matcher(value).matches();
            default:                    return true;
        }
    }

    private static boolean parsePositiveLong(String v) {
        try { long n = Long.parseLong(v); return n > 0 && n <= LONG_MAX; }
        catch (NumberFormatException e) { return false; }
    }
}
