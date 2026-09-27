"""Banco de pruebas del fix del techo de 360p, sin red.

Reproduce la decisión de `_proxy_video_download` con las descargas simuladas:
se comprueba que un resultado por debajo de la calidad pedida NO se sirve
mientras queden intentos, y que al agotarlos se sirve el mejor disponible
(nunca un 502 donde antes había un 200).

No toca el repo: solo importa server.py y monkeypatchea lo de red.
"""
import importlib.util
import os
import shutil
import sys
import tempfile
import types

# El repo se deduce de donde vive este script, para que el banco se pueda
# ejecutar desde cualquier clon y sin editar nada.
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "server"))

# download_engine se importa tal cual (no se toca).
WORK = tempfile.mkdtemp(prefix="mp3test_")
os.environ["LOG_DIR"] = WORK
os.environ["COOKIES_FILE"] = os.path.join(WORK, "cookies", "cookies.txt")
os.makedirs(WORK, exist_ok=True)

spec = importlib.util.spec_from_file_location(
    "srv", os.path.join(REPO, "server", "server.py"))
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)

H = srv.APIHandler

# Alturas que "trae" cada descarga simulada, en orden de llamada.
SCRIPT = {"heights": [], "calls": 0}
SERVED = []


class Fake:
    """Sustituto de APIHandler con solo lo que toca la ruta de vídeo."""

    VIDEO_MAX_BYTES = 1073741824
    DOWNLOAD_CACHE_MAX_FILES = 30
    DOWNLOAD_CACHE_MAX_TOTAL_BYTES = 10 ** 12
    _SIZE_ESTIMATE_CACHE = {}
    SIZE_ESTIMATE_TTL_S = 300
    SIZE_ESTIMATE_TTL_FAIL_S = 30

    _get_download_path = H._get_download_path
    _merged_video_in = H._merged_video_in
    _enforce_video_size = H._enforce_video_size
    _video_dimensions = H.__dict__["_video_dimensions"]
    _discard = H.__dict__["_discard"]
    _reject_too_big = H._reject_too_big
    _keep_as_fallback = H._keep_as_fallback
    _proxy_video_download = H._proxy_video_download
    _video_format_selector = H._video_format_selector

    def _video_dimensions(self, path):
        # La altura va DENTRO del fichero simulado. Leerla por contador de
        # llamadas daba valores equivocados al comparar los candidatos a
        # fallback, que es justo lo que el caso 5 comprueba.
        try:
            with open(path, "rb") as fh:
                return (640, int(fh.read(4).decode()))
        except (OSError, ValueError):
            return None

    def _file_height(self, path):
        d = self._video_dimensions(path)
        return d[1] if d else 0

    def _video_size_estimate(self, *a, **k):
        return None

    def _cleanup_download_cache(self):
        pass

    def _json(self, status, data):
        SERVED.append(("json", status, data.get("error", "")))

    def _serve_file(self, path, ctype, vid):
        h = self._file_height(path)
        SERVED.append(("file", 200, h, os.path.getsize(path)))
        if os.path.isfile(path):
            os.remove(path)
        return True


def run_case(name, heights, quality=1080, max_attempts=3, direct=False):
    """heights: altura que devuelve cada descarga, en orden."""
    SCRIPT["heights"] = heights
    SCRIPT["calls"] = 0
    del SERVED[:]
    f = Fake()
    work = os.path.join(WORK, "cache")
    os.makedirs(work, exist_ok=True)
    for n in os.listdir(work):
        os.remove(os.path.join(work, n))
    f.DOWNLOAD_CACHE_DIR = work

    orig_mkdtemp = tempfile.mkdtemp

    def mkdtemp(prefix="", **k):
        return orig_mkdtemp(prefix=prefix, dir=work)

    srv.tempfile.mkdtemp = mkdtemp

    def fake_subprocess_run(cmd, **kw):
        h = SCRIPT["heights"][min(SCRIPT["calls"], len(SCRIPT["heights"]) - 1)]
        SCRIPT["calls"] += 1
        wd = os.path.dirname([a for a in cmd if a.endswith("v.%(ext)s")][0])
        p = os.path.join(wd, "v.mp4")
        with open(p, "wb") as fh:
            fh.write(("%04d" % h).encode() + b"\0" * 4096)
        return types.SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

    srv.subprocess.run = fake_subprocess_run
    srv._find_working_proxy = lambda vid, blocked=None: (
        f"socks5://fake{SCRIPT['calls']}:1080")
    srv.is_video_level_error = lambda t: False
    srv.record_direct_failure = lambda: None
    srv.direct_path_available = (lambda: True) if direct else (lambda: False)
    srv._base_cmd = lambda c: ["yt-dlp", "--no-warnings"]
    try:
        f._proxy_video_download("VIDtest", "t", quality)
    finally:
        srv.tempfile.mkdtemp = orig_mkdtemp
    got = SERVED[-1] if SERVED else None
    print(f"{name}")
    print(f"   descargas simuladas: {SCRIPT['calls']}   alturas: {heights}")
    print(f"   servido: {got}")
    return got


