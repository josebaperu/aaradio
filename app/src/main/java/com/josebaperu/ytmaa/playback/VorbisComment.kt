package com.josebaperu.ytmaa.playback

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Reads Vorbis comments from the start of an Opus file. The id tag is how a
 * later play tells a file this app wrote from one that was already in Music.
 */
internal object VorbisComment {
    private val MAGIC = "OpusTags".toByteArray(StandardCharsets.US_ASCII)

    fun youtubeId(input: InputStream): String = comments(input)["youtube_id"].orEmpty()

    fun comments(input: InputStream): Map<String, String> {
        val data = readPrefix(input, 64 * 1024)
        val start = indexOf(data, MAGIC)
        if (start < 0) return emptyMap()
        var i = start + MAGIC.size
        val vendorLen = le32(data, i)
        if (vendorLen < 0) return emptyMap()
        i += 4 + vendorLen
        val count = le32(data, i)
        if (count < 0 || count > 256) return emptyMap()
        i += 4
        val out = LinkedHashMap<String, String>()
        for (n in 0 until count) {
            val len = le32(data, i)
            if (len < 0 || len > data.size) break
            i += 4
            if (i + len > data.size) break
            val text = String(data, i, len, StandardCharsets.UTF_8)
            i += len
            val eq = text.indexOf('=')
            if (eq <= 0) continue
            val key = text.substring(0, eq).trim().lowercase(Locale.ROOT)
            if (key.isEmpty() || out.containsKey(key)) continue
            out[key] = text.substring(eq + 1).trim()
        }
        return out
    }

    private fun readPrefix(input: InputStream, max: Int): ByteArray {
        val out = ByteArray(max)
        var filled = 0
        while (filled < max) {
            val n = input.read(out, filled, max - filled)
            if (n < 0) break
            filled += n
        }
        return if (filled == max) out else out.copyOf(filled)
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || data.size < needle.size) return -1
        val last = data.size - needle.size
        for (i in 0..last) {
            var matches = true
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) {
                    matches = false
                    break
                }
            }
            if (matches) return i
        }
        return -1
    }

    private fun le32(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > data.size) return -1
        val value = (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
        if (value < 0 || value > 1_000_000) return -1
        return value
    }
}
