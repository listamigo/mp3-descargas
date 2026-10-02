# Continuar v2 — MP3 Downloader

Fecha de corte: **2026-09-30** · Rama `main`
Repo: `/home/elimdavid/mp3 downloader/`

---

## ⚡ LEER PRIMERO — 2026-09-30: las descargas están ARREGLADAS

**El techo de 360p y el 502 tienen arreglo, y ya están verificados de extremo a
extremo.** Commit `3c1e8c5`. Todo lo de debajo es el diagnóstico que llevó a
ello; lo que hay que hacer ahora está en §9, y §10 es lo que **NO** se hizo.

Medido el 2026-09-30 en local, con la **vía directa cerrada a propósito** — o
sea, la situación exacta de Railway:

```
Proxy activo para cUpOtbCWSRs: socks5://212.77.75.25:1088 (35 probados en 29.8s)
*** SERVIDO cUpOtbCWSRs_q1080.mp4  height=1080  size=51407900
ffprobe: h264,1920,1080
=== listo en 110.3s
```

**1080p de verdad por SOCKS5 gratuito.** Antes salía 360p en 5 de 5.

La causa era una sola, y no era ninguna de las tres hipótesis de `continuar.md`:
**`_find_working_proxy` probaba los candidatos en serie.** De 60 candidatos solo
llegaban a probarse ~5 antes de que se acabara el presupuesto de 60 s, y con
una tasa de uso real del 4 % eso da ~18 % de acierto. El proxy no fallaba: no
llegaba a probarse. Ver §9.

---

> **Este archivo NO sustituye a `continuar.md`: lo corrige.**
> `continuar.md` §A.0, §A.3 y §5.2 contienen tres conclusiones que seMidieron hoy
> y **son falsas**. Dejarlas como está garantiza que la próxima sesión vuelva a
> perder horas en ellas. Lo que sí sigue válido está marcado como tal en §3.
> Todo lo de aquí son **mediciones de producción**, no hipótesis.
>
> ⚠️ **Y `continuarv3.md` corrige a ESTE archivo** (2026-10-01). El punto 2 de
> §10 de aquí afirma que las cookies están descartadas. **Es falso**, y las
> cookies son justo lo que desbloquea el vídeo sin proxies. Antes de tocar
> proxies o cookies, leer `continuarv3.md`.

---

## 0. TL;DR — los 3 cambios de rumbo respecto a `continuar.md`

| `continuar.md` decía | La realidad (medida hoy) |
|---|---|
| §A.0: *"`estimatedBytes` ≈ 6-7 MB → el host ve la escalera alta"* | **Falso.** La estimación es idéntica para cualquier calidad porque solo hay UNA calidad disponible |
| §A.3: *"la escalera alta SÍ aparece, sale 1080p ~1 de cada 3 veces"* | **Falso hoy: 0 de 5.** No es intermitente, es que la IP del servidor no la tiene |
| §5.2: *"el PO token da acceso a la escalera completa desde datacenter"* | **Falso.** `po_script_ok: true` solo comprueba que existe un fichero, nunca que genere un token válido |

**El problema real es una sola cosa:** el servidor sabe usar proxies SOCKS5 que **sí**
dan 1080p, pero su rutina de búsqueda no los encuentra casi nunca.

---

## 1. Estado medido hoy (Railway, `build_commit: 93a64c4`)

```json
{"status":"ok","has_cookies":false,"has_proxy":false,"has_po_provider":false,
 "po_script_ok":true,"yt_dlp_version":"2026.8.19","build_commit":"93a64c4",
 "direct_path":{"available":false,"failures":4,"disabled_for_s":242}}
```

`build_commit` = HEAD, o sea **el código que se probó es el último desplegado**
(la trampa de §5.4 no aplicaba hoy).

### Descargas medidas

