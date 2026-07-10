FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m -u 1000 user
WORKDIR /home/user/app

RUN echo "rebuild-2025-07-10T13-50" > /tmp/.rebuild

COPY --chown=user:user server/server.py .
COPY --chown=user:user server/download_engine.py .
COPY --chown=user:user server/models/ ./models/
COPY --chown=user:user server/utils/ ./utils/

RUN mkdir -p /data/cookies /data/logs && chown -R user:user /data

RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir yt-dlp

ENV PORT=8899
ENV HOST=0.0.0.0
ENV COOKIES_FILE=/data/cookies/cookies.txt
ENV LOG_DIR=/data/logs
ENV LOG_LEVEL=INFO

EXPOSE 8899

USER user

CMD ["python", "server.py"]
