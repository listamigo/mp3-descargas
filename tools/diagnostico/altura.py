"""Altura máxima que da cada client, con el plugin bgutil activo y desactivado.

Uso: python3 altura.py <proxy> <videoId>
"""
import re
import subprocess
import sys
import time

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
PROXY = sys.argv[1]
VIDEO = sys.argv[2] if len(sys.argv) > 2 else "f665ujaFwHA"
Q = 1080

SEL = (f"bv[height<={Q}][ext=mp4][vcodec^=avc1]+ba[ext=m4a]"
       f"/bv[height<={Q}][ext=mp4]+ba[ext=m4a]"
       f"/bv[height<={Q}]+ba"
       f"/b[height<={Q}]/b")


def probe(clients, skip_bgutil=False, no_plugins=False):
    cmd = ["yt-dlp", "--no-warnings"]
    if no_plugins:
        cmd.append("--no-plugin-dirs")
    cmd += ["--proxy", PROXY, "--user-agent", UA,
            "--extractor-args", "youtube:player_client=" + ",".join(clients)]
    if skip_bgutil:
        cmd += ["--extractor-args", "youtubepot-bgutilscript:skip=true"]
    cmd += ["-f", SEL, "-F", f"https://youtube.com/watch?v={VIDEO}"]
    t0 = time.time()
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired:
        return None, 120.0, "TIMEOUT", ""
    hs = sorted({int(m) for m in re.findall(r"^\s*\d+\s+\S+\s+(\d+)x(\d+)\s",
                                            p.stdout or "", re.M) for m in [0]} |
                {int(m) for m in re.findall(r"\b\d+x(\d{2,4})\b(?=\s+\d+\s+\|)", p.stdout or "")})
    if not hs:
        hs = sorted({int(m) for m in re.findall(r"\b\d+x(\d{2,4})\s+avc1", p.stdout or "")})
    err = [l for l in (p.stderr or "").splitlines() if l.startswith(("ERROR", "WARNING"))]
    return (max(hs) if hs else 0), time.time() - t0, (err[-1][:100] if err else ""), " ".join(
        e[8:60] for e in err if "bgutil" in e.lower() or "pot" in e.lower())


CASES = [
    ("mweb            (plugin activo)", ["mweb"], False, False),
    ("mweb            (bgutil SKIP)  ", ["mweb"], True, False),
    ("mweb            (--no-plugin)  ", ["mweb"], False, True),
    ("web_embedded    (plugin activo)", ["web_embedded"], False, False),
    ("web_embedded    (bgutil SKIP)  ", ["web_embedded"], True, False),
    ("android         (plugin activo)", ["android"], False, False),
    ("lista completa  (plugin activo)",
     ["web_embedded", "android", "mweb", "web", "ios", "android_vr,web"], False, False),
    ("lista completa  (bgutil SKIP)  ",
     ["web_embedded", "android", "mweb", "web", "ios", "android_vr,web"], True, False),
]

print(f"proxy={PROXY}  video={VIDEO}  quality={Q}\n")
for label, clients, skip, noplug in CASES:
    h, dt, err, pot = probe(clients, skip, noplug)
    print(f"{label}  maxH={h:>5}  {dt:5.1f}s  {err}")
    if pot:
        print(f"{'':32}  pot: {pot}")
