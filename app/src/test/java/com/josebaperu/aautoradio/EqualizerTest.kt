package com.josebaperu.aautoradio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.josebaperu.aautoradio.audio.Biquad
import com.josebaperu.aautoradio.audio.EqState
import com.josebaperu.aautoradio.audio.EqStore
import com.josebaperu.aautoradio.audio.AudioEffectsProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {
    private val fs = 44_100.0

    @Test fun peakFilterHitsGainAtCenter() {
        val f = Biquad.design(Biquad.Type.PEAK, 910.0, 6.0, 0.9, fs)
        assertEquals(6.0, f.magnitudeDb(910.0, fs), 0.01)
        assertEquals(0.0, f.magnitudeDb(15_000.0, fs), 0.3)
    }

    @Test fun shelvesReachFullGainAtExtremes() {
        val low = Biquad.design(Biquad.Type.LOW_SHELF, 60.0, -8.0, 0.707, fs)
        assertEquals(-8.0, low.magnitudeDb(20.0, fs), 0.8)
        assertEquals(0.0, low.magnitudeDb(5_000.0, fs), 0.1)
        val high = Biquad.design(Biquad.Type.HIGH_SHELF, 14_000.0, 5.0, 0.707, fs)
        assertEquals(0.0, high.magnitudeDb(200.0, fs), 0.1)
    }

    @Test fun flatEqIsTransparent() {
        val filters = EqState(true, List(5) { 0f }).filters(fs)
        for (f in listOf(30.0, 440.0, 5000.0, 18000.0)) assertEquals(0.0, filters.sumOf { it.magnitudeDb(f, fs) }, 1e-6)
    }

    /** Runs a stereo sine through the real processor with a +6 dB boost at 910 Hz (preamp -3 dB): expect ~+3 dB. */
    @Test fun processorAppliesGain() {
        val sine = 910.0
        val inDb = rms(sine, EqState(false, List(5) { 0f }))
        EqStore.applyPreset(com.josebaperu.aautoradio.audio.EqPreset("t", listOf(0f, 0f, 6f, 0f, 0f)))
        val outDb = rms(sine, EqStore.state.value)
        assertEquals(3.0, outDb - inDb, 0.5)
    }

    private fun rms(freq: Double, state: EqState): Double {
        // EqStore has no Android context in unit tests; swap its state via the public API.
        EqStore.setEnabled(state.enabled)
        state.gains.forEachIndexed(EqStore::setGain)
        val p = AudioEffectsProcessor()
        p.configure(AudioProcessor.AudioFormat(fs.toInt(), 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val frames = 44_100
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { i ->
            val s = (sin(2 * PI * freq * i / fs) * 8000).toInt().toShort()
            input.putShort(s); input.putShort(s)
        }
        input.flip()
        p.queueInput(input)
        val out = p.output
        var sum = 0.0; var n = 0
        var i = 0
        while (out.remaining() >= 2) {
            val v = out.getShort().toDouble()
            if (i++ > 8000) { sum += v * v; n++ } // skip filter settling
        }
        return 20 * log10(sqrt(sum / n))
    }
}
