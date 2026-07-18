FROM python:3.13-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir yt-dlp==2026.6.9

WORKDIR /app
# Copia explícita solo del backend móvil (sin arrastrar desktop/ ni composeApp/)
COPY server/server.py .
COPY server/download_engine.py .
COPY server/models/ ./models/
COPY server/utils/ ./utils/

# Directory for YouTube cookies (mounted as volume or created at runtime)
RUN mkdir -p /app/cookies

# Environment variables for anti-ban features:
# - RESIDENTIAL_PROXY: socks5://user:pass@host:port (evita IPs de datacenter)
# - COOKIES_FILE: ruta al archivo cookies.txt
# - COOKIES_B64: cookies en base64 para persistir entre deploys
# - YT_PO_TOKEN: Proof of Origin token (web+XXXX format)
# - CB_COOLDOWN_SECONDS: cooldown del circuit breaker (default 60)
EXPOSE 8899
CMD ["python", "server.py"]
