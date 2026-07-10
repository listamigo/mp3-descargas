# AGENTS.md — Reglas de Codificación Profesional

> **Versión:** 3.0  
> **Propósito:** Prevenir daños al código existente, forzar modularidad paso a paso, y garantizar que cada cambio sea una mejora medible.  
> **Aplica a:** Todo agente de IA en este proyecto.

---

## 🧠 Principios Fundamentales (NO NEGOCIABLES)

### 1. PROTEGER CÓDIGO EXISTENTE
- **ANTES de modificar CUALQUIER función existente**: Lee y analiza completamente su implementación actual.
- **Verifica dependencias**: Identifica qué otras partes del código usan esa función antes de cambiarla.
- **Prueba mental**: Simula mentalmente cómo afectarán tus cambios al flujo existente.
- **Si no estás 100% seguro**: Pregunta antes de modificar. NUNCA asumas.

### 2. MODULARIDAD PASO A PASO
- **Un cambio = Un propósito**: Cada modificación debe tener un objetivo claro y único.
- **Commits atómicos**: Los cambios deben ser independientes y reversibles.
- **Documenta el "por qué"**: Explica la razón de cada cambio, no solo el "qué".
- **Progresión incremental**: Construye sobre lo existente, NO reescribas desde cero.

### 3. OPTIMIZACIÓN INTELIGENTE
- **Analiza antes de optimizar**: Identifica cuellos de botella reales, no supuestos.
- **Métricas claras**: Define cómo medirás la mejora (rendimiento, legibilidad, mantenibilidad).
- **Trade-offs explícitos**: Si optimizas algo, explica qué sacrificas.
- **Mantén la simplicidad**: La mejor optimización es la que NO añade complejidad innecesaria.

---

## 🚫 RESTRICCIONES ESTRICTAS

**NUNCA:**
- Reescribir funciones completas sin analizar su propósito actual.
- Cambiar nombres de funciones/métodos públicos sin actualizar TODOS los usos.
- Eliminar código "que parece inútil" sin preguntar.
- Introducir nuevas dependencias sin justificación.
- Hacer cambios "por si acaso" o "por si mejora".
- Modificar más de 3 archivos sin un plan explícito aprobado.
- Ignorar tests existentes. Si rompes uno, arréglalo ANTES de continuar.

**NUNCA en QThread:**
- Definir señal `finished` en subclase de QThread (sombra la señal nativa `QThread.finished()`, causando `qFatal("QThread: Destroyed while thread is still running")`). Usar nombres únicos: `search_finished`, `thumb_finished`, `download_complete`.
- Usar `worker.deleteLater()` conectado a `finished` — el `deleteLater` programado compite con Python GC y causa crashes intermitentes. En su lugar, mantener referencia vía lista (`_workers`/`_pending`) y remover al terminar (`_unref_worker`/`.pop()`).

**SIEMPRE:**
- Leer antes de escribir.
- Explicar el impacto de cada cambio.
- Proponer cambios reversibles.
- Mantener compatibilidad hacia atrás.
- Documentar decisiones de diseño.
- Ejecutar tests antes y después de cada cambio.

---

## 📐 Protocolo de Trabajo

### Antes de Escribir Código
1. **Lectura completa**: Lee TODO el archivo o módulo relevante antes de proponer cambios.
2. **Mapa de dependencias**: Identifica qué importa y qué es afectado por tus cambios.
3. **Plan explícito**: Lista los pasos específicos que seguirás.
4. **Validación de suposiciones**: Confirma que entiendes el contexto actual.

### Durante la Implementación
1. **Cambios mínimos**: Modifica solo lo estrictamente necesario.
2. **Comentarios de contexto**: Añade comentarios explicando cambios críticos.
3. **Preserva interfaces**: Mantén las firmas de funciones públicas cuando sea posible.
4. **Tests mentales**: Verifica que tus cambios no rompan casos edge existentes.

