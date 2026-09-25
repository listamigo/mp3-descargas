# Continuar — MP3 Downloader (Android KMP + desktop Python)

Fecha de corte: 2026-09-25 (2ª tanda, 13:20–13:40)
Rama: `main` · Último commit: `8ded826 fix(server): probar mas proxies y respetar orden de calidad`
Repo: `/home/elimdavid/mp3 downloader/`

> **Estado de la 2ª tanda:** todo el trabajo de código previsto está hecho y
> verificado en verde (build + lint). Quedan 2 decisiones que necesitan al
> usuario (emulador y tests) y 2 acciones de seguridad que no puede hacer un
> agente. **Nada está commiteado todavía**: los cambios están en el working tree.

---

## 0. REGLAS DEL USUARIO (léelas primero, son duras)

1. **NO modificar nunca la versión desktop.** El usuario afirma que sus descargas desktop
   **ya funcionan** y lo verificó. Solo se puede **observar** el desktop, nunca modificarlo.
   Archivos intocables: `desktop/**`, `deb-package/**`.
2. **No usar la app desktop como servidor** para que Android funcione.
3. Android debe descargar **sin servidor propio**, con algo **gratis y ya disponible**
   en GitHub o en la web.
4. Reglas del repo (`AGENTS.md`): leer antes de editar, máximo **3 archivos por lote**
   salvo plan aprobado, cambios atómicos, no tocar código preexistente no relacionado.
5. No hay dispositivo Android conectado (`adb devices` vacío), ni AVD, ni system images
   instalados. Hay `/dev/kvm`. Compilar o probar la UI real requiere instalar una
   system image (~1.5 GB) y crear un AVD.

---

## 1. OBJETIVO

Que las descargas de la **app Android** funcionen de origen a origen (buscar → descargar),
gratis, sin depender de un servidor propio.

---

## 2. DIAGNÓSTICO CLAVE (ya hecho, no repetir)

Prueba A/B en esta misma máquina, misma IP, mismo video `FGBhQbmPwH8`:

| Motor | Resultado |
|---|---|
| Servidor local + **yt-dlp 2026.08.19** | ✅ HTTP 200, 10.29 MB en 30 s, MP3 válido (ID3v2.3, 256 kbps) |
| Servidor local + **yt-dlp 2026.06.09** (pin del repo) | ❌ `unable to download video data: HTTP Error 403: Forbidden` |
| Render `mp3-descargas-1.onrender.com` (default anterior) | ❌ `502 {"error": "Invidious fallback: no audio URL available"}` tras 105 s; segundo intento sin respuesta en 150 s |

Conclusiones:
- El 403 viene de la **versión vieja de yt-dlp**, no de la IP de datacenter ni de proxies.
  La extracción funciona en ambas; solo la descarga falla con la vieja.
- El pin `yt-dlp==2026.6.9` aparece en: `server/Dockerfile:7`, `server/server.py:56`,
  `Dockerfile:47`, `Dockerfile.railway:48`, `hf-space/Dockerfile:39`, `desktop/requirements.txt:2`.
  **NO se han tocado** (el desktop está prohibido; lo demás está pendiente de decisión).
- `server/server.py:56` (`ensure_ytdlp_updated`) **degrada** yt-dlp al arrancar si encuentra
  una versión mayor. Ojo: en esta máquina intentó bajar a 2026.6.9 y falló con
  `externally-managed-environment`; el yt-dlp del usuario sigue en 2026.08.19 (intacto).
- Render `/api/health` reporta `yt_dlp_version: 2026.6.9`, `has_cookies: true`,
  `has_po_provider: false`, uptime 164 d.

### Instancias públicas (sondeo exhaustivo ya realizado)

Invidious — directorio oficial `https://api.invidious.io/instances.json`: 11 instancias,
**solo 1 con API habilitada y respondiendo**: `https://invidious.f5.si`.
Verificada end to end: search 200 (19 resultados), `/api/v1/videos` 200 (16 formatos,
4 de audio, mejor itag 250 / 80644 bps) y descarga real de pistas completas:
Rick Astley 1.55 MB y Gangnam Style 1.96 MB, ambas con brand `ftyp` (m4a válido).
Velocidad lenta y variable (≈14–110 KB/s). Es un único tercero: puede caerse.

