package com.example.monoplayer.dsp

import kotlin.math.*

/**
 * Zero-allocation real-time Anti-Clipping Reconstruction & De-Clicking Processor.
 *
 * Detects and repairs:
 * 1. 0 dBFS hard digital clipping (flat tops at full scale).
 * 2. Scaled / attenuated mastering clips where tracks were clipped during production and then
 *    subsequently lowered in volume (e.g. -1 dBFS, -3 dBFS, -6 dBFS), leaving clipped flat
 *    plateaus and audible crackling / clicking transients.
 * 3. Isolated clicks, cusps, and 2nd-derivative step discontinuities.
 *
 * Powered by high-speed native Rust kernels (`monoplayer_core::declip`) via JNI with a
 * zero-allocation Kotlin fallback.
 */
class DeclipProcessor(sampleRate: Int = 48000) {

    private var sr: Int = sampleRate.coerceAtLeast(8000)
    private val nativeHandle: Long =
        if (RustNativeBridge.isAvailable) RustNativeBridge.nativeDeclipCreate(sr) else 0L

    private val telemetryOut4 = FloatArray(4)
    private val stereoTempPair = FloatArray(2)

    // Telemetry
    @Volatile var currentClipRate: Float = 0f
        private set
    @Volatile var repairedEventsTotal: Long = 0L
        private set
    @Volatile var estimatedThresholdDb: Float = 0f
        private set
    @Volatile var maxReconstructedPeak: Float = 0f
        private set

    // --- Kotlin Fallback State (used only when Rust native library is unavailable) ---
    private class FallbackChannel(val ringSize: Int = 128, val lookahead: Int = 48) {
        val ring = FloatArray(ringSize)
        val repaired = BooleanArray(ringSize)
        var writeIdx = 0
        var eventsCount = 0L
        var samplesCount = 0L
        var recentClipSamples = 0
        var recentTotalSamples = 0
        var estimatedLevel = 1.0f
        var maxPeak = 0.0f

        fun reset() {
            ring.fill(0f)
            repaired.fill(false)
            writeIdx = 0
            eventsCount = 0L
            samplesCount = 0L
            recentClipSamples = 0
            recentTotalSamples = 0
            estimatedLevel = 1.0f
            maxPeak = 0.0f
        }

        private fun getSample(offset: Int): Float {
            val idx = (writeIdx + offset).mod(ringSize)
            return ring[idx]
        }

        private fun setSample(offset: Int, value: Float) {
            val idx = (writeIdx + offset).mod(ringSize)
            ring[idx] = if (value.isFinite()) value else 0f
            repaired[idx] = true
        }

        private fun isRepaired(offset: Int): Boolean {
            val idx = (writeIdx + offset).mod(ringSize)
            return repaired[idx]
        }

