@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ydl.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource  // FIX: added for merged audio+video preview
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.ydl.app.download.DownloadState
import com.ydl.app.models.FormatType
import com.ydl.app.models.SearchResult
import com.ydl.app.models.VideoFormat
import com.ydl.app.models.VideoInfo
import com.ydl.app.viewmodel.BridgeState
import com.ydl.app.viewmodel.LoadState
import com.ydl.app.viewmodel.Screen
import com.ydl.app.viewmodel.YdlViewModel
import kotlinx.coroutines.launch

private val BgDark    = Color(0xFF0D0D0D)
private val BgCard    = Color(0xFF1C1C1E)
private val BgSheet   = Color(0xFF1C1C1E)
private val Accent    = Color(0xFFFF3B30)
private val AccentBlue = Color(0xFF0A84FF)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF8E8E93)
private val Divider   = Color(0xFF2C2C2E)
private val TabGreen  = Color(0xFF30D158)
private val TabBlue   = Color(0xFF0A84FF)
private val TabOrange = Color(0xFFFF9F0A)

class MainActivity : ComponentActivity() {
    private val vm: YdlViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val sharedUrl = intent
            ?.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BgDark,
                    surface = BgCard,
                    primary = AccentBlue,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary,
                )
            ) {
                YdlRoot(vm = vm, initialUrl = sharedUrl)
            }
        }
    }
}

@Composable
private fun YdlRoot(vm: YdlViewModel, initialUrl: String?) {
    val bridge by vm.bridgeState.collectAsState()
    val screen by vm.screen.collectAsState()
    val loadState by vm.loadState.collectAsState()
    val downloadState by vm.downloadState.collectAsState()

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) vm.loadUrl(initialUrl)
    }

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    DisposableEffect(screen) {
        val cb = object : OnBackPressedCallback(screen !is Screen.Home) {
            override fun handleOnBackPressed() { vm.navigateBack() }
        }
        backDispatcher?.addCallback(cb)
        onDispose { cb.remove() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
    ) {
        when (bridge) {
            is BridgeState.Initializing -> SplashScreen()
            is BridgeState.Error -> ErrorFullScreen(
                (bridge as BridgeState.Error).message, vm::retryInit
            )
            is BridgeState.Ready -> {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        if (targetState is Screen.Home) {
                            slideInHorizontally { -it } + fadeIn() togetherWith
                            slideOutHorizontally { it } + fadeOut()
                        } else {
                            slideInHorizontally { it } + fadeIn() togetherWith
                            slideOutHorizontally { -it } + fadeOut()
                        }
                    },
                    label = "screen"
                ) { s ->
                    when (s) {
                        is Screen.Home -> HomeScreen(
                            loadState = loadState,
                            downloadState = downloadState,
                            onSearch = vm::search,
                            onLoadUrl = vm::loadUrl,
                            onClearError = vm::clearError,
                            onDismissDownload = vm::resetDownload,
                        )
                        is Screen.Results -> ResultsScreen(
                            results = s.results,
                            loadState = loadState,
                            onSelect = vm::loadUrl,
                            onBack = vm::navigateBack,
                            onClearError = vm::clearError,
                        )
                        is Screen.Detail -> DetailScreen(
                            info = s.info,
                            url = s.url,
                            downloadState = downloadState,
                            onDownload = vm::download,
                            onBack = vm::navigateBack,
                            onDismissDownload = vm::resetDownload,
                        )
                        is Screen.Downloads -> Unit
                    }
                }
            }
        }

        if (loadState is LoadState.Loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BgCard)
                ) {
                    Column(
                        Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = AccentBlue, strokeWidth = 3.dp)
                        Spacer(Modifier.height(16.dp))
                        Text("Loading…", color = TextPrimary, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AccentBlue),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Download, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("YDL", color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Video Downloader", color = TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(color = AccentBlue, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ErrorFullScreen(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ErrorOutline, null, tint = Accent, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("Something went wrong", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(message, color = TextSecondary, fontSize = 13.sp, maxLines = 5)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) { Text("Retry") }
        }
    }
}

@Composable
private fun HomeScreen(
    loadState: LoadState,
    downloadState: DownloadState,
    onSearch: (String) -> Unit,
    onLoadUrl: (String) -> Unit,
    onClearError: () -> Unit,
    onDismissDownload: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize().imePadding()) {
        AppBar(title = "YDL", subtitle = "Video Downloader")

        if (downloadState !is DownloadState.Idle) {
            DownloadProgressCard(state = downloadState, onDismiss = onDismissDownload)
        }

        if (loadState is LoadState.Error) {
            ErrorBanner(message = (loadState as LoadState.Error).message, onDismiss = onClearError)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste URL or search…", color = TextSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = Divider,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentBlue,
                    focusedContainerColor = BgCard,
                    unfocusedContainerColor = BgCard,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    val q = query.trim()
                    if (q.startsWith("http")) onLoadUrl(q) else onSearch(q)
                }),
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, null, tint = TextSecondary) } }
                } else null,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    keyboard?.hide()
                    val q = query.trim()
                    if (q.startsWith("http")) onLoadUrl(q) else onSearch(q)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                enabled = query.isNotBlank(),
            ) {
                Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Search", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.VideoLibrary, null, tint = TextSecondary.copy(alpha = 0.3f), modifier = Modifier.size(80.dp))
                Spacer(Modifier.height(12.dp))
                Text("Search for a video", color = TextSecondary, fontSize = 15.sp)
                Text("or paste a URL above", color = TextSecondary.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ResultsScreen(
    results: List<SearchResult>,
    loadState: LoadState,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onClearError: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppBar(title = "Search Results", onBack = onBack)

        if (loadState is LoadState.Error) {
            ErrorBanner((loadState as LoadState.Error).message, onClearError)
        }

        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No results", color = TextSecondary)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(results, key = { it.id }) { r ->
                    SearchCard(r) { onSelect(r.url) }
                }
            }
        }
    }
}

