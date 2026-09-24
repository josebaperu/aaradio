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
        val items = queue.map { MediaItem.Builder().setMediaId(it.id).build() }
        c.setMediaItems(items, queue.indexOf(station).coerceAtLeast(0), C.TIME_UNSET)
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

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()

    fun toggleFavorite(station: Station) = StationRepository.toggleFavorite(station.id)

    fun toggleSort() = StationRepository.setSortDescending(!sortDescending.value)

    override fun onCleared() {
        controller?.run {
            removeListener(listener)
            release()
        }
    }
}
