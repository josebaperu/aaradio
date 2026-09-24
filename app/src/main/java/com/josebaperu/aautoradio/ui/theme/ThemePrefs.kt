package com.josebaperu.aautoradio.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's light/dark choice for the phone UI. `null` = follow the system until the user
 * taps the toggle. (Android Auto always draws its own dark UI; this only affects the phone.)
 */
object ThemePrefs {
    private const val KEY_DARK = "dark"
    private lateinit var prefs: SharedPreferences
    private val _dark = MutableStateFlow<Boolean?>(null)
    val dark: StateFlow<Boolean?> = _dark.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)
        _dark.value = if (prefs.contains(KEY_DARK)) prefs.getBoolean(KEY_DARK, false) else null
    }

    fun setDark(dark: Boolean) {
        _dark.value = dark
        prefs.edit { putBoolean(KEY_DARK, dark) }
    }
}
