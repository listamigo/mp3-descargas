#!/bin/bash
# ============================================================
# Script de inicio para MP3 Downloader Server
# ============================================================
# Arranca el PO token provider (bgutil) en background y luego
# el servidor principal de Python.
# ============================================================

set -e

echo "🚀 Iniciando MP3 Downloader Server..."

# ─── Arrancar PO token provider en background ─────────────
# El provider genera tokens de proof-of-origin que YouTube
# requiere para evitar el bloqueo de IPs de datacenter.
PO_PROVIDER_DIR="/opt/bgutil-provider/server"
PO_PROVIDER_PORT="${PO_PROVIDER_PORT:-4416}"

if [ -d "$PO_PROVIDER_DIR" ] && [ -f "$PO_PROVIDER_DIR/build/main.js" ]; then
    echo "🔧 Iniciando PO token provider en puerto $PO_PROVIDER_PORT..."
    cd "$PO_PROVIDER_DIR"
    node build/main.js --port "$PO_PROVIDER_PORT" &
    PO_PID=$!
    echo "   PO token provider PID: $PO_PID"

    # Esperar a que el provider esté listo (max 15s)
    for i in $(seq 1 15); do
        if curl -s "http://127.0.0.1:$PO_PROVIDER_PORT/" >/dev/null 2>&1; then
            echo "   ✅ PO token provider listo"
            break
        fi
        if ! kill -0 $PO_PID 2>/dev/null; then
            echo "   ⚠️  PO token provider murió, continuando sin él"
            break
        fi
        sleep 1
    done
else
    echo "⚠️  PO token provider no encontrado en $PO_PROVIDER_DIR"
    echo "   Las descargas dependerán de cookies y proxies"
fi

# ─── Configurar extractor args para PO token provider ────
# Si el PO token provider está corriendo, configurar yt-dlp
# para que lo use automáticamente.
if curl -s "http://127.0.0.1:$PO_PROVIDER_PORT/" >/dev/null 2>&1; then
    export PO_TOKEN_PROVIDER_URL="http://127.0.0.1:$PO_PROVIDER_PORT"
    echo "🎯 PO token provider configurado: $PO_TOKEN_PROVIDER_URL"
else
    echo "⚠️  PO token provider no disponible, las descargas usarán cookies/proxies"
fi

# ─── Arrancar servidor principal ──────────────────────────
echo "🎵 Iniciando servidor HTTP en puerto ${PORT:-8899}..."
exec python server.py
