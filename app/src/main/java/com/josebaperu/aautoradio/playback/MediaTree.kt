package com.josebaperu.aautoradio.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.josebaperu.aautoradio.data.Station
import com.josebaperu.aautoradio.data.StationRepository

/** Browse tree exposed to Android Auto and other media browsers. */
object MediaTree {
    const val ROOT = "root"
    const val FAVORITES = "favorites"
    const val ALL = "all"
    const val GENRES = "genres"
    private const val GENRE_PREFIX = "genre:"

    fun rootItem(): MediaItem = folder(ROOT, "aautoradio", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** Hints so Android Auto renders stations as a grid of tiles, and folders as lists. */
    fun rootExtras() = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
    }

    fun children(parentId: String): List<MediaItem>? = when {
        parentId == ROOT -> listOf(
            folder(FAVORITES, "Favorites", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS),
            folder(ALL, "All stations", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS),
            folder(GENRES, "Genres", MediaMetadata.MEDIA_TYPE_FOLDER_GENRES),
        )
        parentId == FAVORITES -> StationRepository.favoriteStations().grouped()
        parentId == ALL -> StationRepository.sortedStations().grouped()
        parentId == GENRES -> StationRepository.genres().map {
            folder(GENRE_PREFIX + it, it.replaceFirstChar(Char::titlecase), MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
        }
        parentId.startsWith(GENRE_PREFIX) -> StationRepository.byGenre(parentId.removePrefix(GENRE_PREFIX)).grouped()
        else -> null
    }

    fun item(id: String): MediaItem? =
        StationRepository[id]?.toMediaItem()
            ?: if (id == ROOT) rootItem() else children(ROOT)?.firstOrNull { it.mediaId == id }

    /** The list a station should be queued in, so next/previous in the car cycle through something sensible. */
    fun queueFor(station: Station): List<Station> =
        StationRepository.favoriteStations().takeIf { station in it } ?: StationRepository.sortedStations()

    /** Ids of every list whose order depends on the alphabetical direction. */
    fun sortedListIds(): List<String> = listOf(FAVORITES, ALL) + StationRepository.genres().map { GENRE_PREFIX + it }

    /** Android Auto shows a letter header above each run of items sharing the same group title. */
    private fun List<Station>.grouped(): List<MediaItem> = map { station ->
        station.toMediaItem(Bundle().apply {
            putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, station.groupLetter)
        })
    }

    private fun folder(id: String, title: String, type: Int) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build()
        )
        .build()
}

fun Station.toMediaItem(extras: Bundle? = null): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(Uri.parse(url))
    .setLiveConfiguration(MediaItem.LiveConfiguration.UNSET)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(name)
            .setDisplayTitle(name)
            // Stays visible under the song title once the stream sends ICY metadata (e.g. in Android Auto).
            .setArtist("$name · ${genre.replaceFirstChar(Char::titlecase)}")
            .setStation(name)
            .setGenre(genre)
            .setArtworkData(Artwork.pngFor(this), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setExtras(extras)
            .build()
    )
    .build()
