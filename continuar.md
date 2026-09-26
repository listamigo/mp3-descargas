# Continuar — MP3 Downloader (Android KMP + desktop Python)

Fecha de corte: 2026-09-25 (3ª tanda, 17:45–18:20)
Rama: `main` · Último commit: `4d0652d perf: acota los segundos que la cadena de descarga esperaba antes del primer byte`
Repo: `/home/elimdavid/mp3 downloader/`

> **Estado de la 3ª tanda:** las 2 tandas anteriores están commiteadas y
> pusheadas a GitHub (`8ded826..4d0652d`). Se investigated y corrigió la
> lentitud de las descargas. Build + lint en verde. Lo que queda es **medir en
> Railway**, porque el caso problemático (IP de datacenter) no se puede
> reproducir en local: aquí la vía directa sí funciona y nunca se pagan los
> deadlines que se cambiaron.

---

## 0.0 RESUELTO EN LA 4ª TANDA: EL SERVIDOR POR DEFECTO ERA RENDER (léelo primero)

**El problema de descargas que duraba desde la 1ª tanda era que la app apuntaba a Render, y Render no puede descargar. Ya está arreglado y verificado en un móvil real.**

Medido el 2026-09-25, mismo vídeo y mismo commit `5c039ea` en los dos despliegues, 3 intentos cada uno:

| Host | Resultado | Primer byte | Total |
|---|---|---|---|
| **Railway** | **200, MP3 válido de 256 kbps** | 0,73-3,18 s | 2,9-22,7 s |
| **Render** | **502 siempre** | 67-78 s | — |

Prueba en frío, vídeo de 14,6 min nunca descargado antes (`34dhfQ8Z_SY`):
Railway 200 en 3,4 s de primer byte y 22,7 s totales (28.073.619 bytes);
Render 502 a los 70,8 s.

**Por qué difieren:** Render tiene cookies pero su proveedor de PO token no llega
a arrancar. Railway no tiene cookies pero genera los PO tokens en modo script, y
eso es lo que permite a una IP de datacenter extraer el audio. No es una
diferencia de ancho de banda, es de si el host consigue o no superar el
desafío de YouTube.

**Verificación de extremo a extremo** (Xiaomi 220333QAG, Android 16, APK
recién instalado y sin URL guardada, o sea configuración de fábrica):

- Búsqueda `Vladimir Drozdoff` → 1,9 s, va directa a `RemoteServerEngine`.
- Descarga en frío de `Elegy` (3:29), nunca descargada antes: completada en menos
  de 6 s, 6.659.062 bytes, 208,09 s, 256 kbps, 44,1 kHz estéreo.
- Fichero presente en `/sdcard/Download/` y la app lo marca `Completado · 6,4 MB`.

Commits de esta tanda:

- `5c039ea` — `build_commit` en `/api/health`.
- `6a75bb1` — servidor por defecto pasa de Render a Railway.

### Por qué no hay ruta "sin servidor propio" (investigado el 2026-09-25, no repetir)

Petición original del usuario: que la app funcionase **sin depender de su propio
servidor**, gratis, con instancias públicas. Se investigó a fondo. **No es
viable para descargas completas**, y el motivo es concreto, para que no se vuelva
a perder tiempo en esto.

**1. Las instancias públicas están todas bloqueadas.** YouTube bloquea por IP, y
todas las instancias públicas viven en IPs de datacenter.

- Invidious: el directorio oficial (`api.invidious.io/instances.json`) sigue
  dando **una sola instancia con API**, `invidious.f5.si`. Búsqueda 200 en 1,4 s,
  pero la descarga está muerta: HTTP 500
  `Error while communicating with Invidious companion`. Las otras 10 (3 de ellas
  con API "desconocida") dan 403, 404 o no resuelven. **0/11 sirve para descargar.**
- Piped: la lista de `piped.video/api/v1/instances` está caída (devuelve el HTML
  del frontend). Se probó la lista actual del repo de documentación de Piped
  (15 instancias): 13 muertos, y `api.piped.private.coffee`, que responde 200,
  está bloqueada por YouTube: `SignInConfirmNotBotException: YouTube probably
  temporarily blocked anonymous watching`. **0/15 sirve para descargar.**

Por eso la lista de Piped se quitó en `bbf8130`: no era una INSTANCE[],
estaban las 5 muertas.