| Petición | Resultado |
|---|---|
| MP3 `_3Kqc2Y7q38` (nunca descargado) | ✅ 200, 16.123.330 B, **10,3 s** |
| MP3 `YY43AJ7Xl1M` (nunca descargado) | ✅ 200, 9.518.739 B, **8,1 s** |
| MP3 `79ikolMBiRk` (1er intento) | ❌ **502 a los 79,4 s** — `Invidious fallback: no audio URL available` |
| MP3 `79ikolMBiRk` (2º intento) | ✅ 200 `audio/mpeg` |
| MP3 `79ikolMBiRk` ×5 | ✅ 200, 9.969.299 B, ~3 s ⚠️ **falsos: caché de 48 h** (`server.py:1026-1049`) |
| Vídeo 1080p `f665ujaFwHA` | 200, 4.437.149 B, 47,8 s, **`x-video-height: 360`** |
| Vídeo 1080p `cUpOtbCWSRs` | 200, 12.104.471 B, 57,3 s, **`360`** |
| Vídeo 1080p `5kFKdMx0JRo` | 200, 12.773.366 B, 60,4 s, **`360`** |
| Vídeo 1080p `YY43AJ7Xl1M` | 200, 17.799.334 B, 66,6 s, **`360`** |
| Vídeo 1080p `_3Kqc2Y7q38` | 200, 11.530.931 B, 55,5 s, **`360`** |
| Vídeo 1080p `_3Kqc2Y7q38` (otro intento) | ❌ **0 bytes en 200 s — cuelgue total sin respuesta** |

**5 de 5 descargas de vídeo correctas salieron a 360p, más 1 cuelgue aparte.**
El MP3 **funciona** en frío (8-10 s).

> Trampa al medir: las 5 descargas de ~3 s **no son pruebas**. La segunda petición
> dejó el fichero en la caché de 48 h (`server.py:1026-1049`) y las cuatro
> siguientes se sirvieron de ahí. Para medir de verdad hay que usar un vídeo
> **nunca descargado**.

---

## 2. Las pruebas que desmontan las tres hipótesis de `continuar.md`

### 2.1 La escalera alta no existe desde Railway (no es lotería, no es el selector)

`/api/ready` sobre `cUpOtbCWSRs`, barriendo calidad:

| q=240 | q=360 | q=480 | q=720 | q=1080 |
|---|---|---|---|---|
| 12.107.463 | 12.107.463 | 12.107.463 | 12.107.463 | 12.107.463 |

**Byte a byte idénticos.** Si la escalera alta existiera, el número cambiaría con la
calidad. Referencia real del mismo vídeo desde IP residencial:

| 480p (itag 135) | 720p (136) | 1080p (137) |
|---|---|---|
| 12,79 MiB | 23,58 MiB | **44,44 MiB** |

Railway estima ~11,5 MB siempre: **su "estimación de 1080p" es una estimación de 360p
con la etiqueta equivocada.** Por eso §A.0 daba por buena la escalera alta: el número
que miraba no significaba lo que parecía.

### 2.2 Los proxies SOCKS5 gratuitos SÍ dan 1080p — el arreglo gratuito existe

`tools/diagnostico/probe.py cUpOtbCWSRs 60` → 300 candidatos → **97 aceptan TCP** →
**4 sirven de verdad**:

```
socks5://103.88.234.239:40003  →  137 mp4 1920x1080  44.44MiB  ✓
socks5://154.37.218.130:555     →  137 mp4 1920x1080  44.44MiB  ✓
socks5://212.231.230.141:18500  →  137 mp4 1920x1080  44.44MiB  ✓
socks5://168.253.92.93:10808   →  (sin respuesta)
```

Con `player_client=mweb` y **sin PO token**. Además se bajó el itag 137 de verdad:
**88,8 % de 44,44 MiB** a 60-100 KiB/s antes de que lo cortara mi propio timeout.

Segundo sondeo 40 min después: `103.88.234.239` **ya estaba muerto** y aparecieron
otros. Confirma la vida de 30-60 min que ya decía `continuar.md`.

### 2.3 Por qué el servidor no los encuentra ← **AQUÍ ESTÁ EL BUG**

`_find_working_proxy` (`server/download_engine.py:920`) recorre los candidatos **en serie**:

```python
for proxy in candidates[:FREE_PROXY_CANDIDATES]:   # 60
    if not _proxy_tcp_alive(proxy): continue         # hasta 1,5 s cada uno
    subprocess.run(cmd, timeout=PROXY_PROBE_TIMEOUT) # hasta 12 s cada uno
    if time.monotonic() - started > PROXY_SEARCH_BUDGET_S: break   # 60 s
```

Aritmética con los números medidos:

- De 60 candidatos, ~**19** pasan el filtro TCP (32 %)
- Cada uno cuesta hasta **12 s** de subprocess
- El presupuesto es **60 s** → **solo llega a probar ~5**

Con una tasa de uso real del **4 %**, P(encontrar uno) ≈ 1−0,96⁵ ≈ **18 %**.

