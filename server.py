"""
MP3 Downloader Server — API HTTP para descargas de YouTube vía yt-dlp
====================================================================
Modo producción: despliega en Oracle Cloud Free Tier para tener el
servidor 24/7 sin necesidad de mantener tu PC encendida.

Endpoints:
  GET  /api/search     ?q=<query>            → Lista de canciones
  GET  /api/stream-url ?videoId=<id>         → URL directa de audio
  GET  /api/download   ?videoId=<id>&title=  → Stream del audio (proxy)
  GET  /api/health                           → Estado del servidor
  POST /api/cookies                          → Subir cookies.txt
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import logging

from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs

from models.song import Song
from download_engine import DownloadEngine, COOKIES_FILE

# ═══════════════════════════════════════════════════════════════
# Configuración desde variables de entorno
# ═══════════════════════════════════════════════════════════════

PORT = int(os.environ.get("PORT", 8899))
HOST = os.environ.get("HOST", "0.0.0.0")
COOKIES_FILE = os.environ.get("COOKIES_FILE", "/opt/mp3downloader/cookies/cookies.txt")
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()
LOG_DIR = os.environ.get("LOG_DIR", "/opt/mp3downloader/logs")
LOG_FILE = os.environ.get("LOG_FILE", os.path.join(LOG_DIR, "server.log"))

# ═══════════════════════════════════════════════════════════════
# Logging
# ═══════════════════════════════════════════════════════════════

os.makedirs(LOG_DIR, exist_ok=True)
os.makedirs(os.path.dirname(COOKIES_FILE), exist_ok=True)

logging.basicConfig(
    level=getattr(logging, LOG_LEVEL, logging.INFO),
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    handlers=[
        logging.FileHandler(LOG_FILE),
        logging.StreamHandler(sys.stdout),
    ],
)
logger = logging.getLogger("mp3downloader")

# ═══════════════════════════════════════════════════════════════
# Auto-actualización de yt-dlp
# ═══════════════════════════════════════════════════════════════

def ensure_ytdlp_updated() -> None:
    """Actualiza yt-dlp al iniciar el servidor (cada 24h máximo)."""
    marker = os.path.join(LOG_DIR, ".ytdlp_updated")
    should_update = True
    if os.path.isfile(marker):
        mtime = os.path.getmtime(marker)
        age_hours = (datetime.now().timestamp() - mtime) / 3600
        if age_hours < 24:
            should_update = False

    if should_update:
        try:
            logger.info("Verificando actualización de yt-dlp...")
            result = subprocess.run(
                [sys.executable, "-m", "pip", "install", "--quiet", "--upgrade", "yt-dlp"],
                capture_output=True, text=True, timeout=60
            )
            if result.returncode == 0:
                # Touch marker
                with open(marker, "w") as f:
                    f.write(datetime.now().isoformat())
                logger.info(f"yt-dlp actualizado: {_get_ytdlp_version()}")
            else:
                logger.warning(f"No se pudo actualizar yt-dlp: {result.stderr[:200]}")
        except Exception as e:
            logger.warning(f"Error al actualizar yt-dlp: {e}")
    else:
        logger.debug(f"yt-dlp ya verificado hace menos de 24h ({_get_ytdlp_version()})")


def _get_ytdlp_version() -> str:
    try:
        result = subprocess.run(
            ["yt-dlp", "--version"], capture_output=True, text=True, timeout=5
        )
        return result.stdout.strip() if result.returncode == 0 else "unknown"
    except Exception:
        return "unknown"


# ═══════════════════════════════════════════════════════════════
# Servidor HTTP
# ═══════════════════════════════════════════════════════════════

engine = DownloadEngine()


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class APIHandler(BaseHTTPRequestHandler):

    # Silenciar logs de cada petición (usamos nuestro propio logging)
    def log_message(self, fmt, *args):
        logger.debug(f"{args[0]} {args[1]} {args[2]}")

    # ─── CORS ────────────────────────────────────────────────
    def _cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")

    # ─── Respuestas ──────────────────────────────────────────
    def _json(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self._cors_headers()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # ─── OPTIONS (CORS preflight) ────────────────────────────
    def do_OPTIONS(self):
        self.send_response(204)
        self._cors_headers()
        self.end_headers()

    # ─── GET ─────────────────────────────────────────────────
    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        params = parse_qs(parsed.query)

        try:
            if path == "" or path == "/":
                self._json(200, {
                    "status": "ok",
                    "message": "MP3 Downloader Server",
                    "version": _get_ytdlp_version(),
                })
                return

            if path == "/api/search":
                q = params.get("q", [""])[0]
                if not q:
                    self._json(400, {"error": "Missing ?q= query"})
                    return
                logger.info(f"Búsqueda: {q}")
                songs = engine.search(q)
                self._json(200, [s.to_dict() for s in songs])
                return

            if path == "/api/stream-url":
                video_id = params.get("videoId", [""])[0]
                if not video_id:
                    self._json(400, {"error": "Missing ?videoId="})
                    return
                logger.info(f"Stream URL solicitado: {video_id}")
                url = engine.get_audio_url(Song(
                    id=video_id, title="", artist="", duration=0, thumbnail_url=""
                ))
                self._json(200, {"url": url})
                return

            if path == "/api/download":
                video_id = params.get("videoId", [""])[0]
                if not video_id:
                    self._json(400, {"error": "Missing ?videoId="})
                    return
                title = params.get("title", [""])[0] or "audio"
                logger.info(f"Descarga proxy: {video_id} - {title}")
                self._proxy_download(video_id, title)
                return

            if path == "/api/health":
                self._json(200, {
                    "status": "ok",
                    "has_cookies": os.path.isfile(COOKIES_FILE),
                    "yt_dlp_version": _get_ytdlp_version(),
                    "uptime": _get_uptime(),
                })
                return

            self._json(404, {"error": f"Not found: {path}"})

        except Exception as e:
            logger.error(f"Error en {path}: {type(e).__name__}: {e}")
            self._json(500, {"error": f"{type(e).__name__}: {e}"})

    # ─── POST ────────────────────────────────────────────────
    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        try:
            if path == "/api/cookies":
                self._handle_cookies_upload()
                return

            #   /api/update  → forzar actualización de yt-dlp
            if path == "/api/update":
                logger.info("Actualización manual de yt-dlp solicitada")
                ensure_ytdlp_updated()
                self._json(200, {
                    "status": "ok",
                    "version": _get_ytdlp_version(),
                })
                return

            self._json(404, {"error": f"Not found: {path}"})

        except Exception as e:
            logger.error(f"Error en POST {path}: {type(e).__name__}: {e}")
            self._json(500, {"error": f"{type(e).__name__}: {e}"})

    # ─── Upload cookies ──────────────────────────────────────
    def _handle_cookies_upload(self):
        content_length = int(self.headers.get("Content-Length", 0))
        if content_length == 0:
            self._json(400, {"error": "Empty request body"})
            return
        body = self.rfile.read(content_length)
        os.makedirs(os.path.dirname(COOKIES_FILE), exist_ok=True)
        with open(COOKIES_FILE, "wb") as f:
            f.write(body)
        logger.info(f"Cookies actualizadas ({len(body)} bytes)")
        self._json(200, {
            "status": "ok",
            "message": f"Cookies saved ({len(body)} bytes)",
        })

    # ─── Proxy download (streaming) ──────────────────────────
    def _proxy_download(self, video_id: str, title: str) -> None:
        import select as _select
        import signal as _signal

        yt_url = f"https://youtube.com/watch?v={video_id}"

        try:
            from download_engine import _base_cmd

            cmd = _base_cmd() + [
                "-f", "bestaudio[ext=m4a]/bestaudio/best",
                "-o", "-",
                "--no-playlist",
                "--no-part",      # no crear archivos temporales
                "--buffer-size",  "4096",
                yt_url,
            ]

            # Limitar tiempo de proceso hijo
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                preexec_fn=lambda: _signal.signal(_signal.SIGALRM, lambda *_: (_signal.alarm(0), os._exit(1))),
            )

            # Esperar primeros datos (máx 60s para cold start)
            ready = _select.select([proc.stdout], [], [], 60.0)
            if not ready[0]:
                proc.kill()
                _, stderr = proc.communicate(timeout=5)
                err = stderr.decode(errors="replace")[:300]
                logger.error(f"yt-dlp timeout para {video_id}")
                self._json(504, {"error": f"yt-dlp timed out. Stderr: {err}"})
                return

            first_chunk = proc.stdout.read(8192)
            if not first_chunk:
                proc.kill()
                _, stderr = proc.communicate(timeout=5)
                err = stderr.decode(errors="replace")[:300]
                logger.error(f"yt-dlp sin output para {video_id} (stderr: {err})")
                self._json(502, {"error": f"yt-dlp no output. Stderr: {err}"})
                return

            # Respuesta 200 — streaming
            self.send_response(200)
            self.send_header("Content-Type", "audio/mp4")
            self._cors_headers()
            self.send_header("Connection", "close")
            self.send_header("X-Video-Id", video_id)
            self.end_headers()

            # Enviar datos en streaming
            self.wfile.write(first_chunk)
            self.wfile.flush()

            sent = len(first_chunk)
            while True:
                chunk = proc.stdout.read(8192)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
                sent += len(chunk)

            proc.stdout.close()
            proc.wait(timeout=10)

            if proc.returncode != 0:
                err = proc.stderr.read().decode(errors="replace")[:200]
                logger.warning(f"yt-dlp exit code {proc.returncode} para {video_id}: {err}")
            else:
                logger.info(f"Descarga completada: {video_id} ({sent} bytes)")

            proc.stderr.close()

        except BrokenPipeError:
            # El cliente Android cerró la conexión — es normal
            logger.debug(f"Cliente desconectado durante descarga de {video_id}")
        except Exception as e:
            logger.error(f"Error en proxy download para {video_id}: {e}")

    # ─── Timeout de conexión ─────────────────────────────────
    def handle_one_request(self):
        try:
            super().handle_one_request()
        except (ConnectionError, TimeoutError, BrokenPipeError):
            pass


def _get_uptime() -> str:
    """Devuelve el uptime del servidor."""
    try:
        with open("/proc/uptime", "r") as f:
            uptime_seconds = float(f.read().split()[0])
        days = int(uptime_seconds // 86400)
        hours = int((uptime_seconds % 86400) // 3600)
        minutes = int((uptime_seconds % 3600) // 60)
        if days > 0:
            return f"{days}d {hours}h {minutes}m"
        return f"{hours}h {minutes}m"
    except Exception:
        return "unknown"


# ═══════════════════════════════════════════════════════════════
# Entry point
# ═══════════════════════════════════════════════════════════════

def main():
    logger.info("═" * 50)
    logger.info("Iniciando MP3 Downloader Server")
    logger.info(f"Python:      {sys.version.split()[0]}")
    logger.info(f"yt-dlp:      {_get_ytdlp_version()}")
    logger.info(f"Host:        {HOST}:{PORT}")
    logger.info(f"Cookies:     {os.path.isfile(COOKIES_FILE)} ({COOKIES_FILE})")
    logger.info(f"Log level:   {LOG_LEVEL}")
    logger.info("═" * 50)

    # Actualizar yt-dlp al arrancar
    ensure_ytdlp_updated()

    server = ThreadingHTTPServer((HOST, PORT), APIHandler)
    logger.info(f"Servidor escuchando en http://{HOST}:{PORT}")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("Servidor detenido por el usuario")
        server.shutdown()


if __name__ == "__main__":
    main()
