from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


class DownloadStatus(Enum):
    IDLE = "idle"
    QUEUED = "queued"
    DOWNLOADING = "downloading"
    CONVERTING = "converting"
    COMPLETED = "completed"
    FAILED = "failed"


@dataclass
class Song:
    id: str
    title: str
    artist: str
    duration: int
    thumbnail_url: str
    audio_url: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "title": self.title,
            "artist": self.artist,
            "duration": self.duration,
            "thumbnailUrl": self.thumbnail_url,
            "audioUrl": self.audio_url,
        }

    def formatted_duration(self) -> str:
        minutes = self.duration // 60
        seconds = self.duration % 60
        return f"{minutes}:{seconds:02d}"


@dataclass
class DownloadTask:
    song: Song
    status: DownloadStatus = DownloadStatus.IDLE
    progress: float = 0.0
    download_speed: str = ""
    total_size: str = ""
    output_path: Optional[str] = None
    error: Optional[str] = None

    @property
    def is_active(self) -> bool:
        return self.status in (DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED)

    @property
    def is_finished(self) -> bool:
        return self.status in (DownloadStatus.COMPLETED, DownloadStatus.FAILED)
