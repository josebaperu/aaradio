package com.josebaperu.aautoradio.audio.dsp

import com.josebaperu.aautoradio.audio.Biquad
import com.josebaperu.aautoradio.audio.EqState
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/** One stereo effect in the chain. Processes a block of left/right samples in place (range ±1). */
abstract class Stage {
    protected var fs = 44_100.0
        private set

    fun configure(sampleRate: Int) {
        fs = sampleRate.toDouble()
        onConfigure()
    }

    protected abstract fun onConfigure()
    abstract fun reset()
    abstract fun process(l: FloatArray, r: FloatArray, n: Int)
}

/** The 5-band graphic equalizer. */
class EqStage : Stage() {
    private var state: EqState? = null
    private var filters: List<Biquad> = emptyList()
    private var preamp = 1.0
    private val z = Array(2) { Array(5) { DoubleArray(2) } }

    fun update(s: EqState) {
        if (s === state) return
        state = s
        filters = s.copy(enabled = true).filters(fs)
        preamp = 10.0.pow(s.preampDb / 20.0)
    }

    override fun onConfigure() { state = null; reset() }
    override fun reset() = z.forEach { ch -> ch.forEach { it.fill(0.0) } }

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        run(l, z[0], n)
        run(r, z[1], n)
    }

    private fun run(buf: FloatArray, zc: Array<DoubleArray>, n: Int) {
        val f = filters
        for (i in 0 until n) {
            var x = buf[i] * preamp
            for (b in f.indices) {
                val q = f[b]; val s = zc[b]
                val y = q.b0 * x + s[0]
                s[0] = q.b1 * x - q.a1 * y + s[1]
                s[1] = q.b2 * x - q.a2 * y
                x = y
            }
            buf[i] = x.toFloat()
        }
    }
}

/**
 * Psychoacoustic bass enhancer: generates harmonics of the sub-120 Hz band so small speakers
 * and earbuds "imply" bass they can't reproduce, plus a gentle real low boost.
 */
class BassEnhancerStage : Stage() {
    var amount = 0.5
    private val lp1 = BiquadFilter(Biquad.Type.LOW_PASS, 120.0)
    private val lp2 = BiquadFilter(Biquad.Type.LOW_PASS, 120.0)
    private val hp1 = BiquadFilter(Biquad.Type.HIGH_PASS, 120.0)
    private val hp2 = BiquadFilter(Biquad.Type.HIGH_PASS, 120.0)
    private val lpH = BiquadFilter(Biquad.Type.LOW_PASS, 900.0)
    private var env = 0.0
    private var release = 0.0

    override fun onConfigure() {
        listOf(lp1, lp2, hp1, hp2, lpH).forEach { it.configure(fs) }
        release = smoothing(0.05, fs)
        env = 0.0
    }

    override fun reset() {
        listOf(lp1, lp2, hp1, hp2, lpH).forEach { it.reset() }
        env = 0.0
    }

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val low = lp2.process(lp1.process((l[i] + r[i]) * 0.5))
            env = max(abs(low), env * release)
            // Level-normalized saturation: harmonic content stays proportional to the bass level.
            val sat = tanh(low / (env + 1e-6) * 2.5) * env
            val harmonics = lpH.process(hp2.process(hp1.process(sat)))
            val add = (amount * (1.4 * harmonics + 0.35 * low)).toFloat()
            l[i] += add; r[i] += add
        }
    }
}

/** Mid/side stereo width. Width is only applied above 150 Hz so the bass stays centered. */
class StereoWidthStage : Stage() {
    var width = 1.4
    private val hp = BiquadFilter(Biquad.Type.HIGH_PASS, 150.0)

    override fun onConfigure() = hp.configure(fs)
    override fun reset() = hp.reset()

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val m = (l[i] + r[i]) * 0.5
            val side = (l[i] - r[i]) * 0.5
            val sideHigh = hp.process(side)
            val s = if (width >= 1) side + (width - 1) * sideHigh else side * width
            l[i] = (m + s).toFloat(); r[i] = (m - s).toFloat()
        }
    }
}

/**
 * Headphone crossfeed (after Bauer / bs2b): feeds a delayed, low-passed copy of each channel
 * to the other ear like speakers would, removing the "inside your head" hard panning.
 */
class CrossfeedStage : Stage() {
    private val g = 10.0.pow(-4.5 / 20)
    private val lpL = OnePoleLowPass(700.0)
    private val lpR = OnePoleLowPass(700.0)
    private val dL = DelayLine(0.002)
    private val dR = DelayLine(0.002)
    private var delay = 13

