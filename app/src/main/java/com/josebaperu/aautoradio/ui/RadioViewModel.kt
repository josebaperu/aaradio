package com.josebaperu.aautoradio.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.josebaperu.aautoradio.data.Station
import com.josebaperu.aautoradio.data.StationRepository
import com.josebaperu.aautoradio.playback.RadioService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

data class NowPlaying(
    val station: Station? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Song info from the stream's ICY / ID3 metadata, when the station sends it. */
    val trackInfo: String? = null,
    val error: String? = null,
)

class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private var controller: MediaController? = null
    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    val stations: List<Station> = StationRepository.stations
    val favorites = StationRepository.favorites
    val sortDescending = StationRepository.sortDescending

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    }

    init {
        viewModelScope.launch {
            val token = SessionToken(app, ComponentName(app, RadioService::class.java))
            val c = MediaController.Builder(app, token).buildAsync().await()
            controller = c
            c.addListener(listener)
            syncQueue(c, activeList)
            refresh(c)
        }
    }

    private fun refresh(player: Player) {
        val station = StationRepository[player.currentMediaItem?.mediaId]
        _nowPlaying.value = NowPlaying(
            station = station,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady,
            trackInfo = player.mediaMetadata.trackInfo(station),
            error = player.playerError?.let(::describe),
        )
    }

    private fun MediaMetadata.trackInfo(station: Station?): String? {
        val t = title?.toString()?.trim()
        return t?.takeIf { it.isNotEmpty() && it != station?.name }
    }

    private fun describe(e: PlaybackException) = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection — retrying…"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Station is offline"
        else -> "Playback error (${e.errorCodeName})"
    }

    /** Play [station], queueing [queue] so next/previous move through the list the user tapped in. */
    fun play(station: Station, queue: List<Station>) {
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId == station.id && c.playerError == null) {
            if (!c.isPlaying) c.play()
            return
        }
        c.setMediaItems(queue.toMediaItems(), queue.indexOf(station).coerceAtLeast(0), C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        when {
            c.mediaItemCount == 0 -> StationRepository[StationRepository.lastPlayedId]?.let { play(it, stations) }
                ?: play(stations.first(), stations)
            c.playerError != null -> { c.prepare(); c.play() }
            c.playWhenReady -> c.pause()
            else -> c.play()
        }
    }

    /** Stations of the tab currently shown (Favorites or All), in on-screen order. */
    private var activeList: List<Station> = emptyList()

    /** Called whenever the active tab or its contents change, so next/previous follow that tab. */
    fun setActiveList(list: List<Station>) {
        activeList = list
        controller?.let { syncQueue(it, list) }
    }

    fun next() = step(forward = true)
    fun previous() = step(forward = false)

    /** Next/previous cycle (wrapping around) through the active tab's list. */
    private fun step(forward: Boolean) {
        val c = controller ?: return
        val list = activeList
        if (list.isEmpty()) return
        if (syncQueue(c, list)) {
            if (forward) c.seekToNextMediaItem() else c.seekToPreviousMediaItem()
            if (c.playerError != null) c.prepare()
            c.play()
            return
        }
        // The playing station isn't in this tab (e.g. a non-favorite while on Favorites):
        // jump to its alphabetical neighbour within the tab.
        val current = StationRepository[c.currentMediaItem?.mediaId]
        val index = if (current == null) {
            if (forward) 0 else list.lastIndex
        } else {
            val pos = StationRepository.sorted(list + current).indexOf(current)
            if (forward) pos % list.size else (pos - 1 + list.size) % list.size
        }
        c.setMediaItems(list.toMediaItems(), index, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    /**
     * Makes the player's queue equal to [list] without interrupting the current station, as long as
     * that station is in [list]. Returns false (leaving the queue alone) when it isn't.
     */
    private fun syncQueue(c: MediaController, list: List<Station>): Boolean {
        val ids = list.map { it.id }
        if (c.mediaItemCount == 0 || ids.isEmpty()) return false
        if ((0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId } == ids) return true
        val pos = ids.indexOf(c.currentMediaItem?.mediaId)
        if (pos < 0) return false
        val current = c.currentMediaItemIndex
        c.removeMediaItems(current + 1, c.mediaItemCount)
        c.removeMediaItems(0, current)
        c.addMediaItems(0, list.subList(0, pos).toMediaItems())
        c.addMediaItems(list.subList(pos + 1, list.size).toMediaItems())
        return true
    }

    private fun List<Station>.toMediaItems() = map { MediaItem.Builder().setMediaId(it.id).build() }

    fun toggleFavorite(station: Station) = StationRepository.toggleFavorite(station.id)

    fun toggleSort() = StationRepository.setSortDescending(!sortDescending.value)

    override fun onCleared() {
        controller?.run {
            removeListener(listener)
            release()
        }
    }
}
