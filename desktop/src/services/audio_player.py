from __future__ import annotations

import subprocess
from typing import Optional

from PySide6.QtCore import QObject, QTimer, Signal


class AudioPlayer(QObject):
    playing_changed = Signal(bool)
    error_occurred = Signal(str)

    def __init__(self, parent: Optional[QObject] = None):
        super().__init__(parent)
        self._ytdlp: Optional[subprocess.Popen] = None
        self._ffmpeg: Optional[subprocess.Popen] = None
        self._aplay: Optional[subprocess.Popen] = None
        self._monitor: Optional[QTimer] = None

    def play_preview(self, video_id: str) -> None:
        self.stop()
        video_url = f"https://youtube.com/watch?v={video_id}"
        try:
            self._ytdlp = subprocess.Popen(
                ["yt-dlp", "--no-warnings",
                 "-f", "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio",
                 "-o", "-", video_url],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self._ffmpeg = subprocess.Popen(
                ["ffmpeg", "-y", "-i", "-",
                 "-f", "wav", "-loglevel", "quiet", "-"],
                stdin=self._ytdlp.stdout, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            if self._ytdlp.stdout:
                self._ytdlp.stdout.close()
            self._aplay = subprocess.Popen(
                ["aplay", "-q", "-"],
                stdin=self._ffmpeg.stdout, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            if self._ffmpeg.stdout:
                self._ffmpeg.stdout.close()

            self._monitor = QTimer(self)
            self._monitor.setInterval(1000)
            self._monitor.timeout.connect(self._check_processes)
            self._monitor.start()

            self.playing_changed.emit(True)
        except FileNotFoundError:
            self.error_occurred.emit("Falta: yt-dlp, ffmpeg o aplay (sudo apt install alsa-utils)")
        except Exception as e:
            self.error_occurred.emit(f"Error de previsualización: {e}")

    def stop(self) -> None:
        if self._monitor:
            self._monitor.stop()
            self._monitor = None
        for proc in (self._aplay, self._ffmpeg, self._ytdlp):
            if proc and proc.poll() is None:
                try:
                    proc.terminate()
                    proc.wait(timeout=2)
                except Exception:
                    try:
                        proc.kill()
                    except Exception:
                        pass
        self._aplay = self._ffmpeg = self._ytdlp = None
        self.playing_changed.emit(False)

    def _check_processes(self) -> None:
        if self._aplay is None:
            return
        if self._aplay.poll() is not None:
            if self._monitor:
                self._monitor.stop()
                self._monitor = None
            self._aplay = None
            self.playing_changed.emit(False)
            return
        for name, proc in [("yt-dlp", self._ytdlp), ("ffmpeg", self._ffmpeg)]:
            if proc and proc.poll() is not None and proc.returncode != 0:
                err = ""
                if proc.stderr:
                    try:
                        err = proc.stderr.read().decode("utf-8", errors="replace")[:500]
                    except Exception:
                        pass
                self.error_occurred.emit(f"Error en {name}: {err or f'código {proc.returncode}'}")
                self.stop()
                return

    @property
    def is_playing(self) -> bool:
        return self._aplay is not None and self._aplay.poll() is None