    override fun onConfigure() {
        listOf(lpL, lpR).forEach { it.configure(fs) }
        listOf(dL, dR).forEach { it.configure(fs) }
        delay = (0.0003 * fs).roundToInt()
    }

    override fun reset() {
        lpL.reset(); lpR.reset(); dL.reset(); dR.reset()
    }

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val norm = 1 / (1 + g)
        for (i in 0 until n) {
            val x = l[i].toDouble(); val y = r[i].toDouble()
            val lowL = lpL.process(x); val lowR = lpR.process(y)
            dL.write(lowL); dR.write(lowR)
            // Direct path gets a matching high boost so mono content stays flat.
            l[i] = ((x + g * (x - lowL) + g * dR.read(delay)) * norm).toFloat()
            r[i] = ((y + g * (y - lowR) + g * dL.read(delay)) * norm).toFloat()
        }
    }
}

/**
 * Headphone virtual surround. A lightweight binaural model (interaural delay + head-shadow
 * filtering) places the stereo pair as speakers at ±30°, extracts the ambience (side signal)
 * to virtual rear speakers at ±110°, and adds a few early room reflections.
 */
class VirtualSurroundStage : Stage() {
    private class Path(itdSeconds: Double, shadowHz: Double, val gain: Double) {
        val itd = itdSeconds
        val shadow = OnePoleLowPass(shadowHz)
        val delay = DelayLine(0.002)
        var samples = 0
        fun configure(fs: Double) { shadow.configure(fs); delay.configure(fs); samples = (itd * fs).roundToInt() }
        fun reset() { shadow.reset(); delay.reset() }
        fun process(x: Double): Double { delay.write(shadow.process(x)); return gain * delay.read(samples) }
    }

    private val frontContraL = Path(0.00026, 2000.0, 0.75)
    private val frontContraR = Path(0.00026, 2000.0, 0.75)
    private val rearContraL = Path(0.00065, 1000.0, 0.6)
    private val rearContraR = Path(0.00065, 1000.0, 0.6)
    private val directShadowL = OnePoleLowPass(2000.0)
    private val directShadowR = OnePoleLowPass(2000.0)
    private val rearIpsiL = OnePoleLowPass(6000.0)
    private val rearIpsiR = OnePoleLowPass(6000.0)
    private val ambienceDelay = DelayLine(0.03)
    private val reflections = DelayLine(0.03)
    private val reflLp = OnePoleLowPass(5000.0)
    private var ambSamples = 0
    private var taps = IntArray(0)
    private val tapGains = doubleArrayOf(0.22, 0.18, 0.14, 0.1)
    private val tapTimes = doubleArrayOf(0.0071, 0.0113, 0.0169, 0.0235)

    private val all get() = listOf(frontContraL, frontContraR, rearContraL, rearContraR)
    private val poles get() = listOf(directShadowL, directShadowR, rearIpsiL, rearIpsiR, reflLp)

    override fun onConfigure() {
        all.forEach { it.configure(fs) }
        poles.forEach { it.configure(fs) }
        ambienceDelay.configure(fs); reflections.configure(fs)
        ambSamples = (0.015 * fs).roundToInt()
        taps = IntArray(tapTimes.size) { (tapTimes[it] * fs).roundToInt() }
    }

    override fun reset() {
        all.forEach { it.reset() }
        poles.forEach { it.reset() }
        ambienceDelay.reset(); reflections.reset()
    }

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val norm = 1 / 1.9
        for (i in 0 until n) {
            val x = l[i].toDouble(); val y = r[i].toDouble()
            // Front speakers: direct path with a high boost matching the contralateral head shadow.
            val dirL = x + 0.75 * (x - directShadowL.process(x))
            val dirR = y + 0.75 * (y - directShadowR.process(y))
            // Rear ambience, delayed (precedence effect keeps the image in front) and decorrelated by polarity.
            ambienceDelay.write((x - y) * 0.5)
            val amb = ambienceDelay.read(ambSamples) * 0.55
            val rearL = rearIpsiL.process(amb)
            val rearR = rearIpsiR.process(-amb)
            // Early reflections of the mid signal, alternating sides.
            reflections.write(reflLp.process((x + y) * 0.5))
            var reflL = 0.0; var reflR = 0.0
            for (t in taps.indices) {
                val v = reflections.read(taps[t]) * tapGains[t]
                if (t % 2 == 0) reflL += v else reflR += v
            }
            val outL = dirL + frontContraR.process(y) + rearL + rearContraR.process(-amb) + reflL
            val outR = dirR + frontContraL.process(x) + rearR + rearContraL.process(amb) + reflR
            l[i] = (outL * norm).toFloat(); r[i] = (outR * norm).toFloat()
        }
    }
}

