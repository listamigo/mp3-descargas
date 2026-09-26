from __future__ import annotations

import json
import logging
import os
import random
import re
import socket
import subprocess
import threading
import time
import urllib.request
import urllib.error
from pathlib import Path
from typing import Callable, List, Optional

logger = logging.getLogger("mp3downloader.engine")

from models.song import DownloadStatus, DownloadTask, Song
from utils.helpers import sanitize_filename

YTDLP_TIMEOUT = 60

# ═══════════════════════════════════════════════════════════════
# Invidious fallback — cuando yt-dlp falla por bot-detection
# ────────────────────────────────────────────────────────────────
# Instancias verificadas vivas (2026-08-28). El orden importa:
# las que funcionan van primero para minimizar latencia.
INVIDIOUS_INSTANCES = [
    "https://invidious.protokolla.fi",
    "https://invidious.projectsegfau.lt",
    "https://invidious.nerdvpn.de",
    "https://inv.nadeko.net",
    "https://yewtu.be",
    # Fallbacks no verificados (pueden estar muertos)
    "https://inv.zoomerville.com",
    "https://invidious.slipfox.xyz",
    "https://invidious.flokinet.to",
    "https://iv.ggtyler.dev",
]

# Cuántas instancias sondar y con qué timeout. El sondeo era 5s por
# instancia sobre las 9 de la lista: hasta 45s solo en decidir dónde
# pedir el audio, y después otros 15s por instancia en la resolución. Este
# es el ÚLTIMO recurso de la cadena, así que no puede gastar minutos.
# Solo se sondean las primeras INVIDIOUS_PROBE_INSTANCES; si ninguna vive,
# se responde rápido y el cliente pasa a su siguiente motor.
INVIDIOUS_PROBE_INSTANCES = int(os.environ.get("INVIDIOUS_PROBE_INSTANCES", "3"))
INVIDIOUS_PROBE_TIMEOUT = int(os.environ.get("INVIDIOUS_PROBE_TIMEOUT", "3"))
INVIDIOUS_VIDEO_TIMEOUT = int(os.environ.get("INVIDIOUS_VIDEO_TIMEOUT", "10"))

_invidious_active = None  # instance that worked last
_invidious_lock = threading.Lock()

# ═══════════════════════════════════════════════════════════════
# User-Agent rotation — YouTube bloquea UAs estáticos de bots
# ────────────────────────────────────────────────────────────────
USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_1) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Safari/605.1.15",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
]

# ═══════════════════════════════════════════════════════════════
# Proxy residencial (opcional) — evita IPs de datacenter
# ────────────────────────────────────────────────────────────────
# Set RESIDENTIAL_PROXY env var (e.g. "socks5://user:pass@host:port")
# to route yt-dlp through a residential proxy.
RESIDENTIAL_PROXY = os.environ.get("RESIDENTIAL_PROXY", "")

# Thread-safe TTL cache for search results so repeated queries don't hit
# yt-dlp/YouTube on every request (cheaper + lowers bot-challenge risk).
SEARCH_CACHE_TTL = int(os.environ.get("SEARCH_CACHE_TTL", "600"))
_search_cache_lock = threading.Lock()
_search_cache: dict = {}


def _search_cache_get(key):
    with _search_cache_lock:
        item = _search_cache.get(key)
        if not item:
            return None
        ts, value = item
        if time.time() - ts > SEARCH_CACHE_TTL:
            _search_cache.pop(key, None)
            return None
        return value


def _search_cache_put(key, value):
    with _search_cache_lock:
        _search_cache[key] = (time.time(), value)


# Ruta del archivo de cookies — configurable via env var
COOKIES_FILE = os.environ.get(
    "COOKIES_FILE",
    os.path.expanduser("~/.mp3downloader/cookies/cookies.txt")
)

# Order matters: clients that bypass YouTube's bot/login challenge are
# tried first so downloads keep working even when cookies are missing or
# expired. With valid cookies, `android` is the most reliable client to
# avoid "Sign in to confirm you're not a bot" (it served pages without the
# bot challenge in live tests). `tv_embedded`/`tv` rarely trigger it too,
# so they follow. Order = speed (reduces how many clients fail before one
# works, which on datacenter IPs saved ~100s per request).
PLAYER_CLIENTS = [
    "android",
    "tv_embedded",
    "tv",
    "ios",
    "mweb",
    "web",
    "android_vr,web",
]

# Optional Proof-of-Origin token (env var YT_PO_TOKEN) in yt-dlp syntax
# "client+token" (e.g. "web+XXXX"). Strongly recommended to avoid bot
# challenges without depending on a session cookie that can expire.
PO_TOKEN = os.environ.get("YT_PO_TOKEN")

# ═══════════════════════════════════════════════════════════════
# Reintentos INTERNOS de yt-dlp (acotados)
# ────────────────────────────────────────────────────────────────
# Por defecto yt-dlp usa --extractor-retries 3 y --retries 10, con
# backoff exponencial y --socket-timeout 20. Cuando YouTube devuelve el
# reto de bot, UN solo comando se pasa 20-30 s reintentando internamente
# antes de devolver el error.
#
# Eso es tiempo duplicado: el engine ya recorre 7 player clients y luego
# hasta 4 proxies distintos, y cada uno de esos intentos arrastra su propio
# reintento interno. Por eso el coste real era de minutos. Se acota el
# reintento interno a 1 y la cadena de respaldo se encarga del resto.
YTDLP_EXTRACTOR_RETRIES = int(os.environ.get("YTDLP_EXTRACTOR_RETRIES", "1"))
YTDLP_RETRIES = int(os.environ.get("YTDLP_RETRIES", "3"))
YTDLP_SOCKET_TIMEOUT = int(os.environ.get("YTDLP_SOCKET_TIMEOUT", "20"))

# Cuánto esperar un proxy para que responda antes de pagarlo con yt-dlp.
# Un proxy que resuelve --get-url en ~3s es el caso bueno; uno que tarda
# más que esto no va a improve y solo consume el deadline del request.
PROXY_PROBE_TIMEOUT = int(os.environ.get("PROXY_PROBE_TIMEOUT", "6"))

# ═══════════════════════════════════════════════════════════════
# Circuit breaker por player client
# ────────────────────────────────────────────────────────────────
# YouTube reta los clientes de forma intermitente. Para no martillar un
# client que acaba de fallar, los que fallaron hace poco van AL FINAL de
# la lista (cooldown corto). PERO la lista NUNCA queda vacía: si todos
# fallaron recientemente, se prueban igual. Un breaker "abierto" que
# bloquee todos los clients durante minutos rompía preview Y descarga
# (un solo mix fallido dejaba el server muerto). Por eso el cooldown es
# corto y nunca permanente.
_CB_COOLDOWN_SECONDS = int(os.environ.get("CB_COOLDOWN_SECONDS", "60"))


class _ClientHealth:
    def __init__(self):
        self.lock = threading.Lock()
        self.failures = 0
        self.successes = 0
        self.last_failure = 0.0


_client_health = {c: _ClientHealth() for c in PLAYER_CLIENTS}