        fun process(input: Float, config: DeclipConfig): Float {
            val clean = if (input.isFinite()) input else 0f
            ring[writeIdx] = clean
            repaired[writeIdx] = false

            recentTotalSamples++
            if (recentTotalSamples >= 4096) {
                recentClipSamples = (recentClipSamples * 0.5f).toInt()
                recentTotalSamples = 2048
            }

            if (config.enabled) {
                val p = -4
                if (!isRepaired(p)) {
                    val refVal = getSample(p)
                    val absRef = abs(refVal)
                    if (absRef >= config.minAmplitude) {
                        val sClamp = config.sensitivity.coerceIn(0f, 1f)
                        val tolerance = 0.00005f + sClamp * 0.00195f
                        var plateauLen = 1
                        var k = p - 1
                        while (k >= p - 24) {
                            if (isRepaired(k)) break
                            val s = getSample(k)
                            if (abs(abs(s) - absRef) <= tolerance && s.sign == refVal.sign) {
                                plateauLen++
                                k--
                            } else {
                                break
                            }
                        }

                        val startPos = p - plateauLen + 1
                        val endPos = p
                        val isPositive = refVal > 0f
                        val sPre = getSample(startPos - 1)
                        val sPost = getSample(endPos + 1)
                        var isCrest = false
                        val minLen = (5f - 2f * sClamp).roundToInt().coerceAtLeast(3)

                        if (plateauLen >= minLen) {
                            val postLeaves = abs(abs(sPost) - absRef) > tolerance || sPost.sign != refVal.sign
                            if (postLeaves) {
                                val sPre2 = getSample(startPos - 2)
                                val sPost2 = getSample(endPos + 2)
                                val dIn: Float
                                val dOut: Float
                                val slopeIn: Float
                                val slopeOut: Float
                                if (isPositive) {
                                    dIn = refVal - sPre; dOut = refVal - sPost
                                    slopeIn = sPre - sPre2; slopeOut = sPost - sPost2
                                } else {
                                    dIn = sPre - refVal; dOut = sPost - refVal
                                    slopeIn = sPre2 - sPre; slopeOut = sPost2 - sPost
                                }
                                val edgeIn = max(dIn, 0.5f * slopeIn)
                                val edgeOut = max(dOut, 0.5f * slopeOut)
                                val cornerThresh = max(absRef * (0.030f - 0.025f * sClamp), tolerance * 6f)
                                if (dIn > 0f && dOut > 0f && edgeIn > cornerThresh && edgeOut > cornerThresh) {
                                    isCrest = true
                                }
                            }
                        } else if (plateauLen == 1 && sClamp > 0.60f &&
                            (abs(abs(sPost) - absRef) > tolerance || sPost.sign != refVal.sign)
                        ) {
                            fun d2At(o: Int): Float = getSample(o + 1) - 2f * getSample(o) + getSample(o - 1)
                            val d2 = d2At(p)
                            if (abs(d2) >= abs(d2At(p - 1)) && abs(d2) >= abs(d2At(p + 1))) {
                                var nb = 0f
                                for (o in intArrayOf(-5, -4, -3, -2, 2, 3)) nb += abs(d2At(p + o))
                                val nbAvg = max(nb / 6f, 1e-5f)
                                val ratioThresh = 10f - 5f * sClamp
                                val pointingOut = (isPositive && d2 < 0f) || (!isPositive && d2 > 0f)
                                if (pointingOut && abs(d2) > ratioThresh * nbAvg && abs(d2) > 0.06f * absRef) {
                                    isCrest = true
                                }
                            }
                        }

                        if (isCrest) {
                            val burstLen = endPos - startPos + 1
                            val deltaT = (burstLen + 1).toFloat()
                            val y0 = getSample(startPos - 1)
                            val ym1 = getSample(startPos - 2)
                            val ym2 = getSample(startPos - 3)
                            val y1 = getSample(endPos + 1)
                            val yp1 = getSample(endPos + 2)
                            val yp2 = getSample(endPos + 3)

                            var m0 = (3f * y0 - 4f * ym1 + ym2) * 0.5f
                            val sm0 = y0 - ym1
                            if (m0.sign != sm0.sign) m0 = sm0

                            var m1 = (-3f * y1 + 4f * yp1 - yp2) * 0.5f
                            val sm1 = yp1 - y1
                            if (m1.sign != sm1.sign) m1 = sm1

                            val maxAllowed = absRef * config.maxReconstructRatio.coerceIn(1.05f, 2.5f)
                            for (step in 0 until burstLen) {
                                val offset = startPos + step
                                val t = (step + 1f) / deltaT
                                val t2 = t * t
                                val t3 = t2 * t
                                val h00 = 2f * t3 - 3f * t2 + 1f
                                val h10 = t3 - 2f * t2 + t
                                val h01 = -2f * t3 + 3f * t2
                                val h11 = t3 - t2
                                var recon = h00 * y0 + h10 * (m0 * deltaT) + h01 * y1 + h11 * (m1 * deltaT)

                                if (isPositive) {
                                    if (burstLen > 1) {
                                        recon = max(recon, min(y0, y1).coerceAtMost(refVal))
                                    } else {
                                        recon = max(recon, min(y0, y1) - abs(y1 - y0))
                                    }
                                    if (recon > maxAllowed) recon = maxAllowed + (recon - maxAllowed) * 0.15f
                                } else {
                                    if (burstLen > 1) {
                                        recon = min(recon, max(y0, y1).coerceAtLeast(refVal))
                                    } else {
                                        recon = min(recon, max(y0, y1) + abs(y1 - y0))
                                    }
                                    if (recon < -maxAllowed) recon = -maxAllowed + (recon - (-maxAllowed)) * 0.15f
                                }
                                setSample(offset, recon)
                                if (abs(recon) > maxPeak) maxPeak = abs(recon)
                            }

                            val smPre = 0.08f * ym1 + 0.84f * y0 + 0.08f * getSample(startPos)
                            val smPost = 0.08f * getSample(endPos) + 0.84f * y1 + 0.08f * yp1
                            setSample(startPos - 1, smPre)
                            setSample(endPos + 1, smPost)

                            if (eventsCount == 0L) {
                                estimatedLevel = absRef
                            } else {
                                estimatedLevel = 0.80f * estimatedLevel + 0.20f * absRef
                            }
                            eventsCount++
                            samplesCount += burstLen.toLong()
                            recentClipSamples += burstLen
                        }
                    }
                }
            }

            val outIdx = (writeIdx + ringSize - lookahead).mod(ringSize)
            val outVal = ring[outIdx]
            writeIdx = (writeIdx + 1).mod(ringSize)
            return outVal
        }
    }

