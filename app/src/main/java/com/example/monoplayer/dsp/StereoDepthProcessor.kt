package com.example.monoplayer.dsp

import kotlin.math.*

/**
 * Stereo-Informed Z-Axis Mono Depth & Adjustable Narrow Stereo Processor.
 *
 * 1. Extracts Mid (foreground coherent) and Side (wide/ambient) information from the time-aligned
 *    stereo pair (delayedL, delayedR) alongside the high-resolution monoCore signal.
 * 2. Applies Blauert's front-to-back directional band shaping (3.1 kHz presence lift on foreground Mid,
 *    3.1 kHz presence dip + 920 Hz warm body + distance air-absorption LPF on Side depth excitation).
 * 3. Generates physical Z-axis front-to-back depth via Haas ITDG Pre-Delay, 4 prime-spaced early
 *    stage reflection taps, 3 Schroeder all-pass diffusers, and a damped 2x2 orthogonal FDN tail.
 * 4. Synthesizes either bit-exact PURE_MONO (outL == outR) with 3D front-to-back layering, or
 *    elliptical-bass-locked NARROW_STEREO (<180 Hz pure mono, adjustable 5%..80% upper width).
 */
class StereoDepthProcessor(initialSampleRate: Int = 48000) {

    private class AllPassDiffuser(maxLen: Int) {
        private val buf = FloatArray(maxLen.coerceAtLeast(16))
        private var len = maxLen.coerceAtLeast(8)
        private var idx = 0

        fun configureLength(newLen: Int) {
            len = newLen.coerceIn(8, buf.size - 1)
            if (idx >= len) idx = 0
        }

        fun process(x: Float, g: Float = 0.52f): Float {
            val delayed = buf[idx]
            val v = x - g * delayed
            buf[idx] = v
            idx++
            if (idx >= len) idx = 0
            return delayed + g * v
        }

        fun reset() {
            buf.fill(0f)
            idx = 0
        }
    }

    companion object {
        private const val PD_SIZE = 8192
        private const val PD_MASK = PD_SIZE - 1
        private const val ITD_SIZE = 64
        private const val ITD_MASK = ITD_SIZE - 1
    }

    private var sr: Int = initialSampleRate.coerceAtLeast(8000)

    // Elliptical bass-lock & Mid/Side conditioning filters
    private val sideHpFilter = TptSvfFilter()
    private val midHpFilter = TptSvfFilter()

    // Blauert front-to-back spectral filters
    private val frontPresenceFilter = TptSvfFilter()
    private val rearPresenceDipFilter = TptSvfFilter()
    private val rearBodyFilter = TptSvfFilter()
    private val rearAirLpFilter = TptSvfFilter()

    // Pre-delay & early reflection circular buffer
    private val preDelayBuf = FloatArray(PD_SIZE)
    private var pdWrite = 0

    // Micro-ITD speaker crossfeed buffer for Narrow Stereo
    private val sideItdBuf = FloatArray(ITD_SIZE)
    private var itdWrite = 0
    private var itdDelaySamples = 13

    // Cascaded Schroeder all-pass diffusers
    private val ap1 = AllPassDiffuser(512)
    private val ap2 = AllPassDiffuser(768)
    private val ap3 = AllPassDiffuser(1024)

    // 2x2 Mutually-detuned damped FDN delay lines
    private val fdnBuf1 = FloatArray(4096)
    private val fdnBuf2 = FloatArray(4096)
    private var fdnLen1 = 1487
    private var fdnLen2 = 2003
    private var fdnIdx1 = 0
    private var fdnIdx2 = 0
    private var fdnLp1 = 0f
    private var fdnLp2 = 0f

    // Envelopes & smoothed parameters
    private var envMid = 0f
    private var envSide = 0f
    private var envDepthOut = 1e-6f
    private var smoothDepthWet = 0f
    private var smoothWidth = 0f
    private var smoothInit = false
    private var controlStep = 0

    @Volatile var currentSideRatio: Float = 0f
        private set
    @Volatile var currentDepthLevelDb: Float = -60f
        private set

    private val nativeHandle: Long =
        if (RustNativeBridge.isAvailable) RustNativeBridge.nativeDepthCreate(sr) else 0L
    private val outPairAndTelemetry4 = FloatArray(4)

    init {
        configureForSampleRate(sr)
    }

    @Synchronized
    fun setSampleRate(newSampleRate: Int) {
        val valid = newSampleRate.coerceAtLeast(8000)
        if (valid != sr) {
            sr = valid
            if (nativeHandle != 0L) {
                RustNativeBridge.nativeDepthSetSampleRate(nativeHandle, sr)
            }
            configureForSampleRate(sr)
            reset()
        }
    }

