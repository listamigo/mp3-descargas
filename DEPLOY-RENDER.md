# 🚀 Guía de Despliegue en Render (Free Tier)

## ✅ Ventajas de Render

- **Sin tarjeta de crédito** para el tier gratuito
- **750 horas gratis/mes** (suficiente para uso personal)
- **Docker nativo** — tu Dockerfile funciona tal cual
- **Deploy automático** — push a GitHub = redeploy
- **Sin configuración de infraestructura**

## ⚠️ Limitaciones del Free Tier

| Característica | Limitación |
|----------------|------------|
| **Spin-down** | Después de 15 min sin tráfico |
| **Cold start** | ~30-50 segundos al reiniciar |
| **RAM** | 512 MB (suficiente para yt-dlp) |
| **Almacenamiento** | Efímero (se pierde en redeploys) |
| **Horas** | 750/mes (~31 días de uso continuo) |

## 📋 Pasos para Desplegar

### 1. Preparar el repositorio

Asegúrate de que tu código esté en GitHub:
```bash
cd /home/elimdavid/mp3\ downloader/
git add .
git commit -m "Optimizar render.yaml para deploy"
git push origin main
```

### 2. Crear cuenta en Render

1. Ve a [render.com](https://render.com/)
2. Regístrate con GitHub
3. Autoriza el acceso a tu repositorio

### 3. Crear el servicio

1. En el dashboard de Render, haz clic en **"New +"**
2. Selecciona **"Web Service"**
3. Conecta tu repositorio de GitHub
4. Configura:
   - **Name**: `mp3downloader-server`
   - **Runtime**: `Docker`
   - **Dockerfile Path**: `./Dockerfile`
   - **Port**: `8899`
   - **Branch**: `main`

### 4. Variables de entorno (opcional)

Si necesitas configurar variables adicionales, agrégalas en la sección **Environment**:

```
PORT=8899
LOG_LEVEL=INFO
YTDLP_VERSION=2026.6.9
COOKIES_B64=<base64-de-tus-cookies>
```

### 5. Deploy

1. Haz clic en **"Create Web Service"**
2. Render construirá e implementará automáticamente
3. Tu servidor estará disponible en: `https://mp3downloader-server.onrender.com`

## 🔧 Configuración del Cliente Android

Actualiza la URL del servidor en tu app Android:

```kotlin
// En tu código de Kotlin
const val SERVER_URL = "https://mp3downloader-server.onrender.com"
```

## 📊 Monitoreo

### Verificar salud del servidor
```bash
curl https://mp3downloader-server.onrender.com/api/health
```

### Ver logs en Render
1. Ve al dashboard de Render
2. Selecciona tu servicio
3. Haz clic en **"Logs"**

## 🔄 Manejo del Spin-down

### Problema
El servidor se duerme después de 15 min sin tráfico. Al recibir una petición, tarda ~30-50s en despertar.

### Soluciones

#### Opción 1: Ping automático (recomendado)
Crea un cron job que haga ping cada 10 minutos:

```bash
# En tu PC local o en otro servidor
crontab -e

# Agregar línea (cada 10 minutos)
*/10 * * * * curl -s https://mp3downloader-server.onrender.com/api/health > /dev/null
```

#### Opción 2: UptimeRobot (gratis)
1. Ve a [uptimerobot.com](https://uptimerobot.com/)
2. Crea una cuenta gratuita
3. Agrega un monitor HTTP para tu URL
4. Configura intervalo de 5 minutos

#### Opción 3: Accept cold starts
Simplemente aceptar que la primera petición después de 15 min será lenta.

## 🛠️ Persistencia de Cookies

Las cookies se pierden en cada redeploy. Para solucionar esto:

### Paso 1: Convertir cookies a Base64
```bash
base64 -w 0 /home/elimdavid/.mp3downloader/cookies/cookies.txt
```

### Paso 2: Guardar en variable de entorno
En Render:
1. Ve a **Environment**
2. Agrega: `COOKIES_B64` = el resultado del paso anterior

### Paso 3: El servidor restaurará automáticamente
El código ya maneja esto en `restore_cookies_from_env()`.

## 🐛 Solución de Problemas

### Error: "No such file or directory"
Verifica que el Dockerfile copie correctamente los archivos:
```dockerfile
COPY server/server.py .
COPY server/download_engine.py .
COPY server/models/ ./models/
COPY server/utils/ ./utils/
```

### Error: "ffmpeg not found"
Render free tier usa contenedores ligeros. ffmpeg ya está en tu Dockerfile.

### Error: "yt-dlp failed"
YouTube puede estar bloqueando. Verifica:
1. La versión de yt-dlp: `curl https://tu-servidor/api/health`
2. Si necesitas actualizar el pin en el Dockerfile

## 📈 Alternativas si Render no es suficiente

Si necesitas más recursos:

### 1. Render Starter ($7/mes)
- Sin spin-down
- 512 MB RAM
- 750 horas/mes

### 2. Hugging Face Spaces (gratis)
Ya tienes `Dockerfile.hf` configurado:
- 2 vCPU, 16GB RAM
- Sin spin-down
- Puerto 7860

### 3. Fly.io (gratis, requiere tarjeta)
- 3 VMs de 256MB
- Sin spin-down
- 160GB bandwidth/mes

## 📚 Recursos

- [Render Documentation](https://render.com/docs)
- [Render Free Tier Limits](https://render.com/docs/free)
- [Docker on Render](https://render.com/docs/docker)

---

**Última actualización**: 2026-08-28
**Versión de yt-dlp**: 2026.6.9
**Estado**: ✅ Listo para desplegar