def _seconds_since_failure(client: str) -> float:
    h = _client_health.get(client)
    if not h:
        return 1e9
    with h.lock:
        return time.time() - h.last_failure


def record_success(client: str) -> None:
    h = _client_health.get(client)
    if not h:
        return
    with h.lock:
        h.failures = 0
        h.successes += 1
        h.last_failure = 0.0


def record_failure(client: str) -> None:
    h = _client_health.get(client)
    if not h:
        return
    with h.lock:
        h.failures += 1
        h.last_failure = time.time()


def ordered_clients():
    """Clients en orden; los que fallaron hace poco van al final.
    NUNCA devuelve lista vacía (eso rompería preview y descarga)."""
    fresh = [c for c in PLAYER_CLIENTS
             if _seconds_since_failure(c) >= _CB_COOLDOWN_SECONDS]
    if fresh:
        cool = [c for c in PLAYER_CLIENTS if c not in fresh]
        return fresh + cool
    return list(PLAYER_CLIENTS)


# Para VÍDEO el orden de clients NO puede ser el de audio, y la razón es
# concreta: la escalera de formatos completa solo aparece en algunos clients,
# y un client "degradado" tiene éxito igualmente, así que el bucle se queda
# con él y nunca llega al bueno.
#
# Medido el 2026-09-25 con ac7KhViaVqc (tráiler de Sintel, 54 s):
#   mweb              -> 8 formatos: 1920x818, 1280x546, 640x272, 256x110, audio
#   android           -> 1 formato : 640x272  (el muxed de 360p, y nada más)
#   android_vr        -> 1 formato : 640x272
#   web / tv / ios    -> 0 formatos (bloqueados desde IP de datacenter)
#
# Con `android` primero, pedir 720p devolvía 360p sin intentar nada más. Por eso
# mweb va el primero aquí aunque en la lista de audio llegue en quinto lugar.
VIDEO_CLIENTS = [
    "mweb",
    "android",
    "tv_embedded",
    "tv",
    "ios",
    "web",
    "android_vr,web",
]


def ordered_video_clients() -> list[str]:
    """Clients para descarga de vídeo, con el mismo criterio de cooldown.

    Nunca devuelve lista vacía, por el mismo motivo que [ordered_clients].
    """
    fresh = [c for c in VIDEO_CLIENTS
             if _seconds_since_failure(c) >= _CB_COOLDOWN_SECONDS]
    if fresh:
        cool = [c for c in VIDEO_CLIENTS if c not in fresh]
        return fresh + cool
    return list(VIDEO_CLIENTS)


def _player_client_arg(clients: list[str]) -> str:
    """Valor de `--extractor-args youtube:player_client=...` con la lista completa.

    Fix 2026-09-26: la ruta del proxy forzaba `player_client=android` en los
    cuatro sitios. Dos consecuencias medidas, no teóricas:

    - `android` expone UN formato de vídeo, el muxed de 640x272 (medido el
      2026-09-25 con ac7KhViaVqc: mweb daba 8 formatos hasta 1920x818, android
      daba 1). Con android forzado, pedir 1080p devolvía 360p sin avisar.
    - Los proxies se sondeaban con `android`, así que uno que solo sirviera con
      otro client se descartaba como muerto y se contaban como "7 probados".

    yt-dlp recorre la lista internamente, así que una sola invocación prueba los
    siete en orden; no hace falta un bucle externo por client.
    """
    return "youtube:player_client=" + ",".join(clients)


def client_state(client: str) -> dict:
    h = _client_health.get(client)
    if not h:
        return {"client": client, "status": "unknown"}
    with h.lock:
        # NOTA: NO llamar a _seconds_since_failure() aquí porque ya tenemos
        # h.lock adquirido y _seconds_since_failure() también lo adquiere,
        # causando un deadlock (threading.Lock NO es reentrante).
        since_failure = time.time() - h.last_failure
        if since_failure < _CB_COOLDOWN_SECONDS:
            status = "cooldown"
        elif h.failures >= 3:
            status = "half-open"
        else:
            status = "closed"
        return {"client": client, "status": status, "failures": h.failures}


def get_client_health() -> list:
    return [client_state(c) for c in PLAYER_CLIENTS]


# Señales de reto transitorio de YouTube: backoff exponencial antes de
# probar el siguiente client en vez de insistir de inmediato.
_TRANSIENT_HINTS = (
    "429", "rate limit", "too many requests", "retry after",
    "sign in to confirm", "bot", "confirm you",
    "not available in your country", "video unavailable",
    "private video", "unavailable video",
)

# Errores que indican bloqueo duro — no reintentar con el mismo client
_HARD_BAN_HINTS = (
    "sign in to confirm you're not a bot",
    "please sign in",
    "confirm your age",
    "content warning",
)


# ────────────────────────────────────────────────────────────────
# Breaker de la VÍA DIRECTA (yt-dlp sin proxy)
# ────────────────────────────────────────────────────────────────
# En una IP de datacenter (Render, Railway) YouTube responde "sign in to
# confirm you're not a bot" a los 7 player clients. El PO token NO lo
# arregla: el bloqueo es por IP, no por token. Antes de caer's al proxy
# SOCKS5 —la única vía que sí funciona desde ahí— el servidor gastaba
# DIRECT_CLIENTS_DEADLINE (25s) en intentos que ya sabía queiban a fallar.
#
# A diferencia del breaker por client (que nunca queda abierto para no
# romper preview/descarga), este solo OMITE la vía directa y NUNCA la vía
# proxy. Es de tiempo acotado y se auto-sana: al vencer la ventana se
# reintenta, de modo que un cambio en YouTube o un cambio de IP se
# recupera solo. Con IP residencial (PC de casa) los clients directos
# suelen funcionar, así que nunca se llega a abrir el breaker ahí.
# Una IP de datacenter o no lo es, y no cambia entre peticiones: si el
# primer request completo falla en la vía directa, los siguientes van a fallar
# igual. Por eso el umbral es 1 y no 3. Con 3, cada reinicio de contenedor
# (frecuente en planes gratuitos) cobraba 3 descargas completas de espera
# antes de que el breaker llegara a abrirse. Con 1, solo la primera.
DIRECT_PATH_MIN_FAILURES = int(os.environ.get("DIRECT_PATH_MIN_FAILURES", "1"))
DIRECT_PATH_DISABLED_S = int(os.environ.get("DIRECT_PATH_DISABLED_S", "300"))

_direct_lock = threading.Lock()
_direct_failures = 0
_direct_disabled_until = 0.0


def direct_path_available() -> bool:
    """False mientras la vía directa esté en cooldown; True tras la ventana."""
    with _direct_lock:
        return time.time() >= _direct_disabled_until


def record_direct_success() -> None:
    global _direct_failures, _direct_disabled_until
    with _direct_lock:
        _direct_failures = 0
        _direct_disabled_until = 0.0


def record_direct_failure() -> None:
    global _direct_failures, _direct_disabled_until
    with _direct_lock:
        _direct_failures += 1
        if _direct_failures >= DIRECT_PATH_MIN_FAILURES:
            _direct_disabled_until = time.time() + DIRECT_PATH_DISABLED_S


