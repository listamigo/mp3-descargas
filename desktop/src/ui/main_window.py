from __future__ import annotations

import os
import subprocess
from typing import Optional

from PySide6.QtCore import Qt, QThread, QTimer, Signal
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QLabel,
    QMainWindow,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from models.song import DownloadStatus, DownloadTask, Song
from services.audio_player import AudioPlayer
from services.download_engine import DownloadEngine
from services.history_manager import HistoryManager
from ui.downloads_tab import DownloadsTab
from ui.search_tab import SearchTab
from ui.settings_tab import SettingsTab
from ui.themes import get_theme_qss
from utils.helpers import get_output_directory
from utils.settings_manager import SettingsManager


class SearchWorker(QThread):
    search_finished = Signal(list)
    error = Signal(str)

    def __init__(self, engine: DownloadEngine, query: str, max_results: int = 20):
        super().__init__()
        self._engine = engine
        self._query = query
        self._max_results = max_results

    def run(self) -> None:
        try:
            songs = self._engine.search(self._query, max_results=self._max_results)
            self.search_finished.emit(songs)
        except Exception as e:
            self.error.emit(str(e))


class DownloadWorker(QThread):
    progress = Signal(str, object, float, str, str)
    download_complete = Signal(str, object, str)

    def __init__(self, engine: DownloadEngine, song: Song, output_dir: str):
        super().__init__()
        self._engine = engine
        self._song = song
        self._output_dir = output_dir
        self._cancelled = False
        self._process: Optional[subprocess.Popen] = None

    def run(self) -> None:
        def on_progress(status: DownloadStatus, progress: float, speed: str = "", total: str = ""):
            self.progress.emit(self._song.id, status, progress, speed, total)

        def on_complete(output_path: Optional[str], error: Optional[str]):
            self.download_complete.emit(
                self._song.id,
                DownloadStatus.COMPLETED if output_path else DownloadStatus.FAILED,
                output_path or (error or "Error desconocido"),
            )

        def track_process(p: subprocess.Popen) -> None:
            self._process = p

        self._engine.download(
            self._song,
            self._output_dir,
            on_progress=on_progress,
            on_complete=on_complete,
            cancel_flag=lambda: self._cancelled,
            process_tracker=track_process,
        )

    def cancel(self) -> None:
        self._cancelled = True
        if self._process and self._process.poll() is None:
            try:
                self._process.terminate()
            except Exception:
                pass


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self._settings = SettingsManager()
        self._engine = DownloadEngine()
        self._audio_player = AudioPlayer(self)
        self._history = HistoryManager()
        self._downloads: list[DownloadTask] = []
        self._search_results: list[Song] = []
        self._seen_ids: set[str] = set()
        self._current_query: str = ""
        self._playing_song_id: Optional[str] = None
        self._download_workers: dict[str, DownloadWorker] = {}
        self._workers: list[QThread] = []
        self._setup_ui()
        self._load_history()
        self._connect_signals()
        self._apply_settings()

    def _setup_ui(self) -> None:
        self.setWindowTitle("MP3 Downloader")
        self.setMinimumSize(960, 680)

        container = QWidget()
        self._container_layout = QVBoxLayout(container)
        self._container_layout.setContentsMargins(0, 0, 0, 0)

        self._wallpaper_label = QLabel(container)
        self._wallpaper_label.setScaledContents(True)
        self._wallpaper_label.setAttribute(
            Qt.WidgetAttribute.WA_TransparentForMouseEvents
        )
        self._wallpaper_label.lower()
        self._wallpaper_label.hide()

        self._overlay = QWidget(container)
        self._overlay.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self._overlay.setStyleSheet("background-color: rgba(0,0,0,0);")
        self._overlay.lower()
        self._overlay.hide()

        self._tabs = QTabWidget()
        self._search_tab = SearchTab()
        self._downloads_tab = DownloadsTab()
        self._settings_tab = SettingsTab()
        self._tabs.addTab(self._search_tab, "Buscar")
        self._tabs.addTab(self._downloads_tab, "Descargas")
        self._tabs.addTab(self._settings_tab, "Configuración")

        self._container_layout.addWidget(self._tabs)
        self.setCentralWidget(container)

    def _connect_signals(self) -> None:
        self._search_tab.connect_signals(
            on_search=self._on_search,
            on_play=self._on_toggle_preview,
            on_download=self._on_download,
            on_load_more=self._on_load_more,
        )
        self._audio_player.playing_changed.connect(self._on_playing_changed)
        self._audio_player.error_occurred.connect(self._on_preview_error)
        self._settings_tab.settings_changed.connect(self._apply_settings)
        self._downloads_tab.clear_all_clicked.connect(self._on_clear_downloads)

    def _apply_settings(self) -> None:
        s = self._settings.settings
        qss = get_theme_qss(s.theme)
        self.setStyleSheet(qss)

        if s.wallpaper_path and os.path.isfile(s.wallpaper_path):
            pixmap = QPixmap(s.wallpaper_path)
            if not pixmap.isNull():
                self._wallpaper_label.setPixmap(pixmap)
                self._wallpaper_label.setFixedSize(self.size())
                self._wallpaper_label.show()
                self._wallpaper_label.lower()

                alpha = int((1.0 - s.wallpaper_opacity) * 255)
                self._overlay.setStyleSheet(
                    f"background-color: rgba(0, 0, 0, {alpha});"
                )
                self._overlay.setFixedSize(self.size())
                self._overlay.show()
                self._overlay.raise_()
                self._overlay.stackUnder(self._tabs)
                return

        self._wallpaper_label.hide()
        self._overlay.hide()

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self._wallpaper_label.setFixedSize(self.size())
        self._overlay.setFixedSize(self.size())

    def show_snackbar(self, message: str) -> None:
        self.statusBar().showMessage(message, 3000)

    def _load_history(self) -> None:
        tasks = self._history.load()
        if tasks:
            self._downloads = tasks
            self._downloads_tab.set_tasks(
                self._downloads,
                on_cancel=self._on_cancel_download,
                on_retry=self._on_retry_download,
            )

    def _save_history(self) -> None:
        QTimer.singleShot(500, lambda: self._history.save(self._downloads))

    def _track_worker(self, worker: QThread) -> None:
        self._workers.append(worker)
        worker.finished.connect(lambda w=worker: self._unref_worker(w))

    def _unref_worker(self, worker: QThread) -> None:
        try:
            self._workers.remove(worker)
        except ValueError:
            pass

    def _on_search(self, query: str) -> None:
        self._current_query = query
        self._search_results = []
        self._seen_ids = set()
        self._search_tab.set_searching(True)
        worker = SearchWorker(self._engine, query, max_results=20)
        worker.search_finished.connect(self._on_search_results)
        worker.error.connect(self._on_search_error)
        self._track_worker(worker)
        worker.start()

    def _on_search_results(self, songs: list[Song]) -> None:
        self._search_results = songs
        self._seen_ids = {s.id for s in songs}
        self._search_tab.set_searching(False)
        self._search_tab.display_results(songs)
        self._search_tab.set_play_state(self._playing_song_id)
        if not songs:
            self.show_snackbar("Sin resultados")

    def _on_search_error(self, error: str) -> None:
        self._search_tab.set_searching(False)
        self.show_snackbar(f"Error de búsqueda: {error}")

    def _on_playing_changed(self, playing: bool) -> None:
        if not playing:
            self._playing_song_id = None
            self._search_tab.set_play_state(None)

    def _on_preview_error(self, message: str) -> None:
        self._playing_song_id = None
        self._search_tab.set_play_state(None)
        self.show_snackbar(f"Error de previsualización: {message}")

    def _on_load_more(self, query: str) -> None:
        current_count = len(self._search_results)
        self._search_tab.set_load_more_visible(False)
        worker = SearchWorker(self._engine, query, max_results=current_count + 20)
        worker.search_finished.connect(self._on_load_more_results)
        worker.error.connect(self._on_search_error)
        self._track_worker(worker)
        worker.start()

    def _on_load_more_results(self, songs: list[Song]) -> None:
        new_songs = [s for s in songs if s.id not in self._seen_ids]
        if new_songs:
            for s in new_songs:
                self._seen_ids.add(s.id)
            self._search_results.extend(new_songs)
            self._search_tab.append_results(new_songs)
        else:
            self.show_snackbar("No hay más resultados")
        self._search_tab.set_load_more_visible(True)

    def _on_toggle_preview(self, song: Song) -> None:
        if self._playing_song_id == song.id and self._audio_player.is_playing:
            self._audio_player.stop()
            self._playing_song_id = None
            self._search_tab.set_play_state(None)
            return

        self._audio_player.stop()
        self._playing_song_id = None
        self._search_tab.set_play_state(None)

        self._start_preview(song)

    def _start_preview(self, song: Song) -> None:
        self._audio_player.play_preview(song.id)
        self._playing_song_id = song.id
        self._search_tab.set_play_state(song.id)
        QTimer.singleShot(3000, lambda: self._check_preview_ready(song.id))

    def _check_preview_ready(self, song_id: str) -> None:
        if self._playing_song_id != song_id:
            return
        if not self._audio_player.is_playing:
            self._playing_song_id = None
            self._search_tab.set_play_state(None)

    def _on_download(self, song: Song) -> None:
        existing = next((t for t in self._downloads if t.song.id == song.id and t.status in (
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.CONVERTING)), None)
        if existing:
            return
        self._downloads = [t for t in self._downloads if t.song.id != song.id]
        task = DownloadTask(song=song, status=DownloadStatus.QUEUED)
        self._downloads.insert(0, task)
        self._tabs.setCurrentIndex(1)
        self._downloads_tab.set_tasks(
            self._downloads,
            on_cancel=self._on_cancel_download,
            on_retry=self._on_retry_download,
        )
        self._start_download(task)

    def _start_download(self, task: DownloadTask) -> None:
        output_dir = get_output_directory()
        worker = DownloadWorker(self._engine, task.song, output_dir)
        self._download_workers[task.song.id] = worker

        def on_progress(song_id: str, status: DownloadStatus, progress: float, speed: str, total: str):
            for t in self._downloads:
                if t.song.id == song_id:
                    t.status = status
                    t.progress = progress
                    t.download_speed = speed
                    t.total_size = total
                    self._downloads_tab.update_task(t)
                    break

        def on_complete(song_id: str, status: DownloadStatus, result: str):
            for t in self._downloads:
                if t.song.id == song_id:
                    t.status = status
                    if status == DownloadStatus.COMPLETED:
                        t.output_path = result
                        self.show_snackbar(f"Descargado: {t.song.title}")
                    else:
                        t.error = result
                        self.show_snackbar(f"Error al descargar: {result}")
                    break
            self._download_workers.pop(song_id, None)
            self._downloads_tab.set_tasks(
                self._downloads,
                on_cancel=self._on_cancel_download,
                on_retry=self._on_retry_download,
            )
            self._save_history()

        worker.progress.connect(on_progress)
        worker.download_complete.connect(on_complete)
        self._track_worker(worker)
        worker.start()

    def _on_cancel_download(self, song_id: str) -> None:
        worker = self._download_workers.get(song_id)
        if worker:
            worker.cancel()
        for t in self._downloads:
            if t.song.id == song_id:
                t.status = DownloadStatus.FAILED
                t.error = "Cancelado"
                break
        self._downloads.sort(key=lambda t: (
            t.status not in (DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.CONVERTING),
            t.status == DownloadStatus.FAILED,
        ))
        self._downloads_tab.set_tasks(
            self._downloads,
            on_cancel=self._on_cancel_download,
            on_retry=self._on_retry_download,
        )
        self.show_snackbar("Descarga cancelada")

    def _on_retry_download(self, song_id: str) -> None:
        task = next((t for t in self._downloads if t.song.id == song_id), None)
        if task:
            self._downloads = [t for t in self._downloads if t.song.id != song_id]
            self._on_download(task.song)

    def _on_clear_downloads(self) -> None:
        self._downloads.clear()
        self._downloads_tab.set_tasks(
            self._downloads,
            on_cancel=self._on_cancel_download,
            on_retry=self._on_retry_download,
        )
        self._save_history()
