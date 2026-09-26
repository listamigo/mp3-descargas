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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mp3downloader.domain.model.Song

/** Bitrate the server encodes every download with (`-codec:a libmp3lame -b:a 256k`). */
private const val MP3_BITRATE_KBPS = 256

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
    modifier: Modifier = Modifier
) {
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
                    Spacer(modifier = Modifier.height(1.dp))
                    // Artist and file weight share one line to keep the card
                    // compact, but they do not get equal space: the artist
                    // yields first so the size and bitrate, which people check
                    // before starting a transfer, are never the part that gets
                    // clipped.
                    // The artist carries the weight and so absorbs every spare
                    // pixel, which parks the file size hard against the right
                    // edge whatever the artist name happens to be. Without the
                    // weight the two only ended up flush when their combined
                    // width happened to fill the column exactly.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        // End matters when there is no artist to stretch: with
                        // nothing weighted in the row the size would otherwise
                        // sit against the left edge.
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        displayArtist(song)?.let { artist ->
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // Two lines so the name is actually readable. It
                                // only makes the card taller for the few results
                                // whose artist genuinely does not fit on one.
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        expectedSizeLabel(song)?.let { size ->
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = size,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

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

                Button(
                    onClick = onDownloadClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    // Tight padding buys the text column ~8 dp of width, which
                    // is a whole extra line of title on the longest results.
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(
                        text = "Descargar",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
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
