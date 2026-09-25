# ============================================================
# Dockerfile para Hugging Face Spaces
# MP3 Downloader Server — 100% gratis, 24/7, sin tarjeta
# ============================================================

FROM python:3.11-slim

# ─── Instalar dependencias del sistema ─────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    curl \
    gnupg \
    && rm -rf /var/lib/apt/lists/*

# ─── Instalar Node.js (requerido por bgutil PO token provider) ──
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# ─── Crear usuario no-root (requisito de HF Spaces) ────────
RUN useradd -m -u 1000 user
WORKDIR /home/user/app

# ─── Copiar código fuente del directorio padre ─────────────
# HF Spaces clona todo el repo, así que server/ está disponible
COPY --chown=user:user server/server.py .
COPY --chown=user:user server/download_engine.py .
COPY --chown=user:user server/models/ ./models/
COPY --chown=user:user server/utils/ ./utils/

# ─── Directorios de datos (usando /data que es persistente) ─
RUN mkdir -p /data/cookies /data/logs && \
    chmod 755 /data/cookies /data/logs && \
    ln -s /data/cookies /opt/mp3downloader/cookies && \
    ln -s /data/logs /opt/mp3downloader/logs

# ─── Instalar dependencias Python ──────────────────────────
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir yt-dlp==2026.8.19 && \
    pip install --no-cache-dir bgutil-ytdlp-pot-provider

# ─── Clonar bgutil provider (server HTTP para PO tokens) ───
RUN git clone --single-branch --depth 1 \
    https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git \
    /opt/bgutil-provider && \
    cd /opt/bgutil-provider/server && \
    npm ci --omit=dev && \
    npx tsc && \
    chown -R user:user /opt/bgutil-provider

# ─── Puerto que expone Hugging Face (por defecto 7860) ─────
ENV PORT=7860
ENV HOST=0.0.0.0
ENV COOKIES_FILE=/data/cookies/cookies.txt
ENV LOG_DIR=/data/logs
ENV LOG_LEVEL=INFO

EXPOSE 7860

# ─── Script de inicio ─────────────────────────────────────
COPY --chown=user:user server/start.sh /start.sh
RUN chmod +x /start.sh

USER user

CMD ["/start.sh"]
