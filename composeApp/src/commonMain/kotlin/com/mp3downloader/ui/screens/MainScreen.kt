package com.mp3downloader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mp3downloader.data.engine.RemoteConfig
import com.mp3downloader.data.storage.saveAppearance
import com.mp3downloader.data.storage.persistWallpaperImage
import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.ui.AppTab
import com.mp3downloader.ui.MainViewModel
import com.mp3downloader.ui.components.DownloadItem
import com.mp3downloader.ui.components.SearchBar
import com.mp3downloader.ui.components.SongListItem
import com.mp3downloader.ui.theme.AppTheme
import com.mp3downloader.ui.theme.ThemeManager
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val previewingSongId by viewModel.previewingSongId.collectAsState()
    val previewLoading by viewModel.previewLoading.collectAsState()
    val previewPaused by viewModel.previewPaused.collectAsState()
    val appearance by ThemeManager.settings.collectAsState()

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

    // Wallpaper overlay
    val wallpaperUri = appearance.wallpaperUri
    val wallpaperOpacity = appearance.wallpaperOpacity

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Text(
                        text = data.visuals.message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Descargas",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Mp3",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Ajustes",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Tabs with custom styling
                TabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                            height = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    divider = {
                        LinearProgressIndicator(
                            progress = { 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.dp),
                            color = Color.Transparent,
                            trackColor = Color.Transparent
                        )
                    }
                ) {
                    AppTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            text = {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                when (selectedTab) {
                    AppTab.SEARCH -> SearchTab(
                        searchQuery = searchQuery,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onSearch = viewModel::search,
                        isSearching = isSearching,
                        searchError = searchError,
                        searchResults = searchResults,
                        isLoadingMore = isLoadingMore,
                        hasMore = hasMore,
                        onLoadMore = viewModel::loadMore,
                        previewingSongId = previewingSongId,
                        previewLoading = previewLoading,
                        previewPaused = previewPaused,
                        onPreviewClick = viewModel::togglePreview,
                        onDownloadClick = viewModel::download,
                        onErrorDismiss = viewModel::clearError,
                        onRefresh = viewModel::search
                    )
                    AppTab.DOWNLOADS -> DownloadsTab(
                        downloads = downloads,
                        onCancel = viewModel::cancelDownload,
                        onRetry = viewModel::retryDownload,
                        onClearFinished = viewModel::clearFinishedDownloads
                    )
                }
            }

            // Wallpaper overlay on top of everything
            if (wallpaperUri != null) {
                WallpaperOverlay(
                    uri = wallpaperUri,
                    opacity = wallpaperOpacity
                )
            }
        }
    }
}

