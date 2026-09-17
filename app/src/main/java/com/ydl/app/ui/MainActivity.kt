package com.ydl.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.ydl.app.download.DownloadState
import com.ydl.app.models.FormatType
import com.ydl.app.models.SearchResult
import com.ydl.app.models.VideoFormat
import com.ydl.app.models.VideoInfo
import com.ydl.app.viewmodel.BridgeState
import com.ydl.app.viewmodel.UiState
import com.ydl.app.viewmodel.YdlViewModel

private val Primary = Color(0xFF6C63FF)
private val Surface = Color(0xFF1A1A2E)
private val SurfaceVariant = Color(0xFF16213E)
private val CardBg = Color(0xFF0F3460)
private val OnSurface = Color(0xFFE0E0E0)
private val Muted = Color(0xFF9E9E9E)

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
                    primary = Primary,
                    background = Surface,
                    surface = SurfaceVariant,
                    onBackground = OnSurface,
                    onSurface = OnSurface,
                )
            ) {
                YdlApp(vm = vm, initialUrl = sharedUrl)
            }
        }
    }
}

@Composable
private fun YdlApp(vm: YdlViewModel, initialUrl: String?) {
    val bridge by vm.bridgeState.collectAsState()
    val ui by vm.ui.collectAsState()
    val download by vm.downloadState.collectAsState()

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) vm.loadUrl(initialUrl)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface)
            .systemBarsPadding()
    ) {
        when (bridge) {
            is BridgeState.Initializing -> SplashScreen()
            is BridgeState.Error -> ErrorScreen(
                message = (bridge as BridgeState.Error).message,
                onRetry = vm::retryInit
            )
            is BridgeState.Ready -> MainScreen(
                ui = ui,
                download = download,
                onSearch = vm::search,
                onLoadUrl = vm::loadUrl,
                onDownload = vm::download,
                onResetDownload = vm::resetDownload,
                onClearError = vm::clearError,
            )
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("YDL", color = OnSurface, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(color = Primary, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFEF5350), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(message, color = OnSurface, fontSize = 14.sp, maxLines = 4)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun MainScreen(
    ui: UiState,
    download: DownloadState,
    onSearch: (String) -> Unit,
    onLoadUrl: (String) -> Unit,
    onDownload: (VideoFormat) -> Unit,
    onResetDownload: () -> Unit,
    onClearError: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize().imePadding()) {
        TopSearchBar(
            query = query,
            onQueryChange = { query = it },
            onSearch = {
                keyboard?.hide()
                if (query.startsWith("http")) onLoadUrl(query.trim()) else onSearch(query.trim())
            }
        )

        AnimatedVisibility(download !is DownloadState.Idle) {
            DownloadBanner(state = download, onDismiss = onResetDownload)
        }

        Box(Modifier.weight(1f)) {
            when (ui) {
                is UiState.Idle -> IdleHint()
                is UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorInline(ui.message, onClearError)
                is UiState.SearchResults -> SearchResultsList(ui.results, onLoadUrl)
                is UiState.VideoReady -> VideoDetailScreen(ui.info, ui.url, onDownload)
            }
        }
    }
}

@Composable
private fun TopSearchBar(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    Surface(color = SurfaceVariant, shadowElevation = 4.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Download, null, tint = Primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search or paste URL", color = Muted, fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Color(0xFF333355),
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    cursorColor = Primary,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Clear, null, tint = Muted) } }
                } else null,
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSearch,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Primary)
            ) {
                Icon(Icons.Default.Search, null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun DownloadBanner(state: DownloadState, onDismiss: () -> Unit) {
    val (icon, text, color) = when (state) {
        is DownloadState.Idle      -> return
        is DownloadState.Preparing -> Triple(Icons.Default.HourglassTop, "Preparing…", Color(0xFF42A5F5))
        is DownloadState.Merging   -> Triple(Icons.Default.MergeType, "Merging ${(state.progress * 100).toInt()}%", Color(0xFFAB47BC))
        is DownloadState.Enqueued  -> Triple(Icons.Default.CloudDownload, "Downloading", Color(0xFF66BB6A))
        is DownloadState.Done      -> Triple(Icons.Default.CheckCircle, "Done!", Color(0xFF4CAF50))
        is DownloadState.Failed    -> Triple(Icons.Default.Error, "Failed", Color(0xFFEF5350))
    }

    Surface(color = color.copy(alpha = 0.15f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            val detail = when (state) {
                is DownloadState.Preparing -> state.filename
                is DownloadState.Enqueued  -> state.filename
                is DownloadState.Done      -> state.filename
                is DownloadState.Failed    -> state.message.take(60)
                else -> ""
            }
            Column(Modifier.weight(1f)) {
                Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (detail.isNotBlank()) Text(detail, color = OnSurface, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state is DownloadState.Merging) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { state.progress }, color = color, modifier = Modifier.fillMaxWidth())
                }
            }
            if (state is DownloadState.Done || state is DownloadState.Failed) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = Muted) }
            }
        }
    }
}

