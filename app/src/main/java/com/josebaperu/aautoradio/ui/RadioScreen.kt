package com.josebaperu.aautoradio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.josebaperu.aautoradio.R
import com.josebaperu.aautoradio.audio.EqStore
import com.josebaperu.aautoradio.data.Station
import com.josebaperu.aautoradio.data.StationRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(isDark: Boolean, onToggleTheme: () -> Unit, vm: RadioViewModel = viewModel()) {
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val eqState by EqStore.state.collectAsStateWithLifecycle()
    val descending by vm.sortDescending.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by rememberSaveable { mutableIntStateOf(if (favorites.isEmpty()) 1 else 0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showEq by remember { mutableStateOf(false) }

    // The active tab's full list; next/previous and the player queue follow it.
    val tabList: List<Station> = remember(tab, favorites, descending) {
        StationRepository.sorted(if (tab == 0) vm.stations.filter { it.id in favorites } else vm.stations, descending)
    }
    LaunchedEffect(tabList) { vm.setActiveList(tabList) }
    val shown: List<Station> = remember(tabList, query, searching) {
        val q = query.trim().lowercase()
        if (!searching || q.isEmpty()) tabList
        else tabList.filter { q in it.name.lowercase() || q in it.genre.lowercase() }
    }
    val sections = remember(shown) { shown.groupBy { it.groupLetter } }

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (searching) {
                            TextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(
                                painterResource(if (searching) R.drawable.ic_close else R.drawable.ic_search),
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                    },
                    scrollBehavior = scroll,
                )
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = vm::toggleSort) {
                            Icon(painterResource(R.drawable.ic_sort_by_alpha), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(if (descending) R.string.sort_z_a else R.string.sort_a_z),
                                modifier = Modifier.semantics {
                                    contentDescription = context.getString(if (descending) R.string.sort_desc_label else R.string.sort_asc_label)
                                },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onToggleTheme) {
                            Icon(
                                painterResource(if (isDark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode),
                                contentDescription = stringResource(if (isDark) R.string.light_theme else R.string.dark_theme),
                            )
                        }
                        IconButton(onClick = { showEq = true }) {
                            Icon(
                                painterResource(R.drawable.ic_equalizer),
                                contentDescription = stringResource(R.string.equalizer),
                                tint = if (eqState.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.favorites) + " · ${favorites.size}") })
                    Tab(tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.all_stations) + " · ${vm.stations.size}") })
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = nowPlaying.station != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                PlayerBar(
                    nowPlaying = nowPlaying,
                    onPlayPause = vm::togglePlayPause,
                    onNext = { vm.next() },
                    onPrevious = { vm.previous() },
                )
            }
        },
    ) { padding ->
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (tab == 0 && !searching) R.string.no_favorites else R.string.no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                sections.forEach { (letter, group) ->
                    stickyHeader(key = "header:$letter") { SectionHeader(letter) }
                    items(group, key = { it.id }) { station ->
                    StationRow(
                        station = station,
                        isCurrent = station.id == nowPlaying.station?.id,
                        isPlaying = station.id == nowPlaying.station?.id && nowPlaying.isPlaying,
                        isFavorite = station.id in favorites,
                        onClick = { vm.play(station, tabList) },
                        onFavorite = { vm.toggleFavorite(station) },
                        modifier = Modifier.animateItem(),
                    )
                    }
                }
            }
        }
    }

    if (showEq) {
        ModalBottomSheet(
            onDismissRequest = { showEq = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            EqualizerPanel(eqState)
        }
    }
}

@Composable
private fun SectionHeader(letter: String) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            letter,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun StationRow(
    station: Station,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "row",
    )
    ListItem(
        headlineContent = {
            Text(station.name, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(station.genre.replaceFirstChar(Char::titlecase), maxLines = 1) },
        leadingContent = { StationAvatar(station, 48.dp, playing = isPlaying) },
        trailingContent = {
            IconToggleButton(checked = isFavorite, onCheckedChange = { onFavorite() }) {
                Icon(
                    painterResource(if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
                    contentDescription = stringResource(if (isFavorite) R.string.remove_favorite else R.string.add_favorite),
                    tint = if (isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = container),
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun PlayerBar(
    nowPlaying: NowPlaying,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val station = nowPlaying.station ?: return
    Surface(
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
            StationAvatar(station, 56.dp, playing = nowPlaying.isPlaying)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(station.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    nowPlaying.error ?: nowPlaying.trackInfo ?: station.genre.replaceFirstChar(Char::titlecase),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (nowPlaying.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
