package com.example.monoplayer.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Real-time Stereo-to-Mono DSP engine + 10-band EQ + preamp + visualizer telemetry.
 *
 * Timing model (important for glitch-free A/B switching and correct overlap-add):
 *  - Every algorithm outputs the signal delayed by exactly [FFT_SIZE] samples, so switching algorithms
 *    never produces a time jump.
 *  - STFT: Hann analysis + Hann synthesis, hop = FFT_SIZE/4, normalised by the accumulated window power.
 *
 * Fixes vs. the first version:
 *  - Continuous soft limiter (the old one had discontinuities at 0.85 / 1.2 which produced crackling).
 *  - Gain (preamp / crossfade) is smoothed per sample (no zipper noise).
 *  - OLA read/write positions are tracked with absolute sample indices (no stale data wrap-around).
 *  - Spectrum bands always contain at least one FFT bin (no empty bands showing 0 around 250 Hz).
 *  - PCA algorithm can no longer cancel centre-panned material (vocal "karaoke" effect).
 *  - Phase alignment no longer applies the arbitrary 1.2 x normalisation (centre material stays at unity gain).
 */
@UnstableApi
class MonoDspAudioProcessor : AudioProcessor {

    companion object {
        const val FFT_SIZE = 2048
        const val HOP_SIZE = 512
        const val NUM_BANDS = 32
        private const val MASK = FFT_SIZE - 1
        private const val OLA_MASK = FFT_SIZE * 2 - 1
        private const val BAND_MIN_HZ = 40f
        private const val BAND_MAX_HZ = 18000f
    }

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var inputEnded = false

    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var internalBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    private val fft = FastFFT(FFT_SIZE)
    private val windowFunc = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * Math.PI * i / FFT_SIZE))).toFloat()
    }

    private val inRingL = FloatArray(FFT_SIZE)
    private val inRingR = FloatArray(FFT_SIZE)
    private val olaOut = FloatArray(FFT_SIZE * 2)
    private val olaNorm = FloatArray(FFT_SIZE * 2)
    private var ringWritePos = 0
    private var samplesSinceHop = 0
    private var absCount = 0L // number of input samples pushed since last flush

    private val lR = FloatArray(FFT_SIZE)
    private val lI = FloatArray(FFT_SIZE)
    private val rR = FloatArray(FFT_SIZE)
    private val rI = FloatArray(FFT_SIZE)
    private val outR = FloatArray(FFT_SIZE)
    private val outI = FloatArray(FFT_SIZE)

    // Per-bin magnitudes for the visualizer
    private val actMag = FloatArray(FFT_SIZE / 2)
    private val naiveMag = FloatArray(FFT_SIZE / 2)

    // Band -> FFT bin ranges (recomputed when the sample rate changes)
    private val bandLo = IntArray(NUM_BANDS)
    private val bandHi = IntArray(NUM_BANDS)
    private var bandsSampleRate = 0

    // Dynamic PCA state (works on the delayed signal)
    private var lpL = 0f
    private var lpR = 0f
    private val pcaBlock = 256
    private var pcaCount = 0
    private var covLL = 0f
    private var covRR = 0f
    private var pcaDominantSign = 1f   // +1 => left channel dominates the HF principal axis, -1 => right
    private var pcaSignSmooth = 1f

    // Smoothed gain
    private var smoothGain = 1f
    private var gainInitialised = false

    // Telemetry
    private val visSpectrum = FloatArray(NUM_BANDS)
    private val visNaive = FloatArray(NUM_BANDS)
    private val visWaveform = FloatArray(128)
    private var visWaveWriteIdx = 0
    private var frameAcc = 0
    private var sumSqL = 0f
    private var sumSqR = 0f
    private var metricCorr = 0.85f
    private var metricLossDb = -2.4f
    private var winPreBoostPeak = 0f
    private var winPostBoostPeak = 0f
    private var winOutPeak = 0f
    private var winMaxGrDb = 0f

    private val stereoTemp = FloatArray(2)
    private val declipTemp = FloatArray(2)

    // Native Rust STFT Mono Engine & Mastering Peak Limiter
    private var nativeStftHandle: Long = 0L
    private var nativeLimiterHandle: Long = 0L
    private val limiterOutPair = FloatArray(3) // [yl, yr, gainReductionDb]
    private var limiterPeakEnv = 0f
    private var limiterGainSmooth = 1f
    private val nativeOrderedRingL = FloatArray(FFT_SIZE)
    private val nativeOrderedRingR = FloatArray(FFT_SIZE)
    private val nativeOlaTime = FloatArray(FFT_SIZE)
    private val nativeOlaNorm = FloatArray(FFT_SIZE)
    private val nativeMetrics = FloatArray(2)

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        DspStateHolder.declipProcessor.setSampleRate(inputAudioFormat.sampleRate)
        DspStateHolder.stereoDepth.setSampleRate(inputAudioFormat.sampleRate)
        DspStateHolder.loudnessNormalizer.setSampleRate(inputAudioFormat.sampleRate)
        DspStateHolder.dynamicEq.setSampleRate(inputAudioFormat.sampleRate)
        DspStateHolder.equalizer.setSampleRate(inputAudioFormat.sampleRate)

        if (RustNativeBridge.isAvailable) {
            if (nativeStftHandle == 0L) {
                nativeStftHandle = RustNativeBridge.nativeStftMonoCreate(inputAudioFormat.sampleRate)
            } else {
                RustNativeBridge.nativeStftMonoSetSampleRate(nativeStftHandle, inputAudioFormat.sampleRate)
            }
            if (nativeLimiterHandle == 0L) {
                nativeLimiterHandle = RustNativeBridge.nativeLimiterCreate(inputAudioFormat.sampleRate)
            } else {
                RustNativeBridge.nativeLimiterSetSampleRate(nativeLimiterHandle, inputAudioFormat.sampleRate)
            }
        }

        // Always output 2ch 16-bit PCM (identical channels for the mono algorithms).
        this.outputAudioFormat = AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
        return outputAudioFormat
    }

    override fun isActive(): Boolean = inputAudioFormat != AudioFormat.NOT_SET

    private fun ensureBands(sr: Int) {
        if (bandsSampleRate == sr) return
        bandsSampleRate = sr
        val step = sr.toFloat() / FFT_SIZE
        val maxBin = FFT_SIZE / 2 - 1
        val ratio = BAND_MAX_HZ / BAND_MIN_HZ
        for (b in 0 until NUM_BANDS) {
            val fLo = BAND_MIN_HZ * ratio.pow(b.toFloat() / NUM_BANDS)
            val fHi = BAND_MIN_HZ * ratio.pow((b + 1).toFloat() / NUM_BANDS)
            var kLo = ceil(fLo / step).toInt()
            var kHi = floor(fHi / step).toInt()
            if (kHi < kLo) {
                // Narrow band between two bins: use the bin nearest to the band centre so it is never empty.
                val k = (sqrt(fLo * fHi) / step).roundToInt()
                kLo = k
                kHi = k
            }
            bandLo[b] = kLo.coerceIn(1, maxBin)
            bandHi[b] = kHi.coerceIn(bandLo[b], maxBin)
        }
    }

    /** Continuous soft limiter: identity below the knee (0.88), smooth tanh compression above, never exceeds 1.0. */
    private fun softLimit(x: Float): Float {
        if (RustNativeBridge.isAvailable) {
            return RustNativeBridge.nativeSoftLimit(x)
        }
        if (!x.isFinite()) return 0f
        val knee = 0.88f
        val a = abs(x)
        if (a <= knee) return x
        val over = (a - knee) / (1f - knee)
        val y = knee + (1f - knee) * tanh(over.toDouble()).toFloat()
        return if (x < 0f) -y else y
    }

    /**
     * Stereo-linked smooth envelope peak limiter prior to softLimit/quantization so overloaded
     * multi-stage EQ boosts scale cleanly without wave-crest square clipping.
     */
    private fun applyMasteringLimiter(inL: Float, inR: Float, sr: Int) {
        if (nativeLimiterHandle != 0L) {
            RustNativeBridge.nativeLimiterProcessStereo(nativeLimiterHandle, inL, inR, limiterOutPair)
            return
        }
        val xl = if (inL.isFinite()) inL else 0f
        val xr = if (inR.isFinite()) inR else 0f
        val absPeak = max(abs(xl), abs(xr))
        val srF = sr.coerceAtLeast(8000).toFloat()
        val attackCoef = (1f - exp(-1f / (srF * 0.00025f))).coerceIn(0.01f, 1f)
        val releaseCoef = (1f - exp(-1f / (srF * 0.120f))).coerceIn(0.00002f, 0.05f)

        limiterPeakEnv = if (absPeak > limiterPeakEnv) {
            absPeak
        } else {
            limiterPeakEnv + releaseCoef * (absPeak - limiterPeakEnv)
        }

        val threshold = 0.84f
        val targetGain = if (limiterPeakEnv > threshold) {
            (threshold / limiterPeakEnv).coerceIn(0.05f, 1f)
        } else 1f
        val coef = if (targetGain < limiterGainSmooth) attackCoef else releaseCoef
        limiterGainSmooth = (limiterGainSmooth + coef * (targetGain - limiterGainSmooth)).coerceIn(0.05f, 1f)

        val minSafeGain = if (absPeak > 0.94f) (0.94f / absPeak).coerceIn(0.05f, 1f) else 1f
        val effGain = min(limiterGainSmooth, minSafeGain)
        limiterOutPair[0] = softLimit(xl * effGain)
        limiterOutPair[1] = softLimit(xr * effGain)
        val outPeak = max(abs(limiterOutPair[0]), abs(limiterOutPair[1]))
        val totalGain = if (absPeak > 1e-5f) (outPeak / absPeak).coerceIn(0.05f, 1f) else limiterGainSmooth
        limiterOutPair[2] = max(0f, -20f * log10(totalGain))
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val inChannels = inputAudioFormat.channelCount
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = inChannels * bytesPerSample
        val numFrames = inputBuffer.remaining() / frameSize

        val outBytesNeeded = numFrames * 4
        if (internalBuffer.capacity() < outBytesNeeded) {
            internalBuffer = ByteBuffer.allocateDirect(outBytesNeeded).order(ByteOrder.nativeOrder())
        } else {
            internalBuffer.clear()
        }

        val sr = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        ensureBands(sr)
        val algo = DspStateHolder.algorithm
        val blend = DspStateHolder.sparkleBlendPercent / 100f
        val crossoverHz = DspStateHolder.crossoverFreqHz
        val cfSec = DspStateHolder.crossfadeSec
        val isCrossfadeActive = cfSec > 0.05f
        val preampLinear = 10.0.pow(DspStateHolder.preampGainDb / 20.0).toFloat()
        val baseExternalMultiplier = DspStateHolder.crossfadeMultiplier.coerceIn(0f, 1f)
        val gainCoef = 1f - exp(-1f / (sr * 0.010f)) // ~10 ms smoothing
        val signCoef = 1f - exp(-1f / (sr * 0.006f))
        val eqEnabled = DspStateHolder.eqEnabled
        val eq = DspStateHolder.equalizer
        val stereoDepth = DspStateHolder.stereoDepth
        val spatialCfg = DspStateHolder.spatialConfig
        val loudNorm = DspStateHolder.loudnessNormalizer
        val loudCfg = DspStateHolder.loudnessConfig
        val dynEq = DspStateHolder.dynamicEq
        val dynEqCfg = DspStateHolder.dynamicEqConfig
        val declip = DspStateHolder.declipProcessor
        val declipCfg = DspStateHolder.declipConfig

        // 1st-order RC low-pass for the PCA split
        val rc = 1.0f / (2.0f * Math.PI.toFloat() * crossoverHz)
        val alphaLP = (1.0f / sr) / (rc + 1.0f / sr)

        for (frame in 0 until numFrames) {
            val rawL: Float
            val rawR: Float
            if (isFloat) {
                rawL = inputBuffer.float
                rawR = if (inChannels > 1) inputBuffer.float else rawL
                for (ch in 2 until inChannels) inputBuffer.float
            } else {
                rawL = inputBuffer.short / 32768f
                rawR = if (inChannels > 1) inputBuffer.short / 32768f else rawL
                for (ch in 2 until inChannels) inputBuffer.short
            }

            // Real-time Anti-Clipping Reconstruction & De-Clicking
            val cleanL: Float
            val cleanR: Float
            if (declipCfg.enabled) {
                declip.processStereoSample(rawL, rawR, declipCfg, declipTemp)
                cleanL = declipTemp[0]
                cleanR = declipTemp[1]
            } else {
                cleanL = rawL
                cleanR = rawR
            }

            // 43ms Lookahead peak envelope (fed FFT_SIZE samples ahead of delayed output)
            loudNorm.pushLookaheadSample(cleanL, cleanR)

            // Push into the ring; after the increment ringWritePos points to the oldest sample,
            // i.e. the sample that is exactly FFT_SIZE-1 samples older than the one just written.
            inRingL[ringWritePos] = cleanL
            inRingR[ringWritePos] = cleanR
            ringWritePos = (ringWritePos + 1) and MASK
            absCount++
            samplesSinceHop++

            if (samplesSinceHop >= HOP_SIZE) {
                samplesSinceHop = 0
                if (absCount >= FFT_SIZE) processStftHop(sr, algo, blend, crossoverHz)
            }

            val dL = inRingL[ringWritePos]
            val dR = inRingR[ringWritePos]

            // STFT output for absolute index p = absCount - FFT_SIZE
            var stftSample = 0.5f * (dL + dR)
            if (absCount >= FFT_SIZE) {
                val p = ((absCount - FFT_SIZE).toInt()) and OLA_MASK
                val norm = olaNorm[p]
                if (norm > 0.1f) stftSample = olaOut[p] / norm
                olaOut[p] = 0f
                olaNorm[p] = 0f
            } else {
                stftSample = 0f
            }

            // Dynamic PCA runs on the delayed signal (same timing as the STFT path)
            lpL += alphaLP * (dL - lpL)
            lpR += alphaLP * (dR - lpR)
            val hfL = dL - lpL
            val hfR = dR - lpR
            covLL += hfL * hfL
            covRR += hfR * hfR
            pcaCount++
            if (pcaCount >= pcaBlock) {
                // Hysteresis: only change the dominant channel when it is clearly dominant.
                if (covLL > covRR * 1.25f) pcaDominantSign = 1f
                else if (covRR > covLL * 1.25f) pcaDominantSign = -1f
                covLL = 0f
                covRR = 0f
                pcaCount = 0
            }
            pcaSignSmooth += signCoef * (pcaDominantSign - pcaSignSmooth)

            // Head fade calculation in sample domain: eliminates cold-start volume burst completely
            val outSampleCount = (absCount - (FFT_SIZE - 1)).coerceAtLeast(0L)
            val outElapsedSec = outSampleCount.toFloat() / sr
            val headFadeMultiplier = if (isCrossfadeActive) {
                (outElapsedSec / cfSec).coerceIn(0f, 1f)
            } else {
                1.0f
            }
            val targetGain = preampLinear * baseExternalMultiplier * headFadeMultiplier

            if (!gainInitialised) {
                smoothGain = if (isCrossfadeActive) 0f else targetGain
                gainInitialised = true
            }

            smoothGain += gainCoef * (targetGain - smoothGain)

            val prePeakSample: Float
            var outL: Float
            var outR: Float
            if (algo == MonoAlgorithm.STEREO_REF) {
                prePeakSample = max(abs(dL), abs(dR))
                loudNorm.processStereoSample(dL, dR, loudCfg, stereoTemp)
                dynEq.processStereoSample(stereoTemp[0], stereoTemp[1], dynEqCfg, stereoTemp)
                if (eqEnabled) {
                    eq.processStereoSample(stereoTemp[0], stereoTemp[1], stereoTemp)
                }
                outL = stereoTemp[0] * smoothGain
                outR = stereoTemp[1] * smoothGain
            } else {
                val monoCore = when (algo) {
                    MonoAlgorithm.NAIVE -> 0.5f * (dL + dR)
                    MonoAlgorithm.DYNAMIC_PCA -> {
                        val midHF = 0.5f * (hfL + hfR)
                        val sideHF = 0.5f * (hfL - hfR)
                        val lfMid = 0.5f * (lpL + lpR)
                        val beta = min(1.3f, blend)
                        lfMid + midHF + beta * pcaSignSmooth * sideHF
                    }
                    MonoAlgorithm.HILBERT,
                    MonoAlgorithm.STFT_ENERGY,
                    MonoAlgorithm.PHASE_ALIGN -> stftSample
                    MonoAlgorithm.STEREO_REF -> 0.5f * (dL + dR)
                }

                stereoDepth.processSample(monoCore, dL, dR, spatialCfg, stereoTemp)
                prePeakSample = max(abs(stereoTemp[0]), abs(stereoTemp[1]))
                if (stereoTemp[0] == stereoTemp[1]) {
                    var mono = stereoTemp[0]
                    mono = loudNorm.processMonoSample(mono, loudCfg)
                    mono = dynEq.processMonoSample(mono, dynEqCfg)
                    if (eqEnabled) mono = eq.processMonoSample(mono)
                    mono *= smoothGain
                    outL = mono
                    outR = mono
                } else {
                    loudNorm.processStereoSample(stereoTemp[0], stereoTemp[1], loudCfg, stereoTemp)
                    dynEq.processStereoSample(stereoTemp[0], stereoTemp[1], dynEqCfg, stereoTemp)
                    if (eqEnabled) {
                        eq.processStereoSample(stereoTemp[0], stereoTemp[1], stereoTemp)
                    }
                    outL = stereoTemp[0] * smoothGain
                    outR = stereoTemp[1] * smoothGain
                }
            }

            if (prePeakSample > winPreBoostPeak) winPreBoostPeak = prePeakSample
            val postBoostSample = max(abs(outL), abs(outR))
            if (postBoostSample > winPostBoostPeak) winPostBoostPeak = postBoostSample

            applyMasteringLimiter(outL, outR, sr)
            outL = limiterOutPair[0]
            outR = limiterOutPair[1]
            val limitedPeakSample = max(abs(outL), abs(outR))
            if (limitedPeakSample > winOutPeak) winOutPeak = limitedPeakSample
            if (limiterOutPair[2] > winMaxGrDb) winMaxGrDb = limiterOutPair[2]

            sumSqL += outL * outL
            sumSqR += outR * outR

            if (frame % 16 == 0) {
                visWaveform[visWaveWriteIdx] = 0.5f * (outL + outR)
                visWaveWriteIdx = (visWaveWriteIdx + 1) and 127
            }

            internalBuffer.putShort((outL * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            internalBuffer.putShort((outR * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
        }

        internalBuffer.flip()
        outputBuffer = internalBuffer

        // Telemetry (~60 fps, queued into TelemetrySyncBuffer for speaker-latency-compensated sync)
        frameAcc += numFrames
        if (frameAcc >= sr / 60 && absCount >= FFT_SIZE) {
            val rmsL = sqrt(sumSqL / frameAcc)
            val rmsR = sqrt(sumSqR / frameAcc)
            val rms = sqrt(0.5f * (sumSqL + sumSqR) / frameAcc)
            val dbL = (20f * log10(rmsL.coerceAtLeast(1e-4f))).coerceIn(-60f, 0f)
            val dbR = (20f * log10(rmsR.coerceAtLeast(1e-4f))).coerceIn(-60f, 0f)
            val retention = when (algo) {
                MonoAlgorithm.NAIVE -> 0
                MonoAlgorithm.STEREO_REF -> 100
                else -> (DspStateHolder.sparkleBlendPercent * 0.95f + 15f).roundToInt().coerceIn(0, 100)
            }
            val ordered = FloatArray(128) { i -> visWaveform[(visWaveWriteIdx + i) and 127] }
            val nowMonoMs = System.nanoTime() / 1_000_000L
            val queuedStreamMs = (absCount * 1000L) / sr
            val preDb = (20f * log10(winPreBoostPeak.coerceAtLeast(1e-3f))).coerceIn(-60f, 6f)
            val postBoostDb = (20f * log10(winPostBoostPeak.coerceAtLeast(1e-3f))).coerceIn(-60f, 18f)
            val outPeakDb = (20f * log10(winOutPeak.coerceAtLeast(1e-3f))).coerceIn(-60f, 0f)
            val maxGrDb = winMaxGrDb.coerceIn(0f, 24f)
            DspStateHolder.publishTelemetry(
                data = VisualizerTelemetry(
                    spectrumDb = visSpectrum.copyOf(),
                    naiveSpectrumDb = visNaive.copyOf(),
                    waveform = ordered,
                    vuLeftDb = dbL,
                    vuRightDb = dbR,
                    rmsLinear = rms.coerceIn(0f, 1f),
                    hfPhaseCorrelation = metricCorr,
                    naiveHfLossDb = metricLossDb,
                    sparkleRetentionPercent = retention,
                    measuredLufs = loudNorm.measuredLufs,
                    loudnessGainDb = loudNorm.appliedGainDb,
                    loudnessGateFrozen = loudNorm.isGateFrozen,
                    carClarityBoostDb = loudNorm.dynamicClarityBoostDb,
                    dynEqDetectedDb = dynEq.getDetectedBandsDb(),
                    dynEqGainDeltaDb = dynEq.getDynamicGainDeltasDb(),
                    spatialSideRatio = stereoDepth.currentSideRatio,
                    spatialDepthDb = stereoDepth.currentDepthLevelDb,
                    declipRate = declip.currentClipRate,
                    declipEventsTotal = declip.repairedEventsTotal,
                    declipThresholdDb = declip.estimatedThresholdDb,
                    declipMaxPeak = declip.maxReconstructedPeak,
                    detectedSpeedKmh = DspStateHolder.currentDetectedSpeedKmh,
                    speedSource = DspStateHolder.currentSpeedSource,
                    carPreBoostDb = preDb,
                    carPostBoostDb = postBoostDb,
                    carOutputDb = outPeakDb,
                    limiterGrDb = maxGrDb,
                    carBassBoostDb = loudNorm.carBassBoostDb,
                    sampleRate = sr,
                    timestampMs = System.currentTimeMillis()
                ),
                produceRealtimeMs = nowMonoMs,
                queuedStreamMs = queuedStreamMs
            )
            frameAcc = 0
            sumSqL = 0f
            sumSqR = 0f
            winPreBoostPeak = 0f
            winPostBoostPeak = 0f
            winOutPeak = 0f
            winMaxGrDb = 0f
        }
    }

    private fun processStftHop(sr: Int, algo: MonoAlgorithm, blend: Float, crossoverFreq: Float) {
        if (nativeStftHandle != 0L) {
            for (i in 0 until FFT_SIZE) {
                val ringIdx = (ringWritePos + i) and MASK
                nativeOrderedRingL[i] = inRingL[ringIdx]
                nativeOrderedRingR[i] = inRingR[ringIdx]
            }
            val algoCode = when (algo) {
                MonoAlgorithm.NAIVE -> 0
                MonoAlgorithm.HILBERT -> 1
                MonoAlgorithm.STFT_ENERGY -> 2
                MonoAlgorithm.PHASE_ALIGN -> 3
                MonoAlgorithm.DYNAMIC_PCA -> 4
                MonoAlgorithm.STEREO_REF -> 5
            }
            RustNativeBridge.nativeStftMonoProcessHop(
                handle = nativeStftHandle,
                ringL = nativeOrderedRingL,
                ringR = nativeOrderedRingR,
                algoCode = algoCode,
                blend = blend,
                crossoverFreq = crossoverFreq,
                outOlaTime = nativeOlaTime,
                outOlaNorm = nativeOlaNorm,
                visSpec32 = visSpectrum,
                visNaive32 = visNaive,
                metrics2 = nativeMetrics
            )
            metricCorr = nativeMetrics[0]
            metricLossDb = nativeMetrics[1]

            val start = (absCount - FFT_SIZE).toInt()
            for (i in 0 until FFT_SIZE) {
                val idx = (start + i) and OLA_MASK
                olaOut[idx] += nativeOlaTime[i]
                olaNorm[idx] += nativeOlaNorm[i]
            }
            return
        }

        for (i in 0 until FFT_SIZE) {
            val ringIdx = (ringWritePos + i) and MASK // oldest first
            val w = windowFunc[i]
            lR[i] = inRingL[ringIdx] * w
            lI[i] = 0f
            rR[i] = inRingR[ringIdx] * w
            rI[i] = 0f
        }

        fft.transform(lR, lI, false)
        fft.transform(rR, rI, false)

        val half = FFT_SIZE shr 1
        // DC and Nyquist: plain average, purely real
        outR[0] = 0.5f * (lR[0] + rR[0]); outI[0] = 0f
        outR[half] = 0.5f * (lR[half] + rR[half]); outI[half] = 0f

        val binFreqStep = sr.toFloat() / FFT_SIZE
        var highMidPower = 1e-9f
        var highStereoPower = 1e-9f
        var corrNumer = 0f
        var corrDenomL = 1e-9f
        var corrDenomR = 1e-9f

        for (k in 1 until half) {
            val freq = k * binFreqStep
            val hfWeight = when {
                freq < crossoverFreq * 0.7f -> 0f
                freq > crossoverFreq * 1.3f -> 1f
                else -> (freq - crossoverFreq * 0.7f) / (crossoverFreq * 0.6f)
            }

            val lr = lR[k]; val li = lI[k]
            val rr = rR[k]; val ri = rI[k]

            val midR = 0.5f * (lr + rr)
            val midI = 0.5f * (li + ri)
            val sideR = 0.5f * (lr - rr)
            val sideI = 0.5f * (li - ri)

            val magL2 = lr * lr + li * li
            val magR2 = rr * rr + ri * ri
            val magMid = hypot(midR, midI)

            if (freq >= 3500f) {
                highMidPower += magMid * magMid
                highStereoPower += 0.5f * (magL2 + magR2)
                corrNumer += (lr * rr + li * ri)
                corrDenomL += magL2
                corrDenomR += magR2
            }

            var oR: Float
            var oI: Float
            when (algo) {
                MonoAlgorithm.STFT_ENERGY -> {
                    val target = sqrt(0.5f * (magL2 + magR2))
                    val finalMag = magMid + (target - magMid) * min(1.0f, blend * hfWeight)
                    if (magMid > 1e-7f) {
                        oR = finalMag * midR / magMid
                        oI = finalMag * midI / magMid
                    } else if (magL2 >= magR2 && magL2 > 1e-12f) {
                        val mL = sqrt(magL2)
                        oR = finalMag * lr / mL; oI = finalMag * li / mL
                    } else if (magR2 > 1e-12f) {
                        val mR = sqrt(magR2)
                        oR = finalMag * rr / mR; oI = finalMag * ri / mR
                    } else {
                        oR = 0f; oI = 0f
                    }
                }
                MonoAlgorithm.PHASE_ALIGN -> {
                    val magL = sqrt(magL2)
                    val magR = sqrt(magR2)
                    var aRr = rr
                    var aRi = ri
                    if (magL > 1e-7f && magR > 1e-7f) {
                        val factor = min(1.0f, blend * hfWeight)
                        aRr = rr + (magR * (lr / magL) - rr) * factor
                        aRi = ri + (magR * (li / magL) - ri) * factor
                    }
                    oR = 0.5f * (lr + aRr)
                    oI = 0.5f * (li + aRi)
                }
                else -> {
                    // HILBERT, and the analysis path for NAIVE / PCA / STEREO_REF:
                    // Mono = Mid + g * (-j) * Side  (90 degree rotated side injection)
                    val g = blend * hfWeight
                    oR = midR + g * sideI
                    oI = midI - g * sideR
                }
            }

            outR[k] = oR; outI[k] = oI
            val symK = FFT_SIZE - k
            outR[symK] = oR; outI[symK] = -oI

            actMag[k] = when (algo) {
                MonoAlgorithm.NAIVE -> magMid
                MonoAlgorithm.STEREO_REF -> sqrt(0.5f * (magL2 + magR2))
                // PCA keeps Mid and adds Side: show the energy envelope (never below Mid)
                MonoAlgorithm.DYNAMIC_PCA -> max(magMid, sqrt(0.5f * (magL2 + magR2)))
                else -> hypot(oR, oI)
            }
            naiveMag[k] = magMid
        }

        // Visualizer bands: RMS over the bins of each band (every band has at least one bin)
        val ampNorm = 1f / (FFT_SIZE * 0.25f)
        for (b in 0 until NUM_BANDS) {
            var sa = 0f
            var sn = 0f
            val lo = bandLo[b]
            val hi = bandHi[b]
            for (k in lo..hi) {
                sa += actMag[k] * actMag[k]
                sn += naiveMag[k] * naiveMag[k]
            }
            val n = (hi - lo + 1).toFloat()
            val ampA = sqrt(sa / n) * ampNorm
            val ampN = sqrt(sn / n) * ampNorm
            val tilt = b * 0.375f // gentle HF tilt so the right side of the analyser is visible
            val lvA = ((20f * log10(ampA.coerceAtLeast(1e-5f)) + 80f + tilt) / 80f).coerceIn(0f, 1f)
            val lvN = ((20f * log10(ampN.coerceAtLeast(1e-5f)) + 80f + tilt) / 80f).coerceIn(0f, 1f)
            // fast attack, slower release
            visSpectrum[b] = if (lvA > visSpectrum[b]) lvA else visSpectrum[b] * 0.82f + lvA * 0.18f
            visNaive[b] = if (lvN > visNaive[b]) lvN else visNaive[b] * 0.82f + lvN * 0.18f
        }

        val instantCorr = (corrNumer / sqrt(corrDenomL * corrDenomR)).coerceIn(-1f, 1f)
        val instantLoss = (10f * log10((highMidPower / highStereoPower).coerceAtLeast(0.004f))).coerceIn(-24f, 0f)
        metricCorr = metricCorr * 0.85f + instantCorr * 0.15f
        metricLossDb = metricLossDb * 0.85f + instantLoss * 0.15f

        fft.transform(outR, outI, true)

        // Overlap-add at absolute positions: frame covers samples [absCount - FFT_SIZE, absCount)
        val start = (absCount - FFT_SIZE).toInt()
        for (i in 0 until FFT_SIZE) {
            val w = windowFunc[i]
            val idx = (start + i) and OLA_MASK
            olaOut[idx] += outR[i] * w
            olaNorm[idx] += w * w
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        inRingL.fill(0f)
        inRingR.fill(0f)
        olaOut.fill(0f)
        olaNorm.fill(0f)
        ringWritePos = 0
        samplesSinceHop = 0
        absCount = 0L
        lpL = 0f
        lpR = 0f
        pcaCount = 0
        covLL = 0f
        covRR = 0f
        gainInitialised = false
        smoothGain = 0f
        limiterPeakEnv = 0f
        limiterGainSmooth = 1f
        winPreBoostPeak = 0f
        winPostBoostPeak = 0f
        winOutPeak = 0f
        winMaxGrDb = 0f
        DspStateHolder.resetCrossfadeOnTrackBoundary()
        DspStateHolder.syncBuffer.onFlush()
        DspStateHolder.stereoDepth.reset()
        DspStateHolder.loudnessNormalizer.resetForNewTrack()
        DspStateHolder.dynamicEq.reset()
        DspStateHolder.equalizer.resetState()
        if (nativeStftHandle != 0L) {
            RustNativeBridge.nativeStftMonoReset(nativeStftHandle)
        }
        if (nativeLimiterHandle != 0L) {
            RustNativeBridge.nativeLimiterReset(nativeLimiterHandle)
        }
    }

    override fun reset() {
        flush()
        if (nativeStftHandle != 0L) {
            RustNativeBridge.nativeStftMonoDestroy(nativeStftHandle)
            nativeStftHandle = 0L
        }
        if (nativeLimiterHandle != 0L) {
            RustNativeBridge.nativeLimiterDestroy(nativeLimiterHandle)
            nativeLimiterHandle = 0L
        }
        internalBuffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
        bandsSampleRate = 0
    }
}
