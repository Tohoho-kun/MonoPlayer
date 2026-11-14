package com.example.monoplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.dsp.OutputStageMode
import com.example.monoplayer.dsp.SpatialStageConfig
import com.example.monoplayer.dsp.VisualizerTelemetry

@Composable
fun SpatialDepthStageCard(
    config: SpatialStageConfig,
    telemetry: VisualizerTelemetry,
    onUpdateConfig: (SpatialStageConfig) -> Unit,
    onSelectPreset: (String) -> Unit
) {
    val gold = AcousticPebbleColors.PrimaryContainer
    val cyan = AcousticPebbleColors.Primary
    val violet = AcousticPebbleColors.Secondary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AcousticPebbleColors.SurfaceContainerLow)
            .border(1.dp, AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ステレオ情報活用・前後奥行き空間 ＆ ナローステレオ",
                    color = AcousticPebbleColors.OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Midを手前に保ちつつSide成分をZ軸後方へ配置。完全モノラル／ナローステレオ対応",
                    color = AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        // Quick Presets Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SpatialStageConfig.PRESETS.keys.forEach { presetName ->
                val isSelected = config.presetName == presetName
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectPreset(presetName) },
                    label = { Text(presetName, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AcousticPebbleColors.SurfaceContainerHigh,
                        selectedLabelColor = gold
                    )
                )
            }
        }

        // Output Stage Mode Selector: PURE_MONO vs NARROW_STEREO
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutputStageMode.entries.forEach { mode ->
                val selected = config.stageMode == mode
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) AcousticPebbleColors.SurfaceContainerHigh else AcousticPebbleColors.SurfaceContainerLowest)
                        .border(
                            width = if (selected) 1.4.dp else 1.dp,
                            color = if (selected) gold else AcousticPebbleColors.OutlineVariant,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            onUpdateConfig(config.copy(stageMode = mode, presetName = "Custom"))
                        }
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = mode.shortBadge,
                        color = if (selected) gold else AcousticPebbleColors.OnSurfaceVariant,
                        fontSize = 9.sp,
                        fontFamily = PlusJakartaSansFamily,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (mode == OutputStageMode.PURE_MONO) "完全モノラル" else "ナローステレオ",
                        color = AcousticPebbleColors.OnSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (mode == OutputStageMode.PURE_MONO) "L=R同一・Z軸前後表現" else "低域モノ固定＋微細幅調整",
                        color = AcousticPebbleColors.OnSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
        }

        // Narrow Stereo Width Slider (visible when NARROW_STEREO is active)
        if (config.stageMode == OutputStageMode.NARROW_STEREO) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AcousticPebbleColors.SurfaceContainerLowest)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ナローステレオ幅 (180Hz以下は完全モノラル固定)",
                        color = AcousticPebbleColors.OnSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${config.narrowWidthPercent.toInt()}%",
                        color = gold,
                        fontSize = 12.sp,
                        fontFamily = PlusJakartaSansFamily,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = config.narrowWidthPercent,
                    onValueChange = {
                        onUpdateConfig(config.copy(narrowWidthPercent = it, presetName = "Custom"))
                    },
                    valueRange = 5f..80f,
                    colors = SliderDefaults.colors(thumbColor = gold, activeTrackColor = gold)
                )
            }
        }

        // Live Z-Axis Front-to-Back Soundstage & Narrow Cone Diagram
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(106.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AcousticPebbleColors.SurfaceContainerLowest)
                .border(1.dp, AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(10.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cx = w * 0.5f
                val listenerY = h * 0.90f

                val halfSpreadFrac = if (config.stageMode == OutputStageMode.NARROW_STEREO) {
                    0.06f + (config.narrowWidthPercent / 80f) * 0.36f
                } else {
                    0.035f
                }

                // Soundstage dispersion cone
                val conePath = Path().apply {
                    moveTo(cx, listenerY)
                    lineTo(cx - w * halfSpreadFrac, h * 0.10f)
                    lineTo(cx + w * halfSpreadFrac, h * 0.10f)
                    close()
                }
                drawPath(
                    path = conePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            violet.copy(alpha = if (config.depthEnabled) 0.24f else 0.06f),
                            gold.copy(alpha = 0.14f),
                            Color.Transparent
                        )
                    )
                )

                // Z-axis distance grid arcs
                for (g in 1..3) {
                    val gy = listenerY - (h * 0.76f) * (g / 3f)
                    drawLine(
                        color = AcousticPebbleColors.OutlineVariant.copy(alpha = 0.5f),
                        start = Offset(w * 0.12f, gy),
                        end = Offset(w * 0.88f, gy),
                        strokeWidth = 1f
                    )
                }

                // Recessed Stereo-Side Depth Halo (Rear Z-axis plane)
                if (config.depthEnabled && config.depthAmount > 0.01f) {
                    val depthNorm = ((telemetry.spatialDepthDb + 54f) / 48f).coerceIn(0.08f, 1f)
                    val zDistanceFrac = (0.48f + (config.preDelayMs / 42f) * 0.24f + config.roomDecay * 0.14f).coerceIn(0.45f, 0.84f)
                    val rearY = listenerY - h * zDistanceFrac
                    val rearRadius = (16f + config.depthAmount * 26f + telemetry.spatialSideRatio * 18f) * (0.7f + depthNorm * 0.5f)

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                violet.copy(alpha = 0.52f * config.depthAmount + 0.15f),
                                cyan.copy(alpha = 0.22f * config.depthAmount),
                                Color.Transparent
                            ),
                            center = Offset(cx, rearY),
                            radius = rearRadius * 1.6f
                        ),
                        radius = rearRadius * 1.6f,
                        center = Offset(cx, rearY)
                    )
                    drawCircle(
                        color = violet.copy(alpha = 0.75f),
                        radius = rearRadius * 0.55f,
                        center = Offset(cx, rearY),
                        style = Stroke(width = 1.5f)
                    )
                }

                // Foreground Coherent Mid Anchor (Front Z-axis plane: Lead Vocal / Kick / Snare)
                val frontY = listenerY - h * 0.26f
                val frontPulse = 7.5f + telemetry.rmsLinear.coerceIn(0f, 1f) * 8.5f
                drawCircle(
                    color = gold.copy(alpha = 0.28f),
                    radius = frontPulse * 1.8f,
                    center = Offset(cx, frontY)
                )
                drawCircle(
                    color = gold,
                    radius = frontPulse * 0.72f,
                    center = Offset(cx, frontY)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "奥 (Z-Rear: Side空間 ${"%.0f".format(telemetry.spatialSideRatio * 100f)}%)",
                    color = violet,
                    fontSize = 9.sp,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "手前 (Z-Front: Mid芯)",
                    color = gold,
                    fontSize = 9.sp,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // Spatial Depth Engine Toggle + Sliders
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ステレオSide空間系・前後奥行きエフェクト",
                color = AcousticPebbleColors.OnSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Switch(
                checked = config.depthEnabled,
                onCheckedChange = {
                    onUpdateConfig(config.copy(depthEnabled = it, presetName = "Custom"))
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AcousticPebbleColors.Primary,
                    checkedTrackColor = AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.45f)
                )
            )
        }

        if (config.depthEnabled) {
            SpatialSliderRow(
                label = "奥行き空間ブレンド量 (Depth Wet)",
                valueText = "${(config.depthAmount * 100).toInt()}%",
                value = config.depthAmount,
                range = 0f..1f,
                accent = violet,
                onChange = { onUpdateConfig(config.copy(depthAmount = it, presetName = "Custom")) }
            )
            SpatialSliderRow(
                label = "前後定位コントラスト (Blauert Front/Back)",
                valueText = "${(config.frontBackContrast * 100).toInt()}%",
                value = config.frontBackContrast,
                range = 0f..1f,
                accent = gold,
                onChange = { onUpdateConfig(config.copy(frontBackContrast = it, presetName = "Custom")) }
            )
            SpatialSliderRow(
                label = "初期反射プリディレイ (Haas ITDG 前後距離)",
                valueText = "${config.preDelayMs.toInt()} ms",
                value = config.preDelayMs,
                range = 4f..42f,
                accent = cyan,
                onChange = { onUpdateConfig(config.copy(preDelayMs = it, presetName = "Custom")) }
            )
            SpatialSliderRow(
                label = "空間サイズ・拡散余韻 (Stage Decay)",
                valueText = "${(config.roomDecay * 100).toInt()}%",
                value = config.roomDecay,
                range = 0.10f..0.85f,
                accent = violet,
                onChange = { onUpdateConfig(config.copy(roomDecay = it, presetName = "Custom")) }
            )
        }
    }
}

@Composable
private fun SpatialSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = AcousticPebbleColors.OnSurface, fontSize = 11.sp)
            Text(
                text = valueText,
                color = accent,
                fontSize = 11.sp,
                fontFamily = PlusJakartaSansFamily,
                letterSpacing = 0.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
        )
    }
}
