package com.josebaperu.ytmaa.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.josebaperu.ytmaa.data.YtLibrary
import com.josebaperu.ytmaa.data.YtPlaylist
import com.josebaperu.ytmaa.data.YtTrack
import java.io.IOException

/**
 * A YouTube Music watch url is turned into a direct audio stream by yt-dlp on the loader
 * thread, then opened with a browser user agent. A copy already in the Music folder is
 * opened instead, and the media item itself keeps the watch url.
 */
@UnstableApi
class YtStreamDataSource(
    private val base: DataSource.Factory,
) : DataSource {
    private val listeners = ArrayList<TransferListener>(2)
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        current?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeCurrent()
        val watch = isYoutubeWatch(dataSpec.uri)
        val ref = if (watch) YtMedia.savedRef(dataSpec.uri) else null
        val playlist = ref?.let { YtLibrary.byId(it.playlistId) }
        val track = if (playlist != null) savedTrack(playlist, ref) else null
        val skip = playlist != null && track != null && TrackStore.skipping(playlist.title, track.id)
        val local = if (playlist != null && track != null && !skip) {
            runCatching { TrackStore.existing(playlist.title, track) }.getOrNull()
        } else {
            null
        }
        if (playlist != null && track != null) TrackStore.noteDisk(playlist.title, track.id, local != null)
        val openedWatch = watch && local == null
        val spec = when {
            local != null -> dataSpec.buildUpon().setUri(local).build()
            watch -> {
                val target = YtMedia.watchTarget(dataSpec.uri)
                val stream = YtDlp.resolveStream(target)
                if (playlist != null && track != null) {
                    val at = playlist.tracks.indexOf(track)
                    val number = if (at >= 0) at + 1 else ref.index + 1
                    TrackStore.request(playlist.title, track, number, playlist.tracks.size, target)
                }
                dataSpec.buildUpon().setUri(Uri.parse(stream)).build()
            }
            else -> dataSpec
        }
        val delegate = base.createDataSource()
        listeners.forEach { delegate.addTransferListener(it) }
        current = delegate
        return try {
            delegate.open(spec)
        } catch (e: IOException) {
            if (openedWatch) YtDlp.invalidate(YtMedia.watchTarget(dataSpec.uri))
            closeCurrent()
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() = closeCurrent()

    private fun closeCurrent() {
        current?.close()
        current = null
    }

    class Factory(
        context: Context,
        upstream: DataSource.Factory,
    ) : DataSource.Factory {
        private val base = DefaultDataSource.Factory(context.applicationContext, upstream)
        override fun createDataSource(): DataSource = YtStreamDataSource(base)
    }

    companion object {
        private fun savedTrack(playlist: YtPlaylist, ref: YtMedia.SavedRef): YtTrack? {
            val at = playlist.tracks.getOrNull(ref.index)
            if (at != null && at.id == ref.videoId) return at
            return playlist.tracks.find { it.id == ref.videoId }
        }

        fun isYoutubeWatch(uri: Uri): Boolean {
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
            if (host == "youtu.be") return (uri.path?.length ?: 0) > 1
            if (host != "youtube.com" && host != "music.youtube.com" && host != "m.youtube.com") return false
            val path = uri.path.orEmpty()
            return path == "/watch" || path.startsWith("/watch/") || path.startsWith("/shorts/") || uri.getQueryParameter("v") != null
        }
    }
}
