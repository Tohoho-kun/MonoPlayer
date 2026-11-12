package com.example.monoplayer.ui

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RainGlassEngineTest {

    @Test
    fun condensationBlurBleedsSharpColorBoundary() {
        val w = 120
        val h = 120
        val engine = RainGlassEngine(width = w, height = h, seed = 42)
        val src = IntArray(w * h)
        // Left half bright crimson, right half deep cobalt blue
        for (y in 0 until h) {
            for (x in 0 until w) {
                src[y * w + x] = if (x < w / 2) {
                    (0xFF shl 24) or (240 shl 16) or (20 shl 8) or 30
                } else {
                    (0xFF shl 24) or (20 shl 16) or (40 shl 8) or 230
                }
            }
        }
        engine.setSourcePixels(src)

        // Just right of the boundary (x = w/2 + 3), sharpPixels is still blue-dominant,
        // while fogPixels has bled warm red across the boundary from left to right.
        val probeIdx = (h / 2) * w + (w / 2 + 3)
        val sharpRed = (engine.sharpPixels[probeIdx] ushr 16) and 0xFF
        val fogRed = (engine.fogPixels[probeIdx] ushr 16) and 0xFF
        assertTrue(
            "Condensation blur must bleed red across sharp edge (sharpRed=$sharpRed, fogRed=$fogRed)",
            fogRed > sharpRed + 45
        )
    }

    @Test
    fun slidingDropsWipeCondensationTrailsAndRefractJacket() {
        val w = 140
        val h = 140
        val engine = RainGlassEngine(width = w, height = h, seed = 777)

        // Checkerboard jacket pattern to verify optical refraction & trail wiping
        val src = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val cell = ((x / 16) + (y / 16)) and 1
                src[y * w + x] = if (cell == 0) {
                    (0xFF shl 24) or (225 shl 16) or (180 shl 8) or 65
                } else {
                    (0xFF shl 24) or (18 shl 16) or (28 shl 8) or 78
                }
            }
        }
        engine.setSourcePixels(src)

        val initialStreakY = engine.exteriorStreaks[0].y
        var out = engine.outPixels
        repeat(30) {
            out = engine.stepAndRenderPixels(
                dtSeconds = 0.033f,
                bassDrive = 0.75f,
                rmsDrive = 0.60f,
                highDrive = 0.65f
            )
        }

        // Verify wiped trails exist where fogMap was cleared well below initial 0.86f
        val wipedPixels = engine.fogMap.count { it < 0.35f }
        assertTrue("Sliding raindrops must wipe clear trails in condensation fog ($wipedPixels px)", wipedPixels > 120)

        // Verify active 3D water height lenses exist and produce valid opaque ARGB pixels
        val wetPixels = engine.waterHeight.count { it > 0.08f }
        assertTrue("3D water droplets & micro-beads must populate height map ($wetPixels px)", wetPixels > 200)
        assertTrue("All rendered pixels must have full alpha", out.all { (it ushr 24) == 0xFF })

        // Verify exterior falling rain streaks advance over time
        assertNotEquals(
            "Exterior rain streaks must move across frames",
            initialStreakY,
            engine.exteriorStreaks[0].y
        )

        // Verify rendered output differs from static fog due to refracted lenses & wiped trails
        var diffCount = 0
        for (i in out.indices) {
            val r1 = (out[i] ushr 16) and 0xFF
            val r2 = (engine.fogPixels[i] ushr 16) and 0xFF
            if (abs(r1 - r2) > 12) diffCount++
        }
        assertTrue("Refracted droplets and wiped trails must visibly modulate output ($diffCount px)", diffCount > 250)
    }

    @Test
    fun smallDropGrowsByAbsorbingBeadsThenAcceleratesPastCriticalSize() {
        val engine = RainGlassEngine(width = 160, height = 160, seed = 2026)
        // Park other collector drops away from center column so we test isolated single-drop physics
        for (idx in 1 until engine.drops.size) {
            engine.drops[idx].x = 16f
            engine.drops[idx].y = 140f
            engine.drops[idx].r = 2.4f
            engine.drops[idx].pinThreshold = 5.5f
            engine.drops[idx].sliding = false
            engine.drops[idx].vy = 0f
        }
        for (b in engine.beads) b.active = false

        val probe = engine.drops[0]
        probe.x = 80f
        probe.y = 32f
        probe.r = 4.1f
        probe.pinThreshold = 5.0f
        probe.sliding = false
        probe.vy = 0f

        // Place small adhered beads touching & along the downward path of the probe drop
        for (k in 0 until 8) {
            engine.beads[k].x = 80f + (k % 2 - 0.5f) * 1.2f
            engine.beads[k].y = 35f + k * 5.0f
            engine.beads[k].r = 2.4f
            engine.beads[k].active = true
        }

        var firstSlideVy = -1f
        var peakSlideVy = 0f
        repeat(45) {
            engine.stepAndRenderPixels(dtSeconds = 0.025f, bassDrive = 0.2f, rmsDrive = 0.3f, highDrive = 0.3f)
            if (probe.sliding && firstSlideVy < 0f) {
                firstSlideVy = probe.vy
            }
            if (probe.sliding && probe.vy > peakSlideVy) {
                peakSlideVy = probe.vy
            }
        }

        assertTrue("Drop must break surface tension after absorbing small beads (firstVy=$firstSlideVy)", firstSlideVy > 0f)
        assertTrue(
            "Once past critical size, drop must accelerate nonlinearly as it sweeps beads (first=$firstSlideVy, peak=$peakSlideVy)",
            peakSlideVy > firstSlideVy * 2.5f && peakSlideVy > 65f
        )
        assertTrue(
            "Drop radii must remain realistic without bloated giant blobs",
            engine.drops.all { it.r <= 8.0f }
        )
    }

    @Test
    fun highResolution512ScalingAndSubPixelBilinearRefraction() {
        val engine = RainGlassEngine(width = 512, height = 512, seed = 31415)
        assertTrue("512x512 engine must scale physics by 2.0x", abs(engine.simScale - 2.0f) < 0.001f)

        val out = engine.stepAndRenderPixels(
            dtSeconds = 0.016f,
            bassDrive = 0.55f,
            rmsDrive = 0.45f,
            highDrive = 0.70f
        )
        assertTrue("High-res 512x512 output must contain 262,144 opaque pixels", out.size == 512 * 512)
        assertTrue("All high-res pixels must preserve full alpha", out.all { (it ushr 24) == 0xFF })

        // Verify chromatic dispersion produces subtle R/B channel differentiation inside refracting droplets
        var dispersedWetPixels = 0
        for (i in out.indices) {
            if (engine.waterHeight[i] > 0.25f) {
                val r = (out[i] ushr 16) and 0xFF
                val b = out[i] and 0xFF
                if (r != b) dispersedWetPixels++
            }
        }
        assertTrue("High-res droplets must render smooth refracted color variation ($dispersedWetPixels px)", dispersedWetPixels > 400)
    }

    @Test
    fun gpuBeadGridAndDropPackingPreservesSubPixelPrecision() {
        val engine = RainGlassEngine(width = 256, height = 256, seed = 999)
        for (b in engine.beads) b.active = false

        // Place a known adhered small droplet at an exact sub-pixel coordinate
        engine.beads[0].x = 73.42f
        engine.beads[0].y = 148.67f
        engine.beads[0].r = 1.85f
        engine.beads[0].active = true

        engine.stepPhysicsOnly(dtSeconds = 0.008f, bassDrive = 0.2f, rmsDrive = 0.2f)

        val beadTexels = IntArray(RainGlassEngine.GPU_BEAD_TEX_WIDTH * RainGlassEngine.GPU_BEAD_TEX_HEIGHT)
        engine.packGpuBeadGrid(beadTexels)
        assertTrue("All GPU bead-grid texels must be fully opaque (0xFF alpha)", beadTexels.all { (it ushr 24) == 0xFF })

        // Verify calibration texel at (24, 0) stores exact byte 128
        val calTexel = beadTexels[RainGlassEngine.GPU_BEAD_GRID_CELLS]
        assertTrue("Calibration texel must encode mid-gray 128", (calTexel and 0xFF) == 128)

        // Decode bead[0]'s cell and verify sub-pixel reconstruction accuracy (< 0.15 px in 256-space!)
        val targetCellX = ((73.42f / 256f) * RainGlassEngine.GPU_BEAD_GRID_CELLS).toInt()
        val targetCellY = ((148.67f / 256f) * RainGlassEngine.GPU_BEAD_GRID_CELLS).toInt()
        val packed = beadTexels[targetCellY * RainGlassEngine.GPU_BEAD_TEX_WIDTH + targetCellX]
        val ox = ((packed ushr 16) and 0xFF) / 255f
        val oy = ((packed ushr 8) and 0xFF) / 255f
        val decodedX = ((targetCellX + ox) / RainGlassEngine.GPU_BEAD_GRID_CELLS) * 256f
        val decodedY = ((targetCellY + oy) / RainGlassEngine.GPU_BEAD_GRID_CELLS) * 256f
        assertTrue("GPU sub-pixel X error must be < 0.12 units (got $decodedX)", abs(decodedX - 73.42f) < 0.12f)
        assertTrue("GPU sub-pixel Y error must be < 0.12 units (got $decodedY)", abs(decodedY - 148.67f) < 0.12f)

        val fogTexels = IntArray(256 * 256)
        engine.packGpuFogMask(fogTexels)
        assertTrue("GPU fog mask texels must all be opaque", fogTexels.all { (it ushr 24) == 0xFF })

        val dropFloats = FloatArray(40)
        engine.packGpuDrops(dropFloats)
        assertTrue("Packed GPU collector drops must have positive normalized radii", dropFloats[2] > 0.005f)
    }
}
