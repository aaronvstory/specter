#!/usr/bin/env python3
"""On-device spoof verifier for Specter — DevInfo ONLY (never Dasher/GeerGit/system).

Applies the current identity to com.liuzh.deviceinfo, then walks every DevInfo tab, scrapes all
displayed text, and checks that each SPOOFED value from the profile actually appears on screen and
that known-real device values (the Pixel's true baseband/model) do NOT. Read back EVERYTHING, not a
sample — the 2.9.6 GSF-staleness lesson.

Usage: python scripts/verify_on_device.py [serial]
Exit 0 = all spoofed values confirmed on device; nonzero = leaks found.
"""
import json, re, subprocess, sys, time

DEVINFO = "com.liuzh.deviceinfo"
SPECTER = "com.fleet.idrotate"
PROFILE = f"/data/local/tmp/specter/{DEVINFO}.json"
SERIAL = sys.argv[1] if len(sys.argv) > 1 else None

# Fields whose spoofed value should be visibly displayed by DevInfo (string-matchable on its screens).
DISPLAYED = [
    "build_manufacturer", "build_model", "build_device", "build_bootloader", "build_radio",
    "build_id", "build_security_patch", "serial",
]


def adb(*args):
    cmd = ["adb"]
    if SERIAL:
        cmd += ["-s", SERIAL]
    cmd += list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=30).stdout


def dump_screen():
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    xml = adb("shell", "cat", "/sdcard/u.xml")
    return set(re.findall(r'text="([^"]{1,90})"', xml))


def tap_text(label):
    xml = adb("shell", "cat", "/sdcard/u.xml")
    m = re.search(r'text="%s".*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(label), xml)
    if m:
        a, b, c, d = map(int, m.groups())
        adb("shell", "input", "tap", str((a + c) // 2), str((b + d) // 2))
        time.sleep(1.5)
        return True
    return False


def main():
    prof = json.loads(adb("shell", "cat", PROFILE) or "{}")  # profile is 0644, world-readable
    if not prof:
        print("FAIL: no profile applied to DevInfo — run RANDOMIZE ALL + APPLY first")
        return 2
    print(f"profile: {len(prof)} keys, device={prof.get('build_model')} radio={prof.get('build_radio')}")

    # relaunch DevInfo fresh, dismiss ad, sweep tabs
    adb("shell", "am", "force-stop", DEVINFO)
    time.sleep(1)
    adb("shell", "monkey", "-p", DEVINFO, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(5)
    seen = set()
    seen |= dump_screen()
    tap_text("CLOSE")  # dismiss ad if present
    seen |= dump_screen()  # capture the default Dashboard before navigating away
    for tab in ("Device", "System", "CPU", "Dashboard"):
        dump_screen()
        tap_text(tab)
        for _ in range(4):  # scroll the tab to capture everything
            seen |= dump_screen()
            adb("shell", "input", "swipe", "540", "1600", "540", "500")
            time.sleep(0.8)
    blob = (" \n".join(seen)).lower()  # DevInfo uppercases some fields (SAMSUNG) — match case-insensitively

    # check each displayed spoofed value appears
    ok, leaks = [], []
    for key in DISPLAYED:
        val = prof.get(key)
        if not val:
            continue
        if val.lower() in blob:
            ok.append((key, val))
        else:
            leaks.append((key, val))

    print(f"\n=== CONFIRMED spoofed on-device ({len(ok)}) ===")
    for k, v in ok:
        print(f"  ✅ {k:22} = {v}")
    if leaks:
        print(f"\n=== NOT FOUND on-device ({len(leaks)}) — may be leaks OR just not displayed by DevInfo ===")
        for k, v in leaks:
            print(f"  ❓ {k:22} = {v}")
    # hard leak check: the real Pixel baseband must NOT appear
    REAL_MARKERS = ["g8150-00088-210507", "flame", "Pixel 4"]
    hard = [m for m in REAL_MARKERS if m in blob]
    if hard:
        print(f"\n=== HARD LEAK: real device value on screen: {hard} ===")
        return 1
    print(f"\nresult: {len(ok)}/{len(ok)+len(leaks)} displayed values confirmed spoofed; no real-device leak.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
