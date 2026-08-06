#!/usr/bin/env python3
"""Autonomous, deterministic on-device spoof verifier for Specter — via the probe app.

No UI scraping. Uses com.specter.probe (a tiny app scoped to Specter) that reads EVERY spoofable
Android API through the hooks and dumps the actual returned values to JSON. This script:
  1. copies the current DevInfo profile to the probe package (so the probe gets a live identity),
  2. relaunches the probe (fresh fork -> Specter re-hooks it),
  3. reads probe_result.json and diffs it against the applied profile,
  4. prints a per-field pass/fail table and exits nonzero if any real-device value leaked.

Safe: touches ONLY com.specter.probe + com.liuzh.deviceinfo. Never Dasher/GeerGit/system.

Setup (one-time): the probe must be in Specter's LSPosed scope. scripts/scope_probe.py does that.
Usage: python scripts/verify_on_device.py [serial]
"""
import json, subprocess, sys, time

PROBE = "com.specter.probe"
DEVINFO = "com.liuzh.deviceinfo"
SPECTER_DIR = "/data/local/tmp/specter"
SERIAL = sys.argv[1] if len(sys.argv) > 1 else None

# probe_key -> profile_key. Both getSerial and the SERIAL field map to 'serial'.
CHECKS = {
    "build_manufacturer": "build_manufacturer", "build_brand": "build_brand",
    "build_device": "build_device", "build_model": "build_model", "build_id": "build_id",
    "build_fingerprint": "build_fingerprint", "build_bootloader": "build_bootloader",
    "build_hardware": "build_hardware", "build_board": "build_board", "build_radio": "build_radio",
    "os_version": "build_kernel_version", "serial_getSerial": "serial", "serial_field": "serial",
    "build_security_patch": "build_security_patch", "android_id": "android_id",
    "prop_gsm_baseband": "build_radio", "total_ram": "total_ram", "build_host": "build_host",
    "build_display": "build_display", "soc_platform": "soc_platform",
    # Build.* the probe already reads but the checks omitted — verify these rotate too.
    "build_product": "build_product", "build_release": "build_release",
    "build_incremental": "build_incremental",
    # Bluetooth MAC via BOTH paths (adapter + Settings) must equal the spoofed value; GSF deviceId source.
    "bt_addr_adapter": "bluetooth_mac", "bt_addr_settings": "bluetooth_mac", "gsf_id": "gsf_id",
    # StatFs storage — was leaking (generated but never hooked). Must equal the spoofed total_storage.
    "storage_total_bytes": "total_storage",
    # Hardware descriptors (GOAL 1.3) — direct-equality fields. GPU/sensors/cpuinfo are compound and
    # checked in their own block below.
    "hw_gles_version": "hw_gles_version", "hw_cores": "hw_cores", "hw_cameras": "hw_cameras",
    # User-Agent — PROVEN to be FPJS Pro's dominant visitorId anchor. Derived, not a profile field
    # (see expected_user_agent below), so its expectation is injected into `applied` before the diff.
    "http_agent": "_expect_http_agent",
}
# Known real Pixel-4 markers — if any appears in a probe value, that's a hard leak.
REAL_MARKERS = ["flame", "Pixel 4", "g8150-00088-210507", "4.14.212", "google/flame"]


def adb(*args):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=40).stdout


def su(cmd):
    return adb("shell", "su", "-c", cmd)


