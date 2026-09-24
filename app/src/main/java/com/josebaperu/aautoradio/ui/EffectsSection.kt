package com.josebaperu.aautoradio.ui

import android.content.Context
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.josebaperu.aautoradio.R
import com.josebaperu.aautoradio.audio.EffectsStore
import com.josebaperu.aautoradio.audio.ReverbPreset
import kotlin.math.roundToInt

@Composable
fun EffectsSection() {
    val fx by EffectsStore.state.collectAsStateWithLifecycle()
    val inCar by EffectsStore.inCar.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spatial = remember { spatialSupport(context) }

    Text(
        stringResource(R.string.effects),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
    if (inCar && (fx.virtualSurround || fx.crossfeed)) {
        Spacer(Modifier.height(8.dp))
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp)) {
            Text(
                stringResource(R.string.fx_car_paused),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    EffectRow(
        title = stringResource(R.string.fx_bass),
        description = stringResource(R.string.fx_bass_desc),
        checked = fx.bassEnhancer,
        onCheckedChange = { on -> EffectsStore.update { it.copy(bassEnhancer = on) } },
    ) {
        LabeledSlider(
            label = stringResource(R.string.fx_strength),
            valueText = "${(fx.bassAmount * 100).roundToInt()}%",
            value = fx.bassAmount,
            range = 0.1f..1f,
            onChange = { v -> EffectsStore.update { it.copy(bassAmount = v) } },
        )
    }
    EffectRow(
        title = stringResource(R.string.fx_width),
        description = stringResource(R.string.fx_width_desc),
        checked = fx.stereoWidth,
        onCheckedChange = { on -> EffectsStore.update { it.copy(stereoWidth = on) } },
    ) {
        LabeledSlider(
            label = stringResource(R.string.fx_width_amount),
            valueText = "${(fx.width * 100).roundToInt()}%",
            value = fx.width,
            range = 0f..2f,
            onChange = { v -> EffectsStore.update { it.copy(width = (v * 20).roundToInt() / 20f) } },
        )
    }
    EffectRow(
        title = stringResource(R.string.fx_surround),
        description = stringResource(R.string.fx_surround_desc),
        checked = fx.virtualSurround,
        headphones = true,
        onCheckedChange = { on -> EffectsStore.update { it.copy(virtualSurround = on) } },
    )
    EffectRow(
        title = stringResource(R.string.fx_crossfeed),
        description = stringResource(if (fx.virtualSurround) R.string.fx_crossfeed_included else R.string.fx_crossfeed_desc),
        checked = fx.crossfeed && !fx.virtualSurround,
        enabled = !fx.virtualSurround,
        headphones = true,
        onCheckedChange = { on -> EffectsStore.update { it.copy(crossfeed = on) } },
    )
    EffectRow(
        title = stringResource(R.string.fx_reverb),
        description = stringResource(R.string.fx_reverb_desc),
        checked = fx.reverb,
        onCheckedChange = { on -> EffectsStore.update { it.copy(reverb = on) } },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReverbPreset.entries.forEach { p ->
                FilterChip(
                    selected = fx.reverbPreset == p,
                    onClick = { EffectsStore.update { it.copy(reverbPreset = p) } },
                    label = { Text(p.label) },
                )
            }
        }
    }
    EffectRow(
        title = stringResource(R.string.fx_leveler),
        description = stringResource(R.string.fx_leveler_desc),
        checked = fx.leveler,
        onCheckedChange = { on -> EffectsStore.update { it.copy(leveler = on) } },
    )
    EffectRow(
        title = stringResource(R.string.fx_mono),
        description = stringResource(R.string.fx_mono_desc),
        checked = fx.mono,
        onCheckedChange = { on -> EffectsStore.update { it.copy(mono = on) } },
    )
    EffectRow(
        title = stringResource(R.string.fx_spatial),
        description = stringResource(
            when (spatial) {
                SpatialSupport.UNSUPPORTED -> R.string.fx_spatial_unsupported
                SpatialSupport.DISABLED -> R.string.fx_spatial_disabled
                SpatialSupport.MULTICHANNEL_ONLY -> R.string.fx_spatial_multichannel
                SpatialSupport.STEREO -> R.string.fx_spatial_desc
            }
        ),
        checked = fx.systemSpatialAudio && spatial != SpatialSupport.UNSUPPORTED,
        enabled = spatial != SpatialSupport.UNSUPPORTED,
        showDivider = false,
        onCheckedChange = { on -> EffectsStore.update { it.copy(systemSpatialAudio = on) } },
    )
}

@Composable
private fun EffectRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    headphones: Boolean = false,
    showDivider: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.5f
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    )
                    if (headphones) {
                        Spacer(Modifier.width(8.dp))
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                            Text(
                                stringResource(R.string.fx_headphones),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        if (checked && extra != null) {
            Spacer(Modifier.height(8.dp))
            extra()
        }
    }
    if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(
            valueText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(52.dp).padding(start = 8.dp),
        )
    }
}

enum class SpatialSupport { UNSUPPORTED, DISABLED, MULTICHANNEL_ONLY, STEREO }

/** What Android's own spatializer (Android 13+) can do for this app's stereo streams right now. */
fun spatialSupport(context: Context): SpatialSupport {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return SpatialSupport.UNSUPPORTED
    val sp = context.getSystemService(AudioManager::class.java).spatializer
    if (sp.immersiveAudioLevel == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) return SpatialSupport.UNSUPPORTED
    if (!sp.isEnabled) return SpatialSupport.DISABLED
    val attrs = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    val stereo = android.media.AudioFormat.Builder()
        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(44_100)
        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
        .build()
    return if (sp.canBeSpatialized(attrs, stereo)) SpatialSupport.STEREO else SpatialSupport.MULTICHANNEL_ONLY
}