@Composable
private fun SearchCard(result: SearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .width(160.dp)
                .height(90.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BgCard)
        ) {
            AsyncImage(
                model = result.thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            result.durationStr?.let { dur ->
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(0.8f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(dur, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                result.title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(6.dp))
            result.channel?.let {
                Text(it, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            result.viewCountStr?.let {
                Text(it, color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
    HorizontalDivider(color = Divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 188.dp))
}

// ---------------------------------------------------------------------------
// FIX 1 – VIDEO PLAYER
//
// The old code looked for VIDEO_AUDIO formats with directUrl != null.
// But merged (VIDEO_AUDIO) formats always have directUrl = null — they need a
// resolve + FFmpeg merge step. So player was always null and nothing played.
//
// The fix: use previewUrl instead. For merged formats, previewUrl is the raw
// video-only stream (no audio — but visible). For VIDEO_ONLY formats it is
// the same as directUrl. We pick the best available previewUrl across all
// format types ordered by: merged (highest quality video) > video-only > any.
//
// We also build the player properly, using MergingMediaSource when we have
// both a video and audio previewUrl from the same merged format so the user
// hears audio too. If we only have the video stream the player still works
// (silent preview).
// ---------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailScreen(
    info: VideoInfo,
    url: String,
    downloadState: DownloadState,
    onDownload: (VideoFormat) -> Unit,
    onBack: () -> Unit,
    onDismissDownload: () -> Unit,
) {
    val context = LocalContext.current
    val tabs = listOf("Video + Audio", "Video only", "Audio only")
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()

    val merged    = remember(info) { info.formats.filter { it.type == FormatType.VIDEO_AUDIO } }
    val videoOnly = remember(info) { info.formats.filter { it.type == FormatType.VIDEO_ONLY } }
    val audioOnly = remember(info) { info.formats.filter { it.type == FormatType.AUDIO_ONLY } }

    // FIX: pick a previewUrl from any format, preferring highest-quality merged,
    // then video-only, then audio-only as last resort.
    val previewUrl = remember(info) {
        // Best merged format's previewUrl (video stream, possibly silent)
        merged.filter { it.previewUrl != null }.maxByOrNull { it.height ?: 0 }?.previewUrl
            ?: videoOnly.filter { it.previewUrl != null }.maxByOrNull { it.height ?: 0 }?.previewUrl
            ?: audioOnly.firstOrNull { it.previewUrl != null }?.previewUrl
    }

    // Best audio previewUrl — lets us build a MergingMediaSource for sound
    val audioPreviewUrl = remember(info) {
        audioOnly.filter { it.previewUrl != null }.maxByOrNull { it.abr ?: 0 }?.previewUrl
    }

    val player = remember(previewUrl) {
        if (previewUrl == null) return@remember null

        val dsFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(mapOf(
                "Accept"          to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Origin"          to "https://www.youtube.com",
                "Referer"         to "https://www.youtube.com/",
            ))
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val videoSource = ProgressiveMediaSource.Factory(dsFactory)
            .createMediaSource(MediaItem.fromUri(previewUrl))

        // If we have a separate audio stream, merge them so the preview has sound
        val mediaSource = if (audioPreviewUrl != null && audioPreviewUrl != previewUrl) {
            val audioSource = ProgressiveMediaSource.Factory(dsFactory)
                .createMediaSource(MediaItem.fromUri(audioPreviewUrl))
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(Unit) { onDispose { player?.release() } }

    Column(Modifier.fillMaxSize()) {
        AppBar(title = "", onBack = onBack)

        LazyColumn(Modifier.weight(1f)) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .background(Color.Black)
                ) {
                    if (player != null) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    this.player = player
                                    useController = true
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AsyncImage(
                            model = info.thumbnail,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayCircleOutline, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(56.dp))
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        info.title,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 21.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(AccentBlue.copy(0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                info.channel?.firstOrNull()?.toString() ?: "?",
                                color = AccentBlue,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            info.channel?.let {
                                Text(it, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                            Row {
                                info.viewCountStr?.let {
                                    Text(it, color = TextSecondary, fontSize = 11.sp)
                                    if (info.durationStr != null) Text(" · ", color = TextSecondary, fontSize = 11.sp)
                                }
                                info.durationStr?.let {
                                    Text(it, color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = Divider)
            }

            if (downloadState !is DownloadState.Idle) {
                item {
                    DownloadProgressCard(state = downloadState, onDismiss = onDismissDownload)
                }
            }

            item {
                val tabColors = listOf(TabGreen, TabBlue, TabOrange)
                ScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = BgDark,
                    contentColor = TextPrimary,
                    edgePadding = 0.dp,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            color = tabColors[pagerState.currentPage]
                        )
                    },
                    divider = { HorizontalDivider(color = Divider) }
                ) {
                    tabs.forEachIndexed { i, label ->
                        val count = when (i) { 0 -> merged.size; 1 -> videoOnly.size; else -> audioOnly.size }
                        Tab(
                            selected = pagerState.currentPage == i,
                            onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            selectedContentColor = tabColors[i],
                            unselectedContentColor = TextSecondary,
                        ) {
                            Column(
                                Modifier.padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(label, fontSize = 13.sp, fontWeight = if (pagerState.currentPage == i) FontWeight.SemiBold else FontWeight.Normal)
                                Text("$count formats", fontSize = 10.sp, color = if (pagerState.currentPage == i) tabColors[i].copy(0.8f) else TextSecondary.copy(0.6f))
                            }
                        }
                    }
                }
            }

            item {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                    val formats = when (page) { 0 -> merged; 1 -> videoOnly; else -> audioOnly }
                    val color = listOf(TabGreen, TabBlue, TabOrange)[page]
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        if (formats.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("No formats available", color = TextSecondary, fontSize = 13.sp)
                            }
                        } else {
                            formats.forEach { fmt ->
                                FormatRow(fmt, color) { onDownload(fmt) }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FormatRow(format: VideoFormat, accentColor: Color, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                format.quality,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            val meta = buildList {
                add(format.ext.uppercase())
                format.fps?.let { add("${it}fps") }
                format.filesizeLabel?.let { add(it) }
                if (format.merged) add("requires merge")
            }
            Text(meta.joinToString(" · "), color = TextSecondary, fontSize = 12.sp)
        }

        Button(
            onClick = onDownload,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                format.filesizeLabel ?: "Download",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
    HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun DownloadProgressCard(state: DownloadState, onDismiss: () -> Unit) {
    val (icon, title, subtitle, color, showProgress, progress) = when (state) {
        is DownloadState.Idle          -> return
        is DownloadState.ResolvingUrls -> Tuple6(Icons.Default.CloudSync,     "Resolving streams",  state.filename, AccentBlue, false, 0f)
        is DownloadState.Downloading   -> Tuple6(Icons.Default.CloudDownload, state.stage,          "${(state.progress * 100).toInt()}%", AccentBlue, true, state.progress)
        is DownloadState.Merging       -> Tuple6(Icons.Default.MergeType,     "Merging video",      "${(state.progress * 100).toInt()}% · ${state.filename}", Color(0xFFBF5AF2), true, state.progress)
        is DownloadState.Enqueued      -> Tuple6(Icons.Default.CloudDownload, "Downloading",        state.filename, TabGreen, false, 0f)
        is DownloadState.Done          -> Tuple6(Icons.Default.CheckCircle,   "Download complete",  state.filename, TabGreen, false, 1f)
        is DownloadState.Failed        -> Tuple6(Icons.Default.Error,         "Download failed",    state.message, Accent, false, 0f)
    }

    val isDismissible = state is DownloadState.Done || state is DownloadState.Failed
    val isIndeterminate = state is DownloadState.ResolvingUrls || state is DownloadState.Enqueued

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isDismissible) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                } else {
                    CircularProgressIndicator(
                        color = color,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            if (showProgress) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = color,
                    trackColor = Divider,
                )
            }
        }
    }
}

@Composable
private fun AppBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
) {
    Surface(color = BgDark, shadowElevation = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (title.isNotEmpty()) {
                    Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                subtitle?.let { Text(it, color = TextSecondary, fontSize = 12.sp) }
            }
        }
        HorizontalDivider(color = Divider, thickness = 0.5.dp)
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Accent.copy(0.15f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(message, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

private data class Tuple6<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)
private operator fun <A, B, C, D, E, F> Tuple6<A, B, C, D, E, F>.component1() = a
private operator fun <A, B, C, D, E, F> Tuple6<A, B, C, D, E, F>.component2() = b
private operator fun <A, B, C, D, E, F> Tuple6<A, B, C, D, E, F>.component3() = c
private operator fun <A, B, C, D, E, F> Tuple6<A, B, C, D, E, F>.component4() = d
private operator fun <A, B, C, D, E, F> Tuple6<A, B, C, D, E, F>.component5() = e
private operator fun <A, B, C, D, E, F> Tuple6<A, B, C, D, E, F>.component6() = f

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tabIndicatorOffset(tabPosition: androidx.compose.material3.TabPosition): Modifier = this
