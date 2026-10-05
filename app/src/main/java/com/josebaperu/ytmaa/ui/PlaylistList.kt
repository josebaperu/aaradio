package com.josebaperu.ytmaa.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.josebaperu.ytmaa.R
import com.josebaperu.ytmaa.data.YtPlaylist

@Composable
fun PlaylistList(
    playlists: List<YtPlaylist>,
    preparing: Boolean,
    resolvingUrl: String?,
    progressDone: Int,
    progressTotal: Int,
    currentId: String?,
    favoriteUrls: Set<String>,
    selecting: Boolean,
    selectedUrls: Set<String>,
    contentPadding: PaddingValues,
    emptyMessage: String,
    onClick: (YtPlaylist) -> Unit,
    onLongClick: (YtPlaylist) -> Unit,
    onFavorite: (YtPlaylist) -> Unit,
) {
    if (playlists.isEmpty() && !preparing && progressTotal == 0) {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Text(
                emptyMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }
    val status = when {
        preparing -> stringResource(R.string.playlist_preparing)
        progressTotal > 0 -> stringResource(R.string.playlist_progress, progressDone, progressTotal)
        else -> null
    }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        if (status != null) {
            item(key = "status") {
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
        items(playlists, key = { it.url }) { playlist ->
            val current = playlist.id == currentId
            val selected = playlist.url in selectedUrls
            val resolving = playlist.url == resolvingUrl
            val detail = playlistDetail(playlist, preparing, resolving, progressTotal > 0)
            val failed = !playlist.resolved && playlist.error != null && !resolving && progressTotal == 0 && !preparing
            ListItem(
                headlineContent = {
                    Text(
                        playlist.title,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        detail,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                },
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) {
                            Checkbox(checked = selected, onCheckedChange = { onClick(playlist) })
                        }
                        LetterAvatar(labelInitials(playlist.title), labelHue(playlist.title), 48.dp, playing = current && !selecting)
                    }
                },
                trailingContent = {
                    if (!selecting) {
                        val favorite = playlist.url in favoriteUrls
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (resolving) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                            IconToggleButton(checked = favorite, onCheckedChange = { onFavorite(playlist) }) {
                                Icon(
                                    painterResource(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
                                    contentDescription = stringResource(if (favorite) R.string.remove_favorite else R.string.add_favorite),
                                    tint = if (favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = when {
                        selected -> MaterialTheme.colorScheme.primaryContainer
                        current -> MaterialTheme.colorScheme.secondaryContainer
                        else -> Color.Transparent
                    },
                ),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .combinedClickable(
                        onClick = { onClick(playlist) },
                        onLongClick = { onLongClick(playlist) },
                    ),
            )
        }
    }
}

@Composable
private fun playlistDetail(playlist: YtPlaylist, preparing: Boolean, resolving: Boolean, importing: Boolean): String =
    when {
        resolving -> stringResource(R.string.playlist_resolving)
        !playlist.resolved && (preparing || importing) -> stringResource(R.string.playlist_waiting)
        !playlist.resolved && playlist.error != null -> playlist.error
        !playlist.resolved -> stringResource(R.string.playlist_not_loaded)
        playlist.tracks.isEmpty() -> stringResource(R.string.playlist_empty)
        else -> stringResource(R.string.playlist_tracks, playlist.tracks.size)
    }
