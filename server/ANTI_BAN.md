# Anti-Ban Configuration — MP3 Downloader Server

## Variables de Entorno

### Proxy Residencial (RECOMENDADO)
```bash
RESIDENTIAL_PROXY=socks5://user:pass@host:port
```
- **Por qué**: YouTube bloquea IPs de datacenter (Railway, AWS, etc.)
- **Solución**: Un proxy residencial usa IPs reales de hogares
- **Proveedores sugeridos**: Bright Data, Oxylabs, Smartproxy, IPRoyal
- **Costo**: ~$1-5/GB dependiendo del proveedor

### Cookies (OBLIGATORIO para calidad completa)
```bash
# Opción 1: Archivo montado
COOKIES_FILE=/opt/mp3downloader/cookies/cookies.txt

# Opción 2: Base64 en variable de entorno (persiste entre deploys)
COOKIES_B64=<base64-encoded-cookies.txt>
```
- **Cómo obtener**: Exportar cookies de tu navegador con extensión "Get cookies.txt"
- **Cuándo renovar**: Cada 1-2 semanas o cuando YouTube pida "verificar cuenta"

### Proof of Origin Token (OPCIONAL pero recomendado)
```bash
YT_PO_TOKEN=web+XXXX
```
- **Qué es**: Token que prueba que la petición viene de un navegador real
- **Cómo obtener**: Usar herramienta como `yt-dlp-get-pot` o `bgutil-ytdlp-pot-provider`
- **Beneficio**: Reduce significativamente los challenges de "no soy un bot"

### Circuit Breaker
```bash
CB_COOLDOWN_SECONDS=60
```
- **Qué hace**: Si un player client falla, lo enfria por N segundos antes de reintentar
- **Default**: 60 segundos
- **Ajustar**: Subir si YouTube está bloqueando agresivamente

## Flujo de Descarga

```
1. Buscar en YouTube (yt-dlp --flat-playlist)
   └─ User-Agent rotado + headers realistas

2. Obtener URL de audio (yt-dlp --get-url)
   └─ Intenta con cookies → sin cookies
   └─ Intenta con diferentes player clients (tv_embedded, tv, mweb, etc.)
   └─ Backoff exponencial si falla (2s, 4s, 8s, 16s)

3. Descargar y convertir a MP3
   └─ Pipeline: yt-dlp → ffmpeg → MP3
   └─ Cache de 48h para no re-descargar
   └─ Streaming progresivo al cliente

4. Si todo falla → error claro al usuario
```

## Player Clients (en orden de prioridad)

1. `tv_embedded` — Rara vez pide "verificar que no eres un bot"
2. `tv` — Similar a tv_embedded
3. `mweb` — Mobile web, menos restricciones
4. `web` — Desktop web, más propenso a challenges
5. `android` — App de Android
6. `ios` — App de iOS

## Troubleshooting

### "Sign in to confirm you're not a bot"
- **Causa**: YouTube detectó IP de datacenter o comportamiento sospechoso
- **Solución**: Configurar `RESIDENTIAL_PROXY` + cookies válidas

### "Requested format is not available"
- **Causa**: Cookies expiradas o formato no disponible en esa región
- **Solución**: Renovar cookies + usar `tv_embedded` client

### Descargas lentas
- **Causa**: Proxy con alta latencia o YouTube rate limiting
- **Solución**: Probar otro proxy o reducir `CB_COOLDOWN_SECONDS`

### Errores 429 (Too Many Requests)
- **Causa**: Demasiadas peticiones desde la misma IP
- **Solución**: Aumentar `CB_COOLDOWN_SECONDS` + proxy residencial

## Monitorear Estado

```bash
# Ver estado del servidor y health de clients
curl http://localhost:8899/api/health

# Respuesta ejemplo:
{
  "status": "ok",
  "has_cookies": true,
  "has_proxy": true,
  "yt_dlp_version": "2026.6.9",
  "uptime": "2d 5h 30m",
  "client_health": [
    {"client": "tv_embedded", "status": "closed", "failures": 0},
    {"client": "tv", "status": "cooldown", "failures": 2}
  ]
}
```

## Estrategia Recomendada

Para máxima disponibilidad, configurar:

1. **Proxy residencial** (obligatorio para producción)
2. **Cookies válidas** (renovar semanalmente)
3. **PO Token** (reduce bans en ~80%)
4. **Monitoreo** (revisar `/api/health` regularmente)

Con esto, las descargas deberían funcionar >99% del tiempo incluso desde Railway.
