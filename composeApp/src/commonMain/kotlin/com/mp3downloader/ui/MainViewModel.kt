package com.mp3downloader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mp3downloader.data.engine.SEARCH_PAGE_SIZE
import com.mp3downloader.data.repository.HistoryRepository
import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.DownloadTask
import com.mp3downloader.domain.model.Song
import com.mp3downloader.domain.repository.SongRepository
import com.mp3downloader.domain.service.AudioPreviewer
import com.mp3downloader.domain.service.LruCache
import com.mp3downloader.domain.service.sanitizeFileName
import com.mp3downloader.domain.service.sanitizeSearchQuery
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.FlowPreview

enum class AppTab(val label: String) {
    SEARCH("Buscar"),
    DOWNLOADS("Descargas")
}

data class SnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

data class DownloadCompleteEvent(
    val title: String
)

data class DownloadFailedEvent(
    val title: String,
    val error: String
)

@OptIn(FlowPreview::class)
class MainViewModel(
    private val repository: SongRepository,
    private val historyRepository: HistoryRepository,
    private val audioPreviewer: AudioPreviewer
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(AppTab.SEARCH)
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var currentQuery: String = ""
    private var currentOffset: Int = 0

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    private val _searchVersion = MutableStateFlow(0)
    val searchVersion: StateFlow<Int> = _searchVersion.asStateFlow()

    /** Número de descargas actualmente en progreso (para Foreground Service). */
    private val _foregroundDownloadsCount = MutableStateFlow(0)
    val foregroundDownloadsCount: StateFlow<Int> = _foregroundDownloadsCount.asStateFlow()

    private val _previewLoading = MutableStateFlow<String?>(null)
    val previewLoading: StateFlow<String?> = _previewLoading.asStateFlow()

    private val _previewingSongId = MutableStateFlow<String?>(null)
    val previewingSongId: StateFlow<String?> = _previewingSongId.asStateFlow()

    private val _previewPaused = MutableStateFlow(false)
    val previewPaused: StateFlow<Boolean> = _previewPaused.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<SnackbarEvent>()
    val snackbarEvent: SharedFlow<SnackbarEvent> = _snackbarEvent.asSharedFlow()

    private val _downloadCompleteEvent = MutableSharedFlow<DownloadCompleteEvent>()
    val downloadCompleteEvent: SharedFlow<DownloadCompleteEvent> = _downloadCompleteEvent.asSharedFlow()

    private val _downloadFailedEvent = MutableSharedFlow<DownloadFailedEvent>()
    val downloadFailedEvent: SharedFlow<DownloadFailedEvent> = _downloadFailedEvent.asSharedFlow()

    private val audioUrlCache = LruCache<String, String>(200)

    /** At most 3 concurrent downloads so we never flood the backend. */
    private val downloadSemaphore = Semaphore(3)

    /** Tracks the active search/load-more request so a new one can cancel it. */
    private var requestJob: Job? = null

    init {
        viewModelScope.launch {
            val history = historyRepository.loadHistory()
            _downloads.value = history
        }

        viewModelScope.launch {
            _downloads
                .debounce(500)
                .map { tasks -> tasks.filter { it.status.isPersistable() } }
                .distinctUntilChanged()
                .collect { tasks ->
                    historyRepository.saveHistory(tasks)
                }
        }
    }

    fun togglePreview(song: Song) {
        // If already loading this song's preview, ignore the click
        if (_previewLoading.value == song.id) return

        // Same song: toggle between pause and resume.
        if (_previewingSongId.value == song.id) {
            if (_previewPaused.value) {
                audioPreviewer.resume()
                _previewPaused.value = false
            } else {
                audioPreviewer.pause()
                _previewPaused.value = true
            }
            return
        }

        // Different song (or nothing playing): stop whatever is playing and
        // start the new one. This guarantees the previous preview never keeps
        // sounding in parallel.
        audioPreviewer.stop()
        _previewingSongId.value = null
        _previewPaused.value = false

        viewModelScope.launch {
            // If URL is cached, play from cache
            val cachedUrl = audioUrlCache.get(song.id)
            if (cachedUrl != null) {
                audioPreviewer.play(cachedUrl, onError = { errorMsg ->
                    _previewingSongId.value = null
                    showSnackbar("Error de reproducción: $errorMsg")
                })
                _previewingSongId.value = song.id
                _previewPaused.value = false
                return@launch
            }

            _previewLoading.value = song.id

            repository.getAudioUrl(song)
                .onSuccess { url ->
                    audioUrlCache.put(song.id, url)
                    audioPreviewer.play(
                        url = url,
                        onError = { errorMsg ->
                            _previewingSongId.value = null
                            _previewLoading.value = null
                            _previewPaused.value = false
                            showSnackbar("Error de reproducción: $errorMsg")
                        },
                        onPlaying = {
                            _previewLoading.value = null
                        }
                    )
                    _previewingSongId.value = song.id
                    _previewPaused.value = false
                }
                .onFailure { e ->
                    _previewLoading.value = null
                    showSnackbar("Vista previa fallida: ${e.message}")
                }
        }
    }

    fun stopPreview() {
        audioPreviewer.stop()
        _previewingSongId.value = null
        _previewPaused.value = false
    }

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun search() {
        val query = sanitizeSearchQuery(_searchQuery.value)
        if (query.isEmpty()) return

        currentQuery = query
        currentOffset = 0
        _searchVersion.value++

        requestJob?.cancel()

        requestJob = viewModelScope.launch {
            _isSearching.value = true
            _searchError.value = null
            _hasMore.value = false

            repository.search(query)
                .onSuccess { songs ->
                    _searchResults.value = songs
                    _hasMore.value = songs.size >= SEARCH_PAGE_SIZE
                    if (songs.isEmpty()) {
                        showSnackbar("Sin resultados para \"$query\"")
                    }
                }
                .onFailure { e ->
                    val msg = e.message ?: "Búsqueda fallida"
                    _searchError.value = msg
                    showSnackbar(
                        message = msg,
                        actionLabel = "Copiar",
                        action = { com.mp3downloader.domain.service.copyTextToClipboard(msg) }
                    )
                }

            _isSearching.value = false
        }
    }

    fun loadMore() {
        val query = currentQuery.trim()
        if (query.isEmpty() || _isLoadingMore.value || !_hasMore.value) return

        // Cancel any in-flight search so its results don't clobber this page.
        requestJob?.cancel()

        requestJob = viewModelScope.launch {
            _isLoadingMore.value = true
            val offset = currentOffset + SEARCH_PAGE_SIZE

            repository.search(query, offset)
                .onSuccess { songs ->
                    if (songs.isNotEmpty()) {
                        val existingIds = _searchResults.value.map { it.id }.toSet()
                        val newSongs = songs.filter { it.id !in existingIds }
                        _searchResults.value = _searchResults.value + newSongs
                        currentOffset = offset
                        _hasMore.value = newSongs.isNotEmpty() && songs.size >= SEARCH_PAGE_SIZE
                    } else {
                        _hasMore.value = false
                    }
                }
                .onFailure { e ->
                    showSnackbar("Error al cargar más: ${e.message}")
                }

            _isLoadingMore.value = false
        }
    }

    fun download(song: Song) {
        val alreadyActive = _downloads.value.any {
            it.song.id == song.id && (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING)
        }
        if (alreadyActive) return

        val task = DownloadTask(song = song, status = DownloadStatus.QUEUED)
        _downloads.value = _downloads.value.filter { it.song.id != song.id } + task

        _selectedTab.value = AppTab.DOWNLOADS

        // Notificar al Foreground Service que empieza una descarga
        _foregroundDownloadsCount.value = _foregroundDownloadsCount.value + 1

        viewModelScope.launch {
            try {
                downloadSemaphore.withPermit {
                    val outputDir = getOutputDirectory()

                    repository.download(song, outputDir).collect { result ->
                        // Capturar tamaño del archivo original antes de que outputPath
                        // se reemplace por un content:// URI (MediaStore en API 29+).
                        val savedFileSize = if (result.status == DownloadStatus.COMPLETED && result.outputPath != null) {
                            java.io.File(result.outputPath).let { if (it.exists()) it.length() else 0L }
                        } else 0L

                        updateTask(
                            songId = result.songId,
                            status = result.status,
                            progress = result.progress,
                            outputPath = result.outputPath,
                            error = result.error,
                            fileSizeBytes = savedFileSize
                        )

                        if (result.status == DownloadStatus.COMPLETED && result.outputPath != null) {
                            val ext = result.outputPath.substringAfterLast('.', "m4a")
                            val publicPath = com.mp3downloader.domain.service.saveToPublicDownloads(
                                result.outputPath,
                                "${sanitizeFileName(song.title)}.$ext"
                            )
                            updateTask(songId = result.songId, outputPath = publicPath ?: result.outputPath)
                            val finalPath = publicPath ?: result.outputPath
                            showSnackbar(
                                message = "Descargado: ${song.title}",
                                actionLabel = "Abrir",
                                action = {
                                    com.mp3downloader.domain.service.openInFileManager(finalPath)
                                    _selectedTab.value = AppTab.DOWNLOADS
                                }
                            )
                            _downloadCompleteEvent.emit(DownloadCompleteEvent(song.title))
                        } else if (result.status == DownloadStatus.FAILED) {
                            val errMsg = result.error ?: "Error desconocido"
                            showSnackbar(
                                message = "Descarga fallida: $errMsg",
                                actionLabel = "Copiar",
                                action = { com.mp3downloader.domain.service.copyTextToClipboard(errMsg) }
                            )
                            _downloadFailedEvent.emit(DownloadFailedEvent(song.title, errMsg))
                        }
                    }
                }
            } finally {
                // Notificar al Foreground Service que terminó una descarga
                _foregroundDownloadsCount.value = (_foregroundDownloadsCount.value - 1).coerceAtLeast(0)
            }
        }
    }

    fun retryDownload(songId: String) {
        val failedTask = _downloads.value.find {
            it.song.id == songId && it.status == DownloadStatus.FAILED
        }
        if (failedTask != null) {
            _downloads.value = _downloads.value.filter { it.song.id != songId }
            // Pequeño delay para dar tiempo a que la conexión se recupere
            viewModelScope.launch {
                kotlinx.coroutines.delay(1000)
                download(failedTask.song)
            }
        }
    }

    fun cancelDownload(songId: String) {
        viewModelScope.launch {
            repository.cancelDownload(songId)
            updateTask(songId, status = DownloadStatus.FAILED, error = "Cancelado")
            showSnackbar("Descarga cancelada")
        }
    }

    fun clearError() {
        _searchError.value = null
    }

    fun getCompletedDownloads(): List<DownloadTask> {
        return _downloads.value.filter { it.status == DownloadStatus.COMPLETED }
    }

    fun getActiveDownloads(): List<DownloadTask> {
        return _downloads.value.filter {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
        }
    }

    fun getFailedDownloads(): List<DownloadTask> {
        return _downloads.value.filter { it.status == DownloadStatus.FAILED }
    }

    fun clearFinishedDownloads() {
        _downloads.value = _downloads.value.filter {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
        }
    }

    private fun updateTask(
        songId: String,
        status: DownloadStatus? = null,
        progress: Float? = null,
        outputPath: String? = null,
        error: String? = null,
        fileSizeBytes: Long? = null
    ) {
        _downloads.value = _downloads.value.map { task ->
            if (task.song.id == songId) {
                task.copy(
                    status = status ?: task.status,
                    progress = progress ?: task.progress,
                    outputPath = outputPath ?: task.outputPath,
                    error = error ?: task.error,
                    fileSizeBytes = fileSizeBytes ?: task.fileSizeBytes
                )
            } else task
        }
    }

    private fun showSnackbar(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val short = if (message.length > 120) message.take(120) + "..." else message
        viewModelScope.launch {
            _snackbarEvent.emit(SnackbarEvent(short, actionLabel, action))
        }
    }

    private fun getOutputDirectory(): String {
        return com.mp3downloader.data.storage.provideDownloadDirectory()
    }
}

private fun DownloadStatus.isPersistable(): Boolean {
    return this == DownloadStatus.COMPLETED || this == DownloadStatus.FAILED
}
