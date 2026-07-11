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