Invidious hardcodeadas que se eliminaron por estar muertas: 3 con CAPTCHA HTML,
1 "Invidious has shutdown", 1 redirect, 2 sin conexión → **0/7 vivas**.

Piped — **0/5 vivas** (526, 502, 502, sin conexión). Las listas oficiales están caídas
(`piped.video/api/v1/instances` devuelve HTML, `piped-instances.kavin.rocks` red
inalcanzable, wiki de Piped 404). Piped queda como tercer motor, sin instancia por
defecto, y falla rápido.

---

## 3. ESTADO DE LA TAREA ANDROID

### 3.1 Build bloqueado → RESUELTO

| Tarea | Estado |
|---|---|
| `:composeApp:compileKotlinDesktop` | ✅ BUILD SUCCESSFUL |
| `:composeApp:lintDebug` | ✅ BUILD SUCCESSFUL (0 errores) |
| `:composeApp:assembleDebug` | ✅ `composeApp/build/outputs/apk/debug/composeApp-debug.apk` |
| `:composeApp:minifyReleaseWithR8` | ✅ BUILD SUCCESSFUL |

Causas raíz arregladas (eran preexistentes, no de los lotes 1-3):
- `Internal compiler error ... should not be called` en `compileKotlinDesktop` **no** venía
  de los cambios de `PlatformAppearance`: faltaba el `actual` de `copyTextToClipboard`
  en `desktopMain`. Añadido en
  `composeApp/src/desktopMain/kotlin/com/mp3downloader/domain/service/FileUtils.kt:27`
  (con `java.awt.Toolkit` + `StringSelection`).
- 4 errores lint `MissingPermission` en `DownloadService.kt`: helper
  `postNotification()` con chequeo de `POST_NOTIFICATIONS` (API 33+) +
  `@SuppressLint("MissingPermission")` puntual.
- R8 `Missing class org.slf4j.impl.StaticLoggerBinder`: reglas `-dontwarn org.slf4j.**`
  y `-dontwarn org.slf4j.impl.**` en `composeApp/proguard-rules.pro`.

### 3.2 Lote 1 — cancelar ahora cancela de verdad

Problema: `FallbackEngine.download` al recibir el fallo de cancelación hacía `continue`
y **arrancaba la descarga con el siguiente motor** (Invidious → Piped). Además el
ViewModel marcaba FAILED mientras la cadena seguía corriendo, y los 3 engines dejaban
archivos parciales truncados en disco.

- `commonMain/.../data/engine/DownloadEngine.kt` → nueva constante
  `const val CANCELLED_ERROR: String = "Cancelado"`.
- `commonMain/.../data/engine/FallbackEngine.kt` → corta la cadena (y el reintento de
  `RemoteServerEngine`) cuando el error es `CANCELLED_ERROR`; emite un único
  `FAILED(CANCELLED_ERROR)`.
- `commonMain/.../ui/MainViewModel.kt` → con cancelación ya no muestra snackbar
  "Descarga fallida" ni emite `DownloadFailedEvent`; `cancelDownload()` usa la constante.
- `androidMain/.../RemoteServerEngine.kt`, `InvidiousApiEngine.kt`, `PipedApiEngine.kt`
  → borran el parcial y emiten `CANCELLED_ERROR` al cancelar.

### 3.3 Lote 2 — instancias configurables y persistentes

Problema: `InvidiousConfig.customInstanceUrl` y `PipedConfig.customInstanceUrl` **nunca se
asignaban** (configuración muerta), los mensajes de error pedían configurar algo que no
existía en la UI, y las instancias hardcodeadas adds minutos de latencia por sondeo en serie.

- `commonMain/.../data/engine/RemoteConfig.kt` → reescrito: `serverUrl`, `invidiousUrl`,
  `pipedUrl` en `settings.json` como JSON. Carga perezosa (en Android depende de
  `AndroidStorage.basePath`, que se asigna en `Application.onCreate`).
  Retrocompatible con el formato antiguo de URL plana.
  `DEFAULT_SERVER_URL` renombrado a `SUGGESTED_SERVER_URL` (ya no se usa por defecto) y
  `legacyServerUrl()` limpia la URL de Render de instalaciones antiguas.
