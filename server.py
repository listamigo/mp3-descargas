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
from download_engine import DownloadEngine, COOKIES_FILE

engine = DownloadEngine()

COOKIES_DIR = os.path.dirname(COOKIES_FILE)
os.makedirs(COOKIES_DIR, exist_ok=True)


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    pass


class APIHandler(BaseHTTPRequestHandler):

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors_headers()
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

        if path == "/api/stream-url":
            video_id = parse_qs(parsed.query).get("videoId", [""])[0]
            if not video_id:
                self._json(400, {"error": "Missing ?videoId="})
                return
            try:
                url = engine.get_audio_url(Song(
                    id=video_id, title="", artist="", duration=0, thumbnail_url=""))
                self._json(200, {"url": url})
            except Exception as e:
                self._json(500, {"error": f"{type(e).__name__}: {e}"})
            return

        if path == "/api/health":
            self._json(200, {
                "status": "ok",
                "has_cookies": os.path.isfile(COOKIES_FILE),
                "yt_dlp_version": self._get_ytdlp_version(),
            })
            return

        self._json(404, {"error": "Not found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        if path == "/api/cookies":
            self._handle_cookies_upload()
            return

        self._json(404, {"error": "Not found"})

    def _handle_cookies_upload(self):
        """Receive cookies.txt from Android app and save to the cookies file."""
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            if content_length == 0:
                self._json(400, {"error": "Empty request body"})
                return
            body = self.rfile.read(content_length)
            os.makedirs(COOKIES_DIR, exist_ok=True)
            with open(COOKIES_FILE, "wb") as f:
                f.write(body)
            print(f"[API] Cookies updated ({len(body)} bytes)", flush=True)
            self._json(200, {"status": "ok", "message": f"Cookies saved ({len(body)} bytes)"})
        except Exception as e:
            self._json(500, {"error": f"Failed to save cookies: {e}"})

    def _proxy_download(self, video_id: str, title: str) -> None:
        import select as _select
        yt_url = f"https://youtube.com/watch?v={video_id}"
        safe_title = "".join(c for c in title if c.isalnum() or c in " ._-").strip() or "audio"

        try:
            from download_engine import _base_cmd
            cmd = _base_cmd() + [
                "-f", "bestaudio[ext=m4a]/bestaudio/best",
                "-o", "-", "--no-playlist", yt_url,
            ]

            proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE
            )

            # Wait for first data before responding (allow for cold start)
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
            self._cors_headers()
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
            # Not calling self._json() here — response may have already started with 200

    def _json(self, status, data):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self._cors_headers()
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def _cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def _get_ytdlp_version(self) -> str:
        try:
            result = subprocess.run(
                ["yt-dlp", "--version"],
                capture_output=True, text=True, timeout=5
            )
            return result.stdout.strip() if result.returncode == 0 else "unknown"
        except Exception:
            return "unknown"

    def log_message(self, fmt, *args):
        print(f"[API] {args[0]} {args[1]} {args[2]}", flush=True)


def main():
    port = int(os.environ.get("PORT", 8899))
    host = os.environ.get("HOST", "0.0.0.0")
    server = ThreadingHTTPServer((host, port), APIHandler)
    print(f"API server running on http://{host}:{port}", flush=True)
    print(f"Cookies: {'EXIST' if os.path.isfile(COOKIES_FILE) else 'not set'}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
