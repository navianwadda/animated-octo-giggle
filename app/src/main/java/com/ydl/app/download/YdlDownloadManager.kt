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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

sealed class DownloadState {
    object Idle : DownloadState()
    data class ResolvingUrls(val filename: String) : DownloadState()
    data class Downloading(val filename: String, val progress: Float, val stage: String) : DownloadState()
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
                        _state.value = DownloadState.Failed("URL resolve failed: ${r.message}")
                        return@withContext
                    }
                }

                downloadAndMerge(format, resolved)
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
            addRequestHeader("User-Agent", UA)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        _state.value = DownloadState.Enqueued(filename, dm.enqueue(request))
    }

    private suspend fun downloadAndMerge(format: VideoFormat, resolved: ResolvedUrls) {
        val cacheDir = context.cacheDir
        val videoTmp = File(cacheDir, "ydl_video_tmp.mp4")
        val audioTmp = File(cacheDir, "ydl_audio_tmp.m4a")

        try {
            _state.value = DownloadState.Downloading(format.filename, 0f, "video")
            downloadToFile(resolved.videoUrl, videoTmp) { prog ->
                _state.value = DownloadState.Downloading(format.filename, prog * 0.5f, "Downloading video…")
            }

            _state.value = DownloadState.Downloading(format.filename, 0.5f, "audio")
            downloadToFile(resolved.audioUrl, audioTmp) { prog ->
                _state.value = DownloadState.Downloading(format.filename, 0.5f + prog * 0.4f, "Downloading audio…")
            }

            _state.value = DownloadState.Merging(format.filename, 0f)

            val outputDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "YDL"
            ).also { it.mkdirs() }
            val outputFile = File(outputDir, format.filename)
            if (outputFile.exists()) outputFile.delete()

            val cmd = "-y -i \"${videoTmp.absolutePath}\" -i \"${audioTmp.absolutePath}\" " +
                      "-c:v copy -c:a copy -map 0:v:0 -map 1:a:0 " +
                      "-movflags +faststart \"${outputFile.absolutePath}\""

            suspendCancellableCoroutine { cont ->
                val session = FFmpegKit.executeAsync(
                    cmd,
                    { s ->
                        if (ReturnCode.isSuccess(s.returnCode)) {
                            _state.value = DownloadState.Done(format.filename)
                        } else {
                            val log = s.allLogsAsString?.takeLast(500) ?: "no log"
                            _state.value = DownloadState.Failed("Merge failed: $log")
                        }
                        if (cont.isActive) cont.resume(Unit)
                    },
                    null,
                    { stats ->
                        if (stats != null && stats.time > 0) {
                            val prog = (stats.time.toFloat() / 1000f).coerceIn(0f, 99f) / 100f
                            _state.value = DownloadState.Merging(format.filename, prog)
                        }
                    }
                )
                cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
            }
        } finally {
            videoTmp.delete()
            audioTmp.delete()
        }
    }

    private fun downloadToFile(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            connectTimeout = 20_000
            readTimeout = 30_000
            connect()
        }

        val total = conn.contentLengthLong.takeIf { it > 0 }
        var downloaded = 0L

        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total != null) onProgress(downloaded.toFloat() / total.toFloat())
                }
            }
        }
        conn.disconnect()
    }

    fun reset() { _state.value = DownloadState.Idle }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