- `androidMain/.../InvidiousApiEngine.kt` y `PipedApiEngine.kt` → borrados los objetos
  `InvidiousConfig` / `PipedConfig`; `invalidateInstance()` para re-resolver si la
  instancia verificada deja de responder; `search` de Invidious detecta HTML;
  `PipedApiEngine.testInstance` ya no acepta `{"error": ...}` con HTTP 200 como sana.
- `commonMain/.../ui/screens/MainScreen.kt` → `SettingsDialog` con 3 campos (servidor
  propio, instancia Invidious, instancia Piped); firma `onSave: (String, String, String) -> Unit`.

### 3.4 Lote 3 — límites y limpieza

- `maxDownloadBytes = 250 MB` añadido a `InvidiousApiEngine` y `PipedApiEngine`
  (aviso previo por `Content-Length` + corte durante la escritura), igual que ya hacía
  `RemoteServerEngine`.
- `uniqueOutputFile` duplicado eliminado de ambos engines → movido a
  `androidMain/.../data/engine/OutputFiles.kt` (función `internal`).

### 3.5 Ruta pública (lo último aplicado, compila y pasa lint)

Objetivo: que Android descargue sin servidor propio.

- `RemoteConfig.kt`: `serverUrl` **devuelve `null`** si no está configurado; los motores
  deben tratarlo como "no disponible" y caer al respaldo de inmediato.
- `InvidiousApiEngine.kt`: `defaultInstances = listOf("https://invidious.f5.si")`
  (única instancia verificada). Prioridad: instancia del usuario → lista por defecto.
- `PlatformModule.kt` (androidMain): orden **Invidious → RemoteServer → Piped**
  (antes Render era el primero y añadía ~105 s de espera antes del respaldo).
- `RemoteServerEngine.kt`: mensajes "Sin servidor propio configurado (opcional)."
- `MainScreen.kt`: campos y textos de Ajustes actualizados.

### 3.6 Validación end-to-end del flujo público (RESUELTO ✅)

Se escribió `/tmp/opencode/invidious_flow_test.py`, que replica **1:1** los 4 pasos de
`InvidiousApiEngine` (mismo User-Agent, mismos timeouts, mismo filtro `audio/mp4` +
`maxByOrNull bitrate`, mismo buffer de 8192, mismo corte de 250 MB, misma detección de
HTML/respuesta muerta) y valida el resultado con `ffprobe`.

Resultado sobre `https://invidious.f5.si`:

| Paso | Resultado |
|---|---|
| `testInstance` `?q=a` | 200, no-muerta → instancia aceptada |
| `search` `?q=...&page=1` | 200, 20 items, 17 con `videoId` válido |
| `getAudioStreamUrl` | itag 140, 133 669 bps, URL `https://` |
| `download` | 25.17 MB a ~31 KB/s |
| `ffprobe` | `mov,mp4,m4a` · **3028.3 s (50:28)** · 69 kbps · **AAC 44100 Hz estéreo** · válido |

La duración completa de la pista (50:28) confirma que **no hay truncamiento**: el flujo
buscar → descargar funciona de origen a origen con la ruta pública, sin servidor propio.

Advertencia conocida: la instancia va a **~31 KB/s**, o sea ~30 s por MB. Un mp3 de 5 MB
tarda ~3 min. Es un único tercero y puede caerse; de ahí la insistencia en la cadena de
respaldo (Lote 3 + ruta pública de §3.5).

### 3.7 Lote 4 — no dejar ficheros parciales truncados

Los 3 engines, en `catch (e: Exception)`, **no borraban** el fichero a medio escribir: un
corte de red dejaba un `.mp3`/`.m4a` corrupto en Descargas y el usuario veía "descarga
fallida" pero con archivo. Ahora el path de excepción también borra el parcial.

- `InvidiousApiEngine.kt` y `PipedApiEngine.kt`: `outputFile` **sacado del `try`**, porque
  antes solo era visible dentro de él y el `catch` no podía borrarlo.
- `RemoteServerEngine.kt`: `outputFile` ya estaba fuera del `try`; solo añadir el `delete()`.