# Errores que NO son culpa de la IP del servidor: el vídeo no existe, es
# privado, se borró o no está disponible en la región. Antes de contar esto
# como fallo de la vía directa se abría el breaker entero (5 minutos sin
# probar ningún cliente) y con ello caían también los vídeos que sí se
# podían descargar. Que un vídeo no esté disponible no dice nada de si
# nuestra IP está bloqueada.
_VIDEO_LEVEL_ERRORS = (
    "this video is unavailable",
    "video unavailable",
    "video is not available",
    "this video is private",
    "private video",
    "removed by the uploader",
    "has not made this video available",
    "account associated with this video has been terminated",
    "not available in your country",
    "who has blocked it on copyright grounds",
    "requested format is not available",
    "requested format not available",
    "unable to extract",
    "no video formats found",
)


def is_video_level_error(text: str) -> bool:
    """True si el fallo es del vídeo y no de una IP bloqueada por YouTube.

    Importante: "Sign in to confirm you're not a bot" NO entra aquí. Eso sí
    es un bloqueo de IP y tiene que seguir abriendo el breaker.
    """
    lowered = (text or "").lower()
    return any(marker in lowered for marker in _VIDEO_LEVEL_ERRORS)


def is_bot_challenge(text: str) -> bool:
    """True si YouTube respondió con el challenge que pide sesión o cookies.

    Es el bloqueo típico de una IP de datacenter. No se arregla reintentando:
    hay que salir de esa IP, con un proxy residencial.
    """
    lowered = (text or "").lower()
    return any(marker in lowered for marker in (
        "sign in to confirm",
        "not a bot",
        "confirm you're not a bot",
        "confirm you’re not a bot",
        "use --cookies",
        "login required",
    ))
    lowered = (text or "").lower()
    return any(marker in lowered for marker in _VIDEO_LEVEL_ERRORS)


def direct_path_state() -> dict:
    with _direct_lock:
        remaining = max(0.0, _direct_disabled_until - time.time())
        return {
            "available": remaining <= 0.0,
            "failures": _direct_failures,
            "disabled_for_s": int(remaining),
        }

# Backoff exponencial con jitter para evitar thundering herd
_BACKOFF_BASE = 2.0  # segundos
_BACKOFF_MAX = 16.0  # tope
_backoff_counter = {}  # client -> consecutive failures


def _is_transient(stderr: str) -> bool:
    s = (stderr or "").lower()
    return any(hint in s for hint in _TRANSIENT_HINTS)


def _is_hard_ban(stderr: str) -> bool:
    s = (stderr or "").lower()
    return any(hint in s for hint in _HARD_BAN_HINTS)


def _get_backoff(client: str) -> float:
    """Backoff exponencial con jitter: 2s, 4s, 8s, 16s..."""
    count = _backoff_counter.get(client, 0)
    base = min(_BACKOFF_BASE * (2 ** count), _BACKOFF_MAX)
    jitter = random.uniform(0, base * 0.3)
    return base + jitter


def _record_backoff(client: str) -> None:
    _backoff_counter[client] = _backoff_counter.get(client, 0) + 1


def _clear_backoff(client: str) -> None:
    _backoff_counter.pop(client, None)


# ═══════════════════════════════════════════════════════════════
# Free SOCKS5 proxy fallback — cuando YouTube bloquea la IP
# ────────────────────────────────────────────────────────────────
_FREE_PROXY_CACHE_TTL = 300  # 5 min
_free_proxy_cache = {"proxies": [], "ts": 0.0}
_free_proxy_lock = threading.Lock()

# Cuántos candidatos de la lista gratuita se prueban por petición. Antes 20:
# la lista de GeoNode está llena de SOCKS5 muertos, así que 20 candidatos
# con 6s cada uno son 120s por intento y hasta 480s por descarga. Con el
# sondeo TCP previo (1s) 20 candidatos cuestan ~20s en el peor caso, y los
# que sí funcionan se recuerdan para el siguiente request.
FREE_PROXY_CANDIDATES = int(os.environ.get("FREE_PROXY_CANDIDATES", "20"))

# Sondeo TCP barato para descartar proxies muertos ANTES de pagarles una
# invocación de yt-dlp. Los proxies que rechazan la conexión mueren rápido,
# pero muchos aceptan el handshake y se cuelgan: sin este filtro cada uno
# costaba los PROXY_PROBE_TIMEOUT completos.
TCP_PROBE_TIMEOUT = float(os.environ.get("TCP_PROBE_TIMEOUT", "1.5"))

# El proxy que funcionó, para no re-descubrirlo en cada request. Se guarda
# en un fichero porque en un plan gratis el contenedor se reinicia o se
# suspende con frecuencia, y la memoria del proceso se pierde: sin esto,
# cada descarga tras un reinicio volvería a pagar el escaneo completo.
# WORKING_PROXY en el entorno tiene prioridad (permite fijarlo sin esperar
# a que el proceso aprenda uno).
WORKING_PROXY = os.environ.get("WORKING_PROXY", "").strip()
WORKING_PROXY_FILE = os.path.join(
    os.environ.get("LOG_DIR", os.path.expanduser("~/.mp3downloader/logs")),
    "working_proxy.txt",
)

# Memoria de proxies que ALGUNA VEZ funcionaron para descargar. La lista
# gratuita rota y muchos estan muertos; probarlos en orden aleatorio gasta
# decenas de segundos en proxies que nunca responden. Al recordar los que
# dieron resultado los probamos PRIMERO en la siguiente peticion, lo que
# acelera mucho el fallback por proxy en IPs de datacenter.
_working_proxy_cache: list[str] = []
_working_proxy_lock = threading.Lock()
_WORKING_PROXY_MAX = 8


def _load_working_proxies() -> list[str]:
    """Proxies conocidos, empezando por WORKING_PROXY y luego el fichero.

    Se llama en cada _find_working_proxy para que un proxy fijado por
    entorno surja sin necesidad de reiniciar el proceso.
    """
    known: list[str] = []
    if WORKING_PROXY and WORKING_PROXY not in known:
        known.append(WORKING_PROXY)
    try:
        if os.path.isfile(WORKING_PROXY_FILE):
            with open(WORKING_PROXY_FILE, "r", encoding="utf-8") as fh:
                for line in fh:
                    p = line.strip()
                    if p and p not in known:
                        known.append(p)
    except OSError as e:
        logger.debug(f"No se pudo leer {WORKING_PROXY_FILE}: {e}")
    with _working_proxy_lock:
        for p in _working_proxy_cache:
            if p not in known:
                known.append(p)
    return known


def _persist_working_proxy(proxy: str) -> None:
    """Guarda el proxy bueno en disco para sobrevivir a un reinicio."""
    try:
        os.makedirs(os.path.dirname(WORKING_PROXY_FILE), exist_ok=True)
        with open(WORKING_PROXY_FILE, "w", encoding="utf-8") as fh:
            fh.write(proxy + "\n")
    except OSError as e:
        logger.debug(f"No se pudo escribir {WORKING_PROXY_FILE}: {e}")


