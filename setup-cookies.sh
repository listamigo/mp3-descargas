#!/bin/bash
# Copia las cookies de YouTube a la ubicación local Y las sube al servidor Railway
SRC="/home/elimdavid/Descargas/www.youtube.com_cookies(1).txt"
DST="$HOME/.mp3downloader/cookies/cookies.txt"
RAILWAY_URL="https://mp3downloader-server-production.up.railway.app"

# Copiar a ubicación local
mkdir -p "$(dirname "$DST")"
cp "$SRC" "$DST"
chmod 644 "$DST"
echo "Cookies locales: $DST ($(wc -c < "$DST") bytes)"

# Subir al servidor Railway
echo "Subiendo cookies a Railway..."
RESP=$(curl -s -X POST "$RAILWAY_URL/api/cookies" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @"$SRC" \
  --max-time 15)
echo "Respuesta Railway: $RESP"

# Verificar
HEALTH=$(curl -s "$RAILWAY_URL/api/health" --max-time 10)
echo "Health: $HEALTH"
