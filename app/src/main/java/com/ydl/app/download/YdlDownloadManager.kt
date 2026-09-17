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
    data class ResolvingUrls(val filename: String) : DownloadState()
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
    ) = withContext(Dispatchers.IO) {
        try {
            if (format.merged) {
                _state.value = DownloadState.ResolvingUrls(format.filename)

                val resolved = when (val r = resolveUrls()) {
                    is YdlResult.Success -> r.data
                    is YdlResult.Error -> {
                        _state.value = DownloadState.Failed("Could not resolve stream URLs: ${r.message}")
                        return@withContext
                    }
                }

                mergeMp4(format, resolved)
            } else {
                val url = format.directUrl
                if (url.isNullOrBlank()) {
                    _state.value = DownloadState.Failed("No direct URL for this format")
                    return@withContext
                }
                enqueueDirectDownload(url, format.filename)
            }
        } catch (e: Exception) {
            _state.value = DownloadState.Failed(e.message ?: "Unknown error")
        }
    }

    private fun enqueueDirectDownload(url: String, filename: String) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(filename)
            setDescription("Downloading via YDL")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "YDL/$filename")
            addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        _state.value = DownloadState.Enqueued(filename, downloadId)
    }

    private suspend fun mergeMp4(format: VideoFormat, resolved: ResolvedUrls) {
        val outputDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "YDL"
        ).also { it.mkdirs() }
        val outputFile = File(outputDir, format.filename)
        if (outputFile.exists()) outputFile.delete()

        _state.value = DownloadState.Merging(format.filename, 0f)

        val ua = "Mozilla/5.0 (Linux; Android 10)"
        val cmd = listOf(
            "-y",
            "-headers", "User-Agent: $ua\r\n",
            "-i", resolved.videoUrl,
            "-headers", "User-Agent: $ua\r\n",
            "-i", resolved.audioUrl,
            "-c:v", "copy",
            "-c:a", "copy",
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-movflags", "+faststart",
            outputFile.absolutePath
        ).joinToString(" ") { arg ->
            if (arg.contains(" ") && !arg.startsWith("-")) "\"$arg\"" else arg
        }

        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeAsync(
                cmd,
                { s ->
                    if (ReturnCode.isSuccess(s.returnCode)) {
                        _state.value = DownloadState.Done(format.filename)
                    } else {
                        val log = s.allLogsAsString?.takeLast(400) ?: "no log"
                        _state.value = DownloadState.Failed("FFmpeg error: $log")
                    }
                    if (cont.isActive) cont.resume(Unit)
                },
                null,
                { stats ->
                    if (stats != null && stats.time > 0) {
                        val prog = (stats.time / 1000f).coerceIn(0f, 99f) / 100f
                        _state.value = DownloadState.Merging(format.filename, prog)
                    }
                }
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
    }

    fun reset() { _state.value = DownloadState.Idle }
}
