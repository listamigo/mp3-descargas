from __future__ import annotations

import json
import os
from typing import List, Optional

from models.song import DownloadStatus, DownloadTask, Song

HISTORY_DIR = os.path.expanduser("~/.mp3downloader")
HISTORY_FILE = os.path.join(HISTORY_DIR, "history.json")


class HistoryManager:

    @staticmethod
    def load() -> List[DownloadTask]:
        try:
            if not os.path.isfile(HISTORY_FILE):
                return []
            with open(HISTORY_FILE, "r") as f:
                data = json.load(f)
            return [HistoryManager._task_from_dict(d) for d in data]
        except Exception:
            return []

    @staticmethod
    def save(tasks: List[DownloadTask]) -> None:
        try:
            os.makedirs(HISTORY_DIR, exist_ok=True)
            data = [HistoryManager._task_to_dict(t) for t in tasks
                    if t.status in (DownloadStatus.COMPLETED, DownloadStatus.FAILED)]
            with open(HISTORY_FILE, "w") as f:
                json.dump(data, f, indent=2)
        except Exception:
            pass

    @staticmethod
    def _task_to_dict(task: DownloadTask) -> dict:
        return {
            "song": {
                "id": task.song.id,
                "title": task.song.title,
                "artist": task.song.artist,
                "duration": task.song.duration,
                "thumbnailUrl": task.song.thumbnail_url,
            },
            "status": task.status.value,
            "progress": task.progress,
            "outputPath": task.output_path,
            "error": task.error,
        }

    @staticmethod
    def _task_from_dict(d: dict) -> DownloadTask:
        s = d["song"]
        song = Song(
            id=s["id"],
            title=s["title"],
            artist=s["artist"],
            duration=s["duration"],
            thumbnail_url=s.get("thumbnailUrl", ""),
        )
        status = DownloadStatus(d.get("status", DownloadStatus.FAILED.value))
        return DownloadTask(
            song=song,
            status=status,
            progress=d.get("progress", 0.0),
            output_path=d.get("outputPath"),
            error=d.get("error"),
        )
