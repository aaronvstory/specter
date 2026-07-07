"""
device.py — adb interaction layer (push profile, clear app, read live identifiers).

All adb calls funnel through here so they can be mocked in tests and swapped for a
different transport later. Never assumes root beyond `su -c` (matches the Pixel setup).
"""
import json
import subprocess
import tempfile
import os

from .validation import validate_pkg

PROFILE_DIR = "/data/local/tmp/ghostprint"


class AdbError(RuntimeError):
    pass


def _run(args, timeout=30):
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        raise AdbError("adb not found on PATH")
    except subprocess.TimeoutExpired:
        raise AdbError(f"adb timed out: {' '.join(args)}")
    return p.returncode, p.stdout.strip(), p.stderr.strip()


def adb(*args, timeout=30):
    return _run(["adb", *args], timeout=timeout)


def su(cmd, timeout=30):
    return adb("shell", "su", "-c", cmd, timeout=timeout)


def device_connected():
    rc, out, _ = adb("devices")
    lines = [l for l in out.splitlines()[1:] if l.strip() and "device" in l]
    return len(lines) > 0


def has_root():
    rc, out, _ = su("id")
    return rc == 0 and "uid=0" in out


def push_profile(profile, pkg):
    validate_pkg(pkg)
    """Write profile to a temp file and push to the phone's per-app profile path."""
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(profile, f)
        tmp = f.name
    try:
        su(f"mkdir -p {PROFILE_DIR}")
        rc, out, err = adb("push", tmp, f"{PROFILE_DIR}/{pkg}.json")
        if rc != 0:
            raise AdbError(f"push failed: {err or out}")
        su(f"chmod 644 {PROFILE_DIR}/{pkg}.json")
    finally:
        os.unlink(tmp)


def clear_app(pkg):
    validate_pkg(pkg)
    rc, out, err = su(f"pm clear {pkg}")
    if rc != 0 or "Success" not in out:
        raise AdbError(f"pm clear failed: {err or out}")


def read_live_identifiers():
    """Read OS-side ground-truth identifiers (what leaks if a surface is un-hooked)."""
    reads = {
        "ssaid_u0": "settings --user 0 get secure android_id",
        "serial": "getprop ro.serialno",
        "build_fingerprint": "getprop ro.build.fingerprint",
        "wifi_mac": "cat /sys/class/net/wlan0/address 2>/dev/null",
        "widevine_dir": "ls /data/vendor/mediadrm/ 2>/dev/null",
    }
    out = {}
    for k, c in reads.items():
        rc, so, _ = su(c)
        out[k] = so
    return out


def read_geergit_hook_log(logpath=None, pattern="GEERGIT"):
    """Return GeerGit's LSPosed-Bridge log lines (what identifiers the target reads)."""
    if logpath is None:
        rc, out, _ = su("ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1")
        logpath = out.strip()
    if not logpath:
        return []
    rc, out, _ = su(f"grep -a {pattern} {logpath} 2>/dev/null", timeout=30)
    return out.splitlines() if out else []
