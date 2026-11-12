package com.example.monoplayer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal data class ClubParticle(
    var x: Float,
    var y: Float,
    val speedFactor: Float,
    val radius: Float,
    val phaseOffset: Float,
    val colorIndex: Int
)

internal fun DrawScope.drawStarFlare(center: Offset, radius: Float, coreColor: Color, glowColor: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(glowColor.copy(alpha = 0.65f), Color.Transparent),
            center = center,
            radius = radius * 2.5f
        ),
        radius = radius * 2.5f,
        center = center,
        blendMode = BlendMode.Plus
    )
    drawLine(
        color = coreColor.copy(alpha = 0.9f),
        start = Offset(center.x - radius * 1.8f, center.y),
        end = Offset(center.x + radius * 1.8f, center.y),
        strokeWidth = 1.6f,
        cap = StrokeCap.Round,
        blendMode = BlendMode.Plus
    )
    drawLine(
        color = coreColor.copy(alpha = 0.85f),
        start = Offset(center.x, center.y - radius * 1.4f),
        end = Offset(center.x, center.y + radius * 1.4f),
        strokeWidth = 1.4f,
        cap = StrokeCap.Round,
        blendMode = BlendMode.Plus
    )
    drawCircle(color = Color.White, radius = max(1.6f, radius * 0.28f), center = center, blendMode = BlendMode.Plus)
}

