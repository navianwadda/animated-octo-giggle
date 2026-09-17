package com.ydl.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ydl.app.bridge.YdlBridge
import com.ydl.app.download.DownloadState
import com.ydl.app.download.YdlDownloadManager
import com.ydl.app.models.SearchResult
import com.ydl.app.models.VideoFormat
import com.ydl.app.models.VideoInfo
import com.ydl.app.models.YdlResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class SearchResults(val results: List<SearchResult>) : UiState()
    data class VideoReady(val info: VideoInfo, val url: String) : UiState()
    data class Error(val message: String) : UiState()
}

sealed class BridgeState {
    object Ready : BridgeState()
    object Initializing : BridgeState()
    data class Error(val message: String) : BridgeState()
}

class YdlViewModel(app: Application) : AndroidViewModel(app) {

    private val bridge = YdlBridge(app)
    private val downloadManager = YdlDownloadManager(app)

    private val _ui = MutableStateFlow<UiState>(UiState.Idle)
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _bridgeState = MutableStateFlow<BridgeState>(BridgeState.Initializing)
    val bridgeState: StateFlow<BridgeState> = _bridgeState.asStateFlow()

    val downloadState: StateFlow<DownloadState> = downloadManager.state

    private var currentUrl: String = ""

    init {
        initBridge()
    }

    private fun initBridge() {
        viewModelScope.launch {
            _bridgeState.value = when (bridge.ensureReady()) {
                is YdlResult.Success -> BridgeState.Ready
                is YdlResult.Error   -> BridgeState.Error("Failed to initialize")
            }
        }
    }

    fun retryInit() {
        _bridgeState.value = BridgeState.Initializing
        initBridge()
    }

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _ui.value = UiState.Loading
            _ui.value = when (val r = bridge.search(query)) {
                is YdlResult.Success -> UiState.SearchResults(r.data)
                is YdlResult.Error   -> UiState.Error(r.message)
            }
        }
    }

    fun loadUrl(url: String) {
        viewModelScope.launch {
            _ui.value = UiState.Loading
            currentUrl = url
            _ui.value = when (val r = bridge.extractInfo(url)) {
                is YdlResult.Success -> UiState.VideoReady(r.data, url)
                is YdlResult.Error   -> UiState.Error(r.message)
            }
        }
    }

    fun download(format: VideoFormat) {
        viewModelScope.launch {
            downloadManager.download(format) {
                bridge.resolveUrls(currentUrl, format.formatId)
            }
        }
    }

    fun resetDownload() = downloadManager.reset()

    fun clearError() {
        if (_ui.value is UiState.Error) _ui.value = UiState.Idle
    }

    fun getYdlVersion(): String = runCatching { bridge.getVersion() }.getOrDefault("unknown")
}
