"""Deterministic local HTTP fixture for TachiyomiSY JSON source E2E tests.

Run: python3 test-fixtures/json-source-smoke/server.py
Android emulator accesses http://10.0.2.2:18765 .
"""
from base64 import b64decode
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
import json

HOST, PORT = "0.0.0.0", 18765
BASE = "http://10.0.2.2:18765"
ONE_PIXEL_PNG = b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAF"
    "gAI/ScL/nwAAAABJRU5ErkJggg=="
)
RULE = {
    "bookSourceName": "JSON Source Local Smoke Fixture",
    "bookSourceUrl": BASE,
    "ruleSearchUrl": BASE + "/search?q={{key}}&page={{page}}",
    "ruleSearchList": "article.entry",
    "ruleSearchName": "h2@text",
    "ruleSearchNoteUrl": "a@href",
    "ruleSearchCoverUrl": "img@data-src",
    "ruleBookName": "h1@text",
    "ruleBookIntro": "p.description@text",
    "ruleChapterList": "ul.chapters li",
    "ruleChapterName": "a@text",
    "ruleChapterUrl": "a@href",
    "ruleBookContent": "img.page@src",
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        route = urlsplit(self.path)
        if route.path == "/source.json":
            return self.reply(json.dumps(RULE, ensure_ascii=False).encode(), "application/json")
        if route.path == "/search":
            query = parse_qs(route.query).get("q", ["sample"])[0]
            return self.reply(f"""
                <html><body><article class="entry">
                <a href="/comic/1"><h2>Smoke Manga: {query}</h2></a>
                <img data-src="/images/cover.png">
                </article></body></html>""".encode(), "text/html")
        if route.path == "/comic/1":
            return self.reply(b"""
                <html><body><h1>Smoke Manga</h1>
                <p class="description">Offline fixture only</p>
                <ul class="chapters"><li><a href="/chapter/1">Chapter One</a></li>
                <li><a href="/chapter/2">Chapter Two</a></li></ul>
                </body></html>""", "text/html")
        if route.path in {"/chapter/1", "/chapter/2"}:
            return self.reply(b"""
                <html><body><img class="page" src="/images/1.png">
                <img class="page" data-src="/images/2.png"></body></html>""", "text/html")
        if route.path.startswith("/images/") and route.path.endswith(".png"):
            return self.reply(ONE_PIXEL_PNG, "image/png")
        return self.reply(b"Not Found", "text/plain", 404)

    def reply(self, payload, content_type, status=200):
        self.send_response(status)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    print(f"Listening on {HOST}:{PORT}, emulator source URL: {BASE}/source.json")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
