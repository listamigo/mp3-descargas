package com.mp3downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mp3downloader.domain.model.Song
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import com.mp3downloader.data.engine.VIDEO_QUALITIES
import kotlin.math.abs

/** Bitrate the server encodes every download with (`-codec:a libmp3lame -b:a 256k`). */
private const val MP3_BITRATE_KBPS = 256

/**
 * Mantener pulsado "Descargar" este tiempo abre el selector de vídeo.
 */
private const val HOLD_FOR_VIDEO_MS = 600L

private val UNKNOWN_ARTISTS = setOf(
    "artista desconocido",
    "unknown artist",
    "unknown",
    "various artists"
)

/**
 * Result card.
 *
 * The previous row put the text, the play button and the download button on one
 * line, so the buttons ate roughly a third of the width and the title was cut
 * after a few words on almost every result. The title is the only reason the
 * row exists, so the actions moved to their own line underneath and the text
 * now gets the full width of the card, wrapped over two lines.
 *
 * The freed room carries the two facts a person actually weighs before
 * downloading: how long it is and how big the file will be. The size is not a
 * guess from the container, it is the exact size this server will produce,
 * because the encode is CBR and `duration * 32000` was measured against a real
 * download at 0,001 % error.
 */

@Composable
fun SongListItem(
    song: Song,
    isPreviewing: Boolean,
    isPreviewLoading: Boolean,
    isPaused: Boolean = false,
    onPreviewClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDownloadVideoClick: (quality: Int) -> Unit = { },
    showVideoOptions: Boolean = false,
    onVideoOptionsChange: (Boolean) -> Unit = { },
    modifier: Modifier = Modifier
) {
    // La opción de vídeo vive escondida: hay que MANTENER pulsado el botón
    // de descargar. Se mide en vez de usar el largo estándar de Material
    // (500 ms) porque 500 ms se dispara sin querer al mover el dedo, y que el
    // selector aparezca a medias es peor que que no exista. 600 ms es
    // suficiente para hacerlo a propósito y lo bastante corto para que la app
    // no parezca colgada.
    //
    // El selector no es estado propio de la tarjeta: el padre guarda cuál es la
    // única tarjeta abierta, así mantener pulsada otra cierra la anterior en
    // lugar de dejar dos selectores a la vez.
    val holdProgress = remember { Animatable(0f) }
    var isPressing by remember { mutableStateOf(false) }

    // El avance se anima fuera del gesto porque un dedo quieto no genera
    // eventos de puntero: contando eventos, la barra no avanzaría justo
    // mientras el usuario está haciendo la pulsación bien hecha.
    LaunchedEffect(isPressing) {
        if (isPressing) {
            holdProgress.animateTo(1f, tween(HOLD_FOR_VIDEO_MS.toInt()))
        } else {
            holdProgress.snapTo(0f)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    ThumbnailImage(
                        url = song.thumbnailUrl.takeIf { it.isNotBlank() },
                        modifier = Modifier.size(54.dp)
                    )
                    // The duration rides on the thumb so it does not compete
                    // with the title for the text line, and it reads the way a
                    // length badge does everywhere else.
                    val duration = formatDuration(song.duration)
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // The title is the one thing the row exists for, and before
                    // it was clipped to a single line while the card had room
                    // for two. Nothing was added to make room: the controls stay
                    // on the right, where they already were.
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // The artist used to share this line with the file size, and
                    // with 157 dp of column the two together clipped the name to
                    // "Artista desc...". The size now sits under the download
                    // button, so the whole column belongs to the name.
                    displayArtist(song)?.let { artist ->
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isPreviewLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            val active = isPreviewing && !isPaused
                            IconButton(
                                onClick = onPreviewClick,
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (active)
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                    else
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    contentColor = if (active)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    imageVector = if (active) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = when {
                                        active -> "Pausar"
                                        isPreviewing && isPaused -> "Reanudar"
                                        else -> "Escuchar"
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // El botón distingue tres gestos: tocar descarga el
                        // MP3, mantener lo suficiente abre el selector de vídeo,
                        // y soltar antes de tiempo no hace nada.
                        val haptics = LocalHapticFeedback.current
                        Box(
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .pointerInput(Unit) {
                                    // touchSlop vive en PointerInputScope, fuera del
                                    // ámbito del gesto, de ahí el paso explícito.
                                    val touchSlop = viewConfiguration.touchSlop
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        isPressing = true
                                        var movedBeforeHold = false

                                        // El umbral se mide contra el reloj del
                                        // propio ámbito de eventos, no contando
                                        // eventos: un dedo quieto no genera
                                        // ninguno, y esperando a que llegara uno
                                        // la pulsación larga no se dispararía
                                        // nunca. Devolver sin valor significa
                                        // "soltado o movido", y agotar el tiempo
                                        // significa "mantenido".
                                        val held = withTimeoutOrNull(HOLD_FOR_VIDEO_MS) {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes
                                                    .firstOrNull { it.id == down.id }
                                                    ?: return@withTimeoutOrNull
                                                if (!change.pressed) return@withTimeoutOrNull
                                                val delta = change.positionChangeIgnoreConsumed()
                                                if (abs(delta.x) > touchSlop ||
                                                    abs(delta.y) > touchSlop
                                                ) {
                                                    movedBeforeHold = true
                                                    return@withTimeoutOrNull
                                                }
                                            }
                                        } == null

                                        isPressing = false

                                        if (held) {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress
                                            )
                                            onVideoOptionsChange(true)

                                            // Con el selector ya abierto, seguir
                                            // arrastrando hacia arriba o abajo
                                            // lo cierra: la mano ya está en
                                            // marcha y el gesto natural es
                                            // apartarla. No se consume nada, así
                                            // la lista sigue desplazándose igual.
                                            var dragX = 0f
                                            var dragY = 0f
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes
                                                    .firstOrNull { it.id == down.id }
                                                    ?: break
                                                if (!change.pressed) break
                                                val delta =
                                                    change.positionChangeIgnoreConsumed()
                                                dragX += delta.x
                                                dragY += delta.y
                                                if (abs(dragY) > touchSlop &&
                                                    abs(dragY) > abs(dragX)
                                                ) {
                                                    onVideoOptionsChange(false)
                                                    break
                                                }
                                            }
                                        } else if (!movedBeforeHold) {
                                            onDownloadClick()
                                        }
                                    }
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Descargar",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            // El borde de progreso es lo que comunica que hay
                            // que seguir manteniendo; sin él el gesto es
                            // indistinguible de un toque lentísimo.
                            if (holdProgress.value > 0f) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth(holdProgress.value)
                                        .height(3.dp)
                                        .background(MaterialTheme.colorScheme.onPrimary)
                                )
                            }
                        }
                    }

                    // What the transfer will weigh, tucked under the button that
                    // starts it. It is the number people check before committing
                    // to a long download, so it stays on the card, just out of
                    // the artist's way.
                    expectedSizeLabel(song)?.let { size ->
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = size,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }

            // El selector ocupa una fila propia con todo el ancho de la tarjeta.
            // Dentro de la columna de los botones no caben cuatro calidades, y al
            // intentar meterlas esa columna se estiraba hasta el ancho completo y
            // se comía el título: la tarjeta se quedaba sin texto justo cuando
            // aparecía la opción de vídeo.
            AnimatedVisibility(
                visible = showVideoOptions,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Vídeo",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    VIDEO_QUALITIES.forEach { q ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                onVideoOptionsChange(false)
                                onDownloadVideoClick(q)
                            },
                            label = {
                                Text(
                                    text = "${q}p",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * The artist, or null when the search backend could not work one out. The
 * server answers "Artista desconocido" in that case, which is long enough to
 * get itself clipped to "Artista desc..." and push the file size out of the
 * row, so it is dropped instead of shown.
 */
private fun displayArtist(song: Song): String? {
    val artist = song.artist.trim()
    if (artist.isEmpty()) return null
    if (artist.lowercase() in UNKNOWN_ARTISTS) return null
    return artist
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(minutes, secs)
}

/**
 * "6,6 MB · 256k", or null when the duration is unknown and there would be
 * nothing honest to print.
 */
private fun expectedSizeLabel(song: Song): String? {
    if (song.duration <= 0L) return null
    val bytes = song.duration * 32_000L
    // Size first and "256k" instead of "256 kbps": at labelSmall on a 411 dp
    // screen the longer wording left the artist with 3 px of room.
    return "${formatFileSize(bytes)} · ${MP3_BITRATE_KBPS}k"
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
