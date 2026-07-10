from __future__ import annotations

from typing import Optional

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QWidget

from models.song import Song


class SongItem(QWidget):
    play_clicked = Signal(Song)
    download_clicked = Signal(Song)

    def __init__(self, song: Song, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.song = song
        self._setup_ui()

    def _setup_ui(self) -> None:
        layout = QHBoxLayout(self)
        layout.setContentsMargins(8, 4, 8, 4)

        self._thumb = QLabel()
        self._thumb.setFixedSize(52, 52)
        self._thumb.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._thumb.setStyleSheet("background-color: #282828; border-radius: 4px;")
        layout.addWidget(self._thumb)

        info_layout = QVBoxLayout()
        info_layout.setSpacing(2)

        title = QLabel(self.song.title)
        title.setObjectName("titleLabel")
        title.setMaximumHeight(18)
        info_layout.addWidget(title)

        meta_layout = QHBoxLayout()
        meta_layout.setSpacing(8)

        artist = QLabel(self.song.artist)
        artist.setObjectName("artistLabel")
        meta_layout.addWidget(artist)

        dur = QLabel(self.song.formatted_duration())
        dur.setObjectName("durationLabel")
        meta_layout.addWidget(dur)
        meta_layout.addStretch()

        info_layout.addLayout(meta_layout)
        layout.addLayout(info_layout, 1)

        self._play_btn = QPushButton()
        self._play_btn.setObjectName("playBtn")
        self._play_btn.setFixedSize(32, 32)
        self._play_btn.clicked.connect(lambda: self.play_clicked.emit(self.song))
        layout.addWidget(self._play_btn)

        dl_btn = QPushButton("Descargar")
        dl_btn.setObjectName("downloadBtn")
        dl_btn.clicked.connect(lambda: self.download_clicked.emit(self.song))
        layout.addWidget(dl_btn)

    def set_thumbnail(self, pixmap: Optional[QPixmap]) -> None:
        if pixmap:
            self._thumb.setPixmap(pixmap)
            self._thumb.setStyleSheet("border-radius: 4px;")
        else:
            self._thumb.setText("♪")
            self._thumb.setStyleSheet(
                "background-color: #282828; border-radius: 4px; font-size: 20px; color: #1DB954;"
            )

    def set_play_icon(self, is_playing: bool) -> None:
        self._play_btn.setText("⏹" if is_playing else "▶")
        color = "#E74C3C" if is_playing else "#1DB954"
        self._play_btn.setStyleSheet(
            f"background-color: transparent; border: none; font-size: 16px; color: {color};"
        )
