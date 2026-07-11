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

EXPOSE 8899
CMD ["python", "server.py"]