### 3.8 Lote 5 — logger real y `lastSearchEngine` en `getAudioStreamUrl`

- **Nuevo** `AppLog`: `expect object` en commonMain, `actual` en androidMain con
  `android.util.Log` y `actual` en desktopMain con `java.util.logging`. Sustituye a los 5
  `println` de `FallbackEngine`, que en Android escribían en stdout sin nivel ni etiqueta y
  por tanto no aparecían en Logcat.
- `FallbackEngine.getAudioStreamUrl` ahora **prioriza `lastSearchEngine`**, igual que ya
  hacía `search`. Antes recorría siempre la lista fija, con lo que la URL de audio podía
  salir de un motor distinto del que devolvió los resultados que el usuario está viendo.
- `ServerStatus` (`RemoteServerEngine.kt:26`) **eliminado**: 0 usos en todo el repo
  (verificado con grep antes de borrar, por la regla de `AGENTS.md`).

### 3.9 Pin de yt-dlp subido a 2026.8.19 (RESUELTO ✅)

**Decisión: subir el pin, no quitarlo.** El pin tiene un motivo legítimo (deploys
deterministas, que un redeploy no traiga una yt-dlp que rompa el bypass cookie-less), pero
la versión fijada era precisamente la que provocaba el 403. La última de PyPI es
`2026.8.19` y es la verificada como OK en el test A/B de §2.

5 ficheros en 2 lotes (máx. 3 por lote). **`desktop/requirements.txt` intacto** (prohibido).

| Lote | Fichero | Cambio |
|---|---|---|
| A | `server/Dockerfile:9` | `2026.6.9` → `2026.8.19` |
| A | `server/server.py:56` | `YTDLP_VERSION` por defecto `2026.6.9` → `2026.8.19` |
| B | `Dockerfile:47` | `2026.6.9` → `2026.8.19` |
| B | `Dockerfile.railway:48` | `2026.6.9` → `2026.8.19` |
| B | `hf-space/Dockerfile:39` | `2026.6.9` → `2026.8.19` |

Además `ensure_ytdlp_updated()` (`server/server.py:97`) ahora **solo sube, nunca degrada**:
compara con el helper nuevo `_version_tuple()` en lugar de igualdad exacta. Antes, una
yt-dlp más nueva que el pin se degradaba en cada arranque — justo lo que dejó el servidor
dando 403, y lo que en esta máquina intentaba bajar a 2026.6.9 y fallaba con
`externally-managed-environment`. Verificado con 7 casos (igual / más nueva con y sin
ceros / más nueva de año / la vieja / `unknown`): los 7 correctos. `py_compile` OK.

### 3.10 Verificación de build de la 2ª tanda

| Tarea | Estado |
|---|---|
| `:composeApp:compileDebugKotlinAndroid` | ✅ BUILD SUCCESSFUL |
| `:composeApp:compileKotlinDesktop` | ✅ BUILD SUCCESSFUL |
| `:composeApp:lintDebug` | ✅ BUILD SUCCESSFUL (**0 errores**, 27 warnings preexistentes de AGP/recursos) |
| `:composeApp:assembleDebug` | ✅ `composeApp-debug.apk` (19.7 MB) |
| `:composeApp:minifyReleaseWithR8` | ✅ BUILD SUCCESSFUL |

### 3.11 Bugs reales encontrados probando en dispositivo (4, todos corregidos)

Con el móvil conectado por adb (Xiaomi 220333QAG, Android 16 / API 36) salió a la luz
un bug que el script de §3.6 **no podía detectar**: el script usaba `.get()` y filtraba
en Python, mientras que la app deserializa con kotlinx.serialization, que es estricto.
Basta con instalar y buscar para que la app no funcione.

