package com.ydl.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ydl.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ─── Public API ──────────────────────────────────────────────────────────────

data class DownloadJob(
    val id: Long,
    val filename: String,
    val videoUrl: String,
    val audioUrl: String,
    val state: JobState = JobState.Queued,
)

sealed class JobState {
    object Queued      : JobState()
    object Resolving   : JobState()
    data class Downloading(
        val stageName: String,
        val byteDone: Long,
        val byteTotal: Long,
    ) : JobState()
    data class Merging(val progress: Float) : JobState()
    data class Done(val uri: Uri)           : JobState()
    data class Failed(val reason: String)   : JobState()
}

// ─── Service ─────────────────────────────────────────────────────────────────

class YdlDownloadService : Service() {

    inner class LocalBinder : Binder() {
        val service get() = this@YdlDownloadService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    private val _jobs = MutableStateFlow<Map<Long, DownloadJob>>(emptyMap())
    val jobs: StateFlow<Map<Long, DownloadJob>> = _jobs.asStateFlow()

    private var nextId = System.currentTimeMillis()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<Long, Job>()

    private var wakeLock: WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var lockAcquired = false

    private lateinit var notificationManager: NotificationManager
    private var lastNotificationUpdate = 0L

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetwork(true)
        override fun onLost(network: Network)      = onNetwork(false)
    }
    @Volatile private var networkAvailable = true

    override fun onCreate() {
        super.onCreate()
        notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java)!!
        connectivityManager  = ContextCompat.getSystemService(this, ConnectivityManager::class.java)!!

