from __future__ import annotations

import json
import os
import re
import subprocess
import threading
import time
from pathlib import Path
from typing import Callable, List, Optional

from models.song import DownloadStatus, DownloadTask, Song
from utils.helpers import sanitize_filename

YTDLP_TIMEOUT = 60

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
            for client in PLAYER_CLIENTS:
                cmd = _base_cmd(client, cookies=use_cookies) + [
                    "-f", "bestaudio/best",
                    "--get-url", url
                ]
                result = subprocess.run(
                    cmd,
                    capture_output=True, text=True, timeout=30
                )
                if result.returncode == 0 and result.stdout.strip():
                    return result.stdout.strip().split("\n")[0].strip()
                last_err = result.stderr[:200]
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
            for client in PLAYER_CLIENTS:
                for fmt in format_attempts:
                    if cancel_flag and cancel_flag():
                        if on_complete:
                            on_complete(None, "Cancelado")
                        return

                    try:
                        cmd = _base_cmd(client, cookies=use_cookies) + [
                            "--newline",
                            "-f", fmt,
                            "--extract-audio", "--audio-format", "mp3", "--audio-quality", "0",
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
                            if on_progress:
                                on_progress(DownloadStatus.CONVERTING, 0.7, "", "")
                            self._embed_metadata(output_path, thumb_path)
                            if on_complete:
                                on_complete(output_path, None)
                            return

                        stderr = process.stderr.read() if process.stderr else ""
                        last_error = f"[{client}] Format '{fmt}' failed: {stderr[:200]}"
                        print(f"[DL] {last_error}", flush=True)

                    except Exception as e:
                        last_error = f"[{client}] Format '{fmt}' exception: {e}"
                        print(f"[DL] {last_error}", flush=True)

                    for f in [output_path, thumb_path, f"{base_path}.m4a", f"{base_path}.webm"]:
                        if os.path.isfile(f):
                            os.remove(f)

        if on_complete:
            on_complete(None, last_error or "All download formats failed")

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