def main():
    # 1. seed the probe with the live DevInfo identity (or its own if already applied)
    su(f"cp {SPECTER_DIR}/{DEVINFO}.json {SPECTER_DIR}/{PROBE}.json 2>/dev/null; "
       f"chmod 644 {SPECTER_DIR}/{PROBE}.json")
    applied_raw = adb("shell", "cat", f"{SPECTER_DIR}/{PROBE}.json")
    if not applied_raw.strip():
        print("FAIL: no profile for the probe — apply an identity in Specter first.")
        return 2
    applied = json.loads(applied_raw)
    # The UA is derived from three build fields, not stored in the profile — mirror HookEntry's
    # rebuild (SpoofLogic.dalvikUserAgent) so the diff below can check it like any other field.
    if all(applied.get(k) for k in ("build_release", "build_model", "build_id")):
        applied["_expect_http_agent"] = (
            f"Dalvik/2.1.0 (Linux; U; Android {applied['build_release']}; "
            f"{applied['build_model']} Build/{applied['build_id']})")

    # 2. relaunch the probe fresh so Specter re-hooks it
    adb("shell", "am", "force-stop", PROBE)
    time.sleep(1)
    adb("shell", "monkey", "-p", PROBE, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(4)

    # 3. read the probe's actual readings
    su(f"cp /data/data/{PROBE}/files/probe_result.json /data/local/tmp/probe.json 2>/dev/null; "
       f"chmod 644 /data/local/tmp/probe.json")
    probe_raw = adb("shell", "cat", "/data/local/tmp/probe.json")
    if not probe_raw.strip():
        print("FAIL: probe wrote no result — is com.specter.probe in Specter's LSPosed scope? "
              "Run scripts/scope_probe.py and reboot.")
        return 2
    probe = json.loads(probe_raw)

    # Values Android returns to any unprivileged app (not the real device value, not a leak):
    #  - 02:00:00:00:00:00 = the placeholder BluetoothAdapter.getAddress() gives apps since Android 6
    #  - ERR:...SecurityException / permission = the probe itself lacks the read permission
    BENIGN = ("02:00:00:00:00:00", "ERR:", "null-adapter", "no-perm")

    # 4. diff
    ok = leak = benign = other = 0
    print(f"probe read {len(probe)} values; applied profile has {len(applied)} keys\n")
    print(f"{'FIELD':22} {'PROBE READ':36} STATUS")
    for pk, prof_k in CHECKS.items():
        got = str(probe.get(pk, "<none>"))
        want = str(applied.get(prof_k, "<none>"))
        if got == want and got not in ("<none>", "unknown"):
            print(f"{pk:22} {got[:36]:36} ✅ spoofed"); ok += 1
        elif any(r in got for r in REAL_MARKERS):
            print(f"{pk:22} {got[:36]:36} ❌ REAL LEAK (want {want[:16]})"); leak += 1
        elif any(got.startswith(b) or b in got for b in BENIGN):
            print(f"{pk:22} {got[:36]:36} ○ OS-placeholder/perm (not a leak)"); benign += 1
        else:
            print(f"{pk:22} {got[:36]:36} ⚠ (want {want[:16]})"); other += 1

    print(f"\n>>> {ok} spoofed, {leak} hard leaks, {benign} OS-placeholder/perm, {other} other <<<")

    # Widevine DRM coherence observation (NOT a pass/fail spoof check — securityLevel has no profile
    # value). Specter value-spoofs media_drm_id but does NOT hook getPropertyString("securityLevel").
    # A *changing* deviceUniqueId at a real L1 is itself incoherent (genuine L1 = fixed hardware id).
    # This measures the "Widevine coherence hole" from docs/BYEDENTITY-ANALYSIS.md — the native-read
    # blind spot byedentity closes via a liboemcrypto L1->L3 bind-mount. If id looks spoofed but level
    # reads L1, that mismatch is the leak to fix (add securityLevel to the hook, or drop to L3).
    drm_id = str(probe.get("media_drm_id", "<none>"))
    drm_lvl = str(probe.get("media_drm_security_level", "<none>"))
    want_id = str(applied.get("media_drm_id", "<none>"))
    id_spoofed = drm_id == want_id and drm_id not in ("<none>", "unknown") and not drm_id.startswith("ERR:")
    print(f"\n--- Widevine coherence ---")
    print(f"{'media_drm_id':22} {drm_id[:36]:36} {'✅ spoofed' if id_spoofed else '(see table)'}")
    print(f"{'  securityLevel':22} {drm_lvl[:36]:36}", end="")
    if id_spoofed and drm_lvl == "L1":
        print("  ⚠ INCOHERENT: spoofed id @ real L1 (a changing id at L1 is a red flag)")
    elif drm_lvl.startswith("ERR:"):
        print("  (unreadable)")
    else:
        print("  ○ coherent / n/a")

    # Hardware-descriptor coherence (GOAL 1.3): the compound signals. GPU renderer via BOTH the
    # native EGL/GLES read (hw_gl_native = VENDOR|RENDERER|VERSION — the path the Zygisk glGetString
    # hook targets) and the framework GLES version; the sensor list; and /proc/cpuinfo's Hardware line.
    print(f"\n--- Hardware descriptors ---")
    want_renderer = str(applied.get("hw_gpu_renderer", "<none>"))
    gl_native = str(probe.get("hw_gl_native", "<none>"))
    native_ok = want_renderer != "<none>" and want_renderer in gl_native
    print(f"{'gpu (native glGetStr)':22} {gl_native[:36]:36} "
          f"{'✅ spoofed (native)' if native_ok else '⚠ want ' + want_renderer[:16]}")
    # cpuinfo Hardware line must name the profile's SoC family, not the real Pixel 4 (SM8150/msmnile).
    ci_hw = str(probe.get("cpuinfo_hardware", "<none>"))
    ci_procs = str(probe.get("cpuinfo_processors", "<none>"))
    want_cores = str(applied.get("hw_cores", "<none>"))
    ci_note = "○" if ci_procs == want_cores else f"⚠ procs={ci_procs} want {want_cores}"
    print(f"{'cpuinfo Hardware':22} {ci_hw[:36]:36}")
    print(f"{'cpuinfo processors':22} {ci_procs:36} {ci_note}")
    # sensor list: the probe reports name|vendor rows; the profile carries name|vendor|type. Compare
    # the first sensor's name+vendor as a spot-check that the relabel landed.
    want_sensors = str(applied.get("hw_sensors", "<none>"))
    got_sensors = str(probe.get("hw_sensors", "<none>"))
    got_sensors_native = str(probe.get("hw_sensors_native", "<none>"))
    if want_sensors != "<none>" and "|" in want_sensors:
        first = want_sensors.split(";")[0]                 # name|vendor|type
        name_vendor = "|".join(first.split("|")[:2])       # name|vendor
        want_name = name_vendor.split("|")[0]
        sens_ok = want_name in got_sensors
        print(f"{'sensors[0] (java)':22} {got_sensors[:36]:36} "
              f"{'✅ relabelled' if sens_ok else '⚠ want ' + name_vendor[:20]}")
        # Native ASensor read — the direct-JNI path the Zygisk ASensor_getName/getVendor hooks target.
        nat_ok = want_name in got_sensors_native
        print(f"{'sensors[0] (native)':22} {got_sensors_native[:36]:36} "
              f"{'✅ spoofed (native)' if nat_ok else '⚠ want ' + want_name[:20]}")

    # Storage coherence: getTotalBytes must equal blockCount*blockSize, else an app computing total
    # from blocks gets a different (real) value than getTotalBytes — a worse tell than a plain leak.
    st_total = str(probe.get("storage_total_bytes", "<none>"))
    st_bxs = str(probe.get("storage_blocks_x_size", "<none>"))
    if st_total not in ("<none>",) and not st_total.startswith("ERR:"):
        print(f"\n--- Storage coherence ---")
        coherent = st_total == st_bxs
        print(f"{'getTotalBytes':22} {st_total[:36]:36}")
        print(f"{'blockCount*blockSize':22} {st_bxs[:36]:36} "
              f"{'○ coherent' if coherent else '⚠ INCOHERENT (total != blocks*size)'}")

    # GPS location (per-identity gps_lat/gps_lon). Both the raw LocationManager and GMS Fused reads must
    # equal the profile fix, and isFromMockProvider() must read false — the edge over a system mock-provider
    # like Lockito (whose test-provider fixes flag isFromMockProvider()==true, a detectable tell).
    gps_leak = False
    want_lat = str(applied.get("gps_lat", "<none>"))
    want_lon = str(applied.get("gps_lon", "<none>"))
    if want_lat != "<none>":
        print(f"\n--- GPS location (want {want_lat},{want_lon}) ---")
        for label, pfx in (("LocationManager gps", "lm_gps"), ("Fused getLastLocation", "fused_last")):
            got_lat = str(probe.get(pfx + "_lat", "<none>"))
            got_lon = str(probe.get(pfx + "_lon", "<none>"))
            mock = str(probe.get(pfx + "_mock", "<none>"))
            raw = str(probe.get(pfx, "<none>"))
            if got_lat == want_lat and got_lon == want_lon:
                mock_note = "✅ spoofed, isFromMockProvider=false" if mock == "false" \
                    else f"⚠ spoofed BUT isFromMockProvider={mock}"
                print(f"{label:22} {got_lat},{got_lon}  {mock_note}")
            elif got_lat in ("<none>",) or raw in ("null", "pending(hook-miss?)"):
                print(f"{label:22} {raw[:36]:36} ⚠ no fix / hook miss (is the probe in Specter's scope?)")
            else:
                print(f"{label:22} {got_lat},{got_lon}  ❌ MISMATCH"); gps_leak = True

    return 1 if (leak or gps_leak) else 0


if __name__ == "__main__":
    sys.exit(main())
