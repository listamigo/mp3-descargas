# Diagnóstico de descargas

Scripts de diagnóstico del servidor. Están aquí porque el runbook completo
está en `continuar.md` §A, y un runbook que apunta a `/tmp` no sobrevive a
un reinicio.

Ninguno modifica nada del servidor. Todos se ejecutan desde tu máquina.

## `test_techo.py` — el primero que hay que ejecutar

Banco de pruebas **sin red** del fix de calidad de vídeo. Importa
`server/server.py` y sustituye `subprocess.run` y `_find_working_proxy`, así
que tarda 1 s y no toca YouTube ni ningún proxy.

```bash
python3 tools/diagnostico/test_techo.py
```

10 casos. Salen todos en verde o el fix de `_proxy_video_download` está roto.
**Ejecutarlo antes de tocar esa función**: es más rápido que cualquier prueba
contra la red y no depende de que los proxies gratuitos estén de humor.

## `probe.py` — encontrar un proxy SOCKS5 que sirva

```bash
python3 tools/diagnostico/probe.py <videoId> [n_candidatos]
```

Descarga la lista de GeoNode, descarta por TCP en paralelo (1,5 s cada uno) y
prueba `--get-url` con `player_client=android`. Imprime los que resuelven.

Lo que hay que hacer con la salida: coger el primero que diga `FUNCIONA` y
fijarlo como `WORKING_PROXY` en el panel de Railway. Es lo que más rinde para
que las descargas no tarden 80 s.

Los proxies gratuitos duran **30-60 min**. Cuando se mueren hay que volver a
correr esto.

## `altura.py` — qué calidad da cada client

```bash
python3 tools/diagnostico/altura.py <proxy> <videoId>
```

Para un proxy dado, prueba `mweb`, `web_embedded`, `android` y la lista
completa de clients, **con y sin el plugin bgutil**
(`youtubepot-bgutilscript:skip=true` y `--no-plugin-dirs`), e imprime la altura
máxima de cada caso.

Solo hace extracción (`-F`), no descarga. Sirve para responder de una vez a
"¿es el plugin del PO token?" y "¿es el orden de los clients?".

## `descarga.py` — descarga real con el comando exacto del servidor

```bash
python3 tools/diagnostico/descarga.py <proxy> <videoId> <quality> <clients>
```

Ejecuta el mismo comando que construye `_proxy_cmd_video` y mide el MP4
resultante con ffprobe.

**Ejecuta esto 3 veces seguidas con el mismo proxy.** Así se descubrió que el
1080p sale 1 de cada 3 veces por el mismo proxy: la escalera alta está
disponible pero la extracción la degrada de forma intermitente. Con una sola
tirada no se ve y se sacan conclusiones equivocadas.

---

# Los `e2e_*.py` — de extremo a extremo, contra la lógica REAL del servidor

Añadidos el 2026-09-30. A diferencia de los de arriba, estos **no sustituyen
nada**: importan `server/server.py` de verdad y llaman a sus métodos
(`_proxy_video_download`, `_proxy_download`) con un handler falso que se traga
la respuesta. Si un arreglo del servidor no funciona, estos lo dicen.

Escriben en `.tmp-e2e/` (ignorado por git).

| Script | Qué ejecuta |
|---|---|
| `e2e_video_directo.py <videoId> <quality>` | La ruta de vídeo normal, por la vía directa |
| `e2e_video_proxy.py <videoId> <quality>` | **La ruta de vídeo por PROXY, con la vía directa cerrada a propósito** |
| `e2e_audio_proxy.py <id1> <id2> ...` | N MP3 seguidos por proxy, con la vía directa cerrada |

## `e2e_video_proxy.py` es EL test que importa

Cierra la vía directa así:

```python
de.direct_path_available = lambda: False      # el breaker, abierto
S.direct_path_available  = lambda: False
de.record_direct_failure = lambda: None       # no realesimentarlo
```

Eso reproduce **exactamente** la condición de Railway — IP de datacenter, vía
directa bloqueada, el proxy como única vía— y es contra la que se cazó el
problema del techo de 360p. Si este test da 360p, el servidor está roto.

Verificado el 2026-09-30 con `cUpOtbCWSRs 1080`:

```
Proxy activo para cUpOtbCWSRs: socks5://212.77.75.25:1088 (35 probados en 29.8s)
*** SERVIDO cUpOtbCWSRs_q1080.mp4  height=1080  size=51407900
ffprobe: h264,1920,1080
=== listo en 110.3s
```

## Avisos que ahorran tiempo

- **Tarda.** Cada descarga por proxy paga su propia búsqueda de proxy (hasta
  `PROXY_SEARCH_BUDGET_S` = 60 s) y luego la descarga. Un vídeo que falla se
  come hasta `PROXY_PHASE_BUDGET_S` = 180 s. Una tanda de 10 MP3 son 10-15 min
  largos. **Córrelo en segundo plano**, no esperes a mirarlo.
- **Los proxies gratuitos son el factor variable.** La tasa de acierto se midió
  en el 4 %, y duran 30-60 min. Un test que hoy pasa puede fallar mañana sin
  que haya cambiado nada del servidor. Por eso hay que leer el log, no solo el
  resultado: `Proxy activo para ...` antes de `SERVIDO` significa que el
  problema fue el proxy, no el código.
- **Usa vídeos que no se hayan descargado nunca**, o la caché de 48 h te
  sirve la segunda petición desde disco y el test no mide nada. Para sacar IDs:
  `curl "http://localhost:8899/api/search?q=...&limit=10"`.
