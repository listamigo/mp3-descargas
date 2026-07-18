# mp3-descargas — Notas de operación y fallos recurrentes

> Documento de soporte para sesiones futuras. No es la documentación formal del proyecto (ver `AGENTS.md`).

## ⚠️ Fallo recurrente: descargas caen con "inicia sesión"

**Síntoma:** Tras un redeploy en Railway/Render, la descarga de audio falla y la app (o el log del server) muestra errores tipo "inicia sesión" / login wall de YouTube. A veces el mismo redeploy "arregla" solo y a veces lo rompe.

**Causa raíz:** El `Dockerfile` (root y `server/Dockerfile`) instala yt-dlp así:

```
RUN pip install --no-cache-dir yt-dlp
```

Cada redeploy reinstala la **última** versión de yt-dlp de forma no determinista. YouTube hace cambios continuos en el reto a los clientes (`tv_embedded`, `tv`, etc.) y yt-dlp lanza versiones que a veces rompen el bypass cookie-less. Por eso "al hacer un cambio en el server en ocasiones daña la descarga": el cambio (redeploy) arrastra una yt-dlp distinta.

**Solución (preparada, NO commiteada aún):**
Fijar la versión en ambos Dockerfiles:

```
RUN pip install --no-cache-dir yt-dlp==2026.6.9
```

`2026.6.9` es la versión que está instalada en el venv de escritorio local y que funciona con el bypass. Con el pin, los redeploys son reproducibles y dejan de romper la descarga. Para aplicar: commitear + push (dispara redeploy). El cache-buster `RUN echo "rebuild-..." > /tmp/.rebuild` también debe llevar un timestamp nuevo para forzar rebuild.

**Cómo diagnosticar si vuelve a fallar:**
- Ver qué versión de yt-dlp corrió: el server no la loguea por defecto; confirmar vía la imagen desplegada o `pip show yt-dlp` en el contenedor.
- Loguear `FallbackEngine: engine[i] failed` en el cliente para ver cuál motor falla (RemoteServer / Invidious / Piped).
- Probar el endpoint `/api/download` del server directo con curl para aislar si el fallo es del server (yt-dlp) y no del cliente.

## 🔒 Lo que NO causa el fallo

- **Los cambios del cliente Android (KMP) NO alteran el bypass de cookies.** El bypass vive en `server/download_engine.py`:
  - `PLAYER_CLIENTS = [tv_embedded, tv, mweb, web, android, ios, android_vr, web]`
  - `cookie_passes = [True, False]` → intenta con cookies y luego **sin cookies** (bypass cookie-less por diseño).
- En esta sesión solo se tocó el cliente (sanitización, caché, concurrencia). `server/` quedó intacto. Si la descarga falla tras un redeploy, la causa es yt-dlp/YouTube, no el cliente.

## 🧩 Estado de los motores de fallback (cliente)

`FallbackEngine` prueba en orden: **RemoteServer → Invidious → Piped**.

- **Piped está BLOQUEADO por YouTube** (no funciona actualmente). No invertir esfuerzo ahí.
- **Invidious** puede funcionar como respaldo si el server principal está retado.
- El server (RemoteServer) es la fuente principal; depende del bypass de yt-dlp.

## 📌 Qué tener presente para la próxima sesión

1. **No redeploy sin necesidad.** Cualquier push reinstala yt-dlp latest y puede romper la descarga. Si hay que redeployar, usar el pin `yt-dlp==2026.6.9`.
2. **Si la descarga se rompe tras redeploy:** sospechar versión de yt-dlp, no el código. Actualizar el pin a la versión buena más reciente si `2026.6.9` deja de funcionar (YouTube cambió).
3. **Token PO:** `server/download_engine.py` soporta `YT_PO_TOKEN`. Si el bypass se vuelve inestable a largo plazo, obtener un PO token (ver `server/setup-cookies.sh`) y setearlo en las variables de entorno del deploy.
4. **Railway CLI no está autenticado** en esta máquina; el único mecanismo de redeploy es push a `main` con un cambio que fuerce rebuild (cache-buster).
5. **El deploy solo contiene `server/`** (el backend móvil). El `Dockerfile` hace `COPY` explícito de `server/server.py`, `download_engine.py`, `models/`, `utils/`. El código de escritorio y `composeApp/` NO van al deploy.
6. **Token de GitHub:** hubo un token compartido en sesiones previas; **revócalo/rota inmediatamente** desde GitHub → Settings → Developer settings. No lo incluyas en commits.
7. **Dispositivo de prueba Android:** `adb` device `5919d785`, paquete `com.mp3downloader`. APK debug en `composeApp/build/outputs/apk/debug/composeApp-debug.apk`.

## 🔧 Cambios pendientes en disco (sin commitear)

- `Dockerfile` (root): pin `yt-dlp==2026.6.9` + cache-buster `rebuild-2026-07-11T01-pin-yt-dlp-2026.6.9`.
- `server/Dockerfile`: pin `yt-dlp==2026.6.9`.

Decidir en la próxima sesión si se commitean (recomendado para estabilizar redeploys).

---

## 📊 Análisis de limitaciones para uso compartido

