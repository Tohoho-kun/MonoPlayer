package com.example.monoplayer.dsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VisualizerTelemetry(
    val spectrumDb: FloatArray = FloatArray(32) { 0f },      // 32-band log-spaced spectrum (0f..1f)
    val naiveSpectrumDb: FloatArray = FloatArray(32) { 0f }, // Reference naive mono spectrum (0f..1f)
    val waveform: FloatArray = FloatArray(128) { 0f },       // Oscilloscope time-domain waveform (-1f..1f)
    val vuLeftDb: Float = -60f,                              // VU Meter Left/Mono in dB (-60..0)
    val vuRightDb: Float = -60f,                             // VU Meter Right/Mono in dB (-60..0)
    val rmsLinear: Float = 0f,                               // Overall energy (0f..1f)
    val hfPhaseCorrelation: Float = 1.0f,                    // High-freq phase correlation (-1f..+1f)
    val naiveHfLossDb: Float = 0.0f,                         // Naive mono high-freq loss (dB)
    val sparkleRetentionPercent: Int = 95,                   // Sparkle retention %
    val measuredLufs: Float = -24.0f,                        // On-the-fly K-weighted Short-Term LUFS
    val loudnessGainDb: Float = 0.0f,                        // Applied Loudness Normalizer gain (dB)
    val loudnessGateFrozen: Boolean = true,                  // Silence protection gate hold status
    val carClarityBoostDb: Float = 0.0f,                     // Car cabin dynamic vocal clarity boost (dB)
    val dynEqDetectedDb: FloatArray = FloatArray(3) { -60f },// LOW/MID/HIGH sidechain input level (dB)
    val dynEqGainDeltaDb: FloatArray = FloatArray(3) { 0f }, // LOW/MID/HIGH instantaneous dynamic EQ gain (dB)
    val spatialSideRatio: Float = 0f,                        // Stereo Side/(Mid+Side) background ratio (0..1)
    val spatialDepthDb: Float = -60f,                        // Z-axis Spatial Depth wet layer level (dB)
    val declipRate: Float = 0f,                              // Realtime declip activity ratio (0..1)
    val declipEventsTotal: Long = 0L,                        // Repaired clip & click events counter
    val declipThresholdDb: Float = 0f,                       // Estimated clipping ceiling in dBFS
    val declipMaxPeak: Float = 0f,                           // Reconstructed crest peak amplitude
    val detectedSpeedKmh: Float = 0f,                        // GPS/IMU detected vehicle speed (km/h)
    val speedSource: SpeedTrackingSource = SpeedTrackingSource.OFF, // Active tracking source
    val carForwardCalibrated: Boolean = false,               // Whether 3D holder forward axis is calibrated
    val carPreBoostDb: Float = -60f,                         // Pre-loudness/EQ window peak level (dBFS)
    val carPostBoostDb: Float = -60f,                        // Post-boost pre-limiter window peak level (dBFS)
    val carOutputDb: Float = -60f,                           // Final limited output window peak level (dBFS)
    val limiterGrDb: Float = 0f,                             // Active Mastering Limiter + Soft-Clip reduction (dB >= 0)
    val carBassBoostDb: Float = 0f,                          // Active dynamic 95Hz road-noise bass punch (dB)
    val sampleRate: Int = 44100,
    val timestampMs: Long = 0L
)

/**
 * Singleton state & real-time telemetry bus shared between the Media3 AudioProcessor and Jetpack Compose UI.
 */
object DspStateHolder {
    // User-configurable DSP parameters
    @Volatile var algorithm: MonoAlgorithm = MonoAlgorithm.HILBERT
    @Volatile var sparkleBlendPercent: Float = 85f // 0..150%
    @Volatile var crossoverFreqHz: Float = 2000f   // 500..6000 Hz
    @Volatile var preampGainDb: Float = 0f         // -12..+12 dB
    @Volatile var eqEnabled: Boolean = true
    @Volatile var crossfadeSec: Float = 0.0f       // Crossfade duration in seconds (0 = off)
    @Volatile var crossfadeMultiplier: Float = 1.0f // Controlled by crossfade engine (0..1)

    fun resetCrossfadeOnTrackBoundary() {
        crossfadeMultiplier = 1.0f
    }

    @Volatile var declipConfig: DeclipConfig = DeclipConfig()
    @Volatile var loudnessConfig: LoudnessConfig = LoudnessConfig()
    @Volatile var dynamicEqConfig: DynamicEqConfig = DynamicEqConfig()
    @Volatile var spatialConfig: SpatialStageConfig = SpatialStageConfig()

