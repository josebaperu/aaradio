package com.josebaperu.aautoradio.data

import java.text.Normalizer

data class Station(
    val id: String,
    val name: String,
    val url: String,
    val genre: String,
    val defaultFavorite: Boolean,
) {
    /** Stable hue (0..360) so a station always gets the same avatar color in the app, notification and Android Auto. */
    val hue: Float get() = ((name.hashCode() and 0x7fffffff) % 360).toFloat()

    /** Section letter for the alphabetical list: first letter without accents, or '#' for digits/symbols. */
    val groupLetter: String
        get() {
            val c = Normalizer.normalize(name.trim().take(1), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar()
            return if (c != null && c.isLetter()) c.toString() else "#"
        }

    val initials: String
        get() {
            val words = name.split(' ', '-', '\'').filter { it.isNotBlank() }
            return when {
                words.isEmpty() -> "?"
                words.size == 1 -> words[0].take(2).uppercase()
                else -> (words[0].take(1) + words[1].take(1)).uppercase()
            }
        }
}
