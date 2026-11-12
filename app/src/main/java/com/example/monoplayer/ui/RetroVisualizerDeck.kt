package com.example.monoplayer.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.data.VisualizerStyle
import com.example.monoplayer.dsp.DspStateHolder
import com.example.monoplayer.dsp.VisualizerTelemetry
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.random.Random

@Composable
fun RetroVisualizerDeck(
    style: VisualizerStyle,
    onStyleChange: (VisualizerStyle) -> Unit,
    artworkBitmap: Bitmap? = null,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val smoothedSpec = remember { FloatArray(32) { 0.03f } }
    val smoothedNaive = remember { FloatArray(32) { 0.02f } }
    val peakHold = remember { FloatArray(32) { 0.05f } }
    val peakVelocity = remember { FloatArray(32) { 0f } }

    val fireState = remember { Y2kFireState() }
    val batteryState = remember { Y2kOrbitalState() }
    val rainGpuRenderer = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RainGlassGpuRenderer.createOrNull()
        } else {
            null
        }
    }
    val cpuRainRes = if (rainGpuRenderer != null) 256 else 512
    val rainEngine = remember(cpuRainRes) { RainGlassEngine(width = cpuRainRes, height = cpuRainRes) }
    val rainBitmap = remember(cpuRainRes) {
        if (rainGpuRenderer == null) {
            Bitmap.createBitmap(cpuRainRes, cpuRainRes, Bitmap.Config.ARGB_8888)
        } else {
            null
        }
    }
    var syncedTelemetry by remember { mutableStateOf(VisualizerTelemetry()) }
    var frameTick by remember { mutableIntStateOf(0) }
    var declipFlashGlow by remember { mutableFloatStateOf(0f) }
    var lastDeclipEvents by remember { mutableStateOf(0L) }

    LaunchedEffect(artworkBitmap, style) {
        if (style == VisualizerStyle.RAINY_GLASS_JACKET) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && rainGpuRenderer != null) {
                rainGpuRenderer.updateArtwork(artworkBitmap, rainEngine)
            } else {
                rainEngine.updateArtworkBitmap(artworkBitmap)
            }
        }
    }

    val particles = remember {
        val rng = Random(909)
        List(28) { i ->
            ClubParticle(
                x = rng.nextFloat(),
                y = rng.nextFloat(),
                speedFactor = 0.40f + rng.nextFloat() * 0.95f,
                radius = 1.8f + rng.nextFloat() * 3.4f,
                phaseOffset = rng.nextFloat() * 6.28f,
                colorIndex = i
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "stage_phase")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // VSync-locked 60fps loop pulling latency-compensated audio telemetry matching speaker output
    LaunchedEffect(style) {
        var subTick = 0
        var prevNanos = 0L
        while (isActive) {
            withFrameNanos { nowNanos ->
                val dtSec = if (prevNanos == 0L) {
                    1f / 60f
                } else {
                    ((nowNanos - prevNanos) / 1_000_000_000f).coerceIn(0.006f, 0.050f)
                }
                prevNanos = nowNanos

                val frame = DspStateHolder.pollSyncedTelemetry(nowNanos / 1_000_000L)
                syncedTelemetry = frame

                // Dynamic De-clipping Restoration Pulse & Flash Logic
                val newEvents = frame.declipEventsTotal > lastDeclipEvents
                if (newEvents) {
                    lastDeclipEvents = frame.declipEventsTotal
                }
                val hasActiveDeclip = frame.declipRate > 0.002f || newEvents
                val targetGlow = if (hasActiveDeclip) {
                    (frame.declipRate * 2.5f + 0.45f).coerceIn(0.40f, 1.0f)
                } else {
                    0f
                }
                declipFlashGlow = if (targetGlow > declipFlashGlow) {
                    targetGlow
                } else {
                    (declipFlashGlow - dtSec * 1.8f).coerceAtLeast(0f)
                }

                for (i in 0 until 32) {
                    val target = frame.spectrumDb.getOrElse(i) { 0f }.coerceIn(0.01f, 1f)
                    val naiveTarget = frame.naiveSpectrumDb.getOrElse(i) { 0f }.coerceIn(0.01f, 1f)

                    smoothedSpec[i] = if (target > smoothedSpec[i]) {
                        smoothedSpec[i] * 0.28f + target * 0.72f
                    } else {
                        smoothedSpec[i] * 0.80f + target * 0.20f
                    }
                    smoothedNaive[i] = smoothedNaive[i] * 0.65f + naiveTarget * 0.35f

                    if (smoothedSpec[i] >= peakHold[i]) {
                        peakHold[i] = smoothedSpec[i]
                        peakVelocity[i] = 0.0018f
                    } else {
                        peakVelocity[i] = (peakVelocity[i] + 0.0019f).coerceAtMost(0.032f)
                        peakHold[i] = (peakHold[i] - peakVelocity[i]).coerceAtLeast(smoothedSpec[i])
                    }
                }

                val bassNow = ((smoothedSpec[0] + smoothedSpec[1] + smoothedSpec[2] + smoothedSpec[3]) * 0.25f).coerceIn(0f, 1f)
                val highNow = ((smoothedSpec[24] + smoothedSpec[26] + smoothedSpec[28] + smoothedSpec[30]) * 0.25f).coerceIn(0f, 1f)
                val rmsNow = (frame.rmsLinear * 2.2f).coerceIn(0.02f, 1f)

                when (style) {
                    VisualizerStyle.RAINY_GLASS_JACKET -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && rainGpuRenderer != null) {
                            rainEngine.stepPhysicsOnly(
                                dtSeconds = dtSec,
                                bassDrive = bassNow,
                                rmsDrive = rmsNow
                            )
                            rainGpuRenderer.syncFrameFromEngine(rainEngine)
                        } else if (rainBitmap != null) {
                            rainEngine.renderToBitmap(
                                targetBitmap = rainBitmap,
                                dtSeconds = dtSec,
                                bassDrive = bassNow,
                                rmsDrive = rmsNow,
                                highDrive = highNow
                            )
                            rainBitmap.prepareToDraw()
                        }
                    }
                    VisualizerStyle.Y2K_FIRESTORM_2000 -> {
                        fireState.step(smoothedSpec, bassNow)
                    }
                    VisualizerStyle.Y2K_ORBITAL_2000 -> {
                        if (subTick % 2 == 0) {
                            batteryState.pushFrame(smoothedSpec, frame.waveform, phase, bassNow)
                        }
                    }
                    else -> Unit
                }
                subTick++
                frameTick++
            }
        }
    }

    val bassDrive = ((smoothedSpec[0] + smoothedSpec[1] + smoothedSpec[2] + smoothedSpec[3] + smoothedSpec[4]) / 5f).coerceIn(0f, 1f)
    val midDrive = ((smoothedSpec[10] + smoothedSpec[12] + smoothedSpec[14] + smoothedSpec[16]) / 4f).coerceIn(0f, 1f)
    val highDrive = ((smoothedSpec[24] + smoothedSpec[26] + smoothedSpec[28] + smoothedSpec[30]) / 4f).coerceIn(0f, 1f)
    val rmsDrive = (syncedTelemetry.rmsLinear * 2.2f).coerceIn(0.02f, 1f)
    val vuNorm = ((syncedTelemetry.vuLeftDb + 48f) / 48f).coerceIn(0.02f, 1f)

    // Dynamic De-clipping Restoration Frame Flash Brush
    val frameBorderBrush = if (declipFlashGlow > 0.04f) {
        val flashAlpha = (0.35f + declipFlashGlow * 0.65f).coerceIn(0f, 1f)
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFFF5252).copy(alpha = flashAlpha),
                Color(0xFFFF7A00).copy(alpha = flashAlpha * 0.85f),
                Color(0xFFFF1744).copy(alpha = flashAlpha)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFC8A24A).copy(alpha = 0.52f), // Antique Gold
                Color(0xFF4C1D95).copy(alpha = 0.45f), // Royal Purple
                Color(0xFF1E3A8A).copy(alpha = 0.50f)  // Deep Blue
            )
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
                        Color(0xFF150E2A), // Deep Royal Purple core
                        Color(0xFF090D1E), // Deep Midnight Blue mid
                        Color(0xFF040408)  // Obsidian Black edge
                    )
                )
            )
            .border(
                width = frameBorderWidth,
                brush = frameBorderBrush,
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read frameTick so Compose redraws Canvas at VSync rate
            val keepAlive = frameTick
            if (keepAlive >= 0) {
                // Soft neon edge glow when de-clipping occurs
                if (declipFlashGlow > 0.04f) {
                    drawRoundRect(
                        color = Color(0xFFFF5252).copy(alpha = declipFlashGlow * 0.28f),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx())
                    )
                }

                val isLuxury = style == VisualizerStyle.CLASSIC_CLUB_SPECTRUM ||
                        style == VisualizerStyle.CAR_STEREO_VFD ||
                        style == VisualizerStyle.CYBER_OSCILLOSCOPE

                if (isLuxury) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF5B21B6).copy(alpha = 0.14f + bassDrive * 0.16f),
                                Color(0xFF1E3A8A).copy(alpha = 0.10f + rmsDrive * 0.12f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.28f),
                            radius = size.minDimension * (0.58f + bassDrive * 0.18f)
                        ),
                        radius = size.minDimension * 0.78f,
                        center = Offset(size.width * 0.5f, size.height * 0.28f)
                    )
                    drawClubParticles(particles, bassDrive, highDrive, phase)
                }

                when (style) {
                    VisualizerStyle.RAINY_GLASS_JACKET -> drawRainyGlassJacket(
                        rainGpuRenderer = rainGpuRenderer,
                        rainBitmap = rainBitmap,
                        rainEngine = rainEngine,
                        highDrive = highDrive,
                        rmsDrive = rmsDrive
                    )
                    VisualizerStyle.CLASSIC_CLUB_SPECTRUM -> drawVipClubLaserAndMirror(
                        specNorm = smoothedSpec,
                        naiveNorm = smoothedNaive,
                        peaks = peakHold,
                        bassDrive = bassDrive,
                        midDrive = midDrive,
                        highDrive = highDrive,
                        rmsDrive = rmsDrive,
                        phase = phase
                    )
                    VisualizerStyle.CAR_STEREO_VFD -> drawLuxuryLoungeRadialHalo(
                        specNorm = smoothedSpec,
                        peaks = peakHold,
                        vuNorm = vuNorm,
                        rmsDrive = rmsDrive,
                        bassDrive = bassDrive,
                        midDrive = midDrive,
                        phase = phase
                    )
                    VisualizerStyle.CYBER_OSCILLOSCOPE -> drawNeonVortexAndAuroraRibbon(
                        specNorm = smoothedSpec,
                        naiveNorm = smoothedNaive,
                        waveform = syncedTelemetry.waveform,
                        bassDrive = bassDrive,
                        midDrive = midDrive,
                        highDrive = highDrive,
                        rmsDrive = rmsDrive,
                        phase = phase
                    )
                    VisualizerStyle.BUBBLE_VFD_1992 -> drawBubbleVfd1992(
                        specNorm = smoothedSpec,
                        peaks = peakHold,
                        vuNorm = vuNorm,
                        bassDrive = bassDrive,
                        phase = phase,
                        textMeasurer = textMeasurer
                    )
                    VisualizerStyle.AMBER_GREEN_LED_1995 -> drawAmberGreenLed1995(
                        specNorm = smoothedSpec,
                        peaks = peakHold,
                        waveform = syncedTelemetry.waveform,
                        vuDb = syncedTelemetry.vuLeftDb,
                        textMeasurer = textMeasurer
                    )
                    VisualizerStyle.Y2K_FIRESTORM_2000 -> drawY2kFireStorm2000(
                        fireState = fireState,
                        specNorm = smoothedSpec,
                        peaks = peakHold,
                        waveform = syncedTelemetry.waveform
                    )
                    VisualizerStyle.Y2K_ORBITAL_2000 -> drawY2kOrbital2000(
                        batteryState = batteryState,
                        bassDrive = bassDrive,
                        highDrive = highDrive,
                        phase = phase
                    )
                }
            }
        }

        // Realtime De-clipping Restoration Flash Indicator (when clipping is detected & repaired)
        if (declipFlashGlow > 0.05f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFF5252).copy(alpha = 0.22f + declipFlashGlow * 0.40f))
                    .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.50f + declipFlashGlow * 0.50f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "⚡ DE-CLIP RESTORE",
                    color = Color(0xFFFF8A80),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Discreet corner whisper pill to cycle visualizer styles without cluttering the stage
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF06050D).copy(alpha = 0.72f))
                .border(1.dp, Color(0xFFC8A24A).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .clickable { onStyleChange(style.nextStyle()) }
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text(
                text = "${style.eraTag} ▸",
                color = Color(0xFFC8A24A).copy(alpha = 0.9f),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun DrawScope.drawRainyGlassJacket(
    rainGpuRenderer: RainGlassGpuRenderer?,
    rainBitmap: Bitmap?,
    rainEngine: RainGlassEngine,
    highDrive: Float,
    rmsDrive: Float
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && rainGpuRenderer != null) {
        rainGpuRenderer.drawNativeResolution(
            nativeCanvas = drawContext.canvas.nativeCanvas,
            width = size.width,
            height = size.height,
            highDrive = highDrive
        )
    } else if (rainBitmap != null) {
        val dstW = size.width.toInt().coerceAtLeast(1)
        val dstH = size.height.toInt().coerceAtLeast(1)
        val srcW = rainBitmap.width
        val srcH = rainBitmap.height

        // Preserve 1:1 physical droplet aspect ratio via center-crop mapping
        val dstAspect = size.width / size.height.coerceAtLeast(1f)
        val cropW: Int
        val cropH: Int
        if (dstAspect >= 1f) {
            cropW = srcW
            cropH = (srcH / dstAspect).toInt().coerceIn(1, srcH)
        } else {
            cropH = srcH
            cropW = (srcW * dstAspect).toInt().coerceIn(1, srcW)
        }
        val cropX = (srcW - cropW) / 2
        val cropY = (srcH - cropH) / 2

        drawImage(
            image = rainBitmap.asImageBitmap(),
            srcOffset = IntOffset(cropX, cropY),
            srcSize = IntSize(cropW, cropH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(dstW, dstH),
            filterQuality = FilterQuality.High
        )
    }

    // High-DPI dual-layer exterior falling rain streaks ("降る雨の軌跡"): soft glow + crisp filament core
    val shimmer = 0.74f + rmsDrive * 0.28f + highDrive * 0.36f
    for (s in rainEngine.exteriorStreaks) {
        val xHead = s.x * size.width
        val yHead = s.y * size.height
        val xTail = (s.x - s.slantX * (s.length / 0.18f)) * size.width
        val yTail = (s.y - s.length) * size.height
        if (yHead < 0f || yTail > size.height) continue

        val headAlpha = (s.alpha * shimmer).coerceIn(0.03f, 0.34f)
        // Outer atmospheric motion-blur halo
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFF9EB8DC).copy(alpha = headAlpha * 0.26f),
                    Color(0xFFD6E6FF).copy(alpha = headAlpha * 0.45f)
                ),
                start = Offset(xTail, yTail),
                end = Offset(xHead, yHead)
            ),
            start = Offset(xTail, yTail),
            end = Offset(xHead, yHead),
            strokeWidth = s.thickness * 1.45f,
            cap = StrokeCap.Round
        )
        // Razor-sharp inner specular raindrop filament
        val coreTailX = xTail + (xHead - xTail) * 0.32f
        val coreTailY = yTail + (yHead - yTail) * 0.32f
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFFF4F8FF).copy(alpha = (headAlpha * 1.15f).coerceAtMost(0.46f))
                ),
                start = Offset(coreTailX, coreTailY),
                end = Offset(xHead, yHead)
            ),
            start = Offset(coreTailX, coreTailY),
            end = Offset(xHead, yHead),
            strokeWidth = (s.thickness * 0.52f).coerceAtLeast(0.75f),
            cap = StrokeCap.Round
        )
    }

    // Subtle oblique front glass-plate reflection & dark window-frame depth bevel
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFD8E4FF).copy(alpha = 0.055f),
                Color.Transparent,
                Color(0xFFC8A24A).copy(alpha = 0.032f),
                Color.Transparent,
                Color(0xFF040408).copy(alpha = 0.24f)
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )
    )
}
