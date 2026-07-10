from __future__ import annotations

from typing import Optional

from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QScrollArea,
    QVBoxLayout,
    QWidget,
)

from models.song import Song
from services.thumbnail_loader import ThumbnailLoader
from ui.widgets.song_item import SongItem


class SearchTab(QWidget):
    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self._thumbnail_loader = ThumbnailLoader(self)
        self._thumbnail_loader.thumbnail_loaded.connect(self._on_thumbnail)
        self._song_widgets: dict[str, SongItem] = {}
        self._playing_song_id: Optional[str] = None
        self._setup_ui()

    def _setup_ui(self) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        search_row = QHBoxLayout()
        search_row.setContentsMargins(12, 8, 12, 8)

        self._input = QLineEdit()
        self._input.setPlaceholderText("Buscar canciones, artistas o videos...")
        self._input.returnPressed.connect(self._on_search)
        search_row.addWidget(self._input)

        self._search_btn = QPushButton("Buscar")
        self._search_btn.setObjectName("searchBtn")
        self._search_btn.clicked.connect(self._on_search)
        search_row.addWidget(self._search_btn)

        layout.addLayout(search_row)

        self._scroll = QScrollArea()
        self._scroll.setWidgetResizable(True)
        self._scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self._scroll_container = QWidget()
        self._results_layout = QVBoxLayout(self._scroll_container)
        self._results_layout.setSpacing(0)
        self._results_layout.setContentsMargins(0, 0, 0, 0)
        self._results_layout.addStretch()

        self._load_more_btn = QPushButton("Cargar más")
        self._load_more_btn.setObjectName("loadMoreBtn")
        self._load_more_btn.setFixedHeight(36)
        self._load_more_btn.clicked.connect(self._on_load_more)
        self._load_more_btn.hide()
        self._results_layout.addWidget(self._load_more_btn)

        self._scroll.setWidget(self._scroll_container)
        layout.addWidget(self._scroll)

        self._empty_label = QLabel("Busca música para descargar")
        self._empty_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._empty_label.setStyleSheet("color: #b3b3b3; font-size: 15px; padding: 40px;")
        layout.addWidget(self._empty_label)
        self._empty_label.show()
        self._scroll.hide()

    def on_search_query_change(self, text: str) -> None:
        self._input.setText(text)

    def set_searching(self, searching: bool) -> None:
        self._search_btn.setEnabled(not searching)
        self._input.setEnabled(not searching)
        if searching:
            self._search_btn.setText("Buscando...")
        else:
            self._search_btn.setText("Buscar")

    def display_results(self, songs: list[Song]) -> None:
        for w in self._song_widgets.values():
            self._results_layout.removeWidget(w)
            w.deleteLater()
        self._song_widgets.clear()

        if not songs:
            self._empty_label.setText("No results found")
            self._empty_label.show()
            self._scroll.hide()
            self._load_more_btn.hide()
            return

        self._empty_label.hide()
        self._scroll.show()

        for song in songs:
            widget = SongItem(song)
            widget.play_clicked.connect(self._on_play_clicked)
            widget.download_clicked.connect(self._on_download_clicked)
            self._song_widgets[song.id] = widget
            self._results_layout.insertWidget(self._results_layout.count() - 1, widget)

            if song.thumbnail_url:
                self._thumbnail_loader.load(song.id, song.thumbnail_url)

        self._load_more_btn.show()

    def append_results(self, songs: list[Song]) -> None:
        for song in songs:
            widget = SongItem(song)
            widget.play_clicked.connect(self._on_play_clicked)
            widget.download_clicked.connect(self._on_download_clicked)
            self._song_widgets[song.id] = widget
            self._results_layout.insertWidget(self._results_layout.count() - 1, widget)
            if song.thumbnail_url:
                self._thumbnail_loader.load(song.id, song.thumbnail_url)
        self.set_play_state(self._playing_song_id)

    def set_load_more_visible(self, visible: bool) -> None:
        self._load_more_btn.setVisible(visible)

    def set_play_state(self, playing_song_id: Optional[str]) -> None:
        self._playing_song_id = playing_song_id
        for sid, widget in self._song_widgets.items():
            widget.set_play_icon(sid == playing_song_id)

    def set_preview_loading(self, song_id: Optional[str]) -> None:
        for sid, widget in self._song_widgets.items():
            if sid == song_id:
                widget.set_play_icon(False)

    # Signals to connect externally
    search_requested = None
    play_requested = None
    download_requested = None
    load_more_requested = None

    def connect_signals(
        self, on_search, on_play, on_download, on_load_more=None
    ) -> None:
        self.search_requested = on_search
        self.play_requested = on_play
        self.download_requested = on_download
        self.load_more_requested = on_load_more

    def _on_search(self) -> None:
        text = self._input.text().strip()
        if text and self.search_requested:
            self.search_requested(text)

    def _on_load_more(self) -> None:
        text = self._input.text().strip()
        if text and self.load_more_requested:
            self.load_more_requested(text)

    def _on_play_clicked(self, song: Song) -> None:
        if self.play_requested:
            self.play_requested(song)

    def _on_download_clicked(self, song: Song) -> None:
        if self.download_requested:
            self.download_requested(song)

    def _on_thumbnail(self, song_id: str, pixmap: QPixmap) -> None:
        widget = self._song_widgets.get(song_id)
        if widget:
            widget.set_thumbnail(pixmap)
