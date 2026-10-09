package com.josebaperu.ytmaa.playback

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/**
 * The phone's Music folder, the same place a desktop player would use ~/Music.
 * From Android 10 the public folder is written through MediaStore, which does
 * not need a storage permission for files this app creates. Older releases
 * write the directory tree directly when storage permission was granted.
 */
internal class MusicFolder(private val context: Context) {
    data class Entry(val uri: Uri, val size: Long, val pending: Boolean)
    data class Tags(
        val title: String,
        val artist: String,
        val album: String,
        val track: Int,
        val durationMs: Long,
    )

    fun list(directory: String, fileName: String): List<Entry> {
        if (!oneSegment(directory) || !oneSegment(fileName)) return emptyList()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listStore(directory, fileName) else listFile(directory, fileName)
        } catch (e: SecurityException) {
            Log.w(TAG, "Music folder is not readable", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Music folder query failed", e)
            emptyList()
        }
    }

    fun insert(directory: String, fileName: String, source: File, tags: Tags): Uri {
        if (!oneSegment(directory) || !oneSegment(fileName)) throw IOException("invalid music file name")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) insertStore(directory, fileName, source, tags)
        else insertFile(directory, fileName, source)
    }

    fun delete(uri: Uri) {
        if (uri.scheme == "file") {
            val path = uri.path ?: return
            val file = File(path)
            if (!file.isFile || !allowed(file)) return
            file.delete()
            return
        }
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listStore(directory: String, fileName: String): List<Entry> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,
        )
        val selection = "(${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?) AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val args = arrayOf(relative(directory), "Music/$directory", fileName)
        val cursor = context.contentResolver.query(collection(), projection, selection, args, null) ?: return emptyList()
        val out = ArrayList<Entry>()
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val pendingCol = it.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            while (it.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection(), it.getLong(idCol))
                val pending = pendingCol >= 0 && it.getInt(pendingCol) == 1
                out += Entry(uri, sizeOf(context, uri), pending)
            }
        }
        return out
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertStore(directory: String, fileName: String, source: File, tags: Tags): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/ogg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative(directory))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.TITLE, tags.title)
            if (tags.artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, tags.artist)
            if (tags.album.isNotBlank()) put(MediaStore.Audio.Media.ALBUM, tags.album)
            if (tags.track > 0) put(MediaStore.Audio.Media.TRACK, tags.track)
            if (tags.durationMs > 0) put(MediaStore.Audio.Media.DURATION, tags.durationMs)
        }
        val uri = context.contentResolver.insert(collection(), values)
            ?: throw IOException("Music folder refused the file")
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("could not write $fileName")
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.SIZE, source.length())
            }
            if (context.contentResolver.update(uri, published, null, null) != 1) {
                throw IOException("could not publish $fileName")
            }
            if (sizeOf(context, uri) < TrackNames.MIN_BYTES) throw IOException("could not write $fileName")
            val stored = displayName(uri)
            if (stored != null && stored != fileName) {
                throw IOException("a file named $fileName is already in Music")
            }
            return uri
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            if (e is IOException) throw e
            throw IOException(e.message ?: "could not write $fileName", e)
        }
    }

    private fun displayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.getString(0)
        }
    }

    private fun listFile(directory: String, fileName: String): List<Entry> {
        val file = File(File(root(), directory), fileName)
        if (!file.isFile || !allowed(file)) return emptyList()
        return listOf(Entry(Uri.fromFile(file), file.length(), pending = false))
    }

    private fun insertFile(directory: String, fileName: String, source: File): Uri {
        val dest = File(File(root(), directory), fileName)
        val parent = dest.parentFile ?: throw IOException("could not create $directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("could not create $directory")
        if (!allowed(parent) && parent.canonicalFile != root().canonicalFile) {
            throw IOException("playlist name escapes the Music folder")
        }
        val part = File(parent, "$fileName.part")
        if (part.exists()) part.delete()
        source.inputStream().use { input -> part.outputStream().use { output -> input.copyTo(output) } }
        if (dest.exists() && dest.length() < TrackNames.MIN_BYTES) dest.delete()
        if (dest.exists()) {
            part.delete()
            throw IOException("a file named $fileName is already in Music")
        }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = false)
            part.delete()
        }
        if (dest.length() < TrackNames.MIN_BYTES) {
            dest.delete()
            throw IOException("could not write $fileName")
        }
        if (root() == publicMusic()) {
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("audio/ogg"), null)
        }
        return Uri.fromFile(dest)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun collection(): Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun relative(directory: String) = "Music/$directory/"

    /** Public Music when this process may write it; otherwise the app's own music directory. */
    private fun root(): File {
        val pub = publicMusic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return pub
        val granted = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return pub
        return context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "Music")
    }

    private fun publicMusic(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

    private fun allowed(file: File): Boolean {
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val bases = listOfNotNull(
            runCatching { publicMusic().canonicalFile }.getOrNull(),
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { runCatching { it.canonicalFile }.getOrNull() },
            runCatching { File(context.filesDir, "Music").canonicalFile }.getOrNull(),
        )
        return bases.any { base -> target == base || target.path.startsWith(base.path + File.separator) }
    }

    private fun oneSegment(name: String): Boolean =
        name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".."

    companion object {
        private const val TAG = "MusicFolder"

        fun sizeOf(context: Context, uri: Uri): Long {
            if (uri.scheme == "file") {
                val path = uri.path ?: return -1
                val file = File(path)
                return if (file.isFile) file.length() else -1
            }
            return runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: -1
        }
    }
}
