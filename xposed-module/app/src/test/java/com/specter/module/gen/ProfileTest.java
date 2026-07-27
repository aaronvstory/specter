package com.specter.module.gen;

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

    // A few real device rows. Column order MUST mirror data/devices.json exactly:
    // [name, manufacturer, brand, MODEL, PRODUCT, "DEVICE:release", build_id, incremental, patch].
    // These fixtures previously had MODEL and DEVICE transposed relative to the real dataset, which is
    // precisely why the swapped-column bug passed the suite — a fixture that disagrees with production
    // data tests nothing. Values below are verified against real device builds.
    static List<List<String>> devices() {
        List<List<String>> d = new ArrayList<>();
        d.add(Arrays.asList("Google Pixel 5", "Google", "google", "Pixel 5", "redfin", "redfin:11", "RQ3A.210805.001.A1", "7474174", "2021-08-05"));
        d.add(Arrays.asList("Samsung Galaxy S21", "Samsung", "samsung", "SM-G991U", "o1sxxx", "o1s:11", "RP1A.200720.012", "G991USQU4AUDA", "2021-04-01"));
        d.add(Arrays.asList("OnePlus 8T", "OnePlus", "oneplus", "KB2005", "OnePlus8T", "kebab:11", "RP1A.201005.001", "2107220042", "2021-07-01"));
        return d;
    }

    public static void main(String[] args) {
        List<List<String>> devs = devices();

        // build() produces a valid, coherent profile across many seeds.
        for (int s = 0; s < 1000; s++) {
            Map<String, String> p = Profile.build(seeded(s), devs, true);
            List<String> errs = Profile.validate(p);
            check(errs.isEmpty(), "profile valid s=" + s + " " + errs);
            // all keys present
            check(p.size() == Profile.KEYS.length, "all keys s=" + s);
            // coherence spot-checks
            check(p.get("build_fingerprint").contains(p.get("build_brand")), "brand in fp s=" + s);
            // The fingerprint is $BRAND/$PRODUCT/$DEVICE:$RELEASE/$ID/$INCREMENTAL:$TYPE/$TAGS, and the
            // DEVICE slot is a CODENAME — never the marketing model. Regression: MODEL and DEVICE were
            // bound to the wrong dataset columns, emitting "google/bramble/Pixel 4a (5G):11/..." with
            // spaces and parens in the DEVICE slot. No real Android build does that, so it flagged
            // every profile. Assert the shape here, where one check covers all 1000 seeds.
            String fp = p.get("build_fingerprint");
            check(fp.matches("[^/]+/[^/]+/[^/:]+:[^/]+/[^/]+/[^:]+:user/release-keys"),
                    "fingerprint shape s=" + s + " " + fp);
            String fpDevice = fp.split("/")[2].split(":")[0];
            check(fpDevice.equals(p.get("build_device")), "fp device slot == build_device s=" + s + " " + fp);
            check(!fpDevice.contains(" ") && !fpDevice.contains("("),
                    "fp device slot is a codename, not a marketing name s=" + s + " " + fp);
            check(!p.get("build_device").contains(" "), "build_device is a codename s=" + s + " " + p.get("build_device"));
            // BOOTLOADER present, non-empty, and DEVICE-coherent — it must be derived from the picked
            // device codename so it can never imply a different model (a Galaxy A01 must not report a
            // Galaxy S21 bootloader). Google embeds the codename; LG embeds the device; Samsung embeds
            // the model code (SM- stripped, uppercased).
            String bl = p.get("build_bootloader");
            String brand = p.get("build_brand").toLowerCase();
            String model = p.get("build_model");
            // Samsung's bootloader derives from the SM- MARKETING model (build_model); Google/LG from
            // the codename (product with the LG region suffix stripped). build_device holds the
            // codename ("flame"), build_model the marketing name ("Pixel 4") — as on a real device.
            String cn2 = p.get("build_product");
            int u2 = cn2.indexOf('_'); if (u2 > 0) cn2 = cn2.substring(0, u2);
            check(bl != null && !bl.isEmpty() && !bl.contains(" "), "bootloader shape s=" + s + " " + bl);
            boolean coherent =
                brand.equals("google")   ? bl.startsWith(cn2.toLowerCase() + "-") :
                brand.equals("samsung")  ? bl.startsWith(model.replace("SM-", "").toUpperCase() + "XXU") :
                brand.equals("lge")      ? bl.startsWith("LGE-" + cn2.toUpperCase() + "-") :
                brand.equals("motorola") ? bl.startsWith("MBM-") : bl.startsWith("BL");
            check(coherent, "bootloader coherent s=" + s + " brand=" + brand + " bl=" + bl);
            // RAM/storage present as plausible byte counts (fingerprint-hash hardware signals).
            check(p.get("total_ram").matches("\\d{9,11}"), "ram bytes s=" + s + " " + p.get("total_ram"));
            check(p.get("total_storage").matches("\\d{10,12}"), "storage bytes s=" + s + " " + p.get("total_storage"));
            // HOST is a farm-style hostname (generated, not the device's real build host); DISPLAY==build_id.
            check(p.get("build_host").matches("[A-Za-z-]+-\\d{5}"), "host shape s=" + s + " " + p.get("build_host"));
            check(p.get("build_display").equals(p.get("build_id")), "display==build_id s=" + s);
            // SoC platform is a real Qualcomm/Google platform codename (never a made-up string).
            check(p.get("soc_platform").matches("[a-z0-9]{4,10}"), "soc shape s=" + s + " " + p.get("soc_platform"));
            // Known-PRODUCT coherence: SoC keys on Build.PRODUCT (the codename), not the marketing name.
            // Pixel 4 (product flame) -> msmnile (SD855); LG G5 (product h1_lra_us) -> msm8996 (SD820).
            String prod = p.get("build_product");
            if ("flame".equals(prod))  check(p.get("soc_platform").equals("msmnile"), "Pixel4 SoC s=" + s + " " + p.get("soc_platform"));
            if (prod != null && prod.startsWith("h1_"))
                check(p.get("soc_platform").equals("msm8996"), "LG G5 SoC s=" + s + " " + p.get("soc_platform"));
            // Fingerprint-hash hardware signals: kernel plausible; HARDWARE/BOARD coherent with device.
            check(p.get("build_kernel_version").matches("\\d+\\.\\d+\\.\\d+-.*-g[0-9a-f]{8}"), "kernel shape s=" + s + " " + p.get("build_kernel_version"));
            // HARDWARE/BOARD are the board CODENAME (product with any LG region suffix stripped),
            // NOT the marketing device name — real devices report the codename here.
            String cn = p.get("build_product");
            int u = cn.indexOf('_'); if (u > 0) cn = cn.substring(0, u);
            check(p.get("build_hardware").equals(cn), "hardware==codename s=" + s + " " + p.get("build_hardware") + " vs " + cn);
            check(p.get("build_board").equals(cn), "board==codename s=" + s);
            // Radio/baseband present, non-empty, plausible (no whitespace, has a vendor prefix + digits)
            String radio = p.get("build_radio");
            check(radio != null && !radio.isEmpty() && !radio.contains(" ") && radio.contains("-"), "radio shape s=" + s + " " + radio);
            check(p.get("sim_subscriber_imsi").startsWith(p.get("sim_operator_mccmnc")), "imsi carrier s=" + s);
            // dual-SIM: imei1 != imei2 but share the TAC (first 8 digits)
            check(!p.get("imei1").equals(p.get("imei2")), "imei1 != imei2 s=" + s);
            check(p.get("imei1").substring(0, 8).equals(p.get("imei2").substring(0, 8)), "imeis share TAC s=" + s);
        }

        // ---- Hardware descriptors ----
        // Every built profile carries the 9 hardware fields (from DEFAULT_HW here — the JVM test has
        // no asset dataset), non-empty and complete, so it is valid and byte-parity KEYS-complete.
        Map<String, String> hwp = Profile.build(seeded(11), devs, true);
        for (String k : new String[]{"hw_gpu_renderer", "hw_gpu_vendor", "hw_gles_version", "hw_cores",
                "hw_sensors", "hw_cameras", "hw_codecs", "hw_input_devices", "proc_cpuinfo"}) {
            check(hwp.get(k) != null && !hwp.get(k).isEmpty(), "hw field present " + k);
        }
        // hwFields renders a supplied entry with the SAME encoding as specter/profile.py _hw_fields:
        // sensors "name|vendor|type" joined by ';', lists comma-joined. This is the cross-language
        // render contract — the data itself is byte-identical (asset-sync test), so identical render +
        // identical data == byte-parity for these fields.
        Map<String, Map<String, String>> hwDs = new HashMap<>();
        Map<String, String> entry = new HashMap<>();
        entry.put("hw_gpu_renderer", "Adreno (TM) 640");
        entry.put("hw_sensors", "BMI160 accelerometer|Bosch|1;BMI160 gyroscope|Bosch|4");
        entry.put("hw_cameras", "0,1,2,3");
        hwDs.put("flame", entry);
        Map<String, String> rendered = Profile.hwFields("flame", hwDs);
        check(rendered.get("hw_gpu_renderer").equals("Adreno (TM) 640"), "hwFields uses dataset value");
        check(rendered.get("hw_sensors").equals("BMI160 accelerometer|Bosch|1;BMI160 gyroscope|Bosch|4"),
                "hwFields sensor encoding matches Python");
        check(rendered.get("hw_cameras").equals("0,1,2,3"), "hwFields camera encoding matches Python");
        // Unknown codename -> _default (or built-in DEFAULT_HW when dataset lacks _default).
        Map<String, String> fb = Profile.hwFields("no_such_device", hwDs);
        check(fb.get("hw_gpu_renderer").equals(Profile.DEFAULT_HW.get("hw_gpu_renderer")),
                "hwFields falls back to DEFAULT_HW for unknown codename");

        // A truncated profile (missing a key) must FAIL validation — else it could be written to
        // the target app and leak the real id for the missing field.
        Map<String, String> truncated = new HashMap<>(Profile.build(seeded(9), devs, true));
        truncated.remove("android_id");
        check(!Profile.isValid(truncated), "missing key -> invalid");
        check(Profile.validate(truncated).stream().anyMatch(s -> s.contains("missing key: android_id")),
                "missing key reported by name");

        // USA-only: every profile is a coherent US identity — US carrier (MCC 310–316), NANP phone,
        // IMSI matching the carrier, and a US-market device brand.
        java.util.Set<String> usBrands = new HashSet<>(Arrays.asList("samsung", "google", "motorola", "lge"));
        for (int s = 0; s < 500; s++) {
            Map<String, String> us = Profile.build(seeded(s), devs, true, Country.US);
            check(Profile.isValid(us), "US profile valid s=" + s + " " + Profile.validate(us));
            String mcc = us.get("sim_operator_mccmnc");
            check(mcc.matches("31[0-6]\\d{3}"), "US carrier MCC 310-316 s=" + s + " " + mcc);
            check(us.get("mobile_number").matches("1[2-9]\\d{2}[2-9]\\d{6}"), "US NANP phone s=" + s);
            check(us.get("sim_subscriber_imsi").startsWith(mcc), "US IMSI carrier-coherent s=" + s);
            check(usBrands.contains(us.get("build_brand").toLowerCase()), "US-market brand s=" + s + " " + us.get("build_brand"));
        }
        // Back-compat overload == US.
        Map<String, String> usProf = Profile.build(seeded(1), devs, true);
        check(usProf.get("sim_operator_mccmnc").matches("31[0-6]\\d{3}"), "US carrier MCC 310-316");
        check(usProf.get("mobile_number").startsWith("1"), "US phone NANP");

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

        // recordOne: the per-field RANDOMIZE ledger path (ban-critical — a single randomized id
        // must ALSO be recorded, else a later generateUnique could reissue it -> coordinated accounts).
        UsedStore one = new UsedStore();
        check(one.recordOne("gsf_id", "111222333"), "recordOne new value claims it");
        check(one.containsValue("gsf_id", "111222333"), "recordOne persists to the set");
        check(!one.recordOne("gsf_id", "111222333"), "recordOne rejects a re-issue (reuse guard)");
        check(one.count() == 1, "recordOne bumps the ledger count");
        // non-unique key is a no-op success (wifi_ssid is not ban-critical)
        check(one.recordOne("wifi_ssid", "NETGEAR12"), "recordOne no-op true for non-unique key");
        check(!one.containsValue("wifi_ssid", "NETGEAR12"), "non-unique key not tracked");
        // a value randomized into one key doesn't false-collide a different key
        check(one.recordOne("android_id", "111222333"), "same string, different key -> distinct");

        // backfillDerived: an OLD profile (missing the newer signals) gets them filled from its own data,
        // without overwriting existing values or needing RNG. This is what makes restoring/importing an
        // old vault profile still apply boot_count/battery/timezone coherently.
        java.util.Map<String, String> old = new java.util.LinkedHashMap<>();
        old.put("android_id", "fd3833c66a179a71");
        old.put("build_device", "flame");
        old.put("mobile_number", "12127890123");   // 212 = NYC -> Eastern
        // (deliberately missing boot_count / battery_uah / timezone / locale)
        Profile.backfillDerived(old);
        check(old.containsKey("boot_count") && old.get("boot_count").equals(
                String.valueOf(Generators.bootCountFor("fd3833c66a179a71"))), "backfill boot_count from android_id");
        check(old.containsKey("battery_uah") && old.get("battery_uah").equals(
                String.valueOf(Generators.batteryUahFor("flame"))), "backfill battery_uah from device");
        check("America/New_York".equals(old.get("timezone")), "backfill timezone from 212 area code");
        check("en-US".equals(old.get("locale")), "backfill locale");
        // Never overwrites an existing value.
        java.util.Map<String, String> keep = new java.util.LinkedHashMap<>();
        keep.put("android_id", "fd3833c66a179a71"); keep.put("boot_count", "999");
        Profile.backfillDerived(keep);
        check("999".equals(keep.get("boot_count")), "backfill does not overwrite an existing boot_count");
        // Null-safe.
        check(Profile.backfillDerived(null) == null, "backfill null -> null");

        System.out.println("Profile+UsedStore: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
