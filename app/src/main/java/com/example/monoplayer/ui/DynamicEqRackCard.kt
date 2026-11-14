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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.dsp.DynamicBandConfig
import com.example.monoplayer.dsp.DynamicBandType
import com.example.monoplayer.dsp.DynamicEqConfig
import com.example.monoplayer.dsp.ThreeBandDynamicEq
import com.example.monoplayer.dsp.VisualizerTelemetry
import kotlin.math.pow

@Composable
fun DynamicEqRackCard(
    config: DynamicEqConfig,
    telemetry: VisualizerTelemetry,
    onConfigChange: (DynamicEqConfig) -> Unit,
    onPresetSelect: (String) -> Unit
) {
    var selectedBand by remember { mutableStateOf(DynamicBandType.LOW) }
    val zeroDeltas = remember { floatArrayOf(0f, 0f, 0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AcousticPebbleColors.SurfaceContainerLow)
            .border(1.dp, AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header + Master Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "3バンド・ダイナミックEQ (LOW / MID / HIGH)",
                    color = AcousticPebbleColors.OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "プリセット: ${config.presetName}  (TPT SVF ゼロ位相歪み動的制御)",
                    color = AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { onConfigChange(config.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AcousticPebbleColors.Primary,
                    checkedTrackColor = AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.45f)
                )
            )
        }

        // Presets Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DynamicEqConfig.PRESETS.keys.forEach { presetName ->
                val isSelected = config.presetName == presetName
                FilterChip(
                    selected = isSelected,
                    onClick = { onPresetSelect(presetName) },
                    label = { Text(presetName, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AcousticPebbleColors.SurfaceContainerHigh,
                        selectedLabelColor = AcousticPebbleColors.PrimaryContainer
                    )
                )
            }
        }

        // Live Dynamic Frequency Response Curve (20Hz .. 20kHz)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AcousticPebbleColors.SurfaceContainerLowest)
                .border(1.dp, AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(10.dp))
                .padding(8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val midY = h * 0.5f

                // 0 dB center grid line & +-6 dB lines
                drawLine(AcousticPebbleColors.OutlineVariant, Offset(0f, midY), Offset(w, midY), strokeWidth = 1.2f)
                drawLine(AcousticPebbleColors.SurfaceContainerHigh, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
                drawLine(AcousticPebbleColors.SurfaceContainerHigh, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)

                val points = 64
                val staticPath = Path()
                val dynamicPath = Path()
                val fillPath = Path()

                for (i in 0 until points) {
                    val t = i.toFloat() / (points - 1)
                    val freq = 20f * (1000f).pow(t) // 20 Hz .. 20,000 Hz log scale
                    val x = t * w

                    val staticDb = ThreeBandDynamicEq.computeCompositeResponseDb(freq, config, zeroDeltas)
                    val dynDb = ThreeBandDynamicEq.computeCompositeResponseDb(freq, config, telemetry.dynEqGainDeltaDb)

                    val yStatic = (midY - (staticDb / 14f) * (h * 0.45f)).coerceIn(2f, h - 2f)
                    val yDyn = (midY - (dynDb / 14f) * (h * 0.45f)).coerceIn(2f, h - 2f)

                    if (i == 0) {
                        staticPath.moveTo(x, yStatic)
                        dynamicPath.moveTo(x, yDyn)
                        fillPath.moveTo(x, midY)
                        fillPath.lineTo(x, yDyn)
                    } else {
                        staticPath.lineTo(x, yStatic)
                        dynamicPath.lineTo(x, yDyn)
                        fillPath.lineTo(x, yDyn)
                    }
                }
                fillPath.lineTo(w, midY)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.22f),
                            AcousticPebbleColors.TertiaryContainer.copy(alpha = 0.08f),
                            AcousticPebbleColors.Secondary.copy(alpha = 0.22f)
                        )
                    )
                )
                drawPath(
                    path = staticPath,
                    color = AcousticPebbleColors.OnSurfaceVariant.copy(alpha = 0.65f),
                    style = Stroke(width = 1.5f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = dynamicPath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(AcousticPebbleColors.Secondary, AcousticPebbleColors.TertiaryContainer, AcousticPebbleColors.PrimaryContainer)
                    ),
                    style = Stroke(width = 3.0f, cap = StrokeCap.Round)
                )
            }

            Text(
                text = "20Hz ──────── LIVE DYNAMIC CURVE (±14dB) ──────── 20kHz",
                color = AcousticPebbleColors.OnSurfaceVariant,
                fontSize = 8.sp,
                fontFamily = PlusJakartaSansFamily,
                letterSpacing = 0.5.sp,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // LOW / MID / HIGH Band Selector Tabs with Live Gain Action Readout
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DynamicBandType.entries.forEachIndexed { idx, bandType ->
                val isSelected = selectedBand == bandType
                val bandCfg = config.band(bandType)
                val deltaDb = telemetry.dynEqGainDeltaDb.getOrElse(idx) { 0f }
                val accent = when (bandType) {
                    DynamicBandType.LOW -> AcousticPebbleColors.Secondary
                    DynamicBandType.MID -> AcousticPebbleColors.TertiaryContainer
                    DynamicBandType.HIGH -> AcousticPebbleColors.PrimaryContainer
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) accent.copy(alpha = 0.18f) else AcousticPebbleColors.SurfaceContainerLowest)
                        .border(1.2.dp, if (isSelected) accent else AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(10.dp))
                        .clickable { selectedBand = bandType }
                        .padding(vertical = 7.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${bandType.label} (${bandCfg.freqHz.toInt()}Hz)",
                        color = if (isSelected) accent else AcousticPebbleColors.OnSurface,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = PlusJakartaSansFamily,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Δ ${"%+.1f".format(deltaDb)} dB",
                        color = if (bandCfg.enabled && config.enabled) accent else AcousticPebbleColors.OnSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = PlusJakartaSansFamily,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        // Selected Band Controls
        val activeBandCfg: DynamicBandConfig = config.band(selectedBand)
        val bandAccent = when (selectedBand) {
            DynamicBandType.LOW -> AcousticPebbleColors.Secondary
            DynamicBandType.MID -> AcousticPebbleColors.TertiaryContainer
            DynamicBandType.HIGH -> AcousticPebbleColors.PrimaryContainer
        }
        val detectedDb = telemetry.dynEqDetectedDb.getOrElse(selectedBand.ordinal) { -60f }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AcousticPebbleColors.SurfaceContainerLowest)
                .border(1.dp, AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selectedBand.label} (${selectedBand.filterName})  •  入力 ${"%.1f".format(detectedDb)} dB",
                    color = bandAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp
                )
                Checkbox(
                    checked = activeBandCfg.enabled,
                    onCheckedChange = { onConfigChange(config.withBand(selectedBand, activeBandCfg.copy(enabled = it))) }
                )
            }

            DynEqSliderRow(
                label = "中心/コーナー周波数",
                valueText = "${activeBandCfg.freqHz.toInt()} Hz",
                value = activeBandCfg.freqHz,
                range = selectedBand.minFreqHz..selectedBand.maxFreqHz,
                accent = bandAccent,
                onChange = { onConfigChange(config.withBand(selectedBand, activeBandCfg.copy(freqHz = it))) }
            )

            DynEqSliderRow(
                label = "スレッショルド (動作開始音量)",
                valueText = "${"%.1f".format(activeBandCfg.thresholdDb)} dB",
                value = activeBandCfg.thresholdDb,
                range = -48f..0f,
                accent = bandAccent,
                onChange = { onConfigChange(config.withBand(selectedBand, activeBandCfg.copy(thresholdDb = it))) }
            )

            val dynModeLabel = when {
                activeBandCfg.maxDynamicGainDb < -0.1f -> "動的カット"
                activeBandCfg.maxDynamicGainDb > 0.1f -> "動的ブースト"
                else -> "OFF"
            }
            DynEqSliderRow(
                label = "最大ダイナミック変化量 ($dynModeLabel)",
                valueText = "${"%+.1f".format(activeBandCfg.maxDynamicGainDb)} dB",
                value = activeBandCfg.maxDynamicGainDb,
                range = -12f..12f,
                accent = bandAccent,
                onChange = { onConfigChange(config.withBand(selectedBand, activeBandCfg.copy(maxDynamicGainDb = it))) }
            )

            DynEqSliderRow(
                label = "静的ベースゲイン (常時オフセット)",
                valueText = "${"%+.1f".format(activeBandCfg.staticGainDb)} dB",
                value = activeBandCfg.staticGainDb,
                range = -6f..6f,
                accent = bandAccent,
                onChange = { onConfigChange(config.withBand(selectedBand, activeBandCfg.copy(staticGainDb = it))) }
            )

            if (selectedBand == DynamicBandType.MID) {
                DynEqSliderRow(
                    label = "Q (帯域幅の鋭さ)",
                    valueText = "${"%.2f".format(activeBandCfg.q)}",
                    value = activeBandCfg.q,
                    range = 0.5f..4.0f,
                    accent = bandAccent,
                    onChange = { onConfigChange(config.withBand(selectedBand, activeBandCfg.copy(q = it))) }
                )
            }
        }
    }
}

@Composable
private fun DynEqSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AcousticPebbleColors.OnSurface, fontSize = 11.sp)
        Text(
            valueText,
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
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = AcousticPebbleColors.SurfaceContainerHigh
        ),
        modifier = Modifier.height(24.dp)
    )
}
