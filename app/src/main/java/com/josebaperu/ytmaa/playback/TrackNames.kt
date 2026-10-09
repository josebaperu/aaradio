package com.josebaperu.ytmaa.playback

import java.nio.charset.StandardCharsets
import java.text.Normalizer

/**
 * File names for a saved track. The playlist title is the directory and the
 * track title is the file, with characters a folder name cannot keep turned
 * into spaces. A second video that would reuse a name is saved beside it.
 */
internal object TrackNames {
    const val EXTENSION = ".opus"
    const val MIN_BYTES = 256L
    private const val NAME_BYTES = 160

    fun directoryName(album: String): String {
        val name = limit(sanitize(album), NAME_BYTES)
        return name.ifEmpty { "YouTube Music" }
    }

    fun stem(title: String, id: String): String {
        var name = limit(sanitize(title), NAME_BYTES)
        if (name.isEmpty()) name = limit(sanitize(id), NAME_BYTES)
        return name.ifEmpty { "track" }
    }

    fun plainFileName(title: String, id: String): String = stem(title, id) + EXTENSION

    fun markedFileName(title: String, id: String): String =
        stem(title, id) + " [" + safeId(id) + "]" + EXTENSION

    /**
     * Where a new copy goes. [SaveDest.Keep] means a finished file is already
     * in place: the marked name, or the plain name when its video id matches
     * or the file has none.
     */
    fun saveDest(markedComplete: Boolean, plainComplete: Boolean, plainId: String, videoId: String): SaveDest {
        if (markedComplete) return SaveDest.Keep
        if (plainComplete) {
            if (plainId.isEmpty() || plainId == videoId) return SaveDest.Keep
            return SaveDest.Marked
        }
        return SaveDest.Plain
    }

    /** Which finished file to play, or null when the track still has to stream. */
    fun playFrom(markedComplete: Boolean, plainComplete: Boolean, plainId: String, videoId: String): PlayFrom? {
        if (markedComplete) return PlayFrom.Marked
        if (plainComplete && (plainId.isEmpty() || plainId == videoId)) return PlayFrom.Plain
        return null
    }

    /** A single path segment: no separators, no hidden-file dot, no trailing dot. */
    fun sanitize(raw: String?): String {
        val normalized = Normalizer.normalize(raw ?: "", Normalizer.Form.NFC)
        val out = StringBuilder(normalized.length)
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 32 || cp == 127 || cp == '/'.code || cp == '\\'.code || cp == ':'.code ||
                cp == '*'.code || cp == '?'.code || cp == '"'.code || cp == '<'.code ||
                cp == '>'.code || cp == '|'.code
            ) {
                out.append(' ')
            } else {
                out.appendCodePoint(cp)
            }
        }
        val collapsed = out.toString().replace(Regex("\\s+"), " ").trim()
        var start = 0
        while (start < collapsed.length && collapsed[start] == '.') start++
        var end = collapsed.length
        while (end > start && (collapsed[end - 1] == '.' || collapsed[end - 1] == ' ')) end--
        val name = collapsed.substring(start, end).trim()
        if (name.isEmpty() || name == "..") return ""
        return name
    }

    /** Cuts on a UTF-8 boundary so a long title still fits in one file name. */
    fun limit(value: String, maxBytes: Int = NAME_BYTES): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var end = maxBytes
        if ((bytes[end].toInt() and 0xC0) == 0x80) {
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        }
        var cut = String(bytes, 0, end, StandardCharsets.UTF_8)
        if (cut.endsWith("\uFFFD")) cut = cut.dropLast(1)
        cut = cut.replace(Regex("[. ]+$"), "")
        return cut.ifEmpty { "unknown" }
    }

    private fun safeId(id: String): String {
        val sb = StringBuilder()
        for (c in id) {
            if (sb.length >= 32) break
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_') sb.append(c)
        }
        return sb.toString().ifEmpty { "track" }
    }

    enum class SaveDest { Plain, Marked, Keep }
    enum class PlayFrom { Plain, Marked }
}
