from __future__ import annotations

import json
import os
import re
import subprocess
from pathlib import Path
from typing import Callable, List, Optional

from models.song import DownloadStatus, DownloadTask, Song
from utils.helpers import sanitize_filename

YTDLP_TIMEOUT = 30


class DownloadEngine:

    def search(self, query: str, max_results: int = 20) -> List[Song]:
        result = subprocess.run(
            ["yt-dlp", "--flat-playlist", "--dump-json", "--no-warnings",
             f"ytsearch{max_results}:{query}"],
            capture_output=True, text=True, timeout=YTDLP_TIMEOUT
        )
        if result.returncode != 0:
            raise RuntimeError(f"Search failed: {result.stderr[:200]}")

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
        result = subprocess.run(
            ["yt-dlp", "--no-warnings",
             "-f", "bestaudio[ext=m4a]/bestaudio",
             "--get-url", url],
            capture_output=True, text=True, timeout=15
        )
        if result.returncode != 0 or not result.stdout.strip():
            raise RuntimeError(f"Failed to get audio URL: {result.stderr[:200]}")
        return result.stdout.strip().split("\n")[0].strip()

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

        try:
            process = subprocess.Popen(
                ["yt-dlp", "--no-warnings", "--newline",
                 "-f", "bestaudio",
                 "--extract-audio", "--audio-format", "mp3", "--audio-quality", "0",
                 "--add-metadata",
                 "--write-thumbnail", "--convert-thumbnails", "jpg",
                 "-o", f"{base_path}.%(ext)s",
                 f"https://youtube.com/watch?v={song.id}"],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
            )

            if process_tracker:
                process_tracker(process)

            pct_re = re.compile(r"\[download\]\s+(\d+\.\d+)%")
            size_re = re.compile(r"of\s+~?([\d.]+\s*[KMG]?i?B)")
            speed_re = re.compile(r"at\s+([\d.]+\s*[KMG]?i?B/s)")
            error_lines: list[str] = []

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
                elif "ERROR:" in line:
                    error_lines.append(line.strip())
            process.stdout.close()
            process.wait()

            if process.returncode != 0:
                err_msg = "; ".join(error_lines) or f"código {process.returncode}"
                if on_complete:
                    on_complete(None, err_msg)
                return

            if on_progress:
                on_progress(DownloadStatus.CONVERTING, 0.7, "", "")

            self._embed_metadata(output_path, thumb_path)

            if on_complete:
                on_complete(output_path, None)

        except Exception as e:
            if on_complete:
                on_complete(None, str(e))

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
