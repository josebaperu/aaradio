package com.josebaperu.ytmaa.data

import java.text.Collator
import java.util.Locale

/**
 * Reads a playlist export (a JSON array of objects) and the saved library.
 * The file decides which urls exist. A resolved playlist for the same url keeps
 * its tracks, including one the user removed and a later file brought back.
 */
object YtPlaylistFile {

    fun parseExport(text: String): List<FilePlaylist> {
        val root = Json.read(text)
        if (root !is List<*>) throw IllegalArgumentException("Not a JSON array")
        val out = ArrayList<FilePlaylist>(root.size)
        for (item in root) {
            val obj = item as? Map<*, *> ?: continue
            val url = obj.str("url")
            if (!url.startsWith("http")) continue
            val title = obj.str("title").ifBlank { url }
            val id = obj.str("id").ifBlank { url }
            out += FilePlaylist(id, title, url, obj.long("updatedDate"))
        }
        return out
    }

    /** Track lines from `yt-dlp --flat-playlist --dump-json`, either one object per line or one array. */
    fun parseFlatListing(text: String): List<YtTrack> {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val root = try {
                Json.read(trimmed)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (root is List<*>) return root.mapNotNull { (it as? Map<*, *>)?.let(::trackFrom) }
        }
        return trimmed.lineSequence().mapNotNull(::parseFlatTrack).toList()
    }