    private fun configureForSampleRate(sampleRate: Int) {
        val scale = sampleRate / 48000f
        sideHpFilter.setHighPass(sampleRate, 180f, 0.7071f)
        midHpFilter.setHighPass(sampleRate, 200f, 0.7071f)

        ap1.configureLength((223 * scale).roundToInt().coerceIn(24, 500))
        ap2.configureLength((349 * scale).roundToInt().coerceIn(32, 750))
        ap3.configureLength((523 * scale).roundToInt().coerceIn(48, 1000))

        fdnLen1 = (1487 * scale).roundToInt().coerceIn(128, fdnBuf1.size - 2)
        fdnLen2 = (2003 * scale).roundToInt().coerceIn(160, fdnBuf2.size - 2)
        itdDelaySamples = (0.00027f * sampleRate).roundToInt().coerceIn(2, 48)
    }

    @Synchronized
    fun reset() {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeDepthReset(nativeHandle)
        }
        sideHpFilter.reset()
        midHpFilter.reset()
        frontPresenceFilter.reset()
        rearPresenceDipFilter.reset()
        rearBodyFilter.reset()
        rearAirLpFilter.reset()
        preDelayBuf.fill(0f)
        sideItdBuf.fill(0f)
        pdWrite = 0
        itdWrite = 0
        ap1.reset()
        ap2.reset()
        ap3.reset()
        fdnBuf1.fill(0f)
        fdnBuf2.fill(0f)
        fdnIdx1 = 0
        fdnIdx2 = 0
        fdnLp1 = 0f
        fdnLp2 = 0f
        envMid = 0f
        envSide = 0f
        envDepthOut = 1e-6f
        smoothInit = false
        controlStep = 0
    }

    @Synchronized
    fun processSample(
        monoCore: Float,
        delayedL: Float,
        delayedR: Float,
        config: SpatialStageConfig,
        outPair: FloatArray
    ) {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeDepthProcessSample(
                nativeHandle,
                monoCore,
                delayedL,
                delayedR,
                config.stageMode == OutputStageMode.NARROW_STEREO,
                config.narrowWidthPercent,
                config.depthEnabled,
                config.depthAmount,
                config.preDelayMs,
                config.roomDecay,
                config.frontBackContrast,
                outPairAndTelemetry4,
            )
            outPair[0] = outPairAndTelemetry4[0]
            outPair[1] = outPairAndTelemetry4[1]
            currentSideRatio = outPairAndTelemetry4[2]
            currentDepthLevelDb = outPairAndTelemetry4[3]
            return
        }
        val targetWet = if (config.depthEnabled) config.depthAmount.coerceIn(0f, 1f) * 0.68f else 0f
        val targetWidth = if (config.stageMode == OutputStageMode.NARROW_STEREO) {
            (config.narrowWidthPercent / 100f).coerceIn(0.05f, 0.80f)
        } else {
            0f
        }

        if (!smoothInit) {
            smoothDepthWet = targetWet
            smoothWidth = targetWidth
            smoothInit = true
        } else {
            val paramAlpha = 0.0025f
            smoothDepthWet += paramAlpha * (targetWet - smoothDepthWet)
            smoothWidth += paramAlpha * (targetWidth - smoothWidth)
            if (targetWet == 0f && smoothDepthWet < 1e-5f) smoothDepthWet = 0f
            if (targetWidth == 0f && smoothWidth < 1e-5f) smoothWidth = 0f
        }

        // Fast dry pure-mono bypass when both spatial depth and narrow stereo are completely off
        if (smoothDepthWet == 0f && smoothWidth == 0f) {
            currentSideRatio *= 0.995f
            currentDepthLevelDb = -60f
            outPair[0] = monoCore
            outPair[1] = monoCore
            return
        }

        val rawMid = 0.5f * (delayedL + delayedR)
        val rawSide = 0.5f * (delayedL - delayedR)
        val sideHP = sideHpFilter.processMono(rawSide)
        val monoHP = midHpFilter.processMono(monoCore)

        // Foreground vs Background stereo dominance envelope
        envMid = envMid * 0.996f + abs(rawMid) * 0.004f
        envSide = envSide * 0.996f + abs(sideHP) * 0.004f
        val sideDom = (envSide / (envMid + envSide + 1e-5f)).coerceIn(0f, 1f)
        currentSideRatio = sideDom

        val contrast = config.frontBackContrast.coerceIn(0f, 1f)
        if ((controlStep and 15) == 0) {
            val frontDb = if (config.depthEnabled) 1.75f * contrast * (1f - sideDom * 0.45f) else 0f
            frontPresenceFilter.setBell(sr, 3150f, 1.15f, frontDb)
            rearPresenceDipFilter.setBell(sr, 3150f, 1.20f, -3.8f * contrast)
            rearBodyFilter.setBell(sr, 920f, 0.90f, 1.45f * contrast)
            rearAirLpFilter.setLowPass(sr, 8200f - 3800f * contrast, 0.7071f)
        }
        controlStep++

        val frontMono = if (smoothDepthWet > 0f && contrast > 0.01f) {
            frontPresenceFilter.processMono(monoCore)
        } else {
            monoCore
        }

        var depthMono = 0f
        var depthSide = 0f
        if (smoothDepthWet > 0f) {
            // 90% stereo Side excitation + 14% coherent Mid coupling for recessed Z-axis stage
            val rawExcite = sideHP * 0.90f + monoHP * 0.14f
            val recessed = rearAirLpFilter.processMono(
                rearBodyFilter.processMono(
                    rearPresenceDipFilter.processMono(rawExcite)
                )
            )

            preDelayBuf[pdWrite] = recessed
            val basePd = (config.preDelayMs.coerceIn(4f, 42f) * 0.001f * sr).roundToInt().coerceIn(24, 3200)
            val decay = config.roomDecay.coerceIn(0.10f, 0.85f)
            val erSpan = (sr * (0.008f + 0.026f * decay)).roundToInt()

            val t1 = preDelayBuf[(pdWrite - basePd) and PD_MASK]
            val t2 = preDelayBuf[(pdWrite - basePd - (erSpan * 23) / 100) and PD_MASK]
            val t3 = preDelayBuf[(pdWrite - basePd - (erSpan * 53) / 100) and PD_MASK]
            val t4 = preDelayBuf[(pdWrite - basePd - (erSpan * 89) / 100) and PD_MASK]
            pdWrite = (pdWrite + 1) and PD_MASK

            val earlyMono = t1 * 0.46f + t2 * 0.30f + t3 * 0.20f + t4 * 0.14f
            val earlyDiff = (t1 - t2) * 0.34f + (t3 - t4) * 0.24f

            // Cascaded Schroeder diffusion + 2x2 orthogonal damped FDN
            val diff = ap3.process(ap2.process(ap1.process(earlyMono, 0.52f), 0.50f), 0.48f)

            val y1 = fdnBuf1[fdnIdx1]
            val y2 = fdnBuf2[fdnIdx2]
            fdnLp1 += 0.36f * (y1 - fdnLp1)
            fdnLp2 += 0.36f * (y2 - fdnLp2)

            val rot1 = 0.70710677f * (fdnLp1 + fdnLp2)
            val rot2 = 0.70710677f * (fdnLp1 - fdnLp2)
            val fb = (0.16f + decay * 0.55f).coerceIn(0.14f, 0.72f)

            fdnBuf1[fdnIdx1] = diff * 0.54f + rot1 * fb
            fdnBuf2[fdnIdx2] = diff * 0.34f + rot2 * fb
            fdnIdx1++; if (fdnIdx1 >= fdnLen1) fdnIdx1 = 0
            fdnIdx2++; if (fdnIdx2 >= fdnLen2) fdnIdx2 = 0

            // Duck late ambience slightly during strong dry center vocals/transients
            val vocalDuck = 1f - 0.28f * (1f - sideDom) * contrast
            depthMono = earlyMono * 0.62f + (diff * 0.26f + (y1 * 0.62f + y2 * 0.38f) * 0.52f) * vocalDuck
            depthSide = earlyDiff * 0.58f + (y1 - y2) * 0.40f * vocalDuck

            val outAbs = abs(depthMono * smoothDepthWet)
            envDepthOut = envDepthOut * 0.997f + outAbs * 0.003f
            currentDepthLevelDb = (20f * log10(envDepthOut.coerceAtLeast(1e-4f))).coerceIn(-60f, 0f)
        } else {
            currentDepthLevelDb = -60f
        }

        val midComposite = frontMono + smoothDepthWet * depthMono
        if (smoothWidth == 0f) {
            outPair[0] = midComposite
            outPair[1] = midComposite
        } else {
            // Natural speaker-like micro-ITD cohesion on upper Side signal
            sideItdBuf[itdWrite] = sideHP
            val delayedSide = sideItdBuf[(itdWrite - itdDelaySamples) and ITD_MASK]
            itdWrite = (itdWrite + 1) and ITD_MASK
            val cohesiveSide = sideHP * 0.82f + delayedSide * 0.18f

            val totalSide = smoothWidth * cohesiveSide + smoothDepthWet * smoothWidth * depthSide * 0.65f
            outPair[0] = midComposite + totalSide
            outPair[1] = midComposite - totalSide
        }
    }
}
