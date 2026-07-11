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
import time

from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs

from models.song import Song
from download_engine import (
    DownloadEngine,
    COOKIES_FILE,
    _base_cmd,
    ordered_clients,
    get_client_health,
    record_success,
    record_failure,
)

# ═══════════════════════════════════════════════════════════════
# Configuración desde variables de entorno
# ═══════════════════════════════════════════════════════════════

PORT = int(os.environ.get("PORT", 8899))
HOST = os.environ.get("HOST", "0.0.0.0")
COOKIES_FILE = os.environ.get("COOKIES_FILE", "/opt/mp3downloader/cookies/cookies.txt")
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()
LOG_DIR = os.environ.get("LOG_DIR", "/opt/mp3downloader/logs")
# Versión de yt-dlp fijada para deploys deterministas. Cambiar YTDLP_VERSION
# (y el pin del Dockerfile) en conjunto cuando se quiera actualizar. Esto
# evita que un redeploy traiga una yt-dlp que rompa el bypass cookie-less.
YTDLP_VERSION = os.environ.get("YTDLP_VERSION", "2026.6.9")
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
    """Asegura la versión fijada de yt-dlp (deploys deterministas).

    A diferencia de un `--upgrade` ciego, respeta YTDLP_VERSION para que los
    redeploys no traigan una yt-dlp que rompa el bypass cookie-less ("inicia
    sesión"). Solo reinstala si la versión actual difiere de la fijada.
    """
    marker = os.path.join(LOG_DIR, ".ytdlp_updated")
    current = _get_ytdlp_version()
    if current == YTDLP_VERSION:
        logger.debug(f"yt-dlp ya en versión fijada {YTDLP_VERSION}")
        return

    should_install = True
    if os.path.isfile(marker):
        mtime = os.path.getmtime(marker)
        age_hours = (datetime.now().timestamp() - mtime) / 3600
        if age_hours < 24:
            # Ya se intentó recientemente; no martillear PyPI.
            should_install = False

    if should_install:
        try:
            logger.info(f"Instalando yt-dlp=={YTDLP_VERSION} (actual: {current})...")
            result = subprocess.run(
                [sys.executable, "-m", "pip", "install", "--quiet", f"yt-dlp=={YTDLP_VERSION}"],
                capture_output=True, text=True, timeout=120
            )
            if result.returncode == 0:
                with open(marker, "w") as f:
                    f.write(datetime.now().isoformat())
                logger.info(f"yt-dlp instalado: {_get_ytdlp_version()}")
            else:
                logger.warning(f"No se pudo instalar yt-dlp=={YTDLP_VERSION}: {result.stderr[:200]}")
        except Exception as e:
            logger.warning(f"Error al instalar yt-dlp: {e}")
    else:
        logger.debug(f"yt-dlp actual {current} (diana {YTDLP_VERSION}); se revisará en <24h")


def _get_ytdlp_version() -> str:
    # Use the installed distribution version (normalized, e.g. "2026.6.9")
    # so it matches YTDLP_VERSION exactly. yt-dlp --version prints a
    # zero-padded form ("2026.06.09") that would never equal the pin.
    try:
        import importlib.metadata as importlib_metadata
        return importlib_metadata.version("yt-dlp")
    except Exception:
        pass
    try:
        result = subprocess.run(
            ["yt-dlp", "--version"], capture_output=True, text=True, timeout=5
        )
        return result.stdout.strip() if result.returncode == 0 else "unknown"
    except Exception:
        return "unknown"


# ═════════════════════════════════════════════════════════════
# Persistencia de cookies entre redeploys
# ═════════════════════════════════════════════════════════════
# Railway (y PaaS similares) usan un filesystem efímero: el directorio
# de cookies se borra en cada redeploy/commit, por eso las cookies
# subidas desaparecían tras cada deploy. La solución permanente es
# guardarlas también en una variable de entorno (COOKIES_B64) que SÍ
# persiste entre deploys, y restaurar el archivo desde ahí al arrancar.

