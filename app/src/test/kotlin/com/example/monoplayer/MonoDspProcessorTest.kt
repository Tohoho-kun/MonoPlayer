package com.example.monoplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.example.monoplayer.dsp.DspStateHolder
import com.example.monoplayer.dsp.MonoAlgorithm
import com.example.monoplayer.dsp.MonoDspAudioProcessor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.random.Random

class MonoDspProcessorTest {

    private val sr = 44100
    private val delay = MonoDspAudioProcessor.FFT_SIZE - 1 // output[j] == input[j - delay]

    @Before
    fun setup() {
        DspStateHolder.setMonoAlgorithm(MonoAlgorithm.NAIVE)
        DspStateHolder.setSparkleBlend(85f)
        DspStateHolder.setCrossoverFreq(2000f)
        DspStateHolder.setPreampGain(0f)
        DspStateHolder.setEqualizerEnabled(true)
        DspStateHolder.applyEqPreset("Flat")
        DspStateHolder.crossfadeSec = 0f
        DspStateHolder.crossfadeMultiplier = 1f
    }

    /** Runs the processor over [l]/[r] and returns the left output channel. */
    private fun run(algo: MonoAlgorithm, l: FloatArray, r: FloatArray): FloatArray {
        DspStateHolder.setMonoAlgorithm(algo)
        val p = MonoDspAudioProcessor()
        p.configure(AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val out = FloatArray(l.size)
        var written = 0
        val chunk = 1024
        var pos = 0
        while (pos < l.size) {
            val n = min(chunk, l.size - pos)
            val bb = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until n) {
                bb.putShort((l[pos + i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
                bb.putShort((r[pos + i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
            }
            bb.flip()
            p.queueInput(bb)
            val o = p.getOutput().order(ByteOrder.nativeOrder())
            while (o.remaining() >= 4) {
                val v = o.short / 32768f
                o.short
                if (written < out.size) out[written++] = v
            }
            pos += n
        }
        assertEquals(l.size, written)
        return out
    }

    private fun sine(freq: Double, amp: Double, n: Int, phase: Double = 0.0) =
        FloatArray(n) { (amp * sin(2.0 * PI * freq * it / sr + phase)).toFloat() }

    /** Amplitude of [freq] in x[from until to] (Goertzel / single-bin DFT). */
    private fun amp(x: FloatArray, freq: Double, from: Int, to: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in from until to) {
            val a = 2.0 * PI * freq * i / sr
            re += x[i] * cos(a)
            im += x[i] * sin(a)
        }
        return 2.0 * hypot(re, im) / (to - from)
    }

    @Test
    fun naiveIsExactlyHalfSumDelayed() {
        val n = sr
        val rnd = Random(1)
        val l = FloatArray(n) { rnd.nextFloat() * 0.6f - 0.3f }
        val r = FloatArray(n) { rnd.nextFloat() * 0.6f - 0.3f }
        val out = run(MonoAlgorithm.NAIVE, l, r)
        for (j in delay + 10 until n) {
            val expected = 0.5f * (l[j - delay] + r[j - delay])
            assertEquals("sample $j", expected, out[j], 2e-4f)
        }
    }

    @Test
    fun stftPathWithZeroBlendEqualsNaiveMid() {
        // Verifies FFT / overlap-add alignment & normalisation: with 0% injection HILBERT must equal (L+R)/2.
        DspStateHolder.setSparkleBlend(0f)
        val n = sr
        val rnd = Random(2)
        val l = FloatArray(n) { rnd.nextFloat() * 0.6f - 0.3f }
        val r = FloatArray(n) { rnd.nextFloat() * 0.6f - 0.3f }
        val out = run(MonoAlgorithm.HILBERT, l, r)
        var maxErr = 0f
        for (j in 2 * delay until n) {
            val expected = 0.5f * (l[j - delay] + r[j - delay])
            maxErr = max(maxErr, abs(expected - out[j]))
        }
        assertTrue("max error $maxErr", maxErr < 2e-3f)
    }

    @Test
    fun centreVocalIsNeverRemovedByAnyMonoAlgorithm() {
        val n = sr * 2
        // Centre material: 250 Hz + 3 kHz in phase. Wide material: 6 kHz fully anti-phase.
        val centre = FloatArray(n) { i ->
            (0.30 * sin(2.0 * PI * 250.0 * i / sr) + 0.20 * sin(2.0 * PI * 3000.0 * i / sr)).toFloat()
        }
        val wide = sine(6000.0, 0.10, n)
        val l = FloatArray(n) { centre[it] + wide[it] }
        val r = FloatArray(n) { centre[it] - wide[it] }
        for (algo in listOf(
            MonoAlgorithm.NAIVE, MonoAlgorithm.HILBERT, MonoAlgorithm.STFT_ENERGY,
            MonoAlgorithm.PHASE_ALIGN, MonoAlgorithm.DYNAMIC_PCA
        )) {
            val out = run(algo, l, r)
            val a250 = amp(out, 250.0, n / 2, n)
            val a3k = amp(out, 3000.0, n / 2, n)
            assertEquals("$algo 250Hz centre", 0.30, a250, 0.30 * 0.06)
            assertEquals("$algo 3kHz centre", 0.20, a3k, 0.20 * 0.06)
        }
    }

    @Test
    fun wideAntiPhaseSparkleIsRestoredByHighQualityAlgorithms() {
        val n = sr * 2
        val wide = sine(6000.0, 0.20, n)
        val l = wide
        val r = FloatArray(n) { -wide[it] }
        val naive = amp(run(MonoAlgorithm.NAIVE, l, r), 6000.0, n / 2, n)
        assertTrue("naive must cancel, was $naive", naive < 0.01)
        for (algo in listOf(
            MonoAlgorithm.HILBERT, MonoAlgorithm.STFT_ENERGY,
            MonoAlgorithm.PHASE_ALIGN, MonoAlgorithm.DYNAMIC_PCA
        )) {
            val a = amp(run(algo, l, r), 6000.0, n / 2, n)
            assertTrue("$algo should restore 6 kHz, was $a", a > 0.12)
        }
    }

    @Test
    fun noClippingDiscontinuitiesWhenDrivenHard() {
        val n = sr
        val x = sine(440.0, 0.95, n) // peaks well above the limiter knee (0.7)
        val out = run(MonoAlgorithm.NAIVE, x, x)
        var maxAbs = 0f
        var maxStep = 0f
        for (j in delay + 2048 until n) {
            maxAbs = max(maxAbs, abs(out[j]))
            maxStep = max(maxStep, abs(out[j] - out[j - 1]))
        }
        assertTrue("limiter must stay within full scale: $maxAbs", maxAbs <= 1.0f)
        // A clean 440 Hz sine of amplitude 1 has a max step of 2*pi*440/44100 = 0.0627
        assertTrue("sample-to-sample step too large (discontinuity): $maxStep", maxStep < 0.075f)
    }

    @Test
    fun spectrumHasNoEmptyBands() {
        val n = sr * 2
        val rnd = Random(3)
        val l = FloatArray(n) { rnd.nextFloat() * 0.4f - 0.2f }
        val r = FloatArray(n) { rnd.nextFloat() * 0.4f - 0.2f }
        for (algo in listOf(MonoAlgorithm.NAIVE, MonoAlgorithm.HILBERT, MonoAlgorithm.STFT_ENERGY)) {
            run(algo, l, r)
            val spec = DspStateHolder.telemetry.value.spectrumDb
            for (b in spec.indices) {
                assertTrue("$algo band $b is empty (${spec[b]})", spec[b] > 0.05f)
            }
        }
    }

    @Test
    fun headFadeInSmoothlyStartsAtZeroWithoutVolumeSpike() {
        // Reproduces the exact user scenario:
        // Crossfade is active (1.0s), previous track left crossfadeMultiplier at 1.0f.
        DspStateHolder.crossfadeSec = 1.0f
        DspStateHolder.crossfadeMultiplier = 1.0f // Stale value from previous playback

        val n = sr * 2 // 2 seconds
        val x = sine(440.0, 0.8, n) // 440 Hz loud sine
        val out = run(MonoAlgorithm.NAIVE, x, x)

        // At the exact start of delayed audio output (delay = 2047), amplitude must be near 0
        val startSample = delay + 1
        val initialAmp = abs(out[startSample])
        assertTrue("Output at start must be muted/near zero (got $initialAmp)", initialAmp < 0.01f)

        // Verify there is no volume spike: 440Hz amplitude around 50ms must be smoothly ramping and smaller than at 500ms
        val start50ms = delay + (sr * 0.040).toInt()
        val end50ms = delay + (sr * 0.080).toInt()
        val start500ms = delay + (sr * 0.450).toInt()
        val end500ms = delay + (sr * 0.550).toInt()

        val amp50ms = amp(out, 440.0, start50ms, end50ms)
        val amp500ms = amp(out, 440.0, start500ms, end500ms)

        assertTrue("Amplitude around 50ms ($amp50ms) must not spike above 500ms ($amp500ms)", amp50ms < amp500ms)
        assertTrue("Amplitude around 50ms ($amp50ms) should be smaller than 0.20", amp50ms in 0.001..0.20)
        assertTrue("Amplitude around 500ms ($amp500ms) should be roughly 40-60% of full scale", amp500ms in 0.25..0.60)

        // Reset state for subsequent tests
        DspStateHolder.crossfadeSec = 0f
        DspStateHolder.crossfadeMultiplier = 1f
    }
}