internal fun DrawScope.drawVipClubLaserAndMirror(
    specNorm: FloatArray,
    naiveNorm: FloatArray,
    peaks: FloatArray,
    bassDrive: Float,
    midDrive: Float,
    highDrive: Float,
    rmsDrive: Float,
    phase: Float
) {
    val w = size.width
    val h = size.height
    val horizonY = h * 0.68f
    val stageTop = h * 0.14f
    val maxPillarH = horizonY - stageTop

    // 1. Overhead VIP Club Laser Fans (Additive volumetric beams)
    val laserCount = 7
    val rigOrigin = Offset(w * 0.5f, h * 0.02f)
    for (i in 0 until laserCount) {
        val ratio = (i.toFloat() / (laserCount - 1)) - 0.5f
        val sway = sin(phase * 1.4f + i * 0.9f) * w * 0.14f * (0.4f + bassDrive * 0.6f)
        val targetX = w * 0.5f + ratio * w * 1.15f + sway
        val spread = w * (0.045f + bassDrive * 0.055f)
        val laserColor = when (i % 3) {
            0 -> Color(0xFF3B66F5)
            1 -> Color(0xFFD4AF37)
            else -> Color(0xFF7C3AED)
        }
        val beamAlpha = (0.06f + rmsDrive * 0.14f + (specNorm[(i * 4) % 32] * 0.12f)).coerceAtMost(0.32f)
        val beamPath = Path().apply {
            moveTo(rigOrigin.x, rigOrigin.y)
            lineTo(targetX - spread, horizonY)
            lineTo(targetX + spread, horizonY)
            close()
        }
        drawPath(
            path = beamPath,
            brush = Brush.verticalGradient(
                colors = listOf(laserColor.copy(alpha = beamAlpha * 1.3f), laserColor.copy(alpha = beamAlpha * 0.35f), Color.Transparent),
                startY = rigOrigin.y,
                endY = horizonY
            ),
            blendMode = BlendMode.Plus
        )
    }

    // 2. Glossy Piano-Black Floor Ambient Glow
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFD4AF37).copy(alpha = 0.12f + bassDrive * 0.15f),
                Color(0xFF080510).copy(alpha = 0.92f),
                Color(0xFF020205)
            ),
            startY = horizonY,
            endY = h
        ),
        topLeft = Offset(0f, horizonY),
        size = Size(w, h - horizonY)
    )

    // 3. Sculpted Luminous Pillars, Continuous Bezier Crest, Lens-Flare Crowns & Floor Reflection
    val numBands = 32
    val padX = w * 0.045f
    val usableW = w - padX * 2f
    val stepX = usableW / numBands
    val crestPath = Path()
    val naiveCrestPath = Path()

    for (i in 0 until numBands) {
        val cx = padX + stepX * (i + 0.5f)
        val v = specNorm[i].coerceIn(0.02f, 1f)
        val nv = naiveNorm[i].coerceIn(0.01f, 1f)
        val pk = peaks[i].coerceIn(v, 1f)
        val pillarH = v * maxPillarH
        val topY = horizonY - pillarH
        val peakY = horizonY - pk * maxPillarH
        val naiveY = horizonY - nv * maxPillarH

        val t = i.toFloat() / (numBands - 1)
        val bandColor = when {
            t < 0.35f -> Color(0xFF7C3AED) // Sub-bass / Bass: Crimson Magenta
            t < 0.72f -> Color(0xFFD4AF37) // Mid / Vocal: Champagne Gold
            else -> Color(0xFF3B66F5)      // High / Air: Electric Cyan
        }

        // Outer soft bloom aura
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(bandColor.copy(alpha = 0.55f * v), bandColor.copy(alpha = 0.18f), Color.Transparent),
                startY = topY - 8f,
                endY = horizonY
            ),
            start = Offset(cx, topY),
            end = Offset(cx, horizonY),
            strokeWidth = stepX * 0.95f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus
        )

        // Sculpted neon core filament
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White, bandColor, bandColor.copy(alpha = 0.35f)),
                startY = topY,
                endY = horizonY
            ),
            start = Offset(cx, topY),
            end = Offset(cx, horizonY),
            strokeWidth = max(2.5f, stepX * 0.28f),
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus
        )

        // Mirrored floor reflection (inverted & attenuated below horizon)
        val reflectH = pillarH * 0.42f
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(bandColor.copy(alpha = 0.38f * v), Color.Transparent),
                startY = horizonY + 2f,
                endY = horizonY + reflectH + 4f
            ),
            start = Offset(cx, horizonY + 2f),
            end = Offset(cx, min(h - 4f, horizonY + reflectH)),
            strokeWidth = stepX * 0.65f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus
        )

        // Peak-hold optical star crown & comet trail
        if (pk > 0.08f) {
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.6f), Color.Transparent),
                    startY = peakY,
                    endY = min(horizonY, peakY + 18f)
                ),
                start = Offset(cx, peakY),
                end = Offset(cx, min(horizonY, peakY + 18f)),
                strokeWidth = 1.5f,
                blendMode = BlendMode.Plus
            )
            drawStarFlare(
                center = Offset(cx, peakY),
                radius = 3.2f + pk * 3.5f,
                coreColor = Color.White,
                glowColor = bandColor
            )
        }

        // Smooth Bezier crest path construction
        if (i == 0) {
            crestPath.moveTo(cx, topY)
            naiveCrestPath.moveTo(cx, naiveY)
        } else {
            val prevCx = padX + stepX * (i - 0.5f)
            val prevY = horizonY - specNorm[i - 1].coerceIn(0.02f, 1f) * maxPillarH
            val prevNaiveY = horizonY - naiveNorm[i - 1].coerceIn(0.01f, 1f) * maxPillarH
            val midX = (prevCx + cx) * 0.5f
            crestPath.cubicTo(midX, prevY, midX, topY, cx, topY)
            naiveCrestPath.cubicTo(midX, prevNaiveY, midX, naiveY, cx, naiveY)
        }
    }

    // Naive mono ghost comparison ribbon (subtle amber-gold trace)
    drawPath(
        path = naiveCrestPath,
        color = Color(0xFFD4AF37).copy(alpha = 0.32f),
        style = Stroke(width = 1.6f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus
    )

    // Active DSP glowing crest ribbon
    drawPath(
        path = crestPath,
        brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFF7C3AED), Color(0xFFD4AF37), Color(0xFF3B66F5))
        ),
        style = Stroke(width = 3.2f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus
    )

    // Horizon laser seam
    drawLine(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, Color(0xFFD4AF37).copy(alpha = 0.85f), Color(0xFF3B66F5).copy(alpha = 0.85f), Color.Transparent)
        ),
        start = Offset(0f, horizonY),
        end = Offset(w, horizonY),
        strokeWidth = 2.2f,
        blendMode = BlendMode.Plus
    )
}


