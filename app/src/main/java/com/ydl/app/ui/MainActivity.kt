package com.ydl.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ydl.app.download.DownloadState
import com.ydl.app.models.FormatType
import com.ydl.app.models.SearchResult
import com.ydl.app.models.VideoFormat
import com.ydl.app.models.VideoInfo
import com.ydl.app.viewmodel.BridgeState
import com.ydl.app.viewmodel.UiState
import com.ydl.app.viewmodel.YdlViewModel

class MainActivity : ComponentActivity() {

    private val vm: YdlViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = intent
            ?.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    YdlApp(vm = vm, initialUrl = sharedUrl)
                }
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

    when (bridge) {
        is BridgeState.Initializing -> FullScreenMessage("Loading yt-dlp…")
        is BridgeState.Error -> FullScreenError(
            message = (bridge as BridgeState.Error).message,
            onRetry = vm::retryInit
        )
        is BridgeState.Ready -> MainContent(
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

@Composable
private fun MainContent(
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

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = query,
            onQueryChange = { query = it },
            onSearch = {
                keyboard?.hide()
                if (query.startsWith("http")) onLoadUrl(query) else onSearch(query)
            }
        )

        DownloadBanner(state = download, onDismiss = onResetDownload)

        Box(modifier = Modifier.weight(1f)) {
            when (ui) {
                is UiState.Idle -> FullScreenMessage("Search or paste a URL")
                is UiState.Loading -> FullScreenMessage("Loading…")
                is UiState.Error -> FullScreenError(message = ui.message, onRetry = onClearError)
                is UiState.SearchResults -> SearchResultsList(
                    results = ui.results,
                    onSelect = onLoadUrl,
                )
                is UiState.VideoReady -> VideoDetail(
                    info = ui.info,
                    onDownload = onDownload,
                )
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search or paste URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSearch) { Text("Go") }
    }
}

@Composable
private fun DownloadBanner(state: DownloadState, onDismiss: () -> Unit) {
    val text = when (state) {
        is DownloadState.Idle      -> return
        is DownloadState.Preparing -> "Preparing ${state.filename}…"
        is DownloadState.Merging   -> "Merging ${state.filename} (${(state.progress * 100).toInt()}%)"
        is DownloadState.Enqueued  -> "Downloading ${state.filename}"
        is DownloadState.Done      -> "Done: ${state.filename}"
        is DownloadState.Failed    -> "Failed: ${state.message}"
    }
    val isDone = state is DownloadState.Done || state is DownloadState.Failed

    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isDone) TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun SearchResultsList(results: List<SearchResult>, onSelect: (String) -> Unit) {
    LazyColumn {
        items(results, key = { it.id }) { result ->
            ListItem(
                headlineContent = { Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text("${result.channel ?: ""} • ${result.durationStr ?: ""} • ${result.viewCountStr ?: ""}")
                },
                leadingContent = {
                    AsyncImage(
                        model = result.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp, 48.dp),
                        contentScale = ContentScale.Crop,
                    )
                },
                modifier = Modifier.clickable { onSelect(result.url) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun VideoDetail(info: VideoInfo, onDownload: (VideoFormat) -> Unit) {
    LazyColumn {
        item {
            AsyncImage(
                model = info.thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Text(info.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(info.channel, info.durationStr, info.viewCountStr).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
        }

        items(info.formats, key = { it.formatId }) { format ->
            FormatRow(format = format, onDownload = { onDownload(format) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun FormatRow(format: VideoFormat, onDownload: () -> Unit) {
    ListItem(
        headlineContent = { Text(format.quality) },
        supportingContent = {
            val details = buildList {
                if (format.type != FormatType.AUDIO_ONLY) add(format.ext.uppercase())
                format.fps?.let { add("${it}fps") }
                format.filesizeLabel?.let { add(it) }
                if (format.merged) add("needs merge")
            }
            Text(details.joinToString(" · "))
        },
        leadingContent = {
            val label = when (format.type) {
                FormatType.VIDEO_AUDIO -> "V+A"
                FormatType.VIDEO_ONLY  -> "V"
                FormatType.AUDIO_ONLY  -> "A"
            }
            Badge { Text(label) }
        },
        trailingContent = {
            TextButton(onClick = onDownload) { Text("Download") }
        }
    )
}

@Composable
private fun FullScreenMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun FullScreenError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}
