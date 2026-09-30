"""Ruta de VÍDEO por PROXY, con la vía directa cerrada a propósito.

Es la configuración real de Railway: IP de datacenter, breaker abierto, el
proxy es la única vía. Comprueba la altura REAL del MP4 con ffprobe.
"""
import sys, os, time, logging, subprocess
# Raíz del repo, para que el script funcione desde cualquier cwd
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_ROOT, "server"))
logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
os.environ.setdefault("LOG_DIR", os.path.join(_ROOT, ".tmp-e2e"))
import server as S
import download_engine as de

# Romper el directo como lo rompe YouTube en una IP de datacenter
de.direct_path_available = lambda: False
S.direct_path_available = lambda: False
de.record_direct_failure = lambda: None
S.record_direct_failure = lambda: None

VIDEO = sys.argv[1] if len(sys.argv) > 1 else "cUpOtbCWSRs"
QUALITY = int(sys.argv[2]) if len(sys.argv) > 2 else 1080

class Fake(S.APIHandler):
    def __init__(self): pass
    def _json(self, code, body):
        print(f"!! RESPUESTA {code}: {str(body)[:300]}"); self.code = code
    def _serve_file(self, path, ctype, video_id):
        h = self._video_dimensions(path)
        print(f"*** SERVIDO {path} height={h[1] if h else '?'} size={os.path.getsize(path)}")
        self.served = path
    def log_message(self, *a): pass

h = Fake(); h.served = None; h.code = None
t0 = time.monotonic()
h._proxy_video_download(VIDEO, "test", QUALITY)
print(f"=== listo en {time.monotonic()-t0:.1f}s served={h.served} code={h.code}")
if h.served:
    out = subprocess.run(["ffprobe","-v","error","-select_streams","v:0",
        "-show_entries","stream=width,height,codec_name","-of","csv=p=0",h.served],
        capture_output=True,text=True)
    print("ffprobe:", out.stdout.strip())
