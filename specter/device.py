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

PROFILE_DIR = "/data/local/tmp/specter"


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
    """
    Run cmd as root on the device.

    IMPORTANT: adb must receive `su -c '<whole cmd>'` as a SINGLE shell string, not as separate
    argv tokens. With argv tokens (["adb","shell","su","-c",cmd]) the device shell binds only the
    first word of a compound command to `su -c` and runs the rest (`&& cp ...`) as the unprivileged
    shell user — which then fails on root-owned paths. We wrap cmd in single quotes (escaping any
    embedded single quotes) and pass the whole `su -c '...'` as one adb-shell argument.
    """
    escaped = cmd.replace("'", "'\\''")
    return adb("shell", f"su -c '{escaped}'", timeout=timeout)


def device_connected():
    rc, out, _ = adb("devices")
    lines = [l for l in out.splitlines()[1:] if l.strip() and "device" in l]
    return len(lines) > 0


def has_root():
    rc, out, _ = su("id")
    return rc == 0 and "uid=0" in out


def push_profile(profile, pkg):
    """
    Write profile to the phone's per-app profile path.

    adb push runs as the `shell` user, which cannot write into a root-owned PROFILE_DIR.
    So we push to a shell-writable staging path, then `su cp` it into place and fix perms.
    The module reads it as the app process; PROFILE_DIR must be world-readable.
    """
    validate_pkg(pkg)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(profile, f)
        tmp = f.name
    # stage in the shell user's OWN dir (adb push writes as shell; root can always read it back)
    stage = f"/data/local/tmp/{pkg}.specter.json"
    dest = f"{PROFILE_DIR}/{pkg}.json"
    try:
        rc, out, err = adb("push", tmp, stage)
        # adb writes its success line ("N file pushed") to stderr — only a literal error is failure.
        combined = (out + err).lower()
        if "error" in combined or "failed" in combined or "pushed" not in combined:
            raise AdbError(f"adb push failed: {err or out}")
        # Use `cp` (not a `>`/`tee` redirect): a shell redirect inside `su -c` gets opened by the
        # outer adb shell as the unprivileged `shell` user (permission denied under SELinux). `cp`
        # opens the dest inside the root context. World-readable so the target app can read it.
        rc, out, err = su(
            f"mkdir -p {PROFILE_DIR} && cp {stage} {dest} && "
            f"chmod 755 {PROFILE_DIR} && chmod 644 {dest} && rm -f {stage}")
        # verify it actually landed (cat is more reliable than cp under SELinux)
        rc2, out2, _ = su(f"test -s {dest} && echo OK")
        if "OK" not in out2:
            raise AdbError(f"profile did not land at {dest}: {err or out}")
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
