package com.example.monoplayer

import com.example.monoplayer.data.ArtistNormalizer
import com.example.monoplayer.dsp.FastFFT
import com.example.monoplayer.dsp.TenBandEqualizer
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MonoDspAndNormalizerTest {

    @Test
    fun testArtistNormalizationAbsorbsVariations() {
        val k1 = ArtistNormalizer.computeCanonicalKey("The Mono Lab")
        val k2 = ArtistNormalizer.computeCanonicalKey("Mono Lab, The")
        val k3 = ArtistNormalizer.computeCanonicalKey("the mono lab feat. Synth")
        assertEquals(k1, k2)
        assertEquals(k1, k3)

        val w1 = ArtistNormalizer.computeCanonicalKey("DJ WINAMP 1999 feat. K.Asai")
        val w2 = ArtistNormalizer.computeCanonicalKey("ｄｊ ｗｉｎａｍｐ １９９９")
        assertEquals(w1, w2)

        val display = ArtistNormalizer.chooseCanonicalDisplayName(
            listOf("Mono Lab, The", "The Mono Lab")
        )
        assertEquals("The Mono Lab", display)
    }

    @Test
    fun testFastFftInvertibility() {
        val fft = FastFFT(256)
        val real = FloatArray(256) { i -> kotlin.math.sin(2.0 * Math.PI * 5.0 * i / 256.0).toFloat() }
        val orig = real.copyOf()
        val imag = FloatArray(256)

        fft.transform(real, imag, inverse = false)
        fft.transform(real, imag, inverse = true)

        for (i in 0 until 256) {
            assertTrue("Sample $i mismatch", abs(orig[i] - real[i]) < 1e-4f)
        }
    }

    @Test
    fun testTenBandEqualizerFlatAndBoost() {
        val eq = TenBandEqualizer(44100)
        // Flat EQ should pass signal unchanged
        val outFlat = eq.processMonoSample(0.5f)
        assertEquals(0.5f, outFlat, 1e-4f)

        // Boost 1kHz band and ensure non-NaN output
        eq.setBandGain(5, 6.0f)
        val outBoost = eq.processMonoSample(0.5f)
        assertFalse(outBoost.isNaN())
    }
}
