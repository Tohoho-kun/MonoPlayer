package com.example.monoplayer.dsp

import kotlin.math.*

/**
 * True 3-Band Dynamic Equalizer (LOW Shelf / MID Peaking Bell / HIGH Shelf).
 * Uses dedicated sidechain band-detection filters + Soft-Knee envelope detectors +
 * Zero-Delay Feedback TPT SVFs modulated at 16-sample control rate for zero phase shift at 0 dB.
 */
class ThreeBandDynamicEq(sampleRate: Int = 48000) {

    companion object {
        private const val KNEE_WIDTH_DB = 6.0f
        private const val CONTROL_STEP_SAMPLES = 16

        /**
         * Analytical magnitude approximation (in dB) for UI live curve visualization across 20Hz..20kHz.
         */
        fun computeCompositeResponseDb(
            freqHz: Float,
            config: DynamicEqConfig,
            dynDeltasDb: FloatArray
        ): Float {
            if (!config.enabled) return 0f
            var totalDb = 0f
            if (config.low.enabled) {
                val gain = config.low.staticGainDb + dynDeltasDb.getOrElse(0) { 0f }
                totalDb += shelfResponseDb(freqHz, config.low.freqHz, isHigh = false, gainDb = gain)
            }
            if (config.mid.enabled) {
                val gain = config.mid.staticGainDb + dynDeltasDb.getOrElse(1) { 0f }
                totalDb += bellResponseDb(freqHz, config.mid.freqHz, config.mid.q, gainDb = gain)
            }
            if (config.high.enabled) {
                val gain = config.high.staticGainDb + dynDeltasDb.getOrElse(2) { 0f }
                totalDb += shelfResponseDb(freqHz, config.high.freqHz, isHigh = true, gainDb = gain)
            }
            return totalDb.coerceIn(-18f, 18f)
        }

        private fun shelfResponseDb(f: Float, fc: Float, isHigh: Boolean, gainDb: Float): Float {
            if (abs(gainDb) < 0.01f) return 0f
            val ratio = if (isHigh) (f / fc.coerceAtLeast(20f)) else (fc.coerceAtLeast(20f) / f.coerceAtLeast(10f))
            val r4 = ratio.pow(3.2f)
            val weight = r4 / (1f + r4)
            return gainDb * weight
        }

        private fun bellResponseDb(f: Float, fc: Float, q: Float, gainDb: Float): Float {
            if (abs(gainDb) < 0.01f) return 0f
            val logDist = ln((f / fc.coerceAtLeast(20f)).coerceAtLeast(1e-4f))
            val bandwidth = 1.0f / q.coerceIn(0.3f, 6.0f)
            val shape = exp(-(logDist * logDist) / (0.36f * bandwidth * bandwidth))
            return gainDb * shape
        }
    }

    private var sr: Int = sampleRate.coerceAtLeast(8000)
    private val nativeHandle: Long =
        if (RustNativeBridge.isAvailable) RustNativeBridge.nativeDynEqCreate(sr) else 0L
    private val packedConfig25 = FloatArray(25)
    private val telemetryOut6 = FloatArray(6)

    // Sidechain Band Detectors (0 = LOW LP, 1 = MID BP, 2 = HIGH HP)
    private val scDetectors = Array(3) { TptSvfFilter() }

    // Audio-path Dynamic TPT SVF stages (0 = LOW Shelf, 1 = MID Bell, 2 = HIGH Shelf)
    private val eqStages = Array(3) { TptSvfFilter() }

    // Per-band envelope (linear power / peak hybrid) and smoothed dynamic gain delta (dB)
    private val scPowerEnv = FloatArray(3) { 1e-6f }
    private val currentDynDeltaDb = FloatArray(3) { 0f }
    private val lastDetectedDb = FloatArray(3) { -60f }

    private var lastLowFreq = -1f
    private var lastMidFreq = -1f
    private var lastMidQ = -1f
    private var lastHighFreq = -1f
    private var stepCounter = 0

    init {
        reset()
    }

