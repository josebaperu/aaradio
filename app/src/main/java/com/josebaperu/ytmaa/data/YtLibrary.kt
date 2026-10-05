package com.josebaperu.ytmaa.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.josebaperu.ytmaa.R
import com.josebaperu.ytmaa.playback.YtDlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Phone library of YouTube Music playlists. The last imported file decides which
 * playlists exist. Track lists that were already resolved for the same url are kept;
 * new urls are listed with yt-dlp and saved, and urls the file dropped are deleted.
 * Opening the app only reads this file. It does not list playlists again.
 */
object YtLibrary {
    data class State(
        val playlists: List<YtPlaylist> = emptyList(),
        val preparing: Boolean = false,
        val resolvingUrl: String? = null,
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
    )

    private const val PREFS = "ytm-playback"
    private const val KEY_LAST = "last_playlist"
    private const val KEY_FAVORITES = "favorites"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicInteger()
    private val publishLock = Any()
    private var job: Job? = null
    private lateinit var store: File
    private lateinit var archiveStore: File
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var archived: List<YtPlaylist> = emptyList()
    /** True while an import is listing playlists, so a tap does not cancel that import. */
    @Volatile private var resolvingMany = false
    /** Url to start once its track list is saved. */
    @Volatile private var playAfterUrl: String? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _readyToPlay = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** A playlist url whose tracks just finished listing because the user tapped the row. */
    val readyToPlay: SharedFlow<String> = _readyToPlay.asSharedFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    /** Playlist urls marked with the heart. */
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        YtDlp.attach(app)
        store = File(app.filesDir, "ytm-library.json")
        archiveStore = File(app.filesDir, "ytm-removed.json")
        val saved = if (store.isFile) {
            runCatching { YtPlaylistFile.decodeLibrary(store.readText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val loadedArchive = if (archiveStore.isFile) {
            runCatching { YtPlaylistFile.decodeLibrary(archiveStore.readText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val savedUrls = saved.map { it.url }.toSet()
        archived = loadedArchive.filter { it.resolved && it.url !in savedUrls }
        if (archived.size != loadedArchive.size) writeArchive()
        _state.value = State(playlists = saved)
        val urls = saved.map { it.url }.toSet()
        val stored = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet().orEmpty()
        val kept = stored.intersect(urls)
        _favorites.value = kept
        if (kept != stored) prefs.edit { putStringSet(KEY_FAVORITES, HashSet(kept)) }
    }

    fun isFavorite(url: String) = url in _favorites.value

    fun favoritePlaylists(): List<YtPlaylist> =
        YtPlaylistFile.sortedByTitle(_state.value.playlists.filter { it.url in _favorites.value })

    fun toggleFavorite(url: String) {
        if (!::prefs.isInitialized) return
        if (_state.value.playlists.none { it.url == url }) return
        val updated = _favorites.value.let { if (url in it) it - url else it + url }
        _favorites.value = updated
        prefs.edit { putStringSet(KEY_FAVORITES, HashSet(updated)) }
    }

    /**
     * Replaces the library with [text], the JSON array from a chosen file.
     * Returns false when [text] is not a playlist list. The caller deletes the file only after true.
     */
    fun import(text: String): Boolean {
        val parsed = try {
            YtPlaylistFile.parseExport(text)
        } catch (_: IllegalArgumentException) {
            _messages.tryEmit(app.getString(R.string.playlist_not_list))
            return false
        }
        resolvingMany = true
        launch { gen ->
            try {
                runImport(parsed, gen)
            } finally {
                if (generation.get() == gen) resolvingMany = false
            }
        }
        return true
    }

    /** Drops playlists from the phone. Resolved ones stay archived so a later file can restore their tracks. */
    fun remove(urls: Set<String>) {
        if (urls.isEmpty() || !::store.isInitialized) return
        synchronized(publishLock) {
            val current = _state.value.playlists
            val gone = current.filter { it.url in urls }
            if (gone.isEmpty()) return
            val next = current.filter { it.url !in urls }
            archiveRemoved(gone)
            _state.value = _state.value.copy(
                playlists = next,
                resolvingUrl = _state.value.resolvingUrl?.takeIf { it !in urls },
            )
            runCatching { writeStore(next) }
            retainFavorites(next)
            if (playAfterUrl in urls) playAfterUrl = null
        }
    }

    fun byId(id: String): YtPlaylist? = _state.value.playlists.find { it.id == id }

    fun playable(): List<YtPlaylist> = _state.value.playlists.filter { it.resolved && it.tracks.isNotEmpty() }

    /** Playlist to resume from Android Auto or a voice command, if it still has tracks. */
    fun lastPlayed(): YtPlaylist? {
        if (!::prefs.isInitialized) return null
        val id = prefs.getString(KEY_LAST, null) ?: return null
        return byId(id)?.takeIf { it.resolved && it.tracks.isNotEmpty() }
    }

    fun rememberPlaying(playlistId: String) {
        if (!::prefs.isInitialized) return
        prefs.edit { putString(KEY_LAST, playlistId) }
    }

    /** Lists [url] if it has no tracks yet, then asks the UI to play it. An import already running is left alone. */
    fun resolveAndPlay(url: String) {
        val playlist = _state.value.playlists.find { it.url == url } ?: return
        if (playlist.resolved) return
        playAfterUrl = url
        if (resolvingMany || _state.value.resolvingUrl == url) return
        launch { gen -> resolveOne(url, gen) }
    }

    private fun launch(block: suspend (Int) -> Unit) {
        val gen = generation.incrementAndGet()
        YtDlp.cancelActive()
        job?.cancel()
        job = scope.launch {
            block(gen)
        }
    }

    private suspend fun runImport(parsed: List<FilePlaylist>, gen: Int) {
        if (!still(gen)) return
        val merged = synchronized(publishLock) {
            if (generation.get() != gen) return
            val merged = YtPlaylistFile.merge(parsed, _state.value.playlists, archived)
            if (!publish(gen, State(playlists = merged))) return
            forgetArchived(merged.map { it.url }.toSet())
            merged
        }
        resolvePending(gen, merged.filter { !it.resolved })
    }

    private suspend fun resolveOne(url: String, gen: Int) {
        val playlist = _state.value.playlists.find { it.url == url } ?: return
        val cleared = playlist.copy(resolved = false, error = null)
        val next = _state.value.playlists.map { if (it.url == url) cleared else it }
        if (!publish(gen, _state.value.copy(playlists = next))) return
        resolvePending(gen, listOf(cleared))
    }

    private suspend fun resolvePending(gen: Int, pending: List<YtPlaylist>) {
        if (pending.isEmpty() || !still(gen)) return
        if (!publish(gen, _state.value.copy(preparing = true, progressDone = 0, progressTotal = pending.size, resolvingUrl = null))) return
        try {
            YtDlp.ensureInit()
        } catch (e: Exception) {
            if (!still(gen)) return
            val message = e.message?.ifBlank { null } ?: app.getString(R.string.playlist_resolve_failed)
            val failed = _state.value.playlists.map { playlist ->
                if (pending.any { it.url == playlist.url }) playlist.copy(error = message) else playlist
            }
            publish(gen, State(playlists = failed))
            return
        }
        if (!still(gen)) return
        publish(gen, _state.value.copy(preparing = false, progressTotal = pending.size, progressDone = 0))
        pending.forEachIndexed { index, playlist ->
            if (!still(gen)) return
            publish(gen, _state.value.copy(resolvingUrl = playlist.url, progressDone = index, progressTotal = pending.size))
            val updated = try {
                val tracks = YtDlp.listPlaylist(playlist.url)
                playlist.copy(resolved = true, tracks = tracks, error = null)
            } catch (e: Exception) {
                if (!still(gen)) return
                playlist.copy(
                    resolved = false,
                    tracks = emptyList(),
                    error = e.message?.ifBlank { null } ?: app.getString(R.string.playlist_resolve_failed),
                )
            }
            val playlists = _state.value.playlists.map { if (it.url == playlist.url) updated else it }
            if (!publish(gen, _state.value.copy(playlists = playlists, progressDone = index + 1))) return
            maybePlay(updated)
        }
        if (!still(gen)) return
        publish(gen, _state.value.copy(preparing = false, resolvingUrl = null, progressDone = 0, progressTotal = 0))
    }

    private fun maybePlay(updated: YtPlaylist) {
        if (updated.url != playAfterUrl) return
        if (_state.value.playlists.none { it.url == updated.url }) {
            playAfterUrl = null
            return
        }
        playAfterUrl = null
        if (updated.resolved && updated.tracks.isNotEmpty()) _readyToPlay.tryEmit(updated.url)
        else if (updated.resolved) _messages.tryEmit(app.getString(R.string.playlist_empty))
    }

    private fun archiveRemoved(gone: List<YtPlaylist>) {
        val urls = gone.map { it.url }.toSet()
        archived = archived.filter { it.url !in urls } + gone.filter { it.resolved }
        writeArchive()
    }

    private fun forgetArchived(urls: Set<String>) {
        val next = archived.filter { it.url !in urls }
        if (next.size == archived.size) return
        archived = next
        writeArchive()
    }

    private suspend fun still(gen: Int): Boolean =
        generation.get() == gen && coroutineContext[Job]?.isActive != false

    /** Writes [next] only when [gen] is still the import the user is waiting on. */
    private fun publish(gen: Int, next: State): Boolean {
        synchronized(publishLock) {
            if (generation.get() != gen) return false
            _state.value = next
            runCatching { writeStore(next.playlists) }
            retainFavorites(next.playlists)
            return true
        }
    }

    private fun retainFavorites(playlists: List<YtPlaylist>) {
        if (!::prefs.isInitialized) return
        val kept = _favorites.value.intersect(playlists.map { it.url }.toSet())
        if (kept == _favorites.value) return
        _favorites.value = kept
        prefs.edit { putStringSet(KEY_FAVORITES, HashSet(kept)) }
    }

    private fun writeStore(playlists: List<YtPlaylist>) = writeJson(store, playlists)

    private fun writeArchive() {
        if (!::archiveStore.isInitialized) return
        writeJson(archiveStore, archived)
    }

    private fun writeJson(file: File, playlists: List<YtPlaylist>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(YtPlaylistFile.encodeLibrary(playlists))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
