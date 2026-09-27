"""Descarga REAL de vídeo por proxy con el comando exacto de `_proxy_cmd_video`.

Reproduce el rung de la escalera y mide la altura real del MP4 resultante.
Uso: python3 descarga.py <proxy> <videoId> <quality> <clients>
"""
import glob
import os
import subprocess
import sys
import tempfile
import time

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
PROXY, VIDEO, Q, CLIENTS = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

SEL = (f"bv[height<={Q}][ext=mp4][vcodec^=avc1]+ba[ext=m4a]"
       f"/bv[height<={Q}][ext=mp4]+ba[ext=m4a]"
       f"/bv[height<={Q}]+ba"
       f"/b[height<={Q}]/b")

wd = tempfile.mkdtemp(prefix="px_")
cmd = ["yt-dlp", "--no-warnings", "--proxy", PROXY, "--user-agent", UA,
       "--extractor-args", "youtube:player_client=" + CLIENTS,
       "-f", SEL, "--merge-output-format", "mp4", "--no-playlist", "--no-part",
       "--extractor-retries", "1", "--retries", "3", "--socket-timeout", "20",
       "-o", os.path.join(wd, "v.%(ext)s"),
       f"https://youtube.com/watch?v={VIDEO}"]
print("clients:", CLIENTS, flush=True)
t0 = time.time()
try:
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=420)
    rc, err = p.returncode, p.stderr
except subprocess.TimeoutExpired:
    rc, err = -1, "TIMEOUT 420s"
dt = time.time() - t0

mp4 = [f for f in glob.glob(os.path.join(wd, "*")) if f.endswith(".mp4")
       and os.path.getsize(f) > 1024]
if mp4:
    sz = os.path.getsize(mp4[0])
    fp = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0",
                         "-show_entries", "stream=width,height,codec_name",
                         "-of", "csv=p=0", mp4[0]], capture_output=True, text=True)
    print(f"RESULTADO  {dt:5.1f}s  bytes={sz}  video={fp.stdout.strip()}  "
          f"{'OK' if sz > 0 else ''}")
else:
    print(f"SIN FICHERO  {dt:5.1f}s  rc={rc}")
    for l in (err or "").splitlines()[-4:]:
        print("   ", l[:130])
