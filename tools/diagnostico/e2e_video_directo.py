"""Prueba de extremo a extremo de la ruta de VÍDEO por proxy, sin HTTP.

Llama a la lógica real de _proxy_video_download con un cliente falso que
escribe a disco, y comprueba la altura REAL del MP4 con ffprobe.
"""
import sys, os, time, logging, subprocess, tempfile, shutil
# Raíz del repo, para que el script funcione desde cualquier cwd
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_ROOT, "server"))
logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
os.environ.setdefault("LOG_DIR", os.path.join(_ROOT, ".tmp-e2e"))

import server as S
import download_engine as de

VIDEO = sys.argv[1] if len(sys.argv) > 1 else "cUpOtbCWSRs"
QUALITY = int(sys.argv[2]) if len(sys.argv) > 2 else 1080

class Fake(S.APIHandler):
    def __init__(self): pass
    def _json(self, code, body):
        print(f"!! RESPUESTA {code}: {str(body)[:200]}")
        self.code = code
    def _serve_file(self, path, ctype, video_id):
        h = self._video_dimensions(path)
        size = os.path.getsize(path)
        print(f"*** SERVIDO {path} height={h[1] if h else '?'} size={size}")
        self.served = path
    def log_message(self, *a): pass
    # sin red: el cliente http no hace falta para esta ruta
    def wfile(self): raise AssertionError

h = Fake()
h.served = None; h.code = None
# _proxy_video_download escribe por self.wfile a través de _serve_file, que
# estáis sobreescrito, así que no se toca la conexión.
t0 = time.monotonic()
h._proxy_video_download(VIDEO, "test", QUALITY)
dt = time.monotonic() - t0
print(f"=== listo en {dt:.1f}s served={h.served} code={h.code}")
if h.served:
    out = subprocess.run(["ffprobe","-v","error","-select_streams","v:0",
                          "-show_entries","stream=width,height,codec_name",
                          "-of","csv=p=0",h.served],capture_output=True,text=True)
    print("ffprobe:", out.stdout.strip(), out.stderr[:200])
