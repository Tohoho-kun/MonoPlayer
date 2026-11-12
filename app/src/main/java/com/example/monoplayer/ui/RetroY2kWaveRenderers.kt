package com.example.monoplayer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal class Y2kFireState(val cols: Int = 64, val rows: Int = 42) {
    val heat = IntArray(cols * rows)
    private val rng = Random(2000)

    fun step(specNorm: FloatArray, bassDrive: Float) {
        // Seed bottom 2 rows from 32-band spectrum + bass bursts
        val bottomRow = (rows - 1) * cols
        val secondBottom = (rows - 2) * cols
        for (x in 0 until cols) {
            val bandIdx = (x * 31) / (cols - 1)
            val v = specNorm[bandIdx].coerceIn(0f, 1f)
            val jitter = rng.nextInt(-22, 34)
            val energy = ((v * 235f) + (bassDrive * 55f) + jitter).roundToInt().coerceIn(0, 255)
            heat[bottomRow + x] = energy
            heat[secondBottom + x] = (energy * 9 / 10).coerceIn(0, 255)
        }

        // Propagate upward with classic DirectDraw demoscene cellular cooling
        for (y in 0 until rows - 2) {
            val rowOff = y * cols
            val below1 = (y + 1) * cols
            val below2 = (y + 2) * cols
            for (x in 0 until cols) {
                val xl = if (x > 0) x - 1 else 0
                val xr = if (x < cols - 1) x + 1 else cols - 1
                val sum = heat[below1 + xl] + heat[below1 + x] + heat[below1 + xr] + heat[below2 + x]
                val cool = rng.nextInt(4, 15)
                heat[rowOff + x] = ((sum shr 2) - cool).coerceAtLeast(0)
            }
        }
    }

    companion object {
        fun firePaletteColor(heatVal: Int): Color {
            return when {
                heatVal < 12 -> Color(0xFF030106)
                heatVal < 36 -> Color(0xFF28000A)
                heatVal < 70 -> Color(0xFF680008)
                heatVal < 110 -> Color(0xFFB80E00)
                heatVal < 150 -> Color(0xFFFF3C00)
                heatVal < 190 -> Color(0xFFFF8800)
                heatVal < 228 -> Color(0xFFFFDD00)
                else -> Color(0xFFFFFFCC)
            }
        }
    }
}

/**
 * Early-2000s Retro PC Fire & Waveform Visualizer Style.
 * Chunky low-res 256-color paletted fire buffer + blocky lime peak caps + jagged 1px wave.
 */
internal fun DrawScope.drawY2kFireStorm2000(
    fireState: Y2kFireState,
    specNorm: FloatArray,
    peaks: FloatArray,
    waveform: FloatArray
) {
    val w = size.width
    val h = size.height
    drawRect(color = Color(0xFF030106))

    val cols = fireState.cols
    val rows = fireState.rows
    val pixW = w / cols
    val pixH = h / rows

    // 1. Render chunky nearest-neighbor paletted pixel flames
    for (y in 0 until rows) {
        val rowOff = y * cols
        val py = y * pixH
        for (x in 0 until cols) {
            val hv = fireState.heat[rowOff + x]
            if (hv >= 12) {
                drawRect(
                    color = Y2kFireState.firePaletteColor(hv),
                    topLeft = Offset(x * pixW, py),
                    size = Size(pixW + 0.6f, pixH + 0.6f)
                )
            }
        }
    }

    // 2. Classic retro blocky Lime-Green / Acid-Yellow quantized spectrum & peak blocks
    val barCount = 32
    val barW = w / barCount
    for (b in 0 until barCount) {
        val bx = b * barW
        val pkY = (h * (1f - (peaks[b] * 0.86f))).coerceIn(8f, h - 8f)
        // Quantize Y to chunky pixel grid
        val snappedPkY = ((pkY / pixH).roundToInt()) * pixH
        drawRect(
            color = Color(0xFF00FF44),
            topLeft = Offset(bx + 1.5f, snappedPkY),
            size = Size((barW - 3f).coerceAtLeast(3f), pixH * 0.9f)
        )
    }

    // 3. Jagged 1.5px aliased Electric-Cyan / Acid-Green Oscilloscope ("Waves" overlay)
    if (waveform.isNotEmpty()) {
        val scopePath = Path()
        val midY = h * 0.36f
        val pts = 64
        for (i in 0 until pts) {
            val sx = (i.toFloat() / (pts - 1)) * w
            val wVal = waveform[(i * waveform.size / pts).coerceIn(0, waveform.lastIndex)]
            val sy = ((midY + wVal * h * 0.22f) / (pixH * 0.5f)).roundToInt() * (pixH * 0.5f)
            if (i == 0) scopePath.moveTo(sx, sy) else scopePath.lineTo(sx, sy)
        }
        drawPath(scopePath, color = Color(0xFF00FFCC), style = Stroke(width = 1.8f))
    }
}

