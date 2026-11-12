package com.example.monoplayer.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.dsp.DspStateHolder
import com.example.monoplayer.dsp.LoudnessConfig
import com.example.monoplayer.dsp.SpeedTrackingSource
import com.example.monoplayer.dsp.VisualizerTelemetry
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val GRAPH_MIN_DB = -48f
private const val GRAPH_MAX_X_DB = 0f
private const val GRAPH_MAX_Y_DB = 4f
private const val LIMITER_THRESH_DB = -1.51f // MasteringPeakLimiter::THRESHOLD_LINEAR = 0.84
private const val TRAIL_CAPACITY = 10

/**
 * Dedicated In-Car Cockpit / HUD Stage for [com.example.monoplayer.dsp.LoudnessMode.NOISY_CAR].
 *
 * Visualizes in real time:
 * 1. Compact Speedometer HUD Strip with active positioning source & Cabin compensation %.
 * 2. DAW-style Input/Output Transfer Curve & Dual-Dot Live Graph:
 *    - 1:1 Unity reference line vs shifted Boost+Limiter transfer curve
 *    - Original input dot (cyan) bouncing on 1:1 line simultaneously with
 *    - Boosted/Limited output dot (amber->coral) + virtual un-limited peak ring when GR engages
 * 3. Per-stage DSP activity strip: Loudness/Speed trim, 95Hz Bass Punch, 2.6kHz Clarity,
 *    3-Band Dynamic EQ delta, and Mastering Peak Limiter Gain Reduction (GR).
 */
