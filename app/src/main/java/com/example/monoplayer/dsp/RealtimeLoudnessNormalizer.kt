package com.example.monoplayer.dsp

import kotlin.math.*

/**
 * Zero-Delay Feedback Linear Trapezoidal State Variable Filter (Cytomic TPT SVF).
 * Unconditionally stable under fast gain/frequency modulation with bit-exact unity when gainDb == 0.
 */
internal class TptSvfFilter {
    private var ic1eqL = 0f
    private var ic2eqL = 0f
    private var ic1eqR = 0f
    private var ic2eqR = 0f

    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f
    private var m0 = 1f
    private var m1 = 0f
    private var m2 = 0f
    private var isBypass = true

    fun reset() {
        ic1eqL = 0f; ic2eqL = 0f
        ic1eqR = 0f; ic2eqR = 0f
    }

    fun setHighPass(sampleRate: Int, freqHz: Float, q: Float = 0.7071f) {
        isBypass = false
        val fc = freqHz.coerceIn(15f, sampleRate * 0.45f)
        val g = tan(Math.PI * fc / sampleRate).toFloat()
        val k = 1f / q.coerceAtLeast(0.1f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
        m0 = 1f
        m1 = -k
        m2 = -1f
    }

    fun setLowPass(sampleRate: Int, freqHz: Float, q: Float = 0.7071f) {
        isBypass = false
        val fc = freqHz.coerceIn(20f, sampleRate * 0.45f)
        val g = tan(Math.PI * fc / sampleRate).toFloat()
        val k = 1f / q.coerceAtLeast(0.1f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
        m0 = 0f
        m1 = 0f
        m2 = 1f
    }

    fun setBandPass(sampleRate: Int, freqHz: Float, q: Float = 0.7071f) {
        isBypass = false
        val fc = freqHz.coerceIn(20f, sampleRate * 0.45f)
        val g = tan(Math.PI * fc / sampleRate).toFloat()
        val k = 1f / q.coerceAtLeast(0.1f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
        m0 = 0f
        m1 = 1f
        m2 = 0f
    }

    fun setBell(sampleRate: Int, freqHz: Float, q: Float, gainDb: Float) {
        if (abs(gainDb) < 0.01f) {
            isBypass = true
            return
        }
        isBypass = false
        val fc = freqHz.coerceIn(20f, sampleRate * 0.45f)
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val g = tan(Math.PI * fc / sampleRate).toFloat()
        val k = 1f / (q.coerceAtLeast(0.2f) * a)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
        m0 = 1f
        m1 = k * (a * a - 1f)
        m2 = 0f
    }

    fun setLowShelf(sampleRate: Int, freqHz: Float, q: Float, gainDb: Float) {
        if (abs(gainDb) < 0.01f) {
            isBypass = true
            return
        }
        isBypass = false
        val fc = freqHz.coerceIn(20f, sampleRate * 0.45f)
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val g = (tan(Math.PI * fc / sampleRate) / sqrt(a.toDouble())).toFloat()
        val k = 1f / q.coerceAtLeast(0.2f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
        m0 = 1f
        m1 = k * (a - 1f)
        m2 = a * a - 1f
    }

    fun setHighShelf(sampleRate: Int, freqHz: Float, q: Float, gainDb: Float) {
        if (abs(gainDb) < 0.01f) {
            isBypass = true
            return
        }
        isBypass = false
        val fc = freqHz.coerceIn(20f, sampleRate * 0.45f)
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val g = (tan(Math.PI * fc / sampleRate) * sqrt(a.toDouble())).toFloat()
        val k = 1f / q.coerceAtLeast(0.2f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
        m0 = a * a
        m1 = k * (1f - a) * a
        m2 = 1f - a * a
    }

    fun processMono(v0: Float): Float {
        if (isBypass) return v0
        val v3 = v0 - ic2eqL
        val v1 = a1 * ic1eqL + a2 * v3
        val v2 = ic2eqL + a2 * ic1eqL + a3 * v3
        ic1eqL = 2f * v1 - ic1eqL
        ic2eqL = 2f * v2 - ic2eqL
        return m0 * v0 + m1 * v1 + m2 * v2
    }

    fun processRight(v0: Float): Float {
        if (isBypass) return v0
        val v3 = v0 - ic2eqR
        val v1 = a1 * ic1eqR + a2 * v3
        val v2 = ic2eqR + a2 * ic1eqR + a3 * v3
        ic1eqR = 2f * v1 - ic1eqR
        ic2eqR = 2f * v2 - ic2eqR
        return m0 * v0 + m1 * v1 + m2 * v2
    }
}


/**
 * On-the-fly Psychoacoustic Loudness Normalizer (ITU-R BS.1770-4 K-Weighting + Gated Dual-Horizon LUFS
 * + 43ms Lookahead Peak Guard + Noisy Car Cabin Road-Noise Masking Compensation).
 */
class RealtimeLoudnessNormalizer(sampleRate: Int = 48000) {

    companion object {
        private const val NUM_SHORT_TERM_BLOCKS = 30 // 30 x 100ms = 3.0s Short-Term window
        private const val NUM_MOMENTARY_BLOCKS = 4   // 4 x 100ms = 400ms Momentary window
        private const val GATE_FREEZE_LUFS = -50.0f  // Below -50 LUFS -> freeze gain (no noise pumping)
        private const val PEAK_CEILING_LINEAR = 0.891f // -1.0 dBFS peak headroom target
    }

    private var sr: Int = sampleRate.coerceAtLeast(8000)
    private val nativeHandle: Long =
        if (RustNativeBridge.isAvailable) RustNativeBridge.nativeLoudnessCreate(sr) else 0L
    private val telemetryOut5 = FloatArray(5)

    // ITU-R BS.1770-4 K-Weighting stages (Stage 1: +4dB High-Shelf @ 1681.97Hz, Stage 2: RLB HPF @ 38.135Hz)
    private val kStage1 = TptSvfFilter()
    private val kStage2 = TptSvfFilter()

    // Noisy Car Cabin Road-Noise Masking Compensation filters
    private val carSubsonicHp = TptSvfFilter()
    private val carUpperBassBell = TptSvfFilter()
    private val carVocalClarityBell = TptSvfFilter()

    // Gated 100ms block ring buffer (stores K-weighted mean square)
    private val gatedBlocks = FloatArray(NUM_SHORT_TERM_BLOCKS)
    private var gatedWriteIdx = 0
    private var gatedCount = 0

    // Raw recent 4 blocks for 400ms Momentary detection
    private val recentBlocks = FloatArray(NUM_MOMENTARY_BLOCKS)
    private var recentWriteIdx = 0
    private var recentCount = 0

    private var blockEnergyAcc = 0.0
    private var blockSampleCount = 0
    private var samplesPerSubUpdate = sr / 20 // 50ms fast cold-start sub-block, transitions to 100ms

    // Track-level slow anchor LUFS for Normal Hi-Fi crest-factor preservation
    private var slowAnchorLufs = -16.0f
    private var anchorInitialised = false

    // Lookahead peak follower (fed 43ms ahead from input ring buffer)
    private var lookaheadPeakEnv = 0.05f
    private var postEqPeakEnv = 0.05f
    private var lastBassPunchDb = 0.0f
    private var peakReleaseCoef = 0.9995f

    // Gain state & telemetry
    private var targetGainDb = 0.0f
    private var currentGainDb = 0.0f
    private var currentLinearGain = 1.0f
    private var controlCounter = 0

    @Volatile var measuredLufs: Float = -24.0f
        private set
    @Volatile var appliedGainDb: Float = 0.0f
        private set
    @Volatile var isGateFrozen: Boolean = true
        private set
    @Volatile var dynamicClarityBoostDb: Float = 0.0f
        private set
    @Volatile var carBassBoostDb: Float = 0.0f
        private set

    init {
        configureFiltersForSampleRate(sr)
    }

    @Synchronized
    fun setSampleRate(newSr: Int) {
        val validSr = newSr.coerceAtLeast(8000)
        if (validSr != sr) {
            sr = validSr
            if (nativeHandle != 0L) {
                RustNativeBridge.nativeLoudnessSetSampleRate(nativeHandle, sr)
            }
            configureFiltersForSampleRate(sr)
            resetForNewTrack()
        }
    }

    private fun configureFiltersForSampleRate(sampleRate: Int) {
        kStage1.setHighShelf(sampleRate, freqHz = 1681.97f, q = 0.7071f, gainDb = 4.0f)
        kStage2.setHighPass(sampleRate, freqHz = 38.135f, q = 0.5003f)
        carSubsonicHp.setHighPass(sampleRate, freqHz = 35.0f, q = 0.7071f)
        carUpperBassBell.setBell(sampleRate, freqHz = 95.0f, q = 0.90f, gainDb = 2.0f)
        carVocalClarityBell.setBell(sampleRate, freqHz = 2600.0f, q = 0.85f, gainDb = 0.0f)
        peakReleaseCoef = exp(-1.0f / (sampleRate * 0.085f))
        samplesPerSubUpdate = (sampleRate / 20).coerceAtLeast(256)
    }

    /**
     * Called on track seek/skip so the new track converges within ~200-300ms without inheriting stale LUFS.
     */
    @Synchronized
    fun resetForNewTrack() {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeLoudnessReset(nativeHandle)
        }
        kStage1.reset()
        kStage2.reset()
        carSubsonicHp.reset()
        carUpperBassBell.reset()
        carVocalClarityBell.reset()
        gatedBlocks.fill(0f)
        recentBlocks.fill(0f)
        gatedWriteIdx = 0
        gatedCount = 0
        recentWriteIdx = 0
        recentCount = 0
        blockEnergyAcc = 0.0
        blockSampleCount = 0
        anchorInitialised = false
        lookaheadPeakEnv = 0.05f
        postEqPeakEnv = 0.05f
        lastBassPunchDb = 0.0f
        targetGainDb = 0.0f
        currentGainDb = 0.0f
        currentLinearGain = 1.0f
        appliedGainDb = 0.0f
        dynamicClarityBoostDb = 0.0f
        carBassBoostDb = 0.0f
        isGateFrozen = true
    }

    /**
     * Feed raw stereo samples ~43ms ahead of the delayed DSP output so sudden loud transients are anticipated.
     */
    fun pushLookaheadSample(rawL: Float, rawR: Float) {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeLoudnessPushLookahead(nativeHandle, rawL, rawR)
            return
        }
        val absPeak = max(abs(rawL), abs(rawR))
        lookaheadPeakEnv = if (absPeak > lookaheadPeakEnv) {
            lookaheadPeakEnv * 0.15f + absPeak * 0.85f
        } else {
            lookaheadPeakEnv * peakReleaseCoef
        }
    }

    private fun modeCode(mode: LoudnessMode): Int = when (mode) {
        LoudnessMode.OFF -> 0
        LoudnessMode.NORMAL_HIFI -> 1
        LoudnessMode.NOISY_CAR -> 2
    }

    private fun unpackTelemetry() {
        measuredLufs = telemetryOut5[0]
        appliedGainDb = telemetryOut5[1]
        isGateFrozen = telemetryOut5[2] > 0.5f
        dynamicClarityBoostDb = telemetryOut5[3]
        carBassBoostDb = telemetryOut5[4]
    }

    @Synchronized
    fun processMonoSample(input: Float, config: LoudnessConfig): Float {
        if (nativeHandle != 0L) {
            val y = RustNativeBridge.nativeLoudnessProcessMono(
                nativeHandle,
                input,
                modeCode(config.mode),
                config.normalTargetLufs,
                config.carTargetLufs,
                config.carCabinIntensity,
                telemetryOut5,
            )
            unpackTelemetry()
            return y
        }
        observeKWeightedEnergy(input * 1.0f)
        if (config.mode == LoudnessMode.OFF) {
            currentGainDb *= 0.995f
            currentLinearGain = 1.0f
            appliedGainDb = 0.0f
            dynamicClarityBoostDb = 0.0f
            carBassBoostDb = 0.0f
            return input
        }

        updateControlStepIfNeeded(config)
        var y = input * currentLinearGain
        if (config.mode == LoudnessMode.NOISY_CAR) {
            y = carSubsonicHp.processMono(y)
            y = carUpperBassBell.processMono(y)
            y = carVocalClarityBell.processMono(y)
        }
        observePostEqPeak(abs(y))
        return y
    }

    private fun observePostEqPeak(absPeak: Float) {
        val p = if (absPeak.isFinite()) absPeak else 0f
        postEqPeakEnv = if (p > postEqPeakEnv) {
            postEqPeakEnv * 0.25f + p * 0.75f
        } else {
            postEqPeakEnv * peakReleaseCoef
        }
    }

    @Synchronized
    fun processStereoSample(inL: Float, inR: Float, config: LoudnessConfig, outPair: FloatArray) {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeLoudnessProcessStereo(
                nativeHandle,
                inL,
                inR,
                modeCode(config.mode),
                config.normalTargetLufs,
                config.carTargetLufs,
                config.carCabinIntensity,
                outPair,
                telemetryOut5,
            )
            unpackTelemetry()
            return
        }
        val powerProxy = sqrt(0.5f * (inL * inL + inR * inR))
        observeKWeightedEnergy(powerProxy)
        if (config.mode == LoudnessMode.OFF) {
            currentGainDb *= 0.995f
            currentLinearGain = 1.0f
            appliedGainDb = 0.0f
            dynamicClarityBoostDb = 0.0f
            carBassBoostDb = 0.0f
            outPair[0] = inL
            outPair[1] = inR
            return
        }

        updateControlStepIfNeeded(config)
        var yL = inL * currentLinearGain
        var yR = inR * currentLinearGain
        if (config.mode == LoudnessMode.NOISY_CAR) {
            yL = carSubsonicHp.processMono(yL)
            yR = carSubsonicHp.processRight(yR)
            yL = carUpperBassBell.processMono(yL)
            yR = carUpperBassBell.processRight(yR)
            yL = carVocalClarityBell.processMono(yL)
            yR = carVocalClarityBell.processRight(yR)
        }
        observePostEqPeak(max(abs(yL), abs(yR)))
        outPair[0] = yL
        outPair[1] = yR
    }

    private fun observeKWeightedEnergy(sample: Float) {
        val kw = kStage2.processMono(kStage1.processMono(sample))
        blockEnergyAcc += (kw * kw).toDouble()
        blockSampleCount++

        // Fast 50ms window during cold-start (< 6 gated blocks), then standard 100ms ITU block
        val targetBlockSamples = if (gatedCount < 6) samplesPerSubUpdate else samplesPerSubUpdate * 2
        if (blockSampleCount >= targetBlockSamples) {
            val meanSq = (blockEnergyAcc / blockSampleCount).toFloat()
            blockEnergyAcc = 0.0
            blockSampleCount = 0

            recentBlocks[recentWriteIdx] = meanSq
            recentWriteIdx = (recentWriteIdx + 1) % NUM_MOMENTARY_BLOCKS
            if (recentCount < NUM_MOMENTARY_BLOCKS) recentCount++

            var momSum = 0.0
            for (i in 0 until recentCount) momSum += recentBlocks[i]
            val momMeanSq = (momSum / recentCount.coerceAtLeast(1)).toFloat()
            val momLufs = (-0.691f + 10f * log10(momMeanSq.coerceAtLeast(1e-10f))).coerceIn(-70f, 3f)
            val blockLufs = (-0.691f + 10f * log10(meanSq.coerceAtLeast(1e-10f))).coerceIn(-70f, 3f)

            if (blockLufs < GATE_FREEZE_LUFS || momLufs < GATE_FREEZE_LUFS) {
                isGateFrozen = true
            } else {
                isGateFrozen = false
                gatedBlocks[gatedWriteIdx] = meanSq
                gatedWriteIdx = (gatedWriteIdx + 1) % NUM_SHORT_TERM_BLOCKS
                if (gatedCount < NUM_SHORT_TERM_BLOCKS) gatedCount++

                // Relative -10 LU gate per ITU-R BS.1770-4
                var unGatedSum = 0.0
                for (i in 0 until gatedCount) unGatedSum += gatedBlocks[i]
                val relThresholdMeanSq = ((unGatedSum / gatedCount) * 0.1).toFloat() // -10 dB relative gate

                var gatedSum = 0.0
                var activeCnt = 0
                for (i in 0 until gatedCount) {
                    val b = gatedBlocks[i]
                    if (b >= relThresholdMeanSq) {
                        gatedSum += b
                        activeCnt++
                    }
                }
                val stMeanSq = if (activeCnt > 0) (gatedSum / activeCnt).toFloat() else momMeanSq
                val stLufs = (-0.691f + 10f * log10(stMeanSq.coerceAtLeast(1e-10f))).coerceIn(-65f, 3f)
                measuredLufs = stLufs

                if (!anchorInitialised) {
                    slowAnchorLufs = stLufs
                    anchorInitialised = true
                } else {
                    // Slow 8-second time constant for track-level anchor
                    slowAnchorLufs = slowAnchorLufs * 0.985f + stLufs * 0.015f
                }
            }
        }
    }

    private fun updateControlStepIfNeeded(config: LoudnessConfig) {
        controlCounter++
        if (controlCounter < 16) return
        val dtSec = controlCounter.toFloat() / sr.toFloat()
        controlCounter = 0

        val intensity = config.carCabinIntensity.coerceIn(0f, 2f)

        if (!isGateFrozen && anchorInitialised) {
            val rawDesiredDb = when (config.mode) {
                LoudnessMode.OFF -> 0f
                LoudnessMode.NORMAL_HIFI -> {
                    // Preserve intra-track crest factor by tracking 70% slow track anchor + 30% short-term
                    val refLufs = slowAnchorLufs * 0.70f + measuredLufs * 0.30f
                    (config.normalTargetLufs - refLufs).coerceIn(-12.0f, 10.5f)
                }
                LoudnessMode.NOISY_CAR -> {
                    // Doubled speed-linked overall trim (+0.0 idle -> +4.4 std -> +8.8 max)
                    val speedGainDb = 4.4f * intensity
                    val baseDeficit = config.carTargetLufs - measuredLufs
                    val maxUpwardBoost = min(6.5f + 15.0f * intensity, 24.0f)
                    if (baseDeficit > 0f) {
                        val upwardRatio = (0.68f + 0.27f * intensity).coerceIn(0.68f, 1.0f)
                        min(min(baseDeficit * upwardRatio, maxUpwardBoost) + speedGainDb, 26.0f)
                    } else {
                        baseDeficit.coerceAtLeast(-18.0f) + speedGainDb
                    }
                }
            }
            targetGainDb = rawDesiredDb
        } else if (isGateFrozen) {
            // Immediately freeze any pending upward target so gain never creeps higher during silence
            if (currentGainDb >= 0f && targetGainDb > currentGainDb) {
                targetGainDb = currentGainDb
            }
            // Gently relax toward 0 dB at ~0.2 dB/s during prolonged silence
            val relaxStep = 0.20f * dtSec
            targetGainDb = when {
                targetGainDb > relaxStep -> targetGainDb - relaxStep
                targetGainDb < -relaxStep -> targetGainDb + relaxStep
                else -> 0f
            }
        }

        // Lookahead Peak Ceiling Guard: reserve NoisyCar EQ headroom & feed back post-EQ peak overshoot
        val eqReserveDb = if (config.mode == LoudnessMode.NOISY_CAR) {
            val activeEq = (lastBassPunchDb * 0.42f + dynamicClarityBoostDb * 0.22f).coerceIn(0f, 5.2f)
            val postOverDb = if (postEqPeakEnv > 0.85f) {
                (20f * log10(postEqPeakEnv / 0.85f)).coerceIn(0f, 9.0f)
            } else 0f
            max(activeEq, postOverDb)
        } else 0f

        val maxSafeGainDb = if (lookaheadPeakEnv > 1e-4f) {
            20f * log10(PEAK_CEILING_LINEAR / lookaheadPeakEnv) - eqReserveDb
        } else 18f
        val guardedTargetDb = min(targetGainDb, maxSafeGainDb)

        // Smooth ballistics (fast convergence on cold-start, fast ducking on lookahead peak, musical release)
        val isColdStart = gatedCount in 1..8
        val tauSec = when {
            guardedTargetDb < currentGainDb - 1.5f && abs(guardedTargetDb - maxSafeGainDb) < 1e-4f -> 0.020f // 20ms lookahead guard
            isColdStart -> 0.22f // Fast ~250ms initial track-start convergence
            config.mode == LoudnessMode.NORMAL_HIFI -> if (guardedTargetDb < currentGainDb) 0.40f else 2.20f
            else -> if (guardedTargetDb < currentGainDb) 0.09f else 0.50f // Car Cabin agile leveler
        }
        val alpha = (1f - exp(-dtSec / tauSec)).coerceIn(0.0005f, 1f)
        currentGainDb += alpha * (guardedTargetDb - currentGainDb)
        currentLinearGain = 10.0.pow(currentGainDb / 20.0).toFloat()
        appliedGainDb = currentGainDb

        // Update Noisy Car road-noise masking contours dynamically
        if (config.mode == LoudnessMode.NOISY_CAR) {
            val headroomScale = if (postEqPeakEnv > 0.86f) {
                (1f - ((postEqPeakEnv - 0.86f) / 0.45f)).coerceIn(0.45f, 1f)
            } else 1f
            val bassPunchDb = (1.0f + 3.6f * intensity) * headroomScale
            lastBassPunchDb = bassPunchDb
            carBassBoostDb = bassPunchDb
            carUpperBassBell.setBell(sr, freqHz = 95.0f, q = 0.90f, gainDb = bassPunchDb)

            // Boost vocal clarity (+0..+6.8 dB std, up to +10.5 dB max) scaling with passage quietness
            val quietnessFactor = ((-8.0f - measuredLufs) / 24.0f).coerceIn(0.25f, 1.0f)
            val clarityDb = (6.8f * intensity * quietnessFactor).coerceIn(0f, 10.5f) * headroomScale
            dynamicClarityBoostDb = clarityDb
            carVocalClarityBell.setBell(sr, freqHz = 2600.0f, q = 0.85f, gainDb = clarityDb)
        } else {
            lastBassPunchDb = 0f
            carBassBoostDb = 0f
            dynamicClarityBoostDb = 0.0f
        }
    }
}