### Límites en la app (código)
- **No hay límite de reproducción** — la app reproduce pistas completas sin restricción de duración.
- **No hay límite de descargas** — el servidor no tiene rate limiting ni restricciones por usuario.
- **No hay autenticación** — cualquier persona con la URL del servidor puede usarlo.

### Problemas reales al compartir con múltiples usuarios

#### 1. YouTube Bot Detection (el mayor problema)
Cuando muchos usuarios descargan simultáneamente, YouTube detecta patrones de bot y bloquea al servidor. El código maneja esto con:
- Rotación de `PLAYER_CLIENTS` (7 clientes diferentes)
- Circuit breaker con cooldown de 60 segundos
- Intentos con y sin cookies

Pero con suficiente tráfico, YouTube bloqueará al servidor temporalmente.

#### 2. Railway Free Tier limitations
- **$5 de crédito mensual** — se agota rápido con uso intensivo
- **512MB RAM** — cada descarga usa yt-dlp + ffmpeg (~100-200MB cada uno)
- **Cold starts** — el servidor se suspende tras inactividad, tarda ~30s en arrancar
- **Disco efímero** — se pierde en cada redeploy (las cookies se guardan en env var)

#### 3. Capacidad estimada
Con Railway free tier, se puede manejar **~5-10 usuarios concurrentes** como máximo. Más que eso:
- Se quedará sin memoria
- YouTube bloqueará al servidor
- Se agotará el crédito mensual

### Recomendaciones para escalar

1. **Para pocos amigos (5-10)**: Railway free tier funciona, pero puede haber fallos ocasionales.

2. **Para más usuarios**:
   - **Oracle Cloud Free Tier** (mencionado en AGENTS.md) — 24/7 sin costo
   - **Railway plan pago** (~$5/mes extra)
   - **Múltiples servidores** con load balancing

3. **Agregar rate limiting** (recomendado para producción):
   - Limitar requests por IP (ej: 10 por minuto)
   - Agregar autentificación básica si se comparte públicamente
   - Monitorear uso de resources

4. **Optimizar recursos del servidor**:
   - Usar cache agresivo para búsquedas repetidas (ya implementado: 600s TTL)
   - Limitar concurrencia de descargas simultáneas
   - Implementar cola de prioridades

### Métricas a monitorear si se comparte
- Requests por minuto por IP
- Tasa de fallos de yt-dlp (bot detection)
- Uso de memoria del contenedor
- Costo acumulado en Railway

---

## 🏗️ Infraestructura Backend: Alternativas Gratuitas (2026-07-11)

> Investigación completa en `research/backend-free-alternatives/REPORT.md`

### Resumen Rápido

| Rango | Estrategia | Coste | Complejidad | Escalabilidad |
|-------|-----------|-------|-------------|---------------|
| 1 | **Supabase Cloud Free** | $0 | Baja | Alta |
| 2 | **Supabase self-hosted en Oracle Cloud** | $0 | Media | Muy alta |
| 3 | **Render + PostHog + AdminJS** | $0 | Media | Media |

### Opción 1: Supabase Cloud Free (RECOMENDADO)

**Para empezar hoy sin infraestructura.**

| Recurso | Límite Free |
|---------|-------------|
| Usuarios (MAU) | 50,000 |
| Base de datos PostgreSQL | 500 MB |
| Storage | 1 GB |
| Egress | 5 GB/mes |
| Realtime | 200 conexiones |
| Edge Functions | 500K invocaciones/mes |

**Incluye**: Auth completo (OAuth social, MFA, anonymous), API REST/GraphQL automática, dashboard de administración.

**Limitación crítica**: Se pausa después de 1 semana sin actividad.

### Opción 2: Supabase Self-Hosted en Oracle Cloud Free

**Para control total y escalabilidad ilimitada.**

Oracle Cloud Always Free ofrece:
- 4 ARM cores, 24 GB RAM
- 200 GB storage
- 10 TB/mes transferencia
- **Coste**: $0 permanente

Supabase self-hosted necesita mínimo 4 GB RAM / 2 cores. Cupido en una VM de Oracle Free.

### Opción 3: Render + PostHog + AdminJS

**Alternativa modular con componentes separados.**

- **Render**: Hosting + Postgres (gratis, pero Postgres expira a 30 días)
- **PostHog**: Analytics + errores (1M events/mes gratis)
- **AdminJS/Strapi**: Dashboard admin (open source MIT)

### Herramientas de Analytics Gratis

| Herramienta | Events gratis | Self-hosted |
|------------|---------------|-------------|
| PostHog Cloud | 1M/mes | Sí (MIT) |
| Umami | Ilimitados | Sí (MIT) |
| Sentry | 5K/mes | Sí (BSL) |

### Dashboard/Admin Open Source

| Opción | Licencia | Gratis |
|--------|----------|--------|
| AdminJS | MIT | Sí |
| Strapi | MIT | Sí |
| Directus | MSCL | Sí (< $5M revenue) |

### Recomendación Final

**Para empezar HOY**: Supabase Cloud Free
**Para producción seria**: Oracle Cloud + Supabase self-hosted
**Para escalabilidad máxima**: Supabase Pro ($25/mes)

### Fuentes Principales
- Supabase Pricing: https://supabase.com/pricing
- Oracle Cloud Free: https://www.oracle.com/cloud/free/
- PostHog Pricing: https://posthog.com/pricing
- Render Free: https://docs.render.com/free
