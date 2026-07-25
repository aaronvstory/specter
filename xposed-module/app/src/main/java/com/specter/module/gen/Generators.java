package com.specter.module.gen;

import java.util.LinkedHashMap;
import java.util.Locale;
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
    public static String hex16upper(Rng r) { return hexs(r, 8).toUpperCase(); } // serial (legacy, pure-hex)
    public static String hex32(Rng r)      { return hexs(r, 16); }              // media_drm (16 bytes)

    // Real device serials are NOT pure hex — they use a broader uppercase-alphanumeric alphabet, a
    // brand-specific length, and a fixed leading prefix (e.g. every Samsung phone serial starts "R",
    // 11 chars total; a real Pixel serial is 14 alnum chars incl letters like Z/P absent from hex).
    // hex16upper (16 pure-hex chars) is detectably synthetic for a device claiming to be a Pixel/Galaxy.
    // We replicate the FORMAT (prefix + length + alphabet), not the decodable factory/date fields — we
    // don't need decodable serials, only format-plausible ones. Grounded in Samsung/Google/Motorola docs.
    // Base34: 0-9 + A-Z minus I and O (confusables Samsung/Google avoid). Fixed order = Java/Python parity.
    static final String SERIAL_ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // 34 chars (no I, no O)

    /** brand -> {fixed prefix, total length}. Prefix chars are literal; the rest are drawn from SERIAL_ALPHABET. */
    static String[] serialSpecForBrand(String brand) {
        String b = brand == null ? "" : brand.toLowerCase();
        if (b.contains("samsung"))  return new String[]{"R", "11"};   // Samsung: always "R" + 10, 11 total
        if (b.contains("google"))   return new String[]{"",  "14"};   // Pixel: 14 alnum (e.g. 9B151FFAZ00FPF)
        if (b.contains("motorola") || b.equals("moto")) return new String[]{"ZY", "12"}; // modern Moto "ZY..."
        if (b.contains("lg"))       return new String[]{"", "15"};    // LG: longer, numeric-heavy alnum
        return new String[]{"", "12"};                                 // generic fallback
    }

    /** Brand-plausible serial: fixed prefix + alphanumeric body, correct per-brand length. Uppercase. */
    public static String serialForBrand(Rng r, String brand) {
        String[] spec = serialSpecForBrand(brand);
        String prefix = spec[0];
        int len = Integer.parseInt(spec[1]);
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < len) sb.append(SERIAL_ALPHABET.charAt(r.next(SERIAL_ALPHABET.length())));
        return sb.toString();
    }

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
        String[] tacs = TAC_BY_BRAND.get(brand == null ? "" : brand.toLowerCase(Locale.ROOT));
        if (tacs == null) tacs = new String[]{"35000000"};
        return tacs[r.next(tacs.length)];
    }

    /** Build.BOOTLOADER — DEVICE-coherent, generic-shaped bootloader string. Derived from the device
     *  codename (not a fixed table of real model-specific firmware prefixes, which would mismatch the
     *  picked device — e.g. a Galaxy A01 must never report a Galaxy S21 bootloader). The shape follows
     *  each OEM's real style but the identifying part comes from THIS device, so it can't contradict it. */
    public static String bootloader(Rng r, String brand, String device) {
        String b = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
        String dev = device == null ? "device" : device;
        if (b.equals("google")) {
            // Pixel-family: "<codename>-1.2-7683913" — codename IS the device, always coherent.
            return dev.toLowerCase(Locale.ROOT) + "-" + (1 + r.next(3)) + "." + r.next(9) + "-" + digits(r, 7);
        }
        if (b.equals("samsung")) {
            // Samsung firmware code is derived from the actual model (e.g. SM-A013G -> "A013GXXU..").
            String code = dev.replace("SM-", "").toUpperCase(Locale.ROOT);
            return code + "XXU" + (1 + r.next(9)) + (char) ('A' + r.next(26))
                    + (char) ('A' + r.next(26)) + (char) ('A' + r.next(26));
        }
        if (b.equals("motorola")) return "MBM-" + digits(r, 2) + "." + digits(r, 2) + "-" + digits(r, 3);
        if (b.equals("lge"))      return "LGE-" + dev.toUpperCase(Locale.ROOT) + "-" + digits(r, 4);
        // generic OEM: a plausible alnum bootloader that names no specific model.
        return "BL" + (char) ('A' + r.next(26)) + digits(r, 2) + "." + digits(r, 4) + "-" + digits(r, 4);
    }

    // Real Android kernel major.minor lines actually shipped on phones (Linux LTS branches used by
    // Android). Keeping to real branches means the kernel string never looks synthetic.
    static final String[] KERNEL_BASES = {"4.9", "4.14", "4.19", "5.4", "5.10", "5.15"};

    /** os.version / uname kernel string, e.g. "4.14.180-perf-g0a1b2c3". High-entropy fingerprint signal. */
    public static String kernelVersion(Rng r) {
        String base = KERNEL_BASES[r.next(KERNEL_BASES.length)];
        int patch = 50 + r.next(250);
        String tag = (r.next(2) == 0) ? "-perf" : "-android" + (10 + r.next(4));
        return base + "." + patch + tag + "-g" + hexs(r, 4);   // -g + 8 hex = a git-ish suffix
    }

    /** Build.HOST — the build-farm hostname. Real ones look like "abfarm-00902" or "SWDG5305". We
     *  generate a model-agnostic farm-style hostname so it can't leak the real (Google) build host or
     *  imply a specific OEM's infra. */
    public static String buildHost(Rng r) {
        String[] pre = {"abfarm", "wprd", "SWDG", "vf-build", "r-build", "prod"};
        String p = pre[r.next(pre.length)];
        return p + "-" + digits(r, 5);
    }

    // ro.board.platform (the SoC codename DevInfo/FingerprintJS map to a chip name). Real Pixel
    // codenames map to a known SoC; for everything else we pick from a pool of REAL Qualcomm platform
    // names — never a made-up string, so it's always a plausible SoC and never MORE wrong than the
    // real leaked one. Keyed by the picked device where we're confident, else a brand-era-plausible pool.
    static final Map<String, String> SOC_BY_DEVICE = new LinkedHashMap<>();
    static {
        SOC_BY_DEVICE.put("flame", "msmnile");   // Pixel 4  = SD855
        SOC_BY_DEVICE.put("coral", "msmnile");   // Pixel 4 XL
        SOC_BY_DEVICE.put("redfin", "lito");     // Pixel 5  = SD765G
        SOC_BY_DEVICE.put("bramble", "lito");    // Pixel 4a 5G
        SOC_BY_DEVICE.put("sunfish", "sm6150");  // Pixel 4a = SD730G
        SOC_BY_DEVICE.put("barbet", "lito");     // Pixel 5a
        SOC_BY_DEVICE.put("oriole", "gs101");    // Pixel 6  = Tensor
        SOC_BY_DEVICE.put("raven", "gs101");     // Pixel 6 Pro
        SOC_BY_DEVICE.put("blueline", "sdm845"); // Pixel 3  = SD845
        SOC_BY_DEVICE.put("crosshatch", "sdm845"); // Pixel 3 XL
        SOC_BY_DEVICE.put("walleye", "msm8998"); // Pixel 2  = SD835
        SOC_BY_DEVICE.put("sailfish", "msm8996");  // Pixel   = SD821
        SOC_BY_DEVICE.put("marlin", "msm8996");    // Pixel XL
        SOC_BY_DEVICE.put("taimen", "msm8998");    // Pixel 2 XL
        SOC_BY_DEVICE.put("h1", "msm8996");        // LG G5 (product is h1_<region>; matched by prefix) = SD820
        SOC_BY_DEVICE.put("elsa", "msm8996");      // LG V20 (elsa_<region>) = SD820
        SOC_BY_DEVICE.put("joan", "msm8998");      // LG V30 (joan_<region>) = SD835
        // Keys are the Build.PRODUCT codename (lowercase). LG products carry a region suffix
        // (h1_lra_us) matched by the leading token in socPlatform. Marketing names (RS988) are NOT keys.
    }
    // Real Qualcomm platform names (a plausible pool for unmapped devices — all shipped on US phones).
    static final String[] SOC_POOL = {"msmnile", "lito", "sdm845", "msm8998", "msm8996", "sm8250",
            "sm8350", "sm6150", "kona", "lahaina", "trinket", "bengal"};

    /** ro.board.platform (SoC codename). Device-coherent where known, else a real-SoC-pool pick.
     *  Takes the PRODUCT codename (Build.PRODUCT, e.g. "flame", "h1_lra_us") — NOT the marketing device
     *  name (devices.json stores "Pixel 4" in the device slot for Google/LG, so keying on device never
     *  matched). LG products carry a regional suffix (h1_lra_us); match on the leading token before "_". */
    public static String socPlatform(Rng r, String product) {
        if (product != null) {
            String key = product.toLowerCase(Locale.ROOT);
            String known = SOC_BY_DEVICE.get(key);
            if (known != null) return known;
            int us = key.indexOf('_');           // strip LG regional suffix: h1_lra_us -> h1
            if (us > 0) {
                known = SOC_BY_DEVICE.get(key.substring(0, us));
                if (known != null) return known;
            }
        }
        return SOC_POOL[r.next(SOC_POOL.length)];
    }

    // ---------- factory-reset timestamp ----------
    // FPJS Pro reports `factoryReset` as a first-class smart signal, read from the mtime of dirs
    // written once at reset (/data/misc/profiles, /data/bootchart — readable without root). PROVEN
    // 2026-07-25: it re-identified the device across three full identity rotations.
    // Coherent by construction: offset from the running build's security patch, so the reset can
    // never predate the OS it runs. Byte-parity mirror of generators.factory_reset_epoch —
    // RNG order: day offset, then seconds-within-day.
    static final int FACTORY_RESET_MAX_DAYS_AFTER_PATCH = 540;   // ~18 months ownership window
    static final long SECONDS_PER_DAY = 86400L;

    private static long utcMidnight(int y, int m, int d) {
        java.util.Calendar c = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.clear();
        c.set(y, m - 1, d, 0, 0, 0);
        return c.getTimeInMillis() / 1000L;
    }

    /** Unix seconds of a plausible factory reset. Mirrors Python factory_reset_epoch. */
    public static String factoryResetEpoch(Rng r, String securityPatch) {
        long base;
        if (securityPatch != null && securityPatch.length() >= 10) {
            String[] p = securityPatch.split("-");
            base = utcMidnight(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } else {
            base = utcMidnight(2023, 1, 1);
        }
        // No wall-clock read — a pure function of (r, securityPatch) so this stays byte-identical to
        // the Python generator. "Never in the future" is guaranteed by the pool's patches staying
        // older than ~18 months, enforced by test_factory_reset_is_after_the_build_and_in_the_past.
        long days = 1 + r.next(FACTORY_RESET_MAX_DAYS_AFTER_PATCH);
        long secs = r.next((int) SECONDS_PER_DAY);
        return Long.toString(base + days * SECONDS_PER_DAY + secs);
    }

    // Baseband/radio version prefixes by SoC vendor — real basebands look like "g8150-00088-210507-B..."
    // (Qualcomm) or "M8998-2010..." Keeping a realistic vendor prefix avoids a synthetic-looking radio.
    static final String[] RADIO_PREFIXES = {"g8150", "g7250", "g6150", "M8998", "M8250", "MPSS.HI"};

    /** Build.getRadioVersion() / Build.RADIO — SoC-plausible baseband string. Confirmed FP leak. */
    public static String radioVersion(Rng r) {
        String pre = RADIO_PREFIXES[r.next(RADIO_PREFIXES.length)];
        // e.g. "g8150-00088-210507-B-7345963"
        return pre + "-" + digits(r, 5) + "-" + digits(r, 6) + "-"
                + (char) ('A' + r.next(6)) + "-" + digits(r, 7);
    }

    // Realistic Android RAM tiers (nominal GB). Reported totalMem is ~3-8% below nominal (kernel/
    // reserved), so we model that so the value looks like a real ActivityManager reading.
    static final int[] RAM_GB = {3, 4, 6, 8, 12};
    static final int[] STORAGE_GB = {32, 64, 128, 256};

    // Storage capacities that plausibly ship with each RAM tier — a 12GB flagship is never 32GB, a
    // 3GB budget phone is never 512GB. Index-aligned to RAM_GB. Coherence matters: an incoherent
    // RAM+storage combo is itself a fingerprint, so storage is derived from the chosen RAM tier, not
    // drawn independently. (Fixes the old independent draw that could pair 12GB RAM with 32GB storage.)
    static final int[][] STORAGE_FOR_RAM = {
        {32, 64},        // 3GB
        {32, 64, 128},   // 4GB
        {64, 128, 256},  // 6GB
        {128, 256},      // 8GB
        {128, 256, 512}, // 12GB
    };

    /**
     * RAM+storage as one coherent pair (both in BYTES), returned as {ramBytes, storageBytes}.
     * Draws the RAM tier ONCE and picks a storage capacity that ships with it. RNG order:
     * ram-tier idx, ram-shave, storage-capacity idx, storage-fill — same total draws on both sides.
     */
    public static String[] ramStorageBytes(Rng r) {
        int ramIdx = r.next(RAM_GB.length);
        long ramGb = RAM_GB[ramIdx];
        long ramNominal = ramGb * 1024L * 1024L * 1024L;
        // reported totalMem is a bit under nominal — shave 3-8%, then round to a MB boundary.
        long ramReported = ramNominal - (ramNominal * (3 + r.next(6)) / 100);
        String ram = String.valueOf((ramReported / (1024L * 1024L)) * 1024L * 1024L);

        int[] pool = STORAGE_FOR_RAM[ramIdx];
        long stGb = pool[r.next(pool.length)];
        // usable storage is ~90-94% of nominal after formatting/system.
        long stNominal = stGb * 1000L * 1000L * 1000L;   // storage is marketed in decimal GB
        String storage = String.valueOf(stNominal * (90 + r.next(5)) / 100);
        return new String[]{ram, storage};
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

    /** Phone by country kind. USA-only build: always NANP. (kept for the Profile call signature) */
    public static String phoneForCountry(Rng r, String kind) {
        return phoneUs(r);
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
        ICCID_IIN.put("310004", "89148000"); // Verizon
        ICCID_IIN.put("310090", "89014104"); // AT&T
        ICCID_IIN.put("312530", "89011201"); // Sprint
        ICCID_IIN.put("311882", "89014103"); // Mint Mobile (T-Mobile MVNO)
        ICCID_IIN.put("310240", "89014103"); // T-Mobile
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

    // Realistic email building blocks. Small curated lists — enough variety to look human without
    // a big bundled corpus. Providers weighted toward gmail via repetition.
    static final String[] FIRST_NAMES = {
        "james","john","robert","michael","david","william","richard","joseph","thomas","charles",
        "mary","patricia","jennifer","linda","elizabeth","susan","jessica","sarah","karen","emily",
        "daniel","matthew","anthony","mark","paul","steven","andrew","joshua","kevin","brian",
        "amanda","ashley","stephanie","nicole","laura","megan","hannah","olivia","emma","sophia",
        "chris","ryan","jacob","tyler","aaron","nathan","adam","justin","brandon","sean",
        "rachel","lauren","victoria","natalie","grace","chloe","zoe","ella","lily","mia",
    };
    static final String[] LAST_NAMES = {
        "smith","johnson","williams","brown","jones","garcia","miller","davis","rodriguez","martinez",
        "hernandez","lopez","gonzalez","wilson","anderson","thomas","taylor","moore","jackson","martin",
        "lee","perez","thompson","white","harris","sanchez","clark","ramirez","lewis","robinson",
        "walker","young","allen","king","wright","scott","torres","nguyen","hill","flores",
        "green","adams","nelson","baker","hall","rivera","campbell","mitchell","carter","roberts",
    };
    static final String[] EMAIL_PROVIDERS = {
        "gmail.com","gmail.com","gmail.com","outlook.com","outlook.com","yahoo.com","hotmail.com","icloud.com",
    };

    /** Realistic-looking email: first/last name in a common pattern + provider. Key stays "gmail". */
    public static String gmail(Rng r) {
        String first = FIRST_NAMES[r.next(FIRST_NAMES.length)];
        String last = LAST_NAMES[r.next(LAST_NAMES.length)];
        String provider = EMAIL_PROVIDERS[r.next(EMAIL_PROVIDERS.length)];
        int pattern = r.next(6);
        String local;
        switch (pattern) {
            case 0: local = first + "." + last; break;
            case 1: local = first + last; break;
            case 2: local = first + "_" + last; break;
            case 3: local = first + last.charAt(0); break;               // firstl
            case 4: local = first + "." + last + digits(r, 2); break;    // first.lastNN
            default: local = first + last + (1970 + r.next(40)); break;  // firstlastYYYY
        }
        return local + "@" + provider;
    }

    /** Alias — the field is conceptually "email"; kept for clarity at call sites. */
    public static String email(Rng r) { return gmail(r); }

    public static String ssid(Rng r) {
        String[] nets = {"NETGEAR", "ATT", "xfinitywifi", "Linksys", "TP-Link_", "SpectrumSetup-"};
        return nets[r.next(nets.length)] + digits(r, 2);
    }

    // ---------- validators ----------
    private static final Pattern P_ANDROID_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern P_SERIAL     = Pattern.compile("[0-9A-HJ-NP-Z]{11,15}"); // Base34, brand-plausible
    private static final Pattern P_MEDIA_DRM  = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern P_ADV        = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern P_MAC_UP     = Pattern.compile("([0-9A-F]{2}:){5}[0-9A-F]{2}");
    private static final Pattern P_MAC_LOW    = Pattern.compile("([0-9a-f]{2}:){5}[0-9a-f]{2}");
    // US NANP only: 1 + area[2-9]XX + exchange[2-9]XX + 4 digits. E.164 digits, no leading +.
    private static final Pattern P_PHONE      = Pattern.compile("1[2-9]\\d{2}[2-9]\\d{6}");
    // Realistic email: local part (letters/digits/./_/-) + one of the supported providers.
    private static final Pattern P_EMAIL      = Pattern.compile(
            "[a-z0-9]([a-z0-9._-]{0,30}[a-z0-9])?@(gmail\\.com|outlook\\.com|yahoo\\.com|hotmail\\.com|proton\\.me|icloud\\.com)");

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
            case "gmail":               return P_EMAIL.matcher(value).matches();
            default:                    return true;
        }
    }

    private static boolean parsePositiveLong(String v) {
        try { long n = Long.parseLong(v); return n > 0 && n <= LONG_MAX; }
        catch (NumberFormatException e) { return false; }
    }
}
