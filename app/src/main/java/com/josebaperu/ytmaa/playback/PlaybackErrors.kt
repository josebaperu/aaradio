package com.josebaperu.ytmaa.playback

import androidx.media3.common.PlaybackException

/** User-facing text for a playback error, shared by the phone snackbar and the Android Auto error screen. */
fun describePlaybackError(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection — retrying…"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Track unavailable"
    else -> "Playback error (${e.errorCodeName})"
}
