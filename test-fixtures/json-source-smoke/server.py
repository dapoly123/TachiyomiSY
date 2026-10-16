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
JSON_RULE = {
    **RULE,
    "bookSourceName": "JSONPath Source Smoke Fixture",
    "ruleSearchUrl": BASE + "/json/search?q={{key}}",
    "ruleSearchList": "$.data.data||$.data",
    "ruleSearchName": "$.comic_name",
    "ruleSearchNoteUrl": BASE + "/json/comic/{{$.comic_id}}",
    "ruleSearchCoverUrl": "$.cover",
    "ruleBookName": "$.data.title",
    "ruleBookIntro": "$.data.description",
    "ruleChapterList": "$.data.chapters[*]",
    "ruleChapterName": "$.name",
    "ruleChapterUrl": BASE + "/json/chapter/{{$.id}}",
    "ruleBookContent": "$.data.images[*].url",
}
LEGACY_RULE = {
    **RULE,
    "bookSourceName": "Legacy Selector Smoke Fixture",
    "ruleSearchList": "class.entry@tag.a",
    "ruleSearchName": "tag.h2@text",
    "ruleSearchNoteUrl": "href",
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        route = urlsplit(self.path)
        if route.path == "/source.json":
            return self.reply(json.dumps(RULE, ensure_ascii=False).encode(), "application/json")
        if route.path == "/source-json.json":
            return self.reply(json.dumps(JSON_RULE, ensure_ascii=False).encode(), "application/json")
        if route.path == "/source-legacy.json":
            return self.reply(json.dumps(LEGACY_RULE, ensure_ascii=False).encode(), "application/json")
        if route.path == "/json/search":
            query = parse_qs(route.query).get("q", ["sample"])[0]
            return self.reply(json.dumps({
                "data": {"data": [{
                    "comic_name": "JSONPath Manga: " + query,
                    "comic_id": 1,
                    "cover": "/images/cover.png",
                }]},
            }).encode(), "application/json")
        if route.path == "/json/comic/1":
            return self.reply(json.dumps({
                "data": {
                    "title": "JSON API Manga",
                    "description": "JSON API detail and chapter fixture",
                    "chapters": [
                        {"id": 1, "name": "JSON Chapter One"},
                        {"id": 2, "name": "JSON Chapter Two"},
                    ],
                },
            }).encode(), "application/json")
        if route.path in {"/json/chapter/1", "/json/chapter/2"}:
            return self.reply(json.dumps({
                "data": {"images": [
                    {"url": "/images/json-page-1.png"},
                    {"url": "/images/json-page-2.png"},
                ]},
            }).encode(), "application/json")
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