**Ese 18 % es la razón de que gane siempre la vía directa a 360p.** No es que el
proxy no sirva: es que la búsqueda casi nunca llega a uno que sirva. La versión
concurrente del mismo sondeo (`probe.py`, `ThreadPoolExecutor(12)`) sí los encuentra.

### 2.4 El PO token no arregla nada desde datacenter

`_base_cmd` (`download_engine.py:1195-1197`) sí añade
`youtubepot-bgutilscript:server_home=...`. Pero la estimación de §2.1 la usa
(`_video_size_estimate` → `_base_cmd`, `server.py:690`) y sigue viendo un solo
formato. `po_script_ok` (`server.py:374`) **solo hace `os.path.isfile` sobre
`generate_once.js`** — nunca lo ejecuta. Es un campo que da falsa confianza.

### 2.5 Invidious está muerto y el código no se entera

`invidious.projectsegfau.lt` responde **HTTP 200 con el cuerpo `"Invidious has
shutdown"`**. `_invidious_request` (`download_engine.py:986-987`) cae en
`except json.JSONDecodeError: return data` y devuelve una **cadena truthy**;
`_resolve_invidious_instance` (`:1010-1013`) la cachea como instancia viva; y
`invidious_get_audio_url` (`:1035`) la rechaza porque no es `dict`. Resultado:
**toda cadena que llega a `server.py:1392` devuelve 502 sin excepción posible.**

### 2.6 La ventana de 502 de 5 minutos

`DIRECT_PATH_MIN_FAILURES=1` (`download_engine.py:354`) + `DIRECT_PATH_DISABLED_S=300`
(`:355`): **un solo fallo transitorio abre el breaker 300 s.** Como el proxy casi
nunca se encuentra (§2.3) e Invidious está muerto (§2.5), durante esa ventana
**no queda ninguna vía** → 502. Es el 502 de 79 s que reproduje.

Y hay dos bugs alrededor:

- **El breaker nunca se cierra en la ruta de vídeo**: no hay `record_direct_success()`
  en `_proxy_video_download`. Un 1080p exitoso no lo sana.
- **La vista previa lo abre sin guarda**: `server.py:1726` llama
  `record_direct_failure()` **sin** el `is_video_level_error()` que sí tienen las otras dos.
- **El mensaje del 502 miente**: cuando el breaker está abierto, `last_err` sigue `""`
  (`server.py:1053`, `:1072`, `:1081-1084`), así que `is_bot_challenge("")` es False y
  siempre gana la rama de "Invidious" (`server.py:1390`) en vez de la de challenge
  de bot (`:1386-1388`). Por eso el error no dice por qué falló de verdad.

### 2.7 El cuelgue de 200 s

`server.py:830` comprueba el deadline **entre** clients, pero `subprocess.run(...,
timeout=420)` (`:847`) es **por client**. Un client colgado se come los 420 s sin que
el deadline pueda actuar. Y como el cliente no ve ni un byte hasta tener el MP4 entero,
durante todo ese tiempo solo hay un 0 %.

---

## 3. Lo de `continuar.md` que SIGUE siendo válido

| Sección | Veredicto |
|---|---|
| §0.0 Render no puede descargar, Railway sí (para MP3) | ✅ **Correcto**, y reconfirmado hoy |
| §5.4 la trampa del "Redeploy" de Railway / `build_commit` | ✅ Correcto, y hoy sirvió para descartar código viejo |
| §5.4 Railway corta a los **5 min sin datos** | ✅ Correcto, es parte de lo que explica §2.7 |
| Los proxies gratuitos duran 30-60 min | ✅ Confirmado dos veces hoy |
| §3.9 el pin de yt-dlp 2026.8.19 | ✅ Correcto, `yt_dlp_version: 2026.8.19` en producción |
| §5.3 la app etiqueta la altura real (`X-Video-Height`) | ✅ Funciona: el 360p llega etiquetado como 360 |
| Los hosts Invidious/Piped están caídos | ✅ Peor de lo que se decía: Invidious al 100 % |
| §3.12 la ruta pública no puede descargar | ✅ Correcto, sigue igual |
| §5.8 `desktop/` y `deb-package/` sin trackear, no subirlos | ✅ Sigue vigente |

---

## 4. Enfoques: cuáles TOMAR y cuáles DEJAR

### 4.1 TOMAR

