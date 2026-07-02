from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs

from models.song import Song
from download_engine import DownloadEngine

engine = DownloadEngine()


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    pass


class APIHandler(BaseHTTPRequestHandler):

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        if path == "" or path == "/":
            self._json(200, {"status": "ok", "message": "MP3 Downloader Server"})
            return

        if path == "/api/search":
            q = parse_qs(parsed.query).get("q", [""])[0]
            if not q:
                self._json(400, {"error": "Missing ?q= query"})
                return
            try:
                songs = engine.search(q)
                self._json(200, [s.to_dict() for s in songs])
            except Exception as e:
                self._json(500, {"error": str(e)})
            return

        if path == "/api/download":
            video_id = parse_qs(parsed.query).get("videoId", [""])[0]
            if not video_id:
                self._json(400, {"error": "Missing ?videoId="})
                return
            title = parse_qs(parsed.query).get("title", [""])[0] or "audio"
            self._proxy_download(video_id, title)
            return

        if path == "/api/health":
            self._json(200, {"status": "ok"})
            return

        self._json(404, {"error": "Not found"})

    def _proxy_download(self, video_id: str, title: str) -> None:
        import select as _select
        yt_url = f"https://youtube.com/watch?v={video_id}"
        safe_title = "".join(c for c in title if c.isalnum() or c in " ._-").strip() or "audio"
        try:
            proc = subprocess.Popen(
                ["yt-dlp", "--no-warnings",
                 "-f", "bestaudio[ext=m4a]/bestaudio/best",
                 "-o", "-", "--no-playlist", yt_url],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE
            )

            # Wait for first data before responding (allow for Render cold start)
            ready = _select.select([proc.stdout], [], [], 60.0)
            if not ready[0]:
                proc.kill()
                _, stderr = proc.communicate(timeout=5)
                err = stderr.decode(errors="replace")[:300]
                self._json(500, {"error": f"yt-dlp timed out. Stderr: {err}"})
                return

            first_chunk = proc.stdout.read(8192)
            if not first_chunk:
                proc.kill()
                _, stderr = proc.communicate(timeout=5)
                err = stderr.decode(errors="replace")[:300]
                self._json(500, {"error": f"yt-dlp produced no output. Stderr: {err}"})
                return

            self.send_response(200)
            self.send_header("Content-Type", "audio/mp4")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Connection", "close")
            self.end_headers()

            self.wfile.write(first_chunk)
            while True:
                chunk = proc.stdout.read(8192)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()

            proc.stdout.close()
            proc.wait(timeout=10)
            if proc.returncode != 0:
                err = proc.stderr.read().decode(errors="replace")[:200]
                print(f"[API] yt-dlp error for {video_id}: {err}", flush=True)
            proc.stderr.close()
        except Exception as e:
            print(f"[API] download proxy error: {e}", flush=True)

    def _json(self, status, data):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def log_message(self, fmt, *args):
        print(f"[API] {args[0]} {args[1]} {args[2]}", flush=True)


def main():
    port = int(os.environ.get("PORT", 8899))
    host = os.environ.get("HOST", "0.0.0.0")
    server = ThreadingHTTPServer((host, port), APIHandler)
    print(f"API server running on http://{host}:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
