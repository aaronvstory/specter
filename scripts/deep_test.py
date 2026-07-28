#!/usr/bin/env python3
"""
deep_test.py — standardized on-device deep test for ANY Specter target app.

For a target package it runs the fleet-realistic gauntlet and prints a pass/fail report:
  1. reads the REAL device values that must never leak (model/board/fingerprint/android_id/serial),
  2. applies identity A (via `python -m specter.cli rotate`), arms trace, launches the target,
     captures every `[specter]` hook that fired (what the app ACTUALLY read),
  3. rotates to identity B (rotate = new + deep-clean + apply),
  4. checks THREE things against the target's own /data:
     - LEAK: none of the real-device values appear anywhere (0 = clean),
     - CAPTURE: identity B's android_id/model ARE present (the app saw the new device),
     - ISOLATION: identity A's android_id is GONE after the rotate (no A->B carryover / account link),
  5. reports which signals the app read (android_id / MediaDrm-Widevine / file-timestamps / props).

Usage:  python scripts/deep_test.py <serial> <pkg> [--wait N]
Example: python scripts/deep_test.py 17031JEC204747 com.squareup.cash

Safe: only clears + re-applies to the ONE target package you name. Never touches other apps.
Requires: target in Specter's LSPosed scope + a Specter profile dir. Root on device.
"""
import subprocess, sys, time, re

SPECTER_DIR = "/data/local/tmp/specter"

def sh(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True).stdout

def su(serial, cmd):
    return sh(serial, "shell", "su", "-c", cmd)

def cli_rotate(serial, pkg):
    # The CLI targets the single connected device; force serial via ANDROID_SERIAL for safety.
    import os
    env = dict(os.environ, ANDROID_SERIAL=serial)
    out = subprocess.run([sys.executable, "-m", "specter.cli", "rotate", "--pkg", pkg],
                         capture_output=True, text=True, env=env).stdout
    # parse "[+] <mfr> <model>  android_id=<hex>  gsf=..."
    m = re.search(r"android_id=([0-9a-f]+)", out)
    aid = m.group(1) if m else None
    m2 = re.search(r"\[\+\]\s+(.*?)\s+android_id=", out)
    dev = m2.group(1).strip() if m2 else "?"
    return aid, dev, out.strip()

def real_values(serial):
    g = lambda p: sh(serial, "shell", "getprop", p).strip()
    return {
        "model": g("ro.product.model"),
        "board": g("ro.product.board"),
        "device": g("ro.product.device"),
        "fingerprint": g("ro.build.fingerprint"),
        "android_id": sh(serial, "shell", "settings", "get", "secure", "android_id").strip(),
        "serial": g("ro.serialno"),
    }

def arm_trace(serial, pkg):
    # inject "trace":"1" at the front of the profile JSON (idempotent-ish; sed only adds if not present)
    p = f"{SPECTER_DIR}/{pkg}.json"
    if '"trace"' not in su(serial, f"cat {p} 2>/dev/null"):
        su(serial, f'sed -i \'s/^{{/{{"trace":"1",/\' {p}')

