package com.specter.module.gen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * Assemble one coherent device identity from the device DB + generators — a 1:1 port of the
 * trusted Python {@code specter/profile.py}. Pure logic (no Android): a device row is a
 * {@code List<String>} of the same positional fields the Python indexes as dev[1..8].
 *
 * Coherence rules enforced (a fingerprint failing these is a fraud flag): Build.* all from ONE
 * real device row; IMSI carrier prefix == SIM MCC/MNC; ICCID issuer prefix matches the carrier;
 * US-market device + US carrier.
 */
public final class Profile {
    private Profile() {}

    // Dominant US-market Android makers only — so device brand reads as a genuine US phone.
    static final Set<String> US_COMMON_BRANDS = new HashSet<>(Arrays.asList(
            "samsung", "google", "motorola", "lge"));

    /** {mccmnc, name} US carriers (MCC 310/311), same order as Python US_CARRIERS. */
    static final String[][] US_CARRIERS = {
            {"310260", "T-Mobile"}, {"311480", "Verizon"}, {"310410", "AT&T"},
            {"310120", "Sprint"}, {"311580", "US Cellular"}, {"310030", "AT&T"},
            {"310160", "T-Mobile"}, {"311870", "Boost Mobile"},
    };

    /** All profile keys, in insertion order matching the Python dict / pushed JSON. */
    public static final String[] KEYS = {
            "android_id", "imei1", "imei2", "serial", "advertising_id", "gsf_id", "media_drm_id",
            "media_drm_security_level",
            "bluetooth_mac", "wifi_mac", "wifi_bssid", "wifi_ssid", "mobile_number",
            "sim_operator_mccmnc", "sim_operator_name", "sim_subscriber_imsi", "sim_serial_iccid",
            "gmail", "build_manufacturer", "build_brand", "build_device", "build_product",
            "build_model", "build_release", "build_id", "build_incremental", "build_fingerprint",
            "build_security_patch", "build_bootloader",
            "build_hardware", "build_board", "build_kernel_version", "build_radio",
            "total_ram", "total_storage", "build_host", "build_display", "soc_platform",
            "factory_reset_epoch",
    };

    /** The globally-unique (ban-critical no-reuse) keys — mirror of identifiers.UNIQUE_KEYS. */
    public static final String[] UNIQUE_KEYS = {
            "android_id", "imei1", "imei2", "serial", "advertising_id", "gsf_id", "media_drm_id",
            "bluetooth_mac", "wifi_mac", "wifi_bssid", "mobile_number",
            "sim_subscriber_imsi", "sim_serial_iccid", "gmail",
    };

    // Minimum plausible Android major. A fresh account on Android < 9 (2018) reads as a red flag.
    // Mirror of profile.MIN_ANDROID_MAJOR — MUST match, or the pool differs and byte-parity breaks.
    static final int MIN_ANDROID_MAJOR = 9;
    // Tablet / TV markers (device NAME, row 0). We emit a phone number + SIM + IMEI, so a WiFi tablet
    // or TV box is incoherent. Mirror of profile._NON_PHONE_MARKERS.
    static final String[] NON_PHONE_MARKERS = {
            "Tab", "Nexus 7", "Nexus 9", "Nexus 10", "Nexus Player", "Shield", "Pixel C"};

