"""
Booth Studio 임시 편집기 개발 서버 — Unity 검증 전용.

정식 편집기는 FE 파트(React)가 만든다. 이 서버는 FE 착수 전에
"프론트에서 배치한 것만 Unity가 생성한다"를 검증하기 위한 임시 도구다.

구현한 계약 (docs/08 §4, spec 005):
    GET  /api/v1/booths/{id}                  Facade 조회 (primaryColor 포함)
    GET  /api/v1/booths/{id}/layouts/draft    Draft 조회
    PUT  /api/v1/booths/{id}/layouts/draft    Draft 저장 — version 낙관적 잠금
    POST /api/v1/booths/{id}/layouts/publish  공개
    GET  /api/v1/booths/{id}/layouts/published 공개본 조회 (Unity 가 읽는 것)

C-05 확정 계약: version 불일치 시 409 + {code, message, requestId}.
클라이언트는 code 로만 분기한다.

사용법 (festa-unity 폴더에서):
    py Tools/booth-editor/serve_editor.py
    → http://localhost:8000/         편집기
    → http://localhost:8000/api/...  API

Unity 는 ApiConfig 의 useMockApi 를 끄고 springBaseUrl=http://localhost:8000 으로 이 서버를 읽는다.
"""
import http.server
import json
import os
import socketserver
import sys
import uuid

ROOT = os.path.dirname(os.path.abspath(__file__))
# 저장 위치는 기존 mock-api 트리를 그대로 쓴다 (Unity 가 이미 이 경로를 읽음)
STORE = os.path.normpath(os.path.join(ROOT, "..", "mock-api", "api", "v1", "booths"))
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8000

DEFAULT_FACADE = {
    "themeCode": "SSAFY_BLUE",
    "primaryColor": "#3B82F6",
    "signText": "AI 프로젝트 전시관",
    "logoUrl": None,
}


def booth_dir(booth_id):
    d = os.path.join(STORE, str(booth_id), "layouts")
    os.makedirs(d, exist_ok=True)
    return d


def read_json(path, fallback):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return fallback


def write_json(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def empty_layout(booth_id):
    return {"boothId": int(booth_id), "template": "PROJECT_EXHIBITION", "version": 0, "objects": []}


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".js": "application/javascript",
        ".json": "application/json",
    }

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    # ---------- helpers ----------

    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, PUT, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(body)

    def _error(self, code, err_code, message):
        # docs/08 §1.3 확정 형식 — 클라이언트는 code 로만 분기
        self._send(code, {"code": err_code, "message": message, "requestId": uuid.uuid4().hex[:16]})

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n == 0:
            return None
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except ValueError:
            return None

    def _route(self):
        """/api/v1/booths/{id}[/layouts/{draft|published|publish}] 를 (id, tail) 로 분해."""
        p = self.path.split("?")[0].rstrip("/")
        prefix = "/api/v1/booths/"
        if not p.startswith(prefix):
            return None, None
        rest = p[len(prefix):]
        if not rest:
            return None, None
        parts = rest.split("/")
        return parts[0], "/".join(parts[1:])

    def end_headers(self):
        if not self.path.startswith("/api/"):
            self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, fmt, *args):
        sys.stderr.write("  %s\n" % (fmt % args))

    # ---------- verbs ----------

    def do_OPTIONS(self):
        self._send(204, {})

    def do_GET(self):
        booth_id, tail = self._route()
        if booth_id is None:
            return super().do_GET()          # 편집기 정적 파일

        d = booth_dir(booth_id)
        if tail == "":
            facade = read_json(os.path.join(d, "..", "facade.json"), DEFAULT_FACADE)
            pub = read_json(os.path.join(d, "published"), empty_layout(booth_id))
            return self._send(200, {
                "boothId": int(booth_id), "slotId": 5, "name": facade.get("signText", ""),
                "leaseStatus": "ACTIVE", "entryAvailable": True,
                "facade": facade, "publishedLayoutVersion": pub.get("version", 0),
            })
        if tail == "layouts/draft":
            return self._send(200, read_json(os.path.join(d, "draft"), empty_layout(booth_id)))
        if tail == "layouts/published":
            return self._send(200, read_json(os.path.join(d, "published"), empty_layout(booth_id)))
        return self._error(404, "NOT_FOUND", "unknown path: %s" % self.path)

    def do_PUT(self):
        booth_id, tail = self._route()
        d = booth_dir(booth_id) if booth_id else None
        if tail == "layouts/draft":
            body = self._body()
            if body is None:
                return self._error(400, "INVALID_BODY", "JSON 본문이 필요하다")

            objects = body.get("objects") or []
            if len(objects) > 12:
                # spec 005 FR-010 — 최대 12개 (헌법 22조)
                return self._error(400, "TOO_MANY_OBJECTS",
                                   "오브젝트는 최대 12개다 (요청 %d개)" % len(objects))

            cur = read_json(os.path.join(d, "draft"), empty_layout(booth_id))
            cur_ver = int(cur.get("version", 0))
            req_ver = body.get("version")
            if req_ver is None:
                return self._error(400, "VERSION_REQUIRED", "version 이 필요하다")
            if int(req_ver) != cur_ver:
                # C-05 확정 — 낙관적 잠금
                return self._send(409, {
                    "code": "LAYOUT_VERSION_CONFLICT",
                    "message": "다른 사용자가 먼저 수정했다 (현재 version=%d)" % cur_ver,
                    "requestId": uuid.uuid4().hex[:16],
                    "currentVersion": cur_ver,
                })

            saved = {
                "boothId": int(booth_id),
                "template": body.get("template") or "PROJECT_EXHIBITION",
                "version": cur_ver + 1,
                "objects": objects,
            }
            write_json(os.path.join(d, "draft"), saved)
            return self._send(200, saved)

        if tail == "facade":
            body = self._body() or {}
            write_json(os.path.join(d, "..", "facade.json"), body)
            return self._send(200, body)

        return self._error(404, "NOT_FOUND", "unknown path: %s" % self.path)

    def do_POST(self):
        booth_id, tail = self._route()
        d = booth_dir(booth_id) if booth_id else None
        if tail == "layouts/publish":
            draft = read_json(os.path.join(d, "draft"), None)
            if draft is None or not draft.get("objects"):
                # FR-007 — 공개 전 유효성 검사
                return self._error(400, "EMPTY_LAYOUT", "빈 배치는 공개할 수 없다")
            write_json(os.path.join(d, "published"), draft)
            return self._send(200, {"publishedVersion": draft.get("version", 0)})
        return self._error(404, "NOT_FOUND", "unknown path: %s" % self.path)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    os.makedirs(STORE, exist_ok=True)
    print("Booth Studio 임시 편집기 (Unity 검증 전용)")
    print("  편집기 : http://localhost:%d/" % PORT)
    print("  API    : http://localhost:%d/api/v1/booths/7/layouts/published" % PORT)
    print("  저장    : %s" % STORE)
    print("  종료    : Ctrl+C")
    with Server(("127.0.0.1", PORT), Handler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n중지")
