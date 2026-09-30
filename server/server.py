"""
MP3 Downloader Server — API HTTP para descargas de YouTube vía yt-dlp
====================================================================
Modo producción: despliega en Oracle Cloud Free Tier para tener el
servidor 24/7 sin necesidad de mantener tu PC encendida.

Endpoints:
  GET  /api/search     ?q=<query>            → Lista de canciones
  GET  /api/stream-url ?videoId=<id>         → URL directa de audio
  GET  /api/download   ?videoId=<id>&title=  → Stream del audio (proxy)
  GET  /api/download   ?videoId=<id>&mode=video&quality=<240|360|480|720|1080>
                                              → MP4 mergeado (Content-Length real)
  GET  /api/ready    ?videoId=<id>&quality=<q> → ¿Puede el host servir este vídeo?
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
    video_format_selector,
    _find_working_proxy,
    _remember_working_proxy,
    ordered_clients,
    ordered_video_clients,
    get_client_health,
    record_success,
    po_http_provider_alive,
    record_failure,
    direct_path_available,
    direct_path_state,
    record_direct_failure,
    record_direct_success,
    DIRECT_PATH_COOLDOWN_REASON,
    is_video_level_error,
    is_bot_challenge,
    _is_auth_error,
    _is_too_big_error,
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

# Escalera de clients que se prueban, UNO POR COMANDO, contra un mismo proxy
# SOCKS5. Se nombra para que el presupuesto de tiempo del intento se pueda
# calcular a partir de ella: son 3 clientes y antes cada uno podía comerse
# los VIDEO_DOWNLOAD_TIMEOUT por separado.
#
# mweb primero: es el que expone la escalera completa y es el único verificado
# bajando 1080p por proxy. Detrás, web_embedded (escalera sin garantía de
# token) y android (muxed itag 18 de 640x360, sin token, el suelo que cierra
# la escalera para no devolver 502).
_PROXY_CLIENTS = ("mweb", "web_embedded", "android")

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


# ═══════════════════════════════════════════════════════════════
# Lectura con reloj del primer byte
# ────────────────────────────────────────────────────────────────
# `pipe.stdout.read(8192)` a pelo es un BLOQUEO INDEFINIDO: no devuelve
# nunca ni un byte ni un error si el otro extremo se cuelga sin cerrar. Con
# eso, un yt-dlp o un ffmpeg que se quedan colgados dejan el request abierto
# para siempre, sin una línea de log y sin que Railway pueda ni cortarlo.
#
# Estas cuatro rutas arrancan dos procesos y esperan su primer bloque, así que
# todas necesitan reloj. El valor sale medido el 2026-09-30 sobre 8 proxies
# SOCKS5 que sí funcionan: min 11,7 s, mediana 13,6 s, max 29,6 s. Con el
# tope anterior de 12 s, 7 de 8 morían antes de dar su primer byte.
def _read_first_byte(stream, timeout_s: float) -> bytes | None:
    """Primer bloque del pipe, o None si no llega nada en `timeout_s`."""
    try:
        import select as _select
        ready, _, _ = _select.select([stream], [], [], timeout_s)
        if not ready:
            return None
    except Exception:
        # select no disponible (o el objeto no es un fd real): mejor el
        # comportamiento viejo de bloquear que dejar de servir.
        pass
    return stream.read(8192)


def _first_byte_timeout_s() -> float:
    return float(os.environ.get("PROXY_FIRST_BYTE_TIMEOUT", "30"))


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
                    # Las mismas calidades que el selector del cliente. Si el
                    # cliente pide otra cosa, se cae a 720p en vez de
                    # inventar un formato que el servidor no pediría igual.
                    # 1080p es el techo del lado servidor por la misma razón
                    # que en el cliente: por encima el h264 deja de estar
                    # publicado y el merge a MP4 dejaría de ser una copia.
                    if quality not in (240, 360, 480, 720, 1080):
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

            if path == "/api/ready":
                # ¿Puede este host sacar este vídeo AHORA? No se deduce del
                # circuit breaker: eso guarda la opinión del host sobre sus
                # clientes, y un host puede tenerlos "sanos" mientras YouTube
                # le rechaza. La única forma de saberlo es preguntar de verdad,
                # que es lo mismo que hace la descarga al empezar, así que el
                # tamaño calculado aquí se cachea y le sirve a la descarga.
                video_id = params.get("videoId", [""])[0]
                if not video_id:
                    self._json(400, {"ok": False, "error": "Missing ?videoId="})
                    return
                try:
                    quality = int(params.get("quality", ["720"])[0])
                except (TypeError, ValueError):
                    quality = 720
                # Deadline corto a propósito: la app espera 25 s y solo cambia
                # de host si esta respuesta llega antes. Con 15 s hay margen
                # para que responda aunque YouTube se cuelgue.
                estimate = self._video_size_estimate(
                    video_id,
                    quality,
                    deadline_s=float(os.environ.get("READY_PROBE_DEADLINE", "15")),
                )
                self._json(200, {
                    "ok": estimate is not None,
                    "videoId": video_id,
                    "quality": quality,
                    "estimatedBytes": estimate,
                    "limitBytes": self.VIDEO_MAX_BYTES,
                })
                return

            if path == "/api/health":
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
    # Tope de peso para un vídeo. El cliente tiene el mismo número: si solo
    # lo comprobara el cliente, el servidor se habría gastado la descarga
    # entera y el disco para que al final la app lo tirase. 1 GB es una peli
    # larga a 1080p; a partir de ahí el merge (que necesita el doble de disco
    # mientras ocurre) ya no cabe cómodo en el plan gratis del host.
    VIDEO_MAX_BYTES = int(os.environ.get("VIDEO_MAX_BYTES", str(1024 * 1024 * 1024)))
    # Presupuesto total de la caché, por encima del número de ficheros. Con 30
    # ficheros de 1 GB el disco se llenaba igualmente: contar ficheros no
    # acota nada cuando cada uno pesa mucho. Es lo que tumbó el contenedor.
    DOWNLOAD_CACHE_MAX_TOTAL_BYTES = int(
        os.environ.get("DOWNLOAD_CACHE_MAX_TOTAL_BYTES", str(2 * 1024 * 1024 * 1024))
    )
    # Tamaño estimado por (vídeo, calidad), para que /api/ready y la descarga no
    # repitan la extracción. Se cachea un fallo poco tiempo porque suele ser un
    # bloqueo temporal de YouTube que se levanta enseguida.
    _SIZE_ESTIMATE_CACHE: dict = {}
    SIZE_ESTIMATE_TTL_S = 300
    SIZE_ESTIMATE_TTL_FAIL_S = 30

    def _get_download_path(self, video_id: str, ext: str = ".mp3", quality: int = 0) -> str:
        safe_id = video_id.replace("/", "_").replace("..", "_")
        # La calidad va en el nombre: 720p y 1080p del mismo vídeo son ficheros
        # distintos y no pueden compartir la entrada de caché.
        suffix = f"_q{quality}" if quality else ""
        return os.path.join(self.DOWNLOAD_CACHE_DIR, f"{safe_id}{suffix}{ext}")

    def _video_workdir(self, prefix: str) -> str:
        """Directorio de trabajo de una descarga de vídeo, junto a la caché.

        NO se usa `tempfile.mkdtemp()` a pelo, que cae en /tmp. Dos motivos,
        y el segundo es el que duele:

        1. Al final se hace `os.replace(merged, download_path)`, y `os.replace`
           solo es atómico **dentro del mismo sistema de ficheros**. Con el
           workdir en /tmp y la caché en otro sitio, el rename falla con
           `[Errno 18] Invalid cross-device link`. Cazar eso el 2026-09-30
           costó un 1080p ya descargado y mergeado entero: la descarga se
           hizo bien y se tiró en el último paso.
        2. Un vídeo de 1080p son 50 MB en /tmp. En un contenedor de plan
           gratuito /tmp es tmpfs, o sea RAM: cada descarga de vídeo se
           estaba comiendo memoria en vez de disco.
        """
        os.makedirs(self.DOWNLOAD_CACHE_DIR, exist_ok=True)
        return tempfile.mkdtemp(prefix=prefix,
                                dir=self.DOWNLOAD_CACHE_DIR)

    @staticmethod
    def _video_dimensions(path: str) -> tuple[int, int] | None:
        """Resolución REAL del MP4 ya descargado, o None si no se puede leer.

        Se mide el fichero, no lo que se pidió. Pedir 1080p y servir 360p sin
        decir nada era el defecto de esta ruta: el selector de formatos cae a
        su último término cuando no hay formatos altos y ahí no hay forma de
        saber qué se pidió. Con la medida, el servidor manda la verdad en una
        cabecera y el cliente etiqueta lo que realmente trae.
        """
        try:
            proc = subprocess.run(
                ["ffprobe", "-v", "error", "-select_streams", "v:0",
                 "-show_entries", "stream=width,height",
                 "-of", "csv=p=0:s=x", path],
                capture_output=True, text=True, timeout=15,
            )
            w, _, h = proc.stdout.strip().partition("x")
            return int(w), int(h)
        except Exception:
            return None

    @classmethod
    def _file_height(cls, path: str) -> int:
        """Altura del MP4, o 0 si no se puede leer. Para comparar candidatos."""
        dims = cls._video_dimensions(path)
        return dims[1] if dims else 0

    @staticmethod
    def _discard(path: str) -> None:
        """Borra un fichero intermedio sin que un error de disco tumbe la descarga."""
        try:
            os.remove(path)
        except OSError:
            pass

    def _keep_as_fallback(self, merged: str, keep: str,
                          current: str | None, got_h: int) -> bool:
        """Aparta un MP4 más bajo de lo pedido como último recurso.

        Devuelve True si `keep` queda siendo el mejor de los que hay, y en
        ese caso `merged` ya no existe (se movió). Si no mejora a lo que ya
        había, se borra el nuevo y se queda el anterior: perder el 480p
        porque después salió un 360p sería tan malo como no mirar la altura.

        Un nombre POR ORIGEN y no uno compartido: con un nombre fijo el
        segundo resultado corto pisaba al primero.
        """
        try:
            os.replace(merged, keep)
        except OSError:
            self._discard(merged)
            return False
        if current is not None and self._file_height(keep) <= self._file_height(current):
            self._discard(keep)
            return False
        if current is not None:
            self._discard(current)
        return True

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
            dims = (self._video_dimensions(path)
                    if content_type == "video/mp4" else None)
            if dims:
                self.send_header("X-Video-Width", str(dims[0]))
                self.send_header("X-Video-Height", str(dims[1]))
            self._cors_headers()
            self.end_headers()
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            suffix = f", {dims[0]}x{dims[1]}" if dims else ""
            logger.info(f"Sirviendo fichero {os.path.basename(path)}: "
                        f"{video_id} ({file_size} bytes{suffix})")
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
                # Aunque quepan en número, pueden no caber en peso: se borra lo
                # más viejo hasta que el total entre en el presupuesto. Con el
                # tope de ficheros solo, 30 vídeos de 1 GB llenaban el disco del
                # host y lo dejaban sin responder.
                total = sum(os.path.getsize(p) for p, _ in files)
                if total <= self.DOWNLOAD_CACHE_MAX_TOTAL_BYTES:
                    return
                files.sort(key=lambda x: x[1])
                for path, _ in files:
                    try:
                        total -= os.path.getsize(path)
                        os.remove(path)
                    except OSError:
                        continue
                    if total <= self.DOWNLOAD_CACHE_MAX_TOTAL_BYTES:
                        break
                return
            files.sort(key=lambda x: x[1])
            for path, _ in files[:len(files) - self.DOWNLOAD_CACHE_MAX_FILES]:
                try: os.remove(path)
                except Exception: pass
        except Exception:
            pass

    def _video_format_selector(self, quality: int) -> str:
        """Delegado: el selector vive en download_engine para que la vía
        directa y la del proxy no puedan divergir."""
        return video_format_selector(quality)

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

    def _video_size_estimate(
        self, video_id: str, quality: int, deadline_s: float | None = None
    ) -> int | None:
        """Peso estimado del MP4 que se pediría, sin descargar nada.

        Una consulta de metadatos (yt-dlp -J) que se resuelve en 6-20 s: hay
        que encontrar un cliente que funcione primero, y si el primero está
        bloqueado prueba el siguiente. Sirve para una cosa: decidir ANTES de
        empezar si el vídeo cabe en el tope. Sin esto, una peli que no cabe se
        descarga entera, se mergea y luego la app la rechaza al final: minutos
        de servidor y disco tirados.

        Devuelve None cuando no se puede saber (todos los clientes fallan, el
        vídeo no expone el tamaño). None nunca bloquea la descarga: el peso
        real se comprueba después del merge, que es la red de seguridad.
        """
        fmt = self._video_format_selector(quality)
        url = f"https://youtube.com/watch?v={video_id}"
        # El deadline lo puede fijar quien llama: /api/ready va justo y quiere
        # responder rápido para que la app decida el host a tiempo; la descarga
        # se puede permitir más margen porque va a tardar igual.
        if deadline_s is None:
            deadline_s = float(os.environ.get("SIZE_PROBE_DEADLINE", "30"))
        # Ningún cliente se puede comer todo el presupuesto: si uno se cuelga
        # hasta el final, los demás no llegan a probarse nunca y el sondeo
        # devuelve "no se puede" sin haberlo comprobado.
        per_client = float(os.environ.get("SIZE_PROBE_CLIENT_TIMEOUT_S", "8"))
        deadline = deadline_s
        start = time.monotonic()

        # Caché: el cliente pregunta antes por /api/ready y, si el host dice que
        # sí sirve, la descarga reutiliza el tamaño que se acaba de calcular en
        # vez de repetir la extracción.
        key = (video_id, quality)
        cached = self._SIZE_ESTIMATE_CACHE.get(key)
        if cached:
            age, value = cached
            # Un None se cachea menos tiempo: suele ser un bloqueo temporal de
            # YouTube que puede levantarse enseguida, y no queremos seguir
            # creyéndolo durante minutos.
            ttl = self.SIZE_ESTIMATE_TTL_S if value else self.SIZE_ESTIMATE_TTL_FAIL_S
            if time.time() - age < ttl:
                return value

        for client in ordered_video_clients():
            if time.monotonic() - start > deadline:
                break
            cmd = _base_cmd(client) + ["-J", "--no-playlist", "-f", fmt, url]
            try:
                proc = subprocess.run(
                    cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    timeout=max(2.0, min(per_client, deadline - (time.monotonic() - start))),
                )
            except subprocess.TimeoutExpired:
                break
            except Exception as e:
                logger.debug(f"Estimación de tamaño falló con {client}: {e}")
                continue
            if proc.returncode != 0:
                continue
            try:
                info = json.loads((proc.stdout or b"").decode(errors="replace"))
            except (ValueError, AttributeError):
                continue
            # Con un selector de dos pistas, `requested_formats` trae el vídeo y
            # el audio por separado y el peso final es la suma de los dos. Con un
            # formato único, el peso está en el propio objeto.
            total = 0
            for f in (info.get("requested_formats") or [info]):
                try:
                    total += int(f.get("filesize") or f.get("filesize_approx") or 0)
                except (TypeError, ValueError):
                    continue
            if total > 0:
                logger.info(f"Tamaño estimado para {video_id} ({quality}p): "
                            f"{total} bytes")
                self._SIZE_ESTIMATE_CACHE[key] = (time.time(), total)
                return total
        logger.info(f"Sin tamaño estimado para {video_id}; se procede sin comprobar")
        self._SIZE_ESTIMATE_CACHE[key] = (time.time(), None)
        return None

    def _enforce_video_size(self, path: str, video_id: str, quality: int) -> bool:
        """Tope de tamano sobre el MP4 ya mergeado. True si se puede servir.

        La estimacion previa es una prediccion y ademas, con la IP bloqueada,
        devuelve None: el chequeo de antes no llega a decides nada. Este es el
        unico sitio donde el tamano es real, asi que tiene que estar en TODAS
        las rutas. Antes solo estaba en la directa, y como la directa esta
        bloqueada, el limite de 1 GB no se aplicaba a ninguna descarga.
        """
        real_size = os.path.getsize(path)
        if real_size <= self.VIDEO_MAX_BYTES:
            return True
        try:
            os.remove(path)
        except OSError:
            pass
        self._reject_too_big(video_id, quality, real_size)
        return False

    def _reject_too_big(self, video_id: str, quality: int,
                        size: int | None) -> None:
        """Corta en seco lo que no cabe, sin haber descargado un solo byte.

        `size` es None cuando el corte lo ha hecho el propio yt-dlp con
        `--max-filesize`: se sabe que no cabe, pero no cuanto ocupa, y
        inventar un tamaño en el mensaje seria mentira.
        """
        limit_mb = self.VIDEO_MAX_BYTES // (1024 * 1024)
        logger.info(f"Rechazado {video_id} ({quality}p): "
                    f"{size} bytes frente al limite {self.VIDEO_MAX_BYTES}")
        detail = (f"ocupa unos {size // (1024 * 1024)} MB y el límite es "
                  f"{limit_mb} MB. Prueba con una calidad más baja."
                  if size else
                  f"supera el límite de {limit_mb} MB. Prueba con una calidad "
                  "más baja.")
        self._json(413, {
            "error": "El vídeo supera el límite de tamaño del servidor",
            "detail": detail,
            "videoId": video_id,
            "quality": quality,
            "sizeBytes": size,
            "limitBytes": self.VIDEO_MAX_BYTES,
        })

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

        # La caché se valida contra la calidad pedida ANTES de servirla. Sin
        # esto, un 360p que se guardara con la petición de 1080p se servía para
        # siempre: el nombre del fichero lleva la calidad (safe_id_q1080.mp4)
        # pero no la altura, así que la entrada parecía correcta y era mentira.
        # Es justo lo que dejó a Railway sirviendo 360p durante días.
        if os.path.isfile(download_path) and os.path.getsize(download_path) > 1024:
            cached_h = self._file_height(download_path)
            if not cached_h or cached_h >= quality:
                if self._serve_file(download_path, "video/mp4", video_id):
                    return
            else:
                logger.info(
                    "Cache de %s a %dp no cumple los %dp pedidos; se descarta "
                    "y se vuelve a descargar", video_id, cached_h, quality)
                self._discard(download_path)

        # Antes de gastar una descarga entera, mirar cuánto va a ocupar. Es la
        # diferencia entre "no se puede" en 2 segundos y "no se puede" después de
        # descargar y mergear una peli entera.
        estimated = self._video_size_estimate(video_id, quality)
        if estimated and estimated > self.VIDEO_MAX_BYTES:
            self._reject_too_big(video_id, quality, estimated)
            return

        fmt = self._video_format_selector(quality)
        url = f"https://youtube.com/watch?v={video_id}"
        timeout_s = int(os.environ.get("VIDEO_DOWNLOAD_TIMEOUT", "420"))
        last_err = ""
        # Último recurso, COMPARTIDO por las dos rutas: el mejor MP4 que se haya
        # conseguido aunque sea más bajo que lo pedido. Existe para que insistir
        # por la calidad NO pueda acabar en un 502 donde antes había un 200
        # (aunque fuera de 360p).
        fallback_path: str | None = None

        # ── 1. Clients directos, con el mismo deadline que el audio ──
        # 25s, no 8s. Medido el 2026-09-26: una descarga que SÍ funciona tarda
        # 3-6s solo en el handshake (extraer formatos + resolver el player), y
        # el deadline se comprueba ANTES de arrancar cada client. Con 8s, si los
        # dos primeros tardaban 4s cada uno, el tercero que sí iba a funcionar
        # ni se intentaba. Ahora caben 4-5 intentos completos, que es lo que
        # hace falta para llegar al client bueno cuando los anteriores fallan.
        direct_deadline = float(os.environ.get("DIRECT_CLIENTS_DEADLINE", "25"))
        if not direct_path_available():
            direct_deadline = 0.0
            # Igual que en el audio: el motivo del salto se anota para que el
            # 502 final no acuse a un recurso que no es el culpable.
            last_err = DIRECT_PATH_COOLDOWN_REASON
        _start = time.monotonic()

        # Techo REAL por client, no solo el deadline de reloj entre clients.
        # El deadline anterior se comprobaba antes de arrancar el siguiente
        # client, así que un único client colgado se comía los
        # VIDEO_DOWNLOAD_TIMEOUT (420 s) enteros sin que el deadline pudiera
        # actuar. Medido el 2026-09-29: un 1080p se quedó 200 s con 0 bytes
        # y sin una sola línea de log, porque el cliente no ve ni un byte
        # hasta tener el MP4 entero. Con este tope, lo que no hayayuk nada
        # en 90 s se corta y se pasa al siguiente, con el error anotado.
        per_client_timeout = float(os.environ.get("VIDEO_PER_CLIENT_TIMEOUT", "90"))

        for client in ordered_video_clients():
            if time.monotonic() - _start > direct_deadline:
                logger.info(f"Deadline de clients directos alcanzado para vídeo "
                            f"{video_id}, pasando a proxy")
                break
            workdir = self._video_workdir("mp3vid_")
            try:
                cmd = _base_cmd(client) + [
                    "--newline",
                    "-f", fmt,
                    "--merge-output-format", "mp4",
                    "--no-playlist", "--no-part",
                    f"--max-filesize={self.VIDEO_MAX_BYTES}",
                    "-o", os.path.join(workdir, "v.%(ext)s"),
                    url,
                ]
                proc = subprocess.run(
                    cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    timeout=min(timeout_s, per_client_timeout),
                )
                merged = self._merged_video_in(workdir)
                if proc.returncode == 0 and merged:
                    # Tope real: la estimación de antes es una predicción y el
                    # tamaño de verdad puede salir mayor. Si aun así se pasa, el
                    # fichero se borra en vez de quedarse ocupando disco.
                    if not self._enforce_video_size(merged, video_id, quality):
                        return
                    dims = self._video_dimensions(merged)
                    got_h = dims[1] if dims else 0
                    if not got_h or got_h >= quality:
                        os.replace(merged, download_path)
                        self._cleanup_download_cache()
                        record_success(client)
                        # El breaker de la vía directa se cerraba solo en la
                        # ruta de audio. En la de vídeo no había ninguna
                        # llamada a record_direct_success(), así que un vídeo
                        # descargado a la calidad correcta por la vía directa
                        # dejaba el breaker abierto 300 s de todos modos y
                        # durante esa ventana no quedaba ninguna vía: con el
                        # proxy además difícil de encontrar, el resultado era
                        # el 502 medido el 2026-09-29.
                        record_direct_success()
                        self._serve_file(download_path, "video/mp4", video_id)
                        return
                    # Mismo criterio que en la ruta del proxy, y hace falta aquí
                    # por un motivo medido el 2026-09-27: la vía directa de
                    # Railway volvió a funcionar (el PO provider por script ya
                    # arranca, /api/ready pasa a ok:true), pero SIN proxy solo
                    # consigue el muxed de 360p. Antes esta rama servía ese
                    # 360p tal cual en 5-7 s y cortocircuitaba la escalera del
                    # proxy, que es la que sí llega a 1080p.
                    keep = f"{download_path}.bajo_directo{client}"
                    if self._keep_as_fallback(merged, keep, fallback_path, got_h):
                        fallback_path = keep
                    logger.info(
                        "Vídeo %s: client directo %s devolvió %dp, se buscaban "
                        "%dp; se sigue buscando", video_id, client, got_h, quality)
                    # No se corta el bucle: otro client directo puede exponer la
                    # escalera alta, y el deadline de arriba lo acota igual.
                    continue
                last_err = (proc.stderr or b"").decode(errors="replace")[-300:]
                if _is_too_big_error(last_err):
                    self._reject_too_big(video_id, quality, None)
                    return
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
        # Un vídeo que no está disponible no dice nada de nuestra IP: si eso
        # abriera el breaker, serían 5 minutos con todos los clientes saltados
        # y los vídeos que sí funcionan caerían también.
        if not is_video_level_error(last_err):
            record_direct_failure()
        blocked: set = set()
        max_proxy_attempts = int(os.environ.get("MAX_PROXY_ATTEMPTS", "3"))
        for _attempt in range(max_proxy_attempts):
            proxy = _find_working_proxy(video_id, blocked=blocked)
            if not proxy:
                break
            workdir = self._video_workdir("mp3vid_px_")
            got_short = False
            # Reloj del INTENTO completo (los 3 clients contra este proxy).
            # Antes cada client podía comerse los VIDEO_DOWNLOAD_TIMEOUT
            # (420 s) por su cuenta y un solo proxy colgado se llevaba 21
            # minutos sin que nadie más pudiera tomar el relevo. Este intento
            # tiene su presupuesto, y cada client se lleva la parte que queda.
            attempt_deadline = time.monotonic() + per_client_timeout * len(_PROXY_CLIENTS)
            try:
                logger.info(f"Vídeo {video_id} ({quality}p) por proxy {proxy} "
                            f"— intento {_attempt + 1}/{max_proxy_attempts}")
                # Escalera de clients del proxy, UNO POR COMANDO. mweb va
                # PRIMERO: es el que expone la escalera completa y con token
                # sirve hasta 720p/1080p incluso desde datacenter. Detrás quedan
                # web_embedded (escalera sin garantía de token) y android (muxed
                # itag 18 de 640x360, sin token, el suelo que siempre cierra la
                # escalera para no devolver 502).
                for clients in _PROXY_CLIENTS:
                    restante = attempt_deadline - time.monotonic()
                    if restante <= 5:
                        last_err = (f"el proxy {proxy} se agotó el tiempo del "
                                    f"intento sin devolver el vídeo")
                        logger.warning(f"Vídeo {video_id}: {last_err}")
                        break
                    cmd = _proxy_cmd_video(video_id, proxy, quality, workdir,
                                           clients=clients,
                                           max_bytes=self.VIDEO_MAX_BYTES)
                    proc = subprocess.run(
                        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                        timeout=min(timeout_s, max(5.0, restante)),
                    )
                    merged = self._merged_video_in(workdir)
                    if merged:
                        if not self._enforce_video_size(merged, video_id, quality):
                            return
                        dims = self._video_dimensions(merged)
                        got_h = dims[1] if dims else 0
                        if not got_h or got_h >= quality:
                            os.replace(merged, download_path)
                            self._cleanup_download_cache()
                            self._serve_file(download_path, "video/mp4", video_id)
                            return
                        # ── EL FIX DEL TECHO DE 360p ──
                        # Salió un fichero, pero por debajo de lo pedido. Antes
                        # se servía tal cual y por eso la app recibía 360p casi
                        # siempre. Medido el 2026-09-27 con un SOCKS5 gratuito
                        # y el MISMO comando, tres descargas seguidas del mismo
                        # vídeo dieron 360p, 360p y 1920x1080: la escalera
                        # alta está disponible pero la extracción la degrada de
                        # forma intermitente, y no depende del proxy (el mismo
                        # proxy dio las tres). Servir el primer resultado era
                        # exactamente dejar la lotería sin repetir.
                        #
                        # Así que un resultado corto NO se sirve mientras queden
                        # intentos: se aparta como último recurso y se vuelve a
                        # intentar. El coste es el mismo que antes (los intentos
                        # ya se contaban) pero ahora apuntan a la calidad pedida.
                        got_short = True
                        keep = f"{download_path}.bajo{_attempt}"
                        if self._keep_as_fallback(merged, keep, fallback_path, got_h):
                            fallback_path = keep
                        logger.info(
                            "Vídeo %s: %s devolvió %dp, se buscaban %dp; reintentando",
                            video_id, clients, got_h, quality)
                        # Otro client del MISMO proxy no mejora: el problema es
                        # que la extracción sale degradada, no el client. Se
                        # corta la escalera y se gasta otro intento (que puede
                        # ser otro proxy) en vez de quemarlo en 20 s más.
                        break
                    last_err = (proc.stderr or b"").decode(errors="replace")[-300:]
                    if _is_too_big_error(last_err):
                        self._reject_too_big(video_id, quality, None)
                        return
                    # Sin `break`: ANTES solo se reintentaba con android si el
                    # fallo era de auth, pero en Railway (datacenter) el fallo
                    # típico de web_embedded es "Requested format is not
                    # available" (extrae 0 formatos) — y ese caso cortaba la
                    # escalera dejando sin probar el suelo android (itag 18),
                    # que es el ÚNICO client que funciona sin token en esta IP
                    # (es el mismo que hace funcionar el MP3). Medido el 27-09:
                    # quality=360 daba 502 en 84 s por esto. Coste: cuando el
                    # vídeo de verdad no existe, android añade ~60-90 s antes
                    # del 502, pero deja de bloquear los vídeos que sí se pueden.
                    logger.info(
                        "Vídeo %s: %s falló (%.120s), siguiente client de la escalera",
                        video_id, clients, last_err.replace("\n", " "),
                    )
                # Un proxy que entregó un resultado CORTO no se bloquea: medido,
                # el proxy no es lo que falla (el mismo dio 1080p una de cada
                # tres veces), así que dejarlo disponible permite que un
                # reintento lo reutilice y no se pague otra búsqueda de 60 s.
                # Un proxy que no entregó nada (falló entero) sí se aparta.
                if not got_short:
                    blocked.add(proxy)
                logger.warning(f"Vídeo no servido por proxy {proxy}: {last_err}")
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

        if fallback_path and os.path.isfile(fallback_path):
            # Se agotaron los intentos sin llegar a la calidad pedida. Servir
            # lo mejor que se tenga es mejor que un 502, y la app lo etiqueta
            # con la altura real porque la mandamos en X-Video-Height.
            os.replace(fallback_path, download_path)
            logger.info(f"Vídeo {video_id}: se sirve {download_path} a la altura "
                        f"real por agotar los intentos a {quality}p")
            self._serve_file(download_path, "video/mp4", video_id)
            return

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
            # El motivo del salto se anota como último error. Sin esto,
            # `last_err` se queda en "" y el mensaje final acaba culpando a
            # Invidious de un fallo que no es suyo: `is_bot_challenge("")` es
            # False, así que siempre ganaba la rama de "Invidious fallback: no
            # audio URL available" (server.py:1390) en vez de la que dice que
            # el bloqueo es de IP. El usuario recibía un 502 que no explicaba
            # nada de lo que pasaba.
            last_err = DIRECT_PATH_COOLDOWN_REASON
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

                first = _read_first_byte(p2.stdout, _first_byte_timeout_s())
                if not first:
                    stderr = b""
                    if p1.stderr: stderr += (p1.stderr.read() or b"")
                    if p2.stderr: stderr += (p2.stderr.read() or b"")
                    last_err = (stderr.decode(errors="replace")[:300]
                                or f"sin audio en {_first_byte_timeout_s():.0f}s")
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
        # Misma razón que en vídeo: si el fallo es del vídeo y no de la IP,
        # abrir el breaker tumba también las descargas que sí funcionarían.
        if not is_video_level_error(last_err):
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
        # Reloj de toda la fase de proxy. Cada intento puede costar hasta
        # PROXY_SEARCH_BUDGET_S de búsqueda más PROXY_FIRST_BYTE_TIMEOUT de
        # espera, así que 3 intentos son hasta 4,5 min: más que el corte de
        # 5 min de Railway, que se llevaría la respuesta por el camino y
        # dejaría al usuario sin saber qué pasó. Con este tope, un request que
        # no va a salir devuelve 502 antes de que lo corten por fuera, y uno
        # que sí sale no se ve afectado porque para entonces ya ha servido.
        proxy_phase_deadline = time.monotonic() + float(
            os.environ.get("PROXY_PHASE_BUDGET_S", "180"))
        for _attempt in range(max_proxy_attempts):
            if time.monotonic() > proxy_phase_deadline:
                logger.warning(
                    f"Presupuesto de la fase de proxy agotado para {video_id} "
                    f"tras {_attempt} intento(s)")
                break
            proxy = _find_working_proxy(video_id, blocked=_blocked_proxies)
            if not proxy:
                logger.warning(f"Proxy falló para {video_id} (no se encontró proxy), "
                               f"intentando Invidious fallback...")
                self._proxy_download_invidious(video_id, title, reason=last_err)
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
        self._proxy_download_invidious(video_id, title, reason=last_err)

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
            # SUBIDO de 12 s a 30 s, y la suposición anterior era falsa. Aquí
            # estaba escrito «un proxy sano entrega el primer bloque de audio
            # en 1-3s», y no: ese 1-3 s era el `--get-url` del SONDEO, que no
            # descarga nada. La descarga tiene que volver a extraer los
            # formatos y además bajar el primer bloque, y por un SOCKS5
            # gratuito eso son más segundos.
            #
            # Medido el 2026-09-30 con 8 proxies que SÍ funcionan, de los
            # que devuelve el sondeo concurrente:
            #
            #   primer byte: min 11,7 s · mediana 13,6 s · max 29,6 s
            #   por encima de 12 s: 7 de 8
            #
            # O sea que el tope de 12 s mataba a casi todos los proxies
            # buenos, y el síntoma era exactamente el 502 que se lleva días
            # buscando: «Proxy download timeout (sin audio en 12s)» seguido de
            # Invidious, que también está muerto. 30 s deja pasar el máximo
            # medido y sigue poniendo un techo al intento.
            first_byte_timeout = _first_byte_timeout_s()
            first = _read_first_byte(p2.stdout, first_byte_timeout)
            if not first:
                logger.warning(
                    f"Proxy download timeout (sin audio en {first_byte_timeout:.0f}s) "
                    f"para {video_id}")
                try: p1.terminate()
                except Exception: pass
                try: p2.terminate()
                except Exception: pass
                # NO escribimos 502 aqui: devolvemos False para que el
                # llamador reintente con otro proxy. Solo al agotar los
                # intentos se reporta error al cliente.
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
    def _proxy_download_invidious(self, video_id: str, title: str, reason: str = "") -> None:
        """Descarga vía Invidious cuando yt-dlp falla.

        `reason` es el error real de la vía directa. Sin él, el mensaje que
        ve el usuario culpa a Invidious de un fallo que no es suyo: si lo que
        hubo fue el challenge de bot de YouTube, Invidious es el último
        recurso que se intentó y no la causa.
        """
        import subprocess as _sp

        audio_url = invidious_get_audio_url(video_id)
        if not audio_url:
            # El mensaje tiene que decir POR QUÉ no se pudo descargar. Antes
            # solo había dos ramas y la de "Invidious" se llevaba el fallo
            # aunque Invidious fuese inocente: cuando el breaker de la vía
            # directa estaba abierto, `reason` llegaba vacío (nadie había
            # intentado nada todavía), así que is_bot_challenge("") era False
            # y el 502 siempre decía "Invidious fallback: no audio URL
            # available". Medido el 2026-09-29.
            if is_bot_challenge(reason):
                mensaje = ("YouTube bloquea este servidor por IP de datacenter "
                           "(challenge de bot). El vídeo no es el problema")
            elif is_video_level_error(reason):
                mensaje = reason[:200] or "El vídeo no está disponible"
            else:
                mensaje = ("No se pudo descargar: no se encontró ninguna vía "
                           "libre (ni acceso directo ni proxy)")
            cuerpo = {"error": mensaje}
            if reason:
                cuerpo["detail"] = reason[:300]
            try:
                self._json(502, cuerpo)
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

                # Leer primer chunk para validar que hay audio, CON RELOJ: a
                # pelo era un bloqueo indefinido si el proceso se colgaba.
                first = _read_first_byte(p2.stdout, _first_byte_timeout_s())
                if not first:
                    stderr = b""
                    if p1.stderr:
                        stderr += (p1.stderr.read() or b"")
                    if p2.stderr:
                        stderr += (p2.stderr.read() or b"")
                    last_err = (stderr.decode(errors="replace")[:300]
                                or f"sin audio en {_first_byte_timeout_s():.0f}s")
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
        # Misma razón que en las otras dos rutas: un fallo del vídeo (no
        # existe, es privado, se borró) no dice nada de nuestra IP, y abrir el
        # breaker entera por eso tumba también las descargas que sí
        # funcionarían durante DIRECT_PATH_DISABLED_S.
        if not is_video_level_error(last_err):
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

            # El primer byte se espera CON RELOJ. Aquí había un
            # `p2.stdout.read(8192)` a pelo, que es un bloqueo indefinido: un
            # proxy que resuelve `--get-url` y luego se cuelga al descargar
            # dejaba este request colgado para siempre, sin log y sin
            # respuesta, y el preview es justo lo que la app llama al abrir
            # una canción. El mismo techo que usa la descarga (30 s, medido
            # contra los 8 proxies que sí funcionan) va aquí también.
            preview_first_byte = _first_byte_timeout_s()
            first = _read_first_byte(p2.stdout, preview_first_byte)
            if not first:
                logger.warning(
                    f"Proxy preview sin audio en {preview_first_byte:.0f}s "
                    f"para {video_id}")
                try: p1.terminate()
                except Exception: pass
                try: p2.terminate()
                except Exception: pass
                try:
                    self._json(504, {
                        "error": "El proxy no entregó el audio a tiempo",
                        "videoId": video_id,
                    })
                except Exception:
                    pass
                return
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