internal class Y2kOrbitalState(val historySize: Int = 9, val vertices: Int = 24) {
    val radiiHistory = Array(historySize) { FloatArray(vertices) { 0.25f } }
    val angleHistory = FloatArray(historySize)
    val paletteHistory = IntArray(historySize)
    private var head = 0
    private var tick = 0

    fun pushFrame(specNorm: FloatArray, waveform: FloatArray, phase: Float, bassDrive: Float) {
        val target = radiiHistory[head]
        for (v in 0 until vertices) {
            val s = specNorm[(v * 31) / (vertices - 1)]
            val wv = if (waveform.isNotEmpty()) waveform[(v * waveform.size / vertices) % waveform.size] else 0f
            // Jagged Spikes + Spyro harmonic lobes
            val spike = if (v % 2 == 0) s * 0.58f else wv * 0.28f + bassDrive * 0.32f
            target[v] = (0.16f + spike).coerceIn(0.08f, 0.95f)
        }
        angleHistory[head] = phase * 1.9f + tick * 0.11f
        paletteHistory[head] = tick
        head = (head + 1) % historySize
        tick++
    }

    fun getSnapshot(age: Int, outRadii: FloatArray): Pair<Float, Int> {
        val idx = (head - 1 - age + historySize * 2) % historySize
        System.arraycopy(radiiHistory[idx], 0, outRadii, 0, vertices)
        return angleHistory[idx] to paletteHistory[idx]
    }
}

/**
 * Early-2000s Psychedelic Geometric Trail Visualizer Style.
 * Early-digital primary/secondary color cycling, zooming polygon feedback trails & scanlines.
 */
internal fun DrawScope.drawY2kOrbital2000(
    batteryState: Y2kOrbitalState,
    bassDrive: Float,
    highDrive: Float,
    phase: Float
) {
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    val cy = h * 0.5f
    val maxR = min(w, h) * 0.48f

    // Garish Y2K dark purple-blue feedback background
    drawRect(color = Color(0xFF080014))

    val clashingPalette = arrayOf(
        Color(0xFF00FF33), // Acid Lime
        Color(0xFFFF00CC), // Screaming Magenta
        Color(0xFFFFEE00), // Harsh Yellow
        Color(0xFFFF4400), // Hot Orange
        Color(0xFF00DDFF), // Electric Cyan
        Color(0xFF9900FF)  // Toxic Purple
    )

    val tempRadii = FloatArray(batteryState.vertices)
    // Draw oldest zooming trails first -> newest sharpest polygon on top
    for (age in (batteryState.historySize - 1) downTo 0) {
        val (baseAngle, palTick) = batteryState.getSnapshot(age, tempRadii)
        val zoomScale = 1.0f + age * (0.14f + bassDrive * 0.08f)
        val alpha = ((batteryState.historySize - age).toFloat() / batteryState.historySize) * 0.85f
        val color = clashingPalette[(palTick / 3 + age) % clashingPalette.size]

        val poly = Path()
        val verts = batteryState.vertices
        for (v in 0 until verts) {
            val ang = baseAngle + (v.toFloat() / verts) * (2f * PI.toFloat())
            val r = tempRadii[v] * maxR * zoomScale
            val px = cx + cos(ang) * r
            val py = cy + sin(ang) * r
            if (v == 0) poly.moveTo(px, py) else poly.lineTo(px, py)

            // Geometric vertex squares on recent frames
            if (age <= 1) {
                drawRect(
                    color = clashingPalette[(palTick + v) % clashingPalette.size],
                    topLeft = Offset(px - 3f, py - 3f),
                    size = Size(6f, 6f)
                )
            }
        }
        poly.close()
        drawPath(
            path = poly,
            color = color.copy(alpha = alpha.coerceIn(0.1f, 0.95f)),
            style = Stroke(width = if (age == 0) 3.0f else 1.8f),
            blendMode = BlendMode.Plus
        )
    }

    // Coarse SVGA CRT scanline raster overlay
    var sy = 0f
    while (sy < h) {
        drawLine(Color.Black.copy(alpha = 0.28f), Offset(0f, sy), Offset(w, sy), strokeWidth = 1.2f)
        sy += 4.5f
    }
}
