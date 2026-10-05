package com.josebaperu.ytmaa.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * A YouTube Music watch url is turned into a direct audio stream by yt-dlp on the loader
 * thread, then opened with a browser user agent. The media item itself keeps the watch url.
 */
@UnstableApi
class YtStreamDataSource(
    private val upstream: DataSource.Factory,
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
        val spec = if (watch) {
            val stream = YtDlp.resolveStream(dataSpec.uri.toString())
            dataSpec.buildUpon().setUri(Uri.parse(stream)).build()
        } else {
            dataSpec
        }
        val delegate = upstream.createDataSource()
        listeners.forEach { delegate.addTransferListener(it) }
        current = delegate
        return try {
            delegate.open(spec)
        } catch (e: IOException) {
            if (watch) YtDlp.invalidate(dataSpec.uri.toString())
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
        private val upstream: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = YtStreamDataSource(upstream)
    }

    companion object {
        fun isYoutubeWatch(uri: Uri): Boolean {
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
            if (host == "youtu.be") return (uri.path?.length ?: 0) > 1
            if (host != "youtube.com" && host != "music.youtube.com" && host != "m.youtube.com") return false
            val path = uri.path.orEmpty()
            return path == "/watch" || path.startsWith("/watch/") || path.startsWith("/shorts/") || uri.getQueryParameter("v") != null
        }
    }
}