def launch_and_capture(serial, pkg, wait):
    sh(serial, "shell", "am", "force-stop", pkg)
    sh(serial, "logcat", "-c")   # clear AFTER force-stop so no lines from a prior run/app bleed in
    sh(serial, "shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(wait)
    # get the target's live pid, then let LOGCAT filter by pid server-side (--pid, Android 7+) — far more
    # reliable than a client-side substring match, which would false-match the pid inside a TID column or a
    # message body of an unrelated process.
    pid = sh(serial, "shell", "pidof", pkg).strip().split()
    pid = pid[0] if pid else None
    if pid:
        log = sh(serial, "logcat", "-d", "--pid", pid)
    else:
        log = sh(serial, "logcat", "-d")   # app died/never started — capture all (report will show 0 reads)
    reads = {}
    for tag in ("idtrace", "lastmod", "osstat", "drm"):
        reads[tag] = len(re.findall(rf"\[specter\]\[{tag}\]", log))
    ids = sorted(set(re.findall(r"idtrace\]\s*(.*?->.*)", log)))
    widevine = bool(re.search(r"MediaDrm|deviceUniqueId", log))
    return reads, ids, widevine, (pid is not None)

def count_in_data(serial, pkg, needle):
    """Files under the app's data dir containing `needle`. Returns an int count (0 = truly absent), or None
    if the check COULDN'T RUN (no needle / su failed / non-numeric output) — the caller must treat None as
    'inconclusive', never as 'clean', so a broken check can't masquerade as a PASS."""
    if not needle:
        return None
    out = su(serial, f"grep -rc '{needle}' /data/data/{pkg}/ 2>/dev/null | grep -v ':0' | wc -l")
    s = out.strip()
    return int(s) if s.isdigit() else None

def data_dir_ok(serial, pkg):
    """The app's data dir exists AND su can read it — precondition for the leak/isolation greps to mean
    anything. Without this, an inaccessible dir makes every grep return 0 = a silent false 'clean'."""
    return su(serial, f"test -d /data/data/{pkg} && echo OK").strip() == "OK"

def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    serial, pkg = sys.argv[1], sys.argv[2]
    wait = 12
    if "--wait" in sys.argv:
        wait = int(sys.argv[sys.argv.index("--wait") + 1])

    print(f"\n=== deep_test: {pkg} on {serial} ===\n")
    real = real_values(serial)
    print("REAL device (must NOT leak):")
    for k, v in real.items():
        print(f"  {k:12} {v}")

    def inconclusive(why):
        # A check couldn't actually RUN — never report PASS/FAIL off a broken run.
        print(f"\n=== {pkg}: INCONCLUSIVE ⚠️ — {why} (fix the setup + re-run) ===\n")
        sys.exit(3)

    # Precondition: su reachable at all.
    if su(serial, "id -u").strip() != "0":
        inconclusive("no root (su) on device")

    # --- identity A ---
    print("\n[1/3] applying identity A …")
    aid_a, dev_a, ok_a = cli_rotate(serial, pkg)
    print(f"  A = {dev_a}  android_id={aid_a}")
    if not ok_a or not aid_a:
        inconclusive("`cli rotate` for identity A failed (non-zero exit / no android_id parsed) — "
                     "is the target scoped + a profile dir present?")
    arm_trace(serial, pkg)
    reads_a, ids_a, wv_a, alive_a = launch_and_capture(serial, pkg, wait)
    print(f"  A reads: {reads_a}  widevine_read={wv_a}  app_launched={alive_a}")
    for line in ids_a:
        print(f"    read: {line}")

    # Precondition: the app actually has a data dir we can read (else every grep is a false 0).
    if not data_dir_ok(serial, pkg):
        inconclusive(f"/data/data/{pkg} is missing or unreadable via su — leak/isolation greps would be "
                     "meaningless (silent false-clean). Did the app launch + write data?")

    # --- identity B (rotate) ---
    print("\n[2/3] rotating to identity B (new + deep-clean + apply) …")
    aid_b, dev_b, ok_b = cli_rotate(serial, pkg)
    print(f"  B = {dev_b}  android_id={aid_b}")
    if not ok_b or not aid_b:
        inconclusive("`cli rotate` for identity B failed — cannot test isolation")
    arm_trace(serial, pkg)
    reads_b, ids_b, wv_b, alive_b = launch_and_capture(serial, pkg, wait)
    print(f"  B reads: {reads_b}  widevine_read={wv_b}  app_launched={alive_b}")
    for line in ids_b:
        print(f"    read: {line}")

    # --- checks --- (count_in_data returns None if a grep couldn't run => treat as INCONCLUSIVE, not clean)
    print("\n[3/3] verdicts:")
    leaks = {k: count_in_data(serial, pkg, v) for k, v in real.items() if v and v not in ("unknown", "")}
    if any(n is None for n in leaks.values()):
        inconclusive("a leak-scan grep produced non-numeric output (su/data-dir error) — cannot trust result")
    leaked = {k: n for k, n in leaks.items() if n and n > 0}
    print(f"  LEAK SCAN (real values in {pkg} data): " +
          ("NONE ✅" if not leaked else f"⚠️ LEAKED {leaked}"))
    for k, n in leaks.items():
        print(f"      real {k:12} = {real[k][:40]:40} -> {n} occ")
    # CAPTURE: B present
    b_stored = count_in_data(serial, pkg, aid_b)
    if b_stored is None:
        inconclusive("CAPTURE grep for B's android_id could not run")
    assert b_stored is not None   # inconclusive() exited otherwise
    print(f"  CAPTURE (B's android_id in data): {b_stored} " + ("✅" if b_stored > 0 else "⚠️ app did not store B"))
    # ISOLATION: A gone after rotate
    a_after = count_in_data(serial, pkg, aid_a)
    if a_after is None:
        inconclusive("ISOLATION grep for A's android_id could not run")
    assert a_after is not None
    iso_ok = a_after == 0
    print(f"  ISOLATION (A's android_id after rotate, want 0): {a_after} " + ("✅" if iso_ok else "⚠️ CARRYOVER"))
    # Widevine
    print(f"  WIDEVINE read by app: {'yes (spoof matters)' if (wv_a or wv_b) else 'no (not on these launches)'}")

    ok = (not leaked) and iso_ok and b_stored > 0
    print(f"\n=== {pkg}: {'PASS ✅ — spoofing clean + isolated' if ok else 'REVIEW ⚠️ — see above'} ===\n")
    sys.exit(0 if ok else 2)

if __name__ == "__main__":
    main()