/** Freeverb (Jezar's public-domain Schroeder/Moorer reverb). */
class ReverbStage : Stage() {
    data class Preset(val room: Double, val damp: Double, val wet: Double)

    var preset = Preset(0.5, 0.5, 0.12)

    private class Comb(val size: Int) {
        val buf = DoubleArray(size); var idx = 0; var store = 0.0
        fun process(x: Double, feedback: Double, damp: Double): Double {
            val out = buf[idx]
            store = out * (1 - damp) + store * damp
            if (abs(store) < 1e-18) store = 0.0
            buf[idx] = x + store * feedback
            if (++idx >= size) idx = 0
            return out
        }
    }

    private class Allpass(val size: Int) {
        val buf = DoubleArray(size); var idx = 0
        fun process(x: Double): Double {
            val b = buf[idx]
            buf[idx] = x + b * 0.5
            if (++idx >= size) idx = 0
            return b - x
        }
    }

    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTunings = intArrayOf(556, 441, 341, 225)
    private val spread = 23
    private var combsL = emptyList<Comb>(); private var combsR = emptyList<Comb>()
    private var apL = emptyList<Allpass>(); private var apR = emptyList<Allpass>()

    override fun onConfigure() = reset()

    override fun reset() {
        val k = fs / 44_100.0
        fun sz(t: Int) = (t * k).roundToInt().coerceAtLeast(1)
        combsL = combTunings.map { Comb(sz(it)) }
        combsR = combTunings.map { Comb(sz(it + spread)) }
        apL = allpassTunings.map { Allpass(sz(it)) }
        apR = allpassTunings.map { Allpass(sz(it + spread)) }
    }

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val feedback = preset.room * 0.28 + 0.7
        val damp = preset.damp * 0.4
        val wet = preset.wet * 3
        for (i in 0 until n) {
            val input = (l[i] + r[i]) * 0.015
            var outL = 0.0; var outR = 0.0
            for (c in combsL) outL += c.process(input, feedback, damp)
            for (c in combsR) outR += c.process(input, feedback, damp)
            for (a in apL) outL = a.process(outL)
            for (a in apR) outR = a.process(outR)
            l[i] = (l[i] + outL * wet).toFloat()
            r[i] = (r[i] + outR * wet).toFloat()
        }
    }
}

/**
 * Volume leveler: slow RMS compressor with make-up gain. Evens out loudness between stations
 * and lifts quiet passages — useful in a noisy car. Silence is not boosted.
 */
class LevelerStage : Stage() {
    private val thresholdDb = -26.0
    private val ratio = 3.0
    private val makeupDb = 9.0
    private var ms = 0.0
    private var gainDb = 0.0
    private var det = 0.0; private var attack = 0.0; private var release = 0.0

    override fun onConfigure() {
        det = smoothing(0.08, fs); attack = smoothing(0.02, fs); release = smoothing(0.5, fs)
        reset()
    }

    override fun reset() { ms = 0.0; gainDb = 0.0 }

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val p = (l[i] * l[i] + r[i] * r[i]) * 0.5
            ms = p + det * (ms - p)
            val level = 10 * log10(ms + 1e-12)
            val compressed = if (level > thresholdDb) thresholdDb + (level - thresholdDb) / ratio - level else 0.0
            // Fade make-up gain out below -50 dBFS so noise floors and silence stay quiet.
            val makeup = makeupDb * ((level + 60) / 10).coerceIn(0.0, 1.0)
            val target = compressed + makeup
            val coef = if (target < gainDb) attack else release
            gainDb = target + coef * (gainDb - target)
            val g = 10.0.pow(gainDb / 20).toFloat()
            l[i] *= g; r[i] *= g
        }
    }
}

/** Sums to mono — for a single earbud or a car with one working speaker. */
class MonoStage : Stage() {
    override fun onConfigure() {}
    override fun reset() {}
    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val m = (l[i] + r[i]) * 0.5f
            l[i] = m; r[i] = m
        }
    }
}

/** Final safety peak limiter: instant attack, smooth release. Only acts above -0.3 dBFS. */
class Limiter {
    private var gain = 1.0
    private var release = 0.0

    fun configure(fs: Double) { release = smoothing(0.06, fs); gain = 1.0 }

    fun process(l: FloatArray, r: FloatArray, n: Int) {
        val ceiling = 0.966
        for (i in 0 until n) {
            val peak = max(abs(l[i]), abs(r[i])).toDouble()
            val released = 1.0 + release * (gain - 1.0)
            gain = if (peak * released > ceiling) ceiling / peak else released
            l[i] = (l[i] * gain).toFloat(); r[i] = (r[i] * gain).toFloat()
        }
    }
}