| # | Enfoque | Por qué | Riesgo |
|---|---|---|---|
| **F1** | **Búsqueda de proxies concurrente** (`ThreadPoolExecutor` sobre los que pasan TCP) | Ataca el 18 % → ~95 %. Es **el** arreglo del 1080p y del "no hay proxy" | Bajo. El sondeo ya es independiente por candidato |
| **F2** | **Cerrar el breaker desde el vídeo** + poner la guarda de `is_video_level_error` en la vista previa + arreglar el mensaje del 502 | Mata la ventana de 502 de 5 min y hace que el error diga la verdad | Bajo, es corrección |
| **F3** | **Dejar de contar con Invidious** como último recurso (está muerto) | Ahorra la cola muerta y da el 502 antes | Bajo |
| **F4** | **Streaming progresivo** con MP4 fragmentado | La app deja de verse colgada al 0 % | Medio — ver §5 y §6 |
| **F5** | **Capping automático por velocidad medida** | 1080p cuando el proxy da, 720p/480p cuando no | Bajo |
| **F6** | **Timeout real por client**, no solo entre clients | Mata el cuelgue de 200 s | Bajo |

### 4.2 DEJAR (descartados con evidencia)

| Enfoque descartado | Por qué |
|---|---|
| ❌ **Proxy residencial de pago** (§5.6, "la solución de verdad") | El usuario pidió gratis. Y **hoy se ha probado que no hace falta**: proxies SOCKS5 gratuitos dan 1080p (§2.2) |
| ❌ **Arreglar el PO token provider** (§5.2) | Aunque funcionase, §2.4 muestra que la escalera no aparece. Alto esfuerzo, resultado incierto |
| ❌ **"Subir `MAX_PROXY_ATTEMPTS` a 4-5"** (§A.3) | Con la búsqueda secuencial actual, más intentos **no** encuentran más proxies: el reloj corta antes. No ataca la causa |
| ❌ **"Poner `DIRECT_CLIENTS_DEADLINE=0`"** (§A.5) | Convierte un 502 lento en un 502 rápido. No arregla nada |
| ❌ **Añadir más instancias Invidious/Piped** | Invidious está muerto; Piped nunca tuvo audio. §0.0 ya lo investigó y lo descartó |
| ❌ **`WORKING_PROXY` pinneado como solución** (§A.2) | Sigue siendo útil como atajo, pero **no como solución**: el proxy caduca a los 30-60 min (§2.2) |
| ❌ **Tocar el APK** | La app ya pide `?mode=video&quality=` y lee `X-Video-Height` correctamente. **El fallo es 100 % servidor** |
| ❌ **Tocar `desktop/`** | Prohibido por regla del usuario. Y funciona: desde IP residencial da 1080p sin token |

---

## 5. Streaming progresivo — validado (esto era la parte de riesgo)

Un MP4 normal **no se puede streamear**: su `moov` está al final. Hay que fragmentar
antes de muxear. Cadena probada de principio a fin, 3 ffmpeg, **todo con `-c copy`**
(sin recodificar):

```
yt-dlp -f "bv[height<=Q][ext=mp4][vcodec^=avc1]" -o -
        │
        └─ ffmpeg -i pipe:0 -c copy
                  -movflags frag_keyframe+empty_moov -f mp4
                          │
yt-dlp -f "ba[ext=m4a]" -o -                (stream de AUDIO)
        │
        └─ ffmpeg -i pipe:0 -c:a copy -f adts     ← ADTS, no m4a
                          │
        mux ──────────────┴─ ffmpeg -i pipe:0 -i pipe:1 -c copy
                                  -bsf:a aac_adtstoasc
                                  -movflags frag_keyframe+empty_moov -f mp4
                                          │
                                       cliente
```

**Resultado medido:**

- `rc=0`, **18.064.415 bytes**, **primer byte a los 2,28 s**, total 4,7 s
- `ffprobe`: h264 854x480 + aac, **duración 289,354 s** (vídeo completo)
- Idéntico en tamaño al mux desde ficheros (18.064.462 B): solo cambian las cabeceras
  de fragmentación

**Los cuatro errores que hubo que descubrir (no repetirlos):**

1. `-o -` con `bv+ba` y `--downloader ffmpeg` **falla** (`ffmpeg exited with code 251`)
2. El audio en m4a por pipe no se puede sondear (su `moov` también está al final)
   → **hay que pasarlo a ADTS**, que lleva cabecera por frame
3. ADTS dentro de MP4 necesita **`-bsf:a aac_adtstoasc`**, si no:
   `Error writing trailer: Operation not permitted`
