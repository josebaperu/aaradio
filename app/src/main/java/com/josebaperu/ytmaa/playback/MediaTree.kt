package com.josebaperu.ytmaa.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.josebaperu.ytmaa.data.YtLibrary
import com.josebaperu.ytmaa.data.YtPlaylist
import com.josebaperu.ytmaa.data.YtPlaylistFile

/** Browse tree exposed to Android Auto and other media browsers. The children are imported playlists. */
object MediaTree {
    const val ROOT = "root"
    const val FAVORITES = "favorites"
    const val ALL = "all"
    const val FAVORITE_ACTION = "com.josebaperu.ytmaa.TOGGLE_FAVORITE"
    private const val PLAYLIST_PREFIX = "pl\u001f"
    private const val BROWSER_ACTION_ID_LIST = "androidx.media.utils.extras.CUSTOM_BROWSER_ACTION_ID_LIST"

    fun playlistMediaId(id: String) = PLAYLIST_PREFIX + id

    fun isPlaylist(mediaId: String?): Boolean = mediaId?.startsWith(PLAYLIST_PREFIX) == true

    fun playlistIdOf(mediaId: String?): String? =
        mediaId?.removePrefix(PLAYLIST_PREFIX)?.takeIf { isPlaylist(mediaId) && it.isNotEmpty() }

    fun rootItem(): MediaItem = folder(ROOT, "YTM", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)

    fun rootExtras() = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        // Android Auto draws the search field when this is true. The legacy stub also sets it
        // when the controller is allowed to search.
        putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
    }

    fun children(parentId: String): List<MediaItem>? = when {
        parentId == ROOT -> listOf(
            folder(FAVORITES, "Favorites", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(ALL, "All", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        )
        parentId == FAVORITES -> YtLibrary.favoritePlaylists().map { playlistItem(it) }
        parentId == ALL -> YtPlaylistFile.sortedByTitle(YtLibrary.state.value.playlists).map { playlistItem(it) }
        isPlaylist(parentId) -> {
            val playlist = YtLibrary.byId(playlistIdOf(parentId) ?: return null) ?: return emptyList()
            playlist.tracks.indices.map { trackItem(playlist, it) }
        }
        else -> null
    }

    fun item(id: String): MediaItem? = when {
        id == ROOT -> rootItem()
        id == FAVORITES -> folder(FAVORITES, "Favorites", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        id == ALL -> folder(ALL, "All", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        isPlaylist(id) -> YtLibrary.byId(playlistIdOf(id) ?: return null)?.let { playlistItem(it) }
        YtMedia.isYtm(id) -> trackFromMediaId(id)
        else -> null
    }

    /** Albums whose title contains the query. Android Auto lists these under its search field. */
    fun search(query: String): List<MediaItem> =
        YtPlaylistFile.matchingTitles(YtLibrary.state.value.playlists, query).map { playlistItem(it) }

    fun playlistItem(playlist: YtPlaylist): MediaItem {
        val playable = playlist.resolved && playlist.tracks.isNotEmpty()
        val subtitle = when {
            playable -> "${playlist.tracks.size} tracks"
            !playlist.error.isNullOrBlank() -> playlist.error
            else -> "Not loaded"
        }
        return MediaItem.Builder()
            .setMediaId(playlistMediaId(playlist.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(playlist.title)
                    .setArtist(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(playable)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setArtworkData(Artwork.pngFor("pl:" + playlist.id, playlist.title), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .setExtras(Bundle().apply {
                        putStringArrayList(BROWSER_ACTION_ID_LIST, arrayListOf(FAVORITE_ACTION))
                    })
                    .build(),
            )
            .build()
    }

    fun trackItem(playlist: YtPlaylist, index: Int): MediaItem {
        val track = playlist.tracks[index]
        val title = track.title.ifBlank { track.id }
        return MediaItem.Builder()
            .setMediaId(YtMedia.mediaId(playlist.id, index, track))
            .setUri(Uri.parse(track.url))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(track.artist)
                    .setAlbumTitle(playlist.title)
                    .setArtworkData(
                        Artwork.pngFor("tr:" + track.id.ifBlank { track.url }, title),
                        MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                    )
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()
    }

    private fun trackFromMediaId(mediaId: String): MediaItem? {
        val playlist = YtLibrary.byId(YtMedia.playlistId(mediaId) ?: return null) ?: return null
        val index = YtMedia.index(mediaId) ?: return null
        if (index !in playlist.tracks.indices) return null
        return trackItem(playlist, index)
    }

    private fun folder(id: String, title: String, type: Int) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build(),
        )
        .build()
}
