#!/usr/bin/env python3
"""One-time: add com.specter.probe to Specter's LSPosed scope so the verification probe gets hooked.

LSPosed stores module scope in a SQLite DB. There's no sqlite3 on the device, so we pull the DB,
edit it with Python's built-in sqlite3 on the PC, push it back, and reboot (LSPosed reloads scope on
boot). SAFE: only adds the probe to Specter's module id; never touches GeerGit's scope (its real
DoorDash/fleet targets). Run once after installing the probe APK.

Usage: python scripts/scope_probe.py [serial]
"""
import sqlite3, subprocess, sys, tempfile, os

SERIAL = sys.argv[1] if len(sys.argv) > 1 else None
DB_ON_DEVICE = "/data/adb/lspd/config/modules_config.db"
SPECTER_PKG = "com.fleet.idrotate"
PROBE_PKG = "com.specter.probe"


def adb(*args):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=60)


def su(cmd):
    return adb("shell", "su", "-c", cmd)


def main():
    tmp = os.path.join(tempfile.gettempdir(), "lspd_scope.db")
    su(f"cp {DB_ON_DEVICE} /data/local/tmp/lspd.db && chmod 644 /data/local/tmp/lspd.db")
    adb("pull", "/data/local/tmp/lspd.db", tmp)
    if not os.path.exists(tmp):
        print("FAIL: could not pull LSPosed DB (need root).")
        return 2

    c = sqlite3.connect(tmp)
    cur = c.cursor()
    row = cur.execute("SELECT mid FROM modules WHERE module_pkg_name=?", (SPECTER_PKG,)).fetchone()
    if not row:
        print(f"FAIL: Specter ({SPECTER_PKG}) not found in LSPosed modules — enable it first.")
        return 2
    mid = row[0]
    exists = cur.execute("SELECT COUNT(*) FROM scope WHERE mid=? AND app_pkg_name=?",
                         (mid, PROBE_PKG)).fetchone()[0]
    if exists:
        print(f"{PROBE_PKG} already in Specter scope (mid={mid}). Nothing to do.")
        c.close()
        return 0
    cur.execute("INSERT INTO scope (mid, app_pkg_name, user_id) VALUES (?,?,0)", (mid, PROBE_PKG))
    c.commit()
    scope = [r[0] for r in cur.execute("SELECT app_pkg_name FROM scope WHERE mid=?", (mid,))]
    c.close()
    print(f"added {PROBE_PKG} to Specter (mid={mid}) scope: {scope}")

    adb("push", tmp, "/data/local/tmp/lspd.db")
    su(f"cp /data/local/tmp/lspd.db {DB_ON_DEVICE} && chown 0:0 {DB_ON_DEVICE} && chmod 660 {DB_ON_DEVICE}")
    print("DB pushed back. Rebooting so LSPosed reloads scope...")
    adb("reboot")
    print("Rebooting — wait ~30s, then run scripts/verify_on_device.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
