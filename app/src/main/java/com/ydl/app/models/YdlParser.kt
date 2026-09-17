package com.ydl.app.models

import org.json.JSONArray
import org.json.JSONObject

object YdlParser {

    fun parseVideoInfo(json: String): YdlResult<VideoInfo> {
        return try {
            val obj = JSONObject(json)
            if (obj.has("error")) return YdlResult.Error(obj.getString("error"))

            val formatsArray = obj.getJSONArray("formats")
            val formats = (0 until formatsArray.length()).map { i ->
                parseFormat(formatsArray.getJSONObject(i))
            }

            YdlResult.Success(
                VideoInfo(
                    title        = obj.getString("title"),
                    channel      = obj.optString("channel").ifBlank { null },
                    durationStr  = obj.optString("duration_str").ifBlank { null },
                    thumbnail    = obj.optString("thumbnail").ifBlank { null },
                    viewCountStr = obj.optString("view_count_str").ifBlank { null },
                    uploadDate   = obj.optString("upload_date").ifBlank { null },
                    formats      = formats,
                )
            )
        } catch (e: Exception) {
            YdlResult.Error(e.message ?: "Failed to parse video info")
        }
    }

    fun parseSearchResults(json: String): YdlResult<List<SearchResult>> {
        return try {
            val obj = JSONObject(json)
            if (obj.has("error")) return YdlResult.Error(obj.getString("error"))

            val arr: JSONArray = obj.getJSONArray("results")
            val results = (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                SearchResult(
                    id           = r.getString("id"),
                    url          = r.getString("url"),
                    title        = r.getString("title"),
                    channel      = r.optString("channel").ifBlank { null },
                    durationStr  = r.optString("duration_str").ifBlank { null },
                    viewCountStr = r.optString("view_count_str").ifBlank { null },
                    thumbnail    = r.optString("thumbnail").ifBlank { null },
                )
            }

            YdlResult.Success(results)
        } catch (e: Exception) {
            YdlResult.Error(e.message ?: "Failed to parse search results")
        }
    }

    fun parseResolvedUrls(json: String): YdlResult<ResolvedUrls> {
        return try {
            val obj = JSONObject(json)
            if (obj.has("error")) return YdlResult.Error(obj.getString("error"))

            YdlResult.Success(
                ResolvedUrls(
                    videoUrl = obj.getString("video_url"),
                    audioUrl = obj.getString("audio_url"),
                    ext      = obj.optString("ext", "mp4"),
                )
            )
        } catch (e: Exception) {
            YdlResult.Error(e.message ?: "Failed to parse resolved URLs")
        }
    }

    private fun parseFormat(obj: JSONObject): VideoFormat {
        val type = when (obj.getString("type")) {
            "video+audio" -> FormatType.VIDEO_AUDIO
            "video-only"  -> FormatType.VIDEO_ONLY
            else          -> FormatType.AUDIO_ONLY
        }

        return VideoFormat(
            type          = type,
            quality       = obj.getString("quality"),
            height        = obj.optInt("height").takeIf { it > 0 },
            fps           = obj.optInt("fps").takeIf { it > 0 },
            ext           = obj.optString("ext", "mp4"),
            vcodec        = obj.optString("vcodec").ifBlank { null },
            acodec        = obj.optString("acodec").ifBlank { null },
            abr           = obj.optInt("abr").takeIf { it > 0 },
            filesizeBytes = obj.optLong("filesize").takeIf { it > 0 },
            formatId      = obj.getString("format_id"),
            directUrl     = obj.optString("direct_url").ifBlank { null },
            merged        = obj.optBoolean("merged", false),
            filename      = obj.getString("filename"),
        )
    }
}
