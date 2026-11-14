package com.example.monoplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.dsp.DspStateHolder
import com.example.monoplayer.dsp.MonoAlgorithm
import com.example.monoplayer.dsp.TenBandEqualizer

@Composable
fun DspAndEqScreen(
    viewModel: MonoPlayerViewModel
) {
    val activeAlgo by DspStateHolder.algoFlow.collectAsState()
    val sparkleBlend by DspStateHolder.blendFlow.collectAsState()
    val crossoverHz by DspStateHolder.crossoverFlow.collectAsState()
    val preampDb by DspStateHolder.preampFlow.collectAsState()
    val eqEnabled by DspStateHolder.eqEnabledFlow.collectAsState()
    val eqBands by DspStateHolder.eqBandsFlow.collectAsState()
    val activePreset by DspStateHolder.activePresetName.collectAsState()
    val declipConfig by DspStateHolder.declipConfigFlow.collectAsState()
    val loudnessConfig by DspStateHolder.loudnessConfigFlow.collectAsState()
    val dynamicEqConfig by DspStateHolder.dynamicEqConfigFlow.collectAsState()
    val spatialConfig by DspStateHolder.spatialConfigFlow.collectAsState()
    val telemetry by DspStateHolder.telemetry.collectAsState()
    val crossfadeSec by viewModel.crossfadeSec.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 1: High-Resolution Stereo-to-Mono Algorithm Cards (from dsp.html)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F172A))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8)
                    )
                    Text(
                        text = "高音質モノラル化アルゴリズム選択 (リアルタイム切替)",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            MonoAlgorithm.entries.forEach { algo ->
                val isSelected = algo == activeAlgo
                val borderColor = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E293B)
                val bgColor = if (isSelected) Color(0xFF1E293B).copy(alpha = 0.9f) else Color(0xFF020617).copy(alpha = 0.7f)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgColor)
                        .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
                        .clickable { DspStateHolder.setMonoAlgorithm(algo) }
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (algo == MonoAlgorithm.NAIVE) Color(0xFFFB7185).copy(alpha = 0.2f)
                                        else Color(0xFF38BDF8).copy(alpha = 0.2f)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = algo.badge,
                                    color = if (algo == MonoAlgorithm.NAIVE) Color(0xFFFB7185) else Color(0xFF38BDF8),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = algo.title,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "選択中",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Text(
                        text = algo.description,
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Text(
                        text = algo.formula,
                        color = Color(0xFF38BDF8).copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Sparkle Blend & Crossover Sliders (directly from dsp.html)
            HorizontalDivider(color = Color(0xFF1E293B), modifier = Modifier.padding(vertical = 4.dp))

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "高域保護・サイド注入量 (Sparkle Blend)",
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${sparkleBlend.toInt()}%",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = sparkleBlend,
                    onValueChange = { DspStateHolder.setSparkleBlend(it) },
                    valueRange = 0f..150f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF38BDF8),
                        activeTrackColor = Color(0xFF38BDF8)
                    )
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "対象クロスオーバー周波数 (High-Pass)",
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${crossoverHz.toInt()} Hz",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = crossoverHz,
                    onValueChange = { DspStateHolder.setCrossoverFreq(it) },
                    valueRange = 500f..6000f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF38BDF8),
                        activeTrackColor = Color(0xFF38BDF8)
                    )
                )
            }
        }

        // Section 1.5: Stereo-Informed Z-Axis Mono Depth & Narrow Stereo Stage
        CollapsibleDspGroup(
            title = "空間・Z軸奥行きステージ",
            enabled = spatialConfig.depthEnabled,
            onToggle = { DspStateHolder.updateSpatialConfig(spatialConfig.copy(depthEnabled = it)) },
            presetLabel = spatialConfig.presetName
        ) {
            SpatialDepthStageCard(
                config = spatialConfig,
                telemetry = telemetry,
                onUpdateConfig = { DspStateHolder.updateSpatialConfig(it) },
                onSelectPreset = { DspStateHolder.applySpatialPreset(it) }
            )
        }

        // Section 2: Preamp Gain & Crossfade Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F172A))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "プリアンプ・ゲイン調整 & クロスフェード",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            // Preamp Gain (-12 dB .. +12 dB)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "プリアンプ・ゲイン (ソフトクリップ保護付)",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp
                )
                Text(
                    text = String.format("%+.1f dB", preampDb),
                    color = Color(0xFF34D399),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = preampDb,
                onValueChange = { DspStateHolder.setPreampGain(it) },
                valueRange = -12f..12f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF34D399),
                    activeTrackColor = Color(0xFF34D399)
                )
            )

            // Crossfade (0.0s .. 12.0s)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "曲間クロスフェード時間",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp
                )
                Text(
                    text = if (crossfadeSec <= 0.1f) "OFF (0.0 秒)" else String.format("%.1f 秒", crossfadeSec),
                    color = Color(0xFFFBBF24),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = crossfadeSec,
                onValueChange = { viewModel.setCrossfadeSeconds(it) },
                valueRange = 0f..12f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFBBF24),
                    activeTrackColor = Color(0xFFFBBF24)
                )
            )
        }

        // Section 2.3: Anti-Clipping Reconstruction & De-Clicking
        CollapsibleDspGroup(
            title = "デクリッピング・波形修復",
            enabled = declipConfig.enabled,
            onToggle = { DspStateHolder.updateDeclipConfig(declipConfig.copy(enabled = it)) }
        ) {
            DeclipRestorationCard(
                config = declipConfig,
                telemetry = telemetry,
                onConfigChange = { DspStateHolder.updateDeclipConfig(it) }
            )
        }

        // Section 2.5: On-the-Fly Psychoacoustic Loudness Normalizer (Normal Hi-Fi & Noisy Car Modes)
        CollapsibleDspGroup(
            title = "ラウドネス正規化・車載モード",
            enabled = loudnessConfig.mode != com.example.monoplayer.dsp.LoudnessMode.OFF,
            onToggle = { on ->
                val nextMode = if (on) com.example.monoplayer.dsp.LoudnessMode.NORMAL_HIFI else com.example.monoplayer.dsp.LoudnessMode.OFF
                DspStateHolder.updateLoudnessConfig(loudnessConfig.copy(mode = nextMode))
            },
            presetLabel = loudnessConfig.mode.name
        ) {
            LoudnessNormalizerCard(
                config = loudnessConfig,
                telemetry = telemetry,
                onConfigChange = { DspStateHolder.updateLoudnessConfig(it) }
            )
        }

        // Section 2.8: Low / Mid / High 3-Band Dynamic EQ
        CollapsibleDspGroup(
            title = "3バンド・ダイナミックEQ",
            enabled = dynamicEqConfig.enabled,
            onToggle = { DspStateHolder.updateDynamicEqConfig(dynamicEqConfig.copy(enabled = it)) },
            presetLabel = dynamicEqConfig.presetName
        ) {
            DynamicEqRackCard(
                config = dynamicEqConfig,
                telemetry = telemetry,
                onConfigChange = { DspStateHolder.updateDynamicEqConfig(it) },
                onPresetSelect = { DspStateHolder.applyDynamicEqPreset(it) }
            )
        }

        // Section 3: 10-Band Graphic Equalizer (31Hz - 16kHz)
        CollapsibleDspGroup(
            title = "10バンド・グラフィックEQ",
            enabled = eqEnabled,
            onToggle = { DspStateHolder.eqEnabled = it },
            presetLabel = activePreset
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F172A))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "10バンド・グラフィックイコライザー",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "プリセット: $activePreset  (-12dB 〜 +12dB)",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { DspStateHolder.applyEqPreset("Flat") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Flatリセット",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = eqEnabled,
                        onCheckedChange = { DspStateHolder.setEqualizerEnabled(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF38BDF8))
                    )
                }
            }

            // Preset Selector Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TenBandEqualizer.PRESETS.keys.forEach { presetName ->
                    val selected = activePreset == presetName
                    FilterChip(
                        selected = selected,
                        onClick = { DspStateHolder.applyEqPreset(presetName) },
                        label = { Text(presetName, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF38BDF8).copy(alpha = 0.25f),
                            selectedLabelColor = Color(0xFF38BDF8)
                        )
                    )
                }
            }

            // 10 Vertical Faders (Classic Hardware Graphic EQ Rack Style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF020617))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0 until 10) {
                    val gainDb = eqBands.getOrElse(i) { 0f }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = String.format("%+.0f", gainDb),
                            color = if (gainDb > 0.1f) Color(0xFF38BDF8)
                            else if (gainDb < -0.1f) Color(0xFFFB7185)
                            else Color(0xFF64748B),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        VerticalEqSlider(
                            value = gainDb,
                            onValueChange = { DspStateHolder.setEqBand(i, it) },
                            enabled = eqEnabled,
                            modifier = Modifier
                                .height(140.dp)
                                .width(28.dp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = TenBandEqualizer.BAND_LABELS[i],
                            color = Color(0xFFCBD5E1),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun VerticalEqSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = -12f..12f,
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = Color(0xFF38BDF8),
            activeTrackColor = Color(0xFF38BDF8),
            inactiveTrackColor = Color(0xFF1E293B)
        ),
        modifier = modifier
            .graphicsLayer {
                rotationZ = 270f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(-placeable.width, 0)
                }
            }
    )
}

@Composable
private fun CollapsibleDspGroup(
    title: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    presetLabel: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0B1120))
            .border(1.dp, if (enabled) Color(0xFF334155) else Color(0xFF1E293B), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "収納" else "展開",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = if (enabled) Color.White else Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (enabled && !expanded && !presetLabel.isNullOrBlank()) {
                        Text(
                            text = "プリセット: $presetLabel",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF38BDF8))
            )
        }
        AnimatedVisibility(visible = expanded) {
            Box(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                content()
            }
        }
    }
}
