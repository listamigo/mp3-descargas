#!/usr/bin/env python3
"""MP3 Downloader — Python Desktop App (PySide6)"""

import sys
import os
import traceback

log_dir = os.path.expanduser("~/.mp3downloader")
os.makedirs(log_dir, exist_ok=True)
log_path = os.path.join(log_dir, "app.log")


def _log_exception(exc_type, exc_value, exc_tb) -> None:
    with open(log_path, "a") as f:
        f.write(f"=== {__import__('datetime').datetime.now()} ===\n")
        traceback.print_exception(exc_type, exc_value, exc_tb, file=f)
        f.write("\n")
    traceback.print_exception(exc_type, exc_value, exc_tb)


sys.excepthook = _log_exception

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication

from ui.main_window import MainWindow


def check_dependencies() -> list[str]:
    import shutil
    missing: list[str] = []
    for cmd in ("yt-dlp", "ffmpeg"):
        if shutil.which(cmd) is None:
            missing.append(cmd)
    return missing


def main() -> None:
    missing = check_dependencies()
    if missing:
        print(f"[ERROR] Falta: {' '.join(missing)} — instala con: sudo apt install {' '.join(missing)}", flush=True)

    from services.server import start_server_thread
    try:
        start_server_thread()
        print("[INFO] API server iniciado en http://0.0.0.0:8899", flush=True)
    except Exception as e:
        print(f"[WARN] No se pudo iniciar el servidor API: {e}", flush=True)

    app = QApplication(sys.argv)
    app.setApplicationName("MP3 Downloader")

    window = MainWindow()
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    with open(log_path, "a") as f:
        f.write(f"=== MP3 Downloader started at {__import__('datetime').datetime.now()} ===\n")
    main()
