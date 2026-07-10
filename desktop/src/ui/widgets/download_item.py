from __future__ import annotations

from typing import Optional

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QProgressBar,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from models.song import DownloadStatus, DownloadTask
from utils.helpers import open_in_file_manager

STATUS_ICONS = {
    DownloadStatus.COMPLETED: "✓",
    DownloadStatus.FAILED: "✗",
    DownloadStatus.DOWNLOADING: "⬇",
    DownloadStatus.QUEUED: "⏳",
}

STATUS_TEXT = {
    DownloadStatus.IDLE: "Inactivo",
    DownloadStatus.QUEUED: "Esperando...",
    DownloadStatus.DOWNLOADING: "Descargando...",
    DownloadStatus.CONVERTING: "Convirtiendo...",
    DownloadStatus.COMPLETED: "Completado",
    DownloadStatus.FAILED: "Fallido",
}

STATUS_COLOR = {
    DownloadStatus.COMPLETED: "#1DB954",
    DownloadStatus.FAILED: "#E74C3C",
    DownloadStatus.DOWNLOADING: "#b3b3b3",
    DownloadStatus.QUEUED: "#b3b3b3",
    DownloadStatus.CONVERTING: "#b3b3b3",
}

ACTIVE = {DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.CONVERTING}


class DownloadItem(QWidget):
    cancel_clicked = Signal(str)
    retry_clicked = Signal(str)

    def __init__(self, task: DownloadTask, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.task = task
        self._setup_ui()

    def _setup_ui(self) -> None:
        self.setFixedHeight(88)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(12, 8, 12, 8)
        layout.setSpacing(4)

        row = QHBoxLayout()
        row.setSpacing(8)

        self._icon = QLabel()
        self._icon.setFixedWidth(20)
        row.addWidget(self._icon)

        info = QVBoxLayout()
        info.setSpacing(1)

        title = QLabel(self.task.song.title)
        title.setObjectName("titleLabel")
        title.setMaximumHeight(16)
        info.addWidget(title)

        self._status_label = QLabel()
        self._status_label.setStyleSheet("font-size: 11px;")
        info.addWidget(self._status_label)

        row.addLayout(info, 1)

        self._cancel_btn = QPushButton("Cancelar")
        self._cancel_btn.setObjectName("cancelBtn")
        self._cancel_btn.clicked.connect(self._on_cancel)
        row.addWidget(self._cancel_btn)

        self._action_btn = QPushButton()
        self._action_btn.clicked.connect(self._on_action)
        row.addWidget(self._action_btn)

        layout.addLayout(row)

        self._progress_bar = QProgressBar()
        self._progress_bar.setTextVisible(False)
        layout.addWidget(self._progress_bar)

        self._extra_label = QLabel()
        self._extra_label.setMaximumHeight(32)
        layout.addWidget(self._extra_label)

        self._update_ui()

    def _on_cancel(self) -> None:
        self.cancel_clicked.emit(self.task.song.id)

    def _on_action(self) -> None:
        if self.task.status == DownloadStatus.COMPLETED:
            open_in_file_manager(self.task.output_path or "")
        elif self.task.status == DownloadStatus.FAILED:
            self.retry_clicked.emit(self.task.song.id)

    def _update_ui(self) -> None:
        s = self.task.status
        in_active = s in ACTIVE

        bg = "#121212"
        if s == DownloadStatus.COMPLETED:
            bg = "rgba(29, 185, 84, 0.08)"
        elif s == DownloadStatus.FAILED:
            bg = "rgba(231, 76, 60, 0.08)"
        self.setStyleSheet(f"background-color: {bg}; border-radius: 6px;")

        self._icon.setText(STATUS_ICONS.get(s, "♪"))
        self._icon.setStyleSheet(
            f"font-size: 16px; color: {STATUS_COLOR.get(s, '#b3b3b3')};"
        )

        if s == DownloadStatus.DOWNLOADING:
            pct = int(self.task.progress * 100)
            parts = [STATUS_TEXT.get(s, "")]
            if self.task.total_size and self.task.download_speed:
                parts.append(f"{pct}% — {self.task.total_size} a {self.task.download_speed}")
            elif self.task.download_speed:
                parts.append(f"{pct}% — {self.task.download_speed}")
            else:
                parts.append(f"{pct}%")
            self._status_label.setText(" | ".join(parts))
        else:
            self._status_label.setText(STATUS_TEXT.get(s, ""))
        self._status_label.setStyleSheet(
            f"font-size: 11px; color: {STATUS_COLOR.get(s, '#b3b3b3')};"
        )

        self._cancel_btn.setVisible(in_active)

        if s == DownloadStatus.COMPLETED:
            self._action_btn.setText("Abrir")
            self._action_btn.setObjectName("openBtn")
            self._action_btn.setVisible(True)
        elif s == DownloadStatus.FAILED:
            self._action_btn.setText("Reintentar")
            self._action_btn.setObjectName("retryBtn")
            self._action_btn.setVisible(True)
        else:
            self._action_btn.setVisible(False)

        if s == DownloadStatus.DOWNLOADING:
            self._progress_bar.setVisible(True)
            self._progress_bar.setValue(int(self.task.progress * 100))
            self._extra_label.setVisible(False)
        elif s == DownloadStatus.COMPLETED and self.task.output_path:
            self._progress_bar.setVisible(False)
            self._extra_label.setText(self.task.output_path)
            self._extra_label.setObjectName("pathLabel")
            self._extra_label.setVisible(True)
        elif s == DownloadStatus.FAILED and self.task.error:
            self._progress_bar.setVisible(False)
            self._extra_label.setText(self.task.error)
            self._extra_label.setObjectName("errorLabel")
            self._extra_label.setVisible(True)
        else:
            self._progress_bar.setVisible(False)
            self._extra_label.setVisible(False)

    def update_task(self, task: DownloadTask) -> None:
        self.task = task
        self._update_ui()
