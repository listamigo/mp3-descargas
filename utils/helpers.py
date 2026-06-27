from typing import Optional


def format_duration(seconds: int) -> str:
    minutes = seconds // 60
    secs = seconds % 60
    return f"{minutes}:{secs:02d}"


def sanitize_filename(name: str) -> str:
    import re
    return re.sub(r'[/\\:*?"<>|]', "_", name)


def get_output_directory() -> str:
    import os
    home = os.path.expanduser("~")
    download_dir = os.path.join(home, "Downloads")
    if os.path.isdir(download_dir):
        return download_dir
    return home


def open_in_file_manager(path: str) -> None:
    import subprocess
    import os
    try:
        parent = os.path.dirname(path)
        subprocess.Popen(["xdg-open", parent])
    except Exception:
        pass