    static int releaseMajorOf(List<String> dev) {
        // Leading-digit-run only, kept in exact lockstep with profile._release_major_of — no locale or
        // float path, so it can't diverge even if a future row has a non-numeric release string.
        try {
            String slot = dev.get(5);
            int c = slot.indexOf(':');
            String rel = c >= 0 ? slot.substring(c + 1) : "0";
            int dot = rel.indexOf('.');
            String head = dot >= 0 ? rel.substring(0, dot) : rel;
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < head.length(); i++) {
                char ch = head.charAt(i);
                if (ch >= '0' && ch <= '9') digits.append(ch); else break;
            }
            return digits.length() > 0 ? Integer.parseInt(digits.toString()) : 0;
        } catch (Exception e) { return 0; }
    }

    static boolean isPlausiblePhone(List<String> dev) {
        if (dev.size() <= 5) return false;
        for (String m : NON_PHONE_MARKERS) if (dev.get(0).contains(m)) return false;
        return releaseMajorOf(dev) >= MIN_ANDROID_MAJOR;
    }

    static List<String> pickDevice(Generators.Rng r, List<List<String>> devices, boolean usBias) {
        if (usBias) {
            List<List<String>> pool = new ArrayList<>();
            for (List<String> d : devices)
                if (d.size() > 2 && US_COMMON_BRANDS.contains(d.get(2).toLowerCase())
                        && isPlausiblePhone(d)) pool.add(d);
            if (!pool.isEmpty()) return pool.get(r.next(pool.size()));
        }
        return devices.get(r.next(devices.size()));
    }

    static List<String> pickDevice(Generators.Rng r, List<List<String>> devices, boolean bias, Country country) {
        if (bias && country != null) {
            Set<String> brands = new HashSet<>(Arrays.asList(country.commonBrands));
            List<List<String>> pool = new ArrayList<>();
            for (List<String> d : devices)
                if (d.size() > 2 && brands.contains(d.get(2).toLowerCase())
                        && isPlausiblePhone(d)) pool.add(d);
            if (!pool.isEmpty()) return pool.get(r.next(pool.size()));
        }
        return devices.get(r.next(devices.size()));
    }

    /** Build a full 27-field identity for the US (back-compat overload; seeded output unchanged). */
    public static Map<String, String> build(Generators.Rng r, List<List<String>> devices, boolean usBias) {
        return build(r, devices, usBias, Country.US);
    }

    /** Build a full 28-field identity. Device rows are [name, manufacturer, brand, device, product, "model:release", build_id, incremental, patch]. */
    public static Map<String, String> build(Generators.Rng r, List<List<String>> devices, boolean usBias, Country country) {
        List<String> dev = pickDevice(r, devices, usBias, country);
        String manufacturer = dev.get(1), brand = dev.get(2), device = dev.get(3), product = dev.get(4);
        String modelRel = dev.get(5);
        int colon = modelRel.indexOf(':');
        String model = colon >= 0 ? modelRel.substring(0, colon) : modelRel;
        String release = colon >= 0 ? modelRel.substring(colon + 1) : "11";
        String buildId = dev.size() > 6 ? dev.get(6) : "RQ3A.211001.001";
        String incremental = dev.size() > 7 ? dev.get(7) : Generators.digits(r, 7);
        String patch = dev.size() > 8 ? dev.get(8) : "2021-01-01";
        String fingerprint = brand + "/" + product + "/" + device + ":" + release + "/" + buildId
                + "/" + incremental + ":user/release-keys";

        String[] carrier = country.carriers[r.next(country.carriers.length)];
        String mccmnc = carrier[0], carrierName = carrier[1];

        // One TAC per device (manufacturer), shared by both IMEIs; imei1 != imei2 (serial differs).
        String tac = Generators.tacForBrand(r, brand);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("android_id", Generators.hex16(r));
        p.put("imei1", Generators.imei(r, tac));
        p.put("imei2", Generators.imei(r, tac));
        p.put("serial", Generators.serialForBrand(r, brand));
        p.put("advertising_id", Generators.uuid(r));
        p.put("gsf_id", Generators.gsf(r));
        p.put("media_drm_id", Generators.hex32(r));
        // L3 (software Widevine): coherent with a spoofed/changing deviceUniqueId — a genuine L1
        // device has a FIXED hardware id, so a changing id at L1 is a red flag. Constant, so it
        // consumes no RNG and the byte-parity draw order is unchanged. Mirrors profile.py.
        p.put("media_drm_security_level", "L3");
        p.put("bluetooth_mac", Generators.macUpper(r));
        p.put("wifi_mac", Generators.macUpper(r));
        p.put("wifi_bssid", Generators.macLower(r));
        p.put("wifi_ssid", Generators.ssid(r));
        p.put("mobile_number", Generators.phoneForCountry(r, country.phoneKind));
        p.put("sim_operator_mccmnc", mccmnc);
        p.put("sim_operator_name", carrierName);
        p.put("sim_subscriber_imsi", Generators.imsi(r, mccmnc));
        p.put("sim_serial_iccid", Generators.iccid(r, mccmnc));
        p.put("gmail", Generators.gmail(r));
        p.put("build_manufacturer", manufacturer);
        p.put("build_brand", brand);
        p.put("build_device", device);
        p.put("build_product", product);
        p.put("build_model", model);
        p.put("build_release", release);
        p.put("build_id", buildId);
        p.put("build_incremental", incremental);
        p.put("build_fingerprint", fingerprint);
        p.put("build_security_patch", patch);
        // The board/platform CODENAME on real devices lives in the product slot (e.g. "flame" for a
        // Pixel 4) — NOT the marketing device name ("Pixel 4"). LG products carry a region suffix.
        String codename = product;
        int usx = codename.indexOf('_');          // strip LG regional suffix: h1_lra_us -> h1
        if (usx > 0) codename = codename.substring(0, usx);
        // Bootloader: Samsung derives it from the SM- model (device slot); Google/others from the
        // codename (device slot = "Pixel 4" would yield a space-containing, incoherent bootloader).
        String blBase = "samsung".equalsIgnoreCase(brand) ? device : codename;
        p.put("build_bootloader", Generators.bootloader(r, brand, blBase));
        // Build.HARDWARE/BOARD are the board codename too.
        p.put("build_hardware", codename);
        p.put("build_board", codename);
        p.put("build_kernel_version", Generators.kernelVersion(r));
        p.put("build_radio", Generators.radioVersion(r));
        String[] ramStorage = Generators.ramStorageBytes(r);   // coherent RAM+storage pair
        p.put("total_ram", ramStorage[0]);
        p.put("total_storage", ramStorage[1]);
        // Build.HOST leaks the real build-farm hostname (e.g. "abfarm-00902" = Google infra — incoherent
        // on a spoofed Samsung/Moto). Build.DISPLAY is the build display id, ==build_id on real devices.
        p.put("build_host", Generators.buildHost(r));
        p.put("build_display", buildId);
        // ro.board.platform (SoC codename) — device-coherent, so the fingerprint's CPU/SoC portion
        // rotates per identity instead of leaking the real phone's SoC on every signup.
        p.put("soc_platform", Generators.socPlatform(r, product));
        // LAST — appended to the end of the RNG order so every existing field is unchanged. Mirrors
        // profile.py, which passes the same security patch so the pair stays coherent.
        p.put("factory_reset_epoch", Generators.factoryResetEpoch(r, patch));
        return p;
    }

    /** Per-field format + cross-field coherence. Returns the list of errors (empty == valid). */
    public static List<String> validate(Map<String, String> p) {
        List<String> errors = new ArrayList<>();
        // Every profile key must be PRESENT — a truncated profile (missing keys) would otherwise pass
        // format checks and could be written to the target app, leaking real ids for the gaps.
        for (String k : KEYS)
            if (p.get(k) == null) errors.add("missing key: " + k);
        for (Map.Entry<String, String> e : p.entrySet())
            if (!Generators.validate(e.getKey(), String.valueOf(e.getValue())))
                errors.add("invalid format: " + e.getKey() + "=" + e.getValue());

        String fp = p.getOrDefault("build_fingerprint", "");
        for (String f : new String[]{"build_brand", "build_device"}) {
            String v = p.get(f);
            if (v != null && !v.isEmpty() && !fp.contains(v))
                errors.add("incoherent: " + f + "=" + v + " not in fingerprint");
        }
        String mccmnc = p.get("sim_operator_mccmnc");
        String imsi = p.getOrDefault("sim_subscriber_imsi", "");
        if (mccmnc != null && !mccmnc.isEmpty() && !imsi.startsWith(mccmnc))
            errors.add("incoherent: IMSI does not start with SIM MCC/MNC");
        String iccid = p.getOrDefault("sim_serial_iccid", "");
        String expectedIin = Generators.ICCID_IIN.get(mccmnc);
        if (expectedIin != null && !iccid.startsWith(expectedIin))
            errors.add("incoherent: ICCID " + (iccid.length() >= 8 ? iccid.substring(0, 8) : iccid)
                    + " does not match carrier IIN " + expectedIin);
        return errors;
    }

    public static boolean isValid(Map<String, String> p) { return validate(p).isEmpty(); }
}