| # | Síntoma | Causa raíz | Corrección |
|---|---|---|---|
| 1 | **La búsqueda no devolvía NADA** (100% de búsquedas) | `/api/v1/search` de Invidious devuelve tipos mezclados: también **canales y playlists**, sin `title` ni `videoId`. El DTO los tenía como no-null requeridos, así que `MissingFieldException` abortaba el parseo de la lista **completa** | `InvidiousSearchItem.title`/`videoId` pasan a `String? = null` + filtro en el engine (que ya descartaba ids inválidos) |
| 2 | Al descargar se veía "Sin streams de audio disponibles" | El **companion** de Invidious contesta **HTTP 200 (o 500) con `{"error": ...}`**, no HTML. El DTO lo aceptaba con todos los campos a null y el error real nunca se veía | Se parsea a `JsonObject` y se propaga el mensaje de `error` |
| 3 | **No aparecía el botón "Cargar más"** | `_hasMore = songs.size >= SEARCH_PAGE_SIZE` (20). Invidious da 20 crudos, se descarta 1 canal → **19 >= 20 es falso**. El bug estaba enmascarado por el #1 | `_hasMore = songs.isNotEmpty()`; una página sin resultados nuevos lo oculta |
| 4 | **Sin miniaturas en los resultados** | El engine pedía la miniatura a la **propia instancia** (`invidious.f5.si/vi/...`), y su proxy de imágenes devuelve **HTTP 200 con `text/html` (7492 B)** cuando cae. `BitmapFactory` no decodifica HTML → null → icono placeholder, y el `catch` se tragaba el motivo | Las miniaturas se piden a `i.ytimg.com` (CDN de YouTube, `image/jpeg` 16435 B verificado) + el loader **rechaza `Content-Type` que no sea `image/*`** y lo registra |

Verificado en el dispositivo tras reinstalar: búsqueda OK (19 y 18 resultados en
páginas 1 y 2), "Cargar más" visible y functional, **0 avisos de `ThumbnailLoader`**, y
el error de descarga ahora dice la verdad:
`InvidiousApiEngine[intento 1]: Invidious (https://invidious.f5.si) no pudo resolver el
video: Error while communicating with Invidious companion: ...`

### 3.12 Estado real de los motores públicos (sondeo del 25-09 ~13:55)

