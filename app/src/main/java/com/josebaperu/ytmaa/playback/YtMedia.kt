package com.josebaperu.ytmaa.playback

import com.josebaperu.ytmaa.data.YtTrack

/** Media ids for YouTube Music tracks. The watch url rides in the id so the service can restore it. */
object YtMedia {
    private const val SEP = "\u001f"

    fun mediaId(playlistId: String, index: Int, track: YtTrack): String =
        listOf("ytm", playlistId, index.toString(), track.id, track.url).joinToString(SEP)

    fun isYtm(mediaId: String?): Boolean = mediaId?.startsWith("ytm$SEP") == true

    fun playlistId(mediaId: String?): String? = parts(mediaId)?.getOrNull(1)

    fun index(mediaId: String?): Int? = parts(mediaId)?.getOrNull(2)?.toIntOrNull()

    fun watchUrl(mediaId: String?): String? =
        parts(mediaId)?.drop(4)?.joinToString(SEP)?.takeIf { it.startsWith("http") }

    private fun parts(mediaId: String?): List<String>? {
        if (mediaId == null || !isYtm(mediaId)) return null
        return mediaId.split(SEP)
    }
}
