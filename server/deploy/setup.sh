#!/usr/bin/env bash
# ==============================================================
# setup.sh — Oracle Cloud Free Tier ARM Setup
# ==============================================================
# Despliega el servidor MP3 Downloader en Oracle Cloud ARM (Ubuntu 22.04/24.04)
#
# Uso:
#   1. Copia el directorio server/ a tu VM Oracle Cloud:
#      rsync -avz --exclude='__pycache__' "/home/elimdavid/mp3 downloader/server/" ubuntu@<IP>:~/server/
#
#   2. SSH a la VM y ejecuta:
#      cd ~/server && chmod +x deploy/setup.sh && sudo ./deploy/setup.sh
#
#   3. Configura cookies de YouTube (ver deploy/cookies_guide.md)
# ==============================================================

set -euo pipefail

# ─── Colores ───────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log()  { echo -e "${GREEN}[✓]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[✗]${NC} $1"; }
info() { echo -e "${BLUE}[i]${NC} $1"; }

# ─── Verificar root ────────────────────────────────────────────
if [[ $EUID -ne 0 ]]; then
    err "Este script debe ejecutarse como root (sudo ./setup.sh)"
    exit 1
fi

# ─── Detectar arquitectura ─────────────────────────────────────
ARCH=$(uname -m)
if [[ "$ARCH" != "aarch64" ]]; then
    warn "Arquitectura detectada: $ARCH"
    warn "Oracle Cloud ARM usa aarch64. El script continuará pero puede haber problemas."
fi

info "Arquitectura: $ARCH"
info "Iniciando instalación del servidor MP3 Downloader..."
echo ""

# ─── 1. Actualizar sistema ────────────────────────────────────
log "Actualizando paquetes del sistema..."
apt-get update -qq
apt-get upgrade -y -qq

# ─── 2. Instalar dependencias ─────────────────────────────────
log "Instalando dependencias del sistema (Python3, ffmpeg, pip)..."
apt-get install -y -qq \
    python3 \
    python3-pip \
    python3-venv \
    ffmpeg \
    netfilter-persistent \
    iptables-persistent \
    curl \
    git

log "Dependencias instaladas correctamente."

# ─── 3. Crear directorios ──────────────────────────────────────
APP_DIR="/opt/mp3downloader"
COOKIES_DIR="$APP_DIR/cookies"
LOGS_DIR="$APP_DIR/logs"
DOWNLOADS_DIR="/var/mp3-downloads"

mkdir -p "$APP_DIR"
mkdir -p "$COOKIES_DIR"
mkdir -p "$LOGS_DIR"
mkdir -p "$DOWNLOADS_DIR"

log "Directorios creados en $APP_DIR"

# ─── 4. Copiar código fuente ───────────────────────────────────
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

