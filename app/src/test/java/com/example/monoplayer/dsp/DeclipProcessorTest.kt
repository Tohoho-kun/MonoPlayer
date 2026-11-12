package com.example.monoplayer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclipProcessorTest {

    @Test
    fun zeroDbDigitalClippingIsDetectedAndPeakReconstructed() {
        val proc = DeclipProcessor(48000)
        val cfg = DeclipConfig(enabled = true, sensitivity = 0.75f, minAmplitude = 0.12f)

        val sr = 48000
        val freq = 800.0
        val nSamples = 480

        var maxReconstructed = 0f
        for (i in 0 until nSamples) {
            val t = i.toDouble() / sr
            val raw = 1.35 * sin(2.0 * PI * freq * t)
            val clipped = raw.coerceIn(-1.0, 1.0).toFloat()
            val y = proc.processMonoSample(clipped, cfg)
            if (abs(y) > maxReconstructed) {
                maxReconstructed = abs(y)
            }
        }

        assertTrue("Expected clipping repair events to be recorded", proc.repairedEventsTotal > 0)
        assertTrue("Reconstructed peak must exceed the 1.0 clipping plateau", proc.maxReconstructedPeak > 1.0f)
        assertTrue("Output peak from stream should exceed 1.0", maxReconstructed > 1.0f)
    }

    @Test
    fun scaledMasterClippingAtMinusSixDbIsDetectedAndRepaired() {
        // Critical requirement: detect & repair tracks where digital clipping occurred during
        // mastering, but the overall track level was subsequently lowered (e.g. -6 dBFS / 0.50 amplitude),
        // leaving flat plateaus and crackling transients.
        val proc = DeclipProcessor(48000)
        val cfg = DeclipConfig(enabled = true, sensitivity = 0.85f, minAmplitude = 0.10f)

        val sr = 48000
        val freq = 440.0
        val nSamples = 960
        val scale = 0.50f // -6.02 dBFS

        val stereoPair = FloatArray(2)
        var maxPeakOut = 0f
        for (i in 0 until nSamples) {
            val t = i.toDouble() / sr
            val raw = 1.30 * sin(2.0 * PI * freq * t)
            // Hard clipped at 1.0, then scaled down by 0.50 (flat top at +/- 0.50)
            val clippedAndScaled = (raw.coerceIn(-1.0, 1.0) * scale).toFloat()
            proc.processStereoSample(clippedAndScaled, clippedAndScaled, cfg, stereoPair)
            val peak = maxOf(abs(stereoPair[0]), abs(stereoPair[1]))
            if (peak > maxPeakOut) maxPeakOut = peak
        }

        assertTrue(
            "Scaled -6dB flat-topped clips must be detected as clipping events",
            proc.repairedEventsTotal > 0
        )
        assertTrue(
            "Estimated threshold should reflect ~ -6dBFS level (actual: ${proc.estimatedThresholdDb} dB)",
            proc.estimatedThresholdDb in -8.0f..-4.0f
        )
        assertTrue(
            "The restored peak should naturally rise above the 0.50 plateau (actual: ${proc.maxReconstructedPeak})",
            proc.maxReconstructedPeak > 0.50f
        )
        assertTrue(
            "Output waveform should contain smooth reconstructed peak > 0.50",
            maxPeakOut > 0.50f
        )
    }

    @Test
    fun cracklingClicksAndCuspsAreSuppressedTowardSmoothContinuity() {
        val proc = DeclipProcessor(48000)
        val cfg = DeclipConfig(enabled = true, sensitivity = 0.85f, minAmplitude = 0.10f)

        val sr = 48000
        val freq = 250.0
        val nSamples = 300
        val clickIdx = 70 // near positive crest

        val input = FloatArray(nSamples)
        val output = FloatArray(nSamples)
        for (i in 0 until nSamples) {
            val t = i.toDouble() / sr
            var s = (0.42 * sin(2.0 * PI * freq * t)).toFloat()
            if (i == clickIdx) {
                s += 0.25f // inject sharp crackle/click spike
            }
            input[i] = s
        }

        for (i in 0 until nSamples) {
            output[i] = proc.processMonoSample(input[i], cfg)
        }

        assertTrue("Crackle/click events must trigger repair", proc.repairedEventsTotal > 0)

        // 48 samples lookahead delay
        val outIdx = clickIdx + 48
        if (outIdx < nSamples) {
            val rawClickVal = input[clickIdx]
            val repairedVal = output[outIdx]
            val reduction = abs(repairedVal - rawClickVal)
            assertTrue(
                "Click spike must be suppressed toward smooth wave (raw: $rawClickVal, repaired: $repairedVal)",
                reduction > 0.08f
            )
        }
    }

    @Test
    fun unclippedCleanSignalsPassWithoutFalsePositiveDistortion() {
        val proc = DeclipProcessor(48000)
        val cfg = DeclipConfig(enabled = true, sensitivity = 0.70f, minAmplitude = 0.12f)

        val sr = 48000
        val freq = 1000.0
        val nSamples = 400

        for (i in 0 until nSamples) {
            val t = i.toDouble() / sr
            // Clean sine wave with 0.65 amplitude (well below clipping)
            val clean = (0.65 * sin(2.0 * PI * freq * t)).toFloat()
            proc.processMonoSample(clean, cfg)
        }

        assertEquals(
            "Clean unclipped audio must not trigger false clipping repairs",
            0L,
            proc.repairedEventsTotal
        )
    }

    @Test
    fun nanAndInfinityAreSanitizedGracefully() {
        val proc = DeclipProcessor(48000)
        val cfg = DeclipConfig(enabled = true)

        val out1 = proc.processMonoSample(Float.NaN, cfg)
        val out2 = proc.processMonoSample(Float.POSITIVE_INFINITY, cfg)
        val out3 = proc.processMonoSample(Float.NEGATIVE_INFINITY, cfg)
        val out4 = proc.processMonoSample(0.5f, cfg)

        assertTrue(out1.isFinite())
        assertTrue(out2.isFinite())
        assertTrue(out3.isFinite())
        assertTrue(out4.isFinite())

        val pair = FloatArray(2)
        proc.processStereoSample(Float.NaN, 0.3f, cfg, pair)
        assertTrue(pair[0].isFinite() && pair[1].isFinite())
    }
}