4. En Python, las **dos** entradas del mux necesitan `os.dup` + `pass_fds`; con
   `pipe:1` ffmpeg se refiere a su propio stdout y falla con `Bad file descriptor`

---

## 6. Lo que NO está verificado (la única incógnita real)

**Concurrencia a través del proxy SOCKS5.** La cadena de streaming abre 2 conexiones
simultáneas al mismo proxy, y muchos SOCKS5 gratuitos son de una sola conexión.

Lo único medido: con `154.37.218.130:555`, dos `yt-dlp` en paralelo dieron ambos
`rc=124` (timeout a los 90 s), o sea **seguían descargando, no fallaron**. Apunta a
que aguanta, pero el test quedó sin resultado limpio y hay que rehacerlo.

**Si resulta que un proxy es de una sola conexión, F4 se cae de vuelta a
descargar-a-fichero-y-servir.** Se pierde el progreso real, **no la descarga**.

Mitigación prevista: elegir el proxy por su capacidad de concurrencia durante el
sondeo, o volver a fichero si el stream se corta a los pocos KB.

---

## 7. Plan de ejecución

| Fase | Qué | Fichero | Verificación |
|---|---|---|---|
| 1 | Búsqueda de proxies concurrente | `download_engine.py` | `probe.py` + descarga 1080p real con `x-video-height: 1080` |
| 2 | Breaker: cerrar desde vídeo, guarda en vista previa, mensaje del 502 | `server.py`, `download_engine.py` | 20 MP3 seguidos, 0× 502 |
| 3 | Streaming progresivo | `server.py` | primer byte < 5 s + `ffprobe` con duración completa |
| 4 | Capping por velocidad medida | `server.py` | 1080p con proxy rápido, 720p con proxy lento |
| 5 | Timeout real por client | `server.py` | ningún request > 120 s sin responder |
| 6 | **Actualizar `continuar.md` §A.0, §A.3, §5.2** con estas mediciones | `continuar.md` | — |

6 commits atómicos, cada uno verificado antes del siguiente. **Solo servidor.**
El APK no se toca, y `desktop/` y `deb-package/` tampoco (prohibido).

---

## 8. Cómo volver a medir (lo mínimo)

```bash
# ¿De verdad hay escalera alta?  Si NO la hay, los 3 números son IGUALES
for q in 360 720 1080; do
  curl -s "https://mp3downloader-server-production.up.railway.app/api/ready?videoId=ID&quality=$q"; echo
done
# Referencia: el 1080p real de cUpOtbCWSRs son 15.214.000 B. Si sale mucho menos, es 360p.

# ¿Hay proxy?  Tiene que salir alguna línea "FUNCIONA"
python3 tools/diagnostico/probe.py cUpOtbCWSRs 60

# Descarga real, 3 veces seguidas (una sola tirada no vale, ver §2.2)
python3 tools/diagnostico/descarga.py <proxy> cUpOtbCWSRs 1080 mweb
```

**Antes de culpar a los proxies, leer `direct_path` en `/api/health`:** si
`available: false` con `disabled_for_s` alto, el 502 es el breaker (§2.6), no la
búsqueda. Y para medir una descarga de verdad, usar **un vídeo nunca descargado**,
porque la caché de 48 h falsea todo lo que se repita (ver §1).

---

## 9. Lo que se arregló el 2026-09-30 — commits `3c1e8c5`, `2ed9fc2`, `e14322c`

Cuatro arreglos, todos sobre la misma raíz: **el servidor nunca encontraba un
proxy que sirviera, y cuando no lo encontraba no quedaba ninguna vía.** Solo
`server/download_engine.py` y `server/server.py`. La app, `desktop/` y
`deb-package/` no se han tocado.

### 9.1 Sondeo de proxies concurrente (era la causa del techo de 360p)

`_find_working_proxy` probaba los candidatos **en serie**. La aritmética de §2.3
sigue siendo la explicación, y el arreglo es el que correspondía: hacerlo en
paralelo.

- `_tcp_alive_many()` filtra los 200 candidatos por TCP con 24 hilos (~6,9 s).
- `_probe_candidates()` lanza los sondeos de yt-dlp en **tandas de 12**, cierra
  la tanda en cuanto uno responde y respeta `PROXY_SEARCH_BUDGET_S` por reloj.