def restore_cookies_from_env() -> bool:
    """Restaura cookies desde COOKIES_B64 si el archivo no existe.

    Devuelve True si restauró (o ya existía) cookies válidas.
    """
    if os.path.isfile(COOKIES_FILE) and os.path.getsize(COOKIES_FILE) > 0:
        return True

    b64 = os.environ.get("COOKIES_B64")
    if not b64:
        return False
    try:
        import base64
        os.makedirs(os.path.dirname(COOKIES_FILE), exist_ok=True)
        with open(COOKIES_FILE, "wb") as f:
            f.write(base64.b64decode(b64))
        logger.info(f"Cookies restauradas desde COOKIES_B64 ({os.path.getsize(COOKIES_FILE)} bytes)")
        return True
    except Exception as e:
        logger.warning(f"No se pudieron restaurar cookies desde COOKIES_B64: {e}")
        return False


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
                try:
                    offset = int(params.get("offset", ["0"])[0])
                except ValueError:
                    offset = 0
                try:
                    limit = int(params.get("limit", ["20"])[0])
                except ValueError:
                    limit = 20
                logger.info(f"Búsqueda: {q} (offset={offset}, limit={limit})")
                songs = engine.search(q, max_results=limit, offset=offset)
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

            if path == "/api/preview":
                video_id = params.get("videoId", [""])[0]
                if not video_id:
                    self._json(400, {"error": "Missing ?videoId="})
                    return
                title = params.get("title", [""])[0] or "audio"
                logger.info(f"Preview stream: {video_id} - {title}")
                self._proxy_preview(video_id, title)
                return

            if path == "/api/health":
                self._json(200, {
                    "status": "ok",
                    "has_cookies": os.path.isfile(COOKIES_FILE),
                    "yt_dlp_version": _get_ytdlp_version(),
                    "uptime": _get_uptime(),
                    "client_health": get_client_health(),
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
        import signal as _signal
        import tempfile

        yt_url = f"https://youtube.com/watch?v={video_id}"
        tmp_dir = tempfile.mkdtemp(prefix="mp3dl_")
        tmp_audio = os.path.join(tmp_dir, f"{video_id}.m4a")
        tmp_thumb = os.path.join(tmp_dir, f"{video_id}.jpg")
        tmp_mp3 = os.path.join(tmp_dir, f"{video_id}.mp3")

        try:
            from download_engine import _base_cmd, PLAYER_CLIENTS

            last_err = ""
            downloaded = False

            # Step 1: Download audio + thumbnail + metadata via yt-dlp
            for client in ordered_clients():
                if downloaded:
                    break
                cmd = _base_cmd(client) + [
                    "-f", "best",
                    "-o", tmp_audio,
                    "--no-playlist",
                    "--no-part",
                    # Metadata flags
                    "--add-metadata",
                    "--write-thumbnail", "--convert-thumbnails", "jpg",
                    "--parse-metadata", f"title:{title}",
                    "--parse-metadata", "artist:%(channel)s",
                    "--parse-metadata", "album:%(playlist_title|)s",
                    "--parse-metadata", "genre:YouTube Audio",
                    "--parse-metadata", "comment:%(description).200s",
                    yt_url,
                ]

                try:
                    proc = subprocess.Popen(
                        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE
                    )
                    _, stderr = proc.communicate(timeout=120)
                    if proc.returncode == 0 and os.path.isfile(tmp_audio) and os.path.getsize(tmp_audio) > 1024:
                        downloaded = True
                        record_success(client)
                    else:
                        last_err = stderr.decode(errors="replace")[:300]
                        record_failure(client)
                        for f in [tmp_audio, tmp_thumb, tmp_mp3]:
                            if os.path.isfile(f):
                                os.remove(f)
                except Exception as e:
                    last_err = str(e)[:300]
                    record_failure(client)

            if not downloaded:
                self._json(502, {"error": f"yt-dlp failed: {last_err}"})
                return

            # Find the thumbnail (yt-dlp may name it differently)
            thumb_file = None
            for ext in ["jpg", "png", "webp"]:
                candidate = os.path.join(tmp_dir, f"{video_id}.{ext}")
                if os.path.isfile(candidate):
                    thumb_file = candidate
                    break
            # Also check for files with suffixes like .m4a.jpg
            if not thumb_file:
                for f in os.listdir(tmp_dir):
                    if f.endswith((".jpg", ".png", ".webp")) and video_id in f:
                        thumb_file = os.path.join(tmp_dir, f)
                        break

            # Step 2: Convert to MP3 + embed thumbnail via ffmpeg
            try:
                ffmpeg_input = ["-i", tmp_audio]
                ffmpeg_maps = ["-map", "0:a", "-map_metadata", "0"]

                # Embed thumbnail as cover art if available
                if thumb_file and os.path.isfile(thumb_file):
                    ffmpeg_input.extend(["-i", thumb_file])
                    ffmpeg_maps = [
                        "-map", "0:a", "-map", "1:0",
                        "-map_metadata", "0",
                    ]

                ffmpeg_cmd = ["ffmpeg", "-y"] + ffmpeg_input + ffmpeg_maps + [
                    "-codec:a", "libmp3lame", "-q:a", "0",
                    "-id3v2_version", "3",
                ]

                # Add cover art metadata if thumbnail exists
                if thumb_file and os.path.isfile(thumb_file):
                    ffmpeg_cmd.extend([
                        "-metadata:s:v", "title=Album cover",
                        "-metadata:s:v", "comment=Cover (front)",
                        "-disposition:v", "attached_pic",
                    ])

                ffmpeg_cmd.append(tmp_mp3)

                proc = subprocess.Popen(
                    ffmpeg_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE
                )
                _, stderr = proc.communicate(timeout=120)
                if proc.returncode != 0 or not os.path.isfile(tmp_mp3):
                    logger.warning(f"ffmpeg conversion failed, sending M4A: {stderr.decode(errors='replace')[:200]}")
                    tmp_mp3 = tmp_audio  # fallback to M4A
            except Exception as e:
                logger.warning(f"ffmpeg error, sending M4A: {e}")
                tmp_mp3 = tmp_audio

            # Step 3: Stream the result
            is_mp3 = tmp_mp3.endswith(".mp3")
            content_type = "audio/mpeg" if is_mp3 else "audio/mp4"

            file_size = os.path.getsize(tmp_mp3)
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self._cors_headers()
            self.send_header("Content-Length", str(file_size))
            self.send_header("Connection", "close")
            self.send_header("X-Video-Id", video_id)
            self.end_headers()

            sent = 0
            with open(tmp_mp3, "rb") as f:
                while True:
                    chunk = f.read(8192)
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                        self.wfile.flush()
                        sent += len(chunk)
                    except BrokenPipeError:
                        break

            logger.info(f"Descarga completada: {video_id} ({sent} bytes, {'MP3' if is_mp3 else 'M4A'})")

        except BrokenPipeError:
            logger.debug(f"Cliente desconectado durante descarga de {video_id}")
        except Exception as e:
            logger.error(f"Error en proxy download para {video_id}: {e}")
        finally:
            # Cleanup temp files
            try:
                for f in os.listdir(tmp_dir):
                    fp = os.path.join(tmp_dir, f)
                    if os.path.isfile(fp):
                        os.remove(fp)
                os.rmdir(tmp_dir)
            except Exception:
                pass

    # ─── Preview con cache de archivos temporales ───────────
    # Descarga audio a un temp file, lo cachea, y sirve con Content-Length
    # para que Android MediaPlayer funcione correctamente. Archivos > 30 min
    # se limpian automáticamente.
    PREVIEW_CACHE_DIR = os.path.join(
        os.environ.get("LOG_DIR", "/opt/mp3downloader/logs"), "preview_cache"
    )
    PREVIEW_CACHE_MAX_AGE_H = 24  # horas antes de re-descargar
    PREVIEW_CACHE_MAX_FILES = 50  # límite de archivos en cache

    def _get_preview_path(self, video_id: str) -> str:
        safe_id = video_id.replace("/", "_").replace("..", "_")
        return os.path.join(self.PREVIEW_CACHE_DIR, f"{safe_id}.mp3")

    def _cleanup_preview_cache(self) -> None:
        """Elimina archivos viejos si se pasa del límite."""
        try:
            if not os.path.isdir(self.PREVIEW_CACHE_DIR):
                return
            files = [
                (os.path.join(self.PREVIEW_CACHE_DIR, f),
                 os.path.getmtime(os.path.join(self.PREVIEW_CACHE_DIR, f)))
                for f in os.listdir(self.PREVIEW_CACHE_DIR) if f.endswith(".mp3")
            ]
            if len(files) <= self.PREVIEW_CACHE_MAX_FILES:
                return
            files.sort(key=lambda x: x[1])  # oldest first
            for path, _ in files[:len(files) - self.PREVIEW_CACHE_MAX_FILES]:
                try:
                    os.remove(path)
                except Exception:
                    pass
        except Exception:
            pass

    def _proxy_preview(self, video_id: str, title: str) -> None:
        """Preview de audio con cache de archivos temporales.

        Android MediaPlayer REQUIERE Content-Length para funcionar
        correctamente con prepareAsync(). Por eso este método:
        1. Descarga el audio completo a un archivo cache (yt-dlp → ffmpeg → MP3)
        2. Lo sirve con Content-Length para que MediaPlayer pueda
           determinar cuándo empezar a reproducir.
        3. Los requests siguientes se sirven instantáneamente desde cache.

        Archivos > 24h se limpian automáticamente.
        """
        import subprocess as _sp

        preview_path = self._get_preview_path(video_id)

        # ── Cache hit: servir instantáneo con Content-Length ──
        if os.path.isfile(preview_path) and os.path.getsize(preview_path) > 1024:
            age_h = (time.time() - os.path.getmtime(preview_path)) / 3600
            if age_h < self.PREVIEW_CACHE_MAX_AGE_H:
                file_size = os.path.getsize(preview_path)
                try:
                    self.send_response(200)
                    self.send_header("Content-Type", "audio/mpeg")
                    self.send_header("Content-Length", str(file_size))
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Cache-Control", "public, max-age=3600")
                    self.end_headers()
                    with open(preview_path, "rb") as f:
                        while True:
                            chunk = f.read(8192)
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                    logger.info(f"Preview desde cache: {video_id} ({file_size} bytes)")
                    return
                except BrokenPipeError:
                    logger.debug(f"Cliente desconectado durante preview cache de {video_id}")
                    return
                except Exception as e:
                    logger.warning(f"Error sirviendo preview cache: {e}")

        # ── Cache miss: descargar completo, cachear, luego servir ──
        yt_url = f"https://youtube.com/watch?v={video_id}"
        last_err = ""
        os.makedirs(self.PREVIEW_CACHE_DIR, exist_ok=True)

        for client in ordered_clients():
            yt_cmd = _base_cmd(client) + [
                "-o", "-",
                "-f", "bestaudio[ext=m4a]/bestaudio",
                "--no-playlist", "--no-part",
                yt_url,
            ]
            ffmpeg_cmd = [
                "ffmpeg", "-y", "-i", "-",
                "-f", "mp3", "-ab", "128k", "-ar", "44100", "-",
            ]

            tmp_path = preview_path + ".tmp"
            try:
                p1 = _sp.Popen(yt_cmd, stdout=_sp.PIPE, stderr=_sp.PIPE)
                p2 = _sp.Popen(ffmpeg_cmd, stdin=p1.stdout, stdout=_sp.PIPE, stderr=_sp.PIPE)
                p1.stdout.close()

                # Leer todo el output de ffmpeg al archivo temporal
                with open(tmp_path, "wb") as f:
                    first = p2.stdout.read(8192)
                    if not first:
                        stderr = b""
                        if p1.stderr:
                            stderr += (p1.stderr.read() or b"")
                        if p2.stderr:
                            stderr += (p2.stderr.read() or b"")
                        last_err = stderr.decode(errors="replace")[:300] or "no se produjo audio"
                        record_failure(client)
                        logger.warning(f"Preview falló (client {client}): {last_err}")
                        try:
                            p1.terminate(); p2.terminate()
                        except Exception:
                            pass
                        continue

                    f.write(first)
                    while True:
                        chunk = p2.stdout.read(8192)
                        if not chunk:
                            break
                        f.write(chunk)

                # Renombrar atómicamente al path final
                os.replace(tmp_path, preview_path)
                self._cleanup_preview_cache()

                # Servir el archivo completo con Content-Length
                file_size = os.path.getsize(preview_path)
                try:
                    self.send_response(200)
                    self.send_header("Content-Type", "audio/mpeg")
                    self.send_header("Content-Length", str(file_size))
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Cache-Control", "public, max-age=3600")
                    self.end_headers()
                    with open(preview_path, "rb") as f:
                        while True:
                            chunk = f.read(8192)
                            if not chunk:
                                break
                            self.wfile.write(chunk)

                    record_success(client)
                    logger.info(f"Preview descargado y servido: {video_id} "
                                f"(client={client}, {file_size} bytes)")
                    return
                except BrokenPipeError:
                    logger.debug(f"Cliente desconectado durante preview de {video_id}")
                    return
            except Exception as e:
                last_err = str(e)[:300]
                record_failure(client)
                logger.warning(f"Preview excepción (client {client}): {e}")
                try:
                    if os.path.exists(tmp_path):
                        os.remove(tmp_path)
                except Exception:
                    pass
                try:
                    p1.terminate(); p2.terminate()
                except Exception:
                    pass

            finally:
                # Asegurar limpieza de procesos en todos los casos
                try:
                    p1.terminate()
                except Exception:
                    pass
                try:
                    p2.terminate()
                except Exception:
                    pass
                try:
                    p1.stdout.close()
                except Exception:
                    pass
                try:
                    p2.stdout.close()
                except Exception:
                    pass

        # Todos los clients fallaron
        try:
            self._json(502, {"error": f"preview failed: {last_err}"})
        except Exception:
            pass

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

    # Restaurar cookies persistentes (sobreviven a redeploys)
    restore_cookies_from_env()

    server = ThreadingHTTPServer((HOST, PORT), APIHandler)
    logger.info(f"Servidor escuchando en http://{HOST}:{PORT}")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("Servidor detenido por el usuario")
        server.shutdown()


if __name__ == "__main__":
    main()
