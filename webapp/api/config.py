"""GET /api/config -> which keyed sources the server already has (env vars). Booleans only, never the
values. Lets the UI show 'shared key active' instead of asking the visitor for a key that's already set."""
from http.server import BaseHTTPRequestHandler
import json
import os


class handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps({
            "ipqs": bool(os.environ.get("IPQS_KEY")),
            "abuse": bool(os.environ.get("ABUSEIPDB_KEY")),
            "getipintel": bool(os.environ.get("GETIPINTEL_CONTACT")),
            # ONE boolean for the pair — a half-set pair is not "shared active", it never runs.
            "scamalytics": bool(os.environ.get("SCAMALYTICS_USER")
                                and os.environ.get("SCAMALYTICS_KEY")),
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
