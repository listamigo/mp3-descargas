# 🚀 Despliegue en Oracle Cloud Free Tier

Guía paso a paso para tener tu servidor de descargas MP3 funcionando 24/7
en Oracle Cloud Infrastructure (OCI) ARM Free Tier — **totalmente gratis, sin caducidad**.

---

## 📋 Requisitos

| Recurso | Detalle |
|---|---|
| Cuenta Oracle Cloud | [Crear cuenta gratis](https://signup.cloud.oracle.com) (requiere tarjeta de crédito para verificación, no cobran) |
| SSH Client | `ssh` desde terminal |
| App Android | MP3 Downloader instalada en tu celular |

---

## 🏗️ Paso 1: Crear VM ARM en Oracle Cloud

### 1.1 Acceder a OCI Console

1. Ve a [cloud.oracle.com](https://cloud.oracle.com) e inicia sesión
2. Menú ☰ → **Compute** → **Instances**
3. Haz clic en **Create Instance**

### 1.2 Configurar la instancia

| Campo | Valor |
|---|---|
| **Name** | `mp3downloader-server` |
| **Image** | `Canonical Ubuntu 24.04` (o 22.04) |
| **Shape** | Haz clic en **Change shape** → `Ampere` → `VM.Standard.A1.Flex` |
| **OCPUs** | `2` (máximo free) |
| **Memory** | `12 GB` (máximo free) |
| **SSH Keys** | Sube tu clave pública SSH |

> ⚠️ **Importante:** Si no ves la opción ARM, cambia de región (menú arriba a la derecha).
> Las regiones más disponibles: `US East (Ashburn)`, `EU Frankfurt`, `AP Mumbai`.

### 1.3 Anotar la IP

Una vez creada, en la página de la instancia verás:
- **Public IP Address** → `xxx.xxx.xxx.xxx` (apunta esto)
- **Username** → `ubuntu`

---

## 🔌 Paso 2: Abrir puerto 8899 (FIREWALL)

Oracle tiene **dos firewalls**. Si no abres ambos, el servidor no será accesible.

### 2.1 Cloud Firewall (OCI Security List)

1. En la página de tu instancia, haz clic en el enlace de la **Subnet**
2. Haz clic en la **Security List** (la primera de la lista)
3. Botón **Add Ingress Rules**
4. Configura:
   - **Source Type**: CIDR
   - **Source CIDR**: `0.0.0.0/0`
   - **IP Protocol**: TCP
   - **Destination Port Range**: `8899`
   - **Description**: `MP3 Downloader Server`
5. Haz clic en **Add Ingress Rules**

### 2.2 OS Firewall (iptables)

El script de setup lo hace automáticamente, pero si lo haces manual:

```bash
sudo iptables -I INPUT -p tcp --dport 8899 -j ACCEPT
sudo netfilter-persistent save
```

---

## 🚀 Paso 3: Desplegar el servidor

### Opción A: Usando el script de deploy desde tu PC

```bash
# 1. Edita la IP en el script
cd "/home/elimdavid/mp3 downloader/server"
nano deploy/deploy.sh   # Cambia SERVER_IP a tu IP pública

# 2. Ejecuta el deploy
chmod +x deploy/deploy.sh
./deploy/deploy.sh
```

### Opción B: Manual paso a paso

```bash
# 1. Copia los archivos al servidor
rsync -avz --exclude='__pycache__' --exclude='*.pyc' \
  "/home/elimdavid/mp3 downloader/server/" \
  ubuntu@<IP_ORACLE>:~/server/

# 2. SSH al servidor
ssh ubuntu@<IP_ORACLE>

# 3. Ejecuta el setup
cd ~/server
sudo bash deploy/setup.sh

# 4. Verifica que funciona
curl http://127.0.0.1:8899/api/health
```

---

## ✅ Paso 4: Verificar

```bash
# Desde el servidor mismo
curl http://127.0.0.1:8899/api/health

# Desde tu PC (debería responder)
curl http://<IP_ORACLE>:8899/api/health

# Probar búsqueda
curl "http://<IP_ORACLE>:8899/api/search?q=never+gonna+give+you+up"
```

Respuesta esperada:
```json
{
  "status": "ok",
  "has_cookies": false,
  "yt_dlp_version": "2025.12.01",
  "uptime": "0h 1m"
}
```

---

## 🍪 Paso 5: Configurar cookies (RECOMENDADO)

Sin cookies, YouTube bloquea IPs de datacenter. Las cookies autentican
las peticiones como un usuario real.

Sigue la guía detallada: [cookies_guide.md](cookies_guide.md)

---

## 📱 Paso 6: Conectar la app Android

1. Abre **MP3 Downloader** en tu Android
2. Toca el ícono ⚙️ **Ajustes** (arriba a la derecha)
3. En **URL del servidor**, ingresa:
   ```
   http://<IP_ORACLE>:8899
   ```
4. Toca **Guardar**
5. La app usará tu servidor Oracle como primer motor de descarga

> 💡 La app ya tiene lógica de fallback: si el servidor Oracle falla,
> intenta con Invidious y Piped automáticamente.

---

## 📊 Monitoreo y Mantenimiento

```bash
# Ver estado del servicio
sudo systemctl status mp3downloader

# Ver logs en tiempo real
sudo journalctl -u mp3downloader -f

# Ver logs de archivo
tail -f /opt/mp3downloader/logs/server.log

# Reiniciar el servidor
sudo systemctl restart mp3downloader

# Actualizar yt-dlp manualmente
curl -X POST http://127.0.0.1:8899/api/update
```

---

## 🔄 Actualizar el servidor

Cuando hagas cambios al código en tu PC:

```bash
cd "/home/elimdavid/mp3 downloader/server"
./deploy/deploy.sh
```

Esto sube los archivos nuevos y reinicia el servicio automáticamente.

---

## 💰 Costos

| Recurso | Costo | Detalle |
|---|---|---|
| VM ARM (2 OCPU, 12GB RAM) | **$0/mes** | Always Free |
| Storage (200 GB) | **$0/mes** | Always Free |
| Ancho de banda (10 TB/mes) | **$0/mes** | Always Free |
| **Total** | **$0/mes** | ✅ Sin caducidad |

Oracle no te cobrará **siempre y cuando**:
- No superes los límites free (2 OCPU ARM, 12GB RAM)
- La VM esté activa (tu servidor corre 24/7, así que está bien)

---

## ❓ Solución de problemas

| Problema | Solución |
|---|---|
| `Connection refused` | El firewall de OCI no está abierto (Paso 2.1) |
| `Connection timeout` | El firewall del SO no está abierto (Paso 2.2) |
| `HTTP Error 403` en descargas | Faltan cookies — sigue la [guía de cookies](cookies_guide.md) |
| `yt-dlp` no encontrado | El script de setup no se ejecutó correctamente |
| Servidor no responde | `sudo systemctl restart mp3downloader` |
| La app Android no se conecta | Verifica que la IP sea correcta y el puerto 8899 esté abierto |
