package com.example.monoplayer.dsp

import kotlinx.serialization.Serializable

@Serializable
enum class LoudnessMode(
    val shortBadge: String,
    val title: String,
    val subtitle: String
) {
    OFF(
        shortBadge = "NORM OFF",
        title = "ノーマライズ OFF",
        subtitle = "音源本来のマスター音量をそのまま出力"
    ),
    NORMAL_HIFI(
        shortBadge = "Hi-Fi -14",
        title = "通常聴取モード (Hi-Fi Reference)",
        subtitle = "曲間の音圧差を自然に揃え、曲内の抑揚・音色は100%保持"
    ),
    NOISY_CAR(
        shortBadge = "CAR CABIN",
        title = "うるさい車内視聴モード (Car Cabin)",
        subtitle = "走行騒音に埋もれる小音量を持ち上げ、低域輪郭と声の明瞭度を動的補正"
    );

    fun nextMode(): LoudnessMode = when (this) {
        OFF -> NORMAL_HIFI
        NORMAL_HIFI -> NOISY_CAR
        NOISY_CAR -> OFF
    }
}

@Serializable
enum class SpeedTrackingSource(val label: String) {
    OFF("OFF"),
    GPS("GPS 測位中"),
    TUNNEL_IMU("トンネル内推測 (IMU)"),
    STOPPED("アイドリング停車"),
    HANDHELD("手持ち検出中 (GPS停止)")
}

@Serializable
data class LoudnessConfig(
    val mode: LoudnessMode = LoudnessMode.OFF,
    val normalTargetLufs: Float = -14.0f,       // -20.0 .. -9.0 LUFS
    val carTargetLufs: Float = -19.0f,          // -24.0 .. -8.0 LUFS (停車基準・ヘッドルーム確保)
    val carCabinIntensity: Float = 0.70f,       // 0.0 (停車) .. 1.0 (標準巡航) .. 2.0 (最大)
    val autoSpeedEnabled: Boolean = false,      // 車速連動自体のオン/オフ
    val speedCompensationFactor: Float = 1.0f,  // 0.2 .. 2.0 (音の増減係数: 標準1.0 / 最大2.0)
    val speedReferenceKmh: Float = 80.0f,       // 50.0 .. 130.0 km/h (基準補正に達する速度)
    val headroomSchemaVersion: Int = 2
)

@Serializable
enum class DynamicBandType(
    val label: String,
    val filterName: String,
    val minFreqHz: Float,
    val maxFreqHz: Float
) {
    LOW("LOW", "Low-Shelf", 40f, 400f),
    MID("MID", "Peaking Bell", 300f, 5000f),
    HIGH("HIGH", "High-Shelf", 2500f, 12000f)
}

@Serializable
data class DynamicBandConfig(
    val enabled: Boolean = true,
    val freqHz: Float,
    val q: Float = 0.707f,
    val thresholdDb: Float = -18.0f,     // -48 .. 0 dB
    val maxDynamicGainDb: Float = 0.0f,  // -12 .. +12 dB (<0: Dynamic Cut, >0: Dynamic Boost)
    val staticGainDb: Float = 0.0f,      // -6 .. +6 dB
    val attackMs: Float = 10.0f,         // 1 .. 100 ms
    val releaseMs: Float = 100.0f        // 20 .. 500 ms
)

