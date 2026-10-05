package com.josebaperu.ytmaa.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.josebaperu.ytmaa.R
import com.josebaperu.ytmaa.audio.AudioEffectsProcessor
import com.josebaperu.ytmaa.audio.EffectsStore
import com.josebaperu.ytmaa.data.YtLibrary
import com.josebaperu.ytmaa.data.YtPlaylist
import com.josebaperu.ytmaa.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Plays imported YouTube Music playlists. As a [MediaLibraryService] it:
 *  - runs as a foreground service with a media notification while playing, so audio continues with the screen off;
 *  - exposes the playlist browse tree, search and playback controls to Android Auto.
 */
@OptIn(UnstableApi::class)
class RadioService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var retries = 0
    /** Android Auto connections; headphone-only effects pause while any is connected. */
    private var carControllers = 0

    override fun onCreate() {
        super.onCreate()
        player = buildPlayer()
        player.addListener(ReconnectListener())

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        scope.launch {
            YtLibrary.state.drop(1).collect { state ->
                val current = session ?: return@collect
                current.notifyChildrenChanged(MediaTree.ROOT, 2, null)
                current.notifyChildrenChanged(MediaTree.ALL, state.playlists.size, null)
                current.notifyChildrenChanged(MediaTree.FAVORITES, YtLibrary.favoritePlaylists().size, null)
                state.playlists.forEach { playlist ->
                    current.notifyChildrenChanged(MediaTree.playlistMediaId(playlist.id), playlist.tracks.size, null)
                }
            }
        }
        scope.launch {
            YtLibrary.favorites.drop(1).collect {
                val current = session ?: return@collect
                current.notifyChildrenChanged(MediaTree.FAVORITES, YtLibrary.favoritePlaylists().size, null)
                current.setMediaButtonPreferences(favoriteButtons())
            }
        }
        scope.launch {
            EffectsStore.state.map { it.systemSpatialAudio }.distinctUntilChanged().drop(1).collect {
                player.setAudioAttributes(audioAttributes(it), /* handleAudioFocus = */ true)
            }
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false) // effects chain works on 16-bit PCM
                .setAudioProcessors(arrayOf(AudioEffectsProcessor()))
                .build()
        }
        val youtube = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to "https://music.youtube.com/"))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(YtStreamDataSource.Factory(youtube)))
            .setAudioAttributes(audioAttributes(EffectsStore.state.value.systemSpatialAudio), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            // The queue is one playlist. Stop when its last track ends.
            .apply { repeatMode = Player.REPEAT_MODE_OFF }
    }

    private fun audioAttributes(spatial: Boolean) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setSpatializationBehavior(if (spatial) C.SPATIALIZATION_BEHAVIOR_AUTO else C.SPATIALIZATION_BEHAVIOR_NEVER)
        .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        scope.cancel()
        EffectsStore.setInCar(false)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** A refused stream is looked up once more. Further failures stay on screen. */
    private inner class ReconnectListener : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                retries = 0
                session?.setPlaybackException(null)
            } else if (state == Player.STATE_ENDED) {
                player.pause()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retries = 0
            session?.setPlaybackException(null)
            YtMedia.playlistId(mediaItem?.mediaId)?.let(YtLibrary::rememberPlaying)
            session?.setMediaButtonPreferences(favoriteButtons())
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId
            if (YtMedia.isYtm(id)) YtDlp.invalidate(YtMedia.watchUrl(id))
            showError(error)
            if (retries >= 1) return
            val delayMs = 2_000L * (1 shl retries++)
            player.playWhenReady = true
            scope.launch {
                kotlinx.coroutines.delay(delayMs)
                if (player.playerError != null) {
                    session?.setPlaybackException(null)
                    player.prepare()
                    player.play()
                }
            }
        }

        /**
         * Android Auto can't show app UI such as a snackbar; it renders the session's error state instead.
         * Swap ExoPlayer's raw message for the same friendly text the phone shows, keeping the error code.
         */
        private fun showError(error: PlaybackException) {
            val title = player.currentMediaItem?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
            val text = describePlaybackError(error)
            val message = if (title != null) "$title: $text" else text
            session?.setPlaybackException(PlaybackException(message, error.cause, error.errorCode))
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName == ANDROID_AUTO_PACKAGE) {
                carControllers++
                EffectsStore.setInCar(true)
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(MediaTree.FAVORITE_ACTION, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(favoriteButtons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == MediaTree.FAVORITE_ACTION) {
                val url = playlistUrl(args.getString(ACTION_MEDIA_ITEM_ID))
                    ?: playlistUrl(player.currentMediaItem?.mediaId)
                url?.let(YtLibrary::toggleFavorite)
                session.setMediaButtonPreferences(favoriteButtons())
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (controller.packageName == ANDROID_AUTO_PACKAGE) {
                carControllers = (carControllers - 1).coerceAtLeast(0)
                EffectsStore.setInCar(carControllers > 0)
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(MediaTree.rootItem(), LibraryParams.Builder().setExtras(rootExtras()).build())
            )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                MediaTree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = MediaTree.children(parentId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItemList(children.page(page, pageSize), params))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, MediaTree.search(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(
                LibraryResult.ofItemList(MediaTree.search(query).page(page, pageSize), params)
            )

        /**
         * Controllers send items by id. A playlist id expands to its tracks. One track expands to the
         * rest of that playlist. A full queue from the phone is played as sent.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.size > 1 && mediaItems.all { YtMedia.isYtm(it.mediaId) }) {
                return ready(ytmQueue(mediaItems, startIndex, startPositionMs))
            }
            if (mediaItems.size == 1) {
                return ready(resolveOne(mediaItems[0], startPositionMs) ?: resumeQueue(startPositionMs))
            }
            val tracks = mediaItems.filter { YtMedia.isYtm(it.mediaId) }
            if (tracks.isNotEmpty()) return ready(ytmQueue(tracks, startIndex, startPositionMs))
            return ready(resumeQueue(startPositionMs))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val out = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                when {
                    YtMedia.isYtm(item.mediaId) -> out += item.withWatchUri()
                    MediaTree.isPlaylist(item.mediaId) -> {
                        val playlist = YtLibrary.byId(MediaTree.playlistIdOf(item.mediaId) ?: continue) ?: continue
                        out += playlist.tracks.indices.map { MediaTree.trackItem(playlist, it) }
                    }
                }
            }
            return Futures.immediateFuture(out)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = ready(resumeQueue(C.TIME_UNSET))

        private fun resolveOne(item: MediaItem, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition? {
            MediaTree.playlistIdOf(item.mediaId)?.let { id ->
                val playlist = YtLibrary.byId(id)?.takeIf { it.tracks.isNotEmpty() } ?: return null
                return queueOf(playlist, 0, startPositionMs)
            }
            if (YtMedia.isYtm(item.mediaId)) {
                val playlist = YtLibrary.byId(YtMedia.playlistId(item.mediaId) ?: return null)
                val index = YtMedia.index(item.mediaId) ?: 0
                if (playlist != null && playlist.tracks.isNotEmpty()) return queueOf(playlist, index, startPositionMs)
                return ytmQueue(listOf(item), 0, startPositionMs)
            }
            val query = item.requestMetadata.searchQuery.orEmpty()
            if (query.isNotBlank()) {
                val hit = MediaTree.search(query).firstOrNull() ?: return null
                return resolveOne(hit, startPositionMs)
            }
            return null
        }

        private fun resumeQueue(startPositionMs: Long): MediaSession.MediaItemsWithStartPosition? {
            val playlist = YtLibrary.lastPlayed() ?: YtLibrary.playable().firstOrNull() ?: return null
            return queueOf(playlist, 0, startPositionMs)
        }

        private fun ready(items: MediaSession.MediaItemsWithStartPosition?): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            if (items != null) Futures.immediateFuture(items)
            else Futures.immediateFailedFuture(IllegalStateException("No playlist"))

        private fun queueOf(
            playlist: YtPlaylist,
            startIndex: Int,
            startPositionMs: Long,
        ): MediaSession.MediaItemsWithStartPosition {
            val items = playlist.tracks.indices.map { MediaTree.trackItem(playlist, it) }
            return MediaSession.MediaItemsWithStartPosition(
                items,
                startIndex.coerceIn(0, items.lastIndex),
                startPositionMs,
            )
        }

        /** A queue that is already tracks. Put the watch url back on each item. */
        private fun ytmQueue(
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): MediaSession.MediaItemsWithStartPosition {
            val items = mediaItems.map { it.withWatchUri() }
            return MediaSession.MediaItemsWithStartPosition(
                items,
                startIndex.coerceIn(0, items.lastIndex),
                startPositionMs,
            )
        }

        private fun MediaItem.withWatchUri(): MediaItem {
            val watch = YtMedia.watchUrl(mediaId) ?: return this
            return buildUpon().setUri(Uri.parse(watch)).build()
        }
    }

    private fun favoriteButtons(): ImmutableList<CommandButton> {
        val url = playlistUrl(player.currentMediaItem?.mediaId) ?: return ImmutableList.of()
        val fav = YtLibrary.isFavorite(url)
        return ImmutableList.of(
            CommandButton.Builder(if (fav) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(getString(if (fav) R.string.remove_favorite else R.string.add_favorite))
                .setSessionCommand(SessionCommand(MediaTree.FAVORITE_ACTION, Bundle.EMPTY))
                .build(),
        )
    }

    private fun playlistUrl(mediaId: String?): String? {
        val id = when {
            MediaTree.isPlaylist(mediaId) -> MediaTree.playlistIdOf(mediaId)
            YtMedia.isYtm(mediaId) -> YtMedia.playlistId(mediaId)
            else -> null
        } ?: return null
        return YtLibrary.byId(id)?.url
    }

    /** Browse-tree hints plus the heart action Android Auto can put on each playlist row. */
    private fun rootExtras(): Bundle = MediaTree.rootExtras().apply {
        val action = Bundle().apply {
            putString("androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_ID", MediaTree.FAVORITE_ACTION)
            putString("androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_LABEL", getString(R.string.add_favorite))
            putString(
                "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_ICON_URI",
                "android.resource://$packageName/drawable/ic_favorite_border",
            )
        }
        putParcelableArrayList("androidx.media.utils.extras.CUSTOM_BROWSER_ACTION_ROOT_LIST", arrayListOf(action))
    }

    companion object {
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
        private const val ACTION_MEDIA_ITEM_ID = "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_MEDIA_ITEM_ID"
    }
}

private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
    if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return this
    val from = (page * pageSize).coerceAtMost(size)
    return subList(from, (from + pageSize).coerceAtMost(size))
}
