package com.mp3downloader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.DownloadTask

@Composable
fun DownloadItem(
    task: DownloadTask,
    onCancel: () -> Unit,
    onOpenFolder: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isActive = task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.QUEUED
    val isCompleted = task.status == DownloadStatus.COMPLETED
    val isFailed = task.status == DownloadStatus.FAILED

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCompleted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                isFailed -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = statusIcon(task.status),
                    contentDescription = null,
                    tint = statusColor(task.status),
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = statusText(task.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(task.status)
                    )
                }

                if (isActive) {
                    Button(
                        onClick = onCancel,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (isCompleted && onOpenFolder != null) {
                    Button(
                        onClick = onOpenFolder,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        Text("Open", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (isFailed && onRetry != null) {
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (task.status == DownloadStatus.DOWNLOADING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            if (isCompleted && task.outputPath != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = task.outputPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isFailed && task.error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = task.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun statusIcon(status: DownloadStatus) = when (status) {
    DownloadStatus.COMPLETED -> Icons.Rounded.CheckCircle
    DownloadStatus.FAILED -> Icons.Rounded.Cancel
    else -> Icons.Rounded.MusicNote
}

private fun statusText(status: DownloadStatus): String = when (status) {
    DownloadStatus.IDLE -> "Idle"
    DownloadStatus.QUEUED -> "Waiting..."
    DownloadStatus.DOWNLOADING -> "Downloading..."
    DownloadStatus.CONVERTING -> "Converting..."
    DownloadStatus.COMPLETED -> "Completed"
    DownloadStatus.FAILED -> "Failed"
}

@Composable
private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
