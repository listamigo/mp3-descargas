#!/bin/bash
# ============================================================
# Configurar variables de entorno en Render automáticamente
# ============================================================
# Uso:
#   1. Genera un API key en https://dashboard.render.com/u/settings#api-keys
#   2. Exporta: export RENDER_API_KEY="rnd_xxxxx"
#   3. Exporta el service ID: export RENDER_SERVICE_ID="srv-xxxxx"
#      (lo encuentras en la URL de tu servicio o en Settings → General)
#   4. Ejecuta: ./setup-render-env.sh
# ============================================================

set -e

# ─── Colores ──────────────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# ─── Verificar dependencias ───────────────────────────────
if ! command -v curl &>/dev/null; then
    echo -e "${RED}❌ curl no encontrado${NC}"
    exit 1
fi

if ! command -v base64 &>/dev/null; then
    echo -e "${RED}❌ base64 no encontrado${NC}"
    exit 1
fi

# ─── Verificar variables de entorno ───────────────────────
if [ -z "$RENDER_API_KEY" ]; then
    echo -e "${YELLOW}⚠️  RENDER_API_KEY no configurado${NC}"
    echo ""
    echo "Genera un API key en:"
    echo "  https://dashboard.render.com/u/settings#api-keys"
    echo ""
    echo "Luego ejecuta:"
    echo "  export RENDER_API_KEY=\"rnd_tu_api_key_aqui\""
    exit 1
fi

if [ -z "$RENDER_SERVICE_ID" ]; then
    echo -e "${YELLOW}⚠️  RENDER_SERVICE_ID no configurado${NC}"
    echo ""
    echo "Encuentra tu service ID en:"
    echo "  https://dashboard.render.com → tu servicio → Settings → General"
    echo "  (empieza con 'srv-')"
    echo ""
    echo "Luego ejecuta:"
    echo "  export RENDER_SERVICE_ID=\"srv-tu_service_id_aqui\""
    exit 1
fi

# ─── Ruta del archivo de cookies ──────────────────────────
COOKIES_FILE="${COOKIES_FILE:-$HOME/.mp3downloader/cookies/cookies.txt}"

if [ ! -f "$COOKIES_FILE" ]; then
    echo -e "${RED}❌ No se encontró el archivo de cookies: $COOKIES_FILE${NC}"
    echo "Exporta COOKIES_FILE si está en otra ubicación."
    exit 1
fi

echo "═══════════════════════════════════════════════════════"
echo " Configurando variables de entorno en Render"
echo "═══════════════════════════════════════════════════════"
echo ""

# ─── 1. Generar COOKIES_B64 ──────────────────────────────
echo -e "${GREEN}✓${NC} Leyendo cookies de: $COOKIES_FILE"
COOKIES_B64=$(base64 -w 0 "$COOKIES_FILE")
echo -e "${GREEN}✓${NC} COOKIES_B64 generado (${#COOKIES_B64} caracteres)"

# ─── 2. Verificar conexión con Render API ────────────────
echo ""
echo "Verificando conexión con Render API..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $RENDER_API_KEY" \
    "https://api.render.com/v1/services/$RENDER_SERVICE_ID" \
    --max-time 10)

if [ "$HTTP_CODE" != "200" ]; then
    echo -e "${RED}❌ Error al conectar con Render API (HTTP $HTTP_CODE)${NC}"
    echo "Verifica que RENDER_API_KEY y RENDER_SERVICE_ID sean correctos."
    exit 1
fi
echo -e "${GREEN}✓${NC} Conexión con Render API exitosa"

# ─── 3. Obtener service actual ───────────────────────────
SERVICE_INFO=$(curl -s \
    -H "Authorization: Bearer $RENDER_API_KEY" \
    "https://api.render.com/v1/services/$RENDER_SERVICE_ID" \
    --max-time 10)

SERVICE_NAME=$(echo "$SERVICE_INFO" | python3 -c "import json,sys; print(json.load(sys.stdin).get('name','?'))" 2>/dev/null)
echo -e "${GREEN}✓${NC} Servicio: $SERVICE_NAME"

# ─── 4. Preparar variables ───────────────────────────────
echo ""
echo "Preparando variables de entorno..."

# Crear payload JSON
PAYLOAD=$(python3 -c "
import json, sys

cookies_b64 = '''$COOKIES_B64'''

env_vars = [
    {'key': 'COOKIES_B64', 'value': cookies_b64},
]

# Agregar SOLO si no están ya configuradas
existing = {}

print(json.dumps({'envVars': env_vars}))
" 2>/dev/null)

echo -e "${GREEN}✓${NC} Payload preparado"

# ─── 5. Actualizar variables en Render ────────────────────
echo ""
echo "Actualizando variables de entorno en Render..."

RESPONSE=$(curl -s -X PATCH \
    -H "Authorization: Bearer $RENDER_API_KEY" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD" \
    "https://api.render.com/v1/services/$RENDER_SERVICE_ID" \
    --max-time 15)

# Verificar respuesta
if echo "$RESPONSE" | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get('id') else 1)" 2>/dev/null; then
    echo -e "${GREEN}✓${NC} Variables actualizadas exitosamente"
else
    ERROR=$(echo "$RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('message','unknown error'))" 2>/dev/null)
    echo -e "${RED}❌ Error al actualizar variables: $ERROR${NC}"
    echo "Respuesta completa:"
    echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE"
    exit 1
fi

# ─── 6. Forzar redeploy ──────────────────────────────────
echo ""
echo "Forzando redeploy para aplicar cambios..."
DEPLOY_RESPONSE=$(curl -s -X POST \
    -H "Authorization: Bearer $RENDER_API_KEY" \
    -H "Content-Type: application/json" \
    -d '{}' \
    "https://api.render.com/v1/services/$RENDER_SERVICE_ID/deploys" \
    --max-time 15)

DEPLOY_ID=$(echo "$DEPLOY_RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id','?'))" 2>/dev/null)
if [ "$DEPLOY_ID" != "?" ] && [ -n "$DEPLOY_ID" ]; then
    echo -e "${GREEN}✓${NC} Deploy iniciado: $DEPLOY_ID"
else
    echo -e "${YELLOW}⚠️  No se pudo forzar redeploy automáticamente${NC}"
    echo "Haz redeploy manual desde el dashboard de Render."
fi

# ─── Resumen ──────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════"
echo -e "${GREEN}✅ Configuración completada${NC}"
echo "═══════════════════════════════════════════════════════"
echo ""
echo "Variables configuradas:"
echo "  • COOKIES_B64 = (${#COOKIES_B64} caracteres)"
echo ""
echo "Servicio: $SERVICE_NAME"
echo "Service ID: $RENDER_SERVICE_ID"
echo ""
echo "El redeploy tarda ~3-5 minutos."
echo "Verifica con:"
echo "  curl https://mp3-descargas-1.onrender.com/api/health"
echo ""
echo "═══════════════════════════════════════════════════════"
