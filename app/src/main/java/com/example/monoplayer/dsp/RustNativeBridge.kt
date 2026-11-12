package com.example.monoplayer.dsp

import java.io.File

/**
 * Panic-safe JNI bridge to the native Rust `monoplayer_core` engine.
 *
 * Loads `libmonoplayer_core.so` on Android (`arm64-v8a` / `x86_64`) or
 * `libmonoplayer_core.dylib` during local host unit tests.
 */
object RustNativeBridge {

  val isAvailable: Boolean = loadNativeLibrary()

  private fun loadNativeLibrary(): Boolean {
    try {
      System.loadLibrary("monoplayer_core")
      return true
    } catch (_: UnsatisfiedLinkError) {
      val candidates = listOf(
        File("rust/monoplayer_core/target/release/libmonoplayer_core.dylib"),
        File("../rust/monoplayer_core/target/release/libmonoplayer_core.dylib"),
        File("rust/monoplayer_core/target/release/libmonoplayer_core.so"),
        File("../rust/monoplayer_core/target/release/libmonoplayer_core.so"),
      )
      for (file in candidates) {
        if (file.exists()) {
          return try {
            System.load(file.absolutePath)
            true
          } catch (_: Throwable) {
            false
          }
        }
      }
      return false
    } catch (_: Throwable) {
      return false
    }
  }

  // --- FastFFT ---
  @JvmStatic external fun nativeFftCreate(size: Int): Long
  @JvmStatic external fun nativeFftDestroy(handle: Long)
  @JvmStatic external fun nativeFftTransform(
    handle: Long,
    real: FloatArray,
    imag: FloatArray,
    inverse: Boolean,
  )

  // --- 10-Band Equalizer ---
  @JvmStatic external fun nativeEqCreate(sampleRate: Int): Long
  @JvmStatic external fun nativeEqDestroy(handle: Long)
  @JvmStatic external fun nativeEqSetSampleRate(handle: Long, sr: Int)
  @JvmStatic external fun nativeEqSetBandGain(handle: Long, index: Int, gainDb: Float)
  @JvmStatic external fun nativeEqSetAllBands(handle: Long, gains: FloatArray)
  @JvmStatic external fun nativeEqResetState(handle: Long)
  @JvmStatic external fun nativeEqProcessMono(handle: Long, x: Float): Float
  @JvmStatic external fun nativeEqProcessStereo(
    handle: Long,
    inL: Float,
    inR: Float,
    outPair: FloatArray,
  )

  // --- 3-Band Dynamic EQ ---
  @JvmStatic external fun nativeDynEqCreate(sampleRate: Int): Long
  @JvmStatic external fun nativeDynEqDestroy(handle: Long)
  @JvmStatic external fun nativeDynEqSetSampleRate(handle: Long, sr: Int)
  @JvmStatic external fun nativeDynEqReset(handle: Long)
  @JvmStatic external fun nativeDynEqProcessMono(
    handle: Long,
    input: Float,
    packedCfg25: FloatArray,
    telemetryOut6: FloatArray,
  ): Float
  @JvmStatic external fun nativeDynEqProcessStereo(
    handle: Long,
    inL: Float,
    inR: Float,
    packedCfg25: FloatArray,
    outPair: FloatArray,
    telemetryOut6: FloatArray,
  )

  // --- Realtime Loudness Normalizer ---
  @JvmStatic external fun nativeLoudnessCreate(sampleRate: Int): Long
  @JvmStatic external fun nativeLoudnessDestroy(handle: Long)
  @JvmStatic external fun nativeLoudnessSetSampleRate(handle: Long, sr: Int)
  @JvmStatic external fun nativeLoudnessReset(handle: Long)
  @JvmStatic external fun nativeLoudnessPushLookahead(handle: Long, rawL: Float, rawR: Float)
  @JvmStatic external fun nativeLoudnessProcessMono(
    handle: Long,
    input: Float,
    modeCode: Int,
    normalTargetLufs: Float,
    carTargetLufs: Float,
    carCabinIntensity: Float,
    telemetryOut4: FloatArray,
  ): Float
  @JvmStatic external fun nativeLoudnessProcessStereo(
    handle: Long,
    inL: Float,
    inR: Float,
    modeCode: Int,
    normalTargetLufs: Float,
    carTargetLufs: Float,
    carCabinIntensity: Float,
    outPair: FloatArray,
    telemetryOut4: FloatArray,
  )

  // --- Stereo Depth & Narrow Stereo ---
  @JvmStatic external fun nativeDepthCreate(sampleRate: Int): Long
  @JvmStatic external fun nativeDepthDestroy(handle: Long)
  @JvmStatic external fun nativeDepthSetSampleRate(handle: Long, sr: Int)
  @JvmStatic external fun nativeDepthReset(handle: Long)
  @JvmStatic external fun nativeDepthProcessSample(
    handle: Long,
    monoCore: Float,
    delayedL: Float,
    delayedR: Float,
    isNarrowStereo: Boolean,
    narrowWidthPercent: Float,
    depthEnabled: Boolean,
    depthAmount: Float,
    preDelayMs: Float,
    roomDecay: Float,
    frontBackContrast: Float,
    outPairAndTelemetry4: FloatArray,
  )

