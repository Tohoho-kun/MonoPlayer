package com.example.monoplayer.dsp

import com.example.monoplayer.ui.RainGlassEngine
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RustNativeBridgeTest {

  @Test
  fun rustJniLibraryIsLoadedAndActive() {
    assertTrue(
      "Expected libmonoplayer_core native Rust library to load via JNI",
      RustNativeBridge.isAvailable,
    )
    assertEquals(0.0f, RustNativeBridge.nativeSanitizeFloat(Float.NaN), 1e-6f)
    assertEquals(0.0f, RustNativeBridge.nativeSanitizeFloat(Float.POSITIVE_INFINITY), 1e-6f)
    assertEquals(0.0f, RustNativeBridge.nativeSanitizeFloat(1e-38f), 1e-6f)
    assertEquals(0.5f, RustNativeBridge.nativeSoftLimit(0.5f), 1e-6f)
    assertTrue(RustNativeBridge.nativeSoftLimit(12.0f) in 0.95f..1.0f)
  }

  @Test
  fun rustFastFftRoundtripAndNanSafety() {
    val fft = FastFFT(512)
    val real = FloatArray(512) { i -> sin(i * 0.11f) }
    val orig = real.copyOf()
    val imag = FloatArray(512)
    fft.transform(real, imag, inverse = false)
    fft.transform(real, imag, inverse = true)
    for (i in 0 until 512) {
      assertEquals(orig[i], real[i], 1e-4f)
    }

    // Feed NaN and verify Rust FFT sanitizes without poisoning
    real[0] = Float.NaN
    fft.transform(real, imag, inverse = false)
    assertTrue(real.all { it.isFinite() })
    assertTrue(imag.all { it.isFinite() })
  }

  @Test
  fun rustDspPipelineSelfHealsFromNanAndSubnormals() {
    val eq = TenBandEqualizer(48000)
    eq.setBandGain(0, 6.0f)
    eq.setBandGain(7, 4.5f)
    assertEquals(0f, eq.processMonoSample(Float.NaN), 1e-6f)
    val eqOut = eq.processMonoSample(0.4f)
    assertTrue(eqOut.isFinite() && abs(eqOut) > 0.05f)

    val dynEq = ThreeBandDynamicEq(48000)
    val dynCfg = DynamicEqConfig(enabled = true)
    assertEquals(0f, dynEq.processMonoSample(Float.POSITIVE_INFINITY, dynCfg), 1e-6f)
    val dynOut = dynEq.processMonoSample(0.35f, dynCfg)
    assertTrue(dynOut.isFinite())

    val loud = RealtimeLoudnessNormalizer(48000)
    val loudCfg = LoudnessConfig(mode = LoudnessMode.NOISY_CAR, carCabinIntensity = 0.8f)
    loud.pushLookaheadSample(Float.NaN, Float.NEGATIVE_INFINITY)
    val loudOut = loud.processMonoSample(0.3f, loudCfg)
    assertTrue(loudOut.isFinite())

    val depth = StereoDepthProcessor(48000)
    val spatialCfg = SpatialStageConfig(
      stageMode = OutputStageMode.NARROW_STEREO,
      narrowWidthPercent = 35f,
      depthEnabled = true,
      depthAmount = 0.65f,
    )
    val pair = FloatArray(2)
    depth.processSample(Float.NaN, Float.POSITIVE_INFINITY, 1e-40f, spatialCfg, pair)
    assertTrue(pair[0].isFinite() && pair[1].isFinite())
    depth.processSample(0.3f, 0.35f, 0.2f, spatialCfg, pair)
    assertTrue(pair[0].isFinite() && pair[1].isFinite())
    assertFalse(pair[0] == 0f && pair[1] == 0f)
  }

  @Test
  fun rustRainGlassOpticsRendersCleanPixels() {
    val engine = RainGlassEngine(width = 128, height = 128, seed = 42)
    val pixels = engine.stepAndRenderPixels(
      dtSeconds = 0.016f,
      bassDrive = 0.5f,
      rmsDrive = 0.4f,
      highDrive = 0.7f,
    )
    assertEquals(128 * 128, pixels.size)
    assertTrue(pixels.all { ((it ushr 24) and 0xFF) == 0xFF })
  }

  @Test
  fun rustDeclipRepairsZeroDbAndScaledMasterClipping() {
    val declip = DeclipProcessor(48000)
    val config = DeclipConfig(enabled = true, sensitivity = 0.80f, minAmplitude = 0.12f)

    // Test A: 0 dBFS clipping
    for (i in 0 until 480) {
      val t = i.toFloat() / 48000f
      val raw = 1.4f * sin(2f * Math.PI.toFloat() * 1000f * t)
      val clipped = raw.coerceIn(-1.0f, 1.0f)
      declip.processMonoSample(clipped, config)
    }
    assertTrue("Should repair 0 dBFS clipping events", declip.repairedEventsTotal > 0)
    assertTrue("Should reconstruct peak above 1.0", declip.maxReconstructedPeak > 1.0f)

    // Test B: Scaled master clipping (-6 dB attenuated track with flat tops and clicks)
    declip.reset()
    val scale = 0.50f // -6.02 dBFS
    val stereoPair = FloatArray(2)
    for (i in 0 until 480) {
      val t = i.toFloat() / 48000f
      val raw = 1.35f * sin(2f * Math.PI.toFloat() * 440f * t)
      val clippedScaled = raw.coerceIn(-1.0f, 1.0f) * scale
      declip.processStereoSample(clippedScaled, clippedScaled, config, stereoPair)
    }
    assertTrue(
      "Scaled -6dB clipped master MUST be detected and repaired",
      declip.repairedEventsTotal > 0
    )
    assertTrue(
      "Estimated threshold should reflect ~ -6dB level",
      declip.estimatedThresholdDb in -8.0f..-4.0f
    )
    assertTrue(
      "Reconstructed peak should exceed 0.50 plateau",
      declip.maxReconstructedPeak > 0.50f
    )

    // Test C: NaN safety
    val safeOut = declip.processMonoSample(Float.NaN, config)
    assertTrue(safeOut.isFinite())
  }

  @Test
  fun rustCarTelemetryExposesBassPunchAndLimiterGainReduction() {
    val loud = RealtimeLoudnessNormalizer(48000)
    val cfg = LoudnessConfig(mode = LoudnessMode.NOISY_CAR, carTargetLufs = -19f, carCabinIntensity = 1.5f)
    repeat(2400) { i ->
      val s = 0.20f * sin(2f * Math.PI.toFloat() * 440f * i / 48000f)
      loud.pushLookaheadSample(s, s)
      loud.processMonoSample(s, cfg)
    }
    assertTrue("Expected positive 95Hz bass punch in NOISY_CAR mode", loud.carBassBoostDb > 1.0f)

    val lim = RustNativeBridge.nativeLimiterCreate(48000)
    assertTrue(lim != 0L)
    val out3 = FloatArray(3)
    repeat(300) {
      RustNativeBridge.nativeLimiterProcessStereo(lim, 2.0f, 2.0f, out3)
    }
    assertTrue("Expected limiter output capped safely", abs(out3[0]) <= 1.0f && abs(out3[1]) <= 1.0f)
    assertTrue("Expected positive gain reduction dB telemetry", out3[2] > 5.0f)
    RustNativeBridge.nativeLimiterDestroy(lim)
  }
}
