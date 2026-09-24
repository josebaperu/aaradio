package com.josebaperu.aautoradio.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
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
import com.josebaperu.aautoradio.R
import com.josebaperu.aautoradio.audio.AudioEffectsProcessor
import com.josebaperu.aautoradio.audio.EffectsStore
import com.josebaperu.aautoradio.data.StationRepository
import com.josebaperu.aautoradio.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Plays the stations. As a [MediaLibraryService] it:
 *  - runs as a foreground service with a media notification while playing, so audio continues with the screen off;
 *  - exposes the browse tree, search and playback controls to Android Auto.
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
            StationRepository.favorites.drop(1).collect {
                session?.run {
                    notifyChildrenChanged(MediaTree.FAVORITES, it.size, null)
                    setMediaButtonPreferences(favoriteButtons())
                }
            }
        }
        scope.launch {
            EffectsStore.state.map { it.systemSpatialAudio }.distinctUntilChanged().drop(1).collect {
                player.setAudioAttributes(audioAttributes(it), /* handleAudioFocus = */ true)
            }
        }
        scope.launch {
            StationRepository.sortDescending.drop(1).collect {
                session?.run { MediaTree.sortedListIds().forEach { id -> notifyChildrenChanged(id, Int.MAX_VALUE, null) } }
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
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("aautoradio/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(audioAttributes(EffectsStore.state.value.systemSpatialAudio), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // Holds CPU + Wi-Fi locks while playing so the stream survives screen-off / doze.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            // Next/previous wrap around the station list instead of stopping at the ends.
            .apply { repeatMode = Player.REPEAT_MODE_ALL }
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

    /** Live streams drop (tunnels, cell handovers). Retry with backoff instead of giving up. */
    private inner class ReconnectListener : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) retries = 0
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retries = 0
            mediaItem?.mediaId?.let { StationRepository.lastPlayedId = it }
            session?.setMediaButtonPreferences(favoriteButtons())
        }

        override fun onPlayerError(error: PlaybackException) {
            if (retries >= MAX_RETRIES) return
            val delayMs = 2_000L * (1 shl retries++)
            player.playWhenReady = true
            scope.launch {
                kotlinx.coroutines.delay(delayMs)
                if (player.playerError != null) {
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    private fun favoriteButtons(): ImmutableList<CommandButton> {
        val id = player.currentMediaItem?.mediaId ?: return ImmutableList.of()
        val fav = StationRepository.isFavorite(id)
        return ImmutableList.of(
            CommandButton.Builder(if (fav) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(getString(if (fav) R.string.remove_favorite else R.string.add_favorite))
                .setSessionCommand(TOGGLE_FAVORITE)
                .build()
        )
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName == ANDROID_AUTO_PACKAGE) {
                carControllers++
                EffectsStore.setInCar(true)
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(TOGGLE_FAVORITE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(favoriteButtons())
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (controller.packageName == ANDROID_AUTO_PACKAGE) {
                carControllers = (carControllers - 1).coerceAtLeast(0)
                EffectsStore.setInCar(carControllers > 0)
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == TOGGLE_FAVORITE.customAction) {
                player.currentMediaItem?.mediaId?.let(StationRepository::toggleFavorite)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(MediaTree.rootItem(), LibraryParams.Builder().setExtras(MediaTree.rootExtras()).build())
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
            session.notifySearchResultChanged(browser, query, StationRepository.search(query).size, params)
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
                LibraryResult.ofItemList(StationRepository.search(query).map { it.toMediaItem() }.page(page, pageSize), params)
            )

        /**
         * Controllers (the app UI, Android Auto, Assistant) send items by id or search query only;
         * resolve them to playable items with stream URIs. A single station is expanded into its list.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.size == 1) {
                val item = mediaItems[0]
                val station = MediaTree.stationFor(item.mediaId)
                    ?: item.requestMetadata.searchQuery?.let { StationRepository.search(it).firstOrNull() }
                    // Voice "play aautoradio" with no match: resume the last station.
                    ?: StationRepository[StationRepository.lastPlayedId]
                    ?: StationRepository.stations.first()
                return Futures.immediateFuture(expand(station, MediaTree.queueFor(item.mediaId)))
            }
            val stations = mediaItems.mapNotNull { MediaTree.stationFor(it.mediaId) }
            if (stations.isEmpty()) return Futures.immediateFuture(expand(StationRepository.stations.first()))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    stations.map { it.toMediaItem() },
                    startIndex.coerceIn(0, stations.lastIndex),
                    C.TIME_UNSET,
                )
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(
                mediaItems.mapNotNull { MediaTree.stationFor(it.mediaId)?.toMediaItem() }.toMutableList()
            )

        /** Lets the system (e.g. Android Auto on connect, or the Bluetooth play button) resume the last station. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val last = StationRepository[StationRepository.lastPlayedId]
                ?: StationRepository.favoriteStations().firstOrNull()
                ?: StationRepository.stations.first()
            return Futures.immediateFuture(expand(last))
        }

        private fun expand(
            station: com.josebaperu.aautoradio.data.Station,
            list: List<com.josebaperu.aautoradio.data.Station>? = null,
        ): MediaSession.MediaItemsWithStartPosition {
            val queue = list?.takeIf { station in it } ?: MediaTree.queueFor(station.id)!!
            return MediaSession.MediaItemsWithStartPosition(queue.map { it.toMediaItem() }, queue.indexOf(station), C.TIME_UNSET)
        }
    }

    companion object {
        private const val MAX_RETRIES = 6
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
        val TOGGLE_FAVORITE = SessionCommand("com.josebaperu.aautoradio.TOGGLE_FAVORITE", Bundle.EMPTY)
    }
}

private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
    if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return this
    val from = (page * pageSize).coerceAtMost(size)
    return subList(from, (from + pageSize).coerceAtMost(size))
}