print("=" * 72)
print("FIX DEL TECHO DE 360p — banco de pruebas sin red")
print("=" * 72)

# 1. El caso que motivó el arreglo: el primer resultado es 360p.
g = run_case("1) 360p a la primera, 1080p a la segunda -> debe servir 1080p",
             [360, 1080])
assert g and g[0] == "file" and g[2] == 1080, f"FALLO: {g}"

# 2. Varios 360p seguidos y luego 1080p: debe seguir insistiendo.
g = run_case("2) 360p, 360p, 1080p -> debe servir 1080p", [360, 360, 1080])
assert g and g[0] == "file" and g[2] == 1080, f"FALLO: {g}"

# 3. Todo 360p: NO debe ser 502, debe servir lo mejor (360p) y pararse.
g = run_case("3) siempre 360p -> debe servir 360p, nunca 502", [360, 360, 360])
assert g and g[0] == "file" and g[2] == 360, f"FALLO: {g}"

# 4. 1080p a la primera: no debe reintentar por nada.
g = run_case("4) 1080p a la primera -> 1 sola descarga", [1080])
assert g and g[0] == "file" and g[2] == 1080, f"FALLO: {g}"
assert SCRIPT["calls"] == 1, f"FALLO: reintentó aunque acertó: {SCRIPT['calls']}"

# 5. Se queda con el MEJOR de los cortos, no con el último.
g = run_case("5) 480p, 360p, 360p -> debe quedarse con el 480p", [480, 360, 360])
assert g and g[0] == "file" and g[2] == 480, f"FALLO: {g}"

# 6. Pedir 360p con 360p disponible: se sirve sin insistir.
g = run_case("6) calidad=360 y llega 360p -> sirve directo", [360], quality=360)
assert g and g[0] == "file" and g[2] == 360, f"FALLO: {g}"
assert SCRIPT["calls"] == 1, f"FALLO: insistio innecesariamente a 360p: {SCRIPT['calls']}"


# ── Via directa: el caso queShort-circuitaba en Railway ──
# En produccion la via directa volvio a funcionar (el PO provider por script
# ya arranca) pero SIN proxy solo da el muxed de 360p, y servia ese 360p en
# 5-7 s sin comprobar la altura, de modo que la escalera del proxy —que si
# llega a 1080p— no se recorria nunca.
SEIS = ["web_embedded", "android", "mweb", "web", "ios", "android_vr,web"]

g = run_case("7) directa 360p en los 6 clients, proxy 1080p -> 1080p",
             [360] * len(SEIS) + [1080], direct=True)
assert g and g[0] == "file" and g[2] == 1080, f"FALLO: {g}"

g = run_case("8) directa 360p y proxy siempre 360p -> 360p, nunca 502",
             [360] * 10, direct=True)
assert g and g[0] == "file" and g[2] == 360, f"FALLO: {g}"

g = run_case("9) directa 1080p a la primera -> sirve sin gastar proxy",
             [1080], direct=True)
assert g and g[0] == "file" and g[2] == 1080, f"FALLO: {g}"
assert SCRIPT["calls"] == 1, f"FALLO: gasto proxy aunque la directa cumplia: {SCRIPT['calls']}"

g = run_case("10) directa 480p y proxy 360p -> se queda con el 480p",
             [480] * len(SEIS) + [360], direct=True)
assert g and g[0] == "file" and g[2] == 480, f"FALLO: {g}"

print("=" * 72)
print("LOS 10 CASOS PASAN")
shutil.rmtree(WORK, ignore_errors=True)
