from __future__ import annotations

import json
import os
import re
import subprocess
import time
from pathlib import Path
from typing import Callable, List, Optional

from models.song import DownloadStatus, DownloadTask, Song
from utils.helpers import sanitize_filename

YTDLP_TIMEOUT = 60

# Ruta del archivo de cookies — configurable via env var
COOKIES_FILE = os.environ.get(
    "COOKIES_FILE",
    "/opt/mp3downloader/cookies/cookies.txt"
)


PLAYER_CLIENTS = [
    "android_vr,web",
    "android,web",
    "ios,web",
    "mweb,web",
    "web",
]


def _base_cmd(client: str | None = None) -> list[str]:
    """Return base yt-dlp args common to all invocations."""
    cmd = ["yt-dlp", "--no-warnings"]
    player = client or PLAYER_CLIENTS[0]
    cmd.extend(["--extractor-args", f"youtube:player_client={player}"])
    if os.path.isfile(COOKIES_FILE):
        cmd.extend(["--cookies", COOKIES_FILE])
    return cmd


class DownloadEngine:

    def search(self, query: str, max_results: int = 20) -> List[Song]:
        cmd = _base_cmd() + [
            "--flat-playlist", "--dump-json",
            f"ytsearch{max_results}:{query}"
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
        return songs

    def get_audio_url(self, song: Song) -> str:
        url = f"https://youtube.com/watch?v={song.id}"
        last_err = ""
        for client in PLAYER_CLIENTS:
            cmd = _base_cmd(client) + [
                "-f", "best",
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

        # Try multiple format options in order of preference
        format_attempts = [
            "bestaudio[ext=m4a]",
            "bestaudio",
            "bestaudio[ext=webm]",
            "best",
        ]

        last_error = ""
        for client in PLAYER_CLIENTS:
            for fmt in format_attempts:
                if cancel_flag and cancel_flag():
                    if on_complete:
                        on_complete(None, "Cancelado")
                    return

                try:
                    cmd = _base_cmd(client) + [
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
