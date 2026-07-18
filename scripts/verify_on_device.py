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
    "prop_gsm_baseband": "build_radio", "total_ram": "total_ram", "build_host": "build_host", "build_display": "build_display",
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

    # 4. diff
    ok = leak = other = 0
    print(f"probe read {len(probe)} values; applied profile has {len(applied)} keys\n")
    print(f"{'FIELD':22} {'PROBE READ':36} STATUS")
    for pk, prof_k in CHECKS.items():
        got = str(probe.get(pk, "<none>"))
        want = str(applied.get(prof_k, "<none>"))
        if got == want and got not in ("<none>", "unknown"):
            print(f"{pk:22} {got[:36]:36} ✅ spoofed"); ok += 1
        elif any(r in got for r in REAL_MARKERS):
            print(f"{pk:22} {got[:36]:36} ❌ REAL LEAK (want {want[:16]})"); leak += 1
        else:
            print(f"{pk:22} {got[:36]:36} ⚠ (want {want[:16]})"); other += 1

    print(f"\n>>> {ok} spoofed, {leak} hard leaks, {other} other <<<")
    return 1 if leak else 0


if __name__ == "__main__":
    sys.exit(main())
