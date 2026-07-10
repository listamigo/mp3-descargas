package com.mp3downloader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mp3downloader.data.engine.RemoteConfig
import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.ui.AppTab
import com.mp3downloader.ui.MainViewModel
import com.mp3downloader.ui.components.DownloadItem
import com.mp3downloader.ui.components.SearchBar
import com.mp3downloader.ui.components.SongListItem


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val previewingSongId by viewModel.previewingSongId.collectAsState()
    val previewLoading by viewModel.previewLoading.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { event ->
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                event.action?.invoke()
            }
        }
    }

    LaunchedEffect(searchError) {
        if (searchError != null) {
            delay(2000)
            viewModel.clearError()
        }
    }

    if (showSettings) {
        SettingsDialog(
            currentUrl = RemoteConfig.serverUrl ?: "",
            onDismiss = { showSettings = false },
            onSave = { url ->
                RemoteConfig.serverUrl = url.ifBlank { null }
                showSettings = false
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "MP3 Downloader",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                AppTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            when (selectedTab) {
                AppTab.SEARCH -> SearchTab(
                    searchQuery = searchQuery,
                    onQueryChange = viewModel::onSearchQueryChange,
                    onSearch = viewModel::search,
                    isSearching = isSearching,
                    searchError = searchError,
                    searchResults = searchResults,
                    previewingSongId = previewingSongId,
                    previewLoading = previewLoading,
                    onPreviewClick = viewModel::togglePreview,
                    onDownloadClick = viewModel::download,
                    onErrorDismiss = viewModel::clearError
                )
                AppTab.DOWNLOADS -> DownloadsTab(
                    downloads = downloads,
                    onCancel = viewModel::cancelDownload,
                    onRetry = viewModel::retryDownload,
                    onClearFinished = viewModel::clearFinishedDownloads
                )
            }
        }
    }
}

@Composable
private fun SearchTab(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    searchError: String?,
    searchResults: List<com.mp3downloader.domain.model.Song>,
    previewingSongId: String?,
    previewLoading: String?,
    onPreviewClick: (com.mp3downloader.domain.model.Song) -> Unit,
    onDownloadClick: (com.mp3downloader.domain.model.Song) -> Unit,
    onErrorDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = searchQuery,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            isLoading = isSearching
        )

        if (searchError != null) {
            Text(
                text = searchError,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable { onErrorDismiss() }
            )
        }

        if (searchResults.isEmpty() && !isSearching && searchError == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
            ) {
                Text(
                    text = if (searchQuery.isBlank())
                        "Search for music to download"
                    else
                        "No results found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(searchResults, key = { it.id }) { song ->
                    SongListItem(
                        song = song,
                        isPreviewing = previewingSongId == song.id,
                        isPreviewLoading = previewLoading == song.id,
                        onPreviewClick = { onPreviewClick(song) },
                        onDownloadClick = { onDownloadClick(song) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsTab(
    downloads: List<com.mp3downloader.domain.model.DownloadTask>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onClearFinished: () -> Unit = {}
) {
    val active = downloads.filter {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
    }
    val completed = downloads.filter { it.status == DownloadStatus.COMPLETED }
    val failed = downloads.filter { it.status == DownloadStatus.FAILED }
    val hasFinished = completed.isNotEmpty() || failed.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        if (active.isNotEmpty()) {
            item {
                SectionHeader("Active Downloads (${active.size})")
            }
            items(active, key = { "active_${it.song.id}" }) { task ->
                DownloadItem(task = task, onCancel = { onCancel(task.song.id) })
            }
        }

        if (completed.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader("Completed (${completed.size})")
                    if (hasFinished) {
                        TextButton(onClick = onClearFinished) {
                            Text("Clear All", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            items(completed, key = { "done_${it.song.id}" }) { task ->
                DownloadItem(
                    task = task,
                    onCancel = {},
                    onOpenFolder = { task.outputPath?.let { openInFileManager(it) } }
                )
            }
        }

        if (failed.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader("Failed (${failed.size})")
                    if (hasFinished) {
                        TextButton(onClick = onClearFinished) {
                            Text("Clear All", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            items(failed, key = { "fail_${it.song.id}" }) { task ->
                DownloadItem(
                    task = task,
                    onCancel = { onCancel(task.song.id) },
                    onRetry = { onRetry(task.song.id) }
                )
            }
        }

        if (active.isEmpty() && completed.isEmpty() && failed.isEmpty()) {
            item {
                Text(
                    text = "No downloads yet.\nSearch and download your first song!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingsDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var url by remember {
        mutableStateOf(currentUrl.ifBlank { "" })
    }
    var showCookiesGuide by remember { mutableStateOf(false) }

    if (showCookiesGuide) {
        CookiesGuideDialog(
            serverUrl = url,
            onDismiss = { showCookiesGuide = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servidor") },
        text = {
            Column {
                Text(
                    text = "Ingresa la URL de tu servidor personal para buscar y descargar canciones 24/7.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL del servidor") },
                    placeholder = { Text("https://mp3downloader-server-production.up.railway.app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Por defecto usa el servidor en Railway. Puedes configurar uno propio si lo deseas.\n" +
                           "Si el servidor falla, la app usará motores alternativos (Invidious/Piped).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { showCookiesGuide = true }) {
                    Text(
                        text = "Cómo subir cookies de YouTube",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(url) }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun CookiesGuideDialog(
    serverUrl: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cookies de YouTube") },
        text = {
            Column {
                Text(
                    text = "Para que el servidor funcione correctamente, necesita cookies de YouTube (una sesión real de navegador).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "1. En Chrome/Firefox de tu PC, instala la extensión 'Get cookies.txt LOCALLY'",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "2. Ve a youtube.com, inicia sesión y exporta cookies",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "3. Súbelas al servidor con:",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "curl -X POST $serverUrl/api/cookies --data-binary @cookies.txt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "O guárdalas directamente en el servidor en /opt/mp3downloader/cookies/cookies.txt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Entendido") }
        }
    )
}

private fun openInFileManager(path: String) {
    com.mp3downloader.domain.service.openInFileManager(path)
}
