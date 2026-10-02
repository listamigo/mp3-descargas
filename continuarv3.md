# continuarv3.md — 2026-10-01: las cookies SÍ desbloquean el vídeo

> **Por qué existe este archivo.** La solución de este archivo costó horas de
> buscar y se encontró por un camino que los archivos anteriores lo relacionaban
> como perdido. Si no queda escrito aquí, la próxima sesión vuelve a buscar
> proxies SOCKS5 gratuitos que nunca van a ser necesarios.
>
> **Corrige a `continuarv2.md` §10 punto 2**, que dice textualmente:
> *"`PO_TOKEN_PROVIDER` / cookies: no tocar. §2.4 y §4.2 ya lo descartaron."*
> **Eso es falso.** Ver §3 de este archivo.

---

## 1. TL;DR

Con una `cookies.txt` válida (sesión iniciada, cookies `SID`/`SAPISID`/
`LOGIN_INFO`/`SIDCC`), el vídeo **deja de necesitar proxies**:

| | Antes (sin cookies) | Ahora (con cookies) |
|---|---|---|
| 720p | 209 s → **360p** | 17.8 s → **720p** |
| 1080p | 175 s → **1080p** (vía proxy, inestable) | 21-26 s → **1080p** |
| Proxies usados | 1-3 SOCKS5 por descarga | **0** |

Medido el 2026-10-01 en local, 6/6 descargas a la altura pedida, verificado con
`ffprobe`, no solo con la cabecera `X-Video-Height`.

**La cookies no solo quitan el challenge: desbloquean la escalera de formatos.**

---

## 2. Por qué ahora sí y antes no

Esta es la parte que cuesta encontrar. La causa no es una sola, son tres
capas, y por eso parecía "no funcionar" desde varios sitios a la vez.

### 2.1 El challenge de bot (lo que se veía)

Sin sesión autenticada:

```
ERROR: [youtube] 9bZkp7q19f0: Sign in to confirm you're not a bot.
```

Con cookies:

```
[youtube] [pot:bgutil:script-deno] Generating a gvs PO Token for web client
[youtube] [jsc:deno] Solving JS challenges using deno
[download] 100.0% of 14.95MiB in 00:00:02 at 6.26MiB/s
```

Un `cookies.txt` caducado produce el **mismo error que no tener cookies**, y por
eso era lo que hacía pensar "las cookies rompen la app" (ver §4).

### 2.2 El bug de orden que hacía parecer que las cookies rompían todo

Este es el hallazgo importante, y explica la experiencia del usuario de que
"con cookies dejaban de descargarse los archivos".

`get_audio_url` y `get_video_url` tenían:

```python
cookie_passes = [True, False] if os.path.isfile(COOKIES_FILE) else [False]
```

**Cookies primero.** Con 7 player clients en la lista, un `cookies.txt`
caducado gastaba un intento COMPLETO por client con `Sign in to confirm`
antes de llegar a la ruta sin cookies, que es la que sí funciona desde IP de
datacenter. Varios minutos de espera para acabar descargando **sin cookies
igual**.

El propio `_base_cmd` ya avisaba del problema en su docstring:

> "an expired/invalid cookie session can restrict the available formats and
> trigger 'requested format is not available'"

...y el código,haciendo justo lo contrario. Por eso la reacción natural
(borrar el fichero y redeployar) funcionaba: era la corrección correcta
por accidente, y por eso nunca se llegó a la causa real.

**Arreglado en `eb34653`:** las cookies son ahora **opt-in** (`USE_COOKIES=1`),
y cuando están activas el orden es `[False, True]` — sin cookies primero,
cookies como último recurso. Un `cookies.txt` roto cuesta un pase extra,
nunca una caída.

### 2.3 Por qué los proxies parecían la única salida

Con el challenge activo, la vía directa no extraía **ningún** formato de
vídeo (solo storyboards `sb0`..`sb3`):

```
ID  EXT   RESOLUTION | MORE INFO
sb3 mhtml 48x27      | images storyboard
...                  (y nada más)
```

Con el challenge resuelto, la misma llamada devuelve la escalera completa.
Los proxies SOCKS5 no "arreglaban" nada: sorteaban el problema porque cada IP
gratuita distinta tiene su propia reputación. Sin cookies, la escalera
alta parecía depender del proxy. Con cookies, **no depende de nada externo**.