    @Synchronized
    fun setSampleRate(newSr: Int) {
        val validSr = newSr.coerceAtLeast(8000)
        if (validSr != sr) {
            sr = validSr
            if (nativeHandle != 0L) {
                RustNativeBridge.nativeDynEqSetSampleRate(nativeHandle, sr)
            }
            lastLowFreq = -1f
            lastMidFreq = -1f
            lastHighFreq = -1f
            reset()
        }
    }

    @Synchronized
    fun reset() {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeDynEqReset(nativeHandle)
        }
        for (i in 0..2) {
            scDetectors[i].reset()
            eqStages[i].reset()
            scPowerEnv[i] = 1e-6f
            currentDynDeltaDb[i] = 0f
            lastDetectedDb[i] = -60f
        }
        stepCounter = 0
    }

    fun getDetectedBandsDb(): FloatArray = floatArrayOf(lastDetectedDb[0], lastDetectedDb[1], lastDetectedDb[2])

    fun getDynamicGainDeltasDb(): FloatArray = floatArrayOf(currentDynDeltaDb[0], currentDynDeltaDb[1], currentDynDeltaDb[2])

    private var lastPackedConfig: DynamicEqConfig? = null

    private fun packBand(off: Int, b: DynamicBandConfig) {
        packedConfig25[off] = if (b.enabled) 1f else 0f
        packedConfig25[off + 1] = b.freqHz
        packedConfig25[off + 2] = b.q
        packedConfig25[off + 3] = b.staticGainDb
        packedConfig25[off + 4] = b.thresholdDb
        packedConfig25[off + 5] = b.maxDynamicGainDb
        packedConfig25[off + 6] = b.attackMs
        packedConfig25[off + 7] = b.releaseMs
    }

    private fun packConfig(config: DynamicEqConfig) {
        if (config === lastPackedConfig) return
        lastPackedConfig = config
        packedConfig25[0] = if (config.enabled) 1f else 0f
        packBand(1, config.low)
        packBand(9, config.mid)
        packBand(17, config.high)
    }

    private fun unpackTelemetry() {
        for (i in 0..2) {
            lastDetectedDb[i] = telemetryOut6[i]
            currentDynDeltaDb[i] = telemetryOut6[3 + i]
        }
    }

    @Synchronized
    fun processMonoSample(input: Float, config: DynamicEqConfig): Float {
        if (!config.enabled) {
            for (i in 0..2) currentDynDeltaDb[i] *= 0.98f
            return input
        }
        if (nativeHandle != 0L) {
            packConfig(config)
            val y = RustNativeBridge.nativeDynEqProcessMono(nativeHandle, input, packedConfig25, telemetryOut6)
            unpackTelemetry()
            return y
        }
        updateSidechainAndFilters(input, config)
        var y = input
        if (config.low.enabled) y = eqStages[0].processMono(y)
        if (config.mid.enabled) y = eqStages[1].processMono(y)
        if (config.high.enabled) y = eqStages[2].processMono(y)
        return y
    }

    @Synchronized
    fun processStereoSample(inL: Float, inR: Float, config: DynamicEqConfig, outPair: FloatArray) {
        if (!config.enabled) {
            for (i in 0..2) currentDynDeltaDb[i] *= 0.98f
            outPair[0] = inL
            outPair[1] = inR
            return
        }
        if (nativeHandle != 0L) {
            packConfig(config)
            RustNativeBridge.nativeDynEqProcessStereo(nativeHandle, inL, inR, packedConfig25, outPair, telemetryOut6)
            unpackTelemetry()
            return
        }
        val midDetector = sqrt(0.5f * (inL * inL + inR * inR)) * (if (inL + inR >= 0f) 1f else -1f)
        updateSidechainAndFilters(midDetector, config)

        var yL = inL
        var yR = inR
        if (config.low.enabled) {
            yL = eqStages[0].processMono(yL)
            yR = eqStages[0].processRight(yR)
        }
        if (config.mid.enabled) {
            yL = eqStages[1].processMono(yL)
            yR = eqStages[1].processRight(yR)
        }
        if (config.high.enabled) {
            yL = eqStages[2].processMono(yL)
            yR = eqStages[2].processRight(yR)
        }
        outPair[0] = yL
        outPair[1] = yR
    }

    private fun updateSidechainAndFilters(detectorSample: Float, config: DynamicEqConfig) {
        if (config.low.freqHz != lastLowFreq) {
            lastLowFreq = config.low.freqHz
            scDetectors[0].setLowPass(sr, config.low.freqHz * 1.25f, 0.7071f)
        }
        if (config.mid.freqHz != lastMidFreq || config.mid.q != lastMidQ) {
            lastMidFreq = config.mid.freqHz
            lastMidQ = config.mid.q
            scDetectors[1].setBandPass(sr, config.mid.freqHz, config.mid.q.coerceIn(0.4f, 4.0f))
        }
        if (config.high.freqHz != lastHighFreq) {
            lastHighFreq = config.high.freqHz
            scDetectors[2].setHighPass(sr, config.high.freqHz * 0.85f, 0.7071f)
        }

        // Fast 3ms RMS/peak sidechain energy tracking per band
        val fastEnvAlpha = 1f - exp(-1f / (sr * 0.003f))
        for (i in 0..2) {
            val bandSig = scDetectors[i].processMono(detectorSample)
            val sq = bandSig * bandSig
            scPowerEnv[i] += fastEnvAlpha * (sq - scPowerEnv[i])
        }

        stepCounter++
        if (stepCounter < CONTROL_STEP_SAMPLES) return
        val dtSec = stepCounter.toFloat() / sr.toFloat()
        stepCounter = 0

        val bands = arrayOf(config.low, config.mid, config.high)
        for (i in 0..2) {
            val b = bands[i]
            val envDb = (10f * log10(scPowerEnv[i].coerceAtLeast(1e-10f))).coerceIn(-65f, 6f)
            lastDetectedDb[i] = envDb

            val targetDeltaDb = if (!b.enabled || abs(b.maxDynamicGainDb) < 0.05f) {
                0f
            } else {
                computeSoftKneeDynamicGain(
                    inputDb = envDb,
                    thresholdDb = b.thresholdDb,
                    maxDynamicGainDb = b.maxDynamicGainDb
                )
            }

            // Use attack when magnitude of action increases, release when returning toward 0 dB
            val isEngaging = abs(targetDeltaDb) > abs(currentDynDeltaDb[i])
            val tauSec = (if (isEngaging) b.attackMs else b.releaseMs).coerceAtLeast(1f) / 1000f
            val coef = (1f - exp(-dtSec / tauSec)).coerceIn(0.001f, 1f)
            currentDynDeltaDb[i] += coef * (targetDeltaDb - currentDynDeltaDb[i])

            val totalGainDb = (if (b.enabled) b.staticGainDb + currentDynDeltaDb[i] else 0f).coerceIn(-15f, 15f)
            when (i) {
                0 -> eqStages[0].setLowShelf(sr, b.freqHz, 0.7071f, totalGainDb)
                1 -> eqStages[1].setBell(sr, b.freqHz, b.q, totalGainDb)
                2 -> eqStages[2].setHighShelf(sr, b.freqHz, 0.7071f, totalGainDb)
            }
        }
    }

    /**
     * Maps sidechain overshoot above [thresholdDb] smoothly (6dB soft knee, 18dB full saturation span)
     * onto [0 .. maxDynamicGainDb] (supports both negative downward cut and positive upward transient boost).
     */
    private fun computeSoftKneeDynamicGain(
        inputDb: Float,
        thresholdDb: Float,
        maxDynamicGainDb: Float
    ): Float {
        val over = inputDb - thresholdDb
        val halfKnee = KNEE_WIDTH_DB * 0.5f
        val effectiveOver = when {
            over <= -halfKnee -> 0f
            over < halfKnee -> {
                val x = over + halfKnee
                (x * x) / (2f * KNEE_WIDTH_DB)
            }
            else -> over
        }
        if (effectiveOver <= 0f) return 0f
        // Asymptotic smooth saturation so extreme peaks gently approach maxDynamicGainDb without hard corners
        val norm = effectiveOver / (effectiveOver + 9.0f)
        return (maxDynamicGainDb * norm * 1.35f).coerceIn(
            min(0f, maxDynamicGainDb),
            max(0f, maxDynamicGainDb)
        )
    }
}