internal fun DrawScope.drawLuxuryLoungeRadialHalo(
    specNorm: FloatArray,
    peaks: FloatArray,
    vuNorm: Float,
    rmsDrive: Float,
    bassDrive: Float,
    midDrive: Float,
    phase: Float
) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.5f, h * 0.48f)
    val baseRadius = min(w, h) * (0.17f + bassDrive * 0.045f)
    val maxPetalLength = min(w, h) * 0.25f

    // 1. Acoustic Shockwave Rings pulsing with Bass & RMS
    for (ring in 1..3) {
        val ringPhase = (phase * 0.65f + ring * 0.33f) % 1f
        val ringR = baseRadius + ringPhase * maxPetalLength * 1.35f
        val ringAlpha = (1f - ringPhase) * (0.14f + bassDrive * 0.28f)
        drawCircle(
            color = if (ring % 2 == 0) Color(0xFFD4AF37).copy(alpha = ringAlpha) else Color(0xFF3B66F5).copy(alpha = ringAlpha),
            radius = ringR,
            center = center,
            style = Stroke(width = 1.8f + (1f - ringPhase) * 2.5f),
            blendMode = BlendMode.Plus
        )
    }

    // 2. 64-way Mirrored Radial Acoustic Halo Petals (Smooth Bezier Crown + Radial Spokes)
    val totalPetals = 64
    val outerHaloPath = Path()
    for (i in 0 until totalPetals) {
        val bandIdx = if (i < 32) i else 63 - i
        val v = specNorm[bandIdx].coerceIn(0.04f, 1f)
        val pk = peaks[bandIdx].coerceIn(v, 1f)
        val angle = (i.toFloat() / totalPetals) * (2f * PI.toFloat()) - (PI.toFloat() * 0.5f) + sin(phase * 0.3f) * 0.12f
        val innerR = baseRadius
        val outerR = baseRadius + v * maxPetalLength
        val peakR = baseRadius + pk * maxPetalLength

        val cosA = cos(angle)
        val sinA = sin(angle)
        val pStart = Offset(center.x + cosA * innerR, center.y + sinA * innerR)
        val pEnd = Offset(center.x + cosA * outerR, center.y + sinA * outerR)
        val pPeak = Offset(center.x + cosA * peakR, center.y + sinA * peakR)

        val petalColor = when {
            bandIdx < 10 -> Color(0xFF7C3AED)
            bandIdx < 22 -> Color(0xFFD4AF37)
            else -> Color(0xFF3B66F5)
        }

        // Glow spoke
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(petalColor.copy(alpha = 0.15f), petalColor.copy(alpha = 0.75f * v), Color.White),
                start = pStart,
                end = pEnd
            ),
            start = pStart,
            end = pEnd,
            strokeWidth = 4.2f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus
        )

        if (i % 2 == 0 && pk > 0.18f) {
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 2.4f,
                center = pPeak,
                blendMode = BlendMode.Plus
            )
        }

        if (i == 0) {
            outerHaloPath.moveTo(pEnd.x, pEnd.y)
        } else {
            outerHaloPath.lineTo(pEnd.x, pEnd.y)
        }
    }
    outerHaloPath.close()

    drawPath(
        path = outerHaloPath,
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFF7C3AED), Color(0xFFD4AF37), Color(0xFF3B66F5), Color(0xFFD4AF37), Color(0xFF7C3AED)),
            center = center
        ),
        style = Stroke(width = 2.5f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus
    )

    // 3. Center Luxury Medallion & Twin Sweeping Arc VU Meters
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFFD4AF37).copy(alpha = 0.32f + rmsDrive * 0.35f),
                Color(0xFF120C1F).copy(alpha = 0.95f),
                Color(0xFF05040A)
            ),
            center = center,
            radius = baseRadius
        ),
        radius = baseRadius,
        center = center
    )

    val arcRectSize = Size(baseRadius * 1.56f, baseRadius * 1.56f)
    val arcTopLeft = Offset(center.x - arcRectSize.width * 0.5f, center.y - arcRectSize.height * 0.5f)
    val vuSweep = (vuNorm * 130f).coerceIn(8f, 130f)

    // Left & Right Sweeping Luxury VU Arcs
    drawArc(
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFF3B66F5), Color(0xFFD4AF37), Color(0xFF7C3AED)),
            center = center
        ),
        startAngle = 155f,
        sweepAngle = vuSweep,
        useCenter = false,
        topLeft = arcTopLeft,
        size = arcRectSize,
        style = Stroke(width = 6.5f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus
    )
    drawArc(
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFF7C3AED), Color(0xFFD4AF37), Color(0xFF3B66F5)),
            center = center
        ),
        startAngle = 25f,
        sweepAngle = -vuSweep,
        useCenter = false,
        topLeft = arcTopLeft,
        size = arcRectSize,
        style = Stroke(width = 6.5f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus
    )

    // Center acoustic star flare
    drawStarFlare(
        center = center,
        radius = 8f + (rmsDrive + midDrive) * 14f,
        coreColor = Color.White,
        glowColor = Color(0xFFD4AF37)
    )
}


