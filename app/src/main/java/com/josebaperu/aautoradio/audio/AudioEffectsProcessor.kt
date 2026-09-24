package com.josebaperu.aautoradio.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.josebaperu.aautoradio.audio.dsp.BassEnhancerStage
import com.josebaperu.aautoradio.audio.dsp.CrossfeedStage
import com.josebaperu.aautoradio.audio.dsp.EqStage
import com.josebaperu.aautoradio.audio.dsp.LevelerStage
import com.josebaperu.aautoradio.audio.dsp.Limiter
import com.josebaperu.aautoradio.audio.dsp.MonoStage
import com.josebaperu.aautoradio.audio.dsp.ReverbStage
import com.josebaperu.aautoradio.audio.dsp.Stage
import com.josebaperu.aautoradio.audio.dsp.StereoWidthStage
import com.josebaperu.aautoradio.audio.dsp.VirtualSurroundStage
import java.nio.ByteBuffer

/**
 * The app's whole audio effects chain as one ExoPlayer audio processor (16-bit PCM, mono or stereo):
 *
 *   EQ → bass enhancer → stereo width → virtual surround | crossfeed → reverb → leveler → mono → limiter
 *
 * Audio is converted to float once, so stages don't clip each other. Toggling an effect cross-fades
 * it in or out over 30 ms instead of clicking. Running in-app means the effects behave the same
 * on every phone, and also apply when playing through Android Auto.
 */
@UnstableApi
class AudioEffectsProcessor : BaseAudioProcessor() {

    private val eq = EqStage()
    private val bass = BassEnhancerStage()
    private val width = StereoWidthStage()
    private val surround = VirtualSurroundStage()
    private val crossfeed = CrossfeedStage()
    private val reverb = ReverbStage()
    private val leveler = LevelerStage()
    private val mono = MonoStage()
    private val stages: List<Stage> = listOf(eq, bass, width, surround, crossfeed, reverb, leveler, mono)
    private val limiter = Limiter()

    private val mix = FloatArray(stages.size)
    private val cleared = BooleanArray(stages.size) { true }
    private var fadeStep = 0f

    private val l = FloatArray(BLOCK); private val r = FloatArray(BLOCK)
    private val dryL = FloatArray(BLOCK); private val dryR = FloatArray(BLOCK)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount in 1..2) inputAudioFormat
        else AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val rate = inputAudioFormat.sampleRate
        stages.forEach { it.configure(rate) }
        limiter.configure(rate.toDouble())
        fadeStep = 1f / (0.03f * rate)
        cleared.fill(true)
        // Start already at the current settings; fades are only for changes during playback.
        targets().forEachIndexed { i, on -> mix[i] = if (on) 1f else 0f }
    }

    override fun onReset() {
        mix.fill(0f)
        cleared.fill(true)
    }

    private fun targets(): BooleanArray {
        val fx = EffectsStore.state.value
        val car = EffectsStore.inCar.value
        return booleanArrayOf(
            EqStore.state.value.enabled,
            fx.bassEnhancer,
            fx.stereoWidth,
            fx.virtualSurround && !car,
            fx.crossfeed && !fx.virtualSurround && !car, // surround already includes crossfeed
            fx.reverb,
            fx.leveler,
            fx.mono,
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val on = targets()
        if (on.none { it } && mix.all { it == 0f }) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val fx = EffectsStore.state.value
        eq.update(EqStore.state.value)
        bass.amount = fx.bassAmount.toDouble()
        width.width = fx.width.toDouble()
        reverb.preset = fx.reverbPreset.params

        val channels = inputAudioFormat.channelCount
        while (inputBuffer.remaining() >= 2 * channels) {
            val n = minOf(BLOCK, inputBuffer.remaining() / (2 * channels))
            for (i in 0 until n) {
                val a = inputBuffer.getShort() / 32768f
                l[i] = a
                r[i] = if (channels == 2) inputBuffer.getShort() / 32768f else a
            }
            runStages(on, n)
            limiter.process(l, r, n)
            for (i in 0 until n) {
                if (channels == 2) {
                    out.putShort(toPcm(l[i])); out.putShort(toPcm(r[i]))
                } else {
                    out.putShort(toPcm((l[i] + r[i]) * 0.5f))
                }
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private fun runStages(on: BooleanArray, n: Int) {
        for (s in stages.indices) {
            val target = if (on[s]) 1f else 0f
            if (mix[s] == 0f && target == 0f) {
                if (!cleared[s]) { stages[s].reset(); cleared[s] = true }
                continue
            }
            cleared[s] = false
            if (mix[s] == 1f && target == 1f) {
                stages[s].process(l, r, n)
                continue
            }
            l.copyInto(dryL, endIndex = n); r.copyInto(dryR, endIndex = n)
            stages[s].process(l, r, n)
            var m = mix[s]
            for (i in 0 until n) {
                m = if (target > m) minOf(1f, m + fadeStep) else maxOf(0f, m - fadeStep)
                l[i] = dryL[i] + (l[i] - dryL[i]) * m
                r[i] = dryR[i] + (r[i] - dryR[i]) * m
            }
            mix[s] = m
        }
    }

    private fun toPcm(x: Float): Short = (x * 32768f).toInt().coerceIn(-32768, 32767).toShort()

    private companion object {
        const val BLOCK = 1024
    }
}
