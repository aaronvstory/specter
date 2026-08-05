"""Back up a device's Specter vault (saved fingerprints AND saved logins) to backups/.

    python scripts/backup_vault.py                 # every connected device
    python scripts/backup_vault.py 192.168.50.19:5557
    python scripts/backup_vault.py --check         # report age, back up nothing

Run this BEFORE anything that can destroy `/data/data/com.specter/files`:

    pm clear · pm uninstall · adb uninstall · a factory reset · a Magisk module removal
    that takes the app with it · any "wipe app data" path in the UI

WHY THIS EXISTS (2026-08-06): a `pm clear com.specter` run to prove an unrelated point wiped the 4a's
vault. Nine fingerprints came back from a FOUR-DAY-OLD manual tarball; the one saved since then was
recovered only by luck, because LSPosed redirects the module's prefs to `/data/misc/<uuid>/prefs/` — which
`pm clear` does not reach — and that copy still held the full 72-field profile and its vault label. The
saved LOGINS were not so lucky: `files/appdata` came back empty and no backup had ever contained it.

A written rule had already existed and was not enough. This is the mechanical version.

The backup is a plain tarball of `files/` (vault + appdata), pulled through a shell rather than `adb pull`
— on a rooted device with Magisk's mount namespace, `adb pull`/`push` of a large file can report success
and move nothing (see CLAUDE.md). Every archive is verified by md5 against the device before it counts.
"""
import argparse
import base64
import datetime
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "backups"
APP_FILES = "/data/data/com.specter/files"
# The LSPosed-redirected prefs — OUTSIDE /data/data, which is why they survived the wipe that started
# all this. They hold the live identity, so they are worth capturing even though pm clear cannot reach them.
PREFS_GLOB = "/data/misc/*/prefs/com.specter/specter.xml"
STALE_DAYS = 3


def sh(serial: str, cmd: str) -> str:
    r = subprocess.run(["adb", "-s", serial, "shell", cmd],
                       capture_output=True, timeout=300)
    return r.stdout.decode("utf-8", "replace").replace("\r\n", "\n").strip()