        createNotificationChannels()
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)

        val powerMgr = ContextCompat.getSystemService(this, PowerManager::class.java)!!
        val wifiMgr  = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        wakeLock  = powerMgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YDL::download")
        wifiLock  = wifiMgr.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YDL::wifi")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOWNLOAD) {
            val videoUrl  = intent.getStringExtra(EXTRA_VIDEO_URL) ?: return START_NOT_STICKY
            val audioUrl  = intent.getStringExtra(EXTRA_AUDIO_URL) ?: return START_NOT_STICKY
            val filename  = intent.getStringExtra(EXTRA_FILENAME)  ?: "download.mp4"
            enqueue(videoUrl, audioUrl, filename)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        manageLock(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun enqueue(videoUrl: String, audioUrl: String, filename: String): Long {
        val id = nextId++
        val job = DownloadJob(id, filename, videoUrl, audioUrl)
        updateJob(job)
        startForegroundIfNeeded()
        activeJobs[id] = scope.launch { runDownload(job) }
        return id
    }

    fun cancelJob(id: Long) {
        activeJobs.remove(id)?.cancel()
        val job = _jobs.value[id] ?: return
        updateJob(job.copy(state = JobState.Failed("Cancelled")))
        checkIdle()
    }

    private suspend fun runDownload(job: DownloadJob) {
        manageLock(true)
        try {
            updateJob(job.copy(state = JobState.Resolving))

            val videoTmp = cacheFile("ydl_${job.id}_v.tmp")
            downloadStream(
                url         = job.videoUrl,
                dest        = videoTmp,
                stageName   = "Downloading video",
                jobId       = job.id,
                weightStart = 0f,
                weightEnd   = 0.5f,
            )

            val audioTmp = cacheFile("ydl_${job.id}_a.tmp")
            downloadStream(
                url         = job.audioUrl,
                dest        = audioTmp,
                stageName   = "Downloading audio",
                jobId       = job.id,
                weightStart = 0.5f,
                weightEnd   = 0.9f,
            )

            updateJob(_jobs.value[job.id]!!.copy(state = JobState.Merging(0f)))
            val outFile = publicOutputFile(job.filename)
            mergeWithFfmpeg(videoTmp, audioTmp, outFile, job.id)

            videoTmp.delete()
            audioTmp.delete()

            val uri = Uri.fromFile(outFile)
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))

            updateJob(_jobs.value[job.id]!!.copy(state = JobState.Done(uri)))
            postDoneNotification(job.filename)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: "Unknown error"
            updateJob(_jobs.value[job.id]!!.copy(state = JobState.Failed(reason)))
            postFailedNotification(job.filename, reason)
        } finally {
            activeJobs.remove(job.id)
            checkIdle()
        }
    }

    private suspend fun downloadStream(
        url: String,
        dest: java.io.File,
        stageName: String,
        jobId: Long,
        weightStart: Float,
        weightEnd: Float,
    ) = withContext(Dispatchers.IO) {
        var resumeFrom = if (dest.exists()) dest.length() else 0L
        var retries = 0

        while (true) {
            if (!networkAvailable) {
                delay(2_000)
                continue
            }

            try {
                val conn = openConnection(url, resumeFrom)
                val totalFromServer = conn.contentLengthLong.takeIf { it > 0 }
                val grandTotal = if (totalFromServer != null) resumeFrom + totalFromServer else null

                conn.inputStream.use { input ->
                    java.io.FileOutputStream(dest, resumeFrom > 0).use { output ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        var written = resumeFrom

                        while (input.read(buf).also { bytesRead = it } != -1) {
                            ensureActive()
                            output.write(buf, 0, bytesRead)
                            written += bytesRead

                            val fraction = if (grandTotal != null && grandTotal > 0)
                                written.toFloat() / grandTotal.toFloat()
                            else 0f
                            @Suppress("UNUSED_VARIABLE")
                            val overall = weightStart + fraction * (weightEnd - weightStart)

                            throttledUpdate(jobId) {
                                _jobs.value[jobId]!!.copy(
                                    state = JobState.Downloading(
                                        stageName = stageName,
                                        byteDone  = written,
                                        byteTotal = grandTotal ?: 0L,
                                    )
                                )
                            }
                            throttledNotification(jobId, stageName, written, grandTotal ?: 0L)
                        }
                    }
                }
                conn.disconnect()
                return@withContext

            } catch (e: CancellationException) {
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                resumeFrom = if (dest.exists()) dest.length() else 0L
                if (++retries > MAX_RETRIES) throw e
                delay(RETRY_DELAY_MS)
            } catch (e: java.net.ConnectException) {
                resumeFrom = if (dest.exists()) dest.length() else 0L
                if (++retries > MAX_RETRIES) throw e
                delay(RETRY_DELAY_MS)
            } catch (e: java.io.IOException) {
                resumeFrom = if (dest.exists()) dest.length() else 0L
                if (++retries > MAX_RETRIES) throw e
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private fun openConnection(url: String, resumeFrom: Long): java.net.HttpURLConnection {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "*")
        conn.connectTimeout = 30_000
        conn.readTimeout    = 0
        if (resumeFrom > 0) {
            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        conn.connect()
        return conn
    }

    // Fix: use suspendCancellableCoroutine with correct resumeWithException extension
    private suspend fun mergeWithFfmpeg(
        videoTmp: java.io.File,
        audioTmp: java.io.File,
        output: java.io.File,
        jobId: Long,
    ) = suspendCancellableCoroutine<Unit> { cont ->
        if (output.exists()) output.delete()

        val cmd = "-y " +
                "-i \"${videoTmp.absolutePath}\" " +
                "-i \"${audioTmp.absolutePath}\" " +
                "-c:v copy -c:a copy " +
                "-map 0:v:0 -map 1:a:0 " +
                "-movflags +faststart " +
                "\"${output.absolutePath}\""

        val session = com.arthenica.ffmpegkit.FFmpegKit.executeAsync(
            cmd,
            { session ->
                if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                    cont.resume(Unit) {}
                } else {
                    val log = session.allLogsAsString?.takeLast(500) ?: "no log"
                    cont.cancel(Exception("FFmpeg failed: $log"))
                }
            },
            null,
            { stats ->
                if (stats != null && stats.time > 0) {
                    // stats.time is Long (ms); cast to Float before dividing
                    val rough = (stats.time.toFloat() / 1000f).coerceIn(0f, 100f) / 100f
                    updateJob(_jobs.value[jobId]!!.copy(state = JobState.Merging(rough)))
                    updateForegroundNotificationMerging(jobId, rough)
                }
            }
        )

        cont.invokeOnCancellation {
            com.arthenica.ffmpegkit.FFmpegKit.cancel(session.sessionId)
        }
    }

    private fun cacheFile(name: String) = java.io.File(cacheDir, name)

    private fun publicOutputFile(filename: String): java.io.File {
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "YDL"
        ).also { it.mkdirs() }
        return java.io.File(dir, filename)
    }

    private fun updateJob(job: DownloadJob) {
        _jobs.update { it + (job.id to job) }
    }

    private fun throttledUpdate(jobId: Long, producer: () -> DownloadJob) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate > 300) {
            updateJob(producer())
        }
    }

    private fun throttledNotification(jobId: Long, stageName: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate < NOTIFICATION_THROTTLE_MS) return
        lastNotificationUpdate = now
        val job = _jobs.value[jobId] ?: return
        updateForegroundNotificationDownload(job.filename, stageName, done, total)
    }

    private fun checkIdle() {
        if (activeJobs.isEmpty()) {
            manageLock(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun onNetwork(available: Boolean) {
        networkAvailable = available
    }

    private fun manageLock(acquire: Boolean) {
        if (acquire == lockAcquired) return
        if (acquire) {
            if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 60 * 1000L)
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } else {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        lockAcquired = acquire
    }

    private fun startForegroundIfNeeded() {
        val notification = buildProgressNotification("YDL Download", "Starting…", 0, 100)
        startForeground(NOTIF_FOREGROUND_ID, notification)
    }

    private fun updateForegroundNotificationDownload(filename: String, stage: String, done: Long, total: Long) {
        val pct   = if (total > 0) ((done * 100) / total).toInt() else 0
        val doneS = formatBytes(done)
        val totS  = if (total > 0) formatBytes(total) else "?"
        val notif = buildProgressNotification(filename, "$stage — $doneS / $totS", pct, 100)
        notificationManager.notify(NOTIF_FOREGROUND_ID, notif)
    }

    private fun updateForegroundNotificationMerging(jobId: Long, fraction: Float) {
        val job = _jobs.value[jobId] ?: return
        val pct = (fraction * 100).toInt()
        val notif = buildProgressNotification(job.filename, "Merging… $pct%", pct, 100)
        notificationManager.notify(NOTIF_FOREGROUND_ID, notif)
    }

    private fun postDoneNotification(filename: String) {
        val tapIntent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, filename.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(filename)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        notificationManager.notify(NOTIF_DONE_BASE + filename.hashCode(), notif)
    }

    private fun postFailedNotification(filename: String, reason: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Download failed")
            .setContentText(filename)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$filename\n$reason"))
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIF_DONE_BASE + filename.hashCode() + 1, notif)
    }

    private fun buildProgressNotification(title: String, text: String, progress: Int, max: Int): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(max, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createNotificationChannels() {
        val progressCh = NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Shows download progress" }
        val doneCh = NotificationChannel(CHANNEL_DONE, "Download complete", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Alerts when a download finishes or fails" }
        notificationManager.createNotificationChannels(listOf(progressCh, doneCh))
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024     -> "%d KB".format(bytes / 1_024)
        else               -> "$bytes B"
    }

    companion object {
        const val ACTION_DOWNLOAD   = "com.ydl.app.action.DOWNLOAD"
        const val EXTRA_VIDEO_URL   = "extra_video_url"
        const val EXTRA_AUDIO_URL   = "extra_audio_url"
        const val EXTRA_FILENAME    = "extra_filename"

        private const val CHANNEL_PROGRESS      = "ydl_progress"
        private const val CHANNEL_DONE          = "ydl_done"
        private const val NOTIF_FOREGROUND_ID   = 1001
        private const val NOTIF_DONE_BASE       = 2000

        private const val BUFFER_SIZE               = 64 * 1024
        private const val MAX_RETRIES               = 5
        private const val RETRY_DELAY_MS            = 3_000L
        private const val NOTIFICATION_THROTTLE_MS  = 800L

        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        fun start(context: Context, videoUrl: String, audioUrl: String, filename: String) {
            val intent = Intent(context, YdlDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                putExtra(EXTRA_AUDIO_URL, audioUrl)
                putExtra(EXTRA_FILENAME, filename)
            }
            context.startForegroundService(intent)
        }
    }
}