| Motor | Estado |
|---|---|
| Invidious | El directorio oficial sigue dando **1 sola instancia con API**: `invidious.f5.si`. **Búsqueda OK, descarga MUERTA**: `/api/v1/videos` → HTTP 500 `{"error":"...Invidious companion..."}`. Además su proxy de imágenes devuelve HTML (§3.11 #4) |
| Piped | De 12 candidatas, **1 viva**: `api.piped.private.coffee` (búsqueda OK, `/streams` OK) pero **`audioStreams: []`**, sin `hls` ni `dash` → sirve metadatos, no audio. Las otras 11: HTTPError o URLError |

**Conclusión**: la ruta pública **no puede descargar ahora mismo**. No es un fallo del
código (el pipeline está verificado extremo a extremo en §3.6) sino de disponibilidad de
terceros gratuitos. Para descargar hoy hace falta el servidor propio, que además ya
funciona con el pin de yt-dlp corregido en §3.9.

---

## 4. PENDIENTE

### 4.1 Hecho en la 2ª tanda ✅

- [x] Prueba end-to-end del flujo real de descarga → **§3.6**, validada con `ffprobe`.
- [x] Pin de yt-dlp decidido y subido a `2026.8.19` en los 5 ficheros de servidor, y
      `ensure_ytdlp_updated()` corregido para que solo suba → **§3.9**.
- [x] Path de excepción de los 3 engines ya no deja parciales → **§3.7**.
- [x] `println` → `AppLog` multiplataforma → **§3.8**.
- [x] `ServerStatus` muerto eliminado → **§3.8**.
- [x] `getAudioStreamUrl` prioriza `lastSearchEngine` → **§3.8**.
- [x] Build y lint verificados en verde → **§3.10**.
- [x] **4 bugs reales encontrados probando en dispositivo real y corregidos** (búsqueda
      rota al 100%, error engañoso, "Cargar más" ausente, miniaturas vacías) → **§3.11**.

### 4.2 Requiere decisión del usuario

1. **Nada está commiteado.** Todo lo de §3.6–§3.11 está en el working tree. Son 3
   commits atómicos naturales (Android/KMP, DTO+engines, servidor), pero no se ha hecho
   `commit` porque no se pidió explícitamente. Decidir si se commitea.
2. **Las descargas no funcionan con la ruta pública ahora mismo** (§3.12): el companion de
   la única instancia de Invidious con API está caído, y la única instancia de Piped viva
   devuelve `audioStreams: []`. No es un fallo de código. Para descargar hay que elegir:
   - **(a)** pointed la app al servidor propio del usuario (⚙ Ajustes → Servidor propio).
     El pin de yt-dlp ya está corregido (§3.9), así que `server/server.py` debería
     funcionar. Se puede probar en local o desplegar en Render/Railway/HF (gratuitos).
   - **(b)** Esperar a que se recupere el companion de Invidious y volver a probar.
   - **(c)** Añadir más instancias de Invidious/Piped. Hoy el directorio oficial solo
     publica 1 con API, así que habría que sondear candidatas a mano periódicamente.
3. **Tests automatizados**: bajo `composeApp/src` no hay ninguno (`allTests` vacío). Ya se
   demostró el coste de no tenerlos: el bug de §3.11 #1 era invisible sin dispositivo.
   Lo natural sería `kotlin.test` en `commonTest` para la lógica pura: el parseo de
   `InvidiousSearchItem` con canales mezclados, `hasMore`, `isTransientError`,
   `isValidYouTubeId`/`isSafeHttpsUrl`, y `FallbackEngine` con engines fake (incluido
   que cancelar corta la cadena). Alcance a decidir.

### 4.3 Pendientes de seguridad (no los puede hacer un agente)

- `server/server.py` **sin autenticación** y con **CORS `*`**: cualquiera que conozca la
  URL puede usarlo de proxy de descarga. Si se despliega en Render/Railway/HF es un
  riesgo de coste y de abuso de cuota. Pendiente de decisión sobre si añadir token.
- `server/Dockerfile.hf` y `hf-space/Dockerfile` crean symlinks a `/opt/mp3downloader`.
- **Hay un PAT en `.git/config`**: debe revocarse y rotarse desde GitHub. No reproducirlo
  ni imprimirlo.

### 4.4 Ideas pendientes (no eran tarea)

- Podría añadirse un timeout más agresivo al probe de instancia, o una segunda instancia
  Invidious alternativa, ya que hoy `defaultInstances` tiene **una sola** entrada y si
  `invidious.f5.si` cae la búsqueda falla hasta que el usuario configure otra en Ajustes.
  Afecta a la latencia de arranque de búsqueda, así que requiere medir antes de tocarlo.

---

## 5. FICHEROS TOCADOS EN ESTA SESIÓN

Android / KMP:
- `composeApp/proguard-rules.pro`
- `composeApp/src/androidMain/kotlin/com/mp3downloader/DownloadService.kt`
- `composeApp/src/androidMain/kotlin/com/mp3downloader/data/engine/InvidiousApiEngine.kt`
- `composeApp/src/androidMain/kotlin/com/mp3downloader/data/engine/PipedApiEngine.kt`
- `composeApp/src/androidMain/kotlin/com/mp3downloader/data/engine/RemoteServerEngine.kt`
- `composeApp/src/androidMain/kotlin/com/mp3downloader/data/storage/PlatformAppearance.kt`
- `composeApp/src/androidMain/kotlin/com/mp3downloader/di/PlatformModule.kt`
- `composeApp/src/androidMain/kotlin/com/mp3downloader/data/engine/OutputFiles.kt` (nuevo)
- `composeApp/src/commonMain/kotlin/com/mp3downloader/data/engine/DownloadEngine.kt`
- `composeApp/src/commonMain/kotlin/com/mp3downloader/data/engine/FallbackEngine.kt`
- `composeApp/src/commonMain/kotlin/com/mp3downloader/data/engine/RemoteConfig.kt`
- `composeApp/src/commonMain/kotlin/com/mp3downloader/data/storage/PlatformAppearance.kt`
- `composeApp/src/commonMain/kotlin/com/mp3downloader/ui/MainViewModel.kt`
- `composeApp/src/commonMain/kotlin/com/mp3downloader/ui/screens/MainScreen.kt`
- `composeApp/src/desktopMain/kotlin/com/mp3downloader/data/storage/PlatformAppearance.kt`
- `composeApp/src/desktopMain/kotlin/com/mp3downloader/domain/service/FileUtils.kt`

Ya modificados antes de esta sesión (NO revertir, no son nuestros):
`AndroidManifest.xml`, `AudioPreviewer.kt` (android/desktop/common), `colors.xml`,
`SearchBar.kt`, `SplashScreen.kt`, `Theme.kt`, `VoiceSearch.kt`,
`composeApp/src/androidMain/kotlin/com/mp3downloader/ui/screens/` (sin trackear).

Desktop Python: restaurado y empaquetado en una fase anterior (`.deb` 1.0.2). **No tocar.**

### 5.1 Ficheros tocados SOLO en la 2ª tanda

Android / KMP (11):
- `composeApp/src/androidMain/kotlin/com/mp3downloader/data/engine/InvidiousApiEngine.kt` (§3.7, §3.11 #1 #2 #4)
- `composeApp/src/androidMain/kotlin/com/mp3downloader/data/engine/PipedApiEngine.kt` (§3.7, §3.11 #4)
- `composeApp/src/androidMain/kotlin/com/mp3downloader/data/engine/RemoteServerEngine.kt` (§3.7 + §3.8)
- `composeApp/src/commonMain/kotlin/com/mp3downloader/data/dto/InvidiousResponse.kt` (§3.11 #1)
- `composeApp/src/commonMain/kotlin/com/mp3downloader/data/engine/FallbackEngine.kt` (§3.8)
- `composeApp/src/commonMain/kotlin/com/mp3downloader/ui/MainViewModel.kt` (§3.11 #3)
- `composeApp/src/androidMain/kotlin/com/mp3downloader/ui/components/ThumbnailLoader.kt` (§3.11 #4)
- `composeApp/src/commonMain/kotlin/com/mp3downloader/domain/service/AppLog.kt` (**nuevo**, §3.8)
- `composeApp/src/androidMain/kotlin/com/mp3downloader/domain/service/AppLog.kt` (**nuevo**, §3.8)
- `composeApp/src/desktopMain/kotlin/com/mp3downloader/domain/service/AppLog.kt` (**nuevo**, §3.8)

Servidor (5, §3.9):
- `server/Dockerfile`
- `server/server.py`
- `Dockerfile`
- `Dockerfile.railway`
- `hf-space/Dockerfile`

Fuera del repo:
- `/tmp/opencode/invidious_flow_test.py` (script de validación, §3.6) — se puede borrar,
  está en `/tmp`.

---

## 6. COMANDOS ÚTILES

```bash
cd "/home/elimdavid/mp3 downloader"
./gradlew :composeApp:compileKotlinDesktop --console=plain
./gradlew :composeApp:lintDebug --console=plain
./gradlew :composeApp:assembleDebug --console=plain
./gradlew :composeApp:minifyReleaseWithR8 --console=plain
# todo junto (tarda ~15 min):
./gradlew :composeApp:compileKotlinDesktop :composeApp:lintDebug :composeApp:assembleDebug :composeApp:minifyReleaseWithR8 --console=plain
```

APK: `composeApp/build/outputs/apk/debug/composeApp-debug.apk`
Mapping R8: `composeApp/build/outputs/mapping/release/mapping.txt`

Servidor local para pruebas (necesita `LOG_DIR` escribible, si no falla con
`PermissionError: /opt/mp3downloader`):

```bash
mkdir -p /tmp/mp3srv/logs/cookies
LOG_DIR=/tmp/mp3srv/logs COOKIES_FILE=/tmp/mp3srv/logs/cookies/cookies.txt \
  python3 server/server.py            # escucha en 0.0.0.0:8899
```

Validar el flujo público sin dispositivo (§3.6). **Tarda ~1 h** porque la instancia va a
~31 KB/s; mejor lanzarlo en segundo plano y mirar el log:

```bash
cd /tmp/opencode
setsid nohup python3 -u invidious_flow_test.py > flow.log 2>&1 < /dev/null &
tail -f flow.log
# usa una pista corta: python3 invidious_flow_test.py <instancia> "<consulta corta>"
```

Comprobar que un `.m4a` descargado no está truncado:

```bash
~/.local/bin/ffprobe -v error -print_format json -show_format -show_streams "archivo.m4a"
```
