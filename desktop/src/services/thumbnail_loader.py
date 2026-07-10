from __future__ import annotations

import os
import tempfile
import urllib.request
from typing import Optional

from PySide6.QtCore import QObject, QThread, Signal
from PySide6.QtGui import QPixmap

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)


class ThumbnailWorker(QThread):
    thumb_finished = Signal(str)
    error = Signal(str, str)

    def __init__(self, song_id: str, url: str, output_path: str):
        super().__init__()
        self._song_id = song_id
        self._url = url
        self._output_path = output_path

    def run(self) -> None:
        try:
            req = urllib.request.Request(self._url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = resp.read()
            with open(self._output_path, "wb") as f:
                f.write(data)
            self.thumb_finished.emit(self._song_id)
        except Exception as e:
            self.error.emit(self._song_id, str(e))


class ThumbnailLoader(QObject):
    thumbnail_loaded = Signal(str, QPixmap)

    def __init__(self, parent: Optional[QObject] = None):
        super().__init__(parent)
        self._cache_dir = os.path.join(
            tempfile.gettempdir(), "mp3downloader_thumbs"
        )
        os.makedirs(self._cache_dir, exist_ok=True)
        self._pending: dict[str, ThumbnailWorker] = {}
        self._pixmaps: dict[str, QPixmap] = {}

    def load(self, song_id: str, url: str) -> None:
        if not song_id or not url:
            return
        if song_id in self._pixmaps:
            self.thumbnail_loaded.emit(song_id, self._pixmaps[song_id])
            return
        if song_id in self._pending:
            return
        thumb_path = os.path.join(self._cache_dir, f"{song_id}.jpg")
        if os.path.isfile(thumb_path):
            pixmap = QPixmap(thumb_path)
            if not pixmap.isNull():
                pixmap = pixmap.scaled(52, 52)
                self._pixmaps[song_id] = pixmap
                self.thumbnail_loaded.emit(song_id, pixmap)
                return

        worker = ThumbnailWorker(song_id, url, thumb_path)
        worker.thumb_finished.connect(self._on_thumbnail_ready)
        worker.error.connect(self._on_thumbnail_error)
        worker.finished.connect(lambda: self._pending.pop(song_id, None))
        self._pending[song_id] = worker
        worker.start()

    def _on_thumbnail_ready(self, song_id: str) -> None:
        thumb_path = os.path.join(self._cache_dir, f"{song_id}.jpg")
        if os.path.isfile(thumb_path):
            pixmap = QPixmap(thumb_path)
            if not pixmap.isNull():
                pixmap = pixmap.scaled(52, 52)
                self._pixmaps[song_id] = pixmap
                self.thumbnail_loaded.emit(song_id, pixmap)

    def _on_thumbnail_error(self, song_id: str, error: str) -> None:
        pass
