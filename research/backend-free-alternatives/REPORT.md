# Reporte: Alternativas Gratuitas para Backend de MP3 Downloader

> Investigación realizada el 2026-07-11. Todas las opciones verificadas con fuentes oficiales.

## Resumen Ejecutivo

Para tu app MP3 Downloader compartida con 10-50 usuarios, existen **3 estrategias viables** sin tarjeta de crédito, ordenadas de mejor a menos profesional:

| Rango | Estrategia | Coste | Complejidad | Escalabilidad |
|-------|-----------|-------|-------------|---------------|
| 1 | **Supabase Cloud Free** | $0 | Baja | Alta |
| 2 | **Supabase self-hosted en Oracle Cloud** | $0 | Media | Muy alta |
| 3 | **Render + PostHog + AdminJS** | $0 | Media | Media |

---

## 1. Supabase Cloud Free (RECOMENDADO)

**La mejor opción para empezar hoy mismo.**

### Qué incluye gratis (sin tarjeta)
| Recurso | Límite Free |
|---------|-------------|
| Usuarios (MAU) | 50,000 |
| Base de datos PostgreSQL | 500 MB |
| Storage (archivos) | 1 GB |
| Egress (transferencia) | 5 GB/mes |
| Realtime connections | 200 concurrentes |
| Edge Functions | 500,000 invocaciones/mes |
| Auth (OAuth, MFA, anonymous) | Incluido |
| API REST y GraphQL | Incluido |

### Ventajas
- PostgreSQL real (no NoSQL propietario)
- Auth completo con OAuth social (Google, GitHub, etc.)
- Realtime para actualizaciones en vivo
- Dashboard de administración incluido
- API REST y GraphQL automáticas
- Instalación en 5 minutos

### Limitaciones
- **Se pausa después de 1 semana sin actividad** (crítico para producción)
- Máximo 2 proyectos activos
- 500 MB de base de datos (suficiente para metadata de usuarios/descargas)

### Cómo mantenerlo activo
- Script de cron job que haga ping cada 6 días
- O migrar a plan Pro ($25/mes) cuando escale

### Integración con tu app
```kotlin
// Ejemplo: registrar descarga en Supabase
val supabase = createSupabaseClient(
    supabaseUrl = "https://tu-proyecto.supabase.co",
    supabaseKey = "tu-anon-key"
)

suspend fun logDownload(userId: String, videoId: String) {
    supabase.from("downloads").insert(mapOf(
        "user_id" to userId,
        "video_id" to videoId,
        "timestamp" to Clock.System.now().toString()
    ))
}
```

---

## 2. Supabase Self-Hosted en Oracle Cloud Free

**Para control total y escalabilidad ilimitada.**

### Infraestructura: Oracle Cloud Always Free
| Recurso | Disponible |
|---------|------------|
| ARM Ampere A1 cores | 4 |
| RAM | 24 GB |
| Block storage | 200 GB |
| Object storage | 20 GB |
| Transferencia saliente | 10 TB/mes |
| Coste | **$0 permanente** |

### Requisitos de Supabase self-hosted
| Componente | Mínimo | Recomendado |
|-----------|--------|-------------|
| RAM | 4 GB | 8+ GB |
| CPU | 2 cores | 4+ cores |
| Disco | 40 GB SSD | 80+ GB SSD |
| Contenedores | ~8-10 | ~8-10 |

### Stack que obtienes
- PostgreSQL con acceso completo
- Auth (GoTrue)
- Realtime
- Storage
- Edge Functions
- Dashboard de administración
- API REST (PostgREST) y GraphQL

### Ventajas
- Sin límites de usuarios
- Sin pausas por inactividad
- Control total sobre datos
- Escalable a cualquier tamaño

### Desventajas
- Tú mantienes seguridad, backups, actualizaciones
- Actualizaciones causan downtime (~15 min/mes)
- Curva de aprendizaje inicial

### Instalación rápida
```bash
# En tu VM de Oracle Cloud
git clone --depth 1 https://github.com/supabase/supabase
cd supabase/docker
cp .env.example .env
# Editar .env con tus secretos
docker compose up -d
```

---

## 3. Render + PostHog + AdminJS (Alternativa modular)

**Para quien prefiera componentes separados.**

### Render (Hosting + Base de datos)
| Recurso | Límite Free |
|---------|-------------|
| Web service | 512 MB RAM, 0.1 CPU |
| Postgres | 256 MB RAM, 1 GB storage |
| Bandwidth | 5 GB/mes |
| **Limitación crítica** | Postgres expira a 30 días |

### PostHog Cloud (Analytics + Errores)
| Recurso | Límite Free |
|---------|-------------|
| Analytics events | 1,000,000/mes |
| Session replays | 5,000/mes |
| Feature flags | 1,000,000/mes |
| Error tracking | 100,000 excepciones/mes |
| Surveys | 1,500 respuestas/mes |