### Si Algo Sale Mal
1. **Identifica el punto exacto**: No adivines, rastrea el error hasta su origen.
2. **Aislamiento**: Determina si el problema es nuevo o preexistente.
3. **Reversión segura**: Si no puedes arreglarlo rápidamente, revierte al estado funcional anterior.
4. **Análisis post-mortem**: Explica qué falló y cómo evitarlo en el futuro.

---

## 📝 Estructura de Respuestas Obligatoria

### Para Nuevas Funcionalidades
Objetivo: [Qué vamos a construir]
Análisis: [Cómo se integra con el código existente]
Plan:
- [Paso 1]
- [Paso 2]
Impacto: [Qué partes del código se ven afectadas]
Riesgos: [Posibles problemas y cómo mitigarlos]

### Para Optimizaciones
Problema identificado: [Qué está lento/ineficiente]
Métrica actual: [Cómo medimos el problema]
Solución propuesta: [Qué haremos]
Métrica esperada: [Cómo mediremos la mejora]
Trade-offs: [Qué sacrificamos]

### Para Corrección de Bugs
Síntoma: [Qué está fallando]
Causa raíz: [Por qué está fallando]
Ubicación exacta: [Archivo, línea, función]
Solución: [Cómo lo arreglamos]
Verificación: [Cómo confirmamos que funciona]

---

## 🔧 Stack Técnico y Comandos

### Plataformas Objetivo
- **Primaria**: Linux Desktop (Debian/Ubuntu/MX Linux — .deb packaging)
- **Secundaria**: Android (API 24+ — Android 7.0 Nougat en adelante)

### Lenguajes y Frameworks

#### Desktop (Primario) — Python + Qt6
| Componente | Tecnología |
|---|---|
| Lenguaje | Python 3.11+ (100% — prohibido usar Java o Kotlin para desktop) |
| GUI Framework | PySide6 (Qt6) |
| Arquitectura | Signals/slots de Qt con `QThread` workers |
| Audio preview | Pipeline `yt-dlp -o - \| ffmpeg -t 30 -f wav \| aplay` (NO QMediaPlayer — crashea) |
| Thumbnails | `urllib.request` + User-Agent en QThread (NO QNetworkAccessManager — YouTube bloquea) |
| Motor de descargas | `yt-dlp --newline` con regex `pct_re`/`size_re`/`speed_re` para progreso |
| Theme engine | QSS plano en `ui/themes.py` con función `get_theme_qss()` |
| Settings | Singleton JSON en `~/.mp3downloader/settings.json` |
| Paqueteo | PyInstaller + dpkg-deb |
| Estilo | QSS (Qt Style Sheets) |

#### Android (Secundario) — Kotlin Multiplatform
| Componente | Tecnología |
|---|---|
| Lenguaje | Kotlin 100% |
| UI Framework | Jetpack Compose Multiplatform |
| DI | Koin 4.0 (usar `KoinApplication(application = { modules(...) })`) |
| Red | Ktor 2.3 (propiedades sin paréntesis: `response.contentLength` no `contentLength()`) |
| Compile SDK | 35 |
| Min SDK | 24 |
| JDK | OpenJDK 21+ (requiere JDK completo con `jlink`, no solo JRE) |

### Convenciones de Código

