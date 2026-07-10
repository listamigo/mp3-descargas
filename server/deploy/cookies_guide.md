# 🍪 Guía de Cookies de YouTube

Para que yt-dlp funcione correctamente en el servidor de Oracle Cloud,
necesita **cookies de YouTube** (una sesión real de navegador).
Sin cookies, YouTube puede bloquear o limitar las descargas.

---

## 📱 Opción 1: Subir cookies desde la app Android (recomendado)

La app Android tiene un endpoint para subir cookies al servidor.

### Obtener cookies.txt en Android

1. Instala **Kiwi Browser** (o Firefox con la extensión "cookies.txt")
2. Ve a https://youtube.com e inicia sesión con tu cuenta
3. Usa una extensión como **"cookies.txt export"** para exportar las cookies
4. Guarda el archivo `cookies.txt` en tu teléfono

### Subir al servidor desde la app

Actualmente la app no tiene UI para subir cookies directamente,
pero puedes hacerlo con una app HTTP como **HTTP Shortcuts** o **Termux**:

```bash
curl -X POST http://<IP_ORACLE>:8899/api/cookies \
  --data-binary @/ruta/a/cookies.txt
```

---

## 💻 Opción 2: Subir cookies desde tu PC

### 2.1 Exportar cookies desde Chrome

1. Instala la extensión **"Get cookies.txt LOCALLY"** (recomendada, código abierto)
2. Ve a https://youtube.com e inicia sesión
3. Haz clic en la extensión → "Export"
4. Guarda como `cookies.txt`

### 2.2 Subir al servidor

```bash
# Desde tu PC
scp cookies.txt ubuntu@<IP_ORACLE>:/tmp/cookies.txt

# Luego SSH al servidor y moverlo
ssh ubuntu@<IP_ORACLE>
sudo cp /tmp/cookies.txt /opt/mp3downloader/cookies/cookies.txt
sudo systemctl restart mp3downloader
```

### 2.3 Verificar que funciona

```bash
curl http://<IP_ORACLE>:8899/api/health
# Debería mostrar: "has_cookies": true
```

---

## 🔄 Las cookies expiran — ¿Qué hacer?

Las cookies de YouTube duran entre 1 y 6 meses.
Cuando dejen de funcionar:

1. Exporta cookies nuevas desde el navegador
2. Súbelas al servidor con el mismo método
3. Reinicia el servidor: `sudo systemctl restart mp3downloader`

### ⚡ Automatización (avanzado)

Puedes crear un script que renueve cookies automáticamente:

```bash
#!/bin/bash
# En tu PC, programa con cron para ejecutar cada mes:
scp ~/Downloads/cookies.txt ubuntu@<IP_ORACLE>:/tmp/cookies.txt
ssh ubuntu@<IP_ORACLE> "sudo cp /tmp/cookies.txt /opt/mp3downloader/cookies/cookies.txt && sudo systemctl restart mp3downloader"
```

---

## ❌ Sin cookies — ¿Sigue funcionando?

**Sí**, pero YouTube aplica restricciones más estrictas a IPs de datacenter.
Sin cookies:
- Las búsquedas funcionan parcialmente
- Las descargas pueden fallar con errores como "HTTP Error 403"
- YouTube puede devolver "Sign in to confirm you're not a bot"

**Con cookies**, el servidor se comporta como un usuario normal y funciona mucho mejor.