@Serializable
data class DynamicEqConfig(
    val enabled: Boolean = false,
    val presetName: String = "Club Sub-Tight & Air",
    val low: DynamicBandConfig = DynamicBandConfig(
        enabled = true,
        freqHz = 140f,
        q = 0.707f,
        thresholdDb = -18f,
        maxDynamicGainDb = -4.5f,
        staticGainDb = 0.5f,
        attackMs = 15f,
        releaseMs = 140f
    ),
    val mid: DynamicBandConfig = DynamicBandConfig(
        enabled = true,
        freqHz = 2100f,
        q = 1.2f,
        thresholdDb = -20f,
        maxDynamicGainDb = -3.0f,
        staticGainDb = 0.0f,
        attackMs = 8f,
        releaseMs = 90f
    ),
    val high: DynamicBandConfig = DynamicBandConfig(
        enabled = true,
        freqHz = 5800f,
        q = 0.707f,
        thresholdDb = -22f,
        maxDynamicGainDb = 3.0f,
        staticGainDb = 0.5f,
        attackMs = 3f,
        releaseMs = 65f
    )
) {
    fun band(type: DynamicBandType): DynamicBandConfig = when (type) {
        DynamicBandType.LOW -> low
        DynamicBandType.MID -> mid
        DynamicBandType.HIGH -> high
    }

    fun withBand(type: DynamicBandType, updated: DynamicBandConfig): DynamicEqConfig = when (type) {
        DynamicBandType.LOW -> copy(low = updated, presetName = "Custom")
        DynamicBandType.MID -> copy(mid = updated, presetName = "Custom")
        DynamicBandType.HIGH -> copy(high = updated, presetName = "Custom")
    }

    companion object {
        val PRESETS: Map<String, DynamicEqConfig> = linkedMapOf(
            "Flat (Init)" to DynamicEqConfig(
                enabled = true,
                presetName = "Flat (Init)",
                low = DynamicBandConfig(freqHz = 150f, thresholdDb = -18f, maxDynamicGainDb = 0f, attackMs = 15f, releaseMs = 140f),
                mid = DynamicBandConfig(freqHz = 1800f, q = 1.1f, thresholdDb = -18f, maxDynamicGainDb = 0f, attackMs = 8f, releaseMs = 90f),
                high = DynamicBandConfig(freqHz = 5500f, thresholdDb = -18f, maxDynamicGainDb = 0f, attackMs = 3f, releaseMs = 60f)
            ),
            "Club Sub-Tight & Air" to DynamicEqConfig(
                enabled = true,
                presetName = "Club Sub-Tight & Air"
            ),
            "Vocal De-Harsh (聴き疲れ防止)" to DynamicEqConfig(
                enabled = true,
                presetName = "Vocal De-Harsh (聴き疲れ防止)",
                low = DynamicBandConfig(freqHz = 160f, thresholdDb = -16f, maxDynamicGainDb = -2.0f, attackMs = 18f, releaseMs = 150f),
                mid = DynamicBandConfig(freqHz = 2400f, q = 1.6f, thresholdDb = -21f, maxDynamicGainDb = -5.5f, attackMs = 5f, releaseMs = 85f),
                high = DynamicBandConfig(freqHz = 6500f, thresholdDb = -22f, maxDynamicGainDb = -4.0f, attackMs = 2f, releaseMs = 55f)
            ),
            "Mono Punch Enhancer" to DynamicEqConfig(
                enabled = true,
                presetName = "Mono Punch Enhancer",
                low = DynamicBandConfig(freqHz = 110f, thresholdDb = -20f, maxDynamicGainDb = 4.0f, staticGainDb = 0.5f, attackMs = 12f, releaseMs = 120f),
                mid = DynamicBandConfig(freqHz = 1500f, q = 1.2f, thresholdDb = -19f, maxDynamicGainDb = -3.0f, attackMs = 8f, releaseMs = 95f),
                high = DynamicBandConfig(freqHz = 6000f, thresholdDb = -24f, maxDynamicGainDb = 2.5f, attackMs = 3f, releaseMs = 60f)
            ),
            "Late Night Smooth" to DynamicEqConfig(
                enabled = true,
                presetName = "Late Night Smooth",
                low = DynamicBandConfig(freqHz = 130f, thresholdDb = -20f, maxDynamicGainDb = -4.0f, staticGainDb = 1.0f, attackMs = 20f, releaseMs = 180f),
                mid = DynamicBandConfig(freqHz = 2000f, q = 1.0f, thresholdDb = -22f, maxDynamicGainDb = -3.5f, attackMs = 10f, releaseMs = 120f),
                high = DynamicBandConfig(freqHz = 5000f, thresholdDb = -22f, maxDynamicGainDb = -3.5f, staticGainDb = -0.5f, attackMs = 4f, releaseMs = 80f)
            )
        )
    }
}


