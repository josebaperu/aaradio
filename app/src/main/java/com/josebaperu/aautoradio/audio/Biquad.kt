package com.josebaperu.aautoradio.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Normalized second-order IIR coefficients (RBJ Audio EQ Cookbook). a0 is folded in. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    enum class Type { LOW_SHELF, PEAK, HIGH_SHELF, LOW_PASS, HIGH_PASS }

    /** Magnitude response in dB at [freq] for sample rate [fs]. */
    fun magnitudeDb(freq: Double, fs: Double): Double {
        val w = 2 * PI * freq / fs
        val c1 = cos(w); val s1 = sin(w)
        val c2 = cos(2 * w); val s2 = sin(2 * w)
        val numRe = b0 + b1 * c1 + b2 * c2
        val numIm = -(b1 * s1 + b2 * s2)
        val denRe = 1 + a1 * c1 + a2 * c2
        val denIm = -(a1 * s1 + a2 * s2)
        val mag2 = (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm)
        return 10 * log10(mag2)
    }

    companion object {
        fun design(type: Type, freq: Double, gainDb: Double, q: Double, fs: Double): Biquad {
            val f = freq.coerceAtMost(fs * 0.45)
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * f / fs
            val cosW = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val b0: Double; val b1: Double; val b2: Double
            val a0: Double; val a1: Double; val a2: Double
            when (type) {
                Type.PEAK -> {
                    b0 = 1 + alpha * a; b1 = -2 * cosW; b2 = 1 - alpha * a
                    a0 = 1 + alpha / a; a1 = -2 * cosW; a2 = 1 - alpha / a
                }
                Type.LOW_PASS -> {
                    b0 = (1 - cosW) / 2; b1 = 1 - cosW; b2 = (1 - cosW) / 2
                    a0 = 1 + alpha; a1 = -2 * cosW; a2 = 1 - alpha
                }
                Type.HIGH_PASS -> {
                    b0 = (1 + cosW) / 2; b1 = -(1 + cosW); b2 = (1 + cosW) / 2
                    a0 = 1 + alpha; a1 = -2 * cosW; a2 = 1 - alpha
                }
                Type.LOW_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cosW + k)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
                    b2 = a * ((a + 1) - (a - 1) * cosW - k)
                    a0 = (a + 1) + (a - 1) * cosW + k
                    a1 = -2 * ((a - 1) + (a + 1) * cosW)
                    a2 = (a + 1) + (a - 1) * cosW - k
                }
                Type.HIGH_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cosW + k)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                    b2 = a * ((a + 1) + (a - 1) * cosW - k)
                    a0 = (a + 1) - (a - 1) * cosW + k
                    a1 = 2 * ((a - 1) - (a + 1) * cosW)
                    a2 = (a + 1) - (a - 1) * cosW - k
                }
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}
