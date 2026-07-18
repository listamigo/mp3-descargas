package com.mp3downloader.domain.service

/**
 * Resuelve la URL de miniatura de YouTube de la máxima calidad disponible
 * para usar como carátula incrustada en MP3/M4A.
 *
 * YouTube ofrece estos tamaños de miniatura:
 *   default.jpg        → 120×90  (muy baja, pixelada)
 *   mqdefault.jpg      → 320×180
 *   hqdefault.jpg      → 480×360  (segura, casi siempre disponible)
 *   sddefault.jpg      → 640×480  (buena calidad, amplia disponibilidad)
 *   maxresdefault.jpg  → 1920×1080 (máxima, no disponible en videos antiguos)
 *
 * Uso: llama a resolveBestThumbnailUrl() para obtener la URL de máxima calidad.
 * Si el reproductor/meta-data writer detecta que la imagen descargada es muy
 * pequeña (placeholder 1x1 de YouTube), debe llamar a generateFallbackChain()
 * y probar cada URL hasta obtener una imagen válida.
 */
object ThumbnailQualityResolver {

    private val YT_CDN_REGEX = Regex("https?://i\\.ytimg\\.com/vi/([a-zA-Z0-9_-]{11})/")
    private val QUALITY_ORDER = listOf("maxresdefault", "sddefault", "hqdefault", "mqdefault")

    /**
     * Devuelve la URL de la mejor calidad posible ([maxresdefault]).
     * Es la URL "principal" que debe intentarse primero.
     */
    fun resolveBestThumbnailUrl(videoId: String, originalUrl: String? = null): String {
        val id = if (videoId.isNotBlank()) videoId
                 else extractVideoId(originalUrl)

        if (id.isBlank()) return originalUrl ?: ""
        return "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
    }

    /**
     * Genera una cadena de URLs de miniatura ordenadas de mayor a menor calidad.
     * Ideal para metadata writers que pueden probar cada URL secuencialmente
     * y detenerse cuando encuentren una imagen de tamaño válido.
     *
     * Ejemplo de retorno:
     *   [maxresdefault.jpg, sddefault.jpg, hqdefault.jpg, mqdefault.jpg, original]
     */
    fun generateFallbackChain(videoId: String, originalUrl: String? = null): List<String> {
        val id = if (videoId.isNotBlank()) videoId
                 else extractVideoId(originalUrl)

        if (id.isBlank()) return listOfNotNull(originalUrl?.takeIf { it.isNotBlank() })

        val urls = QUALITY_ORDER.map { quality ->
            "https://i.ytimg.com/vi/$id/${quality}.jpg"
        }

        // Incluir la URL original como último recurso si no se generó ya
        val original = originalUrl?.takeIf {
            it.isNotBlank() && it !in urls
        }

        return urls + listOfNotNull(original)
    }

    /** Extrae el ID de video de YouTube de una URL de miniatura o de video. */
    private fun extractVideoId(url: String?): String {
        if (url.isNullOrBlank()) return ""

        // Formato: https://i.ytimg.com/vi/VIDEO_ID/...
        YT_CDN_REGEX.find(url)?.let { match ->
            return match.groupValues[1]
        }

        // Formato: https://www.youtube.com/watch?v=VIDEO_ID
        val watchRegex = Regex("[?&]v=([a-zA-Z0-9_-]{11})")
        watchRegex.find(url)?.let { match ->
            return match.groupValues[1]
        }

        // Si es solo el ID (11 caracteres alfanuméricos)
        if (url.length == 11 && url.matches(Regex("[a-zA-Z0-9_-]{11}"))) {
            return url
        }

        return ""
    }
}
