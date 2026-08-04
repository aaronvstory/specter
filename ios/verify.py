#!/usr/bin/env python3
"""Efficacy check for SpecterTweak — the iOS analog of Android's verify_on_device.py.

Compares what SpecterProbe read back on-device (probe_result.json) against the profile we told the
tweak to apply (the tweak plist / a generated profile). Prints a per-signal PASS/FAIL table; exit 0
only if every spoofed signal matches on every read path.

Usage:
  python verify.py --profile <tweak.plist|profile.json> --probe probe_result.json
  python verify.py --profile ... --probe spoofed.json --baseline real.json   # show the real->spoof flip

Pull the probe result off-device first, e.g.:
  ssh -p 2222 root@127.0.0.1 'cat /var/mobile/Library/Specter/probe_result.json' > probe_result.json
"""
import argparse
import json
import plistlib
import sys

# (label, profile-key, [probe read-paths that must ALL equal the profile value])
# NB: the obfuscated-hash MG paths (MG.obf.*) are checked too — a fingerprinter querying MobileGestalt by
# hash rather than plaintext leaked the real value once; without these rows verify.py would mask a regression.
CHECKS = [
    ("model (ProductType/hw.machine/uname)", "ProductType",
        ["MG.ProductType", "MG.obf.ProductType", "sysctl.hw.machine", "uname.machine"]),
    ("board (hw.model/HWModelStr)", "HWModel", ["sysctl.hw.model", "MG.HWModelStr", "MG.obf.HWModelStr"]),
    ("RAM (hw.memsize)", "MemSize", ["sysctl.hw.memsize"]),
    ("CPU count (hw.ncpu)", "NCPU", ["sysctl.hw.ncpu"]),
    ("OS version", "OSVersion", ["UIDevice.systemVersion", "MG.ProductVersion"]),
    ("OS build", "OSBuild", ["sysctl.kern.osversion", "MG.BuildVersion"]),
    ("IDFV", "IDFV", ["UIDevice.identifierForVendor"]),
    ("region", "RegionInfo", ["MG.RegionInfo"]),
    ("device name", "DeviceName", ["UIDevice.name"]),
]


def load(path):
    """Load a profile that may be either a plist (tweak) or JSON (generator)."""
    with open(path, "rb") as f:
        data = f.read()
    if data.lstrip()[:1] in (b"{", b"["):
        return json.loads(data.decode("utf-8"))
    return plistlib.loads(data)


def norm(v):
    if v is None:
        return None
    if isinstance(v, bool):
        return v
    return str(v).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", required=True, help="tweak plist or generated profile json (expected values)")
    ap.add_argument("--probe", required=True, help="probe_result.json read back from the device")
    ap.add_argument("--baseline", help="optional pre-spoof probe_result.json to show the real->spoof flip")
    args = ap.parse_args()

    prof = load(args.profile)
    # accept either the tweak-plist keys or the raw generator json (map the few that differ)
    if "product_type" in prof:  # raw generator json
        prof = {
            "ProductType": prof["product_type"], "HWModel": prof["hw_model"],
            "MemSize": prof["memsize_bytes"], "NCPU": prof["ncpu"], "OSVersion": prof["os_version"],
            "OSBuild": prof["os_build"], "IDFV": prof["identifier_for_vendor"],
            "RegionInfo": prof["region"], "DeviceName": prof.get("device_name", "iPhone"),
        }
    probe = json.loads(open(args.probe, "r", encoding="utf-8").read())
    base = json.loads(open(args.baseline, "r", encoding="utf-8").read()) if args.baseline else None

    print(f"{'signal':40} {'expected':22} {'read paths':8} result")
    print("-" * 86)
    all_ok = True
    for label, pkey, paths in CHECKS:
        exp = norm(prof.get(pkey))
        if exp is None:
            continue
        oks, details = [], []
        for rp in paths:
            got = norm(probe.get(rp))
            ok = (got == exp)
            oks.append(ok)
            if not ok:
                b = norm(base.get(rp)) if base else None
                details.append(f"{rp}={got}" + (f" (real was {b})" if base else ""))
        passed = all(oks)
        all_ok &= passed
        mark = "✅" if passed else "❌"
        flip = ""
        if base and passed:
            realv = norm(base.get(paths[0]))
            if realv and realv != exp:
                flip = f"  (was {realv})"
        print(f"{label:40} {str(exp)[:22]:22} {len(paths):>2}/{len(paths):<5} {mark}{flip}")
        for d in details:
            print(f"    ↳ {d}")

    print("-" * 86)
    print("ALL SPOOFED ✅" if all_ok else "LEAKS PRESENT ❌ — some read paths still return the real value")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
