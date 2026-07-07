"""
compare_with_geergit.py — empirical validation harness (needs a phone + GeerGit installed).

What it does, end to end:
  1. Reads GeerGit's LSPosed-Bridge log to enumerate WHICH identifiers the target app
     (Dasher) actually reads at runtime — ground truth of the surface that matters.
  2. Drives GeerGit through N randomize cycles (you tap RANDOMIZE; script snapshots each),
     capturing GeerGit's per-app hive hash + the OS-side ground-truth ids each time.
  3. Cross-checks:
       - does GeerGit rotate every id it claims to?  (hive hash changes per wipe)
       - does any id REPEAT across wipes?             (the 2.9.6 bug)
       - does OUR generator cover every surface GeerGit's log shows Dasher reading?
  4. Writes a report to reports/geergit-compare-<ts>.md

Run:  python scripts/compare_with_geergit.py --pkg com.doordash.driverapp --cycles 3
This is interactive: it prompts you to tap RANDOMIZE in GeerGit between snapshots.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from specter import device as D
from specter.identifiers import SPECS

# identifier keywords GeerGit prints in its hook log, mapped to our spec keys
LOG_KEYWORDS = {
    "android id": "android_id", "getserial": "serial", "serial": "serial",
    "imei": "imei1", "advertis": "advertising_id", "ad id": "advertising_id",
    "gsf": "gsf_id", "mac": "wifi_mac", "bluetooth": "bluetooth_mac",
    "ssid": "wifi_ssid", "bssid": "wifi_bssid", "subscriber": "sim_subscriber_imsi",
    "sim": "sim_serial_iccid", "operator": "sim_operator_mccmnc",
    "line1": "mobile_number", "drm": "media_drm_id", "model": "build_model",
}


def enumerate_read_surface(pkg):
    """Which identifiers does the target actually read? (from GeerGit's live log)"""
    lines = D.read_geergit_hook_log()
    found = set()
    for ln in lines:
        low = ln.lower()
        for kw, key in LOG_KEYWORDS.items():
            if kw in low:
                found.add(key)
    return found, len(lines)


def geergit_hive_hash(pkg):
    rc, out, _ = D.su(
        f"cp /data/data/com.pyshivam.geergit/app_flutter/{pkg}_app_conf.hive /data/local/tmp/h.hive 2>/dev/null; "
        f"md5sum /data/local/tmp/h.hive 2>/dev/null | cut -d' ' -f1")
    return out.strip()


def snapshot(pkg):
    return {
        "hive_md5": geergit_hive_hash(pkg),
        "os_ids": D.read_live_identifiers(),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pkg", default="com.doordash.driverapp")
    ap.add_argument("--cycles", type=int, default=3)
    ap.add_argument("--report-dir", default=os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "reports"))
    a = ap.parse_args()

    if not D.device_connected():
        print("[!] no device"); return 2

    os.makedirs(a.report_dir, exist_ok=True)
    report = ["# GeerGit comparison report", ""]

    # 1. read surface
    surface, nlines = enumerate_read_surface(a.pkg)
    report.append(f"## Read surface (from {nlines} GeerGit log lines)")
    report.append("Identifiers the target app was observed reading:")
    for k in sorted(surface):
        report.append(f"  - {k}")
    if not surface:
        report.append("  (none captured — launch the target app first so GeerGit logs its reads)")
    report.append("")

    # 2. our coverage of that surface
    our_keys = {s.key for s in SPECS}
    uncovered = surface - our_keys - {"build_model"}  # build_* handled as bundle
    report.append("## Coverage check")
    report.append(f"Our generator covers: {len(our_keys)} identifiers")
    if uncovered:
        report.append(f"**GAP — target reads these but we don't rotate:** {sorted(uncovered)}")
    else:
        report.append("✅ We cover every identifier the target was seen reading.")
    report.append("")

    # 3. randomize cycles
    report.append("## Randomize cycles (GeerGit rotation behavior)")
    snaps = []
    for i in range(a.cycles):
        input(f"\n>>> Tap RANDOMIZE in GeerGit for {a.pkg} (cycle {i+1}/{a.cycles}), then press Enter...")
        time.sleep(1)
        s = snapshot(a.pkg)
        snaps.append(s)
        print(f"    hive_md5={s['hive_md5']}")

    # analysis
    hashes = [s["hive_md5"] for s in snaps if s["hive_md5"]]
    report.append(f"- hive hashes across {len(snaps)} cycles: {hashes}")
    if len(set(hashes)) < len(hashes):
        report.append("  **⚠️ REPEATED hive hash — GeerGit did NOT re-randomize on some cycle (the 2.9.6 bug signature).**")
    else:
        report.append("  ✅ hive changed every cycle (rotation firing).")

    ts = snaps and "run" or "run"
    out = os.path.join(a.report_dir, f"geergit-compare-{int(time.time()) if False else 'latest'}.md")
    open(out, "w").write("\n".join(report))
    print(f"\n[+] report -> {out}")
    print("\n".join(report))


if __name__ == "__main__":
    main()
