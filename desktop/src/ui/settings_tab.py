from __future__ import annotations

import os
from typing import Optional

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QComboBox,
    QFileDialog,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QScrollArea,
    QSlider,
    QVBoxLayout,
    QWidget,
)

from utils.settings_manager import SettingsManager


class SettingsTab(QWidget):
    settings_changed = Signal()

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self._settings = SettingsManager()
        self._setup_ui()

    def _setup_ui(self) -> None:
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)

        container = QWidget()
        layout = QVBoxLayout(container)
        layout.setSpacing(8)
        layout.setContentsMargins(24, 16, 24, 16)

        # ── Tema ──
        theme_header = QLabel("Tema")
        theme_header.setObjectName("sectionHeader")
        layout.addWidget(theme_header)

        self._theme_combo = QComboBox()
        self._theme_combo.addItem("Oscuro", "dark")
        self._theme_combo.addItem("Claro", "light")
        self._theme_combo.addItem("Vidrio", "glass")
        self._theme_combo.addItem("Sakura", "sakura")
        idx = self._theme_combo.findData(self._settings.settings.theme)
        if idx >= 0:
            self._theme_combo.setCurrentIndex(idx)
        self._theme_combo.currentIndexChanged.connect(self._on_theme_changed)
        layout.addWidget(self._theme_combo)

        layout.addSpacing(16)

        # ── Wallpaper ──
        wallpaper_header = QLabel("Fondo de pantalla")
        wallpaper_header.setObjectName("sectionHeader")
        layout.addWidget(wallpaper_header)

        wp_row = QHBoxLayout()
        self._wp_path_label = QLabel(
            self._settings.settings.wallpaper_path or "Ninguno seleccionado"
        )
        self._wp_path_label.setStyleSheet("color: #b3b3b3; font-size: 11px;")
        wp_row.addWidget(self._wp_path_label, 1)

        self._wp_btn = QPushButton("Seleccionar imagen…")
        self._wp_btn.setObjectName("downloadBtn")
        self._wp_btn.clicked.connect(self._on_select_wallpaper)
        wp_row.addWidget(self._wp_btn)

        self._wp_clear_btn = QPushButton("Quitar")
        self._wp_clear_btn.setObjectName("cancelBtn")
        self._wp_clear_btn.clicked.connect(self._on_clear_wallpaper)
        wp_row.addWidget(self._wp_clear_btn)

        layout.addLayout(wp_row)

        # ── Opacidad ──
        opacity_row = QHBoxLayout()
        opacity_label = QLabel("Opacidad del contenido:")
        opacity_label.setStyleSheet("color: #b3b3b3; font-size: 12px;")
        opacity_row.addWidget(opacity_label)

        self._opacity_slider = QSlider(Qt.Orientation.Horizontal)
        self._opacity_slider.setRange(20, 100)
        self._opacity_slider.setValue(int(self._settings.settings.wallpaper_opacity * 100))
        self._opacity_slider.valueChanged.connect(self._on_opacity_changed)
        opacity_row.addWidget(self._opacity_slider, 1)

        self._opacity_value = QLabel(f"{self._opacity_slider.value()}%")
        self._opacity_value.setStyleSheet("color: #b3b3b3; font-size: 12px; min-width: 36px;")
        opacity_row.addWidget(self._opacity_value)

        layout.addLayout(opacity_row)

        layout.addSpacing(24)

        # ── Información ──
        info_header = QLabel("Información")
        info_header.setObjectName("sectionHeader")
        layout.addWidget(info_header)

        info_items = [
            ("Aplicación:", "MP3 Downloader"),
            ("Versión:", "1.0.0"),
            ("Desarrollador:", "Eliezer David Malavé"),
            ("Framework:", "Python / PySide6 (Qt6)"),
            ("Motor de descargas:", "yt-dlp + ffmpeg"),
        ]
        for label, value in info_items:
            row = QHBoxLayout()
            lbl = QLabel(label)
            lbl.setStyleSheet("color: #b3b3b3; font-size: 12px; min-width: 120px;")
            val = QLabel(value)
            val.setStyleSheet("color: #e1e1e1; font-size: 12px; font-weight: 500;")
            row.addWidget(lbl)
            row.addWidget(val, 1)
            layout.addLayout(row)

        layout.addStretch()
        scroll.setWidget(container)
        outer.addWidget(scroll)

    def _on_theme_changed(self, index: int) -> None:
        theme = self._theme_combo.itemData(index)
        self._settings.update(theme=theme)
        self.settings_changed.emit()

    def _on_select_wallpaper(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self, "Seleccionar imagen de fondo", "",
            "Imágenes (*.png *.jpg *.jpeg *.bmp *.webp)"
        )
        if path:
            self._settings.update(wallpaper_path=path)
            self._wp_path_label.setText(os.path.basename(path))
            self.settings_changed.emit()

    def _on_clear_wallpaper(self) -> None:
        self._settings.update(wallpaper_path=None)
        self._wp_path_label.setText("Ninguno seleccionado")
        self.settings_changed.emit()

    def _on_opacity_changed(self, value: int) -> None:
        opacity = value / 100.0
        self._settings.update(wallpaper_opacity=opacity)
        self._opacity_value.setText(f"{value}%")
        self.settings_changed.emit()
