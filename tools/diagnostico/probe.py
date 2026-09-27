"""Sondeo de proxies SOCKS5 igual que _find_working_proxy del servidor.

Uso: python3 probe.py <videoId> [n_candidatos]
Imprime los proxies que resuelven con el client indicado.
"""
import json
import random
import socket
import subprocess
import sys
import urllib.request
import concurrent.futures as cf

VIDEO_ID = sys.argv[1] if len(sys.argv) > 1 else "f665ujaFwHA"
LIMIT = int(sys.argv[2]) if len(sys.argv) > 2 else 60
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")


def fetch():
    out = []
    for page in range(1, 4):
        try:
            req = urllib.request.Request(
                f"https://proxylist.geonode.com/api/proxy-list?"
                f"limit=100&page={page}&sort_by=lastChecked&sort_type=desc&protocols=socks5",
                headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=15) as r:
                data = json.loads(r.read().decode())
            out += [f"socks5://{p['ip']}:{p['port']}" for p in data.get("data", [])
                    if p.get("ip") and p.get("port")]
        except Exception as e:
            print("geonode page", page, "falló:", e, file=sys.stderr)
    return out


def tcp_alive(p, timeout=1.5):
    hp = p.split("://", 1)[-1]
    host, _, port = hp.rpartition(":")
    try:
        with socket.create_connection((host, int(port)), timeout):
            return True
    except (OSError, ValueError):
        return False


def get_url(p, client):
    cmd = ["yt-dlp", "--no-warnings", "--proxy", p, "--user-agent", UA,
           "--extractor-args", f"youtube:player_client={client}",
           "-f", "bestaudio/best", "--extractor-retries", "1", "--retries", "1",
           "--socket-timeout", "15", "--get-url",
           f"https://youtube.com/watch?v={VIDEO_ID}"]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=25)
        return r.returncode == 0 and bool(r.stdout.strip())
    except Exception:
        return False


def main():
    cands = fetch()
    print(f"{len(cands)} candidatos de GeoNode", file=sys.stderr)
    with cf.ThreadPoolExecutor(max_workers=60) as ex:
        alive = [p for p, ok in zip(cands, ex.map(tcp_alive, cands)) if ok]
    print(f"{len(alive)} aceptan TCP", file=sys.stderr)
    alive = alive[:LIMIT]
    good = []
    with cf.ThreadPoolExecutor(max_workers=12) as ex:
        futs = {ex.submit(get_url, p, "android"): p for p in alive}
        for f in cf.as_completed(futs):
            if f.result():
                p = futs[f]
                good.append(p)
                print("FUNCIONA android:", p, flush=True)
    print("\n=== proxies buenos:", good, file=sys.stderr)


if __name__ == "__main__":
    main()