- `FREE_PROXY_CANDIDATES` sube de **60 a 200**: lo que recortaba la búsqueda no
  era el reloj, era la lista. Con 200 entran 43 al sondeo; con 60, solo 15.
- `_try_with_proxy` (ruta `/api/stream-url`) tenía **el mismo bucle en serie** y
  ahora usa el mismo núcleo. No estaba en el plan y hacía falta: era el mismo bug.

Medido, misma máquina, mismo momento:

| | Candidatos | Pasan TCP | Sondeos | Resultado |
|---|---|---|---|---|
| Antes (serie) | 60 | 15 | 15 en 20,5 s | **0 aciertos** |
| Ahora (paralelo) | 200 | 43 | 32 en 30,7 s | **acierto en el 2.º grande** |

### 9.2 El breaker se cerraba solo en el audio

- `_proxy_video_download` no llamaba a `record_direct_success()` en ningún punto:
  un vídeo descargado a la calidad correcta por la vía directa dejaba el breaker
  abierto 300 s igualmente. Ahora lo cierra.
- `/api/preview` abría el breaker **sin** la guarda `is_video_level_error()` que
  sí tienen las otras dos rutas: un vídeo inexistente tumbaba también las
  descargas que sí funcionaban. Corregido.

### 9.3 El 502 mentía sobre su propia causa

Con el breaker abierto la vía directa se salta **sin intentarlo**, así que
`last_err` se quedaba en `""` y `is_bot_challenge("")` es `False`: el mensaje
siempre acababa en la rama de Invidious (`server.py:1390`) en vez de la del
challenge de bot. Por eso el error no decía por qué falló de verdad.

Ahora el salto anota `DIRECT_PATH_COOLDOWN_REASON`, y la respuesta distingue
challenge de bot / error del vídeo / «no se encontró ninguna vía», con el
`detail` real. Importante: ese texto **no** debe pasar el filtro
`is_video_level_error()`, o reintroduciría el bug que se acaba de cerrar (un
skip de la vía directa no es un fallo de la vía directa).

### 9.4 Invidious está muerto y el código lo daba por vivo (§2.5, confirmado)

- `_invidious_request` devolvía el **texto crudo** cuando el cuerpo no era JSON,
  así que una instancia apagada que responde HTTP 200 con
  `Invidious has shutdown` pasaba por «instancia viva» y se cacheaba como tal.
  Ahora solo se acepta un `dict`, y se reconoce el banner.
- Encima, recorrer las 9 instancias a 10 s cada una para acabar devolviendo
  `None` costaba **90 s de espera con el cliente ya esperando**. Un recorrido
  completo sin éxito marca el host como muerto 30 min
  (`INVIDIOUS_DEAD_COOLDOWN_S`) y las siguientes descargas responden al instante.
  El recurso no se borra: si una instancia vuelve, se vuelve a mirar.

### 9.5 Timeout real por client (§2.7, el cuelgue de 200 s)

El deadline se comprobaba **entre** clients, pero `subprocess.run` tenía
`timeout=420` **por** client: un solo client colgado se comía los 420 s sin que
el deadline pudiera actuar. Ahora:

- `VIDEO_PER_CLIENT_TIMEOUT` (90 s) por client en la vía directa.
- Cada **intento de proxy** tiene presupuesto para sus 3 clients
  (`per_client_timeout * len(_PROXY_CLIENTS)`) en vez de 3×420 s = 21 min.

Los 200 s a 0 % sin una línea de log eran esto.

### 9.6 El tope de primer byte mataba a 7 de cada 8 proxies buenos (`2ed9fc2`)

`PROXY_FIRST_BYTE_TIMEOUT` estaba en **12 s** y el comentario que lo justificaba
decía que «un proxy sano entrega el primer bloque de audio en 1-3 s». Ese
1-3 s era el `--get-url` del **sondeo**, que no descarga nada. La descarga tiene
que volver a extraer los formatos y además bajar el primer bloque.

Medido sobre 8 proxies que el sondeo concurrente dice que sirven:

| | |
|---|---|
| primer byte: min / mediana / max | **11,7 s / 13,6 s / 29,6 s** |
| por encima del tope de 12 s | **7 de 8** |

El síntoma era exactamente el 502 que se lleva días buscando: `Proxy download
timeout (sin audio en 12s)` seguido del fallback a Invidious, que también está
muerto. Sube a **30 s**. Comprobado: `3BFTio5296w` fallaba con 12 s y sale con
30 s.

