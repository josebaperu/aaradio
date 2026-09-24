package com.josebaperu.aautoradio.audio.dsp

import com.josebaperu.aautoradio.audio.Biquad
import kotlin.math.PI
import kotlin.math.exp

/** Stateful biquad (transposed direct form II). */
class BiquadFilter(private val type: Biquad.Type, private val freq: Double, private val q: Double = 0.707) {
    private var c = Biquad(1.0, 0.0, 0.0, 0.0, 0.0)
    private var z1 = 0.0
    private var z2 = 0.0

    fun configure(fs: Double) {
        c = Biquad.design(type, freq, 0.0, q, fs)
        reset()
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }

    fun process(x: Double): Double {
        val y = c.b0 * x + z1
        z1 = c.b1 * x - c.a1 * y + z2
        z2 = c.b2 * x - c.a2 * y
        return y
    }
}

/** First-order low-pass, cheap head-shadow / smoothing filter. */
class OnePoleLowPass(private val freq: Double) {
    private var a = 1.0
    private var y = 0.0

    fun configure(fs: Double) {
        a = 1 - exp(-2 * PI * freq / fs)
        reset()
    }

    fun reset() { y = 0.0 }

    fun process(x: Double): Double {
        y += a * (x - y)
        return y
    }
}

/** Integer-sample delay line. */
class DelayLine(maxSeconds: Double) {
    private val maxSeconds = maxSeconds
    private var buf = DoubleArray(1)
    private var mask = 0
    private var pos = 0

    fun configure(fs: Double) {
        var size = 1
        while (size < (maxSeconds * fs).toInt() + 2) size = size shl 1
        buf = DoubleArray(size)
        mask = size - 1
        pos = 0
    }

    fun reset() = buf.fill(0.0)

    fun write(x: Double) {
        buf[pos] = x
        pos = (pos + 1) and mask
    }

    /** Sample written [delay] samples before the most recent one (delay 0 = most recent). */
    fun read(delay: Int): Double = buf[(pos - 1 - delay) and mask]
}

/** Converts a time constant to a one-pole smoothing coefficient. */
fun smoothing(seconds: Double, fs: Double): Double = exp(-1.0 / (seconds * fs))