    /** One `--flat-playlist --dump-json` line. Null when the video is missing or not playable. */
    fun parseFlatTrack(line: String): YtTrack? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed == "NA" || trimmed == "null") return null
        val obj = try {
            Json.read(trimmed) as? Map<*, *>
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return trackFrom(obj)
    }

    private fun trackFrom(obj: Map<*, *>): YtTrack? {
        val id = obj.str("id")
        if (id.isBlank() || id == "NA") return null
        val title = obj.str("title")
        if (title == "[Deleted video]" || title == "[Private video]") return null
        val url = obj.str("url").ifBlank { obj.str("webpage_url") }
        val link = if (url.startsWith("http")) url else "https://music.youtube.com/watch?v=$id"
        return YtTrack(id, link, title.ifBlank { id }, artistOf(obj), obj.dbl("duration"))
    }

    /**
     * [file] is the source of truth. A url that is already resolved keeps its tracks,
     * whether it is still in [saved] or only in [archived] after a manual delete.
     * A url that is new is left unresolved. Urls missing from [file] are not returned.
     */
    fun merge(
        file: List<FilePlaylist>,
        saved: List<YtPlaylist>,
        archived: List<YtPlaylist> = emptyList(),
    ): List<YtPlaylist> {
        val known = LinkedHashMap<String, YtPlaylist>()
        for (playlist in archived) if (playlist.resolved) known[playlist.url] = playlist
        for (playlist in saved) if (playlist.resolved) known[playlist.url] = playlist
        val seen = HashSet<String>()
        val out = ArrayList<YtPlaylist>(file.size)
        for (entry in file) {
            if (!seen.add(entry.url)) continue
            val existing = known[entry.url]
            out += if (existing != null) {
                existing.copy(id = entry.id, title = entry.title, updatedDate = entry.updatedDate, error = null)
            } else {
                YtPlaylist(
                    id = entry.id,
                    title = entry.title,
                    url = entry.url,
                    updatedDate = entry.updatedDate,
                    resolved = false,
                    tracks = emptyList(),
                    error = null,
                )
            }
        }
        return out
    }

    /**
     * Albums whose title contains [query], ignoring case, still in A–Z order.
     * A blank query matches nothing. Track titles are not part of the match.
     */
    fun matchingTitles(playlists: List<YtPlaylist>, query: String): List<YtPlaylist> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return sortedByTitle(playlists).filter { q in it.title.lowercase() }
    }

    /** A–Z by title. Accents share a letter, and the same title stays in url order. */
    fun sortedByTitle(playlists: List<YtPlaylist>): List<YtPlaylist> {
        val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }
        return playlists.sortedWith { a, b ->
            val byTitle = collator.compare(a.title.trim(), b.title.trim())
            if (byTitle != 0) byTitle else a.url.compareTo(b.url)
        }
    }

    fun encodeLibrary(playlists: List<YtPlaylist>): String = buildString {
        append("{\"playlists\":[")
        playlists.forEachIndexed { i, playlist ->
            if (i > 0) append(',')
            append("{\"id\":").append(quote(playlist.id))
            append(",\"title\":").append(quote(playlist.title))
            append(",\"url\":").append(quote(playlist.url))
            append(",\"updatedDate\":").append(playlist.updatedDate)
            append(",\"resolved\":").append(if (playlist.resolved) "true" else "false")
            append(",\"error\":").append(quote(playlist.error.orEmpty()))
            append(",\"tracks\":[")
            playlist.tracks.forEachIndexed { j, track ->
                if (j > 0) append(',')
                append("{\"id\":").append(quote(track.id))
                append(",\"url\":").append(quote(track.url))
                append(",\"title\":").append(quote(track.title))
                append(",\"artist\":").append(quote(track.artist))
                append(",\"duration\":").append(number(track.durationSec))
                append('}')
            }
            append("]}")
        }
        append("]}")
    }

    fun decodeLibrary(text: String): List<YtPlaylist> {
        val root = Json.read(text) as? Map<*, *> ?: return emptyList()
        val items = root["playlists"] as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val url = obj.str("url")
            if (url.isBlank()) return@mapNotNull null
            val tracks = (obj["tracks"] as? List<*>).orEmpty().mapNotNull { it.toTrack() }
            YtPlaylist(
                id = obj.str("id").ifBlank { url },
                title = obj.str("title").ifBlank { url },
                url = url,
                updatedDate = obj.long("updatedDate"),
                resolved = obj["resolved"] == true,
                tracks = tracks,
                error = obj.str("error").ifBlank { null },
            )
        }
    }

    private fun Any?.toTrack(): YtTrack? {
        val obj = this as? Map<*, *> ?: return null
        val id = obj.str("id")
        if (id.isBlank()) return null
        val url = obj.str("url").ifBlank { "https://music.youtube.com/watch?v=$id" }
        return YtTrack(id, url, obj.str("title"), obj.str("artist"), obj.dbl("duration"))
    }

    private fun artistOf(obj: Map<*, *>): String {
        val fromList = (obj["artists"] as? List<*>)?.firstNotNullOfOrNull { item ->
            when (item) {
                is String -> item.takeIf { it.isNotBlank() }
                is Map<*, *> -> (item["name"] as? String)?.takeIf { it.isNotBlank() }
                else -> null
            }
        }
        val raw = fromList
            ?: obj.str("artist").ifBlank { null }
            ?: obj.str("channel").ifBlank { null }
            ?: obj.str("uploader")
        return raw.removeSuffix(" - Topic")
    }

    private fun Map<*, *>.str(key: String): String = this[key] as? String ?: ""

    private fun Map<*, *>.long(key: String): Long = when (val v = this[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        else -> 0L
    }

    private fun Map<*, *>.dbl(key: String): Double = when (val v = this[key]) {
        is Double -> v
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        else -> 0.0
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun number(value: Double): String {
        if (!value.isFinite()) return "0"
        val asLong = value.toLong()
        if (asLong.toDouble() == value) return asLong.toString()
        return value.toString()
    }
}

/** The JSON subset a playlist export and the saved library use. */
private object Json {
    fun read(text: String): Any? {
        val reader = Reader(text.trim().removePrefix("\uFEFF"))
        val value = reader.readValue()
        reader.skip()
        if (!reader.eof()) throw IllegalArgumentException("Not a JSON array")
        return value
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun eof(): Boolean = i >= s.length

        fun readValue(): Any? {
            skip()
            if (eof()) fail()
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> readNumber()
            }
        }

        fun skip() {
            while (!eof() && s[i].isWhitespace()) i++
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skip()
            if (peek('}')) return map
            while (true) {
                skip()
                if (eof() || s[i] != '"') fail()
                val key = readString()
                skip()
                expect(':')
                map[key] = readValue()
                skip()
                when {
                    peek(',') -> continue
                    peek('}') -> return map
                    else -> fail()
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skip()
            if (peek(']')) return list
            while (true) {
                list += readValue()
                skip()
                when {
                    peek(',') -> continue
                    peek(']') -> return list
                    else -> fail()
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (!eof()) {
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (eof()) fail()
                        out.append(
                            when (val e = s[i++]) {
                                '"', '\\', '/' -> e
                                'b' -> '\b'
                                'f' -> '\u000c'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    if (i + 4 > s.length) fail()
                                    val hex = s.substring(i, i + 4)
                                    i += 4
                                    hex.toIntOrNull(16)?.toChar() ?: fail()
                                }
                                else -> fail()
                            },
                        )
                    }
                    else -> if (c.code < 0x20) fail() else out.append(c)
                }
            }
            fail()
        }

        private fun readNumber(): Number {
            val start = i
            if (peek('-')) { /* consumed */ }
            if (eof() || !s[i].isDigit()) fail()
            while (!eof() && s[i].isDigit()) i++
            var frac = false
            if (!eof() && s[i] == '.') {
                frac = true
                i++
                if (eof() || !s[i].isDigit()) fail()
                while (!eof() && s[i].isDigit()) i++
            }
            if (!eof() && (s[i] == 'e' || s[i] == 'E')) {
                frac = true
                i++
                if (!eof() && (s[i] == '+' || s[i] == '-')) i++
                if (eof() || !s[i].isDigit()) fail()
                while (!eof() && s[i].isDigit()) i++
            }
            val raw = s.substring(start, i)
            return if (!frac) raw.toLong() else raw.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return value
        }

        private fun expect(c: Char) {
            if (eof() || s[i] != c) fail()
            i++
        }

        /** True when the next character is [c], and then consumes it. */
        private fun peek(c: Char): Boolean {
            if (eof() || s[i] != c) return false
            i++
            return true
        }

        private fun fail(): Nothing = throw IllegalArgumentException("Not a JSON array")
    }
}
