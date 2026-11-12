package com.example.monoplayer.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 1992 Japanese "Bubble Compo" / 2DIN Car Audio VFD Style
 * Vacuum Fluorescent Display (VFD): ZnO:Zn Blue-Green, Amber & Red-Orange phosphors + cathode heater wires.
 */
internal fun DrawScope.drawBubbleVfd1992(
    specNorm: FloatArray,
    peaks: FloatArray,
    vuNorm: Float,
    bassDrive: Float,
    phase: Float,
    textMeasurer: TextMeasurer
) {
    val w = size.width
    val h = size.height

    // Smoked dark brown-black acrylic VFD vacuum glass background
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF070B0A), Color(0xFF0B0E0C), Color(0xFF050706))
        )
    )

    // Faint horizontal glowing tungsten filament cathode heater wires (signature of real VFD tubes!)
    val wireCount = 7
    for (i in 1..wireCount) {
        val wy = h * (i.toFloat() / (wireCount + 1))
        drawLine(
            color = Color(0xFF5A1E10).copy(alpha = 0.34f),
            start = Offset(0f, wy),
            end = Offset(w, wy),
            strokeWidth = 1.1f
        )
    }

    val vfdGreen = Color(0xFF28E8A8)
    val vfdAmber = Color(0xFFFFA018)
    val vfdRed = Color(0xFFFF3318)
    val ghostCell = Color(0xFF13201B)

    // Top Over-Engineered 90s Digital Status Matrix & 3D Wireframe SFC Soundfield Radar
    val topH = h * 0.25f
    val padX = w * 0.045f
    val badgeLabels = arrayOf("D.S.P", "S.F.C: JAZZ CLUB", "BBE DIGITAL", "SUPER WOOFER")
    val badgeColors = arrayOf(vfdAmber, vfdGreen, vfdRed, if (bassDrive > 0.32f) vfdRed else vfdAmber)
    var curX = padX
    for (b in badgeLabels.indices) {
        val active = b != 3 || bassDrive > 0.18f
        val c = if (active) badgeColors[b] else ghostCell
        val layout = textMeasurer.measure(
            text = badgeLabels[b],
            style = TextStyle(color = c, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
        )
        val bw = layout.size.width + 12f
        val bh = layout.size.height + 6f
        if (curX + bw < w * 0.74f) {
            drawRoundRect(
                color = c.copy(alpha = if (active) 0.14f else 0.05f),
                topLeft = Offset(curX, h * 0.045f),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(2f, 2f)
            )
            drawRoundRect(
                color = c.copy(alpha = if (active) 0.75f else 0.25f),
                topLeft = Offset(curX, h * 0.045f),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(2f, 2f),
                style = Stroke(width = 1.1f)
            )
            drawText(layout, topLeft = Offset(curX + 6f, h * 0.045f + 3f))
            curX += bw + 6f
        }
    }

    // Mini 3D Wireframe Sound Field Acoustic Grid (top-right corner)
    val radarCx = w * 0.85f
    val radarCy = h * 0.13f
    val radarR = min(w * 0.09f, topH * 0.42f)
    for (ring in 1..3) {
        val rr = radarR * (ring / 3f) * (0.85f + bassDrive * 0.25f)
        val poly = Path()
        for (v in 0..5) {
            val ang = phase * 0.7f + v * (Math.PI / 3.0).toFloat()
            val px = radarCx + cos(ang) * rr * 1.35f
            val py = radarCy + sin(ang) * rr * 0.68f
            if (v == 0) poly.moveTo(px, py) else poly.lineTo(px, py)
        }
        poly.close()
        drawPath(poly, color = if (ring == 3) vfdAmber.copy(alpha = 0.7f) else vfdGreen.copy(alpha = 0.55f), style = Stroke(1.1f))
    }

    // 16-Band Quantized Stepped VFD Spectrum (16 discrete vertical cells per column)
    val numCols = 16
    val numRows = 16
    val gridTop = h * 0.24f
    val gridBottom = h * 0.84f
    val gridH = gridBottom - gridTop
    val colW = (w - padX * 2f) / numCols
    val cellGapX = 3.5f
    val cellGapY = 2.4f
    val cellW = (colW - cellGapX).coerceAtLeast(4f)
    val cellH = ((gridH / numRows) - cellGapY).coerceAtLeast(2.5f)

    for (col in 0 until numCols) {
        val s0 = specNorm[(col * 2).coerceAtMost(31)]
        val s1 = specNorm[(col * 2 + 1).coerceAtMost(31)]
        val bandVal = (maxOf(s0, s1) * 1.08f).coerceIn(0f, 1f)
        val peakVal = (maxOf(peaks[(col * 2).coerceAtMost(31)], peaks[(col * 2 + 1).coerceAtMost(31)]) * 1.08f).coerceIn(0f, 1f)

        val litCells = (bandVal * numRows).roundToInt().coerceIn(0, numRows)
        val peakCell = (peakVal * numRows).roundToInt().coerceIn(0, numRows) - 1
        val x = padX + col * colW

        for (row in 0 until numRows) {
            val y = gridBottom - (row + 1) * (gridH / numRows)
            val isLit = row < litCells
            val isPeak = row == peakCell && peakCell >= 1

            val activeColor = when {
                row >= 13 || isPeak -> vfdRed
                row >= 10 -> vfdAmber
                else -> vfdGreen
            }

            if (isLit || isPeak) {
                // Soft VFD phosphor halo
                drawRect(
                    color = activeColor.copy(alpha = 0.24f),
                    topLeft = Offset(x - 1.5f, y - 1.2f),
                    size = Size(cellW + 3f, cellH + 2.4f),
                    blendMode = BlendMode.Plus
                )
                drawRect(
                    color = activeColor,
                    topLeft = Offset(x, y),
                    size = Size(cellW, cellH)
                )
            } else {
                // Unlit VFD anode ghost segment
                drawRect(
                    color = ghostCell,
                    topLeft = Offset(x, y),
                    size = Size(cellW, cellH)
                )
            }
        }
    }

    // Bottom Horizontal Stepped Peak Level Bar (-40dB .. 0dB .. +6dB OVER)
    val barY = h * 0.89f
    val barSegs = 28
    val segStep = (w - padX * 2f) / barSegs
    val litVuSegs = (vuNorm * barSegs).roundToInt().coerceIn(1, barSegs)
    for (s in 0 until barSegs) {
        val sx = padX + s * segStep
        val c = when {
            s >= 23 -> vfdRed
            s >= 18 -> vfdAmber
            else -> vfdGreen
        }
        drawRect(
            color = if (s < litVuSegs) c else ghostCell,
            topLeft = Offset(sx, barY),
            size = Size((segStep - 2.2f).coerceAtLeast(2f), h * 0.045f)
        )
    }
}

