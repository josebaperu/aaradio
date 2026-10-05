package com.josebaperu.ytmaa.data

/** One object from a playlist export. Only the title and the playlist url are required. */
data class FilePlaylist(
    val id: String,
    val title: String,
    val url: String,
    val updatedDate: Long,
)

/** One track listed from a YouTube Music playlist. The watch url is what playback resolves. */
data class YtTrack(
    val id: String,
    val url: String,
    val title: String,
    val artist: String,
    val durationSec: Double,
)

/**
 * A playlist from the latest imported file, with the track list saved after yt-dlp has listed it.
 * [resolved] means the track list is final, including a playlist that listed as empty.
 */
data class YtPlaylist(
    val id: String,
    val title: String,
    val url: String,
    val updatedDate: Long,
    val resolved: Boolean,
    val tracks: List<YtTrack>,
    val error: String?,
)
