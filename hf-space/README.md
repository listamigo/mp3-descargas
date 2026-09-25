---
title: MP3 Downloader Server
emoji: 🎵
colorFrom: red
colorTo: purple
sdk: docker
app_port: 7860
pinned: true
license: mit
---

# MP3 Downloader Server

Servidor HTTP para descargas de YouTube vía yt-dlp.

## Endpoints

- `GET /api/search?q=<query>` — Búsqueda de canciones
- `GET /api/stream-url?videoId=<id>` — URL directa de audio
- `GET /api/download?videoId=<id>&title=` — Stream del audio (proxy)
- `GET /api/preview?videoId=<id>&title=` — Preview de 30s
- `GET /api/health` — Estado del servidor
- `POST /api/cookies` — Subir cookies.txt

## Features

- PO Token provider (bgutil) para evitar bloqueos de YouTube
- Invidious fallback cuando yt-dlp falla
- Free SOCKS5 proxy fallback
- Cache de previews y downloads
- User-Agent rotation