def _private(path: Path, data: bytes) -> None:
    """Write owner-only. These files hold real login tarballs and identity preferences — `.gitignore`
    keeps them out of the repo but does nothing about another local account reading them, and the default
    umask is not private. Created 0600 from the start rather than chmod'd after, so the contents are never
    briefly world-readable.

    HONEST LIMIT: the mode is a POSIX concept. On Windows, `os.open`'s mode only toggles the read-only
    attribute — access is governed by inherited NTFS ACLs, so this call does NOT make the file private
    there and the resulting file will still read `-rw-r--r--`. Protecting it on Windows means putting the
    repo somewhere the account already restricts, not this line."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)


def devices() -> list[str]:
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=60).stdout
    return [ln.split()[0] for ln in out.splitlines()[1:] if ln.strip().endswith("device")]


def _safe(part: str, fallback: str) -> str:
    """One path COMPONENT, with everything that could steer a write out of `backups/` removed.

    `ro.product.device` and the adb serial are both read from the connected device, and both land in a
    directory name — so `../../x` or an absolute path would place the archive (which contains real login
    data) wherever the device asked. Whitelist rather than blacklist, and refuse a name that reduces to
    nothing or to a relative-path token."""
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", part).strip("._-")
    return cleaned if cleaned and cleaned not in (".", "..") else fallback


def device_name(serial: str) -> str:
    # `:` and `.` fold to `_` BEFORE sanitising. They are legal in a filename and _safe() would keep them,
    # but the naming has to stay stable: an adb serial is `192.168.50.19:5557`, and changing how it renders
    # renames every directory — which made `--check` report "NO BACKUP EVER" for devices that had one.
    # A backup checker that cannot find the backups is worse than no checker.
    return f"{_safe(sh(serial, 'getprop ro.product.device'), 'unknown')}-" \
           f"{_safe(serial.replace(':', '_').replace('.', '_'), 'device')}"


def latest_backup(name: str) -> Path | None:
    found = sorted(DEST.glob(f"{name}-*/vault.tgz"))
    return found[-1] if found else None


def backup(serial: str) -> bool:
    name = device_name(serial)
    listing = sh(serial, f"su -c 'ls -la {APP_FILES}/vault {APP_FILES}/appdata 2>&1'")
    n_fp = sh(serial, f"su -c 'ls {APP_FILES}/vault 2>/dev/null | wc -l'") or "0"
    n_ad = sh(serial, f"su -c 'ls {APP_FILES}/appdata 2>/dev/null | wc -l'") or "0"
    print(f"\n{serial}  ({name})")
    print(f"  fingerprints: {n_fp}   saved logins: {n_ad}")
    if n_fp == "0" and n_ad == "0":
        # NOT an error, and NOT silently skipped: an empty vault is exactly what a just-wiped device looks
        # like, and saying so is the difference between "nothing to save" and "you already lost it".
        print("  nothing to back up — the vault is EMPTY. If it should not be, do not run anything")
        print("  destructive; check /data/misc/*/prefs/com.specter/specter.xml, which pm clear cannot reach.")
        return False

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = DEST / f"{name}-{stamp}"
    # 0700, and NOT exist_ok: a directory already at this second means something else is writing there,
    # and quietly merging into it could mix two devices' archives under one name.
    DEST.mkdir(parents=True, exist_ok=True)
    try:
        out_dir.mkdir(mode=0o700)
    except FileExistsError:
        print(f"  FAILED: {out_dir.name} already exists — refusing to write into it")
        return False

    sh(serial, f"su -c 'cd {APP_FILES}/.. && tar czf /data/local/tmp/_vault_bk.tgz files'")
    want = (sh(serial, "su -c 'md5sum /data/local/tmp/_vault_bk.tgz'") or "").split()[0]
    b64 = sh(serial, "su -c 'base64 -w0 /data/local/tmp/_vault_bk.tgz'")
    blob = base64.b64decode(b64)
    got = hashlib.md5(blob).hexdigest()
    sh(serial, "su -c 'rm -f /data/local/tmp/_vault_bk.tgz'")
    if got != want:
        print(f"  FAILED: md5 mismatch (device {want}, received {got}) — NOT written")
        return False
    _private(out_dir / "vault.tgz", blob)

    prefs = sh(serial, f"su -c 'cat {PREFS_GLOB} 2>/dev/null'")
    if prefs:
        _private(out_dir / "specter-prefs.xml", prefs.encode("utf-8"))
    _private(out_dir / "listing.txt", listing.encode("utf-8"))
    print(f"  -> backups/{out_dir.name}/vault.tgz  ({len(blob):,} bytes, md5 {got})"
          + ("  + specter-prefs.xml" if prefs else ""))
    return True


def main() -> int:
    ap = argparse.ArgumentParser(description=(__doc__ or "").split("\n")[0])
    ap.add_argument("serial", nargs="?", help="adb serial; default = every connected device")
    ap.add_argument("--check", action="store_true", help="report backup age, write nothing")
    args = ap.parse_args()

    targets = [args.serial] if args.serial else devices()
    if not targets:
        print("no devices connected")
        return 1

    ok = True
    for serial in targets:
        if args.check:
            name = device_name(serial)
            last = latest_backup(name)
            if not last:
                print(f"{serial} ({name}): NO BACKUP EVER — run this before anything destructive")
                ok = False
                continue
            age = datetime.datetime.now() - datetime.datetime.fromtimestamp(last.stat().st_mtime)
            flag = "  <-- STALE" if age.days >= STALE_DAYS else ""
            print(f"{serial} ({name}): {last.parent.name}, {age.days}d old{flag}")
            ok = ok and age.days < STALE_DAYS
        else:
            ok = backup(serial) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
