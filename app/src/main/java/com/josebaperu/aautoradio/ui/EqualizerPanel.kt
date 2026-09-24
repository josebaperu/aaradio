package com.josebaperu.aautoradio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.josebaperu.aautoradio.R
import com.josebaperu.aautoradio.audio.EqState
import com.josebaperu.aautoradio.audio.EqStore
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun EqualizerPanel(state: EqState) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.equalizer), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    state.preset?.name ?: stringResource(R.string.custom),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.enabled, onCheckedChange = EqStore::setEnabled)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EqStore.presets.forEach { preset ->
                FilterChip(
                    selected = state.enabled && state.preset == preset,
                    onClick = { EqStore.applyPreset(preset) },
                    label = { Text(preset.name) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Column(Modifier.padding(vertical = 16.dp, horizontal = 8.dp)) {
                ResponseCurve(state, Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    EqStore.bands.forEachIndexed { i, band ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val db = state.gains[i]
                            Text(
                                (if (db > 0) "+" else "") + db.roundToInt() + " dB",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.height(8.dp))
                            VerticalGainSlider(
                                value = db,
                                enabled = state.enabled,
                                onChange = { EqStore.setGain(i, it) },
                                modifier = Modifier.width(48.dp).height(200.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(band.label + "Hz", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        EffectsSection()
    }
}

/** Fat Material-style vertical slider, -12..+12 dB, snapping to 0.5 dB, double-tap to reset. */
@Composable
private fun VerticalGainSlider(value: Float, enabled: Boolean, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val active = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.secondaryContainer
    val handle = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline
    val max = EqStore.MAX_DB

    fun dbAt(y: Float, height: Float) =
        (((1f - (y / height)) * 2f - 1f) * max).coerceIn(-max, max).let { (it * 2).roundToInt() / 2f }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onChange(0f) },
                    onTap = { onChange(dbAt(it.y, size.height.toFloat())) },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(dbAt(change.position.y, size.height.toFloat()))
                }
            }
    ) {
        val w = size.width * 0.42f
        val left = (size.width - w) / 2
        val r = CornerRadius(w / 2)
        drawRoundRect(track, Offset(left, 0f), Size(w, size.height), r)
        val zeroY = size.height / 2
        val y = size.height * (1f - (value + max) / (2 * max))
        val top = minOf(y, zeroY); val bottom = maxOf(y, zeroY)
        val h = bottom - top
        if (h > 0f) drawRoundRect(active, Offset(left, top), Size(w, h), CornerRadius(minOf(w, h) / 2), alpha = if (enabled) 1f else 0.5f)
        // zero line
        drawLine(handle.copy(alpha = 0.35f), Offset(left - 6f, zeroY), Offset(left + w + 6f, zeroY), strokeWidth = 2f)
        // handle
        drawRoundRect(handle, Offset(size.width * 0.1f, y - 5.dp.toPx()), Size(size.width * 0.8f, 10.dp.toPx()), CornerRadius(5.dp.toPx()))
    }
}

/** Combined frequency response of the five filters, drawn on a log-frequency axis. */
@Composable
private fun ResponseCurve(state: EqState, modifier: Modifier = Modifier) {
    val color = if (state.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    val fs = 44_100.0
    val points = remember(state) {
        val filters = state.copy(enabled = true).filters(fs)
        val lo = log10(20.0); val hi = log10(20_000.0)
        (0..120).map { i ->
            val f = 10.0.pow(lo + (hi - lo) * i / 120)
            (i / 120f) to filters.sumOf { it.magnitudeDb(f, fs) }.toFloat()
        }
    }
    Canvas(modifier) {
        val range = EqStore.MAX_DB + 3f
        fun yOf(db: Float) = size.height / 2 - db / range * size.height / 2
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
        val path = Path()
        points.forEachIndexed { i, (x, db) ->
            val px = x * size.width
            val py = yOf(db.coerceIn(-range, range))
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height / 2)
            lineTo(0f, size.height / 2)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent, color.copy(alpha = 0.35f))))
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}
