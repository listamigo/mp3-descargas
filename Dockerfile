# ============================================================
# Dockerfile para Hugging Face Spaces
# MP3 Downloader Server — 100% gratis, 24/7, sin tarjeta
# ============================================================
# Uso en Hugging Face:
#   1. Crea un Space nuevo → SDK: Docker
#   2. Sube estos archivos (server.py, download_engine.py, models/, utils/, Dockerfile)
#   3. El Space se construye solo y arranca el servidor
# ============================================================

FROM python:3.11-slim

# ─── Instalar dependencias del sistema (sin curl, no se usa) ─
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# ─── Crear usuario no-root (requisito de HF Spaces) ────────
RUN useradd -m -u 1000 user
WORKDIR /home/user/app

# ─── Copiar código fuente desde server/ ─────────────────────
ARG CACHE_BUST=2
COPY --chown=user:user server/server.py .
COPY --chown=user:user server/download_engine.py .
COPY --chown=user:user server/models/ ./models/
COPY --chown=user:user server/utils/ ./utils/
RUN echo "Build ${CACHE_BUST}" > .build_id

# ─── Directorios de datos con permisos para usuario no-root ─
RUN mkdir -p /data/cookies /data/logs && chown -R user:user /data

# ─── Instalar yt-dlp (última versión) ──────────────────────
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir yt-dlp

# ─── Puerto que expone Hugging Face (por defecto 7860) ─────
ENV PORT=8899
ENV HOST=0.0.0.0
ENV COOKIES_FILE=/data/cookies/cookies.txt
ENV LOG_DIR=/data/logs
ENV LOG_LEVEL=INFO

# ─── Puerto del contenedor ─────────────────────────────────
EXPOSE 8899

# ─── Cambiar al usuario no-root ────────────────────────────
USER user

# ─── Ejecutar servidor ─────────────────────────────────────
CMD ["python", "server.py"]