**2. NewPipeExtractor extrae, pero no se puede descargar el audio.** Probado
`v0.26.5` (JitPack, 1.981 estrellas, commit 4 días antes) desde IP de ISP y
desde el móvil:

- Búsqueda por HTML: **muerta**. YouTube ya no embebe `initialData` en el HTML
  de `/results` (comprobado: 0 apariciones, ni desde el móvil), y
  `YoutubeSearchExtractor` depende de eso.
- Extracción con el cliente InnerTube **iOS**: **funciona**. `playability=OK`,
  5 flujos de audio, 1,3 s, **sin PO token**, y devuelve URLs reales de
  `googlevideo.com`. Los otros dos clientes no: android exige PO token
  (`NullPointerException: androidPoTokenResult is null`) y web-embedded responde
  `ERROR: This video is unavailable`.
- **Pero la descarga es imposible.** El CDN de googlevideo impone una **cuota
  anónima de ~500-700 KB por sesión de cliente**:

  | Prueba | Resultado |
  |---|---|
  | `Range: 0-700001` con URL fresca | 206, 700 KB ✓ |
  | Rango siguiente en la misma URL | 403 |
  | Trozo nuevo pidiendo URL nueva | 403 |
  | `Range: 0-5417352` (fichero entero) | 403 |
  | Fichero entero tras 75 s de espera | 403 |

  Una pista de 5 min son ~5,4 MB. **En el mejor caso salen ~700 KB**, o sea un
  fragmento, no un MP3 completo. Ni reextraer ni esperar reinician la cuota.

**Conclusión:** esa cuota es justo lo que levanta el PO token, que es lo que hace
funcionar Railway. Un PO token en el móvil exigiría un WebView ejecutando el JS
de YouTube, que es el mismo PO script que ya vive en el servidor. Sin él no hay
descargas completas.

**Decisión tomada:** el servidor propio (Railway) es la única ruta que aguanta.
No añadir NewPipeExtractor ni listas de instancias: producirían previews de
700 KB y además Engordarían la APK (Rhino) sin dar descargas completas.

**Aviso importante sobre `uptime`:** el campo `uptime` de `/api/health` miente.
Lee `/proc/uptime`, que es el uptime del **host**, no el del proceso, así que no
cambia con un redeploy. Por eso Render reportaba `161d` y Railway `200d` con
despliegues recientes, y no había forma de saber qué código corría en cada uno.
Para eso está ahora `build_commit`, que lee el SHA que Railway y Render inyectan
en el entorno. Antes de sacar conclusiones de rendimiento, comprobar siempre ese
campo.

**Lo que queda pendiente:** nada para que la app funcione. Opcional: dejar Render
como servidor de respaldo automático. Hoy solo hay un hueco de URL
(`RemoteConfig.serverUrl`), y un valor escrito en Ajustes sustituye al de
fábrica, así que Render se puede seguir usando a mano sin tocar código, pero no
hay conmutación automática entre los dos.

---

## 0.1 LO NUEVO EN LA 3ª TANDA (léelo primero)

Todo está commiteado y en GitHub. La lentitud NO era la descarga: era la
**cadena de intentos** que se recorría antes de encontrar una vía que funcionara.

### Diagnóstico: dónde se iban los segundos

Medido en local (mismo vídeo `FGBhQbmPwH8`, 10.28 MB):

| Culpable | Ubicación | Antes | Ahora |
|---|---|---|---|
| Deadline de clients directos | `server.py:473` | 25 s fijos | 8 s (0 s con breaker abierto) |
| Breaker se abría tras N peticiones | `download_engine.py` | 3 | 1 |
| Reintentos internos de yt-dlp | `_base_cmd` | `--extractor-retries 3` / `--retries 10` (nunca ajustados) | 1 / 3 |
| Escaneo de proxies | `_find_working_proxy` | 20 candidatos × 6 s = **120 s por intento** | filtro TCP 1.5 s |
| Proxy bueno solo en RAM | `_working_proxy_cache` | se perdía al reiniciar | en disco + `WORKING_PROXY` |
| Guardia "sin audio" | `server.py` | 35 s | 12 s |
| Intentos de proxy | `server.py` | 4 | 3 |
| Sondeo de Invidious | `download_engine.py` | 9 × 5 s + 15 s/resolución | 3 × 3 s + 10 s |
| **Incrustado de etiquetas (móvil)** | `RemoteServerEngine.kt` | hasta 5 URLs × 10 s, **antes de `COMPLETED`** | fuera del camino crítico |
| **`URL.readBytes()` sin timeout** | `Mp3MetadataWriter.kt:58` | **infinito** (0 = sin timeout en Java) | 5 s por intento |

