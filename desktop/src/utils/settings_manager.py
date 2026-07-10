from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from typing import Optional

SETTINGS_DIR = os.path.expanduser("~/.mp3downloader")
SETTINGS_FILE = os.path.join(SETTINGS_DIR, "settings.json")

DEFAULT_THEME = "dark"


@dataclass
class AppSettings:
    theme: str = DEFAULT_THEME
    wallpaper_path: Optional[str] = None
    wallpaper_opacity: float = 0.8
    language: str = "es"


class SettingsManager:
    _instance: Optional[SettingsManager] = None

    def __new__(cls) -> SettingsManager:
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._settings = AppSettings()
        return cls._instance

    def __init__(self) -> None:
        if not hasattr(self, "_loaded"):
            self._loaded = True
            self.load()

    def load(self) -> AppSettings:
        try:
            if not os.path.isfile(SETTINGS_FILE):
                return self._settings
            with open(SETTINGS_FILE, "r") as f:
                data = json.load(f)
            for key, value in data.items():
                if hasattr(self._settings, key):
                    setattr(self._settings, key, value)
        except Exception:
            pass
        return self._settings

    def save(self) -> None:
        try:
            os.makedirs(SETTINGS_DIR, exist_ok=True)
            with open(SETTINGS_FILE, "w") as f:
                json.dump({
                    "theme": self._settings.theme,
                    "wallpaper_path": self._settings.wallpaper_path,
                    "wallpaper_opacity": self._settings.wallpaper_opacity,
                    "language": self._settings.language,
                }, f, indent=2)
        except Exception:
            pass

    @property
    def settings(self) -> AppSettings:
        return self._settings

    def update(self, **kwargs) -> None:
        for key, value in kwargs.items():
            if hasattr(self._settings, key):
                setattr(self._settings, key, value)
        self.save()