---

## 3. Cómo lo hacen los Smart TV (la pregunta que faltaba)

Los Smart TV, Fire TV y Android TV **no inician sesión**. No pueden: no hay
teclado ni login. Y sin embargo descargan 1080p.

Usan la **vía directa con PO tokens** (`bgutil`), que es lo que este proyecto
ya tenía instalado desde antes (`po_script_ok: true` en `/api/health`). YouTube
tolera clientes nativos con PO token válido sin pedir autenticación. Esos
dispositivos no parecen un scraper porque son clientes reales con codecs
legítimos.

**Lo que faltaba no era el PO token — ya estaba.** Lo que faltaba era la
sesión autenticada para el vídeo. Son dos capas distintas:

| Capa | Qué es | Estado en este proyecto |
|---|---|---|
| PO token (`bgutil`) | Challenge de JS, sin login | **Ya funcionaba** desde antes |
| Cookies válidas | Sesión autenticada de Google | **Faltaba** — este archivo |

Por eso el camino parezca tan perdido: la mitad de la solución ya estaba
instalada y verificada, y la otra mitad era un fichero de texto.

---

## 4. Lo que hay que hacer y lo que NO

### Reglas que se respectaron

1. **Las cookies son opt-in.** `USE_COOKIES=0` por defecto. Estar el fichero
   en disco no las activa. Un fichero caducado **no puede** tumbar el servicio.
2. **Sin cookies primero.** El orden nunca es "cookies primero".
3. **`/api/health` separa los dos conceptos:**
   - `has_cookies`: ¿existe el fichero? (puede ser que sí, y estar caducado)
   - `cookies_enabled`: ¿se usa? (`USE_COOKIES`)

   Render llevaba días reportando `has_cookies: true` con un fichero
   caducado que además ya no se usaba, y eso se leía como "hay cookies
   activas". Esa ambigüedad fue parte de la confusión.
4. **Verificar con `ffprobe`, no con cabeceras.** `X-Video-Height` lo pone el
   servidor; que diga 720 no prueba que el fichero sea 720.

### ⚠️ Seguridad

Un `cookies.txt` **es una sesión de cuenta**. Quien lo tenga entra en la cuenta.

- No pegarlo en chats ni commitearlo. Va como variable de entorno.
- **`has_cookies: true` en el health NO significa cookies activas.** Mirar
  `cookies_enabled`.
- Rotar la sesión (cerrar sesión en Google) invalida el fichero.
- Recomendación: cuenta secundaria, sin nada valioso.
- Para borrar: `rm ~/.mp3downloader/cookies/cookies.txt`

---

## 5. Cómo volver a medir

```bash
# Servidor local con cookies
COOKIES_FILE=$HOME/.mp3downloader/cookies/cookies.txt \
USE_COOKIES=1 PORT=9919 python3 server/server.py

# Vídeo, y OJO: verificar el fichero, no la cabecera
curl -s -D /tmp/h.txt -o /tmp/v.mp4 \
  "http://127.0.0.1:9919/api/download?videoId=9bZkp7q19f0&title=t&mode=video&quality=1080"
grep -i x-video-height /tmp/h.txt
ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 /tmp/v.mp4

# ¿usó proxy? Si sale 0, la vía directa bastó:
grep -icE "proxy activo|por proxy" server.log
```

Señales de que las cookies están caducadas: `Sign in to confirm you're not
a bot` en el log, o `cookies_enabled: true` con `has_cookies: true` y
fallos.

---

## 6. Lo que sigue pendiente de verdad

1. **Verificar en Railway con cookies**, no solo en local. La IP es otra y la
   sesión puede estar atada a ella. Ojo a la trampa conocida: comprobar
   `build_commit` en `/api/health` antes de creer cualquier resultado.
2. **`use_cookies` en producción = exponer la sesión de Google** en un
   servidor externo. Decisión consciente del usuario, no un descuido.
3. **Los proxies SOCKS5 ya no son el camino principal.** Pasarán a ser
   respaldo para cuando las cookies caduquen, que es un problema recurrente.
4. **Streaminging progresivo** (§5 de `continuarv2.md`): sigue sin hacer.
   Con cookies la descarga baja tan rápido (12-26 s) que la prioridad es
   bastante menor, pero no está resuelto.
5. **No tocar `desktop/` ni `deb-package/`** sin pedirlo.