if [[ -d "$PROJECT_DIR" ]]; then
    cp -r "$PROJECT_DIR"/*.py "$APP_DIR/"
    cp -r "$PROJECT_DIR"/models "$APP_DIR/"
    cp -r "$PROJECT_DIR"/utils "$APP_DIR/"
    log "Código fuente copiado a $APP_DIR"
else
    err "No se encuentra el código fuente en $PROJECT_DIR"
    warn "Asegúrate de copiar los archivos del servidor a /opt/mp3downloader manualmente"
fi

# ─── 5. Crear y activar entorno virtual ────────────────────────
log "Creando entorno virtual de Python..."
python3 -m venv "$APP_DIR/venv"
source "$APP_DIR/venv/bin/activate"

log "Instalando yt-dlp (última versión)..."
pip install --quiet --upgrade pip
pip install --quiet yt-dlp

log "yt-dlp instalado: $(yt-dlp --version)"

# ─── 6. Configurar entorno ─────────────────────────────────────
cat > "$APP_DIR/.env" << 'EOF'
# ==========================================================
# Configuración del servidor MP3 Downloader
# ==========================================================
# Puerto del servidor
PORT=8899

# Host (0.0.0.0 para escuchar en todas las interfaces)
HOST=0.0.0.0

# Ruta del archivo de cookies de YouTube
# (puedes subirlo después vía /api/cookies)
COOKIES_FILE=/opt/mp3downloader/cookies/cookies.txt

# Directorio de descargas (no usado en modo proxy, solo para logs)
DOWNLOAD_DIR=/var/mp3-downloads

# Nivel de logging: DEBUG, INFO, WARNING, ERROR
LOG_LEVEL=INFO

# Directorio de logs
LOG_DIR=/opt/mp3downloader/logs
EOF

log "Archivo de configuración creado en $APP_DIR/.env"

# ─── 7. Configurar systemd ─────────────────────────────────────
log "Instalando servicio systemd..."
cp "$PROJECT_DIR/deploy/mp3downloader.service" /etc/systemd/system/mp3downloader.service
systemctl daemon-reload
systemctl enable mp3downloader.service

log "Servicio systemd instalado y habilitado."

# ─── 8. Firewall — OCI Security List (aviso) ──────────────────
info "═══════════════════════════════════════════════════════"
info "  PASO MANUAL REQUERIDO — Firewall en OCI Console"
info "═══════════════════════════════════════════════════════"
info ""
info "  Además de este script, DEBES abrir el puerto 8899 en"
info "  la consola web de Oracle Cloud:"
info ""
info "  1. Ve a OCI Console → Compute → Instancias"
info "  2. Selecciona tu instancia"
info "  3. Haz clic en el enlace de la Subnet"
info "  4. Haz clic en la Security List"
info "  5. Add Ingress Rule:"
info "     - Source CIDR: 0.0.0.0/0"
info "     - Destination Port Range: 8899"
info "     - Protocol: TCP"
info "  6. Haz clic en Add"
info ""

# ─── 9. Firewall — OS-level (iptables) ─────────────────────────
log "Abriendo puerto 8899 en iptables (firewall del SO)..."
if ! iptables -C INPUT -p tcp --dport 8899 -j ACCEPT 2>/dev/null; then
    iptables -I INPUT -p tcp --dport 8899 -j ACCEPT
    netfilter-persistent save
    log "Puerto 8899 abierto en iptables"
else
    log "Puerto 8899 ya está abierto en iptables"
fi

# ─── 10. Caché de yt-dlp ───────────────────────────────────────
log "Configurando directorio de caché para yt-dlp..."
mkdir -p "$APP_DIR/cache"
cat > "$APP_DIR/yt-dlp.conf" << 'EOF'
# Configuración global de yt-dlp
--cache-dir /opt/mp3downloader/cache
--no-clean-info-json
EOF

# ─── 11. Iniciar servicio ─────────────────────────────────────
log "Iniciando servidor..."
systemctl start mp3downloader.service

# Esperar a que el servicio arranque
sleep 2
if systemctl is-active --quiet mp3downloader.service; then
    log "Servidor MP3 Downloader iniciado correctamente."
else
    warn "El servicio no arrancó. Revisa los logs:"
    warn "  sudo journalctl -u mp3downloader.service -n 50 --no-pager"
fi

# ─── 12. Health check ──────────────────────────────────────────
INTERNAL_IP=$(hostname -I | awk '{print $1}')
log "Probando health check..."
sleep 2
HEALTH=$(curl -s http://127.0.0.1:8899/api/health 2>/dev/null || echo "failed")
if [[ "$HEALTH" != "failed" ]]; then
    log "Health check exitoso: $HEALTH"
else
    warn "Health check falló. Puede que el servicio tarde unos segundos en arrancar."
fi

echo ""
log "═══════════════════════════════════════════════════════"
log "  INSTALACIÓN COMPLETADA"
log "═══════════════════════════════════════════════════════"
log ""
log "  IP interna:       http://$INTERNAL_IP:8899"
log "  IP pública:       (obtener de OCI Console)"
log "  Estado:           sudo systemctl status mp3downloader"
log "  Logs:             sudo journalctl -u mp3downloader -f"
log "  Reiniciar:        sudo systemctl restart mp3downloader"
log ""
info "  Próximos pasos:"
info "  1. Abre el puerto 8899 en OCI Console (paso manual arriba)"
info "  2. Sube cookies.txt (ver deploy/cookies_guide.md)"
info "  3. En tu app Android, ve a Ajustes > Servidor"
info "     e ingresa: http://<IP_PUBLICA_ORACLE>:8899"
info "  4. ¡Disfruta de las descargas 24/7!"
log ""
log "═══════════════════════════════════════════════════════"
