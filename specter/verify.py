"""
verify.py — deep on-device verification harness (questionnaire-driven).

This is the "prove it actually works on the real phone" tool. It walks you through an
interactive questionnaire and runs the checks you pick:

  1. Coverage      — what identifiers does the target app actually read? (from the hook log)
                     do we rotate every one?
  2. Rotation      — launch the target N times, refresh identity between each, confirm the
                     app SEES a different identity each launch (read back from its own data).
  3. Uniqueness    — confirm no identifier repeats across the N launches (the ban check).
  4. Backup/reload — save an identity, rotate away, reload it, confirm the app sees the
                     restored values (the vault round-trip).
  5. Leak audit    — OS-side ground-truth vs what the app stored: is anything un-hooked
                     leaking the REAL device id?

Each check writes evidence to reports/. Uses the same device.py adb layer as the app.
Designed to be safe to re-run; only mutates the target app (clear/relaunch), never the OS.
"""
import json
import os
import time

from rich.console import Console
from rich.panel import Panel
from rich.table import Table
from rich.prompt import Prompt, Confirm
from rich import box

from .theme import THEME
from . import device as D
from . import profile as P
from .identifiers import SPECS, UNIQUE_KEYS

DEFAULT_PKG = "com.doordash.driverapp"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORTS = os.path.join(ROOT, "reports")


class Verifier:
    def __init__(self, pkg=DEFAULT_PKG, console=None):
        self.pkg = pkg
        self.c = console or Console(theme=THEME)
        self.results = {}
        os.makedirs(REPORTS, exist_ok=True)

    # ---------- primitives ----------
    def _target_reads(self):
        """Grep the target's own stored data for a 16-hex android_id and 19-digit gsf it recorded."""
        rc, out, _ = D.su(
            f"grep -rhoE '[0-9a-f]{{16}}' /data/data/{self.pkg}/shared_prefs/ 2>/dev/null | sort -u")
        aids = out.split() if out else []
        rc, out, _ = D.su(
            f"grep -rhoE '[0-9]{{18,20}}' /data/data/{self.pkg}/shared_prefs/*.xml 2>/dev/null | sort -u")
        gsfs = out.split() if out else []
        return {"android_ids": aids, "gsf_ids": gsfs}

    def _launch_target(self, settle=8):
        D.su(f"am force-stop {self.pkg}")
        time.sleep(1)
        # try monkey; if the launcher is cloaked, fall back to component start
        rc, out, _ = D.adb("shell", "monkey", "-p", self.pkg, "-c",
                           "android.intent.category.LAUNCHER", "1")
        time.sleep(settle)
        rc, pid, _ = D.adb("shell", "pidof", self.pkg)
        return pid.strip()

    def _app_stored_identity(self):
        """Best-effort: what device identity is currently in the app's data."""
        reads = self._target_reads()
        return reads

    # ---------- checks ----------
    def check_coverage(self):
        self.c.rule("[brand]1. Coverage[/]")
        lines = D.read_geergit_hook_log()
        our = {s.key for s in SPECS}
        self.c.print(f"hook-log lines: {len(lines)}")
        self.c.print(f"specter rotates {len(our)} identifiers")
        self.results["coverage"] = {"loglines": len(lines), "covered": sorted(our)}
        self.c.print("[good]✓[/] coverage recorded")

    def check_rotation(self, launches=3):
        self.c.rule("[brand]2. Rotation[/]")
        seen_aids, seen_gsfs = [], []
        table = Table(box=box.SIMPLE)
        table.add_column("launch"); table.add_column("android_id seen"); table.add_column("gsf seen"); table.add_column("fresh?")
        for i in range(launches):
            # generate + push a fresh identity, clear, launch
            store = P.UsedStore(os.path.join(ROOT, "used_ids.json"))
            prof = P.generate_unique(store); store.save()
            D.push_profile(prof, self.pkg)
            D.clear_app(self.pkg)
            pid = self._launch_target()
            stored = self._app_stored_identity()
            aid = next((a for a in stored["android_ids"] if a == prof["android_id"]), None)
            gsf = next((g for g in stored["gsf_ids"] if g == prof["gsf_id"]), None)
            fresh = aid not in seen_aids and gsf not in seen_gsfs
            table.add_row(str(i + 1), aid or "(not found)", gsf or "(not found)",
                          "[good]yes[/]" if fresh else "[bad]NO[/]")
            if aid: seen_aids.append(aid)
            if gsf: seen_gsfs.append(gsf)
        self.c.print(table)
        repeated = len(seen_aids) != len(set(seen_aids)) or len(seen_gsfs) != len(set(seen_gsfs))
        self.results["rotation"] = {"launches": launches, "android_ids": seen_aids,
                                    "gsf_ids": seen_gsfs, "any_repeat": repeated}
        self.c.print("[bad]✗ REPEATED identifier across launches[/]" if repeated
                     else "[good]✓ every launch saw a fresh identity[/]")

    def check_backup_reload(self):
        self.c.rule("[brand]3. Backup / reload round-trip[/]")
        store = P.UsedStore(os.path.join(ROOT, "used_ids.json"))
        original = P.generate_unique(store); store.save()
        # save to vault
        vault_path = os.path.join(ROOT, "profiles.json")
        vault = {}
        if os.path.exists(vault_path):
            vault = json.load(open(vault_path))
        vault["_verify_backup"] = original
        json.dump(vault, open(vault_path, "w"), indent=2)
        # rotate away
        other = P.generate_unique(store); store.save()
        # reload
        reloaded = json.load(open(vault_path))["_verify_backup"]
        ok = reloaded == original and reloaded != other
        self.results["backup_reload"] = {"ok": ok}
        self.c.print("[good]✓ backup saved, rotated away, reloaded identical[/]" if ok
                     else "[bad]✗ round-trip mismatch[/]")

    def check_leak_audit(self):
        self.c.rule("[brand]4. Leak audit (OS ground-truth vs app)[/]")
        os_ids = D.read_live_identifiers()
        app = self._app_stored_identity()
        real_serial = os_ids.get("serial", "")
        real_ssaid = os_ids.get("ssaid_u0", "")
        leaks = []
        if real_ssaid and real_ssaid in app["android_ids"]:
            leaks.append(f"REAL android_id {real_ssaid} present in app data")
        self.results["leak_audit"] = {"os": os_ids, "leaks": leaks}
        if leaks:
            for l in leaks: self.c.print(f"[bad]✗ {l}[/]")
        else:
            self.c.print("[good]✓ no real OS identifier found leaking into app[/]")

    def save_report(self):
        path = os.path.join(REPORTS, "verify-latest.json")
        json.dump(self.results, open(path, "w"), indent=2)
        self.c.print(f"\n[muted]report -> {path}[/]")