@Composable
private fun IdleHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.VideoLibrary, null, tint = Primary.copy(alpha = 0.4f), modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text("Search or paste a URL", color = Muted, fontSize = 16.sp)
            Text("YouTube, Twitter, TikTok & more", color = Muted.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Primary)
            Spacer(Modifier.height(12.dp))
            Text("Fetching…", color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ErrorInline(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFEF5350), modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(message, color = OnSurface, fontSize = 14.sp, maxLines = 5)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRetry, border = BorderStroke(1.dp, Primary)) {
                Text("Dismiss", color = Primary)
            }
        }
    }
}

@Composable
private fun SearchResultsList(results: List<SearchResult>, onSelect: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(results, key = { it.id }) { result ->
            SearchResultCard(result = result, onClick = { onSelect(result.url) })
        }
    }
}

@Composable
private fun SearchResultCard(result: SearchResult, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(110.dp, 70.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardBg)
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
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(dur, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    result.title,
                    color = OnSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(4.dp))
                result.channel?.let { Text(it, color = Muted, fontSize = 11.sp, maxLines = 1) }
                result.viewCountStr?.let { Text(it, color = Muted.copy(alpha = 0.7f), fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun VideoDetailScreen(info: VideoInfo, url: String, onDownload: (VideoFormat) -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val merged = remember(info) { info.formats.filter { it.type == FormatType.VIDEO_AUDIO } }
    val videoOnly = remember(info) { info.formats.filter { it.type == FormatType.VIDEO_ONLY } }
    val audioOnly = remember(info) { info.formats.filter { it.type == FormatType.AUDIO_ONLY } }

    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            val streamUrl = info.formats
                .filter { it.type == FormatType.VIDEO_AUDIO && it.directUrl != null }
                .maxByOrNull { it.height ?: 0 }
                ?.directUrl
                ?: url
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        item {
            Column(Modifier.padding(12.dp)) {
                Text(
                    info.title,
                    color = OnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    info.channel?.let {
                        Icon(Icons.Default.Person, null, tint = Muted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(it, color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.width(12.dp))
                    }
                    info.viewCountStr?.let {
                        Icon(Icons.Default.Visibility, null, tint = Muted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(it, color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.width(12.dp))
                    }
                    info.durationStr?.let {
                        Icon(Icons.Default.Timer, null, tint = Muted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(it, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            val tabs = listOf(
                "Video+Audio" to merged.size,
                "Video" to videoOnly.size,
                "Audio" to audioOnly.size,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceVariant)
                    .padding(4.dp)
            ) {
                tabs.forEachIndexed { idx, (label, count) ->
                    val selected = selectedTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Primary else Color.Transparent)
                            .clickable { selectedTab = idx }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                label,
                                color = if (selected) Color.White else Muted,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                "$count",
                                color = if (selected) Color.White.copy(0.8f) else Muted.copy(0.6f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        val activeFormats = when (selectedTab) {
            0 -> merged
            1 -> videoOnly
            else -> audioOnly
        }

        items(activeFormats, key = { it.formatId }) { format ->
            FormatCard(format = format, onDownload = { onDownload(format) })
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun FormatCard(format: VideoFormat, onDownload: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (badgeColor, badgeLabel) = when (format.type) {
                FormatType.VIDEO_AUDIO -> Primary to "V+A"
                FormatType.VIDEO_ONLY  -> Color(0xFF29B6F6) to "VID"
                FormatType.AUDIO_ONLY  -> Color(0xFF66BB6A) to "AUD"
            }

            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(badgeLabel, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(format.quality, color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val meta = buildList {
                    add(format.ext.uppercase())
                    format.fps?.let { add("${it}fps") }
                    format.filesizeLabel?.let { add(it) }
                    if (format.merged) add("needs merge")
                }
                Text(meta.joinToString(" · "), color = Muted, fontSize = 11.sp)
            }

            FilledTonalButton(
                onClick = onDownload,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Primary.copy(alpha = 0.2f),
                    contentColor = Primary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Get", fontSize = 12.sp)
            }
        }
    }
}
