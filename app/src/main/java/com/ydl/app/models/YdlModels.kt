package com.ydl.app.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class FormatType { VIDEO_AUDIO, VIDEO_ONLY, AUDIO_ONLY }

@Parcelize
data class VideoFormat(
    val type: FormatType,
    val quality: String,
    val height: Int?,
    val fps: Int?,
    val ext: String,
    val vcodec: String?,
    val acodec: String?,
    val abr: Int?,
    val filesizeBytes: Long?,
    val formatId: String,
    val directUrl: String?,
    // FIX: previewUrl is the raw video-only stream URL used for in-app preview.
    // For VIDEO_AUDIO (merged) formats, directUrl is null (needs resolve+merge),
    // but previewUrl gives a playable video stream (no audio) for the player preview.
    // For VIDEO_ONLY / AUDIO_ONLY, previewUrl == directUrl.
    val previewUrl: String?,
    val merged: Boolean,
    val filename: String,
) : Parcelable {

    val filesizeLabel: String?
        get() = filesizeBytes?.let {
            when {
                it >= 1_048_576 -> "%.1f MB".format(it / 1_048_576.0)
                it >= 1_024     -> "%d KB".format(it / 1_024)
                else            -> "$it B"
            }
        }
}

@Parcelize
data class VideoInfo(
    val title: String,
    val channel: String?,
    val durationStr: String?,
    val thumbnail: String?,
    val viewCountStr: String?,
    val uploadDate: String?,
    val webpageUrl: String?,
    val formats: List<VideoFormat>,
) : Parcelable

@Parcelize
data class SearchResult(
    val id: String,
    val url: String,
    val title: String,
    val channel: String?,
    val durationStr: String?,
    val viewCountStr: String?,
    val thumbnail: String?,
) : Parcelable

data class ResolvedUrls(
    val videoUrl: String,
    val audioUrl: String,
    val ext: String,
)

sealed class YdlResult<out T> {
    data class Success<T>(val data: T) : YdlResult<T>()
    data class Error(val message: String) : YdlResult<Nothing>()
}
