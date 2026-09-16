// v1/YdlBridge.kt
// VERSION 1 — BUNDLED
//
// yt-dlp is installed at build time via Chaquopy's pip block in build.gradle.
// No network calls are made to update it. It will break when YouTube changes
// their API — the fix is a new APK release.
//
// build.gradle (app) — required:
//
//   plugins {
//       id("com.chaquo.python") version "15.0.0"
//   }
//
//   android {
//       defaultConfig {
//           python {
//               pip {
//                   install("yt-dlp")
//               }
//               // Copy ydl_bridge.py from src/main/python/
//           }
//       }
//   }
//
// Place ydl_bridge.py in: src/main/python/ydl_bridge.py

package com.yourapp.ydl.v1

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import android.content.Context
import com.yourapp.ydl.models.ResolvedUrls
import com.yourapp.ydl.models.SearchResult
import com.yourapp.ydl.models.VideoInfo
import com.yourapp.ydl.models.YdlParser
import com.yourapp.ydl.models.YdlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YdlBridge(context: Context) {

    init {
        // Start Chaquopy once per process
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }

    private val py by lazy { Python.getInstance() }
    private val bridge by lazy { py.getModule("ydl_bridge") }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun extractInfo(url: String): YdlResult<VideoInfo> = withContext(Dispatchers.IO) {
        val json = bridge.callAttr("extract_info", url).toString()
        YdlParser.parseVideoInfo(json)
    }

    suspend fun search(query: String, maxResults: Int = 10): YdlResult<List<SearchResult>> =
        withContext(Dispatchers.IO) {
            val json = bridge.callAttr("search_youtube", query, maxResults).toString()
            YdlParser.parseSearchResults(json)
        }

    suspend fun resolveUrls(url: String, formatId: String): YdlResult<ResolvedUrls> =
        withContext(Dispatchers.IO) {
            val json = bridge.callAttr("resolve_merged_urls", url, formatId).toString()
            YdlParser.parseResolvedUrls(json)
        }

    fun getVersion(): String = bridge.callAttr("get_version").toString()
}
