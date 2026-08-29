FROM python:3.11-slim

# ─── Instalar dependencias del sistema ─────────────────────
# ffmpeg: conversión de audio
# curl: health checks y debugging
# gnupg: para agregar repo de Node.js
# build-essential/pkg-config/libcairo*/libjpeg*/libgif*/librsvg*/libpango*:
#   bgutil PO token provider declara "canvas" que compila dependencias
#   nativas (cairo, jpeg, pangocairo...) — sin ellas `npm ci` falla.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    curl \
    gnupg \
    git \
    build-essential \
    pkg-config \
    python3 \
    libcairo2-dev \
    libjpeg62-turbo-dev \
    libgif-dev \
    librsvg2-dev \
    libpango1.0-dev \
    libpixman-1-dev \
    && rm -rf /var/lib/apt/lists/*

# ─── Instalar Node.js (requerido por bgutil PO token provider) ──
# bgutil-ytdlp-pot-provider exige "node >=22" (engines en package.json);
# Node 20 NO es compatible (youtubei.js/bgutils-js requieren >=22).
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m -u 1000 user
WORKDIR /home/user/app

RUN echo "rebuild-2026-08-29-po-provider-node22" > /tmp/.rebuild

COPY --chown=user:user server/server.py .
COPY --chown=user:user server/download_engine.py .
COPY --chown=user:user server/models/ ./models/
COPY --chown=user:user server/utils/ ./utils/

RUN mkdir -p /data/cookies /data/logs && chown -R user:user /data

# ─── Instalar dependencias Python ──────────────────────────
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir yt-dlp==2026.6.9 && \
    pip install --no-cache-dir bgutil-ytdlp-pot-provider==1.3.2

# ─── Clonar bgutil provider (server HTTP para PO tokens) ───
# Pinear al tag 1.3.2 para que coincidan versiones server HTTP + plugin pip.
RUN git clone --single-branch --depth 1 --branch 1.3.2 \
    https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git \
    /opt/bgutil-provider && \
    cd /opt/bgutil-provider/server && \
    npm ci && \
    npx tsc && \
    chown -R user:user /opt/bgutil-provider

ENV PORT=8899
ENV HOST=0.0.0.0
ENV COOKIES_FILE=/data/cookies/cookies.txt
ENV LOG_DIR=/data/logs
ENV LOG_LEVEL=INFO

EXPOSE 8899

# ─── Script de inicio ─────────────────────────────────────
# Arranca el PO token provider en background y luego el servidor
COPY --chown=user:user server/start.sh /start.sh
RUN chmod +x /start.sh

USER user

CMD ["/start.sh"]
