"""HTTP server for the BAU BUILD portal. Stdlib only, so a fresh VPS can run it."""

from __future__ import annotations

import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

import logic

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
STATIC = REPO / "static"
DOCS = REPO / "docs"
STORE = HERE / "data" / "store.json"
LOCK = threading.Lock()
STATE = logic.default_state()

TYPES = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".svg": "image/svg+xml",
    ".json": "application/json; charset=utf-8",
}


def load_state():
    global STATE
    if not STORE.exists():
        STATE = logic.default_state()
        save_state()
        return
    STATE = json.loads(STORE.read_text(encoding="utf-8"))


def save_state():
    STORE.parent.mkdir(parents=True, exist_ok=True)
    STORE.write_text(json.dumps(STATE, indent=2), encoding="utf-8")


class Handler(BaseHTTPRequestHandler):
    def do_HEAD(self):
        original_end = self.end_headers
        original_write = self.wfile.write

        def end_headers():
            original_end()
            self.wfile.write = lambda data: len(data) if data else 0

        self.end_headers = end_headers
        try:
            self.do_GET()
        finally:
            self.end_headers = original_end
            self.wfile.write = original_write

    def do_GET(self):
        path = urlparse(self.path).path
        if self._docs(path):
            return
        if self._api():
            return
        if path == "/api/state":
            with LOCK:
                self._json(200, logic.public_state(STATE))
            return
        if path == "/api/health":
            self._json(200, {"ok": True})
            return
        if path in ("/", "/index.html"):
            path = "/index.html"
        elif path in ("/app", "/app/"):
            path = "/app.html"
        file_path = (STATIC / path.lstrip("/")).resolve()
        if os.path.commonpath([file_path, STATIC]) != str(STATIC) or not file_path.is_file():
            self._json(404, {"ok": False, "error": "Not found."})
            return
        data = file_path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", TYPES.get(file_path.suffix, "application/octet-stream"))
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if self._api():
            return
        path = urlparse(self.path).path
        if path != "/api/action":
            self._json(404, {"ok": False, "error": "Not found."})
            return
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length > 100_000:
            self._json(413, {"ok": False, "error": "Request is too large."})
            return
        try:
            action = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._json(400, {"ok": False, "error": "Send JSON."})
            return
        if not isinstance(action, dict):
            self._json(400, {"ok": False, "error": "Send a JSON object."})
            return
        with LOCK:
            result = logic.dispatch(STATE, action)
            if result.get("ok"):
                save_state()
            payload = dict(result)
            payload["state"] = logic.public_state(STATE)
        self._json(200 if result.get("ok") else 400, payload)

    def do_PATCH(self):
        if not self._api():
            self._json(404, {"error": {"code": "not_found", "message": "No route matches that path."}})

    def do_PUT(self):
        if not self._api():
            self._json(404, {"error": {"code": "not_found", "message": "No route matches that path."}})

    def do_DELETE(self):
        if not self._api():
            self._json(404, {"error": {"code": "not_found", "message": "No route matches that path."}})

    def do_OPTIONS(self):
        if not self._api():
            self._json(404, {"error": {"code": "not_found", "message": "No route matches that path."}})

    def _api_host(self):
        host = (self.headers.get("Host") or "").split(":")[0].lower()
        return host == "api.vesperconnect.org"

    def _api(self):
        path = urlparse(self.path).path
        if path.startswith("/docs") or path in ("/openapi.json", "/v1/openapi.json"):
            return False
        if not (
            self._api_host()
            or path.startswith("/v1")
            or path.startswith("/.well-known/")
            or path.startswith("/p/")
        ):
            return False
        from api.dispatch import handle_request, query_map

        length = int(self.headers.get("Content-Length", "0") or "0")
        if length > 100_000:
            self._json(413, {"error": {"code": "too_large", "message": "Request is too large."}})
            return True
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        body = None
        if raw:
            try:
                body = json.loads(raw)
            except json.JSONDecodeError:
                self._cors(400, {"error": {"code": "invalid_body", "message": "Send JSON."}}, None, None)
                return True
        parsed = urlparse(self.path)
        if self._api_host() and path == "/":
            path = "/v1"
        result = handle_request(
            self.command,
            path,
            query_map(parsed.query),
            {key.lower(): value for key, value in self.headers.items()},
            body,
        )
        self._cors(result.status, result.json_body, result.text, result.stream)
        return True

    def _docs(self, path):
        if path in ("/docs", "/docs/"):
            target = DOCS / "index.html"
        elif path in ("/docs/openapi.json", "/openapi.json", "/v1/openapi.json"):
            target = DOCS / "openapi.json"
        else:
            return False
        if not target.is_file():
            self._json(404, {"error": {"code": "not_found", "message": "Documentation is not on this server."}})
            return True
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", TYPES.get(target.suffix, "application/octet-stream"))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)
        return True

    def _cors(self, code, payload, text, stream):
        if stream is not None:
            data = "".join(stream).encode("utf-8")
            content_type = "text/event-stream; charset=utf-8"
        elif text is not None:
            data = text.encode("utf-8")
            content_type = "text/html; charset=utf-8"
        elif payload is None:
            data = b""
            content_type = "application/json; charset=utf-8"
        else:
            data = json.dumps(payload).encode("utf-8")
            content_type = "application/json; charset=utf-8"
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type, Last-Event-ID")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PATCH, PUT, DELETE, OPTIONS")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _json(self, code, payload):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args))


def main():
    import os

    from api.db import configure

    configure()
    load_state()
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "8080"))
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"BAU BUILD portal on http://{host}:{port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
