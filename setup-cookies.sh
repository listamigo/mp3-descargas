#!/bin/bash
# Limpia cookies expiradas, copia a ubicación local y sube a Railway
SRC="/home/elimdavid/Descargas/www.youtube.com_cookies(1).txt"
DST="$HOME/.mp3downloader/cookies/cookies.txt"
RAILWAY_URL="https://mp3downloader-server-production.up.railway.app"

# Limpiar cookies expiradas
python3 -c "
import time, sys
now = int(time.time())
src, dst = sys.argv[1], sys.argv[2]
kept = removed = 0
with open(src) as fin, open(dst, 'w') as fout:
    for line in fin:
        if line.startswith('#') or not line.strip():
            fout.write(line)
            continue
        parts = line.split('\t')
        if len(parts) >= 5:
            expiry = int(parts[4]) if parts[4].isdigit() else 0
            if expiry > 0 and expiry < now:
                removed += 1
                continue
            kept += 1
        fout.write(line)
print(f'Cookies: {kept} válidas, {removed} expiradas eliminadas')
" "$SRC" "$DST"

chmod 644 "$DST"
echo "Cookies locales: $DST ($(wc -c < "$DST") bytes)"

# Subir al servidor Railway
echo "Subiendo cookies a Railway..."
RESP=$(curl -s -X POST "$RAILWAY_URL/api/cookies" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @"$DST" \
  --max-time 15)
echo "Respuesta Railway: $RESP"

# Verificar
HEALTH=$(curl -s "$RAILWAY_URL/api/health" --max-time 10)
echo "Health: $HEALTH"
