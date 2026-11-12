package com.example.monoplayer.dsp

import kotlin.math.*

/**
 * 10-Band Peaking / Shelving Biquad Equalizer + Pre-amp Gain + Limiter
 * Standard ISO 10-band center frequencies:
 * 31.25Hz, 62.5Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz
 */
class TenBandEqualizer(sampleRate: Int = 44100) {
    companion object {
        val BAND_FREQUENCIES = floatArrayOf(
            31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )
        val BAND_LABELS = arrayOf(
            "31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"
        )

        val PRESETS: Map<String, FloatArray> = linkedMapOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Mono Sparkle (高域輝き)" to floatArrayOf(0.5f, 1.0f, 0.0f, -0.5f, 0.0f, 1.0f, 2.5f, 3.8f, 4.2f, 3.5f),
            "Winamp Full Bass & Treble" to floatArrayOf(4.5f, 3.5f, 1.5f, -1.5f, -2.0f, -1.0f, 1.5f, 3.5f, 4.8f, 5.2f),
            "90s Car Stereo Loudness" to floatArrayOf(5.0f, 4.0f, 1.8f, 0.0f, -1.2f, 0.0f, 1.2f, 2.8f, 4.2f, 4.5f),
            "Vocal Presence (ボーカル強調)" to floatArrayOf(-2.0f, -1.0f, 0.0f, 1.5f, 3.5f, 4.0f, 3.2f, 1.5f, 0.5f, 0.0f),
            "Jazz / Warm Club" to floatArrayOf(2.5f, 2.0f, 1.0f, 1.5f, -1.0f, -1.0f, 0.0f, 1.2f, 2.0f, 2.5f),
            "Rock / Punchy" to floatArrayOf(4.0f, 3.0f, 1.5f, -1.0f, -1.5f, 0.5f, 2.5f, 3.5f, 3.5f, 3.0f)
        )
    }

    private var currentSampleRate: Int = sampleRate
    private val nativeHandle: Long =
        if (RustNativeBridge.isAvailable) RustNativeBridge.nativeEqCreate(sampleRate) else 0L
    private val bandGainsDb = FloatArray(10) { 0f }

    // Biquad coefficients: b0, b1, b2, a1, a2 per band (normalized by a0)
    private val b0 = FloatArray(10) { 1f }
    private val b1 = FloatArray(10) { 0f }
    private val b2 = FloatArray(10) { 0f }
    private val a1 = FloatArray(10) { 0f }
    private val a2 = FloatArray(10) { 0f }

    // Direct Form II Transposed state variables for Left/Mono and Right
    private val z1L = FloatArray(10)
    private val z2L = FloatArray(10)
    private val z1R = FloatArray(10)
    private val z2R = FloatArray(10)

    init {
        recalculateAllFilters()
    }

    @Synchronized
    fun setSampleRate(sr: Int) {
        if (sr > 0 && sr != currentSampleRate) {
            currentSampleRate = sr
            if (nativeHandle != 0L) {
                RustNativeBridge.nativeEqSetSampleRate(nativeHandle, sr)
            }
            recalculateAllFilters()
            resetState()
        }
    }

    @Synchronized
    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0..9) {
            bandGainsDb[bandIndex] = gainDb.coerceIn(-12f, 12f)
            if (nativeHandle != 0L) {
                RustNativeBridge.nativeEqSetBandGain(nativeHandle, bandIndex, bandGainsDb[bandIndex])
            }
            recalculateBand(bandIndex)
        }
    }

    @Synchronized
    fun setAllBands(gainsDb: FloatArray) {
        for (i in 0 until min(10, gainsDb.size)) {
            bandGainsDb[i] = gainsDb[i].coerceIn(-12f, 12f)
        }
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeEqSetAllBands(nativeHandle, bandGainsDb)
        }
        recalculateAllFilters()
    }

    @Synchronized
    fun getBandGains(): FloatArray = bandGainsDb.copyOf()

    @Synchronized
    fun resetState() {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeEqResetState(nativeHandle)
        }
        z1L.fill(0f)
        z2L.fill(0f)
        z1R.fill(0f)
        z2R.fill(0f)
    }

    private fun recalculateAllFilters() {
        for (i in 0 until 10) {
            recalculateBand(i)
        }
    }

    private fun recalculateBand(i: Int) {
        val db = bandGainsDb[i]
        if (abs(db) < 0.01f) {
            b0[i] = 1f
            b1[i] = 0f
            b2[i] = 0f
            a1[i] = 0f
            a2[i] = 0f
            return
        }

        val freq = BAND_FREQUENCIES[i].coerceAtMost(currentSampleRate * 0.45f)
        val a = 10.0.pow(db / 40.0)
        val w0 = 2.0 * Math.PI * freq / currentSampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val q = 1.4142135 // 1 octave bandwidth Q

        val alpha = sinW0 / (2.0 * q)

        val rawB0 = 1.0 + alpha * a
        val rawB1 = -2.0 * cosW0
        val rawB2 = 1.0 - alpha * a
        val rawA0 = 1.0 + alpha / a
        val rawA1 = -2.0 * cosW0
        val rawA2 = 1.0 - alpha / a

        b0[i] = (rawB0 / rawA0).toFloat()
        b1[i] = (rawB1 / rawA0).toFloat()
        b2[i] = (rawB2 / rawA0).toFloat()
        a1[i] = (rawA1 / rawA0).toFloat()
        a2[i] = (rawA2 / rawA0).toFloat()
    }

    @Synchronized
    fun processMonoSample(x: Float): Float {
        if (nativeHandle != 0L) {
            return RustNativeBridge.nativeEqProcessMono(nativeHandle, x)
        }
        var y = x
        for (i in 0 until 10) {
            val out = b0[i] * y + z1L[i]
            z1L[i] = b1[i] * y - a1[i] * out + z2L[i]
            z2L[i] = b2[i] * y - a2[i] * out
            y = out
        }
        return y
    }

    @Synchronized
    fun processStereoSample(inL: Float, inR: Float, outPair: FloatArray) {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeEqProcessStereo(nativeHandle, inL, inR, outPair)
            return
        }
        var yL = inL
        var yR = inR
        for (i in 0 until 10) {
            val outL = b0[i] * yL + z1L[i]
            z1L[i] = b1[i] * yL - a1[i] * outL + z2L[i]
            z2L[i] = b2[i] * yL - a2[i] * outL
            yL = outL

            val outR = b0[i] * yR + z1R[i]
            z1R[i] = b1[i] * yR - a1[i] * outR + z2R[i]
            z2R[i] = b2[i] * yR - a2[i] * outR
            yR = outR
        }
        outPair[0] = yL
        outPair[1] = yR
    }
}
