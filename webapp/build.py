"""Generate webapp/index.html from specter/ipcheck.py's PAGE, adapted for the Vercel deploy:
  * keys come from the visitor's browser localStorage (not a server config file), and are saved there;
  * the check POSTs to /api/check (the serverless function) instead of the local server's /check.
Re-run after changing PAGE so the hosted UI stays in sync. Usage: python webapp/build.py"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from specter.ipcheck import PAGE  # noqa: E402
import shutil  # noqa: E402
shutil.copyfile(ROOT / 'specter' / 'ipcheck.py', ROOT / 'webapp' / 'api' / 'ipcheck_core.py')  # vendor check()

html = PAGE

# 1) config load: read from localStorage instead of GET /config.
# Anchored on a `});` that starts a LINE — a bare non-greedy `.*?\}\);` stops at the first `});` INSIDE the
# block (e.g. `markKeys({});`) and silently replaces half of it, leaving orphan lines that are a JavaScript
# SyntaxError. A parse error kills the whole <script>, so every button on the deployed page goes inert while
# the page still looks fine. The boot() assert is the tripwire for that whole class of mis-match.
old_cfg = re.search(r"fetch\('/config'\)\.then\(r=>r\.json\(\)\)\.then\(c=>\{.*?\n\}\);", html, re.S)
assert old_cfg, "config-load block not found"
assert "boot();" in old_cfg.group(0), "config-load match truncated — it must span the whole block"
new_cfg = (
    "(function(){const ls=k=>{try{return localStorage.getItem(k)||''}catch(e){return ''}};\n"
    "  $('#proxy').value=q.get('proxy')||'';\n"
    "  $('#ptype').value=q.get('ptype')||'http';\n"
    "  $('#ipqs').value=ls('ipqs_key'); $('#abuse').value=ls('abuse_key');\n"
    "  $('#scamuser').value=ls('scamalytics_user'); $('#scamkey').value=ls('scamalytics_key');\n"
    "  $('#ip').value=q.get('ip')||'';\n"
    "  markKeys({});\n"
    "  // Which sources the DEPLOY already has a key for (booleans only, never the values), so a blank\n"
    "  // field reads 'shared active' instead of looking unconfigured.\n"
    "  fetch('/api/config').then(r=>r.json()).then(markKeys).catch(()=>{});\n"
    "  boot();\n"
    "})();"
)
html = html.replace(old_cfg.group(0), new_cfg, 1)

# 2) point the API at the serverless function (both the single check and the bulk table use `API`)
assert "const API='/check';" in html, "API const not found"
html = html.replace("const API='/check';", "const API='/api/check';", 1)

# 3) note copy: browser storage, not a local file. Asserted like the others — a silent miss would ship a
# page telling visitors their keys sit in a file on a machine they don't have.
OLD_NOTE = "<div class=rw><i>Stored</i><div>~/.specter-ipcheck.json</div></div>"
assert OLD_NOTE in html, "storage note not found"
html = html.replace(OLD_NOTE, "<div class=rw><i>Stored</i><div>your browser only (localStorage)</div></div>")

(ROOT / "webapp" / "index.html").write_text(html, "utf-8")
print("wrote webapp/index.html (%d bytes)" % len(html))

# 4) the asset render-test page. It fills a template with the REAL stylesheet and the REAL icon
# definitions lifted out of PAGE, so it can never show a stale copy of an icon that has since changed —
# which would make it worse than useless (a gallery that says "fine" about markup nobody ships).
style = re.search(r"<style>.*?</style>", PAGE, re.S)
assert style, "stylesheet not found in PAGE"
# esc() through usageOf() is one contiguous run holding esc/band/giiBand/ccColour/row/richRow/SVG/ICON/
# USAGE/usageOf — everything the gallery draws with. Anchored on both ends and length-checked, because a
# short match would silently produce a page missing half the icons.
js = re.search(r"const esc=.*?^const usageOf=.*?$", PAGE, re.S | re.M)
assert js, "icon/helper block not found in PAGE"
assert "const ICON={" in js.group(0) and "const USAGE=[" in js.group(0), \
    "icon block match truncated — it must span esc() through usageOf()"
tpl = (ROOT / "webapp" / "assets-template.html").read_text("utf-8")
assert "__STYLE__" in tpl and "__JS__" in tpl, "assets template lost its placeholders"
assets = tpl.replace("__STYLE__", style.group(0), 1).replace(
    "__JS__", "<script>\n" + js.group(0) + "\n</script>", 1)
# The post-condition, not just the pre-condition. The first version of this substituted into the
# template's own COMMENT (which named both tokens) and shipped a page printing "__STYLE__" as text —
# the pre-check passed happily. Every placeholder must be gone.
assert "__STYLE__" not in assets and "__JS__" not in assets, \
    "a placeholder survived — it is named more than once in assets-template.html"
(ROOT / "webapp" / "assets.html").write_text(assets, "utf-8")
print("wrote webapp/assets.html (%d bytes)" % len(assets))