/**
 * 1995 Pre-Blue-LED Era: Backlit Yellow-Green STN Dot-Matrix LCD +
 * Discrete 565nm Green / 600nm Amber / 635nm Red Rectangular Epoxy LEDs.
 */
internal fun DrawScope.drawAmberGreenLed1995(
    specNorm: FloatArray,
    peaks: FloatArray,
    waveform: FloatArray,
    vuDb: Float,
    textMeasurer: TextMeasurer
) {
    val w = size.width
    val h = size.height

    // Matte dark-grey molded stereo faceplate
    drawRect(color = Color(0xFF121411))

    val padX = w * 0.045f
    // 1. Top Backlit Yellow-Green STN Passive-Matrix LCD Window
    val lcdTop = h * 0.05f
    val lcdH = h * 0.20f
    val lcdW = w - padX * 2f
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF7D9C2B), Color(0xFF8CAE34), Color(0xFF759426))
        ),
        topLeft = Offset(padX, lcdTop),
        size = Size(lcdW, lcdH),
        cornerRadius = CornerRadius(4f, 4f)
    )
    // LCD Pixel matrix scanlines
    val lcdInk = Color(0xFF1B2707)
    for (ly in 0 until 12) {
        val py = lcdTop + (ly / 12f) * lcdH
        drawLine(lcdInk.copy(alpha = 0.08f), Offset(padX, py), Offset(padX + lcdW, py), strokeWidth = 1f)
    }

    val lcdTitle = textMeasurer.measure(
        text = "MEGA BASS // DIGITAL GRAPHIC LEVEL  [${"%.0f".format(vuDb)}dB]",
        style = TextStyle(color = lcdInk, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
    )
    drawText(lcdTitle, topLeft = Offset(padX + 8f, lcdTop + 5f))

    // Chunky quantized STN LCD dot-matrix waveform inside the yellow-green LCD
    val waveCols = 44
    val waveStepX = (lcdW - 16f) / waveCols
    val waveMidY = lcdTop + lcdH * 0.67f
    for (i in 0 until waveCols) {
        val sample = if (waveform.isNotEmpty()) waveform[(i * waveform.size / waveCols).coerceIn(0, waveform.lastIndex)] else 0f
        val qStep = ((sample * 4f).roundToInt().coerceIn(-3, 3)) * (lcdH * 0.075f)
        drawRect(
            color = lcdInk.copy(alpha = 0.85f),
            topLeft = Offset(padX + 8f + i * waveStepX, waveMidY + qStep - 2f),
            size = Size((waveStepX - 1.5f).coerceAtLeast(2.5f), 4.2f)
        )
    }

    // 2. Discrete 3-Color Epoxy LED Spectrum Matrix (565nm Yellow-Green, 600nm Amber, 635nm Red)
    val cols = 14
    val rows = 14
    val ledTop = h * 0.30f
    val ledBottom = h * 0.92f
    val ledAreaH = ledBottom - ledTop
    val colStep = lcdW / cols
    val rowStep = ledAreaH / rows

    val ledGreen = Color(0xFF80F200)
    val ledAmber = Color(0xFFFF9D00)
    val ledRed = Color(0xFFFF1A00)

    for (c in 0 until cols) {
        val idx = (c * 31) / (cols - 1)
        val v = (specNorm[idx] * 1.1f).coerceIn(0f, 1f)
        val pk = (peaks[idx] * 1.1f).coerceIn(0f, 1f)
        val litCount = (v * rows).roundToInt().coerceIn(0, rows)
        val pkRow = (pk * rows).roundToInt().coerceIn(0, rows) - 1
        val lx = padX + c * colStep + 2.5f
        val lw = (colStep - 5f).coerceAtLeast(5f)
        val lh = (rowStep - 3.5f).coerceAtLeast(3f)

        for (r in 0 until rows) {
            val ly = ledBottom - (r + 1) * rowStep
            val isLit = r < litCount || (r == pkRow && pkRow >= 1)
            val baseColor = when {
                r >= 11 -> ledRed
                r >= 8 -> ledAmber
                else -> ledGreen
            }
            // Dark recessed plastic LED cavity
            drawRoundRect(
                color = if (isLit) baseColor else baseColor.copy(alpha = 0.09f),
                topLeft = Offset(lx, ly),
                size = Size(lw, lh),
                cornerRadius = CornerRadius(2f, 2f)
            )
            if (isLit) {
                // Bright internal LED semiconductor die hotspot
                drawRoundRect(
                    color = Color(0xFFFFFFB8).copy(alpha = 0.65f),
                    topLeft = Offset(lx + lw * 0.22f, ly + lh * 0.22f),
                    size = Size(lw * 0.56f, lh * 0.56f),
                    cornerRadius = CornerRadius(1.5f, 1.5f)
                )
            }
        }
    }
}