@Composable
private fun WallpaperOverlay(uri: String, opacity: Float) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        try {
            val bmp = if (uri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(uri))
                    ?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } else {
                android.graphics.BitmapFactory.decodeFile(uri)
                    ?: context.contentResolver.openInputStream(Uri.parse(uri))
                        ?.use { android.graphics.BitmapFactory.decodeStream(it) }
            }
            bmp?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = opacity
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTab(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    searchError: String?,
    searchResults: List<com.mp3downloader.domain.model.Song>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    previewingSongId: String?,
    previewLoading: String?,
    previewPaused: Boolean,
    onPreviewClick: (com.mp3downloader.domain.model.Song) -> Unit,
    onDownloadClick: (com.mp3downloader.domain.model.Song) -> Unit,
    onErrorDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = searchQuery,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            isLoading = isSearching
        )

        AnimatedVisibility(
            visible = searchError != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut()
        ) {
            if (searchError != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = searchError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .padding(12.dp)
                            .clickable { onErrorDismiss() },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (searchResults.isEmpty() && !isSearching && searchError == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Brightness6,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isBlank())
                            "Busca tu música favorita"
                        else
                            "Sin resultados",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (searchQuery.isBlank())
                            "Escribe el nombre de una canción, artista o video"
                        else
                            "Intenta con otra búsqueda",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            val listState = rememberLazyListState()
            var isRefreshing by remember { mutableStateOf(false) }
            var pullOffset by remember { mutableFloatStateOf(0f) }
            val refreshThreshold = 80f
            LaunchedEffect(isSearching) {
                if (isRefreshing && !isSearching) isRefreshing = false
            }
            val nestedScrollConnection = remember {
                object : NestedScrollConnection {
                    private fun atTop() =
                        listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0

                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        if (source == NestedScrollSource.Drag && available.y > 0f && atTop()) {
                            pullOffset = (pullOffset + available.y).coerceAtMost(140f)
                            return available
                        }
                        return Offset.Zero
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        if (source == NestedScrollSource.Drag && available.y > 0f && atTop()) {
                            pullOffset = (pullOffset + available.y).coerceAtMost(140f)
                            return available
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (pullOffset > refreshThreshold && !isRefreshing) {
                            isRefreshing = true
                            onRefresh()
                        }
                        pullOffset = 0f
                        return Velocity.Zero
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(searchResults, key = { it.id }) { song ->
                        SongListItem(
                            song = song,
                            isPreviewing = previewingSongId == song.id,
                            isPreviewLoading = previewLoading == song.id,
                            isPaused = previewPaused && previewingSongId == song.id,
                            onPreviewClick = { onPreviewClick(song) },
                            onDownloadClick = { onDownloadClick(song) }
                        )
                    }

                    if (hasMore) {
                        item {
                            LoadMoreFooter(
                                isLoading = isLoadingMore,
                                onLoadMore = onLoadMore
                            )
                        }
                    }
                }

                if (pullOffset > 1f || isRefreshing) {
                    PullToRefreshIndicator(
                        pullOffset = pullOffset,
                        isRefreshing = isRefreshing,
                        threshold = refreshThreshold,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PullToRefreshIndicator(
    pullOffset: Float,
    isRefreshing: Boolean,
    threshold: Float,
    modifier: Modifier = Modifier
) {
    val progress = (pullOffset / threshold).coerceIn(0f, 1f)
    if (isRefreshing) {
        CircularProgressIndicator(
            modifier = modifier.size(28.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary
        )
    } else {
        CircularProgressIndicator(
            progress = progress,
            modifier = modifier.size(28.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun LoadMoreFooter(
    isLoading: Boolean,
    onLoadMore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Button(
                onClick = onLoadMore,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Cargar más")
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
                SectionHeader("Descargas activas (${active.size})")
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
                    SectionHeader("Completadas (${completed.size})")
                    if (hasFinished) {
                        TextButton(onClick = onClearFinished) {
                            Text("Limpiar", color = MaterialTheme.colorScheme.error)
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
                    SectionHeader("Fallidas (${failed.size})")
                    if (hasFinished) {
                        TextButton(onClick = onClearFinished) {
                            Text("Limpiar", color = MaterialTheme.colorScheme.error)
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Brightness6,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Sin descargas",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Busca y descarga tu primera canción",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
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
    var url by remember { mutableStateOf(currentUrl.ifBlank { "" }) }
    val appearance by ThemeManager.settings.collectAsState()
    var selectedTheme by remember { mutableStateOf(appearance.theme) }
    var isDarkMode by remember { mutableStateOf(appearance.isDarkMode) }
    var wallpaperOpacity by remember { mutableFloatStateOf(appearance.wallpaperOpacity) }

    val wallpaperPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val persisted = persistWallpaperImage(it.toString()) ?: it.toString()
            ThemeManager.updateWallpaper(persisted)
            saveAppearance(ThemeManager.settings.value)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ColorLens,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Apariencia", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                // ── Dark/Light mode toggle ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (isDarkMode) "Modo oscuro" else "Modo claro",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = {
                            isDarkMode = it
                            ThemeManager.updateDarkMode(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ── Theme picker ──
                Text(
                    "Tema",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val themes = AppTheme.entries.take(3)
                    themes.forEach { theme ->
                        ThemeChip(
                            theme = theme,
                            isSelected = selectedTheme == theme,
                            onClick = {
                                selectedTheme = theme
                                ThemeManager.updateTheme(theme)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val themes = AppTheme.entries.drop(3)
                    themes.forEach { theme ->
                        ThemeChip(
                            theme = theme,
                            isSelected = selectedTheme == theme,
                            onClick = {
                                selectedTheme = theme
                                ThemeManager.updateTheme(theme)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Wallpaper ──
                Text(
                    "Fondo de pantalla",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pick wallpaper button
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { wallpaperPicker.launch("image/*") },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Elegir",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // Remove wallpaper button
                    if (appearance.wallpaperUri != null) {
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    ThemeManager.updateWallpaper(null)
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Wallpaper,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Quitar",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                if (appearance.wallpaperUri != null) {
                    Spacer(Modifier.height(12.dp))

                    // Opacity slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Opacidad",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(60.dp)
                        )
                        Slider(
                            value = wallpaperOpacity,
                            onValueChange = {
                                wallpaperOpacity = it
                                ThemeManager.updateWallpaperOpacity(it)
                            },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            "${(wallpaperOpacity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(40.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Server URL section ──
                Text(
                    "Servidor",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL del servidor") },
                    placeholder = { Text("https://mp3downloader-server-production.up.railway.app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    "Motor alternativo: si el servidor falla, usa Invidious/Piped",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(Modifier.height(20.dp))

                // ── Developer credit ──
                Text(
                    "Descargas Mp3 v1.0.0",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "App desarrollada por Eliezer David Malavé",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    saveAppearance(ThemeManager.settings.value)
                    onSave(url)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Guardar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun ThemeChip(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier
            .height(40.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder().copy(
                width = 0.dp
            )
        } else {
            null
        }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = theme.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}

private fun openInFileManager(path: String) {
    com.mp3downloader.domain.service.openInFileManager(path)
}
