package com.fleet.idrotate.gen;

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
            "bluetooth_mac", "wifi_mac", "wifi_bssid", "wifi_ssid", "mobile_number",
            "sim_operator_mccmnc", "sim_operator_name", "sim_subscriber_imsi", "sim_serial_iccid",
            "gmail", "build_manufacturer", "build_brand", "build_device", "build_product",
            "build_model", "build_release", "build_id", "build_incremental", "build_fingerprint",
            "build_security_patch", "build_bootloader",
            "build_hardware", "build_board", "build_kernel_version", "build_radio",
            "total_ram", "total_storage", "build_host", "build_display",
    };

    /** The globally-unique (ban-critical no-reuse) keys — mirror of identifiers.UNIQUE_KEYS. */
    public static final String[] UNIQUE_KEYS = {
            "android_id", "imei1", "imei2", "serial", "advertising_id", "gsf_id", "media_drm_id",
            "bluetooth_mac", "wifi_mac", "wifi_bssid", "mobile_number",
            "sim_subscriber_imsi", "sim_serial_iccid", "gmail",
    };

    static List<String> pickDevice(Generators.Rng r, List<List<String>> devices, boolean usBias) {
        if (usBias) {
            List<List<String>> pool = new ArrayList<>();
            for (List<String> d : devices)
                if (d.size() > 2 && US_COMMON_BRANDS.contains(d.get(2).toLowerCase())) pool.add(d);
            if (!pool.isEmpty()) return pool.get(r.next(pool.size()));
        }
        return devices.get(r.next(devices.size()));
    }

    static List<String> pickDevice(Generators.Rng r, List<List<String>> devices, boolean bias, Country country) {
        if (bias && country != null) {
            Set<String> brands = new HashSet<>(Arrays.asList(country.commonBrands));
            List<List<String>> pool = new ArrayList<>();
            for (List<String> d : devices)
                if (d.size() > 2 && brands.contains(d.get(2).toLowerCase())) pool.add(d);
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
        p.put("serial", Generators.hex16upper(r));
        p.put("advertising_id", Generators.uuid(r));
        p.put("gsf_id", Generators.gsf(r));
        p.put("media_drm_id", Generators.hex32(r));
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
        p.put("build_bootloader", Generators.bootloader(r, brand, device));
        // Fingerprint-hash signals (FingerprintJS reads these): keep coherent with the device.
        // HARDWARE/BOARD track the platform (device codename); kernel is high-entropy, per-identity.
        p.put("build_hardware", device);
        p.put("build_board", device);
        p.put("build_kernel_version", Generators.kernelVersion(r));
        p.put("build_radio", Generators.radioVersion(r));
        p.put("total_ram", Generators.totalRamBytes(r));
        p.put("total_storage", Generators.totalStorageBytes(r));
        // Build.HOST leaks the real build-farm hostname (e.g. "abfarm-00902" = Google infra — incoherent
        // on a spoofed Samsung/Moto). Build.DISPLAY is the build display id, ==build_id on real devices.
        p.put("build_host", Generators.buildHost(r));
        p.put("build_display", buildId);
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
