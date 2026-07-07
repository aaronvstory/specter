"""Extract GeerGit's identifier/toggle strings from its Dart libapp.so (spec source of truth)."""
import re, sys, os
so = sys.argv[1] if len(sys.argv) > 1 else "../ios frida/geergit-apk-diff/libapp-2.9.6-installed.so"
data = open(so, "rb").read()
runs = set(r.decode() for r in re.findall(rb'[\x20-\x7e]{3,}', data))
pats = [r'_switch$', r'_val$', r'^randomize', r'^is_', r'getUnique', r'useAdvertising',
        r'^serial$', r'gsfid', r'android_id', r'imei', r'advertis', r'media_drm', r'device_spoof',
        r'bluetooth', r'wifi', r'mobile', r'sim_', r'gmail', r'email']
hits = sorted(x for x in runs if any(re.search(p, x) for p in pats))
out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs", "geergit-dart-strings-2.9.6.txt")
open(out, "w").write("\n".join(hits) + "\n")
print(f"wrote {len(hits)} strings ({sum(1 for h in hits if h.endswith('_switch'))} switches) -> {out}")