@Composable
fun CarCockpitStage(
    config: LoudnessConfig,
    telemetry: VisualizerTelemetry,
    artworkBitmap: Bitmap?,
    declipFlashGlow: Float = 0f,
    dynEqEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val amberGold = Color(0xFFD4AF37)
    val cyanAccent = Color(0xFF00F5D4)
    val skyBlue = Color(0xFF38BDF8)
    val coralFlash = Color(0xFFFF5252)
    val emeraldSafe = Color(0xFF10B981)

    var syncedTelemetry by remember { mutableStateOf(telemetry) }
    val activeTelemetry = if (syncedTelemetry.timestampMs >= telemetry.timestampMs) {
        syncedTelemetry
    } else {
        telemetry
    }

    // 60fps frame-synced ballistics for crisp DAW bouncing dots & trail
    var smoothPreDb by remember { mutableFloatStateOf(-48f) }
    var smoothPostDb by remember { mutableFloatStateOf(-48f) }
    var smoothOutDb by remember { mutableFloatStateOf(-48f) }
    var smoothGrDb by remember { mutableFloatStateOf(0f) }
    var smoothCurveBoostDb by remember { mutableFloatStateOf(0f) }

    val trailPreDb = remember { FloatArray(TRAIL_CAPACITY) { -48f } }
    val trailOutDb = remember { FloatArray(TRAIL_CAPACITY) { -48f } }
    var trailHead by remember { mutableIntStateOf(0) }
    var trailAccumMs by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastFrameNs = 0L
        while (isActive) {
            withFrameNanos { nowNs ->
                val rawDt = if (lastFrameNs == 0L) 0.016f else (nowNs - lastFrameNs) / 1_000_000_000f
                lastFrameNs = nowNs
                val dt = rawDt.coerceIn(0.004f, 0.05f)

                val frame = DspStateHolder.pollSyncedTelemetry()
                syncedTelemetry = frame

                val targetPre = frame.carPreBoostDb.coerceIn(GRAPH_MIN_DB, GRAPH_MAX_X_DB)
                val targetPost = frame.carPostBoostDb.coerceIn(GRAPH_MIN_DB, 12f)
                val targetOut = frame.carOutputDb.coerceIn(GRAPH_MIN_DB, GRAPH_MAX_X_DB)
                val targetGr = frame.limiterGrDb.coerceIn(0f, 24f)

                val attAlpha = 1f - exp(-dt / 0.018f)
                val relAlpha = 1f - exp(-dt / 0.130f)

                smoothPreDb += (if (targetPre > smoothPreDb) attAlpha else relAlpha) * (targetPre - smoothPreDb)
                smoothPostDb += (if (targetPost > smoothPostDb) attAlpha else relAlpha) * (targetPost - smoothPostDb)
                smoothOutDb += (if (targetOut > smoothOutDb) attAlpha else relAlpha) * (targetOut - smoothOutDb)

                val grAlpha = if (targetGr > smoothGrDb) 1f - exp(-dt / 0.015f) else 1f - exp(-dt / 0.220f)
                smoothGrDb += grAlpha * (targetGr - smoothGrDb)

                val hasSignal = targetPre > -43f
                val targetCurveBoost = if (hasSignal) {
                    (targetPost - targetPre).coerceIn(-12f, 24f)
                } else {
                    (frame.loudnessGainDb + frame.carBassBoostDb * 0.45f + frame.carClarityBoostDb * 0.35f)
                        .coerceIn(-12f, 24f)
                }
                val curveAlpha = 1f - exp(-dt / 0.32f)
                smoothCurveBoostDb += curveAlpha * (targetCurveBoost - smoothCurveBoostDb)

                trailAccumMs += dt * 1000f
                if (trailAccumMs >= 26f) {
                    trailAccumMs = 0f
                    trailHead = (trailHead + 1) % TRAIL_CAPACITY
                    trailPreDb[trailHead] = smoothPreDb
                    trailOutDb[trailHead] = smoothOutDb
                }
            }
        }
    }

    val currentSpeed = activeTelemetry.detectedSpeedKmh
    val animatedSpeed by animateFloatAsState(
        targetValue = currentSpeed,
        animationSpec = tween(durationMillis = 350),
        label = "car_speed"
    )

    val clampedIntensity = config.carCabinIntensity.coerceIn(0f, 2f)
    val intensityPercent = clampedIntensity * 100f
    val speedGainDb = clampedIntensity * 4.4f
    val liveBassPunchDb = activeTelemetry.carBassBoostDb

    val dynEqAvgDeltaDb = remember(activeTelemetry.dynEqGainDeltaDb) {
        val arr = activeTelemetry.dynEqGainDeltaDb
        if (arr.isEmpty()) 0f else (arr[0] + arr[1] + arr[2]) / 3f
    }
    val dynEqPeakDeltaDb = remember(activeTelemetry.dynEqGainDeltaDb) {
        activeTelemetry.dynEqGainDeltaDb.maxByOrNull { abs(it) } ?: 0f
    }

    val frameBorderBrush = if (declipFlashGlow > 0.04f) {
        val a = (0.45f + declipFlashGlow * 0.55f).coerceIn(0f, 1f)
        Brush.linearGradient(
            listOf(coralFlash.copy(alpha = a), Color(0xFFFF7A00).copy(alpha = a), coralFlash.copy(alpha = a))
        )
    } else if (smoothGrDb > 0.4f) {
        val limFrac = (smoothGrDb / 6f).coerceIn(0f, 1f)
        val borderTint = lerp(amberGold, coralFlash, limFrac * 0.7f)
        Brush.linearGradient(
            listOf(borderTint.copy(alpha = 0.65f), skyBlue.copy(alpha = 0.45f), borderTint.copy(alpha = 0.65f))
        )
    } else {
        Brush.linearGradient(
            listOf(amberGold.copy(alpha = 0.55f), skyBlue.copy(alpha = 0.40f), amberGold.copy(alpha = 0.55f))
        )
    }
    val frameBorderWidth = if (declipFlashGlow > 0.04f) (1.2f + declipFlashGlow * 1.8f).dp else 1.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF171206),
                        Color(0xFF0B0F1A),
                        Color(0xFF040408)
                    )
                )
            )
            .border(width = frameBorderWidth, brush = frameBorderBrush, shape = RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Header Bar: Mode + Positioning Source + De-clip Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = amberGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "CAR CABIN DSP MONITOR",
                        color = amberGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (declipFlashGlow > 0.04f) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(coralFlash.copy(alpha = 0.25f))
                                .border(1.dp, coralFlash.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "⚡ DE-CLIP",
                                color = Color(0xFFFF8A80),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when (activeTelemetry.speedSource) {
                                    SpeedTrackingSource.GPS -> Color(0xFF0369A1).copy(alpha = 0.35f)
                                    SpeedTrackingSource.TUNNEL_IMU -> Color(0xFFB45309).copy(alpha = 0.35f)
                                    SpeedTrackingSource.STOPPED -> Color(0xFF334155).copy(alpha = 0.35f)
                                    SpeedTrackingSource.OFF -> Color(0xFF1E293B).copy(alpha = 0.35f)
                                }
                            )
                            .border(
                                1.dp,
                                when (activeTelemetry.speedSource) {
                                    SpeedTrackingSource.GPS -> skyBlue.copy(alpha = 0.6f)
                                    SpeedTrackingSource.TUNNEL_IMU -> Color(0xFFFBBF24).copy(alpha = 0.6f)
                                    SpeedTrackingSource.STOPPED -> Color(0xFF94A3B8).copy(alpha = 0.4f)
                                    SpeedTrackingSource.OFF -> Color(0xFF475569).copy(alpha = 0.4f)
                                },
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = activeTelemetry.speedSource.label,
                            color = when (activeTelemetry.speedSource) {
                                SpeedTrackingSource.GPS -> skyBlue
                                SpeedTrackingSource.TUNNEL_IMU -> Color(0xFFFBBF24)
                                SpeedTrackingSource.STOPPED -> Color(0xFFCBD5E1)
                                SpeedTrackingSource.OFF -> Color(0xFF94A3B8)
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 2. Compact Speedometer HUD Strip
            CarSpeedHeaderStrip(
                config = config,
                animatedSpeed = animatedSpeed,
                intensityPercent = intensityPercent,
                speedGainDb = speedGainDb,
                measuredLufs = activeTelemetry.measuredLufs,
                artworkBitmap = artworkBitmap,
                amberGold = amberGold,
                cyanAccent = cyanAccent,
                coralFlash = coralFlash
            )

            // 3. DAW Input / Output Limiter & Boost Transfer Curve (Hero Centerpiece)
            CarIoTransferGraph(
                smoothPreDb = smoothPreDb,
                smoothPostDb = smoothPostDb,
                smoothOutDb = smoothOutDb,
                smoothGrDb = smoothGrDb,
                curveBoostDb = smoothCurveBoostDb,
                trailPreDb = trailPreDb,
                trailOutDb = trailOutDb,
                trailHead = trailHead,
                cyanAccent = cyanAccent,
                amberGold = amberGold,
                coralFlash = coralFlash,
                emeraldSafe = emeraldSafe,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // 4. Active Car DSP Effect Breakdown Strip (5 Real-time Micro Meters)
            CarEffectBreakdownStrip(
                loudnessGainDb = activeTelemetry.loudnessGainDb,
                speedGainDb = speedGainDb,
                bassPunchDb = liveBassPunchDb,
                clarityBoostDb = activeTelemetry.carClarityBoostDb,
                dynEqEnabled = dynEqEnabled,
                dynEqAvgDeltaDb = dynEqAvgDeltaDb,
                dynEqPeakDeltaDb = dynEqPeakDeltaDb,
                limiterGrDb = smoothGrDb,
                amberGold = amberGold,
                cyanAccent = cyanAccent,
                skyBlue = skyBlue,
                coralFlash = coralFlash,
                emeraldSafe = emeraldSafe
            )
        }
    }
}

@Composable
private fun CarSpeedHeaderStrip(
    config: LoudnessConfig,
    animatedSpeed: Float,
    intensityPercent: Float,
    speedGainDb: Float,
    measuredLufs: Float,
    artworkBitmap: Bitmap?,
    amberGold: Color,
    cyanAccent: Color,
    coralFlash: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0E19))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Compact mini arc speedometer gauge
            Box(
                modifier = Modifier.size(46.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeW = 4.5.dp.toPx()
                    val arcSize = Size(size.width - strokeW, size.height - strokeW)
                    val arcOffset = Offset(strokeW / 2f, strokeW / 2f)
                    drawArc(
                        color = Color(0xFF1E293B),
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = arcOffset,
                        size = arcSize,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                    val frac = if (config.autoSpeedEnabled) {
                        (animatedSpeed / 120f).coerceIn(0f, 1f)
                    } else {
                        (intensityPercent / 200f).coerceIn(0f, 1f)
                    }
                    if (frac > 0f) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                colors = listOf(cyanAccent, amberGold, coralFlash),
                                center = Offset(size.width / 2f, size.height / 2f)
                            ),
                            startAngle = 135f,
                            sweepAngle = 270f * frac,
                            useCenter = false,
                            topLeft = arcOffset,
                            size = arcSize,
                            style = Stroke(width = strokeW, cap = StrokeCap.Round)
                        )
                    }
                }
                Text(
                    text = if (config.autoSpeedEnabled) "${animatedSpeed.toInt()}" else "${intensityPercent.toInt()}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Column {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (config.autoSpeedEnabled) "${animatedSpeed.toInt()} km/h" else "手動 ${intensityPercent.toInt()}%",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "補正 ${intensityPercent.toInt()}%",
                        color = amberGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = if (config.autoSpeedEnabled) {
                        "目標 ${config.carTargetLufs.toInt()}LUFS • 実測 ${"%.1f".format(measuredLufs)} • 係数 x${"%.2f".format(config.speedCompensationFactor)}"
                    } else {
                        "目標 ${config.carTargetLufs.toInt()}LUFS • 実測 ${"%.1f".format(measuredLufs)} • トリム +${"%.1f".format(speedGainDb)}dB"
                    },
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (artworkBitmap != null) {
            Image(
                bitmap = artworkBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(7.dp)),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * DAW-style Input vs Output Transfer Graph.
 *
 * Plots:
 * - X axis: Pre-boost original level [-48 .. 0 dBFS]
 * - Y axis: Post-DSP output level [-48 .. +4 dBFS]
 * - Diagonal 1:1 dotted reference line ("原音")
 * - Active Transfer Curve ("ブースト + リミッター") flattening safely below 0 dBFS
 * - Simultaneous live bouncing dots:
 *   1) Cyan Dot: Original input level riding on the 1:1 diagonal
 *   2) Amber/Coral Dot: Boosted & Limited output level riding on the active transfer curve
 *   3) Hollow Coral Ring + Compression beam whenever Mastering Limiter holds down clipped peaks
 */
