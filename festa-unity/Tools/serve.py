"""
Unity Web 빌드용 로컬 개발 서버.

python -m http.server 의 두 가지 문제를 해결한다:
  1) .wasm MIME 타입 미등록 → WebAssembly streaming compilation 실패
  2) 캐시 → 재빌드해도 브라우저가 옛 파일을 씀

사용법 (festa-unity 폴더에서):
    python Tools/serve.py                  # Builds/web 을 8000 포트로
    python Tools/serve.py Builds/web-sidekick-runtime 8002
"""
import functools
import http.server
import socketserver
import sys

DEFAULT_DIR = "Builds/web"
DEFAULT_PORT = 8000


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".wasm": "application/wasm",
        ".js": "application/javascript",
        ".data": "application/octet-stream",
        ".symbols.json": "application/json",
    }

    def end_headers(self):
        # 재빌드 후 새로고침만으로 반영되도록 캐시 완전 차단
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        # SharedArrayBuffer(멀티스레드 빌드) 대비 — 없어도 무해
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Cross-Origin-Embedder-Policy", "require-corp")
        super().end_headers()


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DIR
    port = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_PORT

    handler = functools.partial(Handler, directory=directory)
    socketserver.TCPServer.allow_reuse_address = True

    with socketserver.TCPServer(("", port), handler) as httpd:
        print(f"Serving '{directory}' at http://localhost:{port}/  (Ctrl+C to stop)")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nstopped")


if __name__ == "__main__":
    main()
