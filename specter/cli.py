"""
cli.py — ghostprint command line.

  ghostprint new   [--name N] [--no-us-bias]     generate a fresh identity (optionally save)
  ghostprint push  [--name N] [--pkg PKG] [--no-clear]   push to phone + clear app
  ghostprint rotate [--pkg PKG]                  new + push + clear (the per-signup button)
  ghostprint list                                saved profiles
  ghostprint show  [--name N | --pkg PKG]         print a profile
  ghostprint stats                               how many identities issued
  ghostprint tui                                 dashboard
"""
import argparse
import json
import os
import sys

from . import profile as P
from . import device as D
from .validation import validate_pkg, InvalidPackageName

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
USED = os.path.join(ROOT, "used_ids.json")
VAULT = os.path.join(ROOT, "profiles.json")
ACTIVE = os.path.join(ROOT, "profile.json")


def _load(path, default):
    try:
        return json.load(open(path))
    except Exception:
        return default


def _save(path, obj):
    json.dump(obj, open(path, "w"), indent=2)


def _store():
    return P.UsedStore(USED)


def cmd_new(a):
    store = _store()
    p = P.generate_unique(store, us_bias=not a.no_us_bias)
    store.save()
    _save(ACTIVE, p)
    if a.name:
        vault = _load(VAULT, {})
        vault[a.name] = p
        _save(VAULT, vault)
    print(f"[+] {p['build_manufacturer']} {p['build_model']}  android_id={p['android_id']}  gsf={p['gsf_id']}"
          + (f"  (saved '{a.name}')" if a.name else ""))
    return 0


def _resolve(a):
    if getattr(a, "name", None):
        return _load(VAULT, {}).get(a.name)
    return _load(ACTIVE, None)


def cmd_push(a):
    p = _resolve(a)
    if not p:
        print("[!] no profile — run 'new' or pass --name", file=sys.stderr)
        return 1
    if not D.device_connected():
        print("[!] no device connected (adb)", file=sys.stderr)
        return 2
    _save(ACTIVE, p)
    D.push_profile(p, a.pkg)
    if not a.no_clear:
        D.clear_app(a.pkg)
    print(f"[+] pushed to {a.pkg}" + ("" if a.no_clear else " + cleared"))
    return 0


def cmd_rotate(a):
    a.name = None
    a.no_us_bias = getattr(a, "no_us_bias", False)
    if cmd_new(a) != 0:
        return 1
    return cmd_push(a)


def cmd_list(a):
    vault = _load(VAULT, {})
    if not vault:
        print("(no saved profiles)")
        return 0
    for n, p in vault.items():
        print(f"  {n:20} {p['build_manufacturer']:10} {p['build_model']:16} gsf={p['gsf_id']}")
    return 0


def cmd_show(a):
    p = _resolve(a)
    if not p and getattr(a, "pkg", None):
        # try the pushed per-app file off the phone
        rc, out, _ = D.su(f"cat {D.PROFILE_DIR}/{a.pkg}.json")
        if rc == 0 and out:
            p = json.loads(out)
    if not p:
        print("[!] not found", file=sys.stderr)
        return 1
    print(json.dumps(p, indent=2))
    return 0


def cmd_stats(a):
    store = _store()
    print(f"identities issued (never reused): {store.count()}")
    return 0


def cmd_verify(a):
    from . import verify
    return verify.run_questionnaire()


def cmd_tui(a):
    from . import tui
    tui.run(ROOT)
    return 0


def build_parser():
    from .tui import VERSION
    ap = argparse.ArgumentParser(prog="specter")
    ap.add_argument("--version", action="version", version="specter " + VERSION)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("new"); p.add_argument("--name"); p.add_argument("--no-us-bias", action="store_true"); p.set_defaults(f=cmd_new)
    p = sub.add_parser("push"); p.add_argument("--name"); p.add_argument("--pkg", default="com.liuzh.deviceinfo"); p.add_argument("--no-clear", action="store_true"); p.set_defaults(f=cmd_push)
    p = sub.add_parser("rotate"); p.add_argument("--pkg", default="com.liuzh.deviceinfo"); p.add_argument("--no-clear", action="store_true"); p.add_argument("--no-us-bias", action="store_true"); p.set_defaults(f=cmd_rotate)
    p = sub.add_parser("list"); p.set_defaults(f=cmd_list)
    p = sub.add_parser("show"); p.add_argument("--name"); p.add_argument("--pkg"); p.set_defaults(f=cmd_show)
    p = sub.add_parser("stats"); p.set_defaults(f=cmd_stats)
    p = sub.add_parser("verify"); p.set_defaults(f=cmd_verify)
    p = sub.add_parser("tui"); p.set_defaults(f=cmd_tui)
    return ap


def main(argv=None):
    ap = build_parser()
    a = ap.parse_args(argv)
    if getattr(a, "pkg", None):
        try:
            validate_pkg(a.pkg)
        except InvalidPackageName as e:
            print(f"[!] {e}", file=sys.stderr)
            return 3
    return a.f(a) or 0


if __name__ == "__main__":
    sys.exit(main())
