package com.josebaperu.ytmaa.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.josebaperu.ytmaa.R
import com.josebaperu.ytmaa.audio.EqStore
import com.josebaperu.ytmaa.data.YtLibrary
import com.josebaperu.ytmaa.data.YtPlaylist
import com.josebaperu.ytmaa.data.YtPlaylistFile
import com.josebaperu.ytmaa.data.YtTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YTMScreen(isDark: Boolean, onToggleTheme: () -> Unit, vm: RadioViewModel = viewModel()) {
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val eqState by EqStore.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by YtLibrary.state.collectAsStateWithLifecycle()
    val favorites by YtLibrary.favorites.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(if (favorites.isEmpty()) 1 else 0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showEq by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.errors.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { YtLibrary.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        YtLibrary.readyToPlay.collect { url ->
            val playlist = YtLibrary.state.value.playlists.find { it.url == url } ?: return@collect
            vm.playPlaylist(playlist)
        }
    }
    LaunchedEffect(library.playlists) {
        val urls = library.playlists.map { it.url }.toSet()
        val next = selected.intersect(urls)
        if (next != selected) selected = next
        if (selecting && next.isEmpty()) selecting = false
    }
    val openFile = rememberLauncherForActivityResult(OpenWritableDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                snackbar.showSnackbar(context.getString(R.string.playlist_unreadable))
                return@launch
            }
            if (!YtLibrary.import(text)) return@launch
            val deleted = withContext(Dispatchers.IO) { deleteChosenFile(context, uri) }
            if (!deleted) snackbar.showSnackbar(context.getString(R.string.file_not_deleted))
        }
    }

    fun toggleSelected(url: String) {
        val next = if (url in selected) selected - url else selected + url
        selected = next
        selecting = next.isNotEmpty()
    }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
        confirmDelete = false
    }

    val ordered = remember(library.playlists) { YtPlaylistFile.sortedByTitle(library.playlists) }
    val tabList = remember(tab, ordered, favorites) {
        if (tab == 0) ordered.filter { it.url in favorites } else ordered
    }
    val shownPlaylists = remember(tabList, query, searching) {
        val q = query.trim().lowercase()
        if (!searching || q.isEmpty()) tabList
        else tabList.filter { q in it.title.lowercase() }
    }
    val playingPlaylist = library.playlists.find { it.id == nowPlaying.playlistId }

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    BackHandler(enabled = selecting && !confirmDelete) { exitSelection() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        when {
                            selecting -> Text(stringResource(R.string.selected_count, selected.size))
                            searching -> TextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.search)) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            else -> Text(stringResource(R.string.playlists))
                        }
                    },
                    navigationIcon = {
                        if (selecting) {
                            IconButton(onClick = { exitSelection() }) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel))
                            }
                        }
                    },
                    actions = {
                        if (selecting) {
                            IconButton(onClick = { if (selected.isNotEmpty()) confirmDelete = true }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete))
                            }
                        } else {
                            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                                Icon(
                                    painterResource(if (searching) R.drawable.ic_close else R.drawable.ic_search),
                                    contentDescription = stringResource(R.string.search),
                                )
                            }
                            IconButton(onClick = onToggleTheme) {
                                Icon(
                                    painterResource(if (isDark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode),
                                    contentDescription = stringResource(if (isDark) R.string.light_theme else R.string.dark_theme),
                                )
                            }
                            IconButton(onClick = {
                                showTracks = false
                                showEq = true
                            }) {
                                Icon(
                                    painterResource(R.drawable.ic_equalizer),
                                    contentDescription = stringResource(R.string.equalizer),
                                    tint = if (eqState.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    scrollBehavior = scroll,
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text(stringResource(R.string.favorites) + " · ${favorites.size}") },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text(stringResource(R.string.all_playlists) + " · ${library.playlists.size}") },
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { openFile.launch(arrayOf("*/*")) }) {
                Icon(painterResource(R.drawable.ic_open_file), contentDescription = stringResource(R.string.load_playlists))
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = nowPlaying.playlistId != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                PlayerBar(
                    nowPlaying = nowPlaying,
                    onOpenTracks = {
                        if (playingPlaylist != null) {
                            showEq = false
                            showTracks = true
                        }
                    },
                    onPlayPause = vm::togglePlayPause,
                    onNext = { vm.next() },
                    onPrevious = { vm.previous() },
                )
            }
        },
    ) { padding ->
        PlaylistList(
            playlists = shownPlaylists,
            preparing = library.preparing,
            resolvingUrl = library.resolvingUrl,
            progressDone = library.progressDone,
            progressTotal = library.progressTotal,
            currentId = nowPlaying.playlistId,
            favoriteUrls = favorites,
            selecting = selecting,
            selectedUrls = selected,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            emptyMessage = stringResource(
                when {
                    searching && query.isNotBlank() -> R.string.no_results
                    tab == 0 -> R.string.no_favorites
                    else -> R.string.no_playlists
                },
            ),
            onClick = { playlist ->
                if (selecting) {
                    toggleSelected(playlist.url)
                } else when {
                    playlist.resolved && playlist.tracks.isNotEmpty() -> vm.playPlaylist(playlist)
                    playlist.resolved -> scope.launch { snackbar.showSnackbar(context.getString(R.string.playlist_empty)) }
                    else -> YtLibrary.resolveAndPlay(playlist.url)
                }
            },
            onLongClick = { playlist ->
                if (!selecting) {
                    selecting = true
                    selected = setOf(playlist.url)
                } else {
                    toggleSelected(playlist.url)
                }
            },
            onFavorite = { YtLibrary.toggleFavorite(it.url) },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_playlists_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    val urls = selected
                    val ids = library.playlists.filter { it.url in urls }.map { it.id }.toSet()
                    YtLibrary.remove(urls)
                    vm.forgetPlaylists(ids)
                    exitSelection()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showEq) {
        ModalBottomSheet(
            onDismissRequest = { showEq = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            EqualizerPanel(eqState)
        }
    }

    val tracksPlaylist = playingPlaylist
    if (showTracks && tracksPlaylist != null) {
        ModalBottomSheet(
            onDismissRequest = { showTracks = false },
            // The sheet drag gesture swallows the first tap on a row. The list only needs taps.
            sheetGesturesEnabled = false,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            TrackSheet(
                playlist = tracksPlaylist,
                currentIndex = nowPlaying.trackIndex,
                playing = nowPlaying.isPlaying,
                onPick = { index -> vm.playAt(tracksPlaylist, index) },
            )
        }
    }
}

@Composable
private fun TrackSheet(
    playlist: YtPlaylist,
    currentIndex: Int,
    playing: Boolean,
    onPick: (Int) -> Unit,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Text(
            playlist.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            itemsIndexed(playlist.tracks, key = { index, track -> "$index:${track.id}:${track.url}" }) { index, track ->
                TrackRow(
                    track = track,
                    index = index,
                    current = index == currentIndex,
                    playing = playing && index == currentIndex,
                    onClick = { onPick(index) },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: YtTrack,
    index: Int,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val title = track.title.ifBlank { track.id }
    ListItem(
        headlineContent = {
            Text(
                title,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val line = listOf(track.artist.trim(), formatDuration(track.durationSec))
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            if (line.isNotEmpty()) Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            if (playing) {
                PlayingBars(
                    Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    )
}

/** Opens any text file and asks for permission to delete it after a successful import. */
private class OpenWritableDocument : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (input.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, input)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

private fun deleteChosenFile(context: Context, uri: Uri): Boolean = try {
    if (DocumentsContract.isDocumentUri(context, uri)) {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } else {
        context.contentResolver.delete(uri, null, null) > 0
    }
} catch (_: Exception) {
    false
}

private fun formatDuration(seconds: Double): String {
    if (seconds.isNaN() || seconds <= 0.0) return ""
    val total = seconds.toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerBar(
    nowPlaying: NowPlaying,
    onOpenTracks: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    if (nowPlaying.playlistId == null) return
    val name = nowPlaying.headline.ifBlank { "YouTube Music" }
    Surface(
        onClick = onOpenTracks,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LetterAvatar(labelInitials(name), labelHue(name), 56.dp, playing = nowPlaying.isPlaying)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    nowPlaying.subline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious) { Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.previous)) }
                Box(contentAlignment = Alignment.Center) {
                    FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(52.dp)) {
                        Icon(
                            painterResource(if (nowPlaying.isPlaying || nowPlaying.isBuffering) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = stringResource(if (nowPlaying.isPlaying) R.string.pause else R.string.play),
                        )
                    }
                    if (nowPlaying.isBuffering) {
                        CircularProgressIndicator(Modifier.size(60.dp), strokeWidth = 3.dp)
                    }
                }
                IconButton(onClick = onNext) { Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.next)) }
            }
        }
    }
}