  // --- Anti-Clipping Reconstruction & De-Clicking ---
  @JvmStatic external fun nativeDeclipCreate(sampleRate: Int): Long
  @JvmStatic external fun nativeDeclipDestroy(handle: Long)
  @JvmStatic external fun nativeDeclipSetSampleRate(handle: Long, sr: Int)
  @JvmStatic external fun nativeDeclipReset(handle: Long)
  @JvmStatic external fun nativeDeclipProcessMono(
    handle: Long,
    input: Float,
    enabled: Boolean,
    sensitivity: Float,
    minAmplitude: Float,
    maxReconstructRatio: Float,
    telemetryOut4: FloatArray,
  ): Float
  @JvmStatic external fun nativeDeclipProcessStereo(
    handle: Long,
    inL: Float,
    inR: Float,
    enabled: Boolean,
    sensitivity: Float,
    minAmplitude: Float,
    maxReconstructRatio: Float,
    outPair: FloatArray,
    telemetryOut4: FloatArray,
  )

  // --- RainGlass Optical Pixel Renderer ---
  @JvmStatic external fun nativeRainPrepareJacketAndFog(
    rawArgb: IntArray,
    sharpOut: IntArray,
    fogOut: IntArray,
    width: Int,
    height: Int,
    blurRadius: Int,
  )
  @JvmStatic external fun nativeRainRenderRefractedFrame(
    sharpPixels: IntArray,
    fogPixels: IntArray,
    fogMap: FloatArray,
    waterHeight: FloatArray,
    outPixels: IntArray,
    width: Int,
    height: Int,
    simScale: Float,
    highDrive: Float,
  )

  // --- Vehicle Speed & Tunnel Dead-Reckoning Engine ---
  @JvmStatic external fun nativeVehicleTrackerCreate(): Long
  @JvmStatic external fun nativeVehicleTrackerDestroy(handle: Long)
  @JvmStatic external fun nativeVehicleTrackerReset(handle: Long)
  @JvmStatic external fun nativeVehicleTrackerOnGps(
    handle: Long,
    speedKmh: Float,
    out2: FloatArray,
  ): Float
  @JvmStatic external fun nativeVehicleTrackerOnSensor(
    handle: Long,
    ax: Float,
    ay: Float,
    az: Float,
    dtSec: Float,
    isGpsLost: Boolean,
    out2: FloatArray,
  ): Float
  @JvmStatic external fun nativeVehicleTrackerOnImuSample(
    handle: Long,
    ax: Float,
    ay: Float,
    az: Float,
    gx: Float,
    gy: Float,
    gz: Float,
    wx: Float,
    wy: Float,
    wz: Float,
    hasGyro: Boolean,
    dtSec: Float,
    isGpsLost: Boolean,
    out5: FloatArray,
  ): Float
  @JvmStatic external fun nativeVehicleTrackerCalcIntensity(
    speedKmh: Float,
    speedRefKmh: Float,
    compensationFactor: Float,
  ): Float
  @JvmStatic external fun nativeVehicleTrackerCalcAcousticBoosts(
    intensity: Float,
    out3: FloatArray,
  )

  // --- STFT Mono & 32-Band Visualizer Engine ---
  @JvmStatic external fun nativeStftMonoCreate(sampleRate: Int): Long
  @JvmStatic external fun nativeStftMonoDestroy(handle: Long)
  @JvmStatic external fun nativeStftMonoSetSampleRate(handle: Long, sampleRate: Int)
  @JvmStatic external fun nativeStftMonoReset(handle: Long)
  @JvmStatic external fun nativeStftMonoProcessHop(
    handle: Long,
    ringL: FloatArray,
    ringR: FloatArray,
    algoCode: Int,
    blend: Float,
    crossoverFreq: Float,
    outOlaTime: FloatArray,
    outOlaNorm: FloatArray,
    visSpec32: FloatArray,
    visNaive32: FloatArray,
    metrics2: FloatArray,
  )
  @JvmStatic external fun nativeStftMonoProcessPcaBlock(
    handle: Long,
    dl: FloatArray,
    dr: FloatArray,
    outMono: FloatArray,
    blend: Float,
    crossoverHz: Float,
    sampleRate: Int,
  )

  // --- Mastering Peak Limiter & MathGuard Utility ---
  @JvmStatic external fun nativeLimiterCreate(sampleRate: Int): Long
  @JvmStatic external fun nativeLimiterDestroy(handle: Long)
  @JvmStatic external fun nativeLimiterSetSampleRate(handle: Long, sampleRate: Int)
  @JvmStatic external fun nativeLimiterReset(handle: Long)
  @JvmStatic external fun nativeLimiterProcessStereo(
    handle: Long,
    inL: Float,
    inR: Float,
    outPair: FloatArray,
  )
  @JvmStatic external fun nativeSoftLimit(x: Float): Float
  @JvmStatic external fun nativeSanitizeFloat(x: Float): Float
  @JvmStatic external fun nativeCombineSessileRadius(
    r1: Float,
    r2: Float,
    retention: Float,
    maxR: Float,
  ): Float
}
