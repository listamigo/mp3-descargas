FROM python:3.13-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    yt-dlp ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY . .

EXPOSE 8899
CMD ["python", "server.py"]