#### Python (Desktop)
| Elemento | Convención | Ejemplo |
|---|---|---|
| Clases | PascalCase | `DownloadService`, `MainWindow` |
| Funciones / Métodos | snake_case | `fetch_audio_url()`, `on_download_clicked` |
| Constantes | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT`, `OUTPUT_DIR` |
| Archivos | snake_case | `download_engine.py`, `main_window.py` |
| Paquetes | minúsculas sin guiones | `services`, `models`, `ui` |
| Signals Qt | prefijo `on_` + widget | `on_play_clicked`, `search_finished` |
| QThread signals | NO sombrear `finished` | usar `search_finished`, `thumb_finished`, `download_complete` |
| QThread cleanup | referencia en lista + `_unref_worker` | NO `deleteLater` |
| Type hints | Obligatorios | `def search(query: str) -> list[Song]:` |

#### Kotlin (Android)
| Elemento | Convención | Ejemplo |
|---|---|---|
| Clases / Interfaces | PascalCase | `UserRepository`, `MainViewModel` |
| Funciones / Variables | camelCase | `fetchUserData()`, `isLoading` |
| Archivos | PascalCase | `UserRepository.kt` |
| Paquetes | dominio invertido | `com.mp3downloader.data.engine` |
| Compose Components | PascalCase | `UserProfileCard()`, `MainScreen()` |

### 📦 Estructura del Proyecto

```
/home/elimdavid/mp3 downloader/
├── desktop/                        # 🐍 App Python (PySide6)
│   ├── src/
│   │   ├── main.py                 # Entry point, excepthook, dep check
│   │   ├── models/
│   │   │   └── song.py             # Song, DownloadTask, DownloadStatus
│   │   ├── services/
│   │   │   ├── download_engine.py  # yt-dlp search/download con progreso
│   │   │   ├── audio_player.py     # yt-dlp | ffmpeg | aplay
│   │   │   ├── thumbnail_loader.py # ThumbnailWorker QThread
│   │   │   └── history_manager.py  # JSON persistente
│   │   ├── ui/
│   │   │   ├── main_window.py      # MainWindow, SearchWorker, DownloadWorker
│   │   │   ├── search_tab.py       # Búsqueda con thumbnails + Load More
│   │   │   ├── downloads_tab.py    # Lista seccionada de descargas
│   │   │   ├── settings_tab.py     # Tema, wallpaper, info app
│   │   │   ├── themes.py           # QSS: dark, light, glass, sakura
│   │   │   └── widgets/
│   │   │       ├── song_item.py    # Fila de resultado
│   │   │       └── download_item.py # Progreso + speed/size en tiempo real
│   │   └── utils/
│   │       ├── settings_manager.py # Singleton JSON
│   │       └── helpers.py          # format_duration, sanitize, etc.
│   ├── venv/                       # Python venv (PySide6 6.11.1)
│   ├── requirements.txt            # PySide6>=6.6.0
│   └── dist/                       # PyInstaller output
├── composeApp/                     # 📱 App Android (Kotlin KMP)
│   ├── build.gradle.kts            # Android + Desktop targets
│   ├── src/
│   │   ├── androidMain/            # Android-specific (PipedApiEngine, Koin)
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── kotlin/com/mp3downloader/
│   │   │   └── res/                # icons, themes, colors
│   │   ├── commonMain/             # Shared: screens, ViewModel, domain
│   │   │   └── kotlin/com/mp3downloader/
│   │   │       ├── App.kt
│   │   │       ├── ui/             # Material3 screens
│   │   │       ├── di/             # Koin modules
│   │   │       ├── data/           # Repository, DTOs
│   │   │       └── domain/         # Models, services
│   │   └── desktopMain/            # Desktop (Compose for Desktop, unused)
│   └── build/outputs/apk/debug/    # APK output
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

### Temas Disponibles
| Tema | Clave | Fondo | Acento | Paleta |
|---|---|---|---|---|
| Oscuro | `dark` | `#121212` | `#1DB954` verde neón |
| Claro | `light` | `#FFFFFF` | `#1565C0` azul marino |
| Vidrio | `glass` | transparente | `#CE93D8` / `#9C27B0` púrpura |
| Sakura | `sakura` | `#FFF0F5` | `#E91E63` rosa |

### Comandos de Build

#### Desktop (Python) — Ejecutar
```bash
cd "/home/elimdavid/mp3 downloader/desktop"
source venv/bin/activate
python src/main.py
```

