package com.example.monoplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.dsp.DeclipConfig
import com.example.monoplayer.dsp.VisualizerTelemetry
import kotlin.math.roundToInt

@Composable
fun DeclipRestorationCard(
    config: DeclipConfig,
    telemetry: VisualizerTelemetry,
    onConfigChange: (DeclipConfig) -> Unit
) {
    val activeColor = AcousticPebbleColors.Error
    val cyanAccent = AcousticPebbleColors.PrimaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AcousticPebbleColors.SurfaceContainerLow)
            .border(1.dp, AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Card Header with Enable Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = if (config.enabled) activeColor else AcousticPebbleColors.OnSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "クリッピング検出 & 波形修復 (De-clipping)",
                        color = AcousticPebbleColors.OnSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "0dB超過 + 音量低下マスタリングの平坦ピーク & プチプチ音をエルミート復元",
                        color = AcousticPebbleColors.OnSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }

            Switch(
                checked = config.enabled,
                onCheckedChange = { onConfigChange(config.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = activeColor,
                    checkedTrackColor = AcousticPebbleColors.ErrorContainer
                )
            )
        }

        // Live Diagnostic Metrics Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AcousticPebbleColors.SurfaceContainerLowest)
                .border(1.dp, AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Repaired events counter
            Column {
                Text(
                    text = "修復イベント数",
                    color = AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${telemetry.declipEventsTotal} 回",
                    color = if (telemetry.declipEventsTotal > 0) cyanAccent else AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Estimated Threshold
            Column {
                Text(
                    text = "検出クリップ天井",
                    color = AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = String.format("%.1f dBFS", telemetry.declipThresholdDb),
                    color = if (telemetry.declipThresholdDb < -0.5f) AcousticPebbleColors.TertiaryContainer else AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Max Reconstructed Peak
            Column {
                Text(
                    text = "復元ピーク振幅",
                    color = AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = String.format("%.2f", telemetry.declipMaxPeak),
                    color = if (telemetry.declipMaxPeak > 1.0f) activeColor else AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Activity Meter (Rate of clipping detected in recent window)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "リアルタイム・クリップ検出強度",
                    color = AcousticPebbleColors.OnSurface,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${(telemetry.declipRate * 100f).roundToInt()}%",
                    color = if (telemetry.declipRate > 0.05f) activeColor else AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AcousticPebbleColors.SurfaceContainerHigh)
            ) {
                val fillRatio = telemetry.declipRate.coerceIn(0f, 1f)
                if (fillRatio > 0f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(AcousticPebbleColors.PrimaryContainer, AcousticPebbleColors.TertiaryContainer, AcousticPebbleColors.Error)
                        ),
                        topLeft = Offset.Zero,
                        size = Size(size.width * fillRatio, size.height),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    )
                }
            }
        }

        // Sensitivity Slider
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "検出感度 (音量低下クリップ・微小プチプチ検知)",
                    color = AcousticPebbleColors.OnSurface,
                    fontSize = 11.sp
                )
                Text(
                    text = "${(config.sensitivity * 100f).roundToInt()}%",
                    color = activeColor,
                    fontSize = 11.sp,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = config.sensitivity,
                onValueChange = { onConfigChange(config.copy(sensitivity = it)) },
                valueRange = 0.20f..0.98f,
                enabled = config.enabled,
                colors = SliderDefaults.colors(
                    thumbColor = activeColor,
                    activeTrackColor = activeColor,
                    disabledThumbColor = AcousticPebbleColors.Outline,
                    disabledActiveTrackColor = AcousticPebbleColors.OutlineVariant
                )
            )
        }

        // Peak Reconstruct Ratio Slider
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "山頂復元ヘッドルーム比率 (Hermite Crest Ratio)",
                    color = AcousticPebbleColors.OnSurface,
                    fontSize = 11.sp
                )
                Text(
                    text = String.format("x%.2f", config.maxReconstructRatio),
                    color = cyanAccent,
                    fontSize = 11.sp,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = config.maxReconstructRatio,
                onValueChange = { onConfigChange(config.copy(maxReconstructRatio = it)) },
                valueRange = 1.10f..2.00f,
                enabled = config.enabled,
                colors = SliderDefaults.colors(
                    thumbColor = cyanAccent,
                    activeTrackColor = cyanAccent,
                    disabledThumbColor = AcousticPebbleColors.Outline,
                    disabledActiveTrackColor = AcousticPebbleColors.OutlineVariant
                )
            )
        }
    }
}
