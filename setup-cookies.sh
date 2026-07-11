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

# ───────────────────────────────────────────────────────────────
# Persistencia PERMANENTE: guardar cookies en variable de entorno
# de Railway (COOKIES_B64). El filesystem del contenedor es efímero
# y se borra en cada redeploy/commit; las variables de entorno SÍ
# persisten, así el server restaura las cookies solo al arrancar.
# ───────────────────────────────────────────────────────────────
if command -v railway >/dev/null 2>&1; then
  echo "Guardando cookies en variable de entorno COOKIES_B64 (persistente)..."
  B64=$(base64 -w0 "$DST")
  railway variables set COOKIES_B64="$B64" 2>&1 | tail -3 || \
    echo "ADVERTENCIA: no se pudo setear COOKIES_B64 (¿railway autenticado?)."

  # PO token opcional para evitar el reto 'eres un bot' sin cookies
  if [ -n "$YT_PO_TOKEN" ]; then
    echo "Guardando YT_PO_TOKEN (persistente)..."
    railway variables set YT_PO_TOKEN="$YT_PO_TOKEN" 2>&1 | tail -3 || \
      echo "ADVERTENCIA: no se pudo setear YT_PO_TOKEN."
  fi
  echo "Listo: las cookies sobrevivirán a futuros commits/redeploys."
else
  echo "ADVERTENCIA: 'railway' CLI no encontrado. Instala Railway CLI y"
  echo "ejecuta:  railway variables set COOKIES_B64=\"\$(base64 -w0 '$DST')\""
  echo "para que las cookies no se pierdan en el próximo deploy."
fi