    val declipProcessor = DeclipProcessor(48000)
    val stereoDepth = StereoDepthProcessor(48000)
    val loudnessNormalizer = RealtimeLoudnessNormalizer(48000)
    val dynamicEq = ThreeBandDynamicEq(48000)
    val equalizer = TenBandEqualizer(44100)
    val syncBuffer = TelemetrySyncBuffer(160)

    private val _telemetry = MutableStateFlow(VisualizerTelemetry())
    val telemetry: StateFlow<VisualizerTelemetry> = _telemetry.asStateFlow()

    private val _syncOffsetMsFlow = MutableStateFlow(0)
    val syncOffsetMsFlow: StateFlow<Int> = _syncOffsetMsFlow.asStateFlow()

    private val _algoFlow = MutableStateFlow(MonoAlgorithm.HILBERT)
    val algoFlow: StateFlow<MonoAlgorithm> = _algoFlow.asStateFlow()

    private val _blendFlow = MutableStateFlow(85f)
    val blendFlow: StateFlow<Float> = _blendFlow.asStateFlow()

    private val _crossoverFlow = MutableStateFlow(2000f)
    val crossoverFlow: StateFlow<Float> = _crossoverFlow.asStateFlow()

    private val _preampFlow = MutableStateFlow(0f)
    val preampFlow: StateFlow<Float> = _preampFlow.asStateFlow()

    private val _eqEnabledFlow = MutableStateFlow(true)
    val eqEnabledFlow: StateFlow<Boolean> = _eqEnabledFlow.asStateFlow()

    private val _eqBandsFlow = MutableStateFlow(FloatArray(10) { 0f })
    val eqBandsFlow: StateFlow<FloatArray> = _eqBandsFlow.asStateFlow()

    private val _activePresetName = MutableStateFlow("Flat")
    val activePresetName: StateFlow<String> = _activePresetName.asStateFlow()

    private val _declipConfigFlow = MutableStateFlow(DeclipConfig())
    val declipConfigFlow: StateFlow<DeclipConfig> = _declipConfigFlow.asStateFlow()

    private val _loudnessConfigFlow = MutableStateFlow(LoudnessConfig())
    val loudnessConfigFlow: StateFlow<LoudnessConfig> = _loudnessConfigFlow.asStateFlow()

    private val _dynamicEqConfigFlow = MutableStateFlow(DynamicEqConfig())
    val dynamicEqConfigFlow: StateFlow<DynamicEqConfig> = _dynamicEqConfigFlow.asStateFlow()

    private val _spatialConfigFlow = MutableStateFlow(SpatialStageConfig())
    val spatialConfigFlow: StateFlow<SpatialStageConfig> = _spatialConfigFlow.asStateFlow()

    fun setMonoAlgorithm(algo: MonoAlgorithm) {
        algorithm = algo
        _algoFlow.value = algo
    }

    fun setSparkleBlend(percent: Float) {
        val clamped = percent.coerceIn(0f, 150f)
        sparkleBlendPercent = clamped
        _blendFlow.value = clamped
    }

    fun setCrossoverFreq(hz: Float) {
        val clamped = hz.coerceIn(500f, 6000f)
        crossoverFreqHz = clamped
        _crossoverFlow.value = clamped
    }

    fun setPreampGain(db: Float) {
        val clamped = db.coerceIn(-12f, 12f)
        preampGainDb = clamped
        _preampFlow.value = clamped
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        eqEnabled = enabled
        _eqEnabledFlow.value = enabled
    }

    fun setEqBand(index: Int, db: Float) {
        equalizer.setBandGain(index, db)
        _eqBandsFlow.value = equalizer.getBandGains()
        _activePresetName.value = "Custom"
    }

    fun applyEqPreset(presetName: String) {
        val gains = TenBandEqualizer.PRESETS[presetName] ?: return
        equalizer.setAllBands(gains)
        _eqBandsFlow.value = equalizer.getBandGains()
        _activePresetName.value = presetName
    }

    @Volatile var currentDetectedSpeedKmh: Float = 0f
    @Volatile var currentSpeedSource: SpeedTrackingSource = SpeedTrackingSource.OFF
    @Volatile var currentCarForwardCalibrated: Boolean = false

    private val _speedTrackingFlow = MutableStateFlow(Pair(0f, SpeedTrackingSource.OFF))
    val speedTrackingFlow: StateFlow<Pair<Float, SpeedTrackingSource>> = _speedTrackingFlow.asStateFlow()

    private val _carForwardCalibratedFlow = MutableStateFlow(false)
    val carForwardCalibratedFlow: StateFlow<Boolean> = _carForwardCalibratedFlow.asStateFlow()

