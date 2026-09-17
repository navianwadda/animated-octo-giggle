package com.ydl.app.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.ydl.app.models.ResolvedUrls
import com.ydl.app.models.VideoFormat
import com.ydl.app.models.YdlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

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

    private fun enqueueDirectDownload(url: String, filename: String, ext: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setDescription("Downloading via YDL")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "YDL/$filename")
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

    private suspend fun downloadMerged(
        format: VideoFormat,
        resolveUrls: suspend () -> YdlResult<ResolvedUrls>,
    ) = withContext(Dispatchers.IO) {
        val resolved = when (val r = resolveUrls()) {
            is YdlResult.Success -> r.data
            is YdlResult.Error   -> {
                _state.value = DownloadState.Failed(r.message)
                return@withContext
            }
        }

        val outputDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "YDL"
        ).also { it.mkdirs() }
        val outputFile = File(outputDir, format.filename)

        _state.value = DownloadState.Merging(format.filename, 0f)

        var lastProgress = 0f

        val cmd = "-i \"${resolved.videoUrl}\" -i \"${resolved.audioUrl}\" " +
                  "-c copy -map 0:v:0 -map 1:a:0 " +
                  "-movflags +faststart " +
                  "\"${outputFile.absolutePath}\""

        suspendCancellableCoroutine { cont ->
            FFmpegKit.executeAsync(
                cmd,
                { completedSession ->
                    if (ReturnCode.isSuccess(completedSession.returnCode)) {
                        _state.value = DownloadState.Done(format.filename)
                    } else {
                        _state.value = DownloadState.Failed(
                            "ffmpeg failed (rc=${completedSession.returnCode})"
                        )
                    }
                    if (cont.isActive) cont.resume(Unit)
                },
                { },
                { _ ->
                    lastProgress = (lastProgress + 0.5f).coerceAtMost(95f)
                    _state.value = DownloadState.Merging(format.filename, lastProgress / 100f)
                }
            )
        }
    }

    fun reset() {
        _state.value = DownloadState.Idle
    }
}