@Serializable
enum class OutputStageMode(
    val shortBadge: String,
    val title: String,
    val subtitle: String
) {
    PURE_MONO(
        shortBadge = "PURE MONO",
        title = "完全モノラル (Pure Mono)",
        subtitle = "L/R完全同一出力。ステレオSide成分を前後・奥行きの立体レイヤーへ変換"
    ),
    NARROW_STEREO(
        shortBadge = "NARROW ST",
        title = "ナローステレオ (Narrow Stereo)",
        subtitle = "180Hz以下はモノ固定しつつ、中央の芯を保った自然な微細ステレオ幅に調整"
    )
}

@Serializable
data class SpatialStageConfig(
    val stageMode: OutputStageMode = OutputStageMode.PURE_MONO,
    val narrowWidthPercent: Float = 32f, // 5 .. 80 % (active in NARROW_STEREO)
    val depthEnabled: Boolean = false,
    val depthAmount: Float = 0.36f,      // 0.0 .. 1.0 (Z-axis spatial wet mix)
    val frontBackContrast: Float = 0.48f,// 0.0 .. 1.0 (Blauert front presence vs rear Side depth dip)
    val preDelayMs: Float = 18f,         // 4.0 .. 42.0 ms (Haas ITDG foreground/background separation)
    val roomDecay: Float = 0.42f,        // 0.10 .. 0.85 (Early reflection span & FDN tail decay)
    val presetName: String = "Mono Z-Depth (標準・前後立体)"
) {
    companion object {
        val PRESETS: Map<String, SpatialStageConfig> = linkedMapOf(
            "Mono Z-Depth (標準・前後立体)" to SpatialStageConfig(
                stageMode = OutputStageMode.PURE_MONO,
                narrowWidthPercent = 32f,
                depthEnabled = true,
                depthAmount = 0.36f,
                frontBackContrast = 0.48f,
                preDelayMs = 18f,
                roomDecay = 0.42f,
                presetName = "Mono Z-Depth (標準・前後立体)"
            ),
            "Deep Club Stage (深い前後奥行き)" to SpatialStageConfig(
                stageMode = OutputStageMode.PURE_MONO,
                narrowWidthPercent = 32f,
                depthEnabled = true,
                depthAmount = 0.58f,
                frontBackContrast = 0.68f,
                preDelayMs = 26f,
                roomDecay = 0.64f,
                presetName = "Deep Club Stage (深い前後奥行き)"
            ),
            "Pinpoint Narrow 25% (凝縮ナロー)" to SpatialStageConfig(
                stageMode = OutputStageMode.NARROW_STEREO,
                narrowWidthPercent = 25f,
                depthEnabled = true,
                depthAmount = 0.34f,
                frontBackContrast = 0.44f,
                preDelayMs = 16f,
                roomDecay = 0.38f,
                presetName = "Pinpoint Narrow 25% (凝縮ナロー)"
            ),
            "Speaker Monitor 42% (スピーカー定位)" to SpatialStageConfig(
                stageMode = OutputStageMode.NARROW_STEREO,
                narrowWidthPercent = 42f,
                depthEnabled = true,
                depthAmount = 0.28f,
                frontBackContrast = 0.38f,
                preDelayMs = 12f,
                roomDecay = 0.32f,
                presetName = "Speaker Monitor 42% (スピーカー定位)"
            ),
            "Dry Pure Mono (空間OFF・純モノ)" to SpatialStageConfig(
                stageMode = OutputStageMode.PURE_MONO,
                narrowWidthPercent = 32f,
                depthEnabled = false,
                depthAmount = 0f,
                frontBackContrast = 0f,
                preDelayMs = 18f,
                roomDecay = 0.35f,
                presetName = "Dry Pure Mono (空間OFF・純モノ)"
            )
        )
    }
}

/**
 * Configuration for the anti-clipping reconstruction & de-clicking processor.
 * Capable of detecting both 0dBFS clipping and attenuated/mastered clips where
 * track volume was lowered, leaving flat tops and clicks.
 */
@Serializable
data class DeclipConfig(
    val enabled: Boolean = false,
    val sensitivity: Float = 0.75f,        // 0.0 .. 1.0
    val minAmplitude: Float = 0.12f,       // 0.05 .. 0.50
    val maxReconstructRatio: Float = 1.50f // 1.1 .. 2.0
)

