#!/usr/bin/env python3
"""Add the dev test set (probe + FPJS demo + DevInfo) to Specter's LSPosed scope so they get hooked.

LSPosed stores module scope in a SQLite DB under /data/adb/lspd/ (root-only). There's no sqlite3 on the
device, so we read the DB to the PC, edit it with Python's built-in sqlite3, and write it back.

**Transport is base64-over-shell, NOT `adb pull`/`adb push`.** adbd runs in a different mount namespace on
this rooted (Magisk/zygisk) device, so a sync of a root-owned overlay file silently reads/writes the wrong
copy — which is exactly why the old pull-based version failed with "no such table: modules" (it pulled an
empty file). Streaming the bytes through `su -c base64` bypasses the sync namespace entirely.
(See memory `adb-push-namespace-gotcha`.)

SAFE: only edits Specter's module id (mid for com.specter); never touches GeerGit's scope (mid 101).

Usage: python scripts/scope_probe.py [serial]
"""
import base64
import os
import sqlite3
import subprocess
import sys
import tempfile

SERIAL = sys.argv[1] if len(sys.argv) > 1 else None
DB_ON_DEVICE = "/data/adb/lspd/config/modules_config.db"
SPECTER_PKG = "com.specter"
# The dev test set CLAUDE.md defines: probe (verify_on_device.py's target) + FPJS demo + DevInfo.
DEV_TARGETS = ["com.specter.probe", "com.fingerprintjs.android.fpjs_pro_demo", "com.liuzh.deviceinfo"]


def adb(*args, **kw):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, capture_output=True, timeout=120, **kw)


def pull_db(local_path):
    """Read the root DB to the PC by streaming base64 through the su shell (namespace-immune)."""
    r = adb("shell", "su", "-c", f"base64 -w0 {DB_ON_DEVICE}")
    b64 = r.stdout.decode("ascii", "ignore").strip()
    if not b64:
        raise SystemExit("FAIL: could not read the LSPosed DB (need root). " + r.stderr.decode("utf-8", "ignore"))
    data = base64.b64decode(b64)
    if data[:16] != b"SQLite format 3\x00":
        raise SystemExit(f"FAIL: pulled data is not a SQLite DB (got {len(data)} bytes) — transport problem.")
    with open(local_path, "wb") as fh:
        fh.write(data)


def push_db(local_path):
    """Write the edited DB back, streaming base64 into a staging file then `cp` over the original so its
    owner/mode/SELinux context are preserved (cp onto an existing file keeps the destination's attrs)."""
    b64 = base64.b64encode(open(local_path, "rb").read()).decode("ascii")
    stage = "/data/local/tmp/.lspd_scope_new.db"
    # write the staging file as root, decode, then cp over the live DB and clean up
    r = adb("shell", "su", "-c", f"base64 -d > {stage} && cp {stage} {DB_ON_DEVICE} && rm -f {stage} && echo OK",
            input=b64.encode("ascii"))
    if b"OK" not in r.stdout:
        raise SystemExit("FAIL: could not write the DB back: " + r.stderr.decode("utf-8", "ignore"))


def main():
    tmp = os.path.join(tempfile.gettempdir(), "lspd_scope.db")
    pull_db(tmp)

    c = sqlite3.connect(tmp)
    cur = c.cursor()
    row = cur.execute("SELECT mid FROM modules WHERE module_pkg_name=?", (SPECTER_PKG,)).fetchone()
    if not row:
        c.close()
        raise SystemExit(f"FAIL: Specter ({SPECTER_PKG}) not found in LSPosed modules — enable it first.")
    mid = row[0]
    added = []
    for pkg in DEV_TARGETS:
        seen = cur.execute("SELECT COUNT(*) FROM scope WHERE mid=? AND app_pkg_name=? AND user_id=0",
                           (mid, pkg)).fetchone()[0]
        if not seen:
            cur.execute("INSERT INTO scope (mid, app_pkg_name, user_id) VALUES (?,?,0)", (mid, pkg))
            added.append(pkg)
    c.commit()
    scope = sorted(r[0] for r in cur.execute("SELECT app_pkg_name FROM scope WHERE mid=?", (mid,)))
    c.close()

    if not added:
        print(f"dev test set already in Specter scope (mid={mid}). Nothing to do.")
        return 0
    print(f"added to Specter (mid={mid}) scope: {added}")
    print(f"scope now: {scope}")
    push_db(tmp)
    print("DB written back. Rebooting so LSPosed reloads scope...")
    adb("reboot")
    print("Rebooting — wait ~30s, unlock the screen, then run scripts/verify_on_device.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
