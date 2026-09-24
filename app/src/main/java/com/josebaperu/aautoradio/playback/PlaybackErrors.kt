package com.josebaperu.aautoradio.playback

import androidx.media3.common.PlaybackException
import com.josebaperu.aautoradio.data.Station

/** User-facing text for a playback error, shared by the phone snackbar and the Android Auto error screen. */
fun describePlaybackError(e: PlaybackException, station: Station?): String {
    val reason = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection — retrying…"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Station is offline"
        else -> "Playback error (${e.errorCodeName})"
    }
    return station?.let { "${it.name}: $reason" } ?: reason
}
