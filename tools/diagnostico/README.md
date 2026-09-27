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
