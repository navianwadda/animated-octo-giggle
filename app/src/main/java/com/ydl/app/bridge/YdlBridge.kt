package com.ydl.app.bridge

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.ydl.app.models.ResolvedUrls
import com.ydl.app.models.SearchResult
import com.ydl.app.models.VideoInfo
import com.ydl.app.models.YdlParser
import com.ydl.app.models.YdlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YdlBridge(context: Context) {

    init {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }

    private val py by lazy { Python.getInstance() }
    private val bridge by lazy { py.getModule("ydl_bridge") }

    suspend fun ensureReady(): YdlResult<Unit> = YdlResult.Success(Unit)

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
