package com.example.monoplayer.dsp

import kotlin.math.*

/**
 * Fast Radix-2 FFT / IFFT Implementation (FloatArray)
 * Ported directly from dsp.html's FastFFT class for zero-allocation real-time STFT processing.
 */
class FastFFT(val size: Int) {
    private val nativeHandle: Long =
        if (RustNativeBridge.isAvailable) RustNativeBridge.nativeFftCreate(size) else 0L
    private val bits: Int = (ln(size.toDouble()) / ln(2.0)).roundToInt()
    private val rev: IntArray = IntArray(size)
    private val cosTable: FloatArray = FloatArray(size / 2)
    private val sinTable: FloatArray = FloatArray(size / 2)

    init {
        for (i in 0 until size) {
            var x = i
            var r = 0
            for (j in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x ushr 1
            }
            rev[i] = r
        }
        for (i in 0 until size / 2) {
            val angle = -2.0 * Math.PI * i / size
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
    }

    fun transform(real: FloatArray, imag: FloatArray, inverse: Boolean = false) {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeFftTransform(nativeHandle, real, imag, inverse)
            return
        }
        val n = size
        for (i in 0 until n) {
            val j = rev[i]
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        val sign = if (inverse) -1f else 1f
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                for (j in 0 until half) {
                    val idx = j * step
                    val c = cosTable[idx]
                    val s = sinTable[idx] * sign
                    val uR = real[i + j]
                    val uI = imag[i + j]
                    val vR = real[i + j + half] * c - imag[i + j + half] * s
                    val vI = real[i + j + half] * s + imag[i + j + half] * c
                    real[i + j] = uR + vR
                    imag[i + j] = uI + vI
                    real[i + j + half] = uR - vR
                    imag[i + j + half] = uI - vI
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1.0f / n
            for (i in 0 until n) {
                real[i] *= invN
                imag[i] *= invN
            }
        }
    }
}

/**
 * Mono Downmix Algorithms from dsp.html
 */
enum class MonoAlgorithm(
    val id: String,
    val title: String,
    val badge: String,
    val subtitle: String,
    val formula: String,
    val description: String
) {
    HILBERT(
        id = "hilbert",
        title = "M/S高域・直交位相(90°)合成",
        badge = "時間・位相領域",
        subtitle = "自然な空気感 (推奨)",
        formula = "Mono(t) = (L+R)/2 + α · Hilbert_90°{ HPF_fc( (L-R)/2 ) }",
        description = "消えてしまうSide成分(L-R)/2の高域のみを抽出し、ヒルベルト変換で位相を90度回転させてMid信号に加算。中央定位のボーカルを100%保ちつつ干渉を起こさず高域の輝きを戻します。"
    ),
    STFT_ENERGY(
        id = "stft_energy",
        title = "パワースペクトル保存型 (STFT Energy)",
        badge = "周波数領域",
        subtitle = "高域100%維持・キラキラ感最大",
        formula = "|M(f)| = √( (|L(f)|² + |R(f)|²) / 2 ),  ∠M(f) = ∠Mid(f)",
        description = "短時間フーリエ変換(STFT)で各周波数ビンのエネルギー和を計算。中央のボーカル(Mid)を下限保証しつつ、逆位相による高域減衰をゼロにしてシンバルや倍音を強く残します。"
    ),
    PHASE_ALIGN(
        id = "phase_align",
        title = "周波数帯域別・位相アライメント",
        badge = "位相補正",
        subtitle = "アタック感・輪郭重視",
        formula = "M(f) = 0.5 × ( L(f) + R(f) · exp( j(∠L(f) - ∠R(f)) ) )",
        description = "周波数帯域ごとにLとRの位相差Δφを検出し、Rチャンネルの位相をLに揃えてから加算。ステレオディレイやマイキングのズレによる高域減衰を綺麗に解消します。"
    ),
    DYNAMIC_PCA(
        id = "pca",
        title = "高域主成分分析 (Dynamic PCA)",
        badge = "統計解析",
        subtitle = "低遅延・高明瞭",
        formula = "Mono_HF(t) = Mid_HF(t) + β · s(t) · Side_HF(t),  s=±1 (PCA主成分が優勢なch)",
        description = "中央定位のボーカル(Mid信号)を一切削らずに100%維持したまま、微小時間窓の主成分分析で逆相の高域Sideエネルギーのみを直交補完します。"
    ),
    NAIVE(
        id = "naive",
        title = "単純加算平均 (L + R) / 2",
        badge = "比較基準",
        subtitle = "一般的なモノラル化 (コムフィルタ発生)",
        formula = "Mono(t) = 0.5 × ( L(t) + R(t) )  ※高域の位相差 Δφ=π で完全消失",
        description = "一般的な単純モノラル化。左右の位相差や逆相成分が打ち消し合い、高域の倍音やシンバル・シンセのキラキラ感が減衰します（比較確認用）。"
    ),
    STEREO_REF(
        id = "stereo",
        title = "Original Stereo (2ch スルー比較用)",
        badge = "REF",
        subtitle = "モノラル化前の基準2ch音声",
        formula = "Out_L = L(t),  Out_R = R(t)",
        description = "モノラル化前の元のステレオ音声です。高音質モノラルアルゴリズムとの聴き比べ（A/Bテスト）用です。"
    );

    companion object {
        fun fromId(id: String): MonoAlgorithm = entries.find { it.id == id } ?: HILBERT
    }
}
