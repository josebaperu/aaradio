package com.josebaperu.aautoradio.audio

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class EqBand(val label: String, val freq: Double, val type: Biquad.Type, val q: Double)

data class EqPreset(val name: String, val gains: List<Float>)

data class EqState(val enabled: Boolean, val gains: List<Float>) {
    val preset: EqPreset? get() = EqStore.presets.firstOrNull { it.gains == gains }

    /** Filters for this state, or empty when the EQ is bypassed. */
    fun filters(fs: Double): List<Biquad> =
        if (!enabled) emptyList()
        else EqStore.bands.mapIndexed { i, b -> Biquad.design(b.type, b.freq, gains[i].toDouble(), b.q, fs) }

    /** Headroom so boosts don't clip: attenuate by half of the largest boost. */
    val preampDb: Float get() = if (enabled) -(gains.maxOrNull() ?: 0f).coerceAtLeast(0f) / 2f else 0f
}

/**
 * Process-wide 5-band EQ settings. The UI writes here, and [EqualizerAudioProcessor]
 * (running on ExoPlayer's playback thread) picks up changes on the next audio buffer.
 */
object EqStore {
    const val MAX_DB = 12f

    val bands = listOf(
        EqBand("60", 60.0, Biquad.Type.LOW_SHELF, 0.707),
        EqBand("230", 230.0, Biquad.Type.PEAK, 0.9),
        EqBand("910", 910.0, Biquad.Type.PEAK, 0.9),
        EqBand("3.6k", 3600.0, Biquad.Type.PEAK, 0.9),
        EqBand("14k", 14000.0, Biquad.Type.HIGH_SHELF, 0.707),
    )

    val presets = listOf(
        EqPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f)),
        EqPreset("Bass", listOf(6f, 4f, 0f, 0f, 0f)),
        EqPreset("Rock", listOf(5f, 2f, -2f, 2f, 5f)),
        EqPreset("Pop", listOf(-1f, 2f, 4f, 2f, -1f)),
        EqPreset("Jazz", listOf(3f, 1f, -1f, 2f, 3f)),
        EqPreset("Classical", listOf(4f, 2f, -1f, 2f, 4f)),
        EqPreset("Vocal", listOf(-2f, -1f, 3f, 4f, 1f)),
        EqPreset("Car", listOf(3f, 0f, -1f, 2f, 4f)),
    )

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(EqState(true, List(bands.size) { 0f }))
    val state: StateFlow<EqState> = _state.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
        val gains = bands.indices.map { prefs.getFloat("band_$it", 0f) }
        _state.value = EqState(prefs.getBoolean("enabled", true), gains)
    }

    fun setGain(band: Int, db: Float) = save { it.copy(gains = it.gains.toMutableList().also { g -> g[band] = db.coerceIn(-MAX_DB, MAX_DB) }) }

    fun setEnabled(enabled: Boolean) = save { it.copy(enabled = enabled) }

    fun applyPreset(preset: EqPreset) = save { it.copy(enabled = true, gains = preset.gains) }

    private fun save(transform: (EqState) -> EqState) {
        _state.update(transform)
        val s = _state.value
        if (!::prefs.isInitialized) return
        prefs.edit {
            putBoolean("enabled", s.enabled)
            s.gains.forEachIndexed { i, g -> putFloat("band_$i", g) }
        }
    }
}