def _remember_working_proxy(proxy: str) -> None:
    """Marca un proxy como que funciono (lo delicamos para reusarlo)."""
    with _working_proxy_lock:
        if proxy in _working_proxy_cache:
            _working_proxy_cache.remove(proxy)
        _working_proxy_cache.insert(0, proxy)
        del _working_proxy_cache[_WORKING_PROXY_MAX:]
    _persist_working_proxy(proxy)


def _proxy_tcp_alive(proxy: str, timeout: float | None = None) -> bool:
    """True si el puerto del proxy acepta conexión dentro del timeout.

    Filtro previo a yt-dlp: un SOCKS5 muerto se descarta en ~timeout (o al
    instante si rechaza la conexión) en lugar de gastar una invocación
    completa de yt-dlp. Un proxy sano de GeoNode acepta en menos de 1 s, así
    que el filtro no descarta ninguno que sirva.
    """
    host_port = proxy.split("://", 1)[-1]
    host, _, port = host_port.rpartition(":")
    if not host or not port.isdigit():
        return False
    try:
        with socket.create_connection((host, int(port)), timeout or TCP_PROBE_TIMEOUT):
            return True
    except (OSError, ValueError):
        return False


def _fetch_free_proxies() -> list[str]:
    """Obtiene proxies SOCKS5 gratuitos de múltiples fuentes.

    Intenta GeoNode primero, luego fallback a otros lists públicos.
    Cachea por 5 min para no abusar de las APIs.
    """
    now = time.time()
    with _free_proxy_lock:
        if _free_proxy_cache["proxies"] and (now - _free_proxy_cache["ts"]) < _FREE_PROXY_CACHE_TTL:
            return _free_proxy_cache["proxies"]

    proxies: list[str] = []

    # Fuente 1: GeoNode (principal)
    try:
        req = urllib.request.Request(
            "https://proxylist.geonode.com/api/proxy-list?"
            "limit=20&page=1&sort_by=lastChecked&sort_type=desc&protocols=socks5",
            headers={"User-Agent": random.choice(USER_AGENTS)},
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode())
            proxies = [
                f"socks5://{p['ip']}:{p['port']}"
                for p in data.get("data", [])
                if p.get("ip") and p.get("port")
            ]
    except Exception as e:
        logger.warning(f"GeoNode proxy fetch failed: {e}")

    # Fuente 2: Si GeoNode falló, intentar SOCKSProxyList
    if not proxies:
        try:
            req = urllib.request.Request(
                "https://www.socks-proxy.net/",
                headers={"User-Agent": random.choice(USER_AGENTS)},
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                html = resp.read().decode("utf-8", errors="replace")
                import re as _re
                for m in _re.finditer(
                    r'<td>(\d+\.\d+\.\d+\.\d+)</td>\s*<td>(\d+)</td>\s*<td>[^<]*</td>\s*<td>[^<]*</td>\s*<td>[^<]*</td>\s*<td>[^<]*</td>\s*<td>[^<]*</td>\s*<td>([^<]+)</td>',
                    html,
                ):
                    ip, port, protocol = m.group(1), m.group(2), m.group(3).strip().lower()
                    if "socks5" in protocol:
                        proxies.append(f"socks5://{ip}:{port}")
        except Exception as e:
            logger.warning(f"SOCKSProxyList fetch failed: {e}")

    if proxies:
        # Respetamos el orden de la fuente (GeoNode ordena por lastChecked
        # desc). Antes se hacía random.shuffle, lo que enterraba al mejor
        # proxy en posiciones >8 y causaba que el server "no encontrara
        # proxy" aunque la lista tuviera uno bueno. Sin shuffle, los mas
        # recientes/mas rapidos se prueban primero.
        with _free_proxy_lock:
            _free_proxy_cache["proxies"] = proxies
            _free_proxy_cache["ts"] = now
        logger.info(f"Fetched {len(proxies)} free SOCKS5 proxies")
    else:
        logger.warning("No free proxies available from any source")

    return proxies


def _try_with_proxy(video_id: str) -> str | None:
    """Intenta obtener URL de audio vía proxies SOCKS5 gratuitos.

    Prueba múltiples proxies con timeout corto para no bloquear.
    YouTube bloquea IPs de datacenter (Render, Railway, etc.), así que
    un proxy residencial o al menos uno que no esté en lista negra es
    necesario para que las descargas funcionen.
    """
    fresh = _fetch_free_proxies()
    if not fresh:
        return None

    # Los proxies ya conocidos se prueban primero: si uno funcionó para otra
    # descarga, es el candidato con más probabilidades.
    candidates: list[str] = []
    for p in _load_working_proxies():
        if p not in candidates:
            candidates.append(p)
    for p in fresh:
        if p not in candidates:
            candidates.append(p)

    url = f"https://youtube.com/watch?v={video_id}"
    # Probar los primeros FREE_PROXY_CANDIDATES, descartando primero los
    # que ni siquiera aceptan conexión (antes eran 8 proxies x 15s = 120s).
    for proxy in candidates[:FREE_PROXY_CANDIDATES]:
        if not _proxy_tcp_alive(proxy):
            continue
        try:
            cmd = [
                "yt-dlp", "--no-warnings",
                "--proxy", proxy,
                "--user-agent", random.choice(USER_AGENTS),
                "--extractor-args", _player_client_arg(ordered_clients()),
                "-f", "bestaudio/best",
                "--extractor-retries", str(YTDLP_EXTRACTOR_RETRIES),
                "--retries", str(YTDLP_RETRIES),
                "--socket-timeout", str(YTDLP_SOCKET_TIMEOUT),
                "--get-url", url,
            ]
            result = subprocess.run(
                cmd, capture_output=True, text=True,
                timeout=PROXY_PROBE_TIMEOUT,
            )
            if result.returncode == 0 and result.stdout.strip():
                logger.info(f"Audio URL via proxy {proxy} for {video_id}")
                return result.stdout.strip().split("\n")[0].strip()
        except subprocess.TimeoutExpired:
            logger.debug(f"Proxy {proxy} timed out for {video_id}")
            continue
        except Exception as e:
            logger.debug(f"Proxy {proxy} failed for {video_id}: {e}")
            continue
    return None


def _proxy_cmd(video_id: str, proxy: str, output_stdout: bool = True) -> list[str]:
    """Comando yt-dlp para descargar el audio COMPLETO vía un proxy SOCKS5.

    A diferencia de `_try_with_proxy` (que solo obtiene la URL con
    `--get-url`), aquí yt-dlp baja los bytes del audio a través del proxy
    y los vuelca a stdout. Esto es OBLIGATORIO: YouTube firma la URL de
    googlevideo para la IP que la solicitó, así que descargar esa URL
    directo desde la IP del servidor siempre devuelve 403 ("Proxy download
    failed"). Al pasar TODO el tráfico por el proxy, la firma aplica a la
    misma IP y la descarga funciona.
    """
    cmd = [
        "yt-dlp", "--no-warnings",
        "--proxy", proxy,
        "--user-agent", random.choice(USER_AGENTS),
        "--extractor-args", _player_client_arg(ordered_clients()),
        "-f", "bestaudio/best",
        "--no-playlist", "--no-part",
    ]
    if output_stdout:
        cmd.append("-o")
        cmd.append("-")
    else:
        cmd += ["-o", f"/tmp/mp3downloader_{video_id}.%(ext)s"]
    cmd.append(f"https://youtube.com/watch?v={video_id}")
    return cmd


def video_format_selector(quality: int) -> str:
    """Selector de formato para el MP4, compartido por la vía directa y el proxy.

    Dos decisiones que costaron un depurado:

    - `bv` y no `bv*`. La estrella de `bv*` incluye los formatos que ya traen
      audio, y como yt-dlp los prefiere, el selector elegía el muxed de 360p en
      lugar del 720p sin audio: se pedía 720p y llegaba 360p. Con `bv` se elige
      la pista de vídeo sola, que es la que luego se combina con `+ba`.

    - Se prefieren `[ext=mp4]` y `[ext=m4a]`, que son h264 y aac. El merge a MP4
      es una copia de pistas, no una conversión: si le cayera VP9 con Opus no
      hay nada que copiar dentro de un contenedor MP4 y habría que reconvertir
      (o fallar). Pidiendo el h264/aac que YouTube ya publica, el archivo sale
      sin tocar los bits.

    - `[ext=mp4]` NO basta para pedir h264, y esto costó una descarga: YouTube
      publica también AV1 y VP9 dentro de MP4, así que sin filtrar el códec
      yt-dlp elige AV1 por defecto porque lo considera mejor. Pasó en Sintel: se
      pidió 240p y llegó av01 de 426x182 teniendo h264 disponible en las cuatro
      alturas (formatos 133/134/135/136). El primer término lleva por eso
      `[vcodec^=avc1]`, y los siguientes se quedan como red de seguridad. Además
      la app declara minSdk 24 y AV1 no se decodifica en Android 7, así que un
      AV1 puede salir a negro en móviles viejos.

    El último `b` es el salto de seguridad para vídeos sin pista de vídeo
    separada o con altura desconocida.
    """
    if quality <= 0:
        return "bv[ext=mp4][vcodec^=avc1]+ba[ext=m4a]/bv*+ba/b"
    return (
        f"bv[height<={quality}][ext=mp4][vcodec^=avc1]+ba[ext=m4a]"
        f"/bv[height<={quality}][ext=mp4]+ba[ext=m4a]"
        f"/bv[height<={quality}]+ba"
        f"/b[height<={quality}]/b"
    )


def _proxy_cmd_video(video_id: str, proxy: str, quality: int, workdir: str) -> list[str]:
    """Comando yt-dlp para descargar VÍDEO completo vía proxy SOCKS5.

    No reutiliza `_proxy_cmd` a propósito: allí el formato es audio y la salida
    va a stdout, mientras que el merge de vídeo necesita un fichero real donde
    ffmpeg pueda escribir el contenedor MP4 (no se puede muxear MP4 a un pipe
    sin usar flags de fragmentado que no queremos en la salida final).
    """
    return [
        "yt-dlp", "--no-warnings",
        "--proxy", proxy,
        "--user-agent", random.choice(USER_AGENTS),
        "--extractor-args", _player_client_arg(ordered_video_clients()),
        "-f", video_format_selector(quality),
        "--merge-output-format", "mp4",
        "--no-playlist", "--no-part",
        "-o", os.path.join(workdir, "v.%(ext)s"),
        f"https://youtube.com/watch?v={video_id}",
    ]


def _find_working_proxy(video_id: str, blocked: set | None = None) -> str | None:
    """Encuentra un proxy que pueda resolver el video (validación rápida).

    Prueba varios proxies con `--get-url`; devuelve el primero que
    responda. Se prueban PRIMERO los proxies que funcionaron en el pasado
    (los gratuitos rotan y la lista está llena de muertos, así que esto
    evita gastar decenas de segundos en proxies que nunca responden).
    `blocked`: conjunto de proxies a omitir (los que ya fallaron la
    descarga completa en esta petición), para no re-probarlos.
    La descarga posterior (`_proxy_cmd`) usará ese MISMO proxy para que la
    firma de la URL coincida con la IP de descarga.
    """
    blocked = blocked or set()
    fresh = _fetch_free_proxies()
    known = _load_working_proxies()
    # Probar primeros los ya conocidos, luego el resto de la lista fresca.
    candidates: list[str] = []
    for p in known:
        if p not in candidates and p not in blocked:
            candidates.append(p)
    for p in fresh:
        if p not in candidates and p not in blocked:
            candidates.append(p)

    url = f"https://youtube.com/watch?v={video_id}"
    # Timeout corto por proxy (PROXY_PROBE_TIMEOUT, 6s por defecto): los
    # muertos se descartan antes con el sondeo TCP y los buenos responden en
    # ~3s (validado: 193.25.215.182 dio get-url en ~3s). Se prueban hasta
    # FREE_PROXY_CANDIDATES, con el filtro TCP el coste de los muertos es
    # ~1.5s cada uno en lugar de los 6s completos.
    probed = 0
    for proxy in candidates[:FREE_PROXY_CANDIDATES]:
        if not _proxy_tcp_alive(proxy):
            logger.debug(f"Proxy {proxy} no acepta conexión, descartado")
            continue
        probed += 1
        try:
            cmd = [
                "yt-dlp", "--no-warnings",
                "--proxy", proxy,
                "--user-agent", random.choice(USER_AGENTS),
                "--extractor-args", _player_client_arg(ordered_clients()),
                "-f", "bestaudio/best",
                "--extractor-retries", str(YTDLP_EXTRACTOR_RETRIES),
                "--retries", str(YTDLP_RETRIES),
                "--socket-timeout", str(YTDLP_SOCKET_TIMEOUT),
                "--get-url", url,
            ]
            result = subprocess.run(
                cmd, capture_output=True, text=True,
                timeout=PROXY_PROBE_TIMEOUT,
            )
            if result.returncode == 0 and result.stdout.strip():
                logger.info(f"Proxy activo para {video_id}: {proxy}")
                # NOTA: NO marcamos el proxy como "working" aqui. Que resuelva
                # el --get-url no garantiza que la descarga COMPLETA funcione
                # (la firma de googlevideo puede fallar al descargar). Solo
                # _stream_proxy_download lo marca al CONFIRMAR la descarga.
                return proxy
        except Exception:
            continue
    logger.info(f"Sin proxy utilizable para {video_id} "
                f"({probed} probados tras filtro TCP de {len(candidates)})")
    return None


# ═══════════════════════════════════════════════════════════════
# Invidious fallback — funciona sin cookies/proxy
# ────────────────────────────────────────────────────────────────

def _invidious_request(url: str, timeout: int = 15) -> dict | str | None:
    """GET request a instancia Invidious, retorna JSON o None."""
    try:
        req = urllib.request.Request(url, headers={
            "User-Agent": random.choice(USER_AGENTS),
            "Accept": "application/json",
        })
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = resp.read().decode("utf-8", errors="replace")
            if data.strip().startswith("<"):
                return None  # HTML = instance bloqueada
            try:
                return json.loads(data)
            except json.JSONDecodeError:
                return data
    except Exception as e:
        logger.debug(f"Invidious request failed ({url}): {e}")
        return None


def _resolve_invidious_instance() -> str | None:
    """Encuentra una instancia Invidious que responda."""
    global _invidious_active
    with _invidious_lock:
        # Si ya sabemos cuál funciona, probar esa primero
        if _invidious_active:
            test = _invidious_request(f"{_invidious_active}/api/v1/stats",
                                      timeout=INVIDIOUS_PROBE_TIMEOUT)
            if test:
                return _invidious_active
            _invidious_active = None

        # Solo las primeras INVIDIOUS_PROBE_INSTANCES: sondear las 9 a 5s
        # eran hasta 45s de espera en el último eslabón de la cadena.
        for instance in INVIDIOUS_INSTANCES[:INVIDIOUS_PROBE_INSTANCES]:
            test = _invidious_request(f"{instance}/api/v1/stats",
                                      timeout=INVIDIOUS_PROBE_TIMEOUT)
            if test:
                _invidious_active = instance
                logger.info(f"Invidious instance activa: {instance}")
                return instance

        logger.warning("Ninguna instancia Invidious disponible")
        return None


def invidious_get_audio_url(video_id: str) -> str | None:
    """Obtiene URL de audio vía Invidious. Retorna None si falla.

    Prueba múltiples instancias si la primera falla.
    """
    # Primero intentar con la instancia activa conocida
    instance = _resolve_invidious_instance()
    instances_to_try = [instance] if instance else []
    # Agregar las demás instancias como fallback
    instances_to_try.extend(
        i for i in INVIDIOUS_INSTANCES if i not in instances_to_try
    )

    for inst in instances_to_try:
        data = _invidious_request(f"{inst}/api/v1/videos/{video_id}",
                                  timeout=INVIDIOUS_VIDEO_TIMEOUT)
        if not data or not isinstance(data, dict):
            continue

        formats = data.get("adaptiveFormats") or data.get("formatStreams") or []
        # Buscar mejor audio m4a/mp4
        audio_formats = [
            f for f in formats
            if "audio" in (f.get("type") or f.get("mimeType") or "")
        ]
        if not audio_formats:
            audio_formats = formats  # fallback a cualquier formato

        if not audio_formats:
            continue

        # Ordenar por bitrate descendente
        audio_formats.sort(key=lambda f: f.get("bitrate") or 0, reverse=True)
        url = audio_formats[0].get("url")
        if url and url.startswith("https://"):
            logger.info(f"Audio URL via Invidious {inst} for {video_id}")
            return url

    logger.warning(f"No Invidious instance could provide audio for {video_id}")
    return None


def invidious_download(video_id: str, output_path: str,
                       on_progress: Optional[Callable] = None) -> bool:
    """Descarga audio vía Invidious. Retorna True si éxito."""
    audio_url = invidious_get_audio_url(video_id)
    if not audio_url:
        return False

    try:
        if on_progress:
            on_progress(DownloadStatus.DOWNLOADING, 0.0, "", "")

        req = urllib.request.Request(audio_url, headers={
            "User-Agent": random.choice(USER_AGENTS),
        })
        with urllib.request.urlopen(req, timeout=60) as resp:
            total = int(resp.headers.get("Content-Length", 0))
            downloaded = 0
            chunk_size = 8192
            tmp_path = output_path + ".tmp"

            with open(tmp_path, "wb") as f:
                while True:
                    chunk = resp.read(chunk_size)
                    if not chunk:
                        break
                    f.write(chunk)
                    downloaded += len(chunk)
                    if on_progress and total > 0:
                        on_progress(DownloadStatus.DOWNLOADING, downloaded / total, "", "")

            os.replace(tmp_path, output_path)
            if on_progress:
                on_progress(DownloadStatus.COMPLETED, 1.0, "", "")
            return True

    except Exception as e:
        logger.warning(f"Invidious download failed for {video_id}: {e}")
        try:
            os.remove(output_path + ".tmp")
        except Exception:
            pass
        return False



# PO token provider (bgutil)
# Dos vías:
#   BGUTIL_SERVER_HOME: ruta al repo del provider (server). yt-dlp usa el
#     plugin `bgutilscript` que ejecuta `node generate_once.js` POR REQUEST.
#     No depende de un proceso HTTP persistente → es FIABLE en Render free,
#     donde el server background se cae por el reinicio/recursos.
#   PO_TOKEN_PROVIDER_URL: server HTTP (bgutilhttp). Solo si responde,
#     porque requiere un proceso Node background que en Render suele morir.
BGUTIL_SERVER_HOME = os.environ.get("BGUTIL_SERVER_HOME", "")
PO_TOKEN_PROVIDER_URL = os.environ.get("PO_TOKEN_PROVIDER_URL", "")

# Si el provider HTTP no responde, NO se añade su `--extractor-args`.
#
# Estar configurado no es lo mismo que estar vivo. En Render la variable
# `PO_TOKEN_PROVIDER_URL` apuntaba a `http://127.0.0.1:4416` sin que hubiera
# ningún proceso escuchando, y eso rompía TODAS las descargas: 502 tras 67-78 s.
# El mismo commit en Railway, donde la variable NO está puesta, descarga bien.
# O sea, la vía HTTP caída era la causa, no un detalle menor.
#
# Antes se añadía el argumento siempre que la variable existiera, asumiendo que
# yt-dlp caería al provider de script cuando el HTTP fallara. No lo hace: con la
# entrada muerta presente, la extracción falla.
_po_http_alive = False
_po_http_checked = False


def po_http_provider_alive(force: bool = False) -> bool:
    """¿Responde el provider HTTP de PO tokens? Resultado cacheado.

    El sondeo se hace una sola vez porque el provider, si existe, es un proceso
    persistente: si no estaba al arrancar, no aparece solo. `force=True` lo
    vuelve a comprobar, para `/api/health`.
    """
    global _po_http_alive, _po_http_checked
    if not PO_TOKEN_PROVIDER_URL:
        return False
    if _po_http_checked and not force:
        return _po_http_alive

    import urllib.request as _urllib
    # Probar IPv4 y luego IPv6: en Render el provider escucha en una u otra
    # segun como se levante, y `::1` es el fallo clasico de Node.
    for test_url in (PO_TOKEN_PROVIDER_URL,
                     PO_TOKEN_PROVIDER_URL.replace("127.0.0.1", "::1")):
        try:
            with _urllib.urlopen(test_url, timeout=3):
                _po_http_alive = True
                break
        except Exception:
            continue
    else:
        _po_http_alive = False

    _po_http_checked = True
    if not _po_http_alive:
        logger.warning(
            f"PO_TOKEN_PROVIDER_URL={PO_TOKEN_PROVIDER_URL} no responde; "
            f"se omite youtubepot-bgutilhttp y se usa solo el provider script"
        )
    return _po_http_alive


def _base_cmd(client: str | None = None, cookies: bool = True) -> list[str]:
    """Return base yt-dlp args common to all invocations.

    `cookies` lets the caller disable cookies for a given attempt. This is
    the key fallback: an expired/invalid cookie session can restrict the
    available formats and trigger "requested format is not available", while
    a cookie-free request often still succeeds for public videos.
    """
    cmd = ["yt-dlp", "--no-warnings"]
    player = client or PLAYER_CLIENTS[0]
    extractor = f"youtube:player_client={player}"
    # Reintentos internos acotados: por defecto yt-dlp reintenta 3 veces la
    # extracción y 10 la descarga con backoff exponencial, y una sola
    # invocación bloqueada se pasa 20-30 s. Como el engine ya recorre
    # 7 player clients y 4 proxies, ese reintento es tiempo duplicado.
    cmd.extend([
        "--extractor-retries", str(YTDLP_EXTRACTOR_RETRIES),
        "--retries", str(YTDLP_RETRIES),
        "--socket-timeout", str(YTDLP_SOCKET_TIMEOUT),
    ])
    if PO_TOKEN:
        extractor += f";po_token={PO_TOKEN}"
    # PO token provider — genera tokens automáticamente para cada video.
    # IMPORTANTE: usar un solo --extractor-args con múltiples PROVIDERS
    # separados por punto y coma. yt-dlp prueba cada provider en orden del
    # registro (script-node tiene preferencia sobre http), así que AMBOS
    # funcionan como fallback el uno del otro.
    if BGUTIL_SERVER_HOME:
        # Vía robusta: spinner propio del provider por request (sin server).
        extractor += f";youtubepot-bgutilscript:server_home={BGUTIL_SERVER_HOME}"
    if po_http_provider_alive():
        # Vía server HTTP — solo si de verdad responde. Si esta puesta pero
        # muerta, incluirla hace fallar la extracción entera, asi que se
        # comprueba antes en vez de fiarse de que la variable exista.
        extractor += f";youtubepot-bgutilhttp:base_url={PO_TOKEN_PROVIDER_URL}"
    cmd.extend(["--extractor-args", extractor])
    if cookies and os.path.isfile(COOKIES_FILE):
        cmd.extend(["--cookies", COOKIES_FILE])
    # User-Agent rotado — evita fingerprinting estático
    cmd.extend(["--user-agent", random.choice(USER_AGENTS)])
    # Headers realistas para simular navegador legítimo
    cmd.extend([
        "--referer", "https://www.youtube.com/",
        "--add-header", "Accept-Language:en-US,en;q=0.9",
        "--add-header", "Accept:text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "--add-header", "Sec-Fetch-Dest:document",
        "--add-header", "Sec-Fetch-Mode:navigate",
        "--add-header", "Sec-Fetch-Site:none",
        "--add-header", "Sec-Fetch-User:?1",
        "--add-header", "Upgrade-Insecure-Requests:1",
    ])
    # Proxy residencial si está configurado
    if RESIDENTIAL_PROXY:
        cmd.extend(["--proxy", RESIDENTIAL_PROXY])
    return cmd


class DownloadEngine:

    def search(self, query: str, max_results: int = 20, offset: int = 0) -> List[Song]:
        cache_key = (query, max_results, offset)
        cached = _search_cache_get(cache_key)
        if cached is not None:
            return cached

        # yt-dlp has no native offset, so we request up to (offset + max_results)
        # and slice out the page we need.
        total = max_results + max(offset, 0)
        cmd = _base_cmd() + [
            "--flat-playlist", "--dump-json",
            f"ytsearch{total}:{query}"
        ]
        result = subprocess.run(
            cmd,
            capture_output=True, text=True, timeout=YTDLP_TIMEOUT
        )
        if result.returncode != 0:
            raise RuntimeError(f"Search failed: {result.stderr[:500]}")

        songs: List[Song] = []
        for line in result.stdout.strip().split("\n"):
            line = line.strip()
            if not line:
                continue
            entry = json.loads(line)
            songs.append(Song(
                id=entry["id"],
                title=entry["title"],
                artist=self._extract_artist(entry["title"]),
                duration=int(entry.get("duration") or 0),
                thumbnail_url=f"https://i.ytimg.com/vi/{entry['id']}/default.jpg",
            ))
        sliced = songs[offset:offset + max_results]
        _search_cache_put(cache_key, sliced)
        return sliced

    def get_audio_url(self, song: Song) -> str:
        url = f"https://youtube.com/watch?v={song.id}"
        last_err = ""
        cookie_passes = [True, False] if os.path.isfile(COOKIES_FILE) else [False]
        for use_cookies in cookie_passes:
            for client in ordered_clients():
                cmd = _base_cmd(client, cookies=use_cookies) + [
                    "-f", "bestaudio/best",
                    "--get-url", url
                ]
                result = subprocess.run(
                    cmd,
                    capture_output=True, text=True, timeout=30
                )
                if result.returncode == 0 and result.stdout.strip():
                    record_success(client)
                    _clear_backoff(client)
                    return result.stdout.strip().split("\n")[0].strip()
                last_err = result.stderr[:200]
                record_failure(client)
                if _is_hard_ban(last_err):
                    logger.warning(f"Hard ban detectado en {client} para {song.id}")
                    _record_backoff(client)
                    continue
                if _is_transient(last_err):
                    backoff = _get_backoff(client)
                    logger.info(f"Backoff {backoff:.1f}s para {client} (transitorio)")
                    time.sleep(backoff)
                    _record_backoff(client)

        # Fallback 1: intentar vía proxy SOCKS5 gratuito
        logger.info(f"yt-dlp falló para {song.id}, intentando proxy SOCKS5...")
        proxy_url = _try_with_proxy(song.id)
        if proxy_url:
            return proxy_url

        # Fallback 2: intentar vía Invidious
        logger.info(f"Proxy falló para {song.id}, intentando Invidious...")
        invidious_url = invidious_get_audio_url(song.id)
        if invidious_url:
            return invidious_url

        raise RuntimeError(f"All player clients failed for {song.id}: {last_err}")

    def download(
        self,
        song: Song,
        output_dir: str,
        on_progress: Optional[Callable[[DownloadStatus, float, str, str], None]] = None,
        on_complete: Optional[Callable[[Optional[str], Optional[str]], None]] = None,
        cancel_flag: Optional[Callable[[], bool]] = None,
        process_tracker: Optional[Callable[[subprocess.Popen], None]] = None,
    ) -> None:
        safe = sanitize_filename(song.title)
        base_path = os.path.join(output_dir, safe)
        output_path = f"{base_path}.mp3"
        thumb_path = f"{base_path}.jpg"

        if on_progress:
            on_progress(DownloadStatus.DOWNLOADING, 0.0, "", "")

        # Robust format selectors: let yt-dlp self-fall-back to whatever
        # audio stream is actually available instead of hard-requiring a
        # specific container (the old [ext=m4a] filter caused
        # "requested format is not available").
        format_attempts = [
            "bestaudio/best",
            "best",
        ]

        last_error = ""
        # Pass 1: with cookies (best quality / restricted content).
        # Pass 2: without cookies (tv_embedded etc. bypass bot checks).
        cookie_passes = [True, False] if os.path.isfile(COOKIES_FILE) else [False]
        for use_cookies in cookie_passes:
            for client in ordered_clients():
                for fmt in format_attempts:
                    if cancel_flag and cancel_flag():
                        if on_complete:
                            on_complete(None, "Cancelado")
                        return

                    try:
                        cmd = _base_cmd(client, cookies=use_cookies) + [
                            "--newline",
                            "-f", fmt,
                            "--extract-audio", "--audio-format", "mp3", "--audio-quality", "256K",
                            "--embed-thumbnail", "--add-metadata",
                            "--write-thumbnail", "--convert-thumbnails", "jpg",
                            "--parse-metadata", "title:%(title)s",
                            "--parse-metadata", "artist:%(channel)s",
                            "--parse-metadata", "album:%(playlist_title|)s",
                            "--parse-metadata", "genre:YouTube Audio",
                            "-o", f"{base_path}.%(ext)s",
                            f"https://youtube.com/watch?v={song.id}",
                        ]

                        process = subprocess.Popen(
                            cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
                        )

                        if process_tracker:
                            process_tracker(process)

                        pct_re = re.compile(r"\[download\]\s+(\d+\.\d+)%")
                        size_re = re.compile(r"of\s+~?([\d.]+\s*[KMG]?i?B)")
                        speed_re = re.compile(r"at\s+([\d.]+\s*[KMG]?i?B/s)")

                        for line in iter(process.stdout.readline, ""):
                            if cancel_flag and cancel_flag():
                                process.terminate()
                                if on_complete:
                                    on_complete(None, "Cancelado")
                                return
                            m = pct_re.search(line)
                            if m and on_progress:
                                pct = float(m.group(1)) / 100.0
                                size_m = size_re.search(line)
                                speed_m = speed_re.search(line)
                                total = size_m.group(1).strip() if size_m else ""
                                speed = speed_m.group(1).strip() if speed_m else ""
                                on_progress(DownloadStatus.DOWNLOADING, pct, speed, total)

                        process.stdout.close()
                        process.wait()

                        if process.returncode == 0:
                            record_success(client)
                            _clear_backoff(client)
                            if on_progress:
                                on_progress(DownloadStatus.CONVERTING, 0.7, "", "")
                            self._embed_metadata(output_path, thumb_path)
                            if on_complete:
                                on_complete(output_path, None)
                            return

                        stderr = process.stderr.read() if process.stderr else ""
                        last_error = f"[{client}] Format '{fmt}' failed: {stderr[:200]}"
                        record_failure(client)
                        print(f"[DL] {last_error}", flush=True)

                        # Backoff exponencial para errores transitorios
                        if _is_transient(stderr):
                            backoff = _get_backoff(client)
                            logger.info(f"DL backoff {backoff:.1f}s para {client}")
                            time.sleep(backoff)
                            _record_backoff(client)
                        elif _is_hard_ban(stderr):
                            _record_backoff(client)

                    except Exception as e:
                        last_error = f"[{client}] Format '{fmt}' exception: {e}"
                        record_failure(client)
                        print(f"[DL] {last_error}", flush=True)

                    for f in [output_path, thumb_path, f"{base_path}.m4a", f"{base_path}.webm"]:
                        if os.path.isfile(f):
                            os.remove(f)

        # Fallback 1: intentar descarga vía proxy SOCKS5 gratuito
        # (la descarga COMPLETA pasa por el proxy — ver _proxy_cmd)
        logger.info(f"yt-dlp falló para {song.id}, intentando proxy SOCKS5...")
        if on_progress:
            on_progress(DownloadStatus.DOWNLOADING, 0.0, "", "Proxy fallback")

        proxy = _find_working_proxy(song.id)
        if proxy:
            try:
                if on_progress:
                    on_progress(DownloadStatus.DOWNLOADING, 0.0, "", "Proxy download")
                # yt-dlp baja el audio por el proxy y lo vuelca a stdout;
                # ffmpeg lo convierte a MP3 sin reintentar la red.
                import subprocess as _sp
                yt_cmd = _proxy_cmd(song.id, proxy, output_stdout=True)
                ffmpeg_cmd = [
                    "ffmpeg", "-y", "-i", "-",
                    "-codec:a", "libmp3lame", "-b:a", "256k",
                    "-id3v2_version", "3",
                    "-f", "mp3", output_path + ".tmp",
                ]
                p1 = _sp.Popen(yt_cmd, stdout=_sp.PIPE, stderr=_sp.PIPE)
                p2 = _sp.Popen(ffmpeg_cmd, stdin=p1.stdout, stdout=_sp.PIPE, stderr=_sp.PIPE)
                p1.stdout.close()
                stderr2 = p2.stderr.read()
                stderr1 = p1.stderr.read()
                p1.wait(); p2.wait()
                if os.path.isfile(output_path + ".tmp"):
                    os.replace(output_path + ".tmp", output_path)
                    if on_progress:
                        on_progress(DownloadStatus.CONVERTING, 0.7, "", "")
                    self._embed_metadata(output_path, thumb_path)
                    if on_complete:
                        on_complete(output_path, None)
                    return
                logger.warning(f"Proxy full download failed for {song.id}: "
                               f"{stderr1[:300]} {stderr2[:300]}")
                p1.terminate(); p2.terminate()
            except Exception as e:
                logger.warning(f"Proxy full download failed for {song.id}: {e}")
                try: p1.terminate()
                except Exception: pass
                try: p2.terminate()
                except Exception: pass
                try:
                    os.remove(output_path + ".tmp")
                except Exception:
                    pass

        # Fallback 2: intentar descarga vía Invidious
        logger.info(f"Proxy falló para {song.id}, intentando Invidious fallback...")
        if on_progress:
            on_progress(DownloadStatus.DOWNLOADING, 0.0, "", "Invidious fallback")

        def _invidious_progress(status, pct, speed, total):
            if on_progress:
                on_progress(status, pct, speed, total)

        success = invidious_download(song.id, output_path, on_progress=_invidious_progress)
        if success:
            if on_progress:
                on_progress(DownloadStatus.CONVERTING, 0.7, "", "")
            self._embed_metadata(output_path, thumb_path)
            if on_complete:
                on_complete(output_path, None)
            return

        if on_complete:
            on_complete(None, last_error or "All download formats failed (yt-dlp + proxy + Invidious)")

    def _embed_metadata(self, output_path: str, thumb_path: str) -> None:
        if not os.path.isfile(thumb_path):
            return
        try:
            tmp_path = f"{output_path}.tmp.mp3"
            result = subprocess.run(
                ["ffmpeg", "-y",
                 "-i", output_path,
                 "-i", thumb_path,
                 "-map", "0:0",
                 "-map", "1:0",
                 "-c", "copy",
                 "-id3v2_version", "3",
                 "-metadata:s:v", "title=Album cover",
                 "-metadata:s:v", "comment=Cover (front)",
                 "-disposition:v", "attached_pic",
                 tmp_path],
                capture_output=True, timeout=30
            )
            if result.returncode == 0 and os.path.isfile(tmp_path):
                os.replace(tmp_path, output_path)
            if os.path.isfile(thumb_path):
                os.remove(thumb_path)
        except Exception:
            pass

    @staticmethod
    def _extract_artist(title: str) -> str:
        match = re.match(r"^(.+?)\s*[-–]", title)
        return match.group(1).strip() if match else "Artista desconocido"
