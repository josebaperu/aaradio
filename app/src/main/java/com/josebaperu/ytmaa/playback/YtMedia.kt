package com.josebaperu.ytmaa.playback

import android.net.Uri
import com.josebaperu.ytmaa.data.YtTrack

/** Media ids for YouTube Music tracks. The watch url rides in the id so the service can restore it. */
object YtMedia {
    private const val SEP = "\u001f"
    private const val REF_PREFIX = "ytmaa="

    /** Playlist, queue index and video id carried on the watch url so a save knows its folder. */
    data class SavedRef(val playlistId: String, val index: Int, val videoId: String)

    fun mediaId(playlistId: String, index: Int, track: YtTrack): String =
        listOf("ytm", playlistId, index.toString(), track.id, track.url).joinToString(SEP)

    fun isYtm(mediaId: String?): Boolean = mediaId?.startsWith("ytm$SEP") == true

    fun playlistId(mediaId: String?): String? = parts(mediaId)?.getOrNull(1)

    fun index(mediaId: String?): Int? = parts(mediaId)?.getOrNull(2)?.toIntOrNull()

    fun videoId(mediaId: String?): String? = parts(mediaId)?.getOrNull(3)?.takeIf { it.isNotEmpty() }

    fun watchUrl(mediaId: String?): String? =
        parts(mediaId)?.drop(4)?.joinToString(SEP)?.takeIf { it.startsWith("http") }

    /** Watch url plus a fragment naming the playlist. yt-dlp never sees the fragment. */
    fun playableUri(watchUrl: String, playlistId: String, index: Int, videoId: String): Uri {
        val fragment = REF_PREFIX + Uri.encode(playlistId) + "/" + index + "/" + Uri.encode(videoId)
        return Uri.parse(watchUrl).buildUpon().fragment(fragment).build()
    }

    fun savedRef(uri: Uri): SavedRef? {
        val fragment = uri.fragment ?: return null
        if (!fragment.startsWith(REF_PREFIX)) return null
        val pieces = fragment.removePrefix(REF_PREFIX).split('/')
        if (pieces.size != 3) return null
        val index = pieces[1].toIntOrNull() ?: return null
        val playlistId = Uri.decode(pieces[0])
        val videoId = Uri.decode(pieces[2])
        if (playlistId.isEmpty() || videoId.isEmpty()) return null
        return SavedRef(playlistId, index, videoId)
    }

    /** Watch url without the playback fragment, so yt-dlp and the stream cache see one address. */
    fun watchTarget(uri: Uri): String = uri.buildUpon().fragment(null).build().toString()

    private fun parts(mediaId: String?): List<String>? {
        if (mediaId == null || !isYtm(mediaId)) return null
        return mediaId.split(SEP)
    }
}