    fun updateVehicleSpeed(
        speedKmh: Float,
        source: SpeedTrackingSource,
        forwardCalibrated: Boolean = false
    ) {
        val clamped = if (speedKmh.isFinite()) speedKmh.coerceAtLeast(0f) else 0f
        currentDetectedSpeedKmh = clamped
        currentSpeedSource = source
        currentCarForwardCalibrated = forwardCalibrated
        _speedTrackingFlow.value = Pair(clamped, source)
        _carForwardCalibratedFlow.value = forwardCalibrated

        val currentTelemetry = _telemetry.value
        if (currentTelemetry.detectedSpeedKmh != clamped ||
            currentTelemetry.speedSource != source ||
            currentTelemetry.carForwardCalibrated != forwardCalibrated
        ) {
            _telemetry.value = currentTelemetry.copy(
                detectedSpeedKmh = clamped,
                speedSource = source,
                carForwardCalibrated = forwardCalibrated
            )
        }
    }

    fun updateLoudnessConfig(config: LoudnessConfig) {
        val sanitized = config.copy(
            normalTargetLufs = config.normalTargetLufs.coerceIn(-20f, -9f),
            carTargetLufs = config.carTargetLufs.coerceIn(-24f, -8f),
            carCabinIntensity = config.carCabinIntensity.coerceIn(0f, 2f),
            speedCompensationFactor = config.speedCompensationFactor.coerceIn(0.2f, 2.0f),
            speedReferenceKmh = config.speedReferenceKmh.coerceIn(50f, 140f)
        )
        loudnessConfig = sanitized
        _loudnessConfigFlow.value = sanitized
    }

    fun cycleLoudnessMode(): LoudnessMode {
        val next = loudnessConfig.mode.nextMode()
        updateLoudnessConfig(loudnessConfig.copy(mode = next))
        return next
    }

    fun updateDynamicEqConfig(config: DynamicEqConfig) {
        dynamicEqConfig = config
        _dynamicEqConfigFlow.value = config
    }

    fun applyDynamicEqPreset(presetName: String) {
        val preset = DynamicEqConfig.PRESETS[presetName] ?: return
        val updated = preset.copy(enabled = true)
        dynamicEqConfig = updated
        _dynamicEqConfigFlow.value = updated
    }

    fun updateSpatialConfig(config: SpatialStageConfig) {
        val sanitized = config.copy(
            narrowWidthPercent = config.narrowWidthPercent.coerceIn(5f, 80f),
            depthAmount = config.depthAmount.coerceIn(0f, 1f),
            frontBackContrast = config.frontBackContrast.coerceIn(0f, 1f),
            preDelayMs = config.preDelayMs.coerceIn(4f, 42f),
            roomDecay = config.roomDecay.coerceIn(0.10f, 0.85f)
        )
        spatialConfig = sanitized
        _spatialConfigFlow.value = sanitized
    }

    fun applySpatialPreset(presetName: String) {
        val preset = SpatialStageConfig.PRESETS[presetName] ?: return
        updateSpatialConfig(preset)
    }

    fun updateDeclipConfig(config: DeclipConfig) {
        val sanitized = config.copy(
            sensitivity = config.sensitivity.coerceIn(0f, 1f),
            minAmplitude = config.minAmplitude.coerceIn(0.01f, 0.90f),
            maxReconstructRatio = config.maxReconstructRatio.coerceIn(1.05f, 2.50f)
        )
        declipConfig = sanitized
        _declipConfigFlow.value = sanitized
    }

    fun setManualSyncOffsetMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-200, 600)
        syncBuffer.manualSyncOffsetMs = clamped
        _syncOffsetMsFlow.value = clamped
    }

    fun publishTelemetry(
        data: VisualizerTelemetry,
        produceRealtimeMs: Long = System.nanoTime() / 1_000_000L,
        queuedStreamMs: Long = 0L,
        flushGen: Int = syncBuffer.flushGeneration
    ) {
        val enriched = if (data.carForwardCalibrated == currentCarForwardCalibrated) {
            data
        } else {
            data.copy(carForwardCalibrated = currentCarForwardCalibrated)
        }
        syncBuffer.push(enriched, produceRealtimeMs, queuedStreamMs, flushGen)
        // Expose latency-compensated frame to general StateFlow observers as well
        _telemetry.value = syncBuffer.sampleAt(produceRealtimeMs) ?: enriched
    }

    fun pollSyncedTelemetry(nowRealtimeMs: Long = System.nanoTime() / 1_000_000L): VisualizerTelemetry {
        val synced = syncBuffer.sampleAt(nowRealtimeMs)
        if (synced != null && synced !== _telemetry.value) {
            _telemetry.value = synced
        }
        return synced ?: _telemetry.value
    }
}
