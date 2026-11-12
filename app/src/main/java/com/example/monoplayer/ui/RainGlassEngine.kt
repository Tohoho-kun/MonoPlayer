package com.example.monoplayer.ui

import android.graphics.Bitmap
import com.example.monoplayer.dsp.RustNativeBridge
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Photorealistic optical simulation of rain falling and clinging/sliding on a front glass pane
 * with the album jacket defocused by condensation behind it.
 *
 * Core optical & fluid features:
 * - 3-pass O(W*H) separable box blur (~Gaussian) for wet condensation color bleed ("滲み").
 * - Stick-slip gravity raindrops with asymmetric 3D teardrop profile, meandering, coalescence,
 *   wiped clear condensation trails, and Plateau-Rayleigh satellite droplets.
 * - Per-pixel 3D central-difference surface normals, Snell's-law UV refraction of the sharp jacket,
 *   Schlick Fresnel rim darkening/reflection, lower-belly focused caustics, and pinpoint specular glints.
 * - High-speed slanted exterior falling rain streaks ("降る雨の軌跡") with depth parallax.
 */
class RainGlassEngine(
    val width: Int = 260,
    val height: Int = 260,
    seed: Int = 199907
) {
    data class ExteriorStreak(
        var x: Float,
        var y: Float,
        var speed: Float,
        var length: Float,
        var slantX: Float,
        var alpha: Float,
        var thickness: Float
    )

    internal class SlidingDrop(
        var x: Float = 0f,
        var y: Float = 0f,
        var r: Float = 3.0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var sliding: Boolean = false,
        var pinThreshold: Float = 5.3f,
        var frictionPatchPhase: Float = 0f,
        var meanderPhase: Float = 0f,
        var trailEmitAccum: Float = 0f
    )

    internal class MicroBead(
        var x: Float = 0f,
        var y: Float = 0f,
        var r: Float = 1.8f,
        var active: Boolean = true
    )

    companion object {
        const val GPU_BEAD_GRID_CELLS = 24
        const val GPU_BEAD_TEX_WIDTH = GPU_BEAD_GRID_CELLS + 1 // +1 col for color-space calibration texel
        const val GPU_BEAD_TEX_HEIGHT = GPU_BEAD_GRID_CELLS
    }

    private val rng = Random(seed)
    private val pixelCount = width * height
    val simScale: Float = if (min(width, height) > 260) min(width, height) / 256f else 1f

    val sharpPixels = IntArray(pixelCount)
    val fogPixels = IntArray(pixelCount)
    val outPixels = IntArray(pixelCount)
    private val blurTemp = IntArray(pixelCount)

    val fogMap = FloatArray(pixelCount) { 0.86f }
    val waterHeight = FloatArray(pixelCount)

    // Sparse collector drops (born small on glass, only 1..3 sliding at once) + rich micro-bead field
    internal val drops = Array(10) { SlidingDrop() }
    internal val beads = Array(220) { MicroBead() }
    private val cellOccupancy = BooleanArray(GPU_BEAD_GRID_CELLS * GPU_BEAD_GRID_CELLS)
    val exteriorStreaks = Array(52) {
        ExteriorStreak(0f, 0f, 1.8f, 0.16f, 0.03f, 0.2f, 1.2f)
    }

    private var lastArtworkRef: Bitmap? = null
    private var initializedSource = false
    private var simTimeSec = 0f

    init {
        resetParticles()
        installProceduralNightJacket()
    }

    private fun resetParticles() {
        cellOccupancy.fill(false)
        for (i in beads.indices) {
            respawnBead(beads[i])
        }
        for (i in drops.indices) {
            respawnDrop(drops[i], initialStaggerIndex = i)
        }
        for (i in exteriorStreaks.indices) {
            respawnStreak(exteriorStreaks[i], randomY = true)
        }
    }

    private fun combineSessileRadius(r1: Float, r2: Float, factor: Float = 1f, maxR: Float = 7.5f * simScale): Float {
        if (RustNativeBridge.isAvailable) {
            return RustNativeBridge.nativeCombineSessileRadius(r1, r2, factor, maxR)
        }
        // Sessile droplet wetting-radius power law (p = 2.3) for realistic glass contact-area coalescence
        val v1 = r1.toDouble().pow(2.3)
        val v2 = r2.toDouble().pow(2.3) * factor.toDouble()
        return (v1 + v2).pow(1.0 / 2.3).toFloat().coerceAtMost(maxR)
    }

    private fun respawnDrop(d: SlidingDrop, initialStaggerIndex: Int = -1) {
        val s = simScale
        // Nucleate ON the glass near existing small droplets so it grows by coalescing with neighbors
        val anchorBead = beads[rng.nextInt(beads.size)]
        if (anchorBead.active && initialStaggerIndex < 0) {
            d.x = anchorBead.x.coerceIn(12f * s, width - 12f * s)
            d.y = (anchorBead.y * 0.75f + 8f * s).coerceIn(8f * s, height * 0.68f)
        } else {
            d.x = rng.nextFloat() * (width - 28f * s) + 14f * s
            d.y = rng.nextFloat() * (height * 0.68f) + 10f * s
        }
        d.pinThreshold = (4.75f + rng.nextFloat() * 0.75f) * s // Critical breakaway radius ~4.75..5.50 units
        d.r = when {
            initialStaggerIndex in 0..1 -> d.pinThreshold - (0.06f + rng.nextFloat() * 0.18f) * s
            initialStaggerIndex in 2..4 -> (3.4f + rng.nextFloat() * 0.9f) * s
            else -> (2.45f + rng.nextFloat() * 0.85f) * s
        }
        d.vx = 0f
        d.vy = 0f
        d.sliding = false
        d.frictionPatchPhase = rng.nextFloat() * 6.283f
        d.meanderPhase = rng.nextFloat() * 6.283f
        d.trailEmitAccum = 0f
    }

    private fun respawnBead(b: MicroBead) {
        val s = simScale
        val marginX = 4f * s
        val marginY = 4f * s
        // Prefer an unoccupied cell (up to 3 tries) so beads distribute cleanly for GPU cellular lookup
        var chosenX = rng.nextFloat() * (width - 2f * marginX) + marginX
        var chosenY = rng.nextFloat() * (height - 2f * marginY) + marginY
        for (attempt in 0 until 3) {
            val candX = rng.nextFloat() * (width - 2f * marginX) + marginX
            val candY = rng.nextFloat() * (height - 2f * marginY) + marginY
            val cx = ((candX / width) * GPU_BEAD_GRID_CELLS).toInt().coerceIn(0, GPU_BEAD_GRID_CELLS - 1)
            val cy = ((candY / height) * GPU_BEAD_GRID_CELLS).toInt().coerceIn(0, GPU_BEAD_GRID_CELLS - 1)
            val cIdx = cy * GPU_BEAD_GRID_CELLS + cx
            if (!cellOccupancy[cIdx] || attempt == 2) {
                cellOccupancy[cIdx] = true
                chosenX = candX
                chosenY = candY
                break
            }
        }
        b.x = chosenX
        b.y = chosenY
        // Crisp natural distribution biased toward fine/small adhered droplets (1.10..2.75 units * simScale)
        val u = rng.nextFloat()
        b.r = (1.10f + (u * u) * 1.65f) * s
        b.active = true
    }

    private fun respawnStreak(s: ExteriorStreak, randomY: Boolean) {
        val depth = 0.25f + rng.nextFloat() * 0.75f
        s.x = rng.nextFloat() * 1.18f - 0.09f
        s.y = if (randomY) rng.nextFloat() else -(0.05f + rng.nextFloat() * 0.30f)
        s.speed = 1.35f + depth * 2.25f
        s.length = 0.09f + depth * 0.19f
        s.slantX = -0.028f - depth * 0.022f
        s.alpha = 0.07f + depth * 0.19f
        s.thickness = 0.7f + depth * 1.35f
    }

    fun updateArtworkBitmap(bitmap: Bitmap?) {
        if (initializedSource && bitmap === lastArtworkRef) return
        lastArtworkRef = bitmap
        initializedSource = true

        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            installProceduralNightJacket()
            return
        }

        // Center-crop & scale bitmap into [width x height]
        val bw = bitmap.width
        val bh = bitmap.height
        val side = min(bw, bh)
        val cropX = (bw - side) / 2
        val cropY = (bh - side) / 2
        val cropped = if (cropX == 0 && cropY == 0 && bw == bh) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, cropX, cropY, side, side)
        }
        val scaled = if (cropped.width == width && cropped.height == height) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, width, height, true)
        }
        val raw = IntArray(pixelCount)
        scaled.getPixels(raw, 0, width, 0, 0, width, height)
        if (scaled !== bitmap && scaled !== cropped) scaled.recycle()
        if (cropped !== bitmap) cropped.recycle()

        setSourcePixels(raw)
    }

    /**
     * Pure-JVM pixel ingestion: prepares sharp wet-glass background and wide Gaussian-approximated
     * condensation defocus/bleed buffer ("滲み").
     */
    fun setSourcePixels(rawArgb: IntArray) {
        require(rawArgb.size == pixelCount)
        val blurRadius = (12f * simScale).toInt().coerceAtLeast(4)
        if (RustNativeBridge.isAvailable) {
            RustNativeBridge.nativeRainPrepareJacketAndFog(
                rawArgb,
                sharpPixels,
                fogPixels,
                width,
                height,
                blurRadius,
            )
            return
        }
        val cx = width * 0.5f
        val cy = height * 0.5f
        val invMaxDistSq = 1f / (cx * cx + cy * cy)

        for (y in 0 until height) {
            val dy = y - cy
            val rowOff = y * width
            for (x in 0 until width) {
                val dx = x - cx
                val vignette = (1.0f - 0.28f * (dx * dx + dy * dy) * invMaxDistSq).coerceIn(0.62f, 1.0f)
                val c = rawArgb[rowOff + x]
                val r = (((c ushr 16) and 0xFF) * 0.94f * vignette).toInt().coerceIn(0, 255)
                val g = (((c ushr 8) and 0xFF) * 0.96f * vignette).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * 1.00f * vignette).toInt().coerceIn(0, 255)
                sharpPixels[rowOff + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // 3-pass separable box blur (scaled with simScale -> wide smooth optical condensation bleed)
        System.arraycopy(sharpPixels, 0, fogPixels, 0, pixelCount)
        repeat(3) {
            boxBlurHorizontal(fogPixels, blurTemp, radius = blurRadius)
            boxBlurVertical(blurTemp, fogPixels, radius = blurRadius)
        }

        // Subtle cool condensation mist scatter & soft contrast compression on fogged glass
        for (i in 0 until pixelCount) {
            val c = fogPixels[i]
            val r = ((c ushr 16) and 0xFF)
            val g = ((c ushr 8) and 0xFF)
            val b = (c and 0xFF)
            val luma = (r * 54 + g * 183 + b * 19) shr 8
            val mr = ((r * 0.72f + luma * 0.16f + 14f)).toInt().coerceIn(0, 255)
            val mg = ((g * 0.74f + luma * 0.16f + 17f)).toInt().coerceIn(0, 255)
            val mb = ((b * 0.78f + luma * 0.16f + 24f)).toInt().coerceIn(0, 255)
            fogPixels[i] = (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
        }
    }

    private fun installProceduralNightJacket() {
        val raw = IntArray(pixelCount)
        for (y in 0 until height) {
            val ny = y.toFloat() / height
            for (x in 0 until width) {
                val nx = x.toFloat() / width
                // Moody midnight-blue, royal-purple, and warm streetlamp gold bokeh jacket fallback
                var r = 10f + 22f * (1f - ny)
                var g = 12f + 18f * ny
                var b = 32f + 46f * (1f - nx * 0.5f)

                val b1 = expFalloff(nx - 0.30f, ny - 0.34f, 0.032f)
                val b2 = expFalloff(nx - 0.72f, ny - 0.28f, 0.024f)
                val b3 = expFalloff(nx - 0.54f, ny - 0.66f, 0.045f)
                val b4 = expFalloff(nx - 0.22f, ny - 0.75f, 0.018f)

                r += b1 * 215f + b2 * 135f + b3 * 195f + b4 * 80f
                g += b1 * 158f + b2 * 52f + b3 * 132f + b4 * 125f
                b += b1 * 64f + b2 * 190f + b3 * 58f + b4 * 210f

                raw[y * width + x] = (0xFF shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
        }
        setSourcePixels(raw)
    }

    private fun expFalloff(dx: Float, dy: Float, sigmaSq: Float): Float {
        val d2 = (dx * dx + dy * dy) / sigmaSq
        return 1f / (1f + d2 + 0.48f * d2 * d2)
    }

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, radius: Int) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -radius..radius) {
                val c = src[row + k.coerceIn(0, width - 1)]
                sr += (c ushr 16) and 0xFF
                sg += (c ushr 8) and 0xFF
                sb += c and 0xFF
            }
            for (x in 0 until width) {
                dst[row + x] = (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val outIdx = (x - radius).coerceIn(0, width - 1)
                val inIdx = (x + radius + 1).coerceIn(0, width - 1)
                val cOut = src[row + outIdx]
                val cIn = src[row + inIdx]
                sr += ((cIn ushr 16) and 0xFF) - ((cOut ushr 16) and 0xFF)
                sg += ((cIn ushr 8) and 0xFF) - ((cOut ushr 8) and 0xFF)
                sb += (cIn and 0xFF) - (cOut and 0xFF)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, radius: Int) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -radius..radius) {
                val c = src[k.coerceIn(0, height - 1) * width + x]
                sr += (c ushr 16) and 0xFF
                sg += (c ushr 8) and 0xFF
                sb += c and 0xFF
            }
            for (y in 0 until height) {
                dst[y * width + x] = (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val outY = (y - radius).coerceIn(0, height - 1)
                val inY = (y + radius + 1).coerceIn(0, height - 1)
                val cOut = src[outY * width + x]
                val cIn = src[inY * width + x]
                sr += ((cIn ushr 16) and 0xFF) - ((cOut ushr 16) and 0xFF)
                sg += ((cIn ushr 8) and 0xFF) - ((cOut ushr 8) and 0xFF)
                sb += (cIn and 0xFF) - (cOut and 0xFF)
            }
        }
    }

    private fun stepPhysics(
        dt: Float,
        bassDrive: Float,
        rmsDrive: Float,
        buildCpuHeightField: Boolean = true
    ) {
        val s = simScale
        simTimeSec += dt

        // 1. Gradual condensation re-fogging on wiped glass + clear water height field when CPU rendering
        val refogRate = (0.048f + rmsDrive * 0.030f) * dt
        if (buildCpuHeightField) {
            for (i in 0 until pixelCount) {
                fogMap[i] = (fogMap[i] + refogRate).coerceAtMost(0.92f)
                waterHeight[i] = 0f
            }
        } else {
            for (i in 0 until pixelCount) {
                fogMap[i] = (fogMap[i] + refogRate).coerceAtMost(0.92f)
            }
        }

        // 2. Exterior falling rain streaks ("降る雨の軌跡")
        val speedBoost = 1.0f + rmsDrive * 0.55f + bassDrive * 0.25f
        for (st in exteriorStreaks) {
            st.y += st.speed * speedBoost * dt
            st.x += st.slantX * st.speed * speedBoost * dt
            if (st.y - st.length > 1.08f || st.x < -0.25f || st.x > 1.25f) {
                respawnStreak(st, randomY = false)
            }
        }

        // 3. Fine rain impacts landing on glass: feed adhered beads & stationary seed droplets
        val impactBudget = (2 + ((34f + rmsDrive * 22f) * dt).toInt()).coerceIn(2, 6)
        repeat(impactBudget) {
            val ix = rng.nextFloat() * (width - 12f * s) + 6f * s
            val iy = rng.nextFloat() * (height - 12f * s) + 6f * s
            val rImp = (1.25f + rng.nextFloat() * 1.25f) * s
            var absorbed = false

            for (d in drops) {
                val dx = ix - d.x
                val dy = iy - d.y
                val hitR = d.r + 4.2f * s
                if (dx * dx + dy * dy <= hitR * hitR) {
                    val newR = combineSessileRadius(d.r, rImp, factor = 0.95f, maxR = 7.5f * s)
                    val gainFrac = ((newR - d.r) / newR.coerceAtLeast(s)).coerceIn(0f, 0.35f)
                    if (!d.sliding) {
                        d.x = (d.x + dx * gainFrac * 0.8f).coerceIn(10f * s, width - 10f * s)
                        d.y = (d.y + dy * gainFrac * 0.8f + 0.18f * s).coerceIn(6f * s, height - 4f * s)
                    }
                    d.r = newR
                    absorbed = true
                    break
                }
            }

            if (!absorbed) {
                for (b in beads) {
                    if (!b.active) {
                        b.x = ix
                        b.y = iy
                        b.r = rImp.coerceAtMost(2.6f * s)
                        b.active = true
                        markCellOccupied(b.x, b.y, true)
                        absorbed = true
                        break
                    } else {
                        val dx = ix - b.x
                        val dy = iy - b.y
                        val mergeR = b.r + 2.1f * s
                        if (dx * dx + dy * dy <= mergeR * mergeR) {
                            b.r = combineSessileRadius(b.r, rImp, factor = 0.80f, maxR = 3.15f * s)
                            absorbed = true
                            break
                        }
                    }
                }
            }
        }

        // Re-nucleate swept micro-condensation beadlets gradually
        for (b in beads) {
            if (!b.active) {
                if (rng.nextFloat() < dt * 0.85f) {
                    respawnBead(b)
                }
            } else {
                val idx = b.y.toInt().coerceIn(0, height - 1) * width + b.x.toInt().coerceIn(0, width - 1)
                if (fogMap[idx] > 0.55f && b.r < 2.75f * s) {
                    b.r += 0.030f * s * dt
                }
            }
        }

        // 4. Coalescence-driven growth -> Critical depinning -> Non-linear avalanche acceleration
        val acousticUnpin = bassDrive * 0.42f * s
        for (i in drops.indices) {
            val d = drops[i]
            // Very slow mist condensation; real growth comes from absorbing small droplets
            d.r = (d.r + (0.045f + rmsDrive * 0.055f) * s * dt).coerceAtMost(7.8f * s)

            // Absorb touching adhered small droplets first (drives both stationary swelling & sliding snowballing!)
            for (b in beads) {
                if (!b.active) continue
                val dx = b.x - d.x
                val dy = b.y - d.y
                val touchR = d.r + b.r * 1.12f
                if (dx * dx + dy * dy <= touchR * touchR) {
                    b.active = false
                    markCellOccupied(b.x, b.y, false)
                    val prevR = d.r
                    val newR = combineSessileRadius(prevR, b.r, factor = 0.92f, maxR = 7.6f * s)
                    val pull = ((newR - prevR) / newR.coerceAtLeast(s)).coerceIn(0.05f, 0.38f)
                    d.r = newR
                    if (!d.sliding) {
                        d.x = (d.x + dx * pull * 1.35f).coerceIn(8f * s, width - 8f * s)
                        d.y = (d.y + dy * pull * 1.35f).coerceIn(6f * s, height - 4f * s)
                    } else {
                        // Capillary steering toward the swallowed droplet + acceleration kick
                        d.vx += dx * 3.4f
                        d.vy = (d.vy + 7.5f * s + b.r * 3.8f).coerceAtMost(235f * s)
                    }
                }
            }

            val effectivePin = d.pinThreshold - acousticUnpin
            var justBrokeLoose = false
            if (!d.sliding) {
                d.vx = 0f
                d.vy = 0f
                wipeFogCircle(d.x, d.y, d.r * 0.74f, residualFog = 0.12f)
                // Break surface tension only once grown past critical size
                if (d.r >= effectivePin) {
                    d.sliding = true
                    justBrokeLoose = true
                    d.vy = (7.5f + rng.nextFloat() * 4.5f) * s
                }
            }

            if (d.sliding && !justBrokeLoose) {
                // Contact-angle hysteresis + spatially varying glass micro-friction patches
                val patchResistance = 0.80f + 0.36f * sin((d.y / s) * 0.085f + d.frictionPatchPhase)
                val dynamicKeepRadius = effectivePin * 0.72f * patchResistance
                val excessRadius = d.r - dynamicKeepRadius

                if (excessRadius > 0f) {
                    // Non-linear gravity acceleration: starts hesitant, surges fast as mass builds
                    val excessNorm = excessRadius / s
                    val accelY = (34f + excessNorm * excessNorm * 96f + bassDrive * 22f) * s
                    d.vy = (d.vy + accelY * dt).coerceAtMost(235f * s)
                    // Viscous glass drag scales gently with speed
                    d.vy *= (1f - (0.55f * dt).coerceAtMost(0.15f))
                } else {
                    // Hit a drier patch with borderline mass: decelerate & possibly re-pin mid-pane!
                    d.vy *= (1f - (5.8f * dt).coerceAtMost(0.45f))
                    if (d.vy < 3.2f * s && d.r < effectivePin * 0.88f) {
                        d.vy = 0f
                        d.vx = 0f
                        d.sliding = false
                    }
                }
            }

            if (d.sliding) {
                val meander = sin(simTimeSec * 2.3f + d.meanderPhase + (d.y / s) * 0.065f) * (8f * s + d.vy * 0.08f)
                d.vx = d.vx * 0.82f + meander * 0.18f

                val prevY = d.y
                d.x = (d.x + d.vx * dt).coerceIn(8f * s, width - 8f * s)
                d.y += d.vy * dt

                val movedDist = abs(d.y - prevY)
                wipeFogTrail(d.x, prevY, d.y, d.r * 0.80f)

                // Thin wet film loss + Plateau-Rayleigh satellite beadlet pinch-off
                d.r = (d.r - movedDist * 0.0038f).coerceAtLeast(3.1f * s)
                d.trailEmitAccum += movedDist
                if (d.trailEmitAccum > d.r * 3.1f && d.r > 4.6f * s) {
                    d.trailEmitAccum = 0f
                    emitTrailSatelliteBead(d.x, d.y - d.r * 1.18f, (d.r * 0.25f).coerceIn(1.15f * s, 2.05f * s))
                    d.r = (d.r * 0.986f).coerceAtLeast(3.4f * s)
                }
            }

            // Coalesce if two collector drops meet -> sudden heavy surge!
            for (j in i + 1 until drops.size) {
                val o = drops[j]
                val dx = o.x - d.x
                val dy = o.y - d.y
                val mergeDist = (d.r + o.r) * 0.85f
                if (dx * dx + dy * dy < mergeDist * mergeDist) {
                    val combinedR = combineSessileRadius(d.r, o.r, factor = 1.0f, maxR = 7.8f * s)
                    if (d.r >= o.r) {
                        d.r = combinedR
                        if (o.sliding) d.sliding = true
                        d.vy = (max(d.vy, o.vy) + 18f * s).coerceAtMost(235f * s)
                        respawnDrop(o)
                    } else {
                        o.r = combinedR
                        if (d.sliding) o.sliding = true
                        o.vy = (max(d.vy, o.vy) + 18f * s).coerceAtMost(235f * s)
                        respawnDrop(d)
                    }
                }
            }

            if (d.y - d.r * 1.9f > height) {
                respawnDrop(d)
            }
        }

        // 5. Splat micro-beads & asymmetric 3D teardrops onto continuous waterHeight field (CPU path)
        if (buildCpuHeightField) {
            for (b in beads) {
                if (b.active) splatSphericalCap(b.x, b.y, b.r, heightScale = 0.54f)
            }
            for (d in drops) {
                splatTeardrop(d.x, d.y, d.r, d.vy)
            }
        }
    }

    private fun markCellOccupied(px: Float, py: Float, occupied: Boolean) {
        val cx = ((px / width) * GPU_BEAD_GRID_CELLS).toInt().coerceIn(0, GPU_BEAD_GRID_CELLS - 1)
        val cy = ((py / height) * GPU_BEAD_GRID_CELLS).toInt().coerceIn(0, GPU_BEAD_GRID_CELLS - 1)
        cellOccupancy[cy * GPU_BEAD_GRID_CELLS + cx] = occupied
    }

    private fun emitTrailSatelliteBead(x: Float, y: Float, radius: Float) {
        val s = simScale
        if (y !in (4f * s)..(height - 4f * s)) return
        for (b in beads) {
            if (!b.active) {
                b.x = x.coerceIn(4f * s, width - 4f * s)
                b.y = y
                b.r = radius.coerceIn(1.25f * s, 2.8f * s)
                b.active = true
                markCellOccupied(b.x, b.y, true)
                return
            }
        }
    }

    private fun wipeFogTrail(x: Float, y0: Float, y1: Float, radius: Float) {
        val steps = (abs(y1 - y0) / (radius * 0.45f).coerceAtLeast(1.2f)).toInt().coerceIn(1, 14)
        for (s in 0..steps) {
            val t = s.toFloat() / steps.toFloat()
            val py = y0 + (y1 - y0) * t
            wipeFogCircle(x, py, radius, residualFog = 0.05f)
        }
    }

    private fun wipeFogCircle(cx: Float, cy: Float, radius: Float, residualFog: Float) {
        val xMin = (cx - radius).toInt().coerceAtLeast(0)
        val xMax = (cx + radius).toInt().coerceAtMost(width - 1)
        val yMin = (cy - radius).toInt().coerceAtLeast(0)
        val yMax = (cy + radius).toInt().coerceAtMost(height - 1)
        val invRSq = 1f / (radius * radius).coerceAtLeast(1f)
        for (y in yMin..yMax) {
            val dy = y - cy
            val row = y * width
            for (x in xMin..xMax) {
                val dx = x - cx
                val d2 = (dx * dx + dy * dy) * invRSq
                if (d2 < 1f) {
                    val edgeBlend = d2 * d2
                    val target = residualFog + (fogMap[row + x] - residualFog) * edgeBlend
                    if (target < fogMap[row + x]) fogMap[row + x] = target
                }
            }
        }
    }

    /**
     * Lightweight physics-only step for GPU AGSL native-resolution rendering (skips CPU height-map & pixel loop).
     */
    fun stepPhysicsOnly(dtSeconds: Float, bassDrive: Float, rmsDrive: Float) {
        stepPhysics(
            dt = dtSeconds.coerceIn(0.004f, 0.055f),
            bassDrive = bassDrive.coerceIn(0f, 1f),
            rmsDrive = rmsDrive.coerceIn(0f, 1f),
            buildCpuHeightField = false
        )
    }

    /**
     * Packs active small adhered droplets into a [GPU_BEAD_TEX_WIDTH]x[GPU_BEAD_TEX_HEIGHT] cellular lookup texture
     * with full opaque alpha (0xFF) and a mid-gray color-space calibration pixel at (GPU_BEAD_GRID_CELLS, 0).
     */
    fun packGpuBeadGrid(outTexels: IntArray) {
        require(outTexels.size == GPU_BEAD_TEX_WIDTH * GPU_BEAD_TEX_HEIGHT)
        outTexels.fill(0xFF000000.toInt())
        outTexels[GPU_BEAD_GRID_CELLS] = (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128

        val invW = 1f / width.toFloat()
        val invH = 1f / height.toFloat()
        for (b in beads) {
            if (!b.active) continue
            val ux = (b.x * invW).coerceIn(0.001f, 0.999f)
            val uy = (b.y * invH).coerceIn(0.001f, 0.999f)
            val cellX = (ux * GPU_BEAD_GRID_CELLS).toInt().coerceIn(0, GPU_BEAD_GRID_CELLS - 1)
            val cellY = (uy * GPU_BEAD_GRID_CELLS).toInt().coerceIn(0, GPU_BEAD_TEX_HEIGHT - 1)
            val r256 = (b.r / simScale).coerceIn(0f, 4.0f)
            val rByte = (r256 * (255f / 4.0f) + 0.5f).toInt().coerceIn(1, 255)
            val idx = cellY * GPU_BEAD_TEX_WIDTH + cellX
            if ((outTexels[idx] and 0xFF) < rByte) {
                val oxByte = ((ux * GPU_BEAD_GRID_CELLS - cellX) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val oyByte = ((uy * GPU_BEAD_GRID_CELLS - cellY) * 255f + 0.5f).toInt().coerceIn(0, 255)
                outTexels[idx] = (0xFF shl 24) or (oxByte shl 16) or (oyByte shl 8) or rByte
            }
        }
    }

    fun packGpuFogMask(outTexels: IntArray) {
        require(outTexels.size == pixelCount)
        for (i in 0 until pixelCount) {
            val f = (fogMap[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            outTexels[i] = (0xFF shl 24) or (f shl 16) or (f shl 8) or f
        }
    }

    fun packGpuDrops(outFloats40: FloatArray) {
        require(outFloats40.size >= drops.size * 4)
        val invW = 1f / width.toFloat()
        val invH = 1f / height.toFloat()
        for (i in drops.indices) {
            val d = drops[i]
            val base = i * 4
            outFloats40[base] = d.x * invW
            outFloats40[base + 1] = d.y * invH
            outFloats40[base + 2] = (d.r / simScale) / 256f
            outFloats40[base + 3] = (d.vy / (150f * simScale)).coerceIn(0f, 1.05f)
        }
    }

    private fun splatSphericalCap(cx: Float, cy: Float, r: Float, heightScale: Float) {
        val xMin = (cx - r).toInt().coerceAtLeast(1)
        val xMax = (cx + r).toInt().coerceAtMost(width - 2)
        val yMin = (cy - r).toInt().coerceAtLeast(1)
        val yMax = (cy + r).toInt().coerceAtMost(height - 2)
        val invRSq = 1f / (r * r).coerceAtLeast(0.5f)
        for (y in yMin..yMax) {
            val dy = y - cy
            val row = y * width
            for (x in xMin..xMax) {
                val dx = x - cx
                val u2 = (dx * dx + dy * dy) * invRSq
                if (u2 < 1f) {
                    // Finite-contact-angle sessile spherical cap (clean non-singular rim gradient)
                    val cap = (sqrt(1f - 0.86f * u2) - 0.3741657f) * 1.597866f
                    val h = cap * heightScale
                    val cur = waterHeight[row + x]
                    waterHeight[row + x] = max(cur, h) + min(cur, h) * 0.22f
                }
            }
        }
    }

    private fun splatTeardrop(cx: Float, cy: Float, r: Float, vy: Float) {
        val s = simScale
        val speedNorm = (vy / (150f * s)).coerceIn(0f, 1.05f)
        val stretchUp = 1.0f + speedNorm
        val taper = 0.08f + speedNorm * 0.30f
        val yTop = (cy - r * stretchUp).toInt().coerceAtLeast(1)
        val yBot = (cy + r * 1.05f).toInt().coerceAtMost(height - 2)
        val xMin = (cx - r * 1.04f).toInt().coerceAtLeast(1)
        val xMax = (cx + r * 1.04f).toInt().coerceAtMost(width - 2)
        val baseHeight = 0.52f + ((r / s) - 1.5f).coerceAtLeast(0f) * 0.068f

        for (y in yTop..yBot) {
            val rawDy = y - cy
            val normDy = if (rawDy < 0f) rawDy / (r * stretchUp) else rawDy / (r * 1.03f)
            // Stationary drops stay round/plump; accelerating drops taper into a sleek teardrop tail
            val widthFactor = if (normDy < 0f) (1f + normDy * taper).coerceAtLeast(0.36f) else 1.02f
            val effR = r * widthFactor
            val invEffRSq = 1f / (effR * effR).coerceAtLeast(0.5f)
            val bellyBias = (1.0f + normDy * (0.14f + speedNorm * 0.16f)).coerceIn(0.60f, 1.30f)
            val row = y * width
            for (x in xMin..xMax) {
                val dx = x - cx
                val u2 = (dx * dx) * invEffRSq + normDy * normDy
                if (u2 < 1f) {
                    val cap = (sqrt(1f - 0.86f * u2) - 0.3741657f) * 1.597866f
                    val h = cap * bellyBias * baseHeight
                    val cur = waterHeight[row + x]
                    waterHeight[row + x] = max(cur, h) + min(cur, h) * 0.26f
                }
            }
        }
    }

    /**
     * Advances simulation by [dtSeconds] and renders refracted + condensation-blurred pixels into [outPixels].
     */
    fun stepAndRenderPixels(
        dtSeconds: Float,
        bassDrive: Float,
        rmsDrive: Float,
        highDrive: Float
    ): IntArray {
        val dt = dtSeconds.coerceIn(0.004f, 0.055f)
        stepPhysics(
            dt = dt,
            bassDrive = bassDrive.coerceIn(0f, 1f),
            rmsDrive = rmsDrive.coerceIn(0f, 1f)
        )

        if (RustNativeBridge.isAvailable) {
            RustNativeBridge.nativeRainRenderRefractedFrame(
                sharpPixels,
                fogPixels,
                fogMap,
                waterHeight,
                outPixels,
                width,
                height,
                simScale,
                highDrive,
            )
            return outPixels
        }

        // Normalized primary overhead club/streetlamp light L1 & cool sky rim light L2
        val l1x = -0.36f
        val l1y = -0.66f
        val l1z = 0.66f
        val l2x = 0.44f
        val l2y = -0.48f
        val l2z = 0.76f
        val glintBoost = 228f + highDrive.coerceIn(0f, 1f) * 38f

        for (y in 0 until height) {
            val row = y * width
            val yPrev = max(0, y - 1) * width
            val yNext = min(height - 1, y + 1) * width
            for (x in 0 until width) {
                val i = row + x
                val h = waterHeight[i]
                if (h <= 0.004f) {
                    val fog = (fogMap[i] * 256f).toInt().coerceIn(0, 256)
                    val invFog = 256 - fog
                    val cs = sharpPixels[i]
                    val cf = fogPixels[i]
                    val r = (((cs ushr 16) and 0xFF) * invFog + ((cf ushr 16) and 0xFF) * fog) shr 8
                    val g = (((cs ushr 8) and 0xFF) * invFog + ((cf ushr 8) and 0xFF) * fog) shr 8
                    val b = ((cs and 0xFF) * invFog + (cf and 0xFF) * fog) shr 8
                    outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    val xPrev = max(0, x - 1)
                    val xNext = min(width - 1, x + 1)
                    val gradScale = 1.95f * simScale
                    val dhdx = (waterHeight[row + xNext] - waterHeight[row + xPrev]) * gradScale
                    val dhdy = (waterHeight[yNext + x] - waterHeight[yPrev + x]) * gradScale
                    val invLen = 1f / sqrt(dhdx * dhdx + dhdy * dhdy + 1f)
                    val nx = -dhdx * invLen
                    val ny = -dhdy * invLen
                    val nz = invLen

                    // Sub-pixel bilinear Snell refraction with subtle water chromatic dispersion
                    val refractScale = (18.5f + h * 27.5f) * simScale
                    val offX = nx * refractScale
                    val offY = ny * refractScale
                    var rr = sampleChannelBilinear(x - offX * 0.972f, y - offY * 0.972f, 16)
                    var rg = sampleChannelBilinear(x - offX, y - offY, 8)
                    var rb = sampleChannelBilinear(x - offX * 1.032f, y - offY * 1.032f, 0)

                    // Schlick Fresnel grazing-angle rim darkening + subtle night-sky reflection
                    val oneMinusCos = (1f - nz).coerceIn(0f, 1f)
                    val fresnel = oneMinusCos * oneMinusCos * oneMinusCos
                    val rimDarken = 1f - fresnel * 0.56f
                    rr = rr * rimDarken + fresnel * 48f
                    rg = rg * rimDarken + fresnel * 58f
                    rb = rb * rimDarken + fresnel * 82f

                    // Lower-belly focused light caustic crescent
                    if (ny > 0.12f && nz > 0.52f) {
                        val caustic = (ny - 0.12f) * (nz - 0.52f) * 1.38f
                        rr *= (1f + caustic)
                        rg *= (1f + caustic * 0.98f)
                        rb *= (1f + caustic * 0.94f)
                    }

                    // Razor-sharp d1^64 pinpoint primary glint + soft secondary sky sheen
                    val d1 = (nx * l1x + ny * l1y + nz * l1z).coerceAtLeast(0f)
                    val d1_4 = d1 * d1 * d1 * d1
                    val d1_16 = d1_4 * d1_4 * d1_4 * d1_4
                    val d1_32 = d1_16 * d1_16
                    val spec1 = d1_32 * d1_32 * (glintBoost * 1.08f)

                    val d2 = (nx * l2x + ny * l2y + nz * l2z).coerceAtLeast(0f)
                    val d2_4 = d2 * d2 * d2 * d2
                    val spec2 = d2_4 * d2_4 * d2_4 * 62f

                    val specTotalR = spec1 + spec2 * 0.82f
                    val specTotalG = spec1 * 0.98f + spec2 * 0.92f
                    val specTotalB = spec1 * 0.94f + spec2 * 1.12f

                    // Smooth thin-film meniscus anti-aliasing into surrounding glass state
                    val edgeAlpha = (h / (0.042f / simScale.coerceAtLeast(1f))).coerceIn(0f, 1f)
                    if (edgeAlpha < 0.99f) {
                        val fog = (fogMap[i] * 256f).toInt().coerceIn(0, 256)
                        val invFog = 256 - fog
                        val cs = sharpPixels[i]
                        val cf = fogPixels[i]
                        val bgR = (((cs ushr 16) and 0xFF) * invFog + ((cf ushr 16) and 0xFF) * fog) shr 8
                        val bgG = (((cs ushr 8) and 0xFF) * invFog + ((cf ushr 8) and 0xFF) * fog) shr 8
                        val bgB = ((cs and 0xFF) * invFog + (cf and 0xFF) * fog) shr 8
                        rr = bgR * (1f - edgeAlpha) + (rr + specTotalR) * edgeAlpha
                        rg = bgG * (1f - edgeAlpha) + (rg + specTotalG) * edgeAlpha
                        rb = bgB * (1f - edgeAlpha) + (rb + specTotalB) * edgeAlpha
                    } else {
                        rr += specTotalR
                        rg += specTotalG
                        rb += specTotalB
                    }

                    outPixels[i] = (0xFF shl 24) or
                        (rr.toInt().coerceIn(0, 255) shl 16) or
                        (rg.toInt().coerceIn(0, 255) shl 8) or
                        rb.toInt().coerceIn(0, 255)
                }
            }
        }
        return outPixels
    }

    private fun sampleChannelBilinear(fx: Float, fy: Float, shift: Int): Float {
        val cx = fx.coerceIn(0f, (width - 1).toFloat())
        val cy = fy.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val tx = cx - x0
        val ty = cy - y0
        val row0 = y0 * width
        val row1 = y1 * width
        val c00 = (sharpPixels[row0 + x0] ushr shift) and 0xFF
        val c10 = (sharpPixels[row0 + x1] ushr shift) and 0xFF
        val c01 = (sharpPixels[row1 + x0] ushr shift) and 0xFF
        val c11 = (sharpPixels[row1 + x1] ushr shift) and 0xFF
        val top = c00 + (c10 - c00) * tx
        val bot = c01 + (c11 - c01) * tx
        return top + (bot - top) * ty
    }

    fun renderToBitmap(
        targetBitmap: Bitmap,
        dtSeconds: Float,
        bassDrive: Float,
        rmsDrive: Float,
        highDrive: Float
    ) {
        val pixels = stepAndRenderPixels(dtSeconds, bassDrive, rmsDrive, highDrive)
        targetBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