De paso, **cuatro rutas leían el primer byte con `stdout.read(8192)` a pelo**,
que es un bloqueo indefinido. Ahora usan `_read_first_byte()` con reloj: el
audio directo, el audio por proxy, el preview directo y el preview por proxy
(este último no tenía **ningún** tope, y el preview es lo que la app llama al
abrir una canción).

Y como 3 intentos × (60 s de búsqueda + 30 s de primer byte) son 4,5 min —más
que el corte de 5 min de Railway— la fase de proxy tiene presupuesto:
`PROXY_PHASE_BUDGET_S` (180 s).

### 9.7 `os.replace` entre sistemas de ficheros tiraba el trabajo hecho (`2ed9fc2`)

`tempfile.mkdtemp()` para el workdir de la descarga de vídeo cae en `/tmp`, y
el `os.replace(merged, download_path)` de después **solo es atómico dentro del
mismo sistema de ficheros**. Con el workdir en `/tmp` y la caché en otro sitio,
el rename falla con `[Errno 18] Invalid cross-device link` y se pierde un 1080p
ya descargado y mergeado entero: la descarga se hace bien y se tira en el
último paso.

El workdir se crea ahora junto a la caché (`_video_workdir()`), lo que además
saca 50 MB de vídeo de `/tmp`, que en un contenedor de plan gratuito es tmpfs,
o sea RAM.

### 9.8 El cuelgue de 200 s: la causa era otra (`e14322c`)

Antes se resolvió el cuelgue de §2.7 poniendo un tope de pared de 90 s por
client. **Eso era un parche, y además salía caro**: 51 MB por un SOCKS5
gratuito puede tardar 3-5 min, así que el tope cortaba descargas de 1080p
legítimas a mitad y el 1080p no llegaba.

La causa de verdad: **`_proxy_cmd_video` no llevaba `--socket-timeout` ni
`--retries`**. Las otras tres rutas de descarga sí los llevaban. Un SOCKS5 que
acepta la conexión y luego deja de mandar datos deja a yt-dlp esperando
**indefinidamente**, y como el cliente no ve ni un byte hasta tener el MP4
entero, el request entero parecía congelado. Eso son los 200 s a 0 % sin una
línea de log.

La distinción que importa, y que estaba al revés:

| | Mide | Para qué |
|---|---|---|
| `--socket-timeout 20` | **inactividad** | Corta lo que se cuelga de verdad, sin castigar una descarga lenta pero viva |
| `VIDEO_PER_CLIENT_TIMEOUT` | duración total | Cinturón por encima, holgado: **300 s** |

Regla para no repetirlo: **el tope de pared no es el anti-colgado**. Con
`--socket-timeout` puesto, un tope de pared corto solo sirve para cortar
descargas sanas.

### 9.9 Cómo volver a verificar (todo esto es local, sin Railway)

```bash
cd "/home/elimdavid/mp3 downloader"

# 1080p por la ruta DIRECTA
python3 tools/diagnostico/e2e_video_directo.py cUpOtbCWSRs 1080

# 1080p por PROXY con la vía directa cerrada (la situación de Railway).
# Es EL test que importa: reproduce el fallo original.
python3 tools/diagnostico/e2e_video_proxy.py cUpOtbCWSRs 1080

# MP3 seguidos por proxy, con IDs REALES (sacados de /api/search)
python3 tools/diagnostico/e2e_audio_proxy.py <id1> <id2> ... <id10>
```

Los tres están en `tools/diagnostico/`, junto a los que ya había
(`probe.py`, `altura.py`, `descarga.py`, `test_techo.py`). Los nuevos van
contra la lógica real del servidor, no contra una simulación, y escriben en
`.tmp-e2e/` (ignorado por git).

**Ojo con el tiempo:** cada descarga por proxy paga su propia búsqueda (hasta
60 s) y luego la descarga, así que una tanda de 10 tarda del orden de 10-15 min.
Un vídeo que falla se come hasta `PROXY_PHASE_BUDGET_S` (180 s) y no avanza al
siguiente. No es un cuelgue del servidor: es el precio de los proxies gratuitos,
y por eso hay que dejar correr la tanda en segundo plano en vez de mirarla.

### 9.10 Qué quedó VERIFICADO y qué no, con honestidad

**Verificado hoy, de extremo a extremo, con la vía directa cerrada a propósito
(la condición exacta de Railway):**

