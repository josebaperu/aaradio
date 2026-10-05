package com.josebaperu.ytmaa.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.josebaperu.ytmaa.data.YtPlaylist
import com.josebaperu.ytmaa.playback.MediaTree
import com.josebaperu.ytmaa.playback.RadioService
import com.josebaperu.ytmaa.playback.YtMedia
import com.josebaperu.ytmaa.playback.describePlaybackError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

data class NowPlaying(
    val playlistId: String? = null,
    val trackIndex: Int = -1,
    val headline: String = "",
    val subline: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
)

class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private var controller: MediaController? = null
    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    /** One-shot playback error messages, shown as a snackbar. */
    private val _errors = Channel<String>(Channel.CONFLATED)
    val errors: Flow<String> = _errors.receiveAsFlow()
    /** Error already reported since the last user action or successful start, so a retry doesn't repeat it. */
    private var reportedError: String? = null

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
        val id = player.currentMediaItem?.mediaId
        val ytm = YtMedia.isYtm(id)
        val meta = player.mediaMetadata
        _nowPlaying.value = NowPlaying(
            playlistId = if (ytm) YtMedia.playlistId(id) else null,
            trackIndex = if (ytm) YtMedia.index(id) ?: player.currentMediaItemIndex else -1,
            headline = if (ytm) meta.title?.toString().orEmpty().ifBlank { "YouTube Music" } else "",
            subline = if (ytm) {
                listOfNotNull(meta.artist, meta.albumTitle).map { it.toString() }.filter { it.isNotBlank() }.joinToString(" · ")
            } else {
                ""
            },
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady,
        )
        if (player.playbackState == Player.STATE_READY) reportedError = null
        player.playerError?.let { describePlaybackError(it) }?.takeIf { it != reportedError }?.let {
            reportedError = it
            _errors.trySend(it)
        }
    }

    /** Plays a resolved playlist from the first track. A second tap on the same playlist resumes. */
    fun playPlaylist(playlist: YtPlaylist) {
        val c = controller ?: return
        if (!playlist.resolved || playlist.tracks.isEmpty()) return
        reportedError = null
        if (YtMedia.playlistId(c.currentMediaItem?.mediaId) == playlist.id && c.playerError == null) {
            if (!c.isPlaying) c.play()
            return
        }
        val items = playlist.tracks.indices.map { MediaTree.trackItem(playlist, it) }
        c.setMediaItems(items, 0, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    /** Plays [index] in [playlist]. The queue stays this playlist, and the last track does not advance past it. */
    fun playAt(playlist: YtPlaylist, index: Int) {
        val c = controller ?: return
        if (!playlist.resolved || index !in playlist.tracks.indices) return
        reportedError = null
        val samePlaylist = YtMedia.playlistId(c.currentMediaItem?.mediaId) == playlist.id &&
            c.mediaItemCount == playlist.tracks.size &&
            c.playerError == null
        if (samePlaylist) {
            if (c.currentMediaItemIndex != index || c.playbackState == Player.STATE_ENDED) c.seekTo(index, 0)
            c.play()
            return
        }
        val items = playlist.tracks.indices.map { MediaTree.trackItem(playlist, it) }
        c.setMediaItems(items, index, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        reportedError = null
        when {
            c.playerError != null -> { c.prepare(); c.play() }
            c.playWhenReady -> c.pause()
            else -> c.play()
        }
    }

    /** Stops playback when the current playlist was removed from the phone. */
    fun forgetPlaylists(ids: Set<String>) {
        val c = controller ?: return
        val current = YtMedia.playlistId(c.currentMediaItem?.mediaId) ?: return
        if (current !in ids) return
        reportedError = null
        c.pause()
        c.clearMediaItems()
    }

    fun next() = step(forward = true)
    fun previous() = step(forward = false)

    private fun step(forward: Boolean) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        reportedError = null
        if (forward && c.currentMediaItemIndex >= c.mediaItemCount - 1) return
        if (forward) c.seekToNextMediaItem() else c.seekToPreviousMediaItem()
        if (c.playerError != null) c.prepare()
        c.play()
    }

    override fun onCleared() {
        controller?.run {
            removeListener(listener)
            release()
        }
    }
}
