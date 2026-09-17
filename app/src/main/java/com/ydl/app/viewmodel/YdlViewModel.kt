package com.ydl.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ydl.app.bridge.YdlBridge
import com.ydl.app.download.DownloadState
import com.ydl.app.download.YdlDownloadManager
import com.ydl.app.models.ResolvedUrls
import com.ydl.app.models.SearchResult
import com.ydl.app.models.VideoFormat
import com.ydl.app.models.VideoInfo
import com.ydl.app.models.YdlResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class Screen {
    object Home : Screen()
    data class Results(val results: List<SearchResult>) : Screen()
    data class Detail(val info: VideoInfo, val url: String) : Screen()
    data class Downloads(val format: VideoFormat, val info: VideoInfo) : Screen()
}

sealed class BridgeState {
    object Ready : BridgeState()
    object Initializing : BridgeState()
    data class Error(val message: String) : BridgeState()
}

sealed class LoadState {
    object Idle : LoadState()
    object Loading : LoadState()
    data class Error(val message: String) : LoadState()
}

class YdlViewModel(app: Application) : AndroidViewModel(app) {

    private val bridge = YdlBridge(app)
    private val downloadManager = YdlDownloadManager(app)

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _bridgeState = MutableStateFlow<BridgeState>(BridgeState.Initializing)
    val bridgeState: StateFlow<BridgeState> = _bridgeState.asStateFlow()

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    val downloadState: StateFlow<DownloadState> = downloadManager.state

    private var currentUrl: String = ""

    init { initBridge() }

    private fun initBridge() {
        viewModelScope.launch {
            _bridgeState.value = when (val r = bridge.ensureReady()) {
                is YdlResult.Success -> BridgeState.Ready
                is YdlResult.Error   -> BridgeState.Error(r.message)
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
            _loadState.value = LoadState.Loading
            when (val r = bridge.search(query)) {
                is YdlResult.Success -> {
                    _loadState.value = LoadState.Idle
                    _screen.value = Screen.Results(r.data)
                }
                is YdlResult.Error -> _loadState.value = LoadState.Error(r.message)
            }
        }
    }

    fun loadUrl(url: String) {
        viewModelScope.launch {
            _loadState.value = LoadState.Loading
            currentUrl = url
            when (val r = bridge.extractInfo(url)) {
                is YdlResult.Success -> {
                    _loadState.value = LoadState.Idle
                    _screen.value = Screen.Detail(r.data, url)
                }
                is YdlResult.Error -> _loadState.value = LoadState.Error(r.message)
            }
        }
    }

    fun openDownloadSheet(format: VideoFormat, info: VideoInfo) {
        _screen.value = Screen.Downloads(format, info)
    }

    fun download(format: VideoFormat) {
        viewModelScope.launch {
            downloadManager.download(format) {
                bridge.resolveUrls(currentUrl, format.formatId)
            }
        }
    }

    fun resetDownload() = downloadManager.reset()

    fun navigateBack() {
        _screen.value = when (val s = _screen.value) {
            is Screen.Downloads -> Screen.Detail(s.info, currentUrl)
            is Screen.Detail    -> Screen.Results(emptyList()).let { Screen.Home }
            is Screen.Results   -> Screen.Home
            else                -> Screen.Home
        }
    }

    fun clearError() {
        _loadState.value = LoadState.Idle
    }

    fun getYdlVersion(): String = runCatching { bridge.getVersion() }.getOrDefault("unknown")
}
