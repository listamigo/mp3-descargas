# Research Brief: Backend Gratuito para Gestión de App MP3 Downloader

## Question
¿Cuáles son las mejores alternativas **totalmente gratuitas** (sin tarjeta de crédito, free tier generoso o open source) para crear un backend propio con:
- Sistema de usuarios/autenticación
- Analytics y métricas de uso
- Logging de errores
- Dashboard de administración
- API para conectar la app Android/Desktop existente

## Scope
- **Incluye**: Free tier real (sin tarjeta), open source self-hosted, servicios con tier gratuito permanente
- **Excluye**: Servicios que requieran tarjeta de crédito, free tier temporal (trial), opciones de pago obligatorio
- **Contexto**: App de descarga de MP3 compartida con ~10-50 usuarios, necesidad de escalar gradualmente
- **Presupuesto**: $0 inicial, posibilidad de escalar a pago después si es necesario

## Assumptions
- El usuario tiene conocimientos técnicos moderados
- Prefiere soluciones profesionales y escalables
- Quiere comparar de mejor a menos profesional
- El backend debe integrarse con la app existente (Kotlin/Android + Desktop)

## Angles to Research

### Angle 1: Backend-as-a-Service (BaaS) gratuitos
- Supabase, Firebase, AppWrite, Nhost
- Límites reales del free tier
- Capacidades de auth, database, real-time

### Angle 2: Plataformas de hosting gratuitas con base de datos
- Railway, Render, Fly.io, Koyeb, Cyclic
- Free tier real sin tarjeta
- Soporte de PostgreSQL/MySQL

### Angle 3: Herramientas de analytics y monitoreo gratuitas
- Plausible, Umami, PostHog, Sentry
- Self-hosted vs cloud free tier
- Integración con backend propio

### Angle 4: Frameworks open source para dashboard/admin
- AdminJS, Retool open source, Directus, Strapi
- Self-hosted gratis
- Facilidad de integración

### Angle 5: Stack completo open source (alternativa a BaaS)
- Supabase self-hosted, AppWrite self-hosted
- Costos de infraestructura (Oracle Cloud free tier)
- Complejidad de mantención

### Angle 6: Arquitectura recomendada y comparativa final
- Cuadro comparativo de todas las opciones
- Recomendación por caso de uso
- Plan de implementación sugerido

## Depth
standard (3-5 sub-agents, 15+ sources)

## Date
2026-07-11
