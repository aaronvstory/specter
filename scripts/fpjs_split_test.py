#!/usr/bin/env python3
"""
fpjs_split_test.py — the definitive "did Specter beat FingerprintJS" gate.

Applies TWO very different device profiles to the FPJS demo (with `push --no-clear`, so the demo's own
API keys survive), triggers an identification after each, reads the on-screen visitorId + eventId via
UI automation, and reports whether the two visitorIds DIFFER (the win) or are the SAME (something still
links them).

PREREQUISITE (one manual step, cannot be scripted — the keys live in encrypted, device-bound prefs):
  Open the FPJS demo -> Settings -> "Use your API keys" = ON -> paste the Public key. Then the demo
  identifies in YOUR OWN workspace (not the shared public-demo tier, whose id ignores client signals —
  proven 2026-07-26). Do NOT `pm clear`/`rotate` after; use only this script, which uses `--no-clear`.

Optionally set FPJS_SECRET_KEY in the env to also pull each event's raw server signals (AP region), so a
SAME-id result can be diffed to find what stayed constant.

Usage:  python scripts/fpjs_split_test.py [serial]
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

DEMO = "com.fingerprintjs.android.fpjs_pro_demo"
SERIAL = sys.argv[1] if len(sys.argv) > 1 else None
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# On this 1080x2280 Pixel 4: the fingerprint icon center (fallback; normally found from the UI dump)
# and the "While using the app" location dialog option.
TAP_FINGERPRINT = (540, 1411)
TAP_LOCATION_WHILE_USING = (539, 1183)


def adb(*args, **kw):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=kw.get("timeout", 40)).stdout


def cli(*args):
    py = os.path.join(ROOT, ".venv", "Scripts", "python.exe")
    if not os.path.exists(py):
        py = sys.executable
    return subprocess.run([py, "-m", "specter.cli", *args], cwd=ROOT, capture_output=True, text=True).stdout


def wake():
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(1)


def dismiss_location_if_present():
    focus = adb("shell", "dumpsys", "window")
    if "permissioncontroller" in focus:
        adb("shell", "input", "tap", *map(str, TAP_LOCATION_WHILE_USING))
        time.sleep(2)


def _ui_dump():
    """Dump the UI hierarchy XML and return it as text (via a file on /sdcard, the reliable path)."""
    adb("shell", "uiautomator", "dump", "/sdcard/_split.xml")
    return adb("shell", "cat", "/sdcard/_split.xml")


def identify_and_read():
    """Force-stop + relaunch the demo, tap the fingerprint icon (found from its clickable bounds so the
    tap is layout-robust), then read the visitorId + eventId from the rendered result."""
    adb("shell", "am", "force-stop", DEMO)
    time.sleep(1)
    adb("shell", "monkey", "-p", DEMO, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(6)
    dismiss_location_if_present()

    # The fingerprint icon is the large clickable node in the vertical middle of the screen. Find its
    # bounds from the dump and tap the center — far more reliable than a hardcoded coordinate.
    cx, cy = _find_fingerprint_icon(_ui_dump())
    adb("shell", "input", "tap", str(cx), str(cy))
    time.sleep(12)

    dump = _ui_dump()
    vid = _first(r'text="([0-9A-Za-z]{20})"', dump)
    eid = _first(r'text="(\d{13}\.[A-Za-z0-9]{6})"', dump)
    return vid, eid


def _find_fingerprint_icon(dump):
    """Center of the biggest clickable node in the middle band of the screen (the fingerprint icon).
    Falls back to a sensible default for the 1080x2280 Pixel 4."""
    best, best_area = None, 0
    for m in re.finditer(r'clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump):
        x1, y1, x2, y2 = map(int, m.groups())
        cy = (y1 + y2) // 2
        area = (x2 - x1) * (y2 - y1)
        # middle band, roughly square, biggest -> the fingerprint icon
        if 900 < cy < 1800 and area > best_area:
            best, best_area = ((x1 + x2) // 2, cy), area
    return best or TAP_FINGERPRINT


def _first(pat, text):
    m = re.search(pat, text)
    return m.group(1) if m else None


def rotate_no_clear(seed_label):
    """new + push --no-clear so the demo's API keys survive."""
    cli("new")
    out = cli("push", "--pkg", DEMO, "--no-clear")
    model = ""
    prof = adb("shell", "cat", f"/data/local/tmp/specter/{DEMO}.json")
    try:
        model = json.loads(prof).get("build_model", "?")
    except Exception:
        pass
    print(f"  [{seed_label}] applied: {model}")
    return model


def pull_event(event_id):
    secret = os.environ.get("FPJS_SECRET_KEY")
    if not secret or not event_id:
        return None
    url = f"https://ap.api.fpjs.io/events/{event_id}"
    req = urllib.request.Request(url, headers={"Auth-API-Key": secret})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)
    except Exception as e:
        print(f"  (server API read failed: {e})")
        return None


def main():
    wake()
    print("FPJS split test — applying two different profiles (push --no-clear).\n")

    ma = rotate_no_clear("A")
    va, ea = identify_and_read()
    print(f"  [A] visitorId={va}  eventId={ea}\n")

    mb = rotate_no_clear("B")
    vb, eb = identify_and_read()
    print(f"  [B] visitorId={vb}  eventId={eb}\n")

    if not va or not vb:
        print("RESULT: could not read a visitorId from the screen. Is the demo showing a result, and are "
              "its API keys entered (Settings -> Use your API keys)? Re-run after confirming.")
        return 2

    if va != vb:
        print(f"RESULT: ✅ WIN — two profiles ({ma} vs {mb}) produced DIFFERENT visitorIds.")
        print("        Specter beats FingerprintJS: the applied identity drives the id.")
        return 0

    print(f"RESULT: ❌ SAME visitorId ({va}) across {ma} vs {mb}.")
    da, db = pull_event(ea), pull_event(eb)
    if da and db:
        _diff_signals(da, db)
    else:
        print("        Set FPJS_SECRET_KEY (AP region) to auto-diff the raw server signals and find the "
              "constant. If firstSeenAt predates today, you're in the shared demo workspace — enter YOUR "
              "keys (see the header).")
    return 1


def _diff_signals(a, b):
    def flat(o, p=""):
        out = {}
        if isinstance(o, dict):
            for k, v in o.items():
                out.update(flat(v, f"{p}.{k}" if p else k))
        elif isinstance(o, list):
            out[p] = json.dumps(o)[:120]
        else:
            out[p] = o
        return out
    fa, fb = flat(a.get("products", {})), flat(b.get("products", {}))
    const = [k for k in sorted(set(fa) & set(fb)) if fa[k] == fb[k]
             and not any(n in k for n in ("requestId", "timestamp", "time", "tag", "secret", "url",
                                          "lastSeen", "intervals"))]
    print("        CONSTANT signals across both events (candidate anchors):")
    for k in const:
        if any(s in k for s in ("browserDetails", "rootApps", "ipInfo.v4.address", "firstSeenAt",
                                "device", "os", "userAgent", "sdk")):
            print(f"          {k} = {fa[k]}")


if __name__ == "__main__":
    sys.exit(main())
