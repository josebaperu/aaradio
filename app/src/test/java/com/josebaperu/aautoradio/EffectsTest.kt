package com.josebaperu.aautoradio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.josebaperu.aautoradio.audio.AudioEffectsProcessor
import com.josebaperu.aautoradio.audio.EffectsState
import com.josebaperu.aautoradio.audio.EffectsStore
import com.josebaperu.aautoradio.audio.EqStore
import com.josebaperu.aautoradio.audio.ReverbPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EffectsTest {
    private val fs = 44_100

    @After fun resetStores() {
        EffectsStore.update { EffectsState() }
        EffectsStore.setInCar(false)
        EqStore.setEnabled(false)
    }

    /** Runs a stereo signal (left freq fL, right freq fR, amplitude amp) through a fresh processor. */
    private fun run(amp: Double, fL: Double = 440.0, fR: Double = 440.0, seconds: Double = 2.0): Pair<ShortArray, ShortArray> {
        val p = AudioEffectsProcessor()
        p.configure(AudioProcessor.AudioFormat(fs, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val frames = (fs * seconds).toInt()
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { i ->
            input.putShort((sin(2 * PI * fL * i / fs) * amp * 32767).toInt().toShort())
            input.putShort((sin(2 * PI * fR * i / fs) * amp * 32767).toInt().toShort())
        }
        input.flip()
        p.queueInput(input)
        val out = p.output
        val l = ShortArray(frames); val r = ShortArray(frames)
        for (i in 0 until frames) { l[i] = out.getShort(); r[i] = out.getShort() }
        return l to r
    }

    private fun rmsDb(x: ShortArray, from: Int = x.size / 2): Double {
        var s = 0.0
        for (i in from until x.size) s += x[i].toDouble() * x[i]
        return 20 * log10(sqrt(s / (x.size - from)) / 32768)
    }

    @Test fun everythingOffIsBitExact() {
        EqStore.setEnabled(false)
        val (l, _) = run(0.5)
        assertEquals((sin(2 * PI * 440 * 1000 / fs) * 0.5 * 32767).toInt().toShort(), l[1000])
    }

    @Test fun allEffectsOnStayFiniteAndBelowFullScale() {
        EqStore.applyPreset(EqStore.presets.first { it.name == "Bass" })
        EffectsStore.update {
            EffectsState(
                bassEnhancer = true, bassAmount = 1f, stereoWidth = true, width = 2f, virtualSurround = true,
                reverb = true, reverbPreset = ReverbPreset.HALL, leveler = true, systemSpatialAudio = false,
            )
        }
        val (l, r) = run(0.99, 60.0, 5000.0)
        val peak = maxOf(l.maxOf { abs(it.toInt()) }, r.maxOf { abs(it.toInt()) })
        assertTrue("peak $peak", peak <= 32767 * 0.97)
        assertTrue(rmsDb(l) > -30) // still producing sound, not silenced or NaN (NaN → 0)
    }

    @Test fun monoMakesChannelsIdentical() {
        EffectsStore.update { it.copy(mono = true) }
        val (l, r) = run(0.5, 300.0, 3000.0)
        for (i in l.size / 2 until l.size) assertEquals(l[i], r[i])
    }

    @Test fun widthZeroCollapsesToCenter() {
        EffectsStore.update { it.copy(stereoWidth = true, width = 0f) }
        val (l, r) = run(0.5, 1000.0, 2000.0)
        for (i in l.size / 2 until l.size) assertTrue(abs(l[i] - r[i]) <= 1)
    }

    @Test fun levelerRaisesQuietAudio() {
        val quiet = rmsDb(run(0.02).first)
        EffectsStore.update { it.copy(leveler = true) }
        val leveled = rmsDb(run(0.02, seconds = 3.0).first)
        assertTrue("quiet $quiet leveled $leveled", leveled - quiet > 5)
    }

    @Test fun headphoneEffectsPauseInTheCar() {
        val dry = run(0.5, 200.0, 0.0)
        EffectsStore.update { it.copy(crossfeed = true, virtualSurround = true) }
        EffectsStore.setInCar(true)
        val inCar = run(0.5, 200.0, 0.0)
        EffectsStore.setInCar(false)
        val onHeadphones = run(0.5, 200.0, 0.0)
        assertEquals(dry.second.toList(), inCar.second.toList())
        assertTrue(onHeadphones.second.toList() != dry.second.toList())
    }

    @Test fun crossfeedLeaksLowsToTheOtherEar() {
        EffectsStore.update { it.copy(crossfeed = true) }
        val (_, r) = run(0.5, 200.0, 0.0) // tone only on the left
        assertTrue(rmsDb(r) > -25)
    }
}