Todos los valores del servidor son variables de entorno, para poder medirlos en
Railway **sin desplegar**:
`DIRECT_CLIENTS_DEADLINE`, `DIRECT_PATH_MIN_FAILURES`, `MAX_PROXY_ATTEMPTS`,
`PROXY_FIRST_BYTE_TIMEOUT`, `FREE_PROXY_CANDIDATES`, `PROXY_PROBE_TIMEOUT`,
`TCP_PROBE_TIMEOUT`, `YTDLP_EXTRACTOR_RETRIES`, `YTDLP_RETRIES`,
`YTDLP_SOCKET_TIMEOUT`, `INVIDIOUS_PROBE_INSTANCES`, `INVIDIOUS_PROBE_TIMEOUT`,
`INVIDIOUS_VIDEO_TIMEOUT`, `WORKING_PROXY`.

### Hallazgo crítico: Railway mata la petición a los 5 min

De la doc oficial de Railway (Edge Traffic):

> "HTTP requests can run for up to 15 minutes if data keeps transferring, and
> are otherwise closed after **5 minutes with no data transferred**."

`/api/download` **no envía ni las cabeceras** hasta tener los primeros 8192
bytes de audio (`server.py:503`). Antes de acotar, ese primer byte podía tardar
25 s + hasta 480 s de escaneo de proxies → **Railway cortaba la petición sin
llegar a responder**, que es exactamente el síntoma "segundo intento sin
respuesta en 150 s" de §3.12. **Acotar no es solo velocidad: es que funcione.**

### Railway Free: no suspende contenedores

La doc de precios de Railway no menciona suspensión por inactividad (cobra RAM
mientras está inactivo y para cuando se acaban los créditos). Por eso el warm-up
`RemoteHealth` importa **más en Render** (que sí duerme a los 15 min) que en
Railway. Aun así se replicó el hook en el camino de descarga, que antes solo se
disparaba en la búsqueda.

### ⚠ Sobre la medición local (leer antes de celebrar)

La descarga en local pasó de 20.15 s a 3.81 s hasta el primer byte, pero **eso NO
es atribuible al cambio**: la primera medición fue con yt-dlp y la caché del SO
en frío, y además en local la vía directa **funciona**, así que el código nunca
alcanza los deadlines que se cambiaron. La prueba válida es la de Railway.

Verificado en verde tras los cambios: `compileKotlinDesktop`, `lintDebug` (0
errores), `assembleDebug`, y una descarga real de 10.28 MB / 321 s / 256 kbps
válida con `ffprobe`.

### Cómo medir en Railway (lo siguiente)

1. Desplegar y mirar el log de una descarga. Buscar estas líneas, que dan el
   tiempo real de cada etapa:
   - `Vía directa en cooldown` → ya se salta los 25 s (o 8 s)
   - `Proxy activo para <id>` → cuánto tardó el escaneo
   - `Download streaming completado` → con qué client
2. Fijar el proxy bueno en cuanto aparezca uno que funcione:
   `WORKING_PROXY=socks5://ip:port`. A partir de ahí el escaneo desaparece.
3. Comparar contra la versión anterior (commit `77cc29a`).



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

## 2026-09-25 — Rediseño de resultados y limpieza por sección (`53b1ac7`)

Búsqueda de resultados tal como queda en un Xiaomi 220333QAG (411 dp de ancho,
720x1650, densidad 280). Medido con `uiautomator dump`, no a ojo: `y=`, `x=` y
alturas de tarjeta en px, dividiendo entre 1,75 para pasar a dp.

```
┌──────────────────────────────────────────────────────────────┐
│ ┌──────┐  ROSALÍA - La Perla (Official Video) ft. Yahritza │  título 2 líneas
│ │ 3:30 │  ROSALÍA              6,4 MB · 256k                │  peso al borde
│ └──────┘  ▶ Escuchar                    [ Descargar ]       │
└──────────────────────────────────────────────────────────────┘
```

Decisiones y por qué:

