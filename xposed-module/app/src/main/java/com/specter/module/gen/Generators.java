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
        String b = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
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
        TAC_BY_BRAND.put("motorola", new String[]{"35462106", "35404007"});   // dropped "35123456" (filler)
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

    /** os.version / uname kernel string, e.g. "4.14.180-perf-g0a1b2c3". High-entropy fingerprint signal.
     *  The "-androidN" branch tag must be COHERENT with the OS — a kernel can't be branched for a NEWER
     *  Android than the one running it. Keep the exact RNG draw order (base, patch, branch, tag-num, hex)
     *  for byte-parity with Python, then CLAMP the drawn tag to {@code release}; release &lt; 10 (no
     *  -androidN tag) falls back to "-perf" (common on Android &lt;= 9). */
    public static String kernelVersion(Rng r, String release) {
        String base = KERNEL_BASES[r.next(KERNEL_BASES.length)];
        int patch = 50 + r.next(250);
        int branch = r.next(2);          // 0 => -perf, 1 => -androidN
        int tagnum = 10 + r.next(4);     // 10..13 (draw consumed regardless, for parity)
        int rel;
        try { rel = Integer.parseInt(release.trim().split("\\.")[0]); }
        catch (Exception e) { rel = 13; }
        String tag = (branch == 0 || rel < 10) ? "-perf" : "-android" + Math.min(tagnum, rel);
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
        SOC_BY_DEVICE.put("sunfish", "sm7150");  // Pixel 4a = SD730G (sm7150, Adreno 618)
        // Corrected SoCs (2026-07-28 dataset audit, kernel-DT/teardown grounded) — were mislabelled to the
        // sm6150 default. gpu_model is derived from the per-model renderer (Profile.socTopologyFields override)
        // so the lito-shared kiev(619)/nairo(620) split stays coherent.
        SOC_BY_DEVICE.put("a71naxx", "sm7150");  // Galaxy A71 (SD730, Adreno 618)
        SOC_BY_DEVICE.put("bonito", "sdm670");   // Pixel 3a XL (SD670, Adreno 615)
        SOC_BY_DEVICE.put("sargo", "sdm670");    // Pixel 3a (SD670, Adreno 615)
        SOC_BY_DEVICE.put("kiev", "lito");       // Moto G 5G (SD750G, Adreno 619)
        SOC_BY_DEVICE.put("nairo", "lito");      // Moto One 5G (SD765G, Adreno 620)
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
    // Draw-free default SoC — a real mid-range Snapdragon. Used only when neither the hardware bundle
    // nor the known-Pixel table has an entry (a non-selectable device, which generated profiles never
    // pick). Mirror of generators._DEFAULT_SOC.
    static final String DEFAULT_SOC = "sm6150";

    /** ro.board.platform (SoC codename), COHERENT with the device this identity claims to be. Mirror of
     *  generators.soc_platform. Prefers {@code hwSoc} — the SoC of the per-model hardware bundle
     *  (data/hardware.json) — so the reported SoC matches the GPU/cpuinfo the same profile carries; else
     *  the known-Pixel table keyed on the PRODUCT codename (Build.PRODUCT, e.g. "flame", "h1_lra_us"),
     *  then a fixed default. PURE (no RNG): a real SoC is a fact of the model, not a random draw — the
     *  old random fallback produced INCOHERENT SoCs. Draw-free also keeps byte-parity trivially. */
    public static String socPlatform(String product, String hwSoc) {
        if (hwSoc != null && !hwSoc.isEmpty()) return hwSoc;
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
        return DEFAULT_SOC;
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
    // Android release -> API level (Build.VERSION.SDK_INT / ro.build.version.sdk). Mirrors
    // generators.sdk_for_release: a profile's release and SDK must agree (Android 11 -> 30, 12 -> 31) —
    // a mismatch is itself a fingerprint. Pure, no RNG.
    private static final Map<String, Integer> SDK_BY_RELEASE = new java.util.HashMap<>();
    static {
        SDK_BY_RELEASE.put("15", 35); SDK_BY_RELEASE.put("14", 34); SDK_BY_RELEASE.put("13", 33);
        SDK_BY_RELEASE.put("12L", 32); SDK_BY_RELEASE.put("12", 31); SDK_BY_RELEASE.put("11", 30);
        SDK_BY_RELEASE.put("10", 29); SDK_BY_RELEASE.put("9", 28); SDK_BY_RELEASE.put("8.1.0", 27);
        SDK_BY_RELEASE.put("8.1", 27); SDK_BY_RELEASE.put("8.0.0", 26); SDK_BY_RELEASE.put("8.0", 26);
        SDK_BY_RELEASE.put("7.1.2", 25); SDK_BY_RELEASE.put("7.1.1", 25); SDK_BY_RELEASE.put("7.1", 25);
        SDK_BY_RELEASE.put("7.0", 24); SDK_BY_RELEASE.put("6.0.1", 23); SDK_BY_RELEASE.put("6.0", 23);
        SDK_BY_RELEASE.put("5.1.1", 22); SDK_BY_RELEASE.put("5.1", 22); SDK_BY_RELEASE.put("5.0.1", 21);
        SDK_BY_RELEASE.put("5.0.2", 21); SDK_BY_RELEASE.put("5.0", 21);
        SDK_BY_RELEASE.put("4.4.4", 19); SDK_BY_RELEASE.put("4.4.2", 19); SDK_BY_RELEASE.put("4.4", 19);
        SDK_BY_RELEASE.put("4.3", 18); SDK_BY_RELEASE.put("4.2.2", 17); SDK_BY_RELEASE.put("4.2", 17);
    }

    /** GPU driver family (adreno/mali/powervr) behind ro.hardware.{egl,vulkan,gralloc}, from the GL renderer.
     *  Mirrors profile.py gpu_hw_for — coherent with the claimed device's GPU (Mali renderer -> mali). Falls
     *  back to adreno (the common US Qualcomm case). Pure lookup -> byte-parity safe. */
    public static String gpuHwFor(String renderer) {
        String r = renderer == null ? "" : renderer.toLowerCase(java.util.Locale.US);
        if (r.contains("mali")) return "mali";
        if (r.contains("adreno")) return "adreno";
        if (r.contains("powervr")) return "powervr";
        return "adreno";
    }

    public static int sdkForRelease(String release) {
        if (release == null || release.isEmpty()) return 30;
        Integer v = SDK_BY_RELEASE.get(release);
        if (v != null) return v;
        Integer m = SDK_BY_RELEASE.get(release.split("\\.")[0]);
        return m != null ? m : 30;
    }

    // Real LAUNCH API level per model (Build.MODEL) — the Android the device SHIPPED with. MUST stay
    // byte-identical to generators._LAUNCH_API_BY_MODEL. See that map for sourcing/notes. Missing model ->
    // launchApiFor falls back to the current sdk (first_api==sdk, prior behaviour).
    private static final Map<String, Integer> LAUNCH_API_BY_MODEL = new java.util.HashMap<>();
    static {
        LAUNCH_API_BY_MODEL.put("SM-A013G", 29); LAUNCH_API_BY_MODEL.put("SM-A205W", 28);
        LAUNCH_API_BY_MODEL.put("SM-A405FN", 28); LAUNCH_API_BY_MODEL.put("SM-A505F", 28);
        LAUNCH_API_BY_MODEL.put("SM-A507FN", 28); LAUNCH_API_BY_MODEL.put("SM-A515F", 29);
        LAUNCH_API_BY_MODEL.put("SM-A525F", 30); LAUNCH_API_BY_MODEL.put("SM-A600F", 26);
        LAUNCH_API_BY_MODEL.put("SM-A605G", 26); LAUNCH_API_BY_MODEL.put("SM-A705FN", 28);
        LAUNCH_API_BY_MODEL.put("SM-A715F", 29); LAUNCH_API_BY_MODEL.put("SM-A750GN", 26);
        LAUNCH_API_BY_MODEL.put("SM-G970F", 28); LAUNCH_API_BY_MODEL.put("SM-G973F", 28);
        LAUNCH_API_BY_MODEL.put("SM-G975F", 28); LAUNCH_API_BY_MODEL.put("SM-G977B", 28);
        LAUNCH_API_BY_MODEL.put("SM-G960F", 26); LAUNCH_API_BY_MODEL.put("SM-G965F", 26);
        LAUNCH_API_BY_MODEL.put("SM-G950F", 24); LAUNCH_API_BY_MODEL.put("SM-G955F", 24);
        LAUNCH_API_BY_MODEL.put("SM-N960F", 27); LAUNCH_API_BY_MODEL.put("SM-N950F", 25);
        LAUNCH_API_BY_MODEL.put("SM-N975F", 28); LAUNCH_API_BY_MODEL.put("SM-N986B", 29);
        LAUNCH_API_BY_MODEL.put("SM-G770F", 29); LAUNCH_API_BY_MODEL.put("SM-G780F", 29);
        LAUNCH_API_BY_MODEL.put("SM-G781B", 29); LAUNCH_API_BY_MODEL.put("SM-G991B", 30);
        LAUNCH_API_BY_MODEL.put("SM-G988B", 29); LAUNCH_API_BY_MODEL.put("SM-M205F", 27);
        LAUNCH_API_BY_MODEL.put("SM-M215F", 29);
        // US-carrier Samsung flagships/mid (2026-07-31). Launch API = the Android the model SHIPPED with:
        // S20 (Feb 2020)=29 · S21 + S21 FE=30 · S22 (Feb 2022)=31 · S23 (Feb 2023)=33 · A52 5G=30 ·
        // A53 5G=31 · A13 5G=31. MUST stay identical to generators.py.
        LAUNCH_API_BY_MODEL.put("SM-G981U", 29); LAUNCH_API_BY_MODEL.put("SM-G986U", 29);
        LAUNCH_API_BY_MODEL.put("SM-G988U", 29); LAUNCH_API_BY_MODEL.put("SM-G991U", 30);
        LAUNCH_API_BY_MODEL.put("SM-G996U", 30); LAUNCH_API_BY_MODEL.put("SM-G998U", 30);
        LAUNCH_API_BY_MODEL.put("SM-G990U", 30); LAUNCH_API_BY_MODEL.put("SM-S901U", 31);
        LAUNCH_API_BY_MODEL.put("SM-S906U", 31); LAUNCH_API_BY_MODEL.put("SM-S908U", 31);
        LAUNCH_API_BY_MODEL.put("SM-S918U", 33); LAUNCH_API_BY_MODEL.put("SM-A526U", 30);
        LAUNCH_API_BY_MODEL.put("SM-A536U", 31); LAUNCH_API_BY_MODEL.put("SM-A136U", 31);
        // Xiaomi/Redmi/POCO, Motorola, OnePlus (2026-07-28, GSMArena-sourced, launch<current only). MIUI traps
        // handled (Mi A1=25, POCOPHONE F1=27, Redmi Note 5 Pro=25). MUST stay identical to generators.py.
        LAUNCH_API_BY_MODEL.put("GM1900", 28); LAUNCH_API_BY_MODEL.put("GM1910", 28);
        LAUNCH_API_BY_MODEL.put("MI 9", 28); LAUNCH_API_BY_MODEL.put("Mi 8 Explorer", 27);
        LAUNCH_API_BY_MODEL.put("Mi 8 Pro", 27); LAUNCH_API_BY_MODEL.put("Mi 9T", 28);
        LAUNCH_API_BY_MODEL.put("Mi 9T Pro", 28); LAUNCH_API_BY_MODEL.put("Mi MIX 2", 25);
        LAUNCH_API_BY_MODEL.put("Mi MIX 2S", 26); LAUNCH_API_BY_MODEL.put("Moto G (4)", 23);
        LAUNCH_API_BY_MODEL.put("Moto G (5S) Plus", 25); LAUNCH_API_BY_MODEL.put("Moto Z2", 25);
        LAUNCH_API_BY_MODEL.put("Moto Z2 Play", 25); LAUNCH_API_BY_MODEL.put("Moto Z3 Play", 27);
        LAUNCH_API_BY_MODEL.put("ONEPLUS A3000", 23); LAUNCH_API_BY_MODEL.put("ONEPLUS A3003", 23);
        LAUNCH_API_BY_MODEL.put("ONEPLUS A5000", 25); LAUNCH_API_BY_MODEL.put("POCOPHONE F1", 27);
        LAUNCH_API_BY_MODEL.put("Redmi 6", 27); LAUNCH_API_BY_MODEL.put("Redmi 6A", 27);
        LAUNCH_API_BY_MODEL.put("Redmi 7", 28); LAUNCH_API_BY_MODEL.put("Redmi K20", 28);
        LAUNCH_API_BY_MODEL.put("Redmi K20 Pro", 28); LAUNCH_API_BY_MODEL.put("Redmi Note 5 Pro", 25);
        LAUNCH_API_BY_MODEL.put("Redmi Note 8 Pro", 28); LAUNCH_API_BY_MODEL.put("moto g pro", 29);
        LAUNCH_API_BY_MODEL.put("moto g(6)", 26); LAUNCH_API_BY_MODEL.put("moto g(6) plus", 26);
        LAUNCH_API_BY_MODEL.put("moto g(7)", 28); LAUNCH_API_BY_MODEL.put("moto x4", 25);
    }

    public static int launchApiFor(String model, int currentSdk) {
        Integer la = LAUNCH_API_BY_MODEL.get(model);
        if (la == null || la > currentSdk) return currentSdk;
        return la;
    }

    // Screen (width,height,densityDpi) — mirrors generators.screen_for_device. Known models use their
    // real spec; unknown codenames map deterministically into a pool via codenameHash (== Python
    // _codename_hash). Pure, no RNG (byte-parity safe).
    private static final Map<String, int[]> SCREEN_KNOWN = new java.util.HashMap<>();
    static {
        SCREEN_KNOWN.put("flame", new int[]{1080, 2280, 440}); SCREEN_KNOWN.put("coral", new int[]{1440, 3040, 560});
        SCREEN_KNOWN.put("redfin", new int[]{1080, 2340, 440}); SCREEN_KNOWN.put("bramble", new int[]{1080, 2400, 400});
        SCREEN_KNOWN.put("sunfish", new int[]{1080, 2340, 440}); SCREEN_KNOWN.put("barbet", new int[]{1080, 2400, 400});
        SCREEN_KNOWN.put("oriole", new int[]{1080, 2400, 420}); SCREEN_KNOWN.put("raven", new int[]{1440, 3120, 560});
        SCREEN_KNOWN.put("blueline", new int[]{1080, 2160, 440}); SCREEN_KNOWN.put("crosshatch", new int[]{1440, 2960, 560});
        SCREEN_KNOWN.put("sargo", new int[]{1080, 2220, 440}); SCREEN_KNOWN.put("bonito", new int[]{1080, 2160, 400});
        SCREEN_KNOWN.put("walleye", new int[]{1080, 1920, 420}); SCREEN_KNOWN.put("taimen", new int[]{1440, 2880, 560});
        SCREEN_KNOWN.put("beyond1", new int[]{1440, 3040, 550}); SCREEN_KNOWN.put("beyond2", new int[]{1440, 3040, 526});
        SCREEN_KNOWN.put("beyond0", new int[]{1080, 2280, 438}); SCREEN_KNOWN.put("o1s", new int[]{1080, 2400, 421});
        SCREEN_KNOWN.put("t2s", new int[]{1080, 2400, 425}); SCREEN_KNOWN.put("p3s", new int[]{1440, 3200, 515});
        SCREEN_KNOWN.put("a50", new int[]{1080, 2340, 403}); SCREEN_KNOWN.put("a50s", new int[]{1080, 2340, 403});
        SCREEN_KNOWN.put("a70q", new int[]{1080, 2400, 393}); SCREEN_KNOWN.put("a30s", new int[]{720, 1560, 268});
        SCREEN_KNOWN.put("a10", new int[]{720, 1520, 269}); SCREEN_KNOWN.put("a20", new int[]{720, 1560, 294});
        SCREEN_KNOWN.put("m21", new int[]{1080, 2340, 411}); SCREEN_KNOWN.put("a51", new int[]{1080, 2400, 405});
        SCREEN_KNOWN.put("a71", new int[]{1080, 2400, 393});
    }
    private static final int[][] SCREEN_POOL = {
        {1080, 2340, 440}, {1080, 2400, 408}, {1080, 2280, 440}, {1080, 2340, 403},
        {720, 1520, 295}, {720, 1560, 269}, {1080, 2160, 424}, {1440, 3040, 550},
        {1080, 2400, 395}, {1080, 1920, 401},
    };

    /** FNV-1a 32-bit over the codename, kept positive. MUST match Python _codename_hash. */
    static long codenameHash(String cn) {
        long h = 2166136261L;
        for (int i = 0; i < cn.length(); i++) {
            h = (h ^ cn.charAt(i)) * 16777619L;
            h &= 0xFFFFFFFFL;
        }
        return h;
    }

    public static int[] screenForDevice(String codename) {
        String cn = codename == null ? "" : codename.toLowerCase(Locale.ROOT);
        int[] k = SCREEN_KNOWN.get(cn);
        if (k != null) return k;
        if (cn.isEmpty()) return SCREEN_POOL[0];
        return SCREEN_POOL[(int) (codenameHash(cn) % SCREEN_POOL.length)];
    }

    /** A plausible, per-device-STABLE boot count (Settings.Global.BOOT_COUNT) derived from the android_id.
     *  A real used phone has booted tens-to-hundreds of times. Pure, no RNG. MUST match Python
     *  Generators.boot_count_for (byte-parity). */
    public static int bootCountFor(String androidId) {
        return 40 + (int) (codenameHash(androidId == null ? "" : androidId) % 420L);
    }

    // Real battery DESIGN capacity (mAh) per pool model, longest-prefix on codename. MUST match Python
    // _BATTERY_MAH_FOR_MODEL. Unmapped codenames fall back to the codename hash.
    static final java.util.Map<String, Integer> BATTERY_MAH_FOR_MODEL = new java.util.HashMap<>();
    static {
        BATTERY_MAH_FOR_MODEL.put("bramble", 3885); BATTERY_MAH_FOR_MODEL.put("redfin", 4080);
        BATTERY_MAH_FOR_MODEL.put("barbet", 4680);  BATTERY_MAH_FOR_MODEL.put("sofiap", 4000);
        BATTERY_MAH_FOR_MODEL.put("mh2lm", 3500);   BATTERY_MAH_FOR_MODEL.put("t2q", 4800);
        BATTERY_MAH_FOR_MODEL.put("flame", 2800);   BATTERY_MAH_FOR_MODEL.put("coral", 3700);
        BATTERY_MAH_FOR_MODEL.put("sunfish", 3140);
        BATTERY_MAH_FOR_MODEL.put("oriole", 4614);  BATTERY_MAH_FOR_MODEL.put("raven", 5003);
        BATTERY_MAH_FOR_MODEL.put("o1s", 4000);     BATTERY_MAH_FOR_MODEL.put("p3q", 5000);
        BATTERY_MAH_FOR_MODEL.put("r9q", 4500);     BATTERY_MAH_FOR_MODEL.put("r0q", 3700);
        BATTERY_MAH_FOR_MODEL.put("b0q", 5000);     BATTERY_MAH_FOR_MODEL.put("sargo", 3000);
        BATTERY_MAH_FOR_MODEL.put("bonito", 3700);  BATTERY_MAH_FOR_MODEL.put("blueline", 2915);
        BATTERY_MAH_FOR_MODEL.put("crosshatch", 3430);
    }

    /** Battery DESIGN capacity in µAh — the real per-model value when the codename is a known pool model,
     *  else a stable hash-derived plausible value. Pure, no RNG. MUST match Python battery_uah_for. */
    public static long batteryUahFor(String codename) {
        String cn = codename == null ? "" : codename.toLowerCase(Locale.ROOT);
        String best = null;
        for (String stem : BATTERY_MAH_FOR_MODEL.keySet())
            if (cn.startsWith(stem) && (best == null || stem.length() > best.length())) best = stem;
        long mah = best != null ? BATTERY_MAH_FOR_MODEL.get(best) : 2800 + (codenameHash(cn) % 19) * 100L;
        return mah * 1000L;   // -> µAh
    }

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

    // Real modem prefix per SoC — each SoC has ONE real modem family, so the baseband must be keyed on the
    // SoC, not drawn at random (the old code contradicted the silicon ~5/6 of the time). MUST match Python
    // _RADIO_PREFIX_BY_SOC. Unknown SoC -> generic-modern g7250.
    static final java.util.Map<String, String> RADIO_PREFIX_BY_SOC = new java.util.HashMap<>();
    static final String RADIO_DEFAULT_PREFIX = "g7250";
    static {
        RADIO_PREFIX_BY_SOC.put("msmnile", "g8150"); RADIO_PREFIX_BY_SOC.put("sdm855", "g8150");
        RADIO_PREFIX_BY_SOC.put("kona", "g7250");
        RADIO_PREFIX_BY_SOC.put("lahaina", "g8350"); RADIO_PREFIX_BY_SOC.put("sm7150", "g7250");
        RADIO_PREFIX_BY_SOC.put("lito", "g7250");    RADIO_PREFIX_BY_SOC.put("sm6150", "g7150");
        RADIO_PREFIX_BY_SOC.put("sdm845", "M8998");  RADIO_PREFIX_BY_SOC.put("msm8998", "M8998");
        RADIO_PREFIX_BY_SOC.put("sdm670", "g6150");  RADIO_PREFIX_BY_SOC.put("sdm660", "M8998");
        RADIO_PREFIX_BY_SOC.put("sdm665", "g7150");  RADIO_PREFIX_BY_SOC.put("trinket", "g7150");
        RADIO_PREFIX_BY_SOC.put("bengal", "g7150");  RADIO_PREFIX_BY_SOC.put("taro", "g8450");
        RADIO_PREFIX_BY_SOC.put("kalama", "g8550");  RADIO_PREFIX_BY_SOC.put("gs101", "g5123b");
        RADIO_PREFIX_BY_SOC.put("exynos9820", "g8090"); RADIO_PREFIX_BY_SOC.put("exynos9825", "g8090");
        RADIO_PREFIX_BY_SOC.put("exynos990", "g5123");  RADIO_PREFIX_BY_SOC.put("exynos2100", "g5123");
        RADIO_PREFIX_BY_SOC.put("exynos9610", "m8090"); RADIO_PREFIX_BY_SOC.put("exynos9611", "m8090");
        RADIO_PREFIX_BY_SOC.put("exynos1280", "g5300"); RADIO_PREFIX_BY_SOC.put("exynos7884", "m7570");
        RADIO_PREFIX_BY_SOC.put("exynos7885", "m7570"); RADIO_PREFIX_BY_SOC.put("exynos7904", "m7570");
        RADIO_PREFIX_BY_SOC.put("exynos7870", "m7570"); RADIO_PREFIX_BY_SOC.put("exynos850", "m7570");
        RADIO_PREFIX_BY_SOC.put("exynos9810", "g8090");
    }

    /** Build.getRadioVersion() / Build.RADIO — SoC-coherent baseband string. Confirmed FP leak. The old
     *  prefix-selection draw is kept (now discarded) so downstream fields are byte-identical. */
    public static String radioVersion(Rng r, String soc) {
        r.next(RADIO_PREFIXES.length);   // keep the draw for byte-parity; prefix is now SoC-derived
        String pre = RADIO_PREFIX_BY_SOC.getOrDefault(soc == null ? "" : soc, RADIO_DEFAULT_PREFIX);
        // e.g. "g8150-00088-210507-B-7345963"
        return pre + "-" + digits(r, 5) + "-" + digits(r, 6) + "-"
                + (char) ('A' + r.next(6)) + "-" + digits(r, 7);
    }

    // Realistic Android RAM tiers (nominal GB). Reported totalMem is ~3-8% below nominal (kernel/
    // reserved), so we model that so the value looks like a real ActivityManager reading.
    static final int[] RAM_GB = {2, 3, 4, 6, 8, 12};   // index 0 = 2GB (budget)
    static final int[] STORAGE_GB = {32, 64, 128, 256};

    // Storage capacities that plausibly ship with each RAM tier — a 12GB flagship is never 32GB, a
    // 3GB budget phone is never 512GB. Index-aligned to RAM_GB. Coherence matters: an incoherent
    // RAM+storage combo is itself a fingerprint, so storage is derived from the chosen RAM tier, not
    // drawn independently. (Fixes the old independent draw that could pair 12GB RAM with 32GB storage.)
    static final int[][] STORAGE_FOR_RAM = {
        {16, 32},        // 2GB
        {32, 64},        // 3GB
        {32, 64, 128},   // 4GB
        {64, 128, 256},  // 6GB
        {128, 256},      // 8GB
        {128, 256, 512}, // 12GB
    };

    // RAM tier INDICES (into RAM_GB) realistic for each SoC — keying RAM off the SoC kills the biggest
    // hardware tell: a totalMem that contradicts the device (e.g. an 8GB moto g7 play, a 2.8GB Pixel 6).
    // MUST stay byte-identical to Python _RAM_IDX_FOR_SOC. Unknown SoC -> RAM_IDX_DEFAULT (3/4/6GB).
    static final java.util.Map<String, int[]> RAM_IDX_FOR_SOC = new java.util.HashMap<>();
    static final int[] RAM_IDX_DEFAULT = {1, 2, 3};   // 3/4/6 GB
    static {
        RAM_IDX_FOR_SOC.put("exynos9820", new int[]{3, 4, 5}); RAM_IDX_FOR_SOC.put("msmnile", new int[]{3, 4, 5});
        RAM_IDX_FOR_SOC.put("exynos990", new int[]{4, 5});     RAM_IDX_FOR_SOC.put("exynos9825", new int[]{4, 5});
        RAM_IDX_FOR_SOC.put("kona", new int[]{4, 5});          RAM_IDX_FOR_SOC.put("exynos2100", new int[]{4, 5});
        RAM_IDX_FOR_SOC.put("lahaina", new int[]{4, 5});       RAM_IDX_FOR_SOC.put("sdm855", new int[]{3, 4, 5});
        RAM_IDX_FOR_SOC.put("taro", new int[]{4, 5});          RAM_IDX_FOR_SOC.put("kalama", new int[]{4, 5});
        RAM_IDX_FOR_SOC.put("exynos9810", new int[]{3, 4});    RAM_IDX_FOR_SOC.put("msm8998", new int[]{2, 3, 4});
        RAM_IDX_FOR_SOC.put("sdm845", new int[]{2, 3, 4});     RAM_IDX_FOR_SOC.put("sdm670", new int[]{1, 2, 3});
        RAM_IDX_FOR_SOC.put("sm6150", new int[]{2, 3, 4});     RAM_IDX_FOR_SOC.put("sm7150", new int[]{2, 3, 4});
        RAM_IDX_FOR_SOC.put("lito", new int[]{2, 3, 4});
        RAM_IDX_FOR_SOC.put("gs101", new int[]{4});            RAM_IDX_FOR_SOC.put("exynos9610", new int[]{2, 3});
        RAM_IDX_FOR_SOC.put("sdm660", new int[]{1, 2, 3});     RAM_IDX_FOR_SOC.put("exynos7904", new int[]{1, 2, 3});
        RAM_IDX_FOR_SOC.put("exynos9611", new int[]{1, 2, 3}); RAM_IDX_FOR_SOC.put("exynos1280", new int[]{2, 3});
        RAM_IDX_FOR_SOC.put("trinket", new int[]{0, 1, 2});    RAM_IDX_FOR_SOC.put("bengal", new int[]{0, 1, 2});
        RAM_IDX_FOR_SOC.put("exynos850", new int[]{0, 1, 2});  RAM_IDX_FOR_SOC.put("exynos7884", new int[]{0, 1, 2});
        RAM_IDX_FOR_SOC.put("exynos7885", new int[]{0, 1, 2}); RAM_IDX_FOR_SOC.put("exynos7870", new int[]{0, 1});
        RAM_IDX_FOR_SOC.put("sdm665", new int[]{1, 2, 3});
    }

    // Per-MODEL RAM index override (into RAM_GB). One SoC serves many SKUs, so the SoC map is a 2-3-wide
    // spread and ~72% of profiles claimed a RAM size the specific MODEL never shipped. Keyed on the
    // product-stripped codename, this pins each real US-pool model to its true SKU. Checked BEFORE the SoC
    // map. MUST stay byte-identical to Python _RAM_IDX_FOR_MODEL.
    static final java.util.Map<String, int[]> RAM_IDX_FOR_MODEL = new java.util.HashMap<>();
    static {
        RAM_IDX_FOR_MODEL.put("bramble", new int[]{3});  RAM_IDX_FOR_MODEL.put("redfin", new int[]{4});
        RAM_IDX_FOR_MODEL.put("barbet", new int[]{3});   RAM_IDX_FOR_MODEL.put("sofiap", new int[]{2});
        RAM_IDX_FOR_MODEL.put("mh2lm", new int[]{3});    RAM_IDX_FOR_MODEL.put("t2q", new int[]{4});
        RAM_IDX_FOR_MODEL.put("o1s", new int[]{4});      RAM_IDX_FOR_MODEL.put("p3q", new int[]{4, 5});
        RAM_IDX_FOR_MODEL.put("r9q", new int[]{3, 4});
        RAM_IDX_FOR_MODEL.put("flame", new int[]{3});    RAM_IDX_FOR_MODEL.put("coral", new int[]{3});
        RAM_IDX_FOR_MODEL.put("oriole", new int[]{4});   RAM_IDX_FOR_MODEL.put("raven", new int[]{5});
    }

    /**
     * RAM+storage as one coherent pair (both in BYTES), returned as {ramBytes, storageBytes}.
     * The RAM tier is constrained to what the MODEL (preferred) or its {@code soc} realistically ships with.
     * RNG order: ram-tier idx (against the model/SoC subset), ram-shave, storage-capacity idx, storage-fill.
     */
    /** RAM index set for a device by LONGEST-prefix match against RAM_IDX_FOR_MODEL, or null. Pool codenames
     *  carry variant suffixes (t2qsqw) while keys are clean stems (t2q); exact match misses. MUST match
     *  Python _ram_idx_for_model. */
    static int[] ramIdxForModel(String codename) {
        String cn = codename == null ? "" : codename.toLowerCase(Locale.ROOT);
        String best = null;
        for (String stem : RAM_IDX_FOR_MODEL.keySet())
            if (cn.startsWith(stem) && (best == null || stem.length() > best.length())) best = stem;
        return best != null ? RAM_IDX_FOR_MODEL.get(best) : null;
    }

    public static String[] ramStorageBytes(Rng r, String soc, String codename) {
        int[] idxs = ramIdxForModel(codename);
        if (idxs == null) idxs = RAM_IDX_FOR_SOC.getOrDefault(soc == null ? "" : soc, RAM_IDX_DEFAULT);
        int ramIdx = idxs[r.next(idxs.length)];
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

    public static String macLower(Rng r) { return macUpper(r).toLowerCase(Locale.ROOT); }

    /** NANP US phone: 1 + area [2-9]XX + exchange [2-9]XX + 4 digits. */
    // Real, currently-assigned US geographic area codes — MUST be byte-identical to
    // specter/generators.py _US_AREA_CODES (same values, same order) or the seeded phone diverges.
    static final String[] US_AREA_CODES = {
            "212", "646", "917", "718",
            "213", "323", "310", "424", "818",
            "312", "773", "872",
            "281", "713", "832",
            "602", "480", "623",
            "215", "267",
            "210", "726",
            "619", "858",
            "214", "469", "972",
            "408", "669",
            "512", "737",
            "904", "407", "321", "305", "786", "813",
            "614", "216", "513",
            "704", "980", "919", "984",
            "317", "463",
            "206", "425", "253",
            "303", "720",
            "617", "857",
            "615", "629", "901",
            "503", "971",
            "702", "725",
            "404", "470", "678",
            "414", "262",
            "505", "575",
            "801", "385",
            "816", "913", "314",
            "412", "878",
            "612", "651", "763",
    };

    public static String phoneUs(Rng r) {
        // A REAL assigned area code + exchange [2-9]XX (never an N11 service code) + 4 digits, leading
        // country code 1. Draw order mirrors phone_us in specter/generators.py exactly (byte-parity):
        // area-code index, exchange leading digit, exchange 2nd/3rd digits, 4 subscriber digits.
        String area = US_AREA_CODES[r.next(US_AREA_CODES.length)];
        String exchFirst = String.valueOf(2 + r.next(8));
        String exchRest = digits(r, 2);
        if (exchRest.equals("11")) exchRest = "12";   // deterministic nudge off N11, no extra draw
        return "1" + area + exchFirst + exchRest + digits(r, 4);
    }

    /** Phone by country kind. USA-only build: always NANP. (kept for the Profile call signature) */
    public static String phoneForCountry(Rng r, String kind) {
        return phoneUs(r);
    }

    // Area-code -> US IANA timezone. MUST stay in exact lockstep with specter/generators.py _TZ_BY_AREA
    // so the derived timezone byte-matches. Pure lookup, no RNG. See the Python side for the rationale.
    private static final java.util.Map<String, String> TZ_BY_AREA = new java.util.HashMap<>();
    static {
        String[][] tz = {
            {"America/New_York", "212 646 917 718 215 267 904 407 321 305 786 813 614 216 513 704 980 919 984 317 463 617 857 404 470 678 412 878"},
            {"America/Chicago", "312 773 872 281 713 832 210 726 214 469 972 512 737 615 629 901 414 262 816 913 314 612 651 763"},
            {"America/Phoenix", "602 480 623"},
            {"America/Denver", "303 720 505 575 801 385"},
            {"America/Los_Angeles", "213 323 310 424 818 619 858 408 669 206 425 253 503 971 702 725"},
        };
        for (String[] row : tz) for (String a : row[1].split(" ")) TZ_BY_AREA.put(a, row[0]);
    }

    /** US IANA timezone for a NANP area code; America/New_York if the code isn't mapped. No RNG. */
    public static String tzForAreaCode(String area) {
        String tz = TZ_BY_AREA.get(area);
        return tz != null ? tz : "America/New_York";
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
    // Widevine PROPERTY_DEVICE_UNIQUE_ID is 16 OR 32 bytes depending on the device (32 or 64 hex chars) —
    // accept both so a real harvested/hand-entered id from a 32-byte device isn't wrongly rejected on import.
    private static final Pattern P_MEDIA_DRM  = Pattern.compile("[0-9a-f]{32}|[0-9a-f]{64}");
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