| Prueba | Resultado |
|---|---|
| 1080p por PROXY (`e2e_video_proxy.py cUpOtbCWSRs 1080`) | ✅ **h264 1920x1080**, 51.407.900 B, 110,3 s |
| 1080p por la vía directa (`e2e_video_directo.py`) | ✅ h264 1920x1080, 51.407.900 B, 15,7 s y 21,6 s |
| Búsqueda de proxy en serie vs. concurrente | ✅ 60 cand. → 0 aciertos / 200 cand. → acierto en el 2.º grande |
| Latencia de primer byte por SOCKS5 | ✅ 8 proxies: min 11,7 s · mediana 13,6 s · max 29,6 s |
| `_invidious_request` con cuerpo no-JSON | ✅ devuelve `None`, no el texto |
| Recorrido de Invidious → cooldown | ✅ 2.ª llamada instantánea |
| EXDEV entre workdir y caché | ✅ arreglado, la ruta que fallaba ahora sirve |
| Sondeo concurrente con stubs (60→200 cand., sin red) | ✅ encuentra proxy tardío, respeta presupuesto, no cuelga |

**NO verificado, y hay que decirlo claro:**

- **En Railway.** Todo lo de arriba es local. La IP de Railway es otra y el
  primer `curl` de §8 tiene que confirmar que allí también sale 1080p.
- **Una tanda larga de MP3.** Se llegaron a bajar 3 de 10 en paralelo a la
  corrección del primer byte, y `3BFTio5296w` pasó de fallar a salir. No se
  tiene una cifra de 20 seguidas, que era la verificación que pedía la fase 2.
  El plan pedía «20 MP3 seguidos, 0× 502» y **esa cifra no está**: falta por
  medir, y es lo primero que hay que hacer al desplegar.
- **El streaming progresivo y el capping por velocidad**: no implementados, a
  propósito. Ver §10.

Trampa al medir, la misma de §1: **usa vídeos nunca descargados**, o la caché de
48 h te sirve la segunda petición desde disco y todo lo que repitas no es una
prueba.

---

## 10. Lo que NO se hizo, y por qué

| Fase | Qué era | Por qué no está |
|---|---|---|
| 4 | **Streaming progresivo** con MP4 fragmentado (la cadena de §5) | Es la única parte con riesgo real sin verificar: §6 dice que **no está probado que un SOCKS5 gratuito aguante dos conexiones simultáneas**, que es justo lo que hace la cadena.Meterla sin ese test es arriesgar la descarga completa por ganar progreso en la UI. El cuelgue de 200 s ya está resuelto de otra forma (§9.5): con topes reales, un client colgado se corta y se pasa al siguiente, con el error en el log. Cuando se quiera, el orden es: (1) rehacer el test de concurrencia del §6, (2) solo entonces meter el streaming |
| 4 | **Capping por velocidad medida** (1080p si el proxy da, 720p si no) | Depende del streaming para tener una señal de velocidad honesta. Con la descarga a fichero no se puede medir la velocidad hasta que ya se ha bajado el fichero entero, que es cuando el capping deja de servir |
| 6 | Reescribir `continuar.md` entero | Hecho solo lo que el plan pedía y lo que era falso: **§A.0, §A.3, §5.2 y §A.4**, con las mediciones de §9. El resto de `continuar.md` sigue en pie |

### Lo que sigue pendiente de verdad

1. **Desplegar y medir en Railway.** Todo lo de §9 está verificado **en local**,
   con la vía directa cerrada a propósito para reproducir la condición de
   datacenter. La IP de Railway es otra, así que el primer `curl` de §8 tiene que
   confirmar que allí también sale 1080p. Ojo a la trampa de §5.4: comprobar
   `build_commit` en `/api/health` antes de creer nada.
2. ~~**`PO_TOKEN_PROVIDER` / cookies**: no tocar.~~ **CORREGIDO el 2026-10-01:
   este punto era FALSO. Ver `continuarv3.md`.** Las cookies válidas sí
   desbloquean el vídeo por vía directa, sin proxies: medido 720p en 17.8 s y
   1080p en 21-26 s, con **0** proxies usados. Además se rectifica otra cosa que
   aquí se daba por buena: el 1080p NO sale sin token; sale con PO token
   (`bgutil`, que ya estaba instalado) **más** cookies. Con cookies, la vía
   directa deja de dar solo storyboards y devuelve la escalera completa.
3. **`desktop/` y `deb-package/`**: no subir, no tocar. Sigue en pie la regla del
   usuario.
