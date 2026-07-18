from __future__ import annotations

import json
import logging
import os
import random
import re
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
INVIDIOUS_INSTANCES = [
    "https://inv.zoomerville.com",
    "https://invidious.slipfox.xyz",
    "https://invidious.projectsegfau.lt",
    "https://invidious.protokolla.fi",
    "https://invidious.flokinet.to",
    "https://vid.puffyan.us",
    "https://iv.ggtyler.dev",
]
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
# expired. `tv_embedded`/`tv` rarely trigger "prove you're not a bot".
PLAYER_CLIENTS = [
    "tv_embedded",
    "tv",
    "mweb",
    "web",
    "android",
    "ios",
    "android_vr,web",
]

# Optional Proof-of-Origin token (env var YT_PO_TOKEN) in yt-dlp syntax
# "client+token" (e.g. "web+XXXX"). Strongly recommended to avoid bot
# challenges without depending on a session cookie that can expire.
PO_TOKEN = os.environ.get("YT_PO_TOKEN")

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


def _fetch_free_proxies() -> list[str]:
    """Obtiene proxies SOCKS5 gratuitos de la API de GeoNode."""
    now = time.time()
    with _free_proxy_lock:
        if _free_proxy_cache["proxies"] and (now - _free_proxy_cache["ts"]) < _FREE_PROXY_CACHE_TTL:
            return _free_proxy_cache["proxies"]

    try:
        req = urllib.request.Request(
            "https://proxylist.geonode.com/api/proxy-list?"
            "limit=15&page=1&sort_by=lastChecked&sort_type=desc&protocols=socks5",
            headers={"User-Agent": random.choice(USER_AGENTS)},
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode())
            proxies = [
                f"socks5://{p['ip']}:{p['port']}"
                for p in data.get("data", [])
                if p.get("ip") and p.get("port")
            ]
            with _free_proxy_lock:
                _free_proxy_cache["proxies"] = proxies
                _free_proxy_cache["ts"] = now
            logger.info(f"Fetched {len(proxies)} free SOCKS5 proxies")
            return proxies
    except Exception as e:
        logger.warning(f"Failed to fetch free proxies: {e}")
        return []


def _try_with_proxy(video_id: str) -> str | None:
    """Intenta obtener URL de audio vía proxies SOCKS5 gratuitos."""
    proxies = _fetch_free_proxies()
    if not proxies:
        return None

    url = f"https://youtube.com/watch?v={video_id}"
    for proxy in proxies[:5]:  # probar max 5
        try:
            cmd = [
                "yt-dlp", "--no-warnings",
                "--proxy", proxy,
                "--user-agent", random.choice(USER_AGENTS),
                "--extractor-args", "youtube:player_client=tv_embedded",
                "-f", "bestaudio/best",
                "--get-url", url,
            ]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
            if result.returncode == 0 and result.stdout.strip():
                logger.info(f"Audio URL via proxy {proxy} for {video_id}")
                return result.stdout.strip().split("\n")[0].strip()
        except (subprocess.TimeoutExpired, Exception):
            continue
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
            test = _invidious_request(f"{_invidious_active}/api/v1/stats", timeout=5)
            if test:
                return _invidious_active
            _invidious_active = None

        for instance in INVIDIOUS_INSTANCES:
            test = _invidious_request(f"{instance}/api/v1/stats", timeout=5)
            if test:
                _invidious_active = instance
                logger.info(f"Invidious instance activa: {instance}")
                return instance

        logger.warning("Ninguna instancia Invidious disponible")
        return None


def invidious_get_audio_url(video_id: str) -> str | None:
    """Obtiene URL de audio vía Invidious. Retorna None si falla."""
    instance = _resolve_invidious_instance()
    if not instance:
        return None

    data = _invidious_request(f"{instance}/api/v1/videos/{video_id}", timeout=20)
    if not data or not isinstance(data, dict):
        return None

    formats = data.get("adaptiveFormats") or data.get("formatStreams") or []
    # Buscar mejor audio m4a/mp4
    audio_formats = [
        f for f in formats
        if "audio" in (f.get("type") or f.get("mimeType") or "")
    ]
    if not audio_formats:
        audio_formats = formats  # fallback a cualquier formato

    if not audio_formats:
        return None

    # Ordenar por bitrate descendente
    audio_formats.sort(key=lambda f: f.get("bitrate") or 0, reverse=True)
    url = audio_formats[0].get("url")
    if url and url.startswith("https://"):
        return url
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
    if PO_TOKEN:
        extractor += f";po_token={PO_TOKEN}"
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
        logger.info(f"yt-dlp falló para {song.id}, intentando proxy SOCKS5...")
        if on_progress:
            on_progress(DownloadStatus.DOWNLOADING, 0.0, "", "Proxy fallback")

        proxy_url = _try_with_proxy(song.id)
        if proxy_url:
            try:
                if on_progress:
                    on_progress(DownloadStatus.DOWNLOADING, 0.0, "", "Proxy download")
                # Descargar vía URL obtenida del proxy
                import urllib.request as _urllib_req
                proxy_req = _urllib_req.Request(proxy_url, headers={
                    "User-Agent": random.choice(USER_AGENTS),
                })
                with _urllib_req.urlopen(proxy_req, timeout=60) as resp:
                    total = int(resp.headers.get("Content-Length", 0))
                    downloaded = 0
                    tmp_path = output_path + ".tmp"
                    with open(tmp_path, "wb") as f:
                        while True:
                            chunk = resp.read(8192)
                            if not chunk:
                                break
                            f.write(chunk)
                            downloaded += len(chunk)
                            if on_progress and total > 0:
                                on_progress(DownloadStatus.DOWNLOADING, downloaded / total, "", "")
                    os.replace(tmp_path, output_path)
                    if on_progress:
                        on_progress(DownloadStatus.CONVERTING, 0.7, "", "")
                    self._embed_metadata(output_path, thumb_path)
                    if on_complete:
                        on_complete(output_path, None)
                    return
            except Exception as e:
                logger.warning(f"Proxy download failed for {song.id}: {e}")
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
