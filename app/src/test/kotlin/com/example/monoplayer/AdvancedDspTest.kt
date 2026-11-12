package com.example.monoplayer

import com.example.monoplayer.dsp.DynamicBandConfig
import com.example.monoplayer.dsp.DynamicEqConfig
import com.example.monoplayer.dsp.LoudnessConfig
import com.example.monoplayer.dsp.LoudnessMode
import com.example.monoplayer.dsp.RealtimeLoudnessNormalizer
import com.example.monoplayer.dsp.ThreeBandDynamicEq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AdvancedDspTest {

    private val sr = 48000

    private fun rmsDb(samples: FloatArray, startIdx: Int = samples.size / 2): Float {
        var sumSq = 0.0
        var count = 0
        for (i in startIdx until samples.size) {
            sumSq += samples[i] * samples[i]
            count++
        }
        val rms = sqrt(sumSq / count.coerceAtLeast(1)).toFloat()
        return 20f * log10(rms.coerceAtLeast(1e-6f))
    }

    @Test
    fun loudnessNormalizer_normalHiFiConvergesQuietAndLoudSignals() {
        val cfg = LoudnessConfig(mode = LoudnessMode.NORMAL_HIFI, normalTargetLufs = -14.0f)

        // 1. Quiet 1kHz sine (-26 dBFS amplitude -> ~ -29 LUFS)
        val normQuiet = RealtimeLoudnessNormalizer(sr)
        val quietAmp = 10.0.pow(-26.0 / 20.0).toFloat()
        val quietOut = FloatArray(sr * 3)
        for (n in quietOut.indices) {
            val s = quietAmp * sin(2.0 * PI * 1000.0 * n / sr).toFloat()
            normQuiet.pushLookaheadSample(s, s)
            quietOut[n] = normQuiet.processMonoSample(s, cfg)
        }

        // 2. Loud 1kHz sine (-6 dBFS amplitude -> ~ -9 LUFS)
        val normLoud = RealtimeLoudnessNormalizer(sr)
        val loudAmp = 10.0.pow(-6.0 / 20.0).toFloat()
        val loudOut = FloatArray(sr * 3)
        for (n in loudOut.indices) {
            val s = loudAmp * sin(2.0 * PI * 1000.0 * n / sr).toFloat()
            normLoud.pushLookaheadSample(s, s)
            loudOut[n] = normLoud.processMonoSample(s, cfg)
        }

        assertTrue("Quiet signal should receive positive gain, got ${normQuiet.appliedGainDb}", normQuiet.appliedGainDb > 5.0f)
        assertTrue("Loud signal should be attenuated, got ${normLoud.appliedGainDb}", normLoud.appliedGainDb < -3.0f)

        // Input gap was 20 dB; normalized output gap should be drastically narrower (< 6.5 dB)
        val gapAfterDb = abs(rmsDb(loudOut) - rmsDb(quietOut))
        assertTrue("20 dB input gap should shrink below 6.5 dB, got $gapAfterDb dB", gapAfterDb < 6.5f)
    }

    @Test
    fun loudnessNormalizer_silenceGatePreventsNoisePumping() {
        val cfg = LoudnessConfig(mode = LoudnessMode.NOISY_CAR, carTargetLufs = -11.0f, carCabinIntensity = 0.85f)
        val norm = RealtimeLoudnessNormalizer(sr)

        // Feed 1.5s of moderate music (-20 dBFS), then 2.0s of background hiss (-68 dBFS)
        val modAmp = 0.1f
        for (n in 0 until (sr * 3 / 2)) {
            val s = modAmp * sin(2.0 * PI * 900.0 * n / sr).toFloat()
            norm.pushLookaheadSample(s, s)
            norm.processMonoSample(s, cfg)
        }
        val gainBeforeSilence = norm.appliedGainDb

        val hissAmp = 10.0.pow(-68.0 / 20.0).toFloat()
        for (n in 0 until (sr * 2)) {
            val s = hissAmp * sin(2.0 * PI * 3000.0 * n / sr).toFloat()
            norm.pushLookaheadSample(s, s)
            norm.processMonoSample(s, cfg)
        }

        assertTrue("Gate must freeze on -68 dBFS hiss", norm.isGateFrozen)
        assertTrue(
            "Gain must not pump upward during silence (before=$gainBeforeSilence, after=${norm.appliedGainDb})",
            norm.appliedGainDb <= gainBeforeSilence + 0.2f
        )
    }

    @Test
    fun loudnessNormalizer_noisyCarModeLiftsQuietPassagesAndClarity() {
        val quietAmp = 10.0.pow(-30.0 / 20.0).toFloat() // Very quiet vocal passage @ 2.5 kHz
        val normalNorm = RealtimeLoudnessNormalizer(sr)
        val carNorm = RealtimeLoudnessNormalizer(sr)
        val normalCfg = LoudnessConfig(mode = LoudnessMode.NORMAL_HIFI)
        val carCfg = LoudnessConfig(mode = LoudnessMode.NOISY_CAR, carTargetLufs = -11.0f, carCabinIntensity = 0.9f)

        val normalOut = FloatArray(sr * 2)
        val carOut = FloatArray(sr * 2)
        for (n in normalOut.indices) {
            val s = quietAmp * sin(2.0 * PI * 2500.0 * n / sr).toFloat()
            normalNorm.pushLookaheadSample(s, s)
            carNorm.pushLookaheadSample(s, s)
            normalOut[n] = normalNorm.processMonoSample(s, normalCfg)
            carOut[n] = carNorm.processMonoSample(s, carCfg)
        }

        assertTrue(
            "Noisy Car mode should lift quiet 2.5kHz vocal much higher than Normal Hi-Fi",
            rmsDb(carOut) > rmsDb(normalOut) + 4.0f
        )
        assertTrue("Car dynamic vocal clarity boost should engage on quiet passage", carNorm.dynamicClarityBoostDb > 1.0f)
    }

    @Test
    fun loudnessNormalizer_noisyCarSpeedGainDoublesForStdAndMaxWithoutClipping() {
        fun measureGainDb(intensity: Float): Pair<Float, Float> {
            val norm = RealtimeLoudnessNormalizer(sr)
            val cfg = LoudnessConfig(
                mode = LoudnessMode.NOISY_CAR,
                carTargetLufs = -19.0f,
                carCabinIntensity = intensity
            )
            val amp = 0.45f // ~ -10 LUFS, -6.9 dBFS peak
            var peakOut = 0f
            for (n in 0 until (sr * 2)) {
                val s = amp * sin(2.0 * PI * 1000.0 * n / sr).toFloat()
                norm.pushLookaheadSample(s, s)
                val out = norm.processMonoSample(s, cfg)
                if (n > sr) {
                    peakOut = kotlin.math.max(peakOut, abs(out))
                }
            }
            return Pair(norm.appliedGainDb, peakOut)
        }

        val (idleDb, _) = measureGainDb(0.0f)
        val (stdDb, stdPeak) = measureGainDb(1.0f)
        val (maxDb, maxPeak) = measureGainDb(2.0f)

        val deltaStd = stdDb - idleDb
        val deltaMax = maxDb - idleDb
        assertTrue("Standard (1.0x) speed delta should be ~+4.4 dB, got $deltaStd", abs(deltaStd - 4.4f) < 0.35f)
        assertTrue("Max (2.0x) speed delta should be ~+8.8 dB, got $deltaMax", abs(deltaMax - 8.8f) < 0.45f)
        assertTrue("Peak output must stay strictly below clipping ceiling, stdPeak=$stdPeak, maxPeak=$maxPeak", stdPeak < 0.95f && maxPeak < 0.95f)
    }

    @Test
    fun threeBandDynamicEq_unityAtZeroAndDynamicCutAndBoostWork() {
        val dynEq = ThreeBandDynamicEq(sr)
        val flatCfg = DynamicEqConfig.PRESETS.getValue("Flat (Init)")

        // 1. Bit-exact unity when static & dynamic gains are 0 dB
        for (n in 0 until 4000) {
            val x = 0.4f * sin(2.0 * PI * 1200.0 * n / sr).toFloat()
            val y = dynEq.processMonoSample(x, flatCfg)
            assertEquals(x, y, 1e-6f)
        }

        // 2. Mid-band Dynamic Cut (-8 dB max cut above -24 dB threshold at 2000 Hz)
        dynEq.reset()
        val cutCfg = flatCfg.copy(
            mid = DynamicBandConfig(enabled = true, freqHz = 2000f, q = 1.2f, thresholdDb = -24f, maxDynamicGainDb = -8.0f, attackMs = 4f, releaseMs = 60f)
        )
        val loudMidOut = FloatArray(sr)
        for (n in loudMidOut.indices) {
            val x = 0.5f * sin(2.0 * PI * 2000.0 * n / sr).toFloat() // ~ -9 dBFS (well above -24 dB)
            loudMidOut[n] = dynEq.processMonoSample(x, cutCfg)
        }
        val midDelta = dynEq.getDynamicGainDeltasDb()[1]
        assertTrue("Mid dynamic cut should engage strongly (< -3.5 dB), got $midDelta", midDelta < -3.5f)
        assertTrue("Output RMS should drop below input (-9 dBFS)", rmsDb(loudMidOut) < -12.0f)

        // 3. High-band Dynamic Boost (+6 dB above -25 dB threshold at 6000 Hz)
        dynEq.reset()
        val boostCfg = flatCfg.copy(
            high = DynamicBandConfig(enabled = true, freqHz = 6000f, thresholdDb = -25f, maxDynamicGainDb = 6.0f, attackMs = 3f, releaseMs = 50f)
        )
        val highOut = FloatArray(sr)
        for (n in highOut.indices) {
            val x = 0.25f * sin(2.0 * PI * 7500.0 * n / sr).toFloat() // ~ -15 dBFS
            highOut[n] = dynEq.processMonoSample(x, boostCfg)
        }
        val highDelta = dynEq.getDynamicGainDeltasDb()[2]
        assertTrue("High dynamic boost should engage (> +2.5 dB), got $highDelta", highDelta > 2.5f)
    }
}
