package com.josebaperu.ytmaa.playback

import android.content.Context
import android.util.Log
import com.josebaperu.ytmaa.data.YtPlaylistFile
import com.josebaperu.ytmaa.data.YtTrack
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * yt-dlp, bundled for Android. Listing a playlist is saved by [com.josebaperu.ytmaa.data.YtLibrary].
 * A stream address expires, so it is resolved when playback opens the track and kept only in memory.
 */
object YtDlp {
    private const val TAG = "YtDlp"
    private val EXPIRE = Pattern.compile("[?&/]expire[=/](\\d+)")
    private val lock = Any()
    private val activeId = AtomicReference<String?>(null)
    private val streams = HashMap<String, Cached>()

    @Volatile private var app: Context? = null
    @Volatile private var ready = false

    private class Cached(val url: String, val expiresEpoch: Long)

    fun attach(context: Context) {
        app = context.applicationContext
    }

    /** Extracts the bundled Python runtime and refreshes yt-dlp once per process. Blocks. */
    fun ensureInit() {
        synchronized(lock) {
            if (ready) return
            val context = app ?: throw IOException("yt-dlp is not ready")
            try {
                YoutubeDL.getInstance().init(context)
                runCatching { FFmpeg.getInstance().init(context) }
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) }
                    .onFailure { Log.w(TAG, "yt-dlp update skipped", it) }
                ready = true
            } catch (e: Exception) {
                throw IOException(short(e.message, "Couldn't prepare yt-dlp"), e)
            } catch (e: LinkageError) {
                // R8 or a static initializer can throw Error. Keep that on the playlist row.
                throw IOException(short(e.cause?.message ?: e.message, "Couldn't prepare yt-dlp"), e)
            }
        }
    }

    /** Tracks of a playlist or album, in listed order. Blocks. */
    fun listPlaylist(url: String): List<YtTrack> {
        val request = YoutubeDLRequest(url)
        request.addOption("--flat-playlist")
        request.addOption("--ignore-errors")
        request.addOption("--no-warnings")
        request.addOption("--dump-json")
        request.addOption("--remote-components", "ejs:github")
        return YtPlaylistFile.parseFlatListing(run(request))
    }

    /**
     * Direct audio url for a watch page. Reused until YouTube's expiry, minus two minutes.
     * Blocks; called from ExoPlayer's loader thread.
     */
    fun resolveStream(watchUrl: String): String {
        synchronized(streams) {
            val cached = streams[watchUrl]
            if (cached != null && !expired(cached)) return cached.url
        }
        val request = YoutubeDLRequest(watchUrl)
        request.addOption("-f", "bestaudio/best")
        request.addOption("--no-playlist")
        request.addOption("--no-warnings")
        request.addOption("--print", "urls")
        request.addOption("--remote-components", "ejs:github")
        val stream = run(request).lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http") }
            ?: throw IOException("yt-dlp found no audio stream")
        val match = EXPIRE.matcher(stream)
        val expires = if (match.find()) match.group(1)?.toLongOrNull() else null
        val expiry = expires ?: (System.currentTimeMillis() / 1000 + 3600)
        synchronized(streams) { streams[watchUrl] = Cached(stream, expiry) }
        return stream
    }

    /** Drops a stream address that playback refused, so the next attempt looks it up again. */
    fun invalidate(watchUrl: String?) {
        if (watchUrl == null) return
        synchronized(streams) { streams.remove(watchUrl) }
    }

    /** Stops the yt-dlp process started by this object, if one is running. */
    fun cancelActive() {
        val id = activeId.get() ?: return
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }

    private fun expired(cached: Cached): Boolean =
        cached.expiresEpoch > 0 && System.currentTimeMillis() / 1000 > cached.expiresEpoch - 120

    private fun run(request: YoutubeDLRequest): String {
        synchronized(lock) {
            ensureInit()
            val id = UUID.randomUUID().toString()
            activeId.set(id)
            try {
                val response = YoutubeDL.getInstance().execute(request, id, null)
                if (response.out.isBlank() && response.exitCode != 0) {
                    throw IOException(short(response.err, "yt-dlp exited with ${response.exitCode}"))
                }
                return response.out
            } catch (e: YoutubeDL.CanceledException) {
                throw IOException("yt-dlp was stopped")
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp failed", e)
                throw IOException(short(e.message, "yt-dlp failed"), e)
            } finally {
                activeId.compareAndSet(id, null)
            }
        }
    }

    private fun short(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        var last = ""
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("ERROR:")) last = trimmed.removePrefix("ERROR:").trim()
            else if (last.isEmpty() && trimmed.isNotEmpty()) last = trimmed
        }
        last = last.replace(Regex("^\\[[^]]*]\\s*"), "")
        return last.ifBlank { fallback }.take(180)
    }
}
