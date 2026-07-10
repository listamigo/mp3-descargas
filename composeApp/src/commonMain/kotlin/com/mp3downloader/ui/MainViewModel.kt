package com.mp3downloader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mp3downloader.data.repository.HistoryRepository
import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.DownloadTask
import com.mp3downloader.domain.model.Song
import com.mp3downloader.domain.repository.SongRepository
import com.mp3downloader.domain.service.AudioPreviewer
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

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    private val _previewLoading = MutableStateFlow<String?>(null)
    val previewLoading: StateFlow<String?> = _previewLoading.asStateFlow()

    private val _previewingSongId = MutableStateFlow<String?>(null)
    val previewingSongId: StateFlow<String?> = _previewingSongId.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<SnackbarEvent>()
    val snackbarEvent: SharedFlow<SnackbarEvent> = _snackbarEvent.asSharedFlow()

    private val audioUrlCache = mutableMapOf<String, String>()

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

        // If this song is already being previewed, stop it
        if (_previewingSongId.value == song.id) {
            audioPreviewer.stop()
            _previewingSongId.value = null
            return
        }

        // Stop any current playback
        audioPreviewer.stop()
        _previewingSongId.value = null

        // If URL is cached, play from cache
        val cachedUrl = audioUrlCache[song.id]
        if (cachedUrl != null) {
            audioPreviewer.play(cachedUrl, onError = { errorMsg ->
                viewModelScope.launch {
                    _previewingSongId.value = null
                    showSnackbar("Error de reproducción: $errorMsg")
                }
            })
            _previewingSongId.value = song.id
            return
        }

        // Fetch audio URL and play
        viewModelScope.launch {
            _previewLoading.value = song.id

            repository.getAudioUrl(song)
                .onSuccess { url ->
                    audioUrlCache[song.id] = url
                    audioPreviewer.play(
                        url = url,
                        onError = { errorMsg ->
                            viewModelScope.launch {
                                _previewingSongId.value = null
                                _previewLoading.value = null
                                showSnackbar("Error de reproducción: $errorMsg")
                            }
                        },
                        onPlaying = {
                            _previewLoading.value = null
                        }
                    )
                    _previewingSongId.value = song.id
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
    }

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun search() {
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) return

        viewModelScope.launch {
            _isSearching.value = true
            _searchError.value = null

            repository.search(query)
                .onSuccess { songs ->
                    _searchResults.value = songs
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

    fun download(song: Song) {
        val alreadyActive = _downloads.value.any {
            it.song.id == song.id && (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING)
        }
        if (alreadyActive) return

        viewModelScope.launch {
            val task = DownloadTask(song = song, status = DownloadStatus.QUEUED)
            _downloads.value = _downloads.value + task

            _selectedTab.value = AppTab.DOWNLOADS

            val outputDir = getOutputDirectory()

            repository.download(song, outputDir).collect { result ->
                updateTask(
                    songId = result.songId,
                    status = result.status,
                    progress = result.progress,
                    outputPath = result.outputPath,
                    error = result.error
                )

                if (result.status == DownloadStatus.COMPLETED && result.outputPath != null) {
                    val ext = result.outputPath.substringAfterLast('.', "m4a")
                    val publicPath = com.mp3downloader.domain.service.saveToPublicDownloads(
                        result.outputPath,
                        "${song.title}.$ext"
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
                } else if (result.status == DownloadStatus.FAILED) {
                    val errMsg = result.error ?: "Error desconocido"
                    showSnackbar(
                        message = "Descarga fallida: $errMsg",
                        actionLabel = "Copiar",
                        action = { com.mp3downloader.domain.service.copyTextToClipboard(errMsg) }
                    )
                }
            }
        }
    }

    fun retryDownload(songId: String) {
        val failedTask = _downloads.value.find {
            it.song.id == songId && it.status == DownloadStatus.FAILED
        }
        if (failedTask != null) {
            _downloads.value = _downloads.value.filter { it.song.id != songId }
            download(failedTask.song)
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
        error: String? = null
    ) {
        _downloads.value = _downloads.value.map { task ->
            if (task.song.id == songId) {
                task.copy(
                    status = status ?: task.status,
                    progress = progress ?: task.progress,
                    outputPath = outputPath ?: task.outputPath,
                    error = error ?: task.error
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
