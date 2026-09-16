// viewmodel/YdlViewModel.kt
// Works identically with V1 and V2 YdlBridge — the public API is the same.
// Swap the import and the constructor injection to switch versions.

package com.yourapp.ydl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.ydl.download.DownloadState
import com.yourapp.ydl.download.YdlDownloadManager
import com.yourapp.ydl.models.SearchResult
import com.yourapp.ydl.models.VideoFormat
import com.yourapp.ydl.models.VideoInfo
import com.yourapp.ydl.models.YdlResult

// ── Switch between versions here ─────────────────────────────────────────────
// V1: import com.yourapp.ydl.v1.YdlBridge
// V2: import com.yourapp.ydl.v2.YdlBridge
import com.yourapp.ydl.v2.YdlBridge
// ─────────────────────────────────────────────────────────────────────────────

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

// V2-only bridge state
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

    // Current URL being viewed (needed for resolveUrls on merged formats)
    private var currentUrl: String = ""

    init {
        initBridge()
    }

    // ── Bridge init (V2 only — V1 is always ready) ────────────────────────────

    private fun initBridge() {
        viewModelScope.launch {
            // V1: bridge is always ready; ensureReady() is a no-op.
            // V2: downloads/updates yt-dlp.pyz before first use.
            val result = (bridge as? com.yourapp.ydl.v2.YdlBridge)?.ensureReady()
                ?: YdlResult.Success(Unit)

            _bridgeState.value = when (result) {
                is YdlResult.Success -> BridgeState.Ready
                is YdlResult.Error   -> BridgeState.Error(result.message)
            }
        }
    }

    fun retryInit() {
        _bridgeState.value = BridgeState.Initializing
        initBridge()
    }

    // ── Search ────────────────────────────────────────────────────────────────

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

    // ── Info extraction ───────────────────────────────────────────────────────

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

    // ── Download ──────────────────────────────────────────────────────────────

    fun download(format: VideoFormat) {
        viewModelScope.launch {
            downloadManager.download(format) {
                // resolveUrls lambda — only called for merged formats
                bridge.resolveUrls(currentUrl, format.formatId)
            }
        }
    }

    fun resetDownload() = downloadManager.reset()

    fun clearError() {
        if (_ui.value is UiState.Error) _ui.value = UiState.Idle
    }

    // ── Version info ──────────────────────────────────────────────────────────

    fun getYdlVersion(): String = runCatching { bridge.getVersion() }.getOrDefault("unknown")
}