#### Desktop (Python) — Empaquetar .deb
```bash
cd "/home/elimdavid/mp3 downloader/desktop"
source venv/bin/activate
pip install pyinstaller
pyinstaller --onefile --windowed --name mp3-downloader \
  --add-data "src:src" \
  --hidden-import PySide6.QtCore \
  --hidden-import PySide6.QtWidgets \
  --hidden-import PySide6.QtGui \
  --paths src src/main.py

# Crear .deb
cd "/home/elimdavid/mp3 downloader"
mkdir -p deb-package/DEBIAN deb-package/usr/bin deb-package/usr/share/applications
cp desktop/dist/mp3-downloader deb-package/usr/bin/
# Escribir DEBIAN/control y .desktop, luego:
dpkg-deb --build --root-owner-group deb-package mp3-downloader_1.0.0_amd64.deb
sudo dpkg -i mp3-downloader_1.0.0_amd64.deb
```

#### Android — APK Debug
```bash
cd "/home/elimdavid/mp3 downloader"
export ANDROID_HOME="$HOME/Android/Sdk"
source "$HOME/.sdkman/bin/sdkman-init.sh"  # JDK 21+ con jlink
./gradlew :composeApp:assembleDebug

# Instalar en dispositivo
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### Errores Conocidos y Soluciones

| Síntoma | Causa | Solución |
|---|---|---|
| `QThread: Destroyed while thread '' is still running` + `Abortado` | Señal `finished` sombrea `QThread.finished()`; `deleteLater` + GC compiten | Renombrar señal (`search_finished`, etc.); eliminar `deleteLater`; usar `_track_worker`/`_unref_worker` |
| `NameError: name 'Qt' is not defined` | Falta `from PySide6.QtCore import Qt` | Agregar el import |
| `jlink executable ... does not exist` | Solo JRE instalado, falta JDK | Instalar JDK vía sdkman: `source ~/.sdkman/bin/sdkman-init.sh && sdk install java 21.0.6-amzn` |
| `AAPT: error: resource mipmap/ic_launcher not found` | Faltan resources Android (icon, theme) | Crear `res/mipmap-anydpi-v26/ic_launcher.xml`, `res/values/themes.xml`, `res/drawable/` |
| `Unresolved reference 'contentLength'` | Ktor 2.3: `contentLength` es propiedad, no función | Usar `response.contentLength` (sin paréntesis) o `response.headers["Content-Length"]?.toLongOrNull()` |
| `KoinApplication: No parameter with name 'modules'` | Koin 4.0: `KoinApplication` solo acepta `application` lambda | `KoinApplication(application = { modules(list) }) { Content() }` |

### Archivos de Configuración y Estado
- `~/.mp3downloader/settings.json` — tema, wallpaper, opacidad
- `~/.mp3downloader/history.json` — historial de descargas (compatible entre desktop Kotlin y Python)
- `~/.mp3downloader/app.log` — excepciones no capturadas (vía `sys.excepthook`)
- `/tmp/mp3downloader_thumbs/` — caché de thumbnails

### Dependencias del Sistema (Desktop Linux)
```bash
sudo apt install yt-dlp ffmpeg alsa-utils    # runtime
# ffplay NO es necesario
```

---

## 🔄 Flujo de Trabajo Ideal

1. **Entender**: Lee y comprende el contexto completo.
2. **Planificar**: Define pasos claros y secuenciales.
3. **Implementar**: Ejecuta cambios mínimos y precisos.
4. **Validar**: Verifica que nada se rompió.
5. **Documentar**: Explica qué hiciste y por qué.

---

## ⚠️ Ejemplo de Buen vs. Mal Comportamiento

**❌ MAL:**
> "Voy a reescribir esta función para que sea más limpia"

**✅ BIEN:**
> "Analicé la función `procesarDatos()`. Actualmente hace X, Y, Z. Propongo extraer la lógica Z en una función separada `validarEntrada()` porque:
> 1. Mejora la legibilidad (función más corta)
> 2. Permite reutilizar la validación en otros lugares
> 3. Facilita testing unitario
> La función original mantendrá su interfaz pública, solo cambiará su implementación interna."

---

## 🔗 Referencias Externas (Opcional)

Si tienes documentación adicional, referénciala aquí. OpenCode puede cargar archivos externos cuando los necesite:

- Guías de estilo: `@docs/style-guide.md`
- Arquitectura del sistema: `@docs/architecture.md`
- API documentation: `@docs/api.md`
