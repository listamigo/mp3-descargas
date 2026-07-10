---
title: MP3 Downloader Server
emoji: 🎵
colorFrom: green
colorTo: blue
sdk: docker
app_port: 7860
pinned: false
short_description: Servidor 24/7 para descargar música desde la app Android
---

# 🎵 MP3 Downloader Server

Servidor API para descargar música de YouTube vía yt-dlp.

**100% gratis, 24/7, sin tarjeta de crédito.**

## 📱 Conectar app Android

En la app MP3 Downloader de tu celular:

1. Toca ⚙️ **Ajustes**
2. En **URL del servidor**, pega la URL de este Space
   - La URL es: `https://[TU_SPACE].hf.space`
   - La puedes copiar desde la barra de direcciones del navegador
3. Toca **Guardar**
4. ¡Listo! Ya puedes buscar y descargar

## 🍪 Subir cookies (recomendado)

Para mejor compatibilidad con YouTube:

```bash
curl -X POST https://[TU_SPACE].hf.space/api/cookies \
  --data-binary @cookies.txt
```

## 🩺 Verificar estado

```bash
curl https://[TU_SPACE].hf.space/api/health
```
