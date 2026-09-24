package com.josebaperu.aautoradio.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import com.josebaperu.aautoradio.data.Station
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Generated letter-tile artwork so stations look good in the notification, lock screen and Android Auto. */
object Artwork {
    private const val SIZE = 256
    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun pngFor(station: Station): ByteArray = cache.getOrPut(station.id) { render(station) }

    private fun render(station: Station): ByteArray {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val top = Color.HSVToColor(floatArrayOf(station.hue, 0.55f, 0.85f))
        val bottom = Color.HSVToColor(floatArrayOf((station.hue + 40f) % 360f, 0.70f, 0.55f))
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = SIZE * 0.38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val y = SIZE / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(station.initials, SIZE / 2f, y, text)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }
}
