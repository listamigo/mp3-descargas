"""Ruta de AUDIO completa con la vía directa cerrada ( Railway real).

20 descargas seguidas, cada una de un vídeo distinto para que la caché de 48 h
no falsee la medición (ver §1 de continuarv2.md).
"""
import sys, os, time, logging, random
# Raíz del repo, para que el script funcione desde cualquier cwd
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_ROOT, "server"))
logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
os.environ.setdefault("LOG_DIR", os.path.join(_ROOT, ".tmp-e2e"))
import server as S, download_engine as de

CERRAR_DIRECTO = os.environ.get("CERRAR_DIRECTO", "1") == "1"
if CERRAR_DIRECTO:
    de.direct_path_available = lambda: False
    S.direct_path_available = lambda: False
    de.record_direct_failure = lambda: None
    S.record_direct_failure = lambda: None

VIDEOS = sys.argv[1:]
class Sink:
    """wfile que se traga los bytes y los cuenta."""
    def __init__(self): self.n = 0
    def write(self, b):
        self.n += len(b); return len(b)
    def flush(self): pass

class Fake(S.APIHandler):
    def __init__(self):
        self.wfile = Sink()
        self.headers_buf = []
    def send_response(self, *a): pass
    def send_header(self, *a): self.headers_buf.append(a)
    def end_headers(self): pass
    def _cors_headers(self): pass
    def _json(self, code, body):
        print(f"  {code}: {str(body)[:160]}"); self.last_code = code
    def log_message(self, *a): pass

ok = fail = 0
for vid in VIDEOS:
    h = Fake(); h.last_code = None
    t0 = time.monotonic()
    h._proxy_download(vid, "t")
    dt = time.monotonic() - t0
    if h.last_code is None:
        print(f"OK  {vid} {h.wfile.n} B en {dt:.1f}s"); ok += 1
    else:
        print(f"FALLO {vid} {dt:.1f}s"); fail += 1
print(f"=== {ok} OK / {fail} fallos")