    private val fallbackLeft = FallbackChannel()
    private val fallbackRight = FallbackChannel()

    @Synchronized
    fun setSampleRate(sampleRate: Int) {
        val newSr = sampleRate.coerceAtLeast(8000)
        if (sr == newSr) return
        sr = newSr
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeDeclipSetSampleRate(nativeHandle, newSr)
        }
        fallbackLeft.reset()
        fallbackRight.reset()
    }

    @Synchronized
    fun reset() {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeDeclipReset(nativeHandle)
        }
        fallbackLeft.reset()
        fallbackRight.reset()
        currentClipRate = 0f
        repairedEventsTotal = 0L
        estimatedThresholdDb = 0f
        maxReconstructedPeak = 0f
    }

    @Synchronized
    fun processMonoSample(input: Float, config: DeclipConfig): Float {
        if (nativeHandle != 0L) {
            val out = RustNativeBridge.nativeDeclipProcessMono(
                nativeHandle,
                input,
                config.enabled,
                config.sensitivity,
                config.minAmplitude,
                config.maxReconstructRatio,
                telemetryOut4
            )
            currentClipRate = telemetryOut4[0]
            repairedEventsTotal = telemetryOut4[1].toLong()
            estimatedThresholdDb = telemetryOut4[2]
            maxReconstructedPeak = telemetryOut4[3]
            return out
        }

        val out = fallbackLeft.process(input, config)
        repairedEventsTotal = fallbackLeft.eventsCount
        val total = fallbackLeft.recentTotalSamples.coerceAtLeast(1).toFloat()
        currentClipRate = (fallbackLeft.recentClipSamples.toFloat() / total).coerceIn(0f, 1f)
        estimatedThresholdDb = (20f * log10(fallbackLeft.estimatedLevel.coerceAtLeast(1e-4f))).coerceIn(-60f, 0f)
        maxReconstructedPeak = fallbackLeft.maxPeak
        return out
    }

    @Synchronized
    fun processStereoSample(
        inL: Float,
        inR: Float,
        config: DeclipConfig,
        outPair: FloatArray
    ) {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeDeclipProcessStereo(
                nativeHandle,
                inL,
                inR,
                config.enabled,
                config.sensitivity,
                config.minAmplitude,
                config.maxReconstructRatio,
                outPair,
                telemetryOut4
            )
            currentClipRate = telemetryOut4[0]
            repairedEventsTotal = telemetryOut4[1].toLong()
            estimatedThresholdDb = telemetryOut4[2]
            maxReconstructedPeak = telemetryOut4[3]
            return
        }

        outPair[0] = fallbackLeft.process(inL, config)
        outPair[1] = fallbackRight.process(inR, config)

        repairedEventsTotal = fallbackLeft.eventsCount + fallbackRight.eventsCount
        val total = (fallbackLeft.recentTotalSamples + fallbackRight.recentTotalSamples).coerceAtLeast(1).toFloat()
        val clips = (fallbackLeft.recentClipSamples + fallbackRight.recentClipSamples).toFloat()
        currentClipRate = (clips / total).coerceIn(0f, 1f)

        val avgLvl = when {
            fallbackLeft.eventsCount > 0 && fallbackRight.eventsCount > 0 ->
                0.5f * (fallbackLeft.estimatedLevel + fallbackRight.estimatedLevel)
            fallbackLeft.eventsCount > 0 -> fallbackLeft.estimatedLevel
            fallbackRight.eventsCount > 0 -> fallbackRight.estimatedLevel
            else -> 1.0f
        }
        estimatedThresholdDb = (20f * log10(avgLvl.coerceAtLeast(1e-4f))).coerceIn(-60f, 0f)
        maxReconstructedPeak = max(fallbackLeft.maxPeak, fallbackRight.maxPeak)
    }

    protected fun finalize() {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeDeclipDestroy(nativeHandle)
        }
    }
}
