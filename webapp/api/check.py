"""Vercel serverless function: runs the same check() as the local tool, server-side (so getIPIntel / IPQS /
AbuseIPDB — which browsers can't call cross-origin — work). Keys arrive per-request from the visitor's
browser localStorage, or fall back to the project's server-side env vars; they are NEVER returned to the client."""
from http.server import BaseHTTPRequestHandler
import json
import os
import traceback


class handler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
            req = json.loads(self.rfile.read(n) or b"{}")
            assert isinstance(req, dict)
        except Exception:
            self._json(400, {"error": "expected a JSON object"})
            return
        try:
            # ipcheck_core sets CONFIG = Path.home()/... at import; Lambda has no HOME → point it at /tmp.
            os.environ.setdefault("HOME", "/tmp")
            os.environ.setdefault("USERPROFILE", "/tmp")
            import sys
            sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # so the sibling module imports
            from ipcheck_core import check
            ipqs = req.get("ipqs_key") or os.environ.get("IPQS_KEY", "")
            abuse = req.get("abuse_key") or os.environ.get("ABUSEIPDB_KEY", "")
            gii = req.get("getipintel_contact") or os.environ.get("GETIPINTEL_CONTACT", "")
            rep = check(req.get("proxy") or None, req.get("ip") or None,
                        ipqs, abuse, req.get("proxy_scheme") or "http", gii)
        except Exception as exc:
            rep = {"error": str(exc)}
        self._json(200, rep)

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)
