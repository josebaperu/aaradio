package com.josebaperu.aautoradio.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Collator
import java.util.Locale

/**
 * Process-wide station catalog, parsed from assets/stations.tsv
 * (`name<TAB>url<TAB>genre<TAB>favorite(0/1)`, `#` starts a comment).
 * Favorites are seeded from the file on first run, then persisted in preferences.
 */
object StationRepository {
    private const val PREFS = "stations"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_LAST_PLAYED = "last_played"
    private const val KEY_SORT_DESC = "sort_descending"

    private lateinit var prefs: SharedPreferences

    lateinit var stations: List<Station>
        private set
    private lateinit var byId: Map<String, Station>

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    /** Alphabetical direction for every station list (phone and Android Auto). Defaults to A→Z. */
    private val _sortDescending = MutableStateFlow(false)
    val sortDescending: StateFlow<Boolean> = _sortDescending.asStateFlow()

    // Case- and accent-insensitive, so "Émile" sorts with the E's.
    private val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        stations = context.assets.open("stations.tsv").bufferedReader().use { parse(it.readText()) }
        byId = stations.associateBy { it.id }
        _favorites.value = prefs.getStringSet(KEY_FAVORITES, null)?.toSet()
            ?: stations.filter { it.defaultFavorite }.map { it.id }.toSet()
        _sortDescending.value = prefs.getBoolean(KEY_SORT_DESC, false)
    }

    fun parse(text: String): List<Station> {
        val usedIds = mutableSetOf<String>()
        return text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .mapNotNull { line ->
                val cols = line.split('\t')
                if (cols.size < 2 || cols[1].isBlank()) return@mapNotNull null
                val name = cols[0].trim()
                var id = slug(name)
                var n = 2
                while (!usedIds.add(id)) id = "${slug(name)}-${n++}"
                Station(
                    id = id,
                    name = name,
                    url = cols[1].trim(),
                    genre = cols.getOrNull(2)?.trim().orEmpty(),
                    defaultFavorite = cols.getOrNull(3)?.trim() == "1",
                )
            }
            .toList()
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "station" }

    operator fun get(id: String?): Station? = id?.let { byId[it] }

    fun isFavorite(id: String) = id in _favorites.value

    fun sorted(list: List<Station>, descending: Boolean = _sortDescending.value): List<Station> {
        val ascending = list.sortedWith { a, b -> collator.compare(a.name, b.name) }
        return if (descending) ascending.asReversed() else ascending
    }

    fun sortedStations(): List<Station> = sorted(stations)

    fun favoriteStations(): List<Station> = _favorites.value.let { fav -> sorted(stations.filter { it.id in fav }) }

    fun genres(): List<String> = stations.map { it.genre }.filter { it.isNotEmpty() }.distinct().sorted()

    fun byGenre(genre: String): List<Station> = sorted(stations.filter { it.genre == genre })

    fun search(query: String): List<Station> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return stations
            .filter { q in it.name.lowercase() || q in it.genre.lowercase() }
            .sortedBy { if (it.name.lowercase().startsWith(q)) 0 else 1 }
    }

    fun toggleFavorite(id: String) {
        val updated = _favorites.value.let { if (id in it) it - id else it + id }
        _favorites.value = updated
        prefs.edit { putStringSet(KEY_FAVORITES, updated) }
    }

    fun setSortDescending(descending: Boolean) {
        _sortDescending.value = descending
        prefs.edit { putBoolean(KEY_SORT_DESC, descending) }
    }

    var lastPlayedId: String?
        get() = prefs.getString(KEY_LAST_PLAYED, null)
        set(value) = prefs.edit { putString(KEY_LAST_PLAYED, value) }
}
