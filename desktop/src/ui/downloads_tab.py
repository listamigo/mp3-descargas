from __future__ import annotations

from collections import OrderedDict
from typing import Optional

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QHBoxLayout, QLabel, QPushButton, QScrollArea, QVBoxLayout, QWidget

from models.song import DownloadStatus, DownloadTask
from ui.widgets.download_item import DownloadItem


class DownloadsTab(QWidget):
    clear_all_clicked = Signal()

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self._task_widgets: OrderedDict[str, DownloadItem] = OrderedDict()
        self._section_labels: dict[str, QLabel] = {}
        self._setup_ui()

    def _setup_ui(self) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)

        self._header = QWidget()
        header_layout = QHBoxLayout(self._header)
        header_layout.setContentsMargins(12, 8, 12, 8)
        title = QLabel("Descargas")
        title.setObjectName("tabTitle")
        header_layout.addWidget(title)
        header_layout.addStretch()
        self._clear_btn = QPushButton("Limpiar todo")
        self._clear_btn.setObjectName("clearBtn")
        self._clear_btn.clicked.connect(self.clear_all_clicked)
        header_layout.addWidget(self._clear_btn)
        layout.addWidget(self._header)

        self._scroll = QScrollArea()
        self._scroll.setWidgetResizable(True)
        self._scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self._scroll_container = QWidget()
        self._tasks_layout = QVBoxLayout(self._scroll_container)
        self._tasks_layout.setSpacing(0)
        self._tasks_layout.setContentsMargins(0, 0, 0, 0)
        self._tasks_layout.addStretch()
        self._scroll.setWidget(self._scroll_container)
        layout.addWidget(self._scroll)

        self._empty = QLabel("Sin descargas aún.\n¡Busca y descarga tu primera canción!")
        self._empty.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._empty.setStyleSheet("color: #b3b3b3; font-size: 15px; padding: 40px;")
        layout.addWidget(self._empty)

    def set_tasks(self, tasks: list[DownloadTask],
                  on_cancel, on_retry) -> None:
        active = [t for t in tasks if t.status in (DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.CONVERTING)]
        completed = [t for t in tasks if t.status == DownloadStatus.COMPLETED]
        failed = [t for t in tasks if t.status == DownloadStatus.FAILED]

        # Remove old widgets
        for w in self._task_widgets.values():
            self._tasks_layout.removeWidget(w)
            w.deleteLater()
        for lbl in self._section_labels.values():
            self._tasks_layout.removeWidget(lbl)
            lbl.deleteLater()
        self._task_widgets.clear()
        self._section_labels.clear()

        has_any = bool(active or completed or failed)
        self._clear_btn.setVisible(has_any)

        if not has_any:
            self._empty.show()
            self._scroll.hide()
            return

        self._empty.hide()
        self._scroll.show()

        sections = [
            ("Descargas activas", active),
            ("Completadas", completed),
            ("Fallidas", failed),
        ]

        for section_name, section_tasks in sections:
            if not section_tasks:
                continue

            label = QLabel(f"{section_name} ({len(section_tasks)})")
            label.setObjectName("sectionHeader")
            self._tasks_layout.insertWidget(self._tasks_layout.count() - 1, label)
            self._section_labels[section_name] = label

            for task in section_tasks:
                widget = DownloadItem(task)
                widget.cancel_clicked.connect(on_cancel)
                widget.retry_clicked.connect(on_retry)
                self._task_widgets[task.song.id] = widget
                self._tasks_layout.insertWidget(self._tasks_layout.count() - 1, widget)

    def update_task(self, task: DownloadTask) -> None:
        widget = self._task_widgets.get(task.song.id)
        if widget:
            widget.update_task(task)