internal fun DrawScope.drawNeonVortexAndAuroraRibbon(
    specNorm: FloatArray,
    naiveNorm: FloatArray,
    waveform: FloatArray,
    bassDrive: Float,
    midDrive: Float,
    highDrive: Float,
    rmsDrive: Float,
    phase: Float
) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.5f, h * 0.48f)
    val maxR = min(w, h) * 0.46f

    // 1. 3D Perspective Neon Tunnel Rings pulsing with Sub-Bass
    val ringCount = 6
    for (r in 0 until ringCount) {
        val progress = ((r.toFloat() / ringCount) + (phase * 0.32f)) % 1f
        val radius = progress * progress * maxR * (1.05f + bassDrive * 0.25f)
        val alpha = (1f - progress) * progress * 4f * (0.22f + bassDrive * 0.35f)
        val ringColor = when (r % 3) {
            0 -> Color(0xFF3B66F5)
            1 -> Color(0xFF7C3AED)
            else -> Color(0xFFD4AF37)
        }
        drawCircle(
            color = ringColor.copy(alpha = alpha.coerceIn(0f, 0.7f)),
            radius = radius,
            center = Offset(
                center.x + cos(phase + r) * 8f * midDrive,
                center.y + sin(phase + r) * 6f * midDrive
            ),
            style = Stroke(width = 1.5f + progress * 4.5f),
            blendMode = BlendMode.Plus
        )
    }

    // 2. Multi-Layered Smooth Bezier Aurora Ribbons (Spectrum Envelope + Time-Domain Waveform)
    val points = 64
    val ribbonPath = Path()
    val glowFillPath = Path()
    val secondaryRibbonPath = Path()
    val midY = h * 0.5f
    val ampScale = h * (0.22f + rmsDrive * 0.16f)

    for (i in 0 until points) {
        val t = i.toFloat() / (points - 1)
        val x = t * w
        val waveVal = if (waveform.isNotEmpty()) {
            waveform[(i * waveform.size / points).coerceIn(0, waveform.lastIndex)]
        } else 0f
        val specIdx = (t * 31f).toInt().coerceIn(0, 31)
        val specMod = specNorm[specIdx]
        val naiveMod = naiveNorm[specIdx]

        val y1 = midY + (waveVal * 0.65f + sin(t * 8f + phase * 2f) * specMod * 0.45f) * ampScale
        val y2 = midY - (waveVal * 0.45f - cos(t * 6f - phase * 1.6f) * naiveMod * 0.4f) * ampScale

        if (i == 0) {
            ribbonPath.moveTo(x, y1)
            glowFillPath.moveTo(x, midY)
            glowFillPath.lineTo(x, y1)
            secondaryRibbonPath.moveTo(x, y2)
        } else {
            val prevX = ((i - 1).toFloat() / (points - 1)) * w
            val ctrlX = (prevX + x) * 0.5f
            ribbonPath.quadraticTo(ctrlX, y1, x, y1)
            glowFillPath.quadraticTo(ctrlX, y1, x, y1)
            secondaryRibbonPath.quadraticTo(ctrlX, y2, x, y2)
        }
    }
    glowFillPath.lineTo(w, midY)
    glowFillPath.close()

    // Aurora Curtain Fill
    drawPath(
        path = glowFillPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF3B66F5).copy(alpha = 0.24f + highDrive * 0.2f),
                Color(0xFFD4AF37).copy(alpha = 0.08f),
                Color(0xFF7C3AED).copy(alpha = 0.24f + bassDrive * 0.2f)
            ),
            startY = midY - ampScale,
            endY = midY + ampScale
        ),
        blendMode = BlendMode.Plus
    )

    // Secondary Champagne Gold Counter-Wave
    drawPath(
        path = secondaryRibbonPath,
        brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFFD4AF37).copy(alpha = 0.45f), Color(0xFF7C3AED).copy(alpha = 0.65f), Color(0xFFD4AF37).copy(alpha = 0.45f))
        ),
        style = Stroke(width = 3.2f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus
    )

    // Primary Electric Aurora Ribbon (Outer Bloom + White-Hot Core)
    drawPath(
        path = ribbonPath,
        brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFF7C3AED), Color(0xFFD4AF37), Color(0xFF3B66F5))
        ),
        style = Stroke(width = 8.5f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus
    )
    drawPath(
        path = ribbonPath,
        color = Color.White.copy(alpha = 0.9f),
        style = Stroke(width = 2.2f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus
    )
}

internal fun DrawScope.drawClubParticles(
    particles: List<ClubParticle>,
    bassDrive: Float,
    highDrive: Float,
    phase: Float
) {
    val w = size.width
    val h = size.height
    for (p in particles) {
        val driftY = ((p.y - phase * 0.06f * p.speedFactor * (1f + bassDrive * 1.8f)) % 1f + 1f) % 1f
        val driftX = ((p.x + sin(phase * 0.8f + p.phaseOffset) * 0.035f) % 1f + 1f) % 1f
        val center = Offset(driftX * w, driftY * h)
        val pColor = when (p.colorIndex % 3) {
            0 -> Color(0xFFD4AF37)
            1 -> Color(0xFF3B66F5)
            else -> Color(0xFF7C3AED)
        }
        val radius = p.radius * (0.85f + highDrive * 1.1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.7f), pColor.copy(alpha = 0.35f), Color.Transparent),
                center = center,
                radius = radius * 2.6f
            ),
            radius = radius * 2.6f,
            center = center,
            blendMode = BlendMode.Plus
        )
    }
}
