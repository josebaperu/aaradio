package com.josebaperu.ytmaa.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import com.josebaperu.ytmaa.R
import com.josebaperu.ytmaa.data.YtTrack
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Saves a streamed track into the phone's Music folder and finds it again later.
 * The playlist name is the directory and the track title is the file. Title,
 * artist, album and track number are written into the file; a `youtube_id` tag
 * ties that file back to the video. A file already in that place is played as
 * it is and is not replaced. A second track that would reuse a name is saved
 * beside it, with the video id in the file name.
 *
 * The bytes come from the same stream address playback uses. yt-dlp stays free
 * for the next track: its process map is not safe to share with a long download.
 */
object TrackStore {
    private const val TAG = "TrackStore"
    private const val DOWNLOAD_TIMEOUT_MS = 900_000L
    private const val FFMPEG_TIMEOUT_S = 180L
    private const val PROBE_TIMEOUT_S = 10L

    private val workers = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "track-store").apply { isDaemon = true }
    }
    private val locks = ConcurrentHashMap<String, Any>()
    /** Finished files already matched to a track during this run. */
    private val found = ConcurrentHashMap<String, Uri>()
    /** Tracks whose save is queued or running, so a second play does not start another. */
    private val pending = ConcurrentHashMap<String, Boolean>()
    /** This attempt plays the stream even when a file is present, after that file failed to decode. */
    private val skip = ConcurrentHashMap.newKeySet<String>()
    private val fromDisk = ConcurrentHashMap<String, Boolean>()

    private val _problems = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Save failures. Playback still uses the stream. */
    val problems: SharedFlow<String> = _problems.asSharedFlow()

    @Volatile private var app: Context? = null
    @Volatile private var folder: MusicFolder? = null
    @Volatile private var download: HttpURLConnection? = null

    fun attach(context: Context) {
        val application = context.applicationContext
        app = application
        if (folder == null) folder = MusicFolder(application)
    }

    /**
     * A finished copy of this track, or null when it still has to be streamed.
     * The plain name counts when its `youtube_id` tag is this video or the file
     * has no video id. A tag for a different video is not played by mistake.
     */
    fun existing(album: String, track: YtTrack): Uri? {
        val context = app ?: return null
        if (track.id.isBlank()) return null
        val music = folder ?: MusicFolder(context).also { folder = it }
        val key = key(album, track.id)
        found[key]?.let { uri ->
            if (MusicFolder.sizeOf(context, uri) >= TrackNames.MIN_BYTES) return uri
            found.remove(key, uri)
        }
        val directory = TrackNames.directoryName(album)
        val marked = complete(music, directory, TrackNames.markedFileName(track.title, track.id))
        val plain = complete(music, directory, TrackNames.plainFileName(track.title, track.id))
        val plainId = if (marked == null && plain != null) readId(context, plain.uri) else ""
        val chosen = when (TrackNames.playFrom(marked != null, plain != null, plainId, track.id)) {
            TrackNames.PlayFrom.Marked -> marked?.uri
            TrackNames.PlayFrom.Plain -> plain?.uri
            null -> null
        } ?: return null
        found[key] = chosen
        return chosen
    }

    /**
     * Downloads the track in the background when no finished copy exists.
     * [number] and [total] are the playlist position, written as the track tag.
     */
    fun request(album: String, track: YtTrack, number: Int, total: Int, watchUrl: String) {
        if (app == null || track.id.isBlank() || watchUrl.isBlank()) return
        val key = key(album, track.id)
        if (pending.putIfAbsent(key, true) != null) return
        try {
            workers.execute {
                try {
                    save(album, track, number, total, watchUrl)
                } finally {
                    pending.remove(key)
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(key)
        }
    }

    /**
     * Deletes a copy this app wrote when playback could not read it, so the next
     * attempt streams the track and saves a fresh one. A file that was already
     * in the folder, with no video id, is left where it is.
     */
    fun discard(album: String, track: YtTrack) {
        val context = app ?: return
        if (track.id.isBlank()) return
        val music = folder ?: return
        synchronized(lock(album, track)) {
            val key = key(album, track.id)
            val directory = TrackNames.directoryName(album)
            var uri = found.remove(key)
            if (uri == null) {
                val marked = complete(music, directory, TrackNames.markedFileName(track.title, track.id))
                val plain = complete(music, directory, TrackNames.plainFileName(track.title, track.id))
                uri = when {
                    marked != null -> marked.uri
                    plain != null && readId(context, plain.uri) == track.id -> plain.uri
                    else -> null
                }
            }
            if (uri == null || readId(context, uri) != track.id) return
            music.delete(uri)
        }
    }

    fun skipping(album: String, videoId: String): Boolean = key(album, videoId) in skip

    fun skip(album: String, videoId: String) {
        skip.add(key(album, videoId))
    }

    fun clearSkip() {
        skip.clear()
    }

    fun noteDisk(album: String, videoId: String, disk: Boolean) {
        fromDisk[key(album, videoId)] = disk
    }

    fun wasDisk(album: String, videoId: String): Boolean = fromDisk[key(album, videoId)] == true

    private fun save(album: String, track: YtTrack, number: Int, total: Int, watchUrl: String) {
        val context = app ?: return
        val music = folder ?: MusicFolder(context).also { folder = it }
        var dir: File? = null
        try {
            if (existing(album, track) != null) return
            val stream = YtDlp.resolveStream(watchUrl)
            dir = File(context.cacheDir, "ytm-" + UUID.randomUUID()).apply { mkdirs() }
            val audio = File(dir, "audio.bin")
            download(stream, audio)
            if (!looksLikeAudio(audio)) throw IOException("downloaded data was not audio")
            // Tag outside the lock. discard() runs on the player thread and must not wait on ffmpeg.
            val part = File(dir, "tagged.opus")
            val written = tag(context, audio, part, track, album, number, total)
            synchronized(lock(album, track)) {
                val fileName = claim(context, music, album, track) ?: return
                val directory = TrackNames.directoryName(album)
                for (entry in music.list(directory, fileName)) {
                    if (entry.pending || entry.size < TrackNames.MIN_BYTES) music.delete(entry.uri)
                }
                val uri = music.insert(
                    directory,
                    fileName,
                    part,
                    MusicFolder.Tags(
                        title = track.title.ifBlank { track.id },
                        artist = track.artist,
                        album = album,
                        track = number,
                        durationMs = (written.durationSec * 1000).toLong(),
                    ),
                )
                found[key(album, track.id)] = uri
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            val title = track.title.ifBlank { track.id }
            val message = context.getString(R.string.save_failed, title, brief(e))
            Log.w(TAG, message, e)
            _problems.tryEmit(message)
        } finally {
            dir?.deleteRecursively()
        }
    }

    /** Copies the audio into an opus file, or transcodes when the stream is not already opus. */
    private fun tag(
        context: Context,
        source: File,
        part: File,
        track: YtTrack,
        album: String,
        number: Int,
        total: Int,
    ): Inspection {
        FFmpeg.getInstance().init(context)
        var inspection = remux(context, source, part, track, album, number, total, copy = true)
        if (inspection == null || !inspection.matches(track.id)) {
            inspection = remux(context, source, part, track, album, number, total, copy = false)
        }
        if (inspection == null || !inspection.matches(track.id)) {
            throw IOException("could not write " + TrackNames.plainFileName(track.title, track.id))
        }
        return inspection
    }

    private fun remux(
        context: Context,
        source: File,
        part: File,
        track: YtTrack,
        album: String,
        number: Int,
        total: Int,
        copy: Boolean,
    ): Inspection? {
        if (part.exists()) part.delete()
        val bin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!bin.isFile) throw IOException("ffmpeg is not available")
        val cmd = ArrayList<String>()
        cmd += bin.absolutePath
        cmd += listOf("-y", "-hide_banner", "-nostdin", "-loglevel", "error", "-i", source.absolutePath, "-map", "0:a:0", "-vn")
        if (copy) cmd += listOf("-c", "copy") else cmd += listOf("-c:a", "libopus", "-b:a", "160k")
        cmd += listOf("-map_metadata", "-1")
        meta(cmd, "title", track.title.ifBlank { track.id })
        meta(cmd, "artist", track.artist)
        meta(cmd, "album", album)
        if (number > 0) {
            val value = if (total >= number) "$number/$total" else number.toString()
            meta(cmd, "track", value)
            meta(cmd, "tracknumber", value)
        }
        meta(cmd, "youtube_id", track.id)
        cmd += listOf("-f", "ogg", part.absolutePath)
        return try {
            run(context, cmd, FFMPEG_TIMEOUT_S, capture = false)
            inspect(context, part)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: IOException) {
            if (part.exists()) part.delete()
            if (copy) null else throw e
        }
    }

    private fun inspect(context: Context, file: File): Inspection {
        if (file.length() < TrackNames.MIN_BYTES) return Inspection(0.0, "")
        val id = file.inputStream().use { VorbisComment.youtubeId(it) }
        return Inspection(probeDuration(context, file), id)
    }

    private fun probeDuration(context: Context, file: File): Double {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libffprobe.so")
        if (!bin.isFile) throw IOException("ffprobe is not available")
        val out = run(
            context,
            listOf(
                bin.absolutePath, "-v", "quiet",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1",
                file.absolutePath,
            ),
            PROBE_TIMEOUT_S,
            capture = true,
        )
        var duration = 0.0
        for (line in out.split('\n')) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            if (key == "duration" && value.isNotEmpty() && value != "N/A" && duration <= 0) {
                duration = value.toDoubleOrNull() ?: 0.0
            }
        }
        return duration
    }

    private fun download(url: String, dest: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", YtDlp.USER_AGENT)
            setRequestProperty("Referer", YtDlp.REFERER)
            setRequestProperty("Accept-Encoding", "identity")
        }
        download = connection
        val deadline = System.nanoTime() + DOWNLOAD_TIMEOUT_MS * 1_000_000L
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("stream returned $code")
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        if (System.nanoTime() > deadline) throw IOException("download timed out")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            if (dest.length() < TrackNames.MIN_BYTES) throw IOException("downloaded audio was empty")
        } finally {
            connection.disconnect()
            if (download === connection) download = null
        }
    }

    private fun looksLikeAudio(file: File): Boolean {
        val head = ByteArray(16)
        val n = file.inputStream().use { it.read(head) }
        if (n < 4) return false
        if (head.startsWith("OggS")) return true
        if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()) return true
        if (n >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()) return true
        if (head.startsWith("ID3") || head.startsWith("RIFF")) return true
        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF
        return b0 == 0xFF && (b1 and 0xE0) == 0xE0
    }

    private fun ByteArray.startsWith(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        if (size < bytes.size) return false
        for (i in bytes.indices) if (this[i] != bytes[i]) return false
        return true
    }

    private fun run(context: Context, command: List<String>, timeoutSec: Long, capture: Boolean): String {
        val process = ProcessBuilder(command).apply {
            environment()["LD_LIBRARY_PATH"] = libraryPath(context)
            environment()["TMPDIR"] = context.cacheDir.absolutePath
            directory(context.cacheDir)
        }.start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outPump = pump(process.inputStream, if (capture) stdout else ByteArrayOutputStream())
        val errPump = pump(process.errorStream, stderr)
        try {
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException(File(command.first()).name + " timed out")
            }
            outPump.join(2000)
            errPump.join(2000)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            throw e
        }
        if (process.exitValue() != 0) {
            throw IOException(lastLine(stderr.toString(Charsets.UTF_8), process.exitValue()))
        }
        return stdout.toString(Charsets.UTF_8)
    }

    /** Same library path the bundled yt-dlp uses when it launches ffmpeg. */
    private fun libraryPath(context: Context): String {
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        return listOf("python", "ffmpeg", "aria2c").joinToString(":") { name ->
            File(packages, "$name/usr/lib").absolutePath
        }
    }

    private fun pump(input: InputStream, into: ByteArrayOutputStream): Thread {
        val thread = Thread({
            try {
                input.use { stream ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        synchronized(into) { into.write(buf, 0, n) }
                    }
                }
            } catch (_: IOException) {
                // The process went away.
            }
        }, "track-store-pipe")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun meta(cmd: MutableList<String>, key: String, value: String) {
        val cleaned = value.replace('\n', ' ').replace('\r', ' ').trim()
        if (cleaned.isEmpty()) return
        cmd += "-metadata"
        cmd += "$key=$cleaned"
    }

    /** The file name to write, or null when a finished copy is already in place. */
    private fun claim(context: Context, music: MusicFolder, album: String, track: YtTrack): String? {
        val directory = TrackNames.directoryName(album)
        val markedName = TrackNames.markedFileName(track.title, track.id)
        val plainName = TrackNames.plainFileName(track.title, track.id)
        val marked = complete(music, directory, markedName)
        val plain = complete(music, directory, plainName)
        val plainId = if (marked == null && plain != null) readId(context, plain.uri) else ""
        return when (TrackNames.saveDest(marked != null, plain != null, plainId, track.id)) {
            TrackNames.SaveDest.Keep -> null
            TrackNames.SaveDest.Marked -> markedName
            TrackNames.SaveDest.Plain -> plainName
        }
    }

    private fun complete(music: MusicFolder, directory: String, fileName: String): MusicFolder.Entry? =
        music.list(directory, fileName).firstOrNull { !it.pending && it.size >= TrackNames.MIN_BYTES }

    private fun readId(context: Context, uri: Uri): String = runCatching {
        open(context, uri)?.use { VorbisComment.youtubeId(it) }.orEmpty()
    }.getOrDefault("")

    private fun open(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") uri.path?.let { path -> File(path).takeIf { it.isFile }?.inputStream() }
        else context.contentResolver.openInputStream(uri)

    private fun lock(album: String, track: YtTrack): Any =
        locks.computeIfAbsent(TrackNames.directoryName(album) + "\u0000" + TrackNames.stem(track.title, track.id)) { Any() }

    private fun key(album: String, videoId: String): String =
        TrackNames.directoryName(album) + "\u0000" + videoId

    private fun brief(e: Exception): String {
        val message = (e.message ?: e.javaClass.simpleName).replace(Regex("\\s+"), " ").trim()
        return message.take(140).ifBlank { "could not save" }
    }

    private fun lastLine(stderr: String, exit: Int): String {
        val last = stderr.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        return if (last.isNullOrEmpty()) "ffmpeg exited with $exit" else last.take(180)
    }

    private data class Inspection(val durationSec: Double, val youtubeId: String) {
        fun matches(id: String): Boolean = durationSec > 0 && youtubeId == id
    }
}
