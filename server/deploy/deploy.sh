#!/usr/bin/env bash
# ==============================================================
# deploy.sh — Despliega el servidor desde tu PC al Oracle Cloud
# ==============================================================
# Uso:
#   1. Configura la IP en la variable SERVER_IP abajo
#   2. Ejecuta: ./deploy/deploy.sh
# ==============================================================

set -euo pipefail

# ─── Configuración — CAMBIA ESTO ──────────────────────────────
SERVER_IP="xxx.xxx.xxx.xxx"   # ← Cambia por la IP pública de tu VM Oracle
SERVER_USER="ubuntu"           # Usuario de la VM (ubuntu para Oracle Linux)
SERVER_PATH="~/server"         # Ruta temporal en el servidor
# ──────────────────────────────────────────────────────────────

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}[✓]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[✗]${NC} $1"; }

# ─── Verificar configuración ───────────────────────────────────
if [[ "$SERVER_IP" == "xxx.xxx.xxx.xxx" ]]; then
    err "Primero configura la IP del servidor en deploy.sh (variable SERVER_IP)"
    exit 1
fi

# ─── Confirmar ─────────────────────────────────────────────────
echo "¿Desplegar en $SERVER_USER@$SERVER_IP? (s/N): "
read -r confirm
if [[ "$confirm" != "s" && "$confirm" != "S" ]]; then
    warn "Despliegue cancelado."
    exit 0
fi

# ─── Subir archivos vía rsync ─────────────────────────────────
log "Subiendo código fuente al servidor..."
rsync -avz \
    --exclude='__pycache__' \
    --exclude='*.pyc' \
    --exclude='.git' \
    --exclude='venv' \
    "$PROJECT_DIR/" \
    "$SERVER_USER@$SERVER_IP:$SERVER_PATH/"

log "Archivos subidos correctamente."

# ─── Ejecutar setup en el servidor ────────────────────────────
log "Ejecutando setup en el servidor..."
ssh "$SERVER_USER@$SERVER_IP" "cd $SERVER_PATH && sudo bash deploy/setup.sh"

log "═══════════════════════════════════════════════════════"
log "  DESPLIEGUE COMPLETADO"
log "═══════════════════════════════════════════════════════"
log "  Tu servidor está corriendo en:"
log "  http://$SERVER_IP:8899"
log ""
log "  Prueba con: curl http://$SERVER_IP:8899/api/health"
