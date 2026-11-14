package com.example.monoplayer.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.dsp.LoudnessConfig
import com.example.monoplayer.dsp.LoudnessMode
import com.example.monoplayer.dsp.SpeedTrackingSource
import com.example.monoplayer.dsp.VehicleSpeedTracker
import com.example.monoplayer.dsp.VisualizerTelemetry

@Composable
fun LoudnessNormalizerCard(
    config: LoudnessConfig,
    telemetry: VisualizerTelemetry,
    onConfigChange: (LoudnessConfig) -> Unit
) {
    val context = LocalContext.current
    var hasLocationPerm by remember {
        mutableStateOf(VehicleSpeedTracker.hasLocationPermission(context))
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasLocationPerm = perms.values.any { it }
        if (hasLocationPerm) {
            onConfigChange(config.copy(autoSpeedEnabled = true))
        } else {
            onConfigChange(config.copy(autoSpeedEnabled = false))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AcousticPebbleColors.SurfaceContainerLow)
            .border(1.dp, AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(14.dp))
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
                    text = "リアルタイム聴覚ラウドネス・ノーマライザー",
                    color = AcousticPebbleColors.OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "ITU-R BS.1770-4 K-Weighting 即時解析 / 43ms先読みピーク保護",
                    color = AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 10.sp
                )
            }
            val gateColor = if (telemetry.loudnessGateFrozen) AcousticPebbleColors.TertiaryContainer else AcousticPebbleColors.PrimaryContainer
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(gateColor.copy(alpha = 0.16f))
                    .border(1.dp, gateColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (telemetry.loudnessGateFrozen) "GATE HOLD" else "TRACKING",
                    color = gateColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // 3-Mode Selector Cards (OFF / NORMAL_HIFI / NOISY_CAR)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LoudnessMode.entries.forEach { mode ->
                val selected = config.mode == mode
                val accent = when (mode) {
                    LoudnessMode.OFF -> AcousticPebbleColors.OnSurfaceVariant
                    LoudnessMode.NORMAL_HIFI -> AcousticPebbleColors.PrimaryContainer
                    LoudnessMode.NOISY_CAR -> AcousticPebbleColors.TertiaryContainer
                }
                val icon = when (mode) {
                    LoudnessMode.OFF -> Icons.Default.PowerSettingsNew
                    LoudnessMode.NORMAL_HIFI -> Icons.Default.Headphones
                    LoudnessMode.NOISY_CAR -> Icons.Default.DirectionsCar
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) accent.copy(alpha = 0.18f) else AcousticPebbleColors.SurfaceContainerLowest)
                        .border(1.2.dp, if (selected) accent else AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(10.dp))
                        .clickable { onConfigChange(config.copy(mode = mode)) }
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(icon, contentDescription = mode.title, tint = if (selected) accent else AcousticPebbleColors.OnSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        text = mode.shortBadge,
                        color = if (selected) accent else AcousticPebbleColors.OnSurface,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = PlusJakartaSansFamily,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        Text(
            text = config.mode.subtitle,
            color = AcousticPebbleColors.OnSurfaceVariant,
            fontSize = 11.sp
        )

        // Live LUFS & Bidirectional Normalization Gain Meter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AcousticPebbleColors.SurfaceContainerLowest)
                .border(1.dp, AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "計測: ${"%.1f".format(telemetry.measuredLufs)} LUFS",
                    color = AcousticPebbleColors.Primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "補正ゲイン: ${"%+.1f".format(telemetry.loudnessGainDb)} dB",
                    color = if (config.mode == LoudnessMode.NOISY_CAR) AcousticPebbleColors.TertiaryContainer else AcousticPebbleColors.PrimaryContainer,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp
                )
            }

            Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
                val midX = size.width * 0.5f
                drawRoundRect(
                    color = AcousticPebbleColors.SurfaceContainerHigh,
                    size = size,
                    cornerRadius = CornerRadius(6f, 6f)
                )
                val gainFrac = (telemetry.loudnessGainDb / 18f).coerceIn(-1f, 1f)
                if (gainFrac >= 0f) {
                    val barW = gainFrac * (size.width * 0.5f)
                    drawRoundRect(
                        color = AcousticPebbleColors.PrimaryContainer,
                        topLeft = Offset(midX, 0f),
                        size = Size(barW, size.height),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                } else {
                    val barW = -gainFrac * (size.width * 0.5f)
                    drawRoundRect(
                        color = AcousticPebbleColors.Error,
                        topLeft = Offset(midX - barW, 0f),
                        size = Size(barW, size.height),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                }
                drawLine(AcousticPebbleColors.OnSurface.copy(alpha = 0.6f), Offset(midX, 0f), Offset(midX, size.height), strokeWidth = 2f)
            }

            if (config.mode == LoudnessMode.NOISY_CAR) {
                Text(
                    text = "車内ロードノイズ補償: 低域パンチ補強 ON  •  声帯域サポート +${"%.1f".format(telemetry.carClarityBoostDb)} dB (2.6kHz)",
                    color = AcousticPebbleColors.TertiaryContainer,
                    fontSize = 10.sp,
                    fontFamily = PlusJakartaSansFamily,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // Mode-specific Controls
        if (config.mode == LoudnessMode.NORMAL_HIFI) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("目標ラウドネス (Hi-Fi 基準)", color = AcousticPebbleColors.OnSurface, fontSize = 12.sp)
                Text("${"%.1f".format(config.normalTargetLufs)} LUFS", color = AcousticPebbleColors.PrimaryContainer, fontSize = 12.sp, fontFamily = PlusJakartaSansFamily, letterSpacing = 0.5.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.normalTargetLufs,
                onValueChange = { onConfigChange(config.copy(normalTargetLufs = it)) },
                valueRange = -20f..-9f,
                colors = SliderDefaults.colors(thumbColor = AcousticPebbleColors.PrimaryContainer, activeTrackColor = AcousticPebbleColors.PrimaryContainer)
            )
        } else if (config.mode == LoudnessMode.NOISY_CAR) {
            // Target LUFS (Idle Headroom Baseline) for Car Mode
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("車内ベース音量 (停車基準・余裕確保)", color = AcousticPebbleColors.OnSurface, fontSize = 12.sp)
                Text("${"%.1f".format(config.carTargetLufs)} LUFS", color = AcousticPebbleColors.TertiaryContainer, fontSize = 12.sp, fontFamily = PlusJakartaSansFamily, letterSpacing = 0.5.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "車側の音量を大きめにし基準値を低く保つほど、車速連動アップ幅(最大+8.8dB)を音割れなく広く使えます",
                color = AcousticPebbleColors.OnSurfaceVariant,
                fontSize = 9.sp
            )
            Slider(
                value = config.carTargetLufs.coerceIn(-24f, -8f),
                onValueChange = { onConfigChange(config.copy(carTargetLufs = it)) },
                valueRange = -24f..-8f,
                colors = SliderDefaults.colors(thumbColor = AcousticPebbleColors.TertiaryContainer, activeTrackColor = AcousticPebbleColors.TertiaryContainer)
            )

            // Dedicated Vehicle Speed Compensation Panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AcousticPebbleColors.SurfaceContainerLowest)
                    .border(1.dp, AcousticPebbleColors.OutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Toggle row
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
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = if (config.autoSpeedEnabled) AcousticPebbleColors.TertiaryContainer else AcousticPebbleColors.OnSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "車速連動自動コンペンセーション",
                                color = AcousticPebbleColors.OnSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "GPS車速 + トンネル内加速度推測で強度を自動追従",
                                color = AcousticPebbleColors.OnSurfaceVariant,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Switch(
                        checked = config.autoSpeedEnabled,
                        onCheckedChange = { enable ->
                            if (enable) {
                                if (hasLocationPerm) {
                                    onConfigChange(config.copy(autoSpeedEnabled = true))
                                } else {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            } else {
                                onConfigChange(config.copy(autoSpeedEnabled = false))
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AcousticPebbleColors.Primary,
                            checkedTrackColor = AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.45f)
                        )
                    )
                }

                // Permission Warning & Request Banner (if permission missing)
                if (!hasLocationPerm) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AcousticPebbleColors.ErrorContainer.copy(alpha = 0.5f))
                            .border(1.dp, AcousticPebbleColors.Error.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = AcousticPebbleColors.Error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "車速取得には位置情報の許可が必要です",
                                color = AcousticPebbleColors.OnErrorContainer,
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AcousticPebbleColors.Error,
                                contentColor = AcousticPebbleColors.OnError
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("権限を許可", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (config.autoSpeedEnabled) {
                    val isHandheld = telemetry.speedSource == SpeedTrackingSource.HANDHELD
                    // Live Speed & Sensor Status Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isHandheld) AcousticPebbleColors.ErrorContainer.copy(alpha = 0.55f) else AcousticPebbleColors.SurfaceContainerLow)
                            .border(
                                width = if (isHandheld) 1.dp else 0.dp,
                                color = if (isHandheld) AcousticPebbleColors.Error.copy(alpha = 0.65f) else Color.Transparent,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("検知車速", color = AcousticPebbleColors.OnSurfaceVariant, fontSize = 9.sp)
                            Text(
                                text = if (isHandheld) "0.0 km/h" else "${"%.1f".format(telemetry.detectedSpeedKmh)} km/h",
                                color = if (isHandheld) AcousticPebbleColors.Error else AcousticPebbleColors.PrimaryContainer,
                                fontSize = 12.sp,
                                fontFamily = PlusJakartaSansFamily,
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column {
                            Text("測位ソース", color = AcousticPebbleColors.OnSurfaceVariant, fontSize = 9.sp)
                            Text(
                                text = telemetry.speedSource.label,
                                color = when (telemetry.speedSource) {
                                    SpeedTrackingSource.GPS -> AcousticPebbleColors.PrimaryContainer
                                    SpeedTrackingSource.TUNNEL_IMU -> AcousticPebbleColors.TertiaryContainer
                                    SpeedTrackingSource.STOPPED -> AcousticPebbleColors.Secondary
                                    SpeedTrackingSource.HANDHELD -> AcousticPebbleColors.Error
                                    SpeedTrackingSource.OFF -> AcousticPebbleColors.OnSurfaceVariant
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!isHandheld && telemetry.speedSource != SpeedTrackingSource.OFF) {
                                Text(
                                    text = if (telemetry.carForwardCalibrated) "進行軸ロック" else "進行軸学習中",
                                    color = if (telemetry.carForwardCalibrated) AcousticPebbleColors.PrimaryContainer else AcousticPebbleColors.TertiaryContainer,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Column {
                            Text("連動補正 / 車速ゲイン", color = AcousticPebbleColors.OnSurfaceVariant, fontSize = 9.sp)
                            val effectiveIntensity = config.carCabinIntensity
                            Text(
                                text = "${(effectiveIntensity * 100).toInt()}% (+${"%.1f".format(effectiveIntensity * 4.4f)} dB)",
                                color = if (isHandheld) AcousticPebbleColors.OnSurfaceVariant else AcousticPebbleColors.TertiaryContainer,
                                fontSize = 11.sp,
                                fontFamily = PlusJakartaSansFamily,
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (isHandheld) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(AcousticPebbleColors.ErrorContainer.copy(alpha = 0.35f))
                                .border(1.dp, AcousticPebbleColors.Error.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = AcousticPebbleColors.Error,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "手持ち検出中 • GPS一時停止 (固定で自動再開)",
                                color = AcousticPebbleColors.OnErrorContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Speed Compensation Factor Slider
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("音の増減係数 (車速連動感度)", color = AcousticPebbleColors.OnSurface, fontSize = 11.sp)
                            val factorLabel = when {
                                config.speedCompensationFactor > 1.25f -> "アグレッシブ"
                                config.speedCompensationFactor < 0.75f -> "マイルド"
                                else -> "標準"
                            }
                            Text(
                                text = "x${"%.2f".format(config.speedCompensationFactor)} ($factorLabel)",
                                color = AcousticPebbleColors.TertiaryContainer,
                                fontSize = 11.sp,
                                fontFamily = PlusJakartaSansFamily,
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "標準(x1.00)で最大+4.4dB・最大(x2.00)で最大+8.8dBの車速ゲイン＋低域・明瞭度を増加",
                            color = AcousticPebbleColors.OnSurfaceVariant,
                            fontSize = 9.sp
                        )
                        Slider(
                            value = config.speedCompensationFactor,
                            onValueChange = { onConfigChange(config.copy(speedCompensationFactor = it)) },
                            valueRange = 0.2f..2.0f,
                            colors = SliderDefaults.colors(thumbColor = AcousticPebbleColors.TertiaryContainer, activeTrackColor = AcousticPebbleColors.TertiaryContainer)
                        )
                    }

                    // Reference Speed Slider
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("基準巡航速度 (係数通りの最大補正に達する速度)", color = AcousticPebbleColors.OnSurface, fontSize = 11.sp)
                            Text(
                                text = "${config.speedReferenceKmh.toInt()} km/h",
                                color = AcousticPebbleColors.PrimaryContainer,
                                fontSize = 11.sp,
                                fontFamily = PlusJakartaSansFamily,
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = config.speedReferenceKmh,
                            onValueChange = { onConfigChange(config.copy(speedReferenceKmh = it)) },
                            valueRange = 50f..130f,
                            colors = SliderDefaults.colors(thumbColor = AcousticPebbleColors.PrimaryContainer, activeTrackColor = AcousticPebbleColors.PrimaryContainer)
                        )
                    }
                } else {
                    // Manual Intensity Slider when auto-speed is OFF
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("手動騒音コンペンセーション強度 (0〜200%)", color = AcousticPebbleColors.OnSurface, fontSize = 11.sp)
                            Text(
                                text = "${(config.carCabinIntensity * 100).toInt()}% (+${"%.1f".format(config.carCabinIntensity * 4.4f)} dB)",
                                color = AcousticPebbleColors.TertiaryContainer,
                                fontSize = 11.sp,
                                fontFamily = PlusJakartaSansFamily,
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = config.carCabinIntensity.coerceIn(0f, 2f),
                            onValueChange = { onConfigChange(config.copy(carCabinIntensity = it)) },
                            valueRange = 0f..2f,
                            colors = SliderDefaults.colors(thumbColor = AcousticPebbleColors.TertiaryContainer, activeTrackColor = AcousticPebbleColors.TertiaryContainer)
                        )
                    }
                }
            }
        }
    }
}
