from __future__ import annotations

import json
import os
import shutil
import subprocess
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from services.download_engine import DownloadEngine
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs

engine = DownloadEngine()


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    pass


class APIHandler(BaseHTTPRequestHandler):

    def do_OPTIONS(self):
        self._cors()
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

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

        if path == "/api/stream-url":
            video_id = parse_qs(parsed.query).get("videoId", [""])[0]
            if not video_id:
                self._json(400, {"error": "Missing ?videoId="})
                return
            try:
                from models.song import Song
                url = engine.get_audio_url(Song(
                    id=video_id, title="", artist="", duration=0, thumbnail_url=""))
                self._json(200, {"url": url})
            except Exception as e:
                self._json(500, {"error": f"{type(e).__name__}: {e}"})
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
        yt_url = f"https://youtube.com/watch?v={video_id}"
        safe_title = "".join(c for c in title if c.isalnum() or c in " ._-").strip() or "audio"
        try:
            proc = subprocess.Popen(
                ["yt-dlp", "--no-warnings", "-f", "bestaudio[ext=m4a]/bestaudio/best",
                 "-o", "-", "--no-playlist", yt_url],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self.send_response(200)
            self.send_header("Content-Type", "audio/mp4")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Connection", "close")
            self.end_headers()
            buf = bytearray(4096)
            while True:
                n = proc.stdout.readinto(buf)
                if not n:
                    break
                self.wfile.write(buf[:n])
                self.wfile.flush()
            proc.stdout.close()
            proc.wait()
            if proc.returncode != 0:
                err = proc.stderr.read().decode(errors="replace")[:200]
                print(f"[API] yt-dlp error for {video_id}: {err}")
            proc.stderr.close()
        except Exception as e:
            print(f"[API] download proxy error: {e}")

    def _json(self, status, data):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def log_message(self, fmt, *args):
        print(f"[API] {args[0]} {args[1]} {args[2]}")


def run_server(host: str = "0.0.0.0", port: int = 8899):
    server = ThreadingHTTPServer((host, port), APIHandler)
    print(f"API server running on http://{host}:{port}")
    server.serve_forever()


def start_server_thread(host: str = "0.0.0.0", port: int = 8899):
    t = threading.Thread(target=run_server, args=(host, port), daemon=True)
    t.start()
    return t


def restart_server(host: str = "0.0.0.0", port: int = 8899):
    import socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    if sock.connect_ex(("127.0.0.1", port)) == 0:
        sock.close()
        return
    sock.close()
    start_server_thread(host, port)


if __name__ == "__main__":
    run_server()