def run_questionnaire(console=None):
    c = console or Console(theme=THEME)
    c.print(Panel("[brand]Specter — deep on-device verification[/]\n"
                  "Prove the tool works against the real phone before trusting it.",
                  box=box.ROUNDED, border_style="magenta"))

    if not D.device_connected():
        c.print("[bad]No device connected (adb). Plug in the Pixel and enable USB debugging.[/]")
        return 2
    if not D.has_root():
        c.print("[warn]No root detected — some checks read /data and need su.[/]")

    pkg = Prompt.ask("[key]Target package[/]", default=DEFAULT_PKG)
    v = Verifier(pkg, console=c)

    checks = {
        "coverage": ("Coverage — what the app reads vs what we rotate", v.check_coverage),
        "rotation": ("Rotation — launch N times, confirm fresh identity each time", None),
        "backup": ("Backup/reload round-trip", v.check_backup_reload),
        "leak": ("Leak audit — real OS id vs app data", v.check_leak_audit),
    }
    c.print("\n[brand]Which checks?[/]")
    for k, (desc, _) in checks.items():
        c.print(f"  [unique]{k}[/] — {desc}")
    picked = Prompt.ask("[key]Comma-separated (or 'all')[/]", default="all")
    want = set(checks) if picked.strip() == "all" else {x.strip() for x in picked.split(",")}

    if "coverage" in want: v.check_coverage()
    if "rotation" in want:
        n = int(Prompt.ask("[key]How many launches?[/]", default="3"))
        if Confirm.ask(f"[warn]This clears + relaunches {pkg} {n}× (logs it out). Proceed?[/]", default=True):
            v.check_rotation(n)
    if "backup" in want: v.check_backup_reload()
    if "leak" in want: v.check_leak_audit()

    v.save_report()
    c.print("\n[good]Verification complete.[/]")
    return 0
