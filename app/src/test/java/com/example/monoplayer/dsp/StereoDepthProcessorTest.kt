package com.example.monoplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class StereoDepthProcessorTest {

    @Test
    fun pureMonoGuaranteesBitExactIdenticalLeftAndRight() {
        val sr = 48000
        val proc = StereoDepthProcessor(sr)
        val cfg = SpatialStageConfig(
            stageMode = OutputStageMode.PURE_MONO,
            depthEnabled = true,
            depthAmount = 0.55f,
            frontBackContrast = 0.60f,
            preDelayMs = 18f,
            roomDecay = 0.50f
        )
        val pair = FloatArray(2)
        for (n in 0 until sr / 2) {
            val l = (0.4 * sin(2.0 * PI * 440.0 * n / sr) + 0.25 * sin(2.0 * PI * 1760.0 * n / sr)).toFloat()
            val r = (0.4 * sin(2.0 * PI * 440.0 * n / sr) - 0.25 * sin(2.0 * PI * 1760.0 * n / sr)).toFloat()
            val monoCore = 0.5f * (l + r)
            proc.processSample(monoCore, l, r, cfg, pair)
            assertEquals("Sample $n must be bit-identical in PURE_MONO", pair[0], pair[1], 0f)
        }
    }

    @Test
    fun stereoSideInformationCreatesDelayedZAxisDepthInMono() {
        val sr = 48000
        val proc = StereoDepthProcessor(sr)
        val cfg = SpatialStageConfig(
            stageMode = OutputStageMode.PURE_MONO,
            depthEnabled = true,
            depthAmount = 0.70f,
            frontBackContrast = 0.50f,
            preDelayMs = 20f, // 960 samples at 48 kHz
            roomDecay = 0.55f
        )
        val pair = FloatArray(2)
        val burstSamples = (0.005 * sr).toInt() // 240 samples (5 ms)
        val totalSamples = (0.120 * sr).toInt()

        var preGapEnergy = 0.0
        var depthTailEnergy = 0.0

        for (n in 0 until totalSamples) {
            // Pure stereo Side burst (L = +x, R = -x -> Mid = 0, monoCore = 0)
            val s = if (n < burstSamples) (0.6 * sin(2.0 * PI * 1200.0 * n / sr)).toFloat() else 0f
            proc.processSample(monoCore = 0f, delayedL = s, delayedR = -s, config = cfg, outPair = pair)
            val y = pair[0].toDouble()
            if (n in (burstSamples + 10)..850) {
                // Before 20 ms pre-delay arrives (960 samples)
                preGapEnergy += y * y
            } else if (n >= 960) {
                depthTailEnergy += y * y
            }
        }

        assertTrue("Haas ITDG gap before pre-delay must remain quiet ($preGapEnergy)", preGapEnergy < 1e-6)
        assertTrue(
            "Stereo Side burst must emerge as Z-axis mono depth tail after pre-delay ($depthTailEnergy)",
            depthTailEnergy > 0.02
        )
    }

    @Test
    fun narrowStereoLocksSubBassMonoAndScalesUpperWidth() {
        val sr = 48000

        fun measureSideRms(freqHz: Double, widthPct: Float): Double {
            val proc = StereoDepthProcessor(sr)
            val cfg = SpatialStageConfig(
                stageMode = OutputStageMode.NARROW_STEREO,
                narrowWidthPercent = widthPct,
                depthEnabled = false
            )
            val pair = FloatArray(2)
            val nTotal = sr / 2
            var sideSq = 0.0
            var count = 0
            for (n in 0 until nTotal) {
                val mid = (0.3 * sin(2.0 * PI * 300.0 * n / sr)).toFloat()
                val side = (0.4 * sin(2.0 * PI * freqHz * n / sr)).toFloat()
                proc.processSample(mid, mid + side, mid - side, cfg, pair)
                if (n >= sr / 4) {
                    val outSide = 0.5 * (pair[0] - pair[1])
                    sideSq += outSide * outSide
                    count++
                }
            }
            return sqrt(sideSq / count)
        }

        val bassSide55Hz = measureSideRms(55.0, 50f)
        val highSide20Pct = measureSideRms(2000.0, 20f)
        val highSide65Pct = measureSideRms(2000.0, 65f)

        assertTrue(
            "55 Hz sub-bass must be strongly mono-locked vs 2 kHz ($bassSide55Hz vs $highSide65Pct)",
            bassSide55Hz < highSide65Pct * 0.18
        )
        assertTrue(
            "65% Narrow Stereo must produce wider Side RMS than 20% ($highSide65Pct vs $highSide20Pct)",
            highSide65Pct > highSide20Pct * 2.5
        )
        assertTrue(
            "65% Narrow Stereo must remain narrower than full 0.40 input Side ($highSide65Pct)",
            highSide65Pct < 0.25
        )
    }
}