@Composable
private fun CarIoTransferGraph(
    smoothPreDb: Float,
    smoothPostDb: Float,
    smoothOutDb: Float,
    smoothGrDb: Float,
    curveBoostDb: Float,
    trailPreDb: FloatArray,
    trailOutDb: FloatArray,
    trailHead: Int,
    cyanAccent: Color,
    amberGold: Color,
    coralFlash: Color,
    emeraldSafe: Color,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val netDeltaDb = smoothOutDb - smoothPreDb
    val isLimiting = smoothGrDb > 0.20f

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF070A12))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // Legend & Live Delta Telemetry Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LegendDotPill(
                    color = cyanAccent,
                    label = "原音 ${"%.1f".format(smoothPreDb)}dB"
                )
                LegendDotPill(
                    color = if (isLimiting) lerp(amberGold, coralFlash, (smoothGrDb / 5f).coerceIn(0f, 1f)) else amberGold,
                    label = "出力 ${"%.1f".format(smoothOutDb)}dB"
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (netDeltaDb >= 0f) cyanAccent.copy(alpha = 0.14f)
                            else Color(0xFFEC4899).copy(alpha = 0.16f)
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "GAIN ${"%+.1f".format(netDeltaDb)}dB",
                        color = if (netDeltaDb >= 0f) cyanAccent else Color(0xFFF472B6),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isLimiting) coralFlash.copy(alpha = 0.22f)
                            else emeraldSafe.copy(alpha = 0.14f)
                        )
                        .border(
                            0.8.dp,
                            if (isLimiting) coralFlash.copy(alpha = 0.55f) else emeraldSafe.copy(alpha = 0.35f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isLimiting) "GR -${"%.1f".format(smoothGrDb)}dB" else "LIMIT 待機",
                        color = if (isLimiting) Color(0xFFFF8A80) else emeraldSafe,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Main Interactive I/O Transfer Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val padLeft = 28.dp.toPx()
            val padRight = 8.dp.toPx()
            val padTop = 8.dp.toPx()
            val padBottom = 16.dp.toPx()

            val plotW = (size.width - padLeft - padRight).coerceAtLeast(40f)
            val plotH = (size.height - padTop - padBottom).coerceAtLeast(40f)

            fun xForDb(db: Float): Float {
                val frac = ((db - GRAPH_MIN_DB) / (GRAPH_MAX_X_DB - GRAPH_MIN_DB)).coerceIn(0f, 1f)
                return padLeft + frac * plotW
            }

            fun yForDb(db: Float): Float {
                val frac = ((db - GRAPH_MIN_DB) / (GRAPH_MAX_Y_DB - GRAPH_MIN_DB)).coerceIn(0f, 1f)
                return padTop + (1f - frac) * plotH
            }

            // Over-0dB Headroom Safety Band at top of graph
            val yZeroDb = yForDb(0f)
            drawRect(
                color = coralFlash.copy(alpha = 0.08f),
                topLeft = Offset(padLeft, padTop),
                size = Size(plotW, (yZeroDb - padTop).coerceAtLeast(0f))
            )

            // Plot Frame
            drawRoundRect(
                color = Color(0xFF172033),
                topLeft = Offset(padLeft, padTop),
                size = Size(plotW, plotH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )

            val axisLabelStyle = TextStyle(
                color = Color(0xFF64748B),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )

            // Gridlines (-48, -36, -24, -12, 0 dB)
            val gridDbValues = listOf(-48f, -36f, -24f, -12f, 0f)
            for (db in gridDbValues) {
                val gx = xForDb(db)
                val gy = yForDb(db)
                val isZero = db == 0f
                val gridColor = if (isZero) coralFlash.copy(alpha = 0.50f) else Color(0xFF162032)
                val gridStroke = if (isZero) 1.2.dp.toPx() else 0.8.dp.toPx()
                val dash = if (isZero) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null

                if (db > GRAPH_MIN_DB && db < GRAPH_MAX_X_DB) {
                    drawLine(
                        color = gridColor,
                        start = Offset(gx, padTop),
                        end = Offset(gx, padTop + plotH),
                        strokeWidth = gridStroke
                    )
                }
                if (db > GRAPH_MIN_DB) {
                    drawLine(
                        color = gridColor,
                        start = Offset(padLeft, gy),
                        end = Offset(padLeft + plotW, gy),
                        strokeWidth = gridStroke,
                        pathEffect = dash
                    )
                }

                // Y-axis tick text
                val yLabel = if (isZero) "0dB" else "${db.toInt()}"
                val yMeasured = textMeasurer.measure(
                    text = yLabel,
                    style = if (isZero) axisLabelStyle.copy(color = Color(0xFFFF8A80)) else axisLabelStyle
                )
                drawText(
                    textLayoutResult = yMeasured,
                    topLeft = Offset(
                        (padLeft - yMeasured.size.width - 4.dp.toPx()).coerceAtLeast(0f),
                        (gy - yMeasured.size.height / 2f).coerceIn(0f, size.height - yMeasured.size.height)
                    )
                )

                // X-axis tick text
                if (db == -36f || db == -24f || db == -12f || db == 0f) {
                    val xLabel = if (isZero) "0dB" else "${db.toInt()}"
                    val xMeasured = textMeasurer.measure(xLabel, style = axisLabelStyle)
                    drawText(
                        textLayoutResult = xMeasured,
                        topLeft = Offset(
                            (gx - xMeasured.size.width / 2f).coerceIn(padLeft, size.width - xMeasured.size.width),
                            padTop + plotH + 3.dp.toPx()
                        )
                    )
                }
            }

            // Limiter Threshold horizontal guide (-1.5 dBFS = linear 0.84)
            val yLimit = yForDb(LIMITER_THRESH_DB)
            drawLine(
                color = amberGold.copy(alpha = 0.55f),
                start = Offset(padLeft, yLimit),
                end = Offset(padLeft + plotW, yLimit),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
            )
            val limTag = textMeasurer.measure(
                text = "LIMIT -1.5dB",
                style = TextStyle(
                    color = amberGold.copy(alpha = 0.85f),
                    fontSize = 7.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            )
            drawText(
                textLayoutResult = limTag,
                topLeft = Offset(padLeft + 5.dp.toPx(), yLimit + 2.dp.toPx())
            )

            // 1:1 Unity Diagonal Reference (Original = Output)
            drawLine(
                color = Color(0xFF475569).copy(alpha = 0.75f),
                start = Offset(xForDb(GRAPH_MIN_DB), yForDb(GRAPH_MIN_DB)),
                end = Offset(xForDb(GRAPH_MAX_X_DB), yForDb(GRAPH_MAX_X_DB)),
                strokeWidth = 1.1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )

            // Build Active Boost + Mastering Limiter Transfer Curve Path + Fill
            val steps = 48
            val curvePath = Path()
            val fillPath = Path()
            fillPath.moveTo(xForDb(GRAPH_MIN_DB), yForDb(GRAPH_MIN_DB))

            for (i in 0..steps) {
                val xDb = GRAPH_MIN_DB + (i.toFloat() / steps) * (GRAPH_MAX_X_DB - GRAPH_MIN_DB)
                val yDb = transferCurveOutputDb(xDb, curveBoostDb)
                val px = xForDb(xDb)
                val py = yForDb(yDb)
                if (i == 0) {
                    curvePath.moveTo(px, py)
                } else {
                    curvePath.lineTo(px, py)
                }
                fillPath.lineTo(px, py)
            }
            // Close fill area back along 1:1 diagonal from right to left
            fillPath.lineTo(xForDb(GRAPH_MAX_X_DB), yForDb(GRAPH_MAX_X_DB))
            fillPath.lineTo(xForDb(GRAPH_MIN_DB), yForDb(GRAPH_MIN_DB))
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        coralFlash.copy(alpha = 0.16f),
                        amberGold.copy(alpha = 0.13f),
                        cyanAccent.copy(alpha = 0.06f)
                    ),
                    startY = yZeroDb,
                    endY = padTop + plotH
                )
            )
            drawPath(
                path = curvePath,
                brush = Brush.horizontalGradient(
                    colors = listOf(cyanAccent.copy(alpha = 0.75f), amberGold, coralFlash),
                    startX = padLeft,
                    endX = padLeft + plotW
                ),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Motion trail for bouncing output dot ("ぴょんぴょん跳ね動く" persistence)
            for (k in 0 until TRAIL_CAPACITY) {
                val ageIndex = (trailHead - k + TRAIL_CAPACITY) % TRAIL_CAPACITY
                val tPre = trailPreDb[ageIndex]
                val tOut = trailOutDb[ageIndex]
                if (tPre <= GRAPH_MIN_DB + 1.5f) continue
                val fade = (1f - k.toFloat() / TRAIL_CAPACITY).coerceIn(0f, 1f)
                drawCircle(
                    color = amberGold.copy(alpha = 0.28f * fade * fade),
                    radius = (1.8f + 2.4f * fade).dp.toPx(),
                    center = Offset(xForDb(tPre), yForDb(tOut))
                )
            }

            // Live Dual Bouncing Points & Effect Vector
            val curXDb = smoothPreDb.coerceIn(GRAPH_MIN_DB, GRAPH_MAX_X_DB)
            val curUnityYDb = curXDb
            val curOutYDb = smoothOutDb.coerceIn(GRAPH_MIN_DB, GRAPH_MAX_X_DB)
            val curPostYDb = smoothPostDb.coerceIn(GRAPH_MIN_DB, GRAPH_MAX_Y_DB - 0.15f)

            val cx = xForDb(curXDb)
            val cyUnity = yForDb(curUnityYDb)
            val cyOut = yForDb(curOutYDb)
            val cyPost = yForDb(curPostYDb)

            // Vertical crosshair ticks to axes for clarity
            drawLine(
                color = cyanAccent.copy(alpha = 0.18f),
                start = Offset(cx, max(cyUnity, cyOut)),
                end = Offset(cx, padTop + plotH),
                strokeWidth = 0.8.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )

            // Boost Lift Vector (Original -> Final Output)
            if (abs(cyUnity - cyOut) > 2f) {
                val liftColor = if (curOutYDb >= curUnityYDb) amberGold else Color(0xFFF472B6)
                drawLine(
                    color = liftColor.copy(alpha = 0.78f),
                    start = Offset(cx, cyUnity),
                    end = Offset(cx, cyOut),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Limiter Compression Beam + Virtual Un-Limited Peak Ring (when Limiting GR > 0.2dB)
            if (smoothGrDb > 0.20f || curPostYDb > curOutYDb + 0.35f) {
                drawLine(
                    color = coralFlash.copy(alpha = 0.85f),
                    start = Offset(cx, cyPost),
                    end = Offset(cx, cyOut),
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
                // Virtual peak ring showing where audio would have clipped without Limiter
                drawCircle(
                    color = coralFlash.copy(alpha = 0.80f),
                    radius = 5.5.dp.toPx(),
                    center = Offset(cx, cyPost),
                    style = Stroke(width = 1.6.dp.toPx())
                )
            }

            // Dot 1: Original Input Level (Cyan dot on 1:1 line)
            drawCircle(
                color = cyanAccent.copy(alpha = 0.22f),
                radius = 9.5.dp.toPx(),
                center = Offset(cx, cyUnity)
            )
            drawCircle(
                color = cyanAccent,
                radius = 5.dp.toPx(),
                center = Offset(cx, cyUnity)
            )
            drawCircle(
                color = Color.White,
                radius = 2.dp.toPx(),
                center = Offset(cx, cyUnity)
            )

            // Dot 2: Boosted & Limited Output Level (Amber Gold -> Coral orb)
            val outOrbColor = if (isLimiting) {
                lerp(amberGold, coralFlash, (smoothGrDb / 6f).coerceIn(0f, 1f))
            } else {
                amberGold
            }
            drawCircle(
                color = outOrbColor.copy(alpha = 0.26f),
                radius = 12.dp.toPx(),
                center = Offset(cx, cyOut)
            )
            drawCircle(
                color = outOrbColor,
                radius = 6.5.dp.toPx(),
                center = Offset(cx, cyOut)
            )
            drawCircle(
                color = Color.White,
                radius = 2.4.dp.toPx(),
                center = Offset(cx, cyOut)
            )

            // Subtle 1:1 diagonal & I/O orientation caption inside top-left margin
            val ioHint = textMeasurer.measure(
                text = "Y:出力 / X:原音",
                style = TextStyle(color = Color(0xFF475569), fontSize = 7.5.sp, fontFamily = FontFamily.Monospace)
            )
            drawText(
                textLayoutResult = ioHint,
                topLeft = Offset(padLeft + 5.dp.toPx(), padTop + 3.dp.toPx())
            )
        }
    }
}

/**
 * Computes the DAW transfer curve Y (Output dBFS) for a given input X (dBFS) and net boost dB,
 * modeling the MasteringPeakLimiter (-1.51 dBFS threshold) + Soft-Limiter knee (never exceeding 0 dBFS).
 */
private fun transferCurveOutputDb(xInDb: Float, boostDb: Float): Float {
    val boostedDb = xInDb + boostDb
    // Soft knee transition starting 3 dB below -1.51 dBFS limiter threshold up to -0.25 dBFS soft-clip ceiling
    val kneeStart = LIMITER_THRESH_DB - 3.0f
    val rawOut = if (boostedDb <= kneeStart) {
        boostedDb
    } else {
        val excess = boostedDb - kneeStart
        // Smooth asymptotic saturation toward -0.20 dBFS ceiling
        val headroom = -0.20f - kneeStart
        kneeStart + headroom * (excess / (excess + headroom))
    }
    return rawOut.coerceIn(GRAPH_MIN_DB, -0.10f)
}

@Composable
private fun LegendDotPill(
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            color = Color(0xFFE2E8F0),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun CarEffectBreakdownStrip(
    loudnessGainDb: Float,
    speedGainDb: Float,
    bassPunchDb: Float,
    clarityBoostDb: Float,
    dynEqEnabled: Boolean,
    dynEqAvgDeltaDb: Float,
    dynEqPeakDeltaDb: Float,
    limiterGrDb: Float,
    amberGold: Color,
    cyanAccent: Color,
    skyBlue: Color,
    coralFlash: Color,
    emeraldSafe: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF080C16))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Wideband Loudness + Speed Gain
        EffectMiniMeter(
            title = "音量+車速",
            valueText = "${"%+.1f".format(loudnessGainDb)}dB",
            subText = "速+${"%.1f".format(speedGainDb)}",
            fraction = ((loudnessGainDb + 6f) / 24f).coerceIn(0f, 1f),
            barColor = if (loudnessGainDb >= 0f) cyanAccent else Color(0xFFF472B6),
            modifier = Modifier.weight(1f)
        )

        // 2. 95Hz Road-noise Bass Punch
        EffectMiniMeter(
            title = "低域95Hz",
            valueText = "+${"%.1f".format(bassPunchDb)}dB",
            subText = "ロードノイズ",
            fraction = (bassPunchDb / 8.5f).coerceIn(0f, 1f),
            barColor = skyBlue,
            modifier = Modifier.weight(1f)
        )

        // 3. 2.6kHz Vocal Clarity
        EffectMiniMeter(
            title = "明瞭2.6k",
            valueText = "+${"%.1f".format(clarityBoostDb)}dB",
            subText = "声マスク補償",
            fraction = (clarityBoostDb / 10.5f).coerceIn(0f, 1f),
            barColor = amberGold,
            modifier = Modifier.weight(1f)
        )

        // 4. 3-Band Dynamic EQ
        EffectMiniMeter(
            title = "Dyn EQ",
            valueText = if (dynEqEnabled) "${"%+.1f".format(dynEqPeakDeltaDb)}dB" else "OFF",
            subText = if (dynEqEnabled) "平均${"%+.1f".format(dynEqAvgDeltaDb)}" else "バイパス",
            fraction = if (dynEqEnabled) (abs(dynEqPeakDeltaDb) / 8f).coerceIn(0f, 1f) else 0f,
            barColor = if (dynEqEnabled) Color(0xFFA78BFA) else Color(0xFF475569),
            dimmed = !dynEqEnabled,
            modifier = Modifier.weight(1f)
        )

        // 5. Mastering Peak Limiter GR
        val grActive = limiterGrDb > 0.2f
        EffectMiniMeter(
            title = "LIMIT保護",
            valueText = if (grActive) "-${"%.1f".format(limiterGrDb)}dB" else "SAFE",
            subText = if (grActive) "音割れ抑制" else "歪み 0%",
            fraction = if (grActive) (limiterGrDb / 9f).coerceIn(0f, 1f) else 0.12f,
            barColor = if (grActive) lerp(amberGold, coralFlash, (limiterGrDb / 5f).coerceIn(0f, 1f)) else emeraldSafe,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun EffectMiniMeter(
    title: String,
    valueText: String,
    subText: String,
    fraction: Float,
    barColor: Color,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0E1422))
            .padding(horizontal = 5.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = if (dimmed) Color(0xFF475569) else Color(0xFF94A3B8),
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Text(
            text = valueText,
            color = if (dimmed) Color(0xFF64748B) else barColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(3.dp))
        // Progress bar track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF1E293B))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subText,
            color = Color(0xFF475569),
            fontSize = 7.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
