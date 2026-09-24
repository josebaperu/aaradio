package com.josebaperu.aautoradio.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.josebaperu.aautoradio.data.Station

/** Gradient letter tile matching the artwork generated for the notification and Android Auto. */
@Composable
fun StationAvatar(station: Station, size: Dp, playing: Boolean = false, modifier: Modifier = Modifier) {
    val top = Color.hsv(station.hue, 0.55f, 0.85f)
    val bottom = Color.hsv((station.hue + 40f) % 360f, 0.70f, 0.55f)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            PlayingBars(Modifier.size(size * 0.5f))
        } else {
            Text(
                station.initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
            )
        }
    }
}

/** Small animated "now playing" bars. */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, color: Color = Color.White) {
    val t = rememberInfiniteTransition(label = "bars")
    val heights = listOf(520, 380, 610).map { period ->
        t.animateFloat(
            initialValue = 0.2f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(period), RepeatMode.Reverse),
            label = "bar",
        )
    }
    val h0 by heights[0]; val h1 by heights[1]; val h2 by heights[2]
    Canvas(modifier) {
        val barW = size.width / 5f
        listOf(h0, h1, h2).forEachIndexed { i, h ->
            val barH = size.height * h
            drawRoundRect(
                color,
                topLeft = Offset(barW * (i * 2), size.height - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2),
            )
        }
    }
}
