package com.josebaperu.aautoradio.audio

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.josebaperu.aautoradio.audio.dsp.ReverbStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ReverbPreset(val label: String, val params: ReverbStage.Preset) {
    ROOM("Room", ReverbStage.Preset(0.45, 0.5, 0.10)),
    CLUB("Club", ReverbStage.Preset(0.68, 0.4, 0.14)),
    HALL("Hall", ReverbStage.Preset(0.88, 0.25, 0.18)),
}

data class EffectsState(
    val bassEnhancer: Boolean = false,
    val bassAmount: Float = 0.5f,
    val stereoWidth: Boolean = false,
    val width: Float = 1.4f,
    val crossfeed: Boolean = false,
    val virtualSurround: Boolean = false,
    val reverb: Boolean = false,
    val reverbPreset: ReverbPreset = ReverbPreset.ROOM,
    val leveler: Boolean = false,
    val mono: Boolean = false,
    /** Lets Android's own spatializer (Android 13+, supported phones/headphones) process this app's audio. */
    val systemSpatialAudio: Boolean = true,
)

/**
 * Process-wide audio effect settings, written by the UI and read by [AudioEffectsProcessor]
 * on the playback thread. [inCar] is set by the service while Android Auto is connected,
 * which pauses the headphone-only effects (crossfeed, virtual surround).
 */
object EffectsStore {
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(EffectsState())
    val state: StateFlow<EffectsState> = _state.asStateFlow()

    private val _inCar = MutableStateFlow(false)
    val inCar: StateFlow<Boolean> = _inCar.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("effects", Context.MODE_PRIVATE)
        val d = EffectsState()
        _state.value = EffectsState(
            bassEnhancer = prefs.getBoolean("bass", d.bassEnhancer),
            bassAmount = prefs.getFloat("bass_amount", d.bassAmount),
            stereoWidth = prefs.getBoolean("width_on", d.stereoWidth),
            width = prefs.getFloat("width", d.width),
            crossfeed = prefs.getBoolean("crossfeed", d.crossfeed),
            virtualSurround = prefs.getBoolean("surround", d.virtualSurround),
            reverb = prefs.getBoolean("reverb", d.reverb),
            reverbPreset = ReverbPreset.entries.getOrElse(prefs.getInt("reverb_preset", 0)) { ReverbPreset.ROOM },
            leveler = prefs.getBoolean("leveler", d.leveler),
            mono = prefs.getBoolean("mono", d.mono),
            systemSpatialAudio = prefs.getBoolean("spatial", d.systemSpatialAudio),
        )
    }

    fun update(transform: (EffectsState) -> EffectsState) {
        _state.update(transform)
        if (!::prefs.isInitialized) return
        val s = _state.value
        prefs.edit {
            putBoolean("bass", s.bassEnhancer); putFloat("bass_amount", s.bassAmount)
            putBoolean("width_on", s.stereoWidth); putFloat("width", s.width)
            putBoolean("crossfeed", s.crossfeed); putBoolean("surround", s.virtualSurround)
            putBoolean("reverb", s.reverb); putInt("reverb_preset", s.reverbPreset.ordinal)
            putBoolean("leveler", s.leveler); putBoolean("mono", s.mono)
            putBoolean("spatial", s.systemSpatialAudio)
        }
    }

    fun setInCar(inCar: Boolean) { _inCar.value = inCar }
}
