"""
MP3 Downloader Server — API HTTP para descargas de YouTube vía yt-dlp
====================================================================
Modo producción: despliega en Oracle Cloud Free Tier para tener el
servidor 24/7 sin necesidad de mantener tu PC encendida.

Endpoints:
  GET  /api/search     ?q=<query>            → Lista de canciones
  GET  /api/stream-url ?videoId=<id>         → URL directa de audio
  GET  /api/download   ?videoId=<id>&title=  → Stream del audio (proxy)
  GET  /api/download   ?videoId=<id>&mode=video&quality=<360|720|1080>
                                              → MP4 mergeado (Content-Length real)
  GET  /api/health                           → Estado del servidor
  POST /api/cookies                          → Subir cookies.txt
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import logging
import tempfile
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
    _proxy_cmd,
    _proxy_cmd_video,
    _find_working_proxy,
    _remember_working_proxy,
    ordered_clients,
    get_client_health,
    record_success,
    po_http_provider_alive,
    record_failure,
    direct_path_available,
    direct_path_state,
    record_direct_failure,
    record_direct_success,
    invidious_get_audio_url,
)

# ═══════════════════════════════════════════════════════════════
# Configuración desde variables de entorno
# ═══════════════════════════════════════════════════════════════

PORT = int(os.environ.get("PORT", 8899))
HOST = os.environ.get("HOST", "0.0.0.0")
COOKIES_FILE = os.environ.get("COOKIES_FILE", "/opt/mp3downloader/cookies/cookies.txt")
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()
LOG_DIR = os.environ.get("LOG_DIR", "/opt/mp3downloader/logs")
# Versión mínima de yt-dlp para deploys deterministas. Debe subirse junto con
# el pin del Dockerfile. 2026.6.9 fallaba al descargar con HTTP 403; 2026.8.19
# es la última de PyPI y está verificada funcionando. NO se baja de versión:
# si el entorno ya trae una yt-dlp más nueva, se respeta (ver ensure_ytdlp_updated).
YTDLP_VERSION = os.environ.get("YTDLP_VERSION", "2026.8.19")
LOG_FILE = os.environ.get("LOG_FILE", os.path.join(LOG_DIR, "server.log"))
# Proxy residencial para evitar IPs de datacenter (Railway, etc.)
RESIDENTIAL_PROXY = os.environ.get("RESIDENTIAL_PROXY", "")

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

def _version_tuple(version: str) -> tuple[int, ...]:
    """Convierte '2026.8.19' en (2026, 8, 19) para poder comparar versiones.

    Acepta la forma con ceros ('2026.08.19') que imprime `yt-dlp --version`.
    Devuelve (0,) si la versión no es comparable, para forzar la instalación
    del pin en vez de dejar una versión desconocida en producción.
    """
    try:
        return tuple(int(part) for part in str(version).split("."))
    except (AttributeError, ValueError):
        return (0,)


def ensure_ytdlp_updated() -> None:
    """Asegura que yt-dlp sea al menos tan nueva como YTDLP_VERSION.

    A diferencia de un `--upgrade` ciego, respeta YTDLP_VERSION para que los
    redeploys no traigan una yt-dlp que rompa el bypass cookie-less ("inicia
    sesión"). Solo reinstala si la versión actual es ANTERIOR a la fijada: una
    versión más nueva que la fijada se respeta y nunca se degrada, porque
    degradar fue justo lo que dejó el servidor devolviendo HTTP 403.
    """
    marker = os.path.join(LOG_DIR, ".ytdlp_updated")
    current = _get_ytdlp_version()
    if _version_tuple(current) >= _version_tuple(YTDLP_VERSION):
        logger.debug(f"yt-dlp {current} >= fijada {YTDLP_VERSION}; no se toca")
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
            logger.info(f"Actualizando yt-dlp {current} -> {YTDLP_VERSION}...")
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
        try:
            logger.debug(fmt % args)
        except Exception:
            pass

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
                # ?mode=video&quality=720 descarga el MP4. Sin mode (o mode=audio)
                # se comporta exactamente como antes, así que los clientes viejos
                # no se ven afectados.
                mode = (params.get("mode", ["audio"])[0] or "audio").lower()
                if mode == "video":
                    try:
                        quality = int(params.get("quality", ["720"])[0])
                    except (TypeError, ValueError):
                        quality = 720
                    if quality not in (360, 720, 1080):
                        quality = 720
                    logger.info(f"Descarga VIDEO proxy: {video_id} - {title} "
                                f"({quality}p)")
                    self._proxy_video_download(video_id, title, quality)
                    return
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
                # Verificar PO token provider (vias HTTP y script)
                # El sondeo lo hace download_engine y lo cachea; force=True
                # porque aqui se quiere el estado actual, no el cacheado.
                po_provider_ok = po_http_provider_alive(force=True)
                po_provider_url = os.environ.get("PO_TOKEN_PROVIDER_URL", "")
                # Via script: disponible si existe el repo del provider
                po_script_ok = bool(
                    os.environ.get("BGUTIL_SERVER_HOME")
                    and os.path.isfile(os.path.join(
                        os.environ["BGUTIL_SERVER_HOME"], "build", "generate_once.js"))
                )
                self._json(200, {
                    "status": "ok",
                    "has_cookies": os.path.isfile(COOKIES_FILE),
                    "has_proxy": bool(RESIDENTIAL_PROXY),
                    "has_po_provider": po_provider_ok,
                    "po_provider_url": po_provider_url or None,
                    "po_script_ok": po_script_ok,
                    "yt_dlp_version": _get_ytdlp_version(),
                    "uptime": _get_uptime(),
                    "build_commit": _get_build_commit(),
                    "client_health": get_client_health(),
                    "direct_path": direct_path_state(),
                })
                return

            if path == "/api/clear-preview-cache":
                result = self._clear_preview_cache()
                self._json(200, {
                    "status": "ok",
                    "message": f"Preview cache cleared. {result['deleted']} files deleted.",
                    **result,
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

    # ─── Proxy download (streaming progresivo) ────────────────
    # AHORA usa pipeline yt-dlp | ffmpeg igual que preview, enviando datos
    # al cliente progresivamente mientras se procesan. Ya NO espera a que
    # yt-dlp termine de descargar todo el mix antes de empezar a enviar.
    # Para mixes largos (1h+), el cliente empieza a recibir datos en ~5-10s.
    DOWNLOAD_CACHE_DIR = os.path.join(
        os.environ.get("LOG_DIR", "/opt/mp3downloader/logs"), "download_cache"
    )
    DOWNLOAD_CACHE_MAX_AGE_H = 48
    DOWNLOAD_CACHE_MAX_FILES = 30

    def _get_download_path(self, video_id: str, ext: str = ".mp3", quality: int = 0) -> str:
        safe_id = video_id.replace("/", "_").replace("..", "_")
        # La calidad va en el nombre: 720p y 1080p del mismo vídeo son ficheros
        # distintos y no pueden compartir la entrada de caché.
        suffix = f"_q{quality}" if quality else ""
        return os.path.join(self.DOWNLOAD_CACHE_DIR, f"{safe_id}{suffix}{ext}")

    def _serve_file(self, path: str, content_type: str, video_id: str) -> bool:
        """Sirve un fichero ya descargado con Content-Length real.

        Devuelve False si no hay nada servible, para que el llamante siga con
        la descarga. El Content-Length es lo que permite que el cliente muestre
        un porcentaje honesto: en la ruta de audio por tubería no se conoce el
        tamaño hasta el final, aquí sí.
        """
        if not os.path.isfile(path) or os.path.getsize(path) <= 1024:
            return False
        try:
            file_size = os.path.getsize(path)
            self.protocol_version = "HTTP/1.1"
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(file_size))
            self.send_header("Cache-Control", "public, max-age=3600")
            self.send_header("X-Video-Id", video_id)
            self._cors_headers()
            self.end_headers()
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            logger.info(f"Sirviendo fichero {os.path.basename(path)}: "
                        f"{video_id} ({file_size} bytes)")
            return True
        except BrokenPipeError:
            logger.debug(f"Cliente desconectado durante {os.path.basename(path)}")
            return True
        except Exception as e:
            logger.warning(f"Error sirviendo {path}: {e}")
            return False

    def _cleanup_download_cache(self) -> None:
        try:
            if not os.path.isdir(self.DOWNLOAD_CACHE_DIR):
                return
            files = [
                (os.path.join(self.DOWNLOAD_CACHE_DIR, f),
                 os.path.getmtime(os.path.join(self.DOWNLOAD_CACHE_DIR, f)))
                for f in os.listdir(self.DOWNLOAD_CACHE_DIR)
                # .mp4 entra también: los vídeos pesan mucho más y se comen el
                # disco del plan gratis antes que los MP3.
                if f.endswith(".mp3") or f.endswith(".mp4")
            ]
            if len(files) <= self.DOWNLOAD_CACHE_MAX_FILES:
                return
            files.sort(key=lambda x: x[1])
            for path, _ in files[:len(files) - self.DOWNLOAD_CACHE_MAX_FILES]:
                try: os.remove(path)
                except Exception: pass
        except Exception:
            pass

    def _video_format_selector(self, quality: int) -> str:
        """Selector de formato para vídeo.

        Calidades fijas y no "best": con "best" el tamaño final depende de lo
        que YouPub tenga publicado en ese momento y el cliente no podría
        fiarse del total para el porcentaje. El último "b" es el salto de
        seguridad para vídeos sin pista combinada o con altura desconocida.
        """
        if quality <= 0:
            return "bv*+ba/b"
        return f"bv*[height<={quality}]+ba/b[height<={quality}]/b"

    def _merged_video_in(self, workdir: str) -> str | None:
        """Localiza el MP4 ya mergeado en el directorio de trabajo."""
        try:
            for name in sorted(os.listdir(workdir)):
                if not name.endswith(".mp4"):
                    continue
                path = os.path.join(workdir, name)
                if os.path.getsize(path) > 1024:
                    return path
        except OSError:
            return None
        return None

    def _proxy_video_download(self, video_id: str, title: str, quality: int) -> None:
        """Descarga el vídeo a fichero y lo sirve con Content-Length.

        No se puede reutilizar la ruta de audio por tubería: el merge de vídeo
        necesita que ffmpeg escriba el MP4 en un fichero, así que aquí primero
        se baja y se mergea, y después se sirve el fichero ya completo.

        La contrapartida es que el cliente no ve el primer byte hasta que el
        vídeo está entero. A cambio el Content-Length es exacto y el progreso
        es un porcentaje real, no una estimación.
        """
        download_path = self._get_download_path(video_id, ext=".mp4", quality=quality)
        os.makedirs(self.DOWNLOAD_CACHE_DIR, exist_ok=True)

        if self._serve_file(download_path, "video/mp4", video_id):
            return

        fmt = self._video_format_selector(quality)
        url = f"https://youtube.com/watch?v={video_id}"
        timeout_s = int(os.environ.get("VIDEO_DOWNLOAD_TIMEOUT", "420"))
        last_err = ""

        # ── 1. Clients directos, con el mismo deadline que el audio ──
        direct_deadline = float(os.environ.get("DIRECT_CLIENTS_DEADLINE", "8"))
        if not direct_path_available():
            direct_deadline = 0.0
        _start = time.monotonic()

        for client in ordered_clients():
            if time.monotonic() - _start > direct_deadline:
                logger.info(f"Deadline de clients directos alcanzado para vídeo "
                            f"{video_id}, pasando a proxy")
                break
            workdir = tempfile.mkdtemp(prefix="mp3vid_")
            try:
                cmd = _base_cmd(client) + [
                    "--newline",
                    "-f", fmt,
                    "--merge-output-format", "mp4",
                    "--no-playlist", "--no-part",
                    "-o", os.path.join(workdir, "v.%(ext)s"),
                    url,
                ]
                proc = subprocess.run(
                    cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    timeout=timeout_s,
                )
                merged = self._merged_video_in(workdir)
                if proc.returncode == 0 and merged:
                    os.replace(merged, download_path)
                    self._cleanup_download_cache()
                    record_success(client)
                    self._serve_file(download_path, "video/mp4", video_id)
                    return
                last_err = (proc.stderr or b"").decode(errors="replace")[-300:]
                record_failure(client)
                logger.warning(f"Vídeo falló (client {client}): {last_err}")
            except subprocess.TimeoutExpired:
                last_err = "timeout descargando el vídeo"
                record_failure(client)
                logger.warning(f"Vídeo timeout (client {client}) para {video_id}")
            except Exception as e:
                last_err = str(e)[:300]
                record_failure(client)
                logger.warning(f"Vídeo excepción (client {client}): {e}")
            finally:
                shutil.rmtree(workdir, ignore_errors=True)

        # ── 2. Proxy SOCKS5, que es la única vía que funciona desde datacenter ──
        record_direct_failure()
        blocked: set = set()
        max_proxy_attempts = int(os.environ.get("MAX_PROXY_ATTEMPTS", "3"))
        for _attempt in range(max_proxy_attempts):
            proxy = _find_working_proxy(video_id, blocked=blocked)
            if not proxy:
                break
            workdir = tempfile.mkdtemp(prefix="mp3vid_px_")
            try:
                logger.info(f"Vídeo {video_id} por proxy {proxy}")
                cmd = _proxy_cmd_video(video_id, proxy, quality, workdir)
                proc = subprocess.run(
                    cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    timeout=timeout_s,
                )
                merged = self._merged_video_in(workdir)
                if merged:
                    os.replace(merged, download_path)
                    self._cleanup_download_cache()
                    self._serve_file(download_path, "video/mp4", video_id)
                    return
                last_err = (proc.stderr or b"").decode(errors="replace")[-300:]
                blocked.add(proxy)
                logger.warning(f"Vídeo falló por proxy {proxy}: {last_err}")
            except subprocess.TimeoutExpired:
                last_err = "timeout en el proxy"
                blocked.add(proxy)
                logger.warning(f"Vídeo timeout por proxy {proxy}")
            except Exception as e:
                last_err = str(e)[:300]
                blocked.add(proxy)
                logger.warning(f"Vídeo excepción por proxy {proxy}: {e}")
            finally:
                shutil.rmtree(workdir, ignore_errors=True)

        self._json(502, {
            "error": "No se pudo descargar el vídeo",
            "detail": last_err[:300] or "sin detalle",
            "videoId": video_id,
            "quality": quality,
        })

    def _proxy_download(self, video_id: str, title: str) -> None:
        import subprocess as _sp

        download_path = self._get_download_path(video_id)

        # ── Cache hit: servir instantáneo ──
        if os.path.isfile(download_path) and os.path.getsize(download_path) > 1024:
            age_h = (time.time() - os.path.getmtime(download_path)) / 3600
            if age_h < self.DOWNLOAD_CACHE_MAX_AGE_H:
                file_size = os.path.getsize(download_path)
                try:
                    self.send_response(200)
                    self.send_header("Content-Type", "audio/mpeg")
                    self.send_header("Content-Length", str(file_size))
                    self._cors_headers()
                    self.send_header("Cache-Control", "public, max-age=3600")
                    self.send_header("X-Video-Id", video_id)
                    self.end_headers()
                    with open(download_path, "rb") as f:
                        while True:
                            chunk = f.read(8192)
                            if not chunk: break
                            self.wfile.write(chunk)
                    logger.info(f"Download desde cache: {video_id} ({file_size} bytes)")
                    return
                except BrokenPipeError:
                    logger.debug(f"Cliente desconectado durante download cache de {video_id}")
                    return
                except Exception as e:
                    logger.warning(f"Error sirviendo download cache: {e}")

        # ── Cache miss: pipeline streaming ──
        yt_url = f"https://youtube.com/watch?v={video_id}"
        last_err = ""
        os.makedirs(self.DOWNLOAD_CACHE_DIR, exist_ok=True)

        # Límite de tiempo para el bucle de clients DIRECTOS. En IPs de
        # datacenter (Render/Railway) YouTube responde "Sign in to confirm
        # you're not a bot" a TODOS los clients, y cada fallo tarda varios
        # segundos, por lo que recorrer los 7 agotaba el timeout del request
        # ANTES de llegar al fallback de proxy.
        #
        # Bajado de 25s a 8s: con los reintentos internos de yt-dlp acotados
        # a 1 (YTDLP_EXTRACTOR_RETRIES), un client bloqueado falla en ~1-2s,
        # así que 8s dan margen para probar 3-4 clients y saltar al proxy,
        # que es la única vía que funciona desde datacenter. Los 25s solo se
        # alcanzaban cuando un client se colgaba, y ese caso lo cubre el
        # socket timeout, no el deadline.
        direct_deadline = float(os.environ.get("DIRECT_CLIENTS_DEADLINE", "8"))
        if not direct_path_available():
            # Ya sabemos que esta IP recibe el reto de bot en todos los
            # clients. Saltar los intentos fallidos y yendo directo al proxy.
            direct_deadline = 0.0
            logger.info(
                f"Vía directa en cooldown ({video_id}): yendo directo al proxy "
                f"sin gastar los {os.environ.get('DIRECT_CLIENTS_DEADLINE', '8')}s "
                f"de clients bloqueados"
            )
        _clients_start = time.monotonic()

        for client in ordered_clients():
            if time.monotonic() - _clients_start > direct_deadline:
                logger.info(f"Deadline de clients directos alcanzado para {video_id} "
                            f"({direct_deadline:.0f}s), pasando a proxy...")
                break
            yt_cmd = _base_cmd(client) + [
                "-o", "-",
                "-f", "bestaudio[ext=m4a]/bestaudio/best",
                "--no-playlist", "--no-part",
                yt_url,
            ]
            ffmpeg_cmd = [
                "ffmpeg", "-y", "-i", "-",
                "-codec:a", "libmp3lame", "-b:a", "256k",
                "-id3v2_version", "3",
                "-f", "mp3", "-",
            ]

            tmp_path = download_path + ".tmp"
            try:
                p1 = _sp.Popen(yt_cmd, stdout=_sp.PIPE, stderr=_sp.PIPE)
                p2 = _sp.Popen(ffmpeg_cmd, stdin=p1.stdout, stdout=_sp.PIPE, stderr=_sp.PIPE)
                p1.stdout.close()

                first = p2.stdout.read(8192)
                if not first:
                    stderr = b""
                    if p1.stderr: stderr += (p1.stderr.read() or b"")
                    if p2.stderr: stderr += (p2.stderr.read() or b"")
                    last_err = stderr.decode(errors="replace")[:300] or "no se produjo audio"
                    record_failure(client)
                    logger.warning(f"Download falló (client {client}): {last_err}")
                    try: p1.terminate(); p2.terminate()
                    except Exception: pass
                    continue

                # Headers inmediatos (chunked — no sabemos el tamaño final aún)
                self.protocol_version = "HTTP/1.1"
                self.send_response(200)
                self.send_header("Content-Type", "audio/mpeg")
                self.send_header("Transfer-Encoding", "chunked")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("X-Video-Id", video_id)
                self._cors_headers()
                self.end_headers()

                try:
                    # Primer chunk
                    self.wfile.write(f"{len(first):x}\r\n".encode())
                    self.wfile.write(first)
                    self.wfile.write(b"\r\n")
                    self.wfile.flush()

                    cache_file = open(tmp_path, "wb")
                    cache_file.write(first)
                    total_bytes = len(first)

                    try:
                        while True:
                            chunk = p2.stdout.read(8192)
                            if not chunk: break
                            self.wfile.write(f"{len(chunk):x}\r\n".encode())
                            self.wfile.write(chunk)
                            self.wfile.write(b"\r\n")
                            self.wfile.flush()
                            cache_file.write(chunk)
                            total_bytes += len(chunk)
                    finally:
                        cache_file.close()

                    self.wfile.write(b"0\r\n\r\n")
                    self.wfile.flush()

                    os.replace(tmp_path, download_path)
                    self._cleanup_download_cache()

                    record_success(client)
                    record_direct_success()
                    logger.info(f"Download streaming completado: {video_id} "
                                f"(client={client}, {total_bytes} bytes)")
                    return

                except BrokenPipeError:
                    logger.debug(f"Cliente desconectado durante download de {video_id}")
                    try:
                        if os.path.exists(tmp_path) and os.path.getsize(tmp_path) > 1024:
                            os.replace(tmp_path, download_path)
                    except Exception: pass
                    return

            except Exception as e:
                last_err = str(e)[:300]
                record_failure(client)
                logger.warning(f"Download excepción (client {client}): {e}")
                try:
                    if os.path.exists(tmp_path): os.remove(tmp_path)
                except Exception: pass
                try: p1.terminate(); p2.terminate()
                except Exception: pass
            finally:
                try: p1.terminate()
                except Exception: pass
                try: p2.terminate()
                except Exception: pass
                try: p1.stdout.close()
                except Exception: pass
                try: p2.stdout.close()
                except Exception: pass

        # Todos los clients fallaron → intentar proxy SOCKS5
        record_direct_failure()
        # NOTA: la descarga COMPLETA debe pasar por el proxy. YouTube firma
        # la URL de googlevideo para la IP que la solicitó, así que obtener
        # la URL con --get-url y descargarla directo desde la IP del server
        # siempre falla (403 → "Proxy download failed").
        #
        # Hacemos VARIOS intentos de proxy: un proxy puede resolver el video
        # (--get-url) pero fallar en la descarga real de bytes, o morir a
        # mitad. Cada intento busca un proxy distinto y descarga; salimos en
        # cuanto uno produce audio. Esto hace el fallback mucho mas tolerable
        # dado que la IP de Render esta bloqueada y el proxy es la unica via.
        logger.info(f"yt-dlp falló para {video_id}, intentando proxy SOCKS5...")
        # Lista negra por-request: proxies cuya descarga COMPLETA falló.
        # Así el reintento no vuelve a probar el mismo proxy fallido.
        _blocked_proxies: set = set()
        max_proxy_attempts = int(os.environ.get("MAX_PROXY_ATTEMPTS", "3"))
        for _attempt in range(max_proxy_attempts):
            proxy = _find_working_proxy(video_id, blocked=_blocked_proxies)
            if not proxy:
                logger.warning(f"Proxy falló para {video_id} (no se encontró proxy), "
                               f"intentando Invidious fallback...")
                self._proxy_download_invidious(video_id, title)
                return
            ok = self._stream_proxy_download(video_id, proxy)
            if ok:
                return
            _blocked_proxies.add(proxy)
            logger.info(f"Proxy {proxy} falló la descarga para {video_id}, "
                        f"probando otro proxy...")

        # Agotados los intentos de proxy sin producir audio → Invidious
        logger.warning(f"Proxy falló para {video_id} "
                       f"({max_proxy_attempts} intentos sin audio), "
                       f"intentando Invidious fallback...")
        self._proxy_download_invidious(video_id, title)

    def _stream_proxy_download(self, video_id: str, proxy: str) -> None:
        """Stream download a través del proxy.

        Usa `_proxy_cmd` → yt-dlp baja el audio completo vía el proxy y lo
        vuelca a stdout; ffmpeg lo convierte a MP3. Esto mantiene la firma
        de la URL (YouTube la firma para la IP del proxy) y evita el 403.
        """
        import subprocess as _sp

        download_path = self._get_download_path(video_id)
        os.makedirs(self.DOWNLOAD_CACHE_DIR, exist_ok=True)

        p1 = None
        p2 = None
        try:
            yt_cmd = _proxy_cmd(video_id, proxy, output_stdout=True)
            ffmpeg_cmd = [
                "ffmpeg", "-y", "-i", "-",
                "-codec:a", "libmp3lame", "-b:a", "256k",
                "-id3v2_version", "3",
                "-f", "mp3", "-",
            ]

            tmp_path = download_path + ".tmp"
            p1 = _sp.Popen(yt_cmd, stdout=_sp.PIPE, stderr=_sp.PIPE)
            p2 = _sp.Popen(ffmpeg_cmd, stdin=p1.stdout, stdout=_sp.PIPE, stderr=_sp.PIPE)
            p1.stdout.close()

            # Esperar el primer byte con timeout: si el proxy/yt-dlp nunca
            # produce audio, abortamos en vez de bloquear el request hasta
            # el timeout global. Un proxy que resuelve --get-url puede aun
            # colgarse al descargar, y bloquear aqui gastaria decenas de
            # segundos por intento.
            #
            # Bajado de 35s a 12s: un proxy sano entrega el primer bloque de
            # audio en 1-3s (es lo que se midió en el get-url que lo
            # seleccionó). 12s es margen de sobra para el arranque de yt-dlp
            # más el PO token; más allá de eso el proxy está muerto y cada
            # segundo extra se paga con los otros intentos de la cadena.
            first_byte_timeout = float(
                os.environ.get("PROXY_FIRST_BYTE_TIMEOUT", "12"))
            try:
                import select as _select
                _ready, _, _ = _select.select([p2.stdout], [], [], first_byte_timeout)
                if not _ready:
                    logger.warning(
                        f"Proxy download timeout (sin audio en {first_byte_timeout:.0f}s) "
                        f"para {video_id}")
                    try: p1.terminate()
                    except Exception: pass
                    try: p2.terminate()
                    except Exception: pass
                    return False
            except Exception:
                pass

            first = p2.stdout.read(8192)
            if not first:
                stderr = b""
                if p1.stderr: stderr += (p1.stderr.read() or b"")
                if p2.stderr: stderr += (p2.stderr.read() or b"")
                logger.warning(f"Proxy download falló: {stderr.decode(errors='replace')[:200]}")
                try: p1.terminate()
                except Exception: pass
                try: p2.terminate()
                except Exception: pass
                # NO escribimos 502 aqui: si el fallo es temprano (no hubo
                # audio), devolvemos False para que el llamador reintente con
                # otro proxy. Solo al agotar los intentos se reporta error.
                return False

            self.protocol_version = "HTTP/1.1"
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("X-Video-Id", video_id)
            self._cors_headers()
            self.end_headers()

            try:
                self.wfile.write(f"{len(first):x}\r\n".encode())
                self.wfile.write(first)
                self.wfile.write(b"\r\n")
                self.wfile.flush()

                cache_file = open(tmp_path, "wb")
                cache_file.write(first)
                total_bytes = len(first)

                try:
                    while True:
                        chunk = p2.stdout.read(8192)
                        if not chunk:
                            break
                        self.wfile.write(f"{len(chunk):x}\r\n".encode())
                        self.wfile.write(chunk)
                        self.wfile.write(b"\r\n")
                        self.wfile.flush()
                        cache_file.write(chunk)
                        total_bytes += len(chunk)
                finally:
                    cache_file.close()

                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()

                os.replace(tmp_path, download_path)
                self._cleanup_download_cache()
                _remember_working_proxy(proxy)
                logger.info(f"Proxy download completado: {video_id} "
                            f"(via {proxy}, {total_bytes} bytes)")
                return True

            except BrokenPipeError:
                logger.debug(f"Cliente desconectado durante proxy download de {video_id}")
                try:
                    if os.path.exists(tmp_path) and os.path.getsize(tmp_path) > 1024:
                        os.replace(tmp_path, download_path)
                except Exception:
                    pass
                return

        except Exception as e:
            logger.warning(f"Proxy download excepción: {e}")
            try:
                if os.path.exists(download_path + ".tmp"):
                    os.remove(download_path + ".tmp")
            except Exception:
                pass
            # Devuelve False para que el llamador reintente con otro proxy.
            # Solo al agotar los intentos el flujo reportara error.
            return False
        finally:
            try: p1.terminate()
            except Exception: pass
            try: p2.terminate()
            except Exception: pass
            try: p1.stdout.close()
            except Exception: pass
            try: p2.stdout.close()
            except Exception: pass
        return False

    # ─── Invidious fallback para download ────────────────────
    def _proxy_download_invidious(self, video_id: str, title: str) -> None:
        """Descarga vía Invidious cuando yt-dlp falla."""
        import subprocess as _sp

        audio_url = invidious_get_audio_url(video_id)
        if not audio_url:
            try:
                self._json(502, {"error": "Invidious fallback: no audio URL available"})
            except Exception:
                pass
            return

        download_path = self._get_download_path(video_id)
        os.makedirs(self.DOWNLOAD_CACHE_DIR, exist_ok=True)

        try:
            # Descargar vía Invidious URL → ffmpeg → MP3
            ffmpeg_cmd = [
                "ffmpeg", "-y", "-i", audio_url,
                "-codec:a", "libmp3lame", "-b:a", "256k",
                "-id3v2_version", "3",
                "-f", "mp3", "-",
            ]

            tmp_path = download_path + ".tmp"
            p = _sp.Popen(ffmpeg_cmd, stdout=_sp.PIPE, stderr=_sp.PIPE)

            first = p.stdout.read(8192)
            if not first:
                stderr = p.stderr.read() if p.stderr else b""
                logger.warning(f"Invidious download falló: {stderr.decode(errors='replace')[:200]}")
                p.terminate()
                try:
                    self._json(502, {"error": "Invidious download failed"})
                except Exception:
                    pass
                return

            self.protocol_version = "HTTP/1.1"
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("X-Video-Id", video_id)
            self._cors_headers()
            self.end_headers()

            try:
                self.wfile.write(f"{len(first):x}\r\n".encode())
                self.wfile.write(first)
                self.wfile.write(b"\r\n")
                self.wfile.flush()

                cache_file = open(tmp_path, "wb")
                cache_file.write(first)
                total_bytes = len(first)

                try:
                    while True:
                        chunk = p.stdout.read(8192)
                        if not chunk:
                            break
                        self.wfile.write(f"{len(chunk):x}\r\n".encode())
                        self.wfile.write(chunk)
                        self.wfile.write(b"\r\n")
                        self.wfile.flush()
                        cache_file.write(chunk)
                        total_bytes += len(chunk)
                finally:
                    cache_file.close()

                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()

                os.replace(tmp_path, download_path)
                self._cleanup_download_cache()
                logger.info(f"Invidious download completado: {video_id} ({total_bytes} bytes)")
                return

            except BrokenPipeError:
                logger.debug(f"Cliente desconectado durante Invidious download de {video_id}")
                try:
                    if os.path.exists(tmp_path) and os.path.getsize(tmp_path) > 1024:
                        os.replace(tmp_path, download_path)
                except Exception:
                    pass
                return

        except Exception as e:
            logger.warning(f"Invidious download excepción: {e}")
            try:
                if os.path.exists(download_path + ".tmp"):
                    os.remove(download_path + ".tmp")
            except Exception:
                pass
            try:
                self._json(502, {"error": f"Invidious download failed: {e}"})
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

    def _clear_preview_cache(self) -> dict:
        """Elimina TODOS los archivos del caché de preview."""
        deleted = 0
        errors = 0
        try:
            if not os.path.isdir(self.PREVIEW_CACHE_DIR):
                return {"deleted": 0}
            for f in os.listdir(self.PREVIEW_CACHE_DIR):
                if f.endswith(".mp3") or f.endswith(".tmp"):
                    try:
                        os.remove(os.path.join(self.PREVIEW_CACHE_DIR, f))
                        deleted += 1
                    except Exception:
                        errors += 1
            logger.info(f"Preview cache limpiado: {deleted} archivos eliminados")
            return {"deleted": deleted, "errors": errors}
        except Exception as e:
            logger.error(f"Error limpiando preview cache: {e}")
            return {"deleted": deleted, "errors": errors, "error": str(e)}

    def _proxy_preview(self, video_id: str, title: str) -> None:
        """Preview con streaming progresivo (chunked) + cache.

        DOS MODOS:

        1. Cache hit → sirve instantáneo con Content-Length (segundo play en
           adelante).

        2. Cache miss → streaming chunked: envía headers inmediatamente y
           comienza a mandar datos tan pronto como ffmpeg produce el primer
           chunk. Android MediaPlayer empieza a reproducir en cuanto tiene
           buffer suficiente (~2-5 segundos), sin esperar la descarga
           completa. Simultáneamente se guarda el archivo en cache para
           requests futuros.

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

        # ── Cache miss: streaming chunked + cache simultáneo ──
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

                # Leer primer chunk para validar que hay audio
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

                # ── Enviar headers INMEDIATAMENTE ──
                # Usamos Transfer-Encoding: chunked con HTTP/1.1 para que
                # Android MediaPlayer reciba datos progresivamente y pueda
                # empezar a reproducir con solo ~2-5 segundos de buffer,
                # sin esperar la descarga completa.
                self.protocol_version = "HTTP/1.1"
                self.send_response(200)
                self.send_header("Content-Type", "audio/mpeg")
                self.send_header("Transfer-Encoding", "chunked")
                self.send_header("Cache-Control", "no-cache")
                self._cors_headers()
                self.end_headers()

                try:
                    # Primer chunk
                    self.wfile.write(f"{len(first):x}\r\n".encode())
                    self.wfile.write(first)
                    self.wfile.write(b"\r\n")
                    self.wfile.flush()

                    # Tee: escribir a cache simultáneamente
                    cache_file = open(tmp_path, "wb")
                    cache_file.write(first)
                    total_bytes = len(first)

                    try:
                        while True:
                            chunk = p2.stdout.read(8192)
                            if not chunk:
                                break
                            # Chunked encoding
                            self.wfile.write(f"{len(chunk):x}\r\n".encode())
                            self.wfile.write(chunk)
                            self.wfile.write(b"\r\n")
                            self.wfile.flush()
                            # Cache
                            cache_file.write(chunk)
                            total_bytes += len(chunk)
                    finally:
                        cache_file.close()

                    # Chunk final
                    self.wfile.write(b"0\r\n\r\n")
                    self.wfile.flush()

                    # Renombrar temp a cache (atómico)
                    os.replace(tmp_path, preview_path)
                    self._cleanup_preview_cache()

                    record_success(client)
                    record_direct_success()
                    logger.info(f"Preview streaming completado: {video_id} "
                                f"(client={client}, {total_bytes} bytes)")
                    return

                except BrokenPipeError:
                    logger.debug(f"Cliente desconectado durante preview de {video_id}")
                    # Guardar cache aunque el cliente se desconecte
                    try:
                        if os.path.exists(tmp_path) and os.path.getsize(tmp_path) > 1024:
                            os.replace(tmp_path, preview_path)
                    except Exception:
                        pass
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
                # Asegurar limpieza de procesos
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

        # Todos los clients fallaron → intentar proxy SOCKS5
        record_direct_failure()
        # (descarga COMPLETA vía proxy para que la firma de la URL coincida)
        logger.info(f"yt-dlp preview falló para {video_id}, intentando proxy SOCKS5...")
        proxy = _find_working_proxy(video_id)
        if proxy:
            self._stream_proxy_preview(video_id, proxy)
            return

        # Fallback 2: intentar Invidious
        logger.info(f"Proxy preview falló para {video_id}, intentando Invidious...")
        self._proxy_preview_invidious(video_id, title)

    def _stream_proxy_preview(self, video_id: str, proxy: str) -> None:
        """Stream preview a través del proxy (yt-dlp --proxy → ffmpeg)."""
        import subprocess as _sp

        preview_path = self._get_preview_path(video_id)
        os.makedirs(self.PREVIEW_CACHE_DIR, exist_ok=True)

        p1 = None
        p2 = None
        try:
            yt_cmd = _proxy_cmd(video_id, proxy, output_stdout=True)
            ffmpeg_cmd = [
                "ffmpeg", "-y", "-i", "-",
                "-f", "mp3", "-ab", "128k", "-ar", "44100", "-",
            ]

            tmp_path = preview_path + ".tmp"
            p1 = _sp.Popen(yt_cmd, stdout=_sp.PIPE, stderr=_sp.PIPE)
            p2 = _sp.Popen(ffmpeg_cmd, stdin=p1.stdout, stdout=_sp.PIPE, stderr=_sp.PIPE)
            p1.stdout.close()

            first = p2.stdout.read(8192)
            if not first:
                stderr = b""
                if p1.stderr: stderr += (p1.stderr.read() or b"")
                if p2.stderr: stderr += (p2.stderr.read() or b"")
                logger.warning(f"Proxy preview falló: {stderr.decode(errors='replace')[:200]}")
                try: p1.terminate()
                except Exception: pass
                try: p2.terminate()
                except Exception: pass
                try:
                    self._json(502, {"error": "Proxy preview failed"})
                except Exception:
                    pass
                return

            self.protocol_version = "HTTP/1.1"
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Cache-Control", "no-cache")
            self._cors_headers()
            self.end_headers()

            try:
                self.wfile.write(f"{len(first):x}\r\n".encode())
                self.wfile.write(first)
                self.wfile.write(b"\r\n")
                self.wfile.flush()

                cache_file = open(tmp_path, "wb")
                cache_file.write(first)
                total_bytes = len(first)

                try:
                    while True:
                        chunk = p2.stdout.read(8192)
                        if not chunk:
                            break
                        self.wfile.write(f"{len(chunk):x}\r\n".encode())
                        self.wfile.write(chunk)
                        self.wfile.write(b"\r\n")
                        self.wfile.flush()
                        cache_file.write(chunk)
                        total_bytes += len(chunk)
                finally:
                    cache_file.close()

                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()

                os.replace(tmp_path, preview_path)
                self._cleanup_preview_cache()
                logger.info(f"Proxy preview completado: {video_id} "
                            f"(via {proxy}, {total_bytes} bytes)")
                return

            except BrokenPipeError:
                logger.debug(f"Cliente desconectado durante proxy preview de {video_id}")
                try:
                    if os.path.exists(tmp_path) and os.path.getsize(tmp_path) > 1024:
                        os.replace(tmp_path, preview_path)
                except Exception:
                    pass
                return

        except Exception as e:
            logger.warning(f"Proxy preview excepción: {e}")
            try:
                if os.path.exists(preview_path + ".tmp"):
                    os.remove(preview_path + ".tmp")
            except Exception:
                pass
            try:
                self._json(502, {"error": f"Proxy preview failed: {e}"})
            except Exception:
                pass
        finally:
            try: p1.terminate()
            except Exception: pass
            try: p2.terminate()
            except Exception: pass
            try: p1.stdout.close()
            except Exception: pass
            try: p2.stdout.close()
            except Exception: pass

    def _proxy_preview_invidious(self, video_id: str, title: str) -> None:
        """Preview vía Invidious cuando yt-dlp falla."""
        import subprocess as _sp

        audio_url = invidious_get_audio_url(video_id)
        if not audio_url:
            try:
                self._json(502, {"error": "Invidious preview: no audio URL available"})
            except Exception:
                pass
            return

        preview_path = self._get_preview_path(video_id)
        os.makedirs(self.PREVIEW_CACHE_DIR, exist_ok=True)

        try:
            ffmpeg_cmd = [
                "ffmpeg", "-y", "-i", audio_url,
                "-f", "mp3", "-ab", "128k", "-ar", "44100", "-",
            ]

            tmp_path = preview_path + ".tmp"
            p = _sp.Popen(ffmpeg_cmd, stdout=_sp.PIPE, stderr=_sp.PIPE)

            first = p.stdout.read(8192)
            if not first:
                stderr = p.stderr.read() if p.stderr else b""
                logger.warning(f"Invidious preview falló: {stderr.decode(errors='replace')[:200]}")
                p.terminate()
                try:
                    self._json(502, {"error": "Invidious preview failed"})
                except Exception:
                    pass
                return

            self.protocol_version = "HTTP/1.1"
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Cache-Control", "no-cache")
            self._cors_headers()
            self.end_headers()

            try:
                self.wfile.write(f"{len(first):x}\r\n".encode())
                self.wfile.write(first)
                self.wfile.write(b"\r\n")
                self.wfile.flush()

                cache_file = open(tmp_path, "wb")
                cache_file.write(first)
                total_bytes = len(first)

                try:
                    while True:
                        chunk = p.stdout.read(8192)
                        if not chunk:
                            break
                        self.wfile.write(f"{len(chunk):x}\r\n".encode())
                        self.wfile.write(chunk)
                        self.wfile.write(b"\r\n")
                        self.wfile.flush()
                        cache_file.write(chunk)
                        total_bytes += len(chunk)
                finally:
                    cache_file.close()

                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()

                os.replace(tmp_path, preview_path)
                self._cleanup_preview_cache()
                logger.info(f"Invidious preview completado: {video_id} ({total_bytes} bytes)")
                return

            except BrokenPipeError:
                logger.debug(f"Cliente desconectado durante Invidious preview de {video_id}")
                try:
                    if os.path.exists(tmp_path) and os.path.getsize(tmp_path) > 1024:
                        os.replace(tmp_path, preview_path)
                except Exception:
                    pass
                return

        except Exception as e:
            logger.warning(f"Invidious preview excepción: {e}")
            try:
                if os.path.exists(preview_path + ".tmp"):
                    os.remove(preview_path + ".tmp")
            except Exception:
                pass
            try:
                self._json(502, {"error": f"Invidious preview failed: {e}"})
            except Exception:
                pass

    # ─── Timeout de conexión ─────────────────────────────────
    def handle_one_request(self):
        try:
            super().handle_one_request()
        except (ConnectionError, TimeoutError, BrokenPipeError):
            pass


def _get_build_commit() -> str:
    """SHA corto del commit desplegado, o 'unknown'.

    Railway y Render inyectan el SHA del commit en el entorno, así que esto
    dice sin ambigüedad qué código está corriendo cada despliegue. Se añadió
    porque `uptime` miente: lee /proc/uptime, que es el uptime del HOST y no
    del proceso, así que no cambia con un redeploy y no sirve para distinguir
    un despliegue viejo de uno nuevo.
    """
    for var in ("RAILWAY_GIT_COMMIT_SHA", "RENDER_GIT_COMMIT", "GIT_SHA"):
        sha = os.environ.get(var, "").strip()
        if sha:
            return sha[:7]
    return "unknown"


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
    logger.info(f"Proxy:       {'CONFIGURADO' if RESIDENTIAL_PROXY else 'NO CONFIGURADO'}")
    logger.info(f"Log level:   {LOG_LEVEL}")
    logger.info("═" * 50)

    # ── Iniciar servidor INMEDIATAMENTE ────────────────────
    # Railway hace healthcheck al contenedor casi al instante. Si el
    # servidor no está escuchando, el deploy falla por timeout. Por eso
    # arrancamos el servidor ANTES de ejecutar tareas lentas de inicio
    # (version check, pip install, restore cookies).
    server = ThreadingHTTPServer((HOST, PORT), APIHandler)
    logger.info(f"Servidor escuchando en http://{HOST}:{PORT}")

    # ── Background: tareas lentas de inicio ────────────────
    import threading as _threading

    def _startup_tasks():
        try:
            ensure_ytdlp_updated()
        except Exception as e:
            logger.warning(f"Startup task 'yt-dlp update' falló: {e}")
        try:
            restore_cookies_from_env()
        except Exception as e:
            logger.warning(f"Startup task 'restore cookies' falló: {e}")

    bg = _threading.Thread(target=_startup_tasks, daemon=True)
    bg.start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("Servidor detenido por el usuario")
        server.shutdown()


if __name__ == "__main__":
    main()