### AdminJS o Strapi (Dashboard)
| Opción | Licencia | Integración |
|--------|----------|-------------|
| AdminJS | MIT | Express, Nest.js, Prisma |
| Strapi | MIT | Cualquier DB SQL |
| Directus | MSCL (gratis < $5M revenue) | Cualquier DB SQL |

### Limitaciones importantes
- Render free Postgres expira a 30 días (inestable para producción)
- Render web service se apaga tras 15 min sin tráfico
- Necesitas integrar 3 servicios separados

---

## Comparativa Detallada

### Auth y Usuarios

| Plataforma | Usuarios gratis | OAuth social | MFA | Anonymous |
|-----------|----------------|--------------|-----|-----------|
| Supabase Cloud | 50,000 | Sí | Sí | Sí |
| Firebase | 50,000 | Sí | Sí | No |
| Appwrite | 75,000 | Sí | Sí | Sí |
| Nhost | Ilimitados | Sí | Sí (WebAuthn) | No |

### Base de datos

| Plataforma | Tipo | Tamaño gratis | Acceso completo |
|-----------|------|---------------|-----------------|
| Supabase | PostgreSQL | 500 MB | Sí |
| Firebase | Firestore (NoSQL) | 1 GiB | No (API propietaria) |
| Appwrite | MariaDB/MongoDB | 2 GB | Parcial |
| Nhost | PostgreSQL | 1 GB | Sí |

### Dashboard de administración

| Opción | Tipo | Gratis | Personalizable |
|--------|------|--------|----------------|
| Supabase Dashboard | Incluido | Sí | Limitado |
| AdminJS | Open source (MIT) | Sí | Total |
| Strapi | Open source (MIT) | Sí | Total |
| Directus | Source-available | Sí (< $5M) | Total |
| Retool | Propietario | Sí (5 usuarios) | Parcial |

### Analytics y Monitoreo

| Herramienta | Events gratis | Self-hosted | Errores |
|------------|---------------|-------------|---------|
| PostHog Cloud | 1M/mes | Sí (MIT, 16GB RAM) | 100K/mes |
| Umami | Ilimitados | Sí (MIT, ligero) | No |
| Sentry | 5K/mes | Sí (BSL) | Sí |
| Plausible | N/A | Sí (AGPL, sin premium) | No |

---

## Recomendación Final

### Para empezar HOY (sin infraestructura)
```
Supabase Cloud Free
├── Auth (50K usuarios)
├── PostgreSQL (500 MB)
├── Realtime
├── Edge Functions
└── Dashboard incluido
```
**Coste**: $0 | **Tiempo de setup**: 30 min | **Limitación**: pausa tras 1 semana sin actividad

### Para producción seria (control total)
```
Oracle Cloud Always Free (VM ARM)
├── Supabase self-hosted
│   ├── PostgreSQL
│   ├── Auth
│   ├── Realtime
│   └── Storage
├── PostHog Cloud Free (analytics)
└── AdminJS self-hosted (dashboard)
```
**Coste**: $0 | **Tiempo de setup**: 2-4 horas | **Limitación**: mantención manual

### Para escalabilidad máxima
```
Supabase Pro ($25/mes)
├── Sin pausas
├── 8 GB database
├── 100 GB storage
├── Priority support
└── Soporte de backup automático
```
**Coste**: $25/mes | **Escalable**: 100K+ usuarios

---

## Preguntas Abiertas

1. **¿Necesitas analytics de la app móvil o solo del backend?** Si es de la app, Firebase Analytics (gratis) podría complementar Supabase.

2. **¿Cuánto tráfico esperas?** Para 10-50 usuarios activos, Supabase Cloud Free es más que suficiente.

3. **¿Necesitas landing page?** Puedes usar Vercel/Netlify (gratis) con Next.js/Nuxt.js.

---

## Fuentes Consultadas

1. Supabase Pricing - https://supabase.com/pricing
2. Firebase Pricing - https://firebase.google.com/pricing
3. Appwrite Pricing - https://appwrite.io/pricing
4. Nhost Pricing - https://nhost.io/pricing
5. Render Free Tier Docs - https://docs.render.com/free
6. PostHog Pricing - https://posthog.com/pricing
7. Sentry Pricing - https://sentry.io/pricing/
8. Oracle Cloud Free - https://www.oracle.com/cloud/free/
9. Supabase Self-Hosting - https://supabase.com/docs/guides/self-hosting/docker
10. AdminJS - https://adminjs.co/
11. Strapi - https://github.com/strapi/strapi
12. Directus - https://github.com/directus/directus
