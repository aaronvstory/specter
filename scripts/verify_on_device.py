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

    return 1 if leak else 0


if __name__ == "__main__":
    sys.exit(main())
