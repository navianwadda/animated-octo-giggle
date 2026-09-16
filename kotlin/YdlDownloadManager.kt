// download/YdlDownloadManager.kt
// Shared download manager used by both V1 and V2.
// Handles two paths:
//   1. Direct URL (video-only / audio-only) → Android DownloadManager
//   2. Merged (video+audio) → ffmpeg-kit muxes on-device
//
// This class is UI-framework agnostic — it exposes a StateFlow of DownloadState
// that any ViewModel or Composable can collect.

package com.yourapp.ydl.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yourapp.ydl.models.ResolvedUrls
import com.yourapp.ydl.models.VideoFormat
import com.yourapp.ydl.models.YdlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed class DownloadState {
    object Idle : DownloadState()
    data class Preparing(val filename: String) : DownloadState()
    data class Merging(val filename: String, val progress: Float) : DownloadState()
    data class Enqueued(val filename: String, val downloadId: Long) : DownloadState()
    data class Done(val filename: String) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

class YdlDownloadManager(private val context: Context) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    /**
     * Start a download for the given format.
     *
     * For merged formats, [resolveUrls] is called by the bridge to get the
     * two separate stream URLs, then ffmpeg-kit merges them on-device.
     *
     * For direct formats, Android DownloadManager handles it natively.
     *
     * @param format     The selected VideoFormat
     * @param resolveUrls Suspend lambda that calls ydl_bridge.resolve_merged_urls()
     *                    Only invoked for merged formats.
     */
    suspend fun download(
        format: VideoFormat,
        resolveUrls: suspend () -> YdlResult<ResolvedUrls>,
    ) {
        _state.value = DownloadState.Preparing(format.filename)

        if (format.merged) {
            downloadMerged(format, resolveUrls)
        } else {
            val url = format.directUrl
            if (url.isNullOrBlank()) {
                _state.value = DownloadState.Failed("No direct URL available for this format")
                return
            }
            enqueueDirectDownload(url, format.filename, format.ext)
        }
    }

    // ── Direct download via Android DownloadManager ───────────────────────────

    private fun enqueueDirectDownload(url: String, filename: String, ext: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setDescription("Downloading via YDL")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "YDL/$filename")
                // Pass the URL's user-agent so YouTube CDN doesn't reject us
                addRequestHeader("User-Agent", "Mozilla/5.0")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)

            _state.value = DownloadState.Enqueued(filename, downloadId)
        } catch (e: Exception) {
            _state.value = DownloadState.Failed(e.message ?: "Failed to enqueue download")
        }
    }

    // ── Merged download via ffmpeg-kit ────────────────────────────────────────

    private suspend fun downloadMerged(
        format: VideoFormat,
        resolveUrls: suspend () -> YdlResult<ResolvedUrls>,
    ) = withContext(Dispatchers.IO) {
        // 1. Resolve the two stream URLs
        val resolved = when (val r = resolveUrls()) {
            is YdlResult.Success -> r.data
            is YdlResult.Error   -> {
                _state.value = DownloadState.Failed(r.message)
                return@withContext
            }
        }

        // 2. Output file in Downloads/YDL/
        val outputDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "YDL"
        ).also { it.mkdirs() }
        val outputFile = File(outputDir, format.filename)

        _state.value = DownloadState.Merging(format.filename, 0f)

        // 3. Run ffmpeg-kit: download + mux video and audio streams in one pass
        // -c copy avoids re-encoding — fast and lossless quality
        val cmd = "-i \"${resolved.videoUrl}\" -i \"${resolved.audioUrl}\" " +
                  "-c copy -map 0:v:0 -map 1:a:0 " +
                  "-movflags +faststart " +
                  "\"${outputFile.absolutePath}\""

        var lastProgress = 0f

        val session = FFmpegKit.executeAsync(
            cmd,
            { completedSession ->
                if (ReturnCode.isSuccess(completedSession.returnCode)) {
                    _state.value = DownloadState.Done(format.filename)
                } else {
                    val logs = completedSession.allLogsAsString
                    _state.value = DownloadState.Failed(
                        "ffmpeg failed (rc=${completedSession.returnCode}): ${logs.takeLast(200)}"
                    )
                }
            },
            { /* log callback — ignored, use statistics for progress */ },
            { stats ->
                // ffmpeg-kit statistics: use time as a proxy for progress
                // Real duration-based progress requires probing the source first;
                // for simplicity we show indeterminate progress here.
                val newProgress = (lastProgress + 0.5f).coerceAtMost(95f)
                lastProgress = newProgress
                _state.value = DownloadState.Merging(format.filename, newProgress / 100f)
            }
        )

        // Block this coroutine until ffmpeg finishes
        // (FFmpegKit.executeAsync is callback-based; we wait on the session)
        session.waitUntilCompleted()
    }

    fun reset() {
        _state.value = DownloadState.Idle
    }
}