- **La tarjeta no crece.** La primera versión添加ó una fila de controles debajo y
  pasó de 85 dp a 120 dp. Lo que estaba sin aprovechar era el ancho del título, no
  la altura: los botones se comían un tercio y el título quedaba en una línea.
  Ahora el título usa las dos líneas que la tarjeta ya tenía y los controles siguen
  a la derecha. Altura final medida: 89 dp (155 px de paso entre tarjetas).
- **`verticalAlignment = Alignment.CenterVertically` en la fila principal.** Estaba en
  `Top`, y con el título a 2 líneas el botón "Descargar" quedaba 10 dp por encima
  del centro de la tarjeta (centro medido 484 px contra 501 px del contenido).
- **El peso del archivo pasa debajo del botón "Descargar"**, en una columna derecha
  con `horizontalAlignment = Alignment.End`. Sharing line with the artist left each
  of them about 78 dp on a 157 dp column, and the name clipped to "Artista desc...".
  The column was not actually wider than the text column, the two texts were just
  competing for the same run of pixels. Cost: 8 dp more per card (89 -> 97 dp).
- **"5,8 MB · 256k" a 9 sp** en vez de "256 kbps · 5,8 MB" a labelSmall, que no cabía
  junto al artista.
- **`Artista desconocido` se oculta en la UI**, no en el servidor: lo produce
  `server/download_engine.py:1214` cuando no deduce el artista, y es lo bastante
  largo para truncarse y empujar el peso fuera de la fila. Filtrarlo en el cliente
  también cubre respuestas ya cacheadas de despliegues viejos.
- **`ThumbnailImage` ya no fija su propio `size(52.dp)`**; el llamador decide el
  tamaño, que ahora es 54 dp.

Limpieza por sección: antes los dos botones "Limpiar" llamaban a
`clearFinishedDownloads()`, que quitaba completadas *y* fallidas. Ahora es
`clearSection(status)` y cada botón borra solo lo suyo; la sección sin elementos no
dibuja botón.

Pendiente: el PAT de GitHub que apareció en los logs sigue sin revocarse.

---

## OBJETIVO FUTURO: integrar el reproductor propio con la app de descargas

Registrado el 2026-09-25 a petición del usuario, **fuera del alcance de la fase
de vídeo** que se estaba cerrando. No es un bug de la app de descargas: es un
problema de la integración entre las dos apps, que hoy no existe.

Qué pide el usuario:

- El usuario tiene **su propio reproductor de música y vídeo**. Cuando desde la
  app de descargas se pulsa "Abrir" sobre un fichero ya descargado, y ese fichero
  se abre en su reproductor, **la primera vez no sale todo bien**. A partir de la
  segunda ya funciona.
- Objetivo: que la descarga y la reproducción funcionen bien juntas de principio a
  fin, sin ese primer intento fallido.

Por qué queda como objetivo futuro y no como bug a arreglar ya:

- El síntoma ("la primera vez no") apunta a algo que ocurre **entre** las dos
  apps: paso de Intent, escritura del fichero, MediaStore/indexado, o permisos.
  Sin reproducirlo con el reproductor real a mano no se puede atribuir a ninguna
  de las dos, y cambiar la app de descargas a ciegas sería adivinar.
- La app de descargas ya entrega el fichero donde el reproductor lo encuentra
  (`content://media/external/downloads/...`, `mime_type` correcto: se verificó
  `video/mp4` en el MP4 y el MP3 en el audio). Ese contrato es la base sobre la
  que luego se construirá el resto.
- El usuario es quien tiene el reproductor; hace falta su prueba manual para
  capturar **qué falla exactamente** en ese primer intento (no aparece, sale
  negro, no suena, se cierra, etc.).

Cómo abordarlo cuando se retome:

1. Reproducir el fallo a mano con el reproductor real y apuntar el síntoma exacto.
2. Registrar con `adb logcat` lo que pasa en el primer `ACTION_VIEW` y compararlo
   con el segundo, que sí funciona. La diferencia es la causa.
3. Revisar el lado escritura: `MediaStore` con `IS_PENDING`, permisos y momento en
   que el fichero se hace visible. Un fichero visible antes de estar completo
   explica justo un "a la primera no, a la segunda sí".
4. Solo entonces tocar la app de descargas, y solo el lado que aparezca en el log.

Nota de codificación: el MP4 sale **h264 + aac** a propósito (ver
`server/download_engine.py:video_format_selector`), pero el usuario tiene su
propio reproductor y el códec no es un bloqueante para esta integración.
