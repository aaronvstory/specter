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

# 1) config load: read from localStorage instead of GET /config
old_cfg = re.search(r"fetch\('/config'\)\.then\(r=>r\.json\(\)\)\.then\(c=>\{.*?\}\);", html, re.S)
assert old_cfg, "config-load block not found"
new_cfg = (
    "(function(){const ls=k=>{try{return localStorage.getItem(k)||''}catch(e){return ''}};\n"
    "  $('#proxy').value=q.get('proxy')||'';\n"
    "  $('#ptype').value=q.get('ptype')||'http';\n"
    "  $('#ipqs').value=ls('ipqs_key'); $('#abuse').value=ls('abuse_key'); $('#gii').value=ls('getipintel_contact');\n"
    "  $('#ip').value=q.get('ip')||'';\n"
    "  boot();\n"
    "})();"
)
html = html.replace(old_cfg.group(0), new_cfg, 1)

# 2) point the API at the serverless function (both the single check and the bulk table use `API`)
assert "const API='/check';" in html, "API const not found"
html = html.replace("const API='/check';", "const API='/api/check';", 1)

# 3) note copy: browser storage, not a local file
html = html.replace("Stored locally in ~/.specter-ipcheck.json.",
                    "Stored in your browser (localStorage) — never sent anywhere but the checker.")

(ROOT / "webapp" / "index.html").write_text(html, "utf-8")
print("wrote webapp/index.html (%d bytes)" % len(html))
