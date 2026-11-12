package com.example.monoplayer.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.min

/**
 * Native-display-resolution (1080p / 1440p+) hardware GPU raindrop & wet-glass optical renderer
 * powered by Android 13+ AGSL [RuntimeShader].
 *
 * Evaluates analytical sessile spherical-cap micro-beads (via a 24x24 sub-pixel cellular grid)
 * and 10 coalescing/sliding 3D teardrops with C1 smooth capillary bridges (smax), chromatic
 * Snell refraction into a 768x768 sharp jacket texture, Schlick Fresnel rim reflections,
 * lower-belly caustics, and razor-sharp pinpoint specular glints at every physical screen pixel.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class RainGlassGpuRenderer private constructor(
    private val runtimeShader: RuntimeShader
) {
    companion object {
        const val SHARP_JACKET_SIZE = 768

        fun createOrNull(): RainGlassGpuRenderer? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return runCatching {
                RainGlassGpuRenderer(RuntimeShader(AGSL_RAIN_GLASS_SHADER))
            }.onFailure { err ->
                android.util.Log.w("RainGlassGpuRenderer", "AGSL shader fallback to 512px CPU", err)
            }.getOrNull()
        }

        private val AGSL_RAIN_GLASS_SHADER = """
            uniform float2 resolution;
            uniform float sharpSize;
            uniform float fogSize;
            uniform float highDrive;

            uniform shader sharpJacket;
            uniform shader fogJacket;
            uniform shader fogMask;
            uniform shader beadGrid;

            uniform float4 drop0;
            uniform float4 drop1;
            uniform float4 drop2;
            uniform float4 drop3;
            uniform float4 drop4;
            uniform float4 drop5;
            uniform float4 drop6;
            uniform float4 drop7;
            uniform float4 drop8;
            uniform float4 drop9;

            float decodeChannel(float v, float cal) {
                float res = v;
                if (cal < 0.35) {
                    res = (v <= 0.0031308) ? (v * 12.92) : (1.055 * pow(max(v, 0.0001), 0.4166667) - 0.055);
                } else if (cal > 0.65) {
                    res = (v <= 0.04045) ? (v / 12.92) : pow(max((v + 0.055) / 1.055, 0.0001), 2.4);
                }
                return res;
            }

            float3 mergeMeniscus(float3 acc, float3 cand, float kSmooth) {
                float3 res = acc;
                if (cand.x > 0.0) {
                    if (acc.x <= 0.0) {
                        res = cand;
                    } else {
                        float t = clamp(0.5 + 0.5 * (cand.x - acc.x) / kSmooth, 0.0, 1.0);
                        float h = mix(acc.x, cand.x, t) + kSmooth * t * (1.0 - t);
                        float2 g = mix(acc.yz, cand.yz, t);
                        res = float3(h, g.x, g.y);
                    }
                }
                return res;
            }

            float3 evalBead(float2 p256, float2 c256, float r256) {
                float3 res = float3(0.0);
                float2 d = p256 - c256;
                float invR2 = 1.0 / max(r256 * r256, 0.25);
                float u2 = dot(d, d) * invR2;
                if (u2 < 1.0) {
                    float root = sqrt(1.0 - 0.86 * u2);
                    float cap = (root - 0.3741657) * 1.597866;
                    float hScale = 0.54;
                    float factor = hScale * (-0.6870824 / root) * 2.0 * invR2;
                    res = float3(cap * hScale, factor * d.x, factor * d.y);
                }
                return res;
            }

            float3 evalTeardrop(float2 p256, float4 dropNorm) {
                float3 res = float3(0.0);
                float r = dropNorm.z * 256.0;
                if (r > 0.05) {
                    float2 c = dropNorm.xy * 256.0;
                    float speed = dropNorm.w;
                    float stretchUp = 1.0 + speed;
                    float rawDy = p256.y - c.y;
                    float scaleY = (rawDy < 0.0) ? (r * stretchUp) : (r * 1.03);
                    float ny = rawDy / scaleY;
                    if (abs(ny) < 1.0) {
                        float taper = 0.08 + speed * 0.30;
                        float wf = 1.02;
                        float dwfDny = 0.0;
                        if (ny < 0.0) {
                            float rawWf = 1.0 + ny * taper;
                            if (rawWf > 0.36) {
                                wf = rawWf;
                                dwfDny = taper;
                            } else {
                                wf = 0.36;
                            }
                        }
                        float dx = p256.x - c.x;
                        float effR = r * wf;
                        float nx = dx / effR;
                        float u2 = nx * nx + ny * ny;
                        if (u2 < 1.0) {
                            float dnyDy = 1.0 / scaleY;
                            float dnxDx = 1.0 / effR;
                            float dnxDy = (-nx / wf) * dwfDny * dnyDy;
                            float du2Dx = 2.0 * nx * dnxDx;
                            float du2Dy = 2.0 * nx * dnxDy + 2.0 * ny * dnyDy;
                            float root = sqrt(1.0 - 0.86 * u2);
                            float cap = (root - 0.3741657) * 1.597866;
                            float dCapDu2 = -0.6870824 / root;
                            float bellySlope = 0.14 + speed * 0.16;
                            float rawBelly = 1.0 + ny * bellySlope;
                            float belly = clamp(rawBelly, 0.60, 1.30);
                            float dBellyDy = (rawBelly > 0.60 && rawBelly < 1.30) ? (bellySlope * dnyDy) : 0.0;
                            float baseH = 0.52 + max(r - 1.5, 0.0) * 0.068;
                            float h = baseH * belly * cap;
                            float dhdx = baseH * belly * dCapDu2 * du2Dx;
                            float dhdy = baseH * (dBellyDy * cap + belly * dCapDu2 * du2Dy);
                            res = float3(h, dhdx, dhdy);
                        }
                    }
                }
                return res;
            }

            half4 main(float2 fragCoord) {
                float scale = max(resolution.x, resolution.y);
                float2 uv = clamp((fragCoord - 0.5 * resolution) / max(scale, 1.0) + 0.5, 0.001, 0.999);
                float2 p256 = uv * 256.0;

                float cal = float(beadGrid.eval(float2(24.5, 0.5)).r);

                float3 field = float3(0.0);
                float2 cellBase = floor(uv * 24.0);
                for (int jy = -1; jy <= 1; jy++) {
                    for (int jx = -1; jx <= 1; jx++) {
                        float cx = cellBase.x + float(jx);
                        float cy = cellBase.y + float(jy);
                        if (cx >= 0.0 && cx < 24.0 && cy >= 0.0 && cy < 24.0) {
                            half4 raw = beadGrid.eval(float2(cx + 0.5, cy + 0.5));
                            float rNorm = decodeChannel(float(raw.b), cal);
                            if (rNorm > 0.003) {
                                float ox = decodeChannel(float(raw.r), cal);
                                float oy = decodeChannel(float(raw.g), cal);
                                float2 beadPos = (float2(cx + ox, cy + oy) / 24.0) * 256.0;
                                field = mergeMeniscus(field, evalBead(p256, beadPos, rNorm * 4.0), 0.10);
                            }
                        }
                    }
                }

                field = mergeMeniscus(field, evalTeardrop(p256, drop0), 0.15);
                field = mergeMeniscus(field, evalTeardrop(p256, drop1), 0.15);
                field = mergeMeniscus(field, evalTeardrop(p256, drop2), 0.15);
                field = mergeMeniscus(field, evalTeardrop(p256, drop3), 0.15);
                field = mergeMeniscus(field, evalTeardrop(p256, drop4), 0.15);
                field = mergeMeniscus(field, evalTeardrop(p256, drop5), 0.15);
                field = mergeMeniscus(field, evalTeardrop(p256, drop6), 0.15);
                field = mergeMeniscus(field, evalTeardrop(p256, drop7), 0.15);
                field = mergeMeniscus(field, evalTeardrop(p256, drop8), 0.15);
                field = mergeMeniscus(field, evalTeardrop(p256, drop9), 0.15);

                float3 sharpBg = float3(sharpJacket.eval(uv * sharpSize).rgb);
                float3 fogBg = float3(fogJacket.eval(uv * fogSize).rgb);
                float fogAmt = clamp(decodeChannel(float(fogMask.eval(uv * fogSize).r), cal), 0.0, 1.0);
                float3 glassRgb = mix(sharpBg, fogBg, fogAmt);

                float3 finalRgb = glassRgb;
                if (field.x > 0.0015) {
                    float3 n = normalize(float3(-field.y * 3.90, -field.z * 3.90, 1.0));
                    float refractUv = (18.5 + field.x * 27.5) / 256.0;
                    float2 offUv = n.xy * refractUv;
                    float2 uvR = clamp(uv - offUv * 0.972, 0.002, 0.998);
                    float2 uvG = clamp(uv - offUv, 0.002, 0.998);
                    float2 uvB = clamp(uv - offUv * 1.032, 0.002, 0.998);

                    float3 refrRgb = float3(
                        float(sharpJacket.eval(uvR * sharpSize).r),
                        float(sharpJacket.eval(uvG * sharpSize).g),
                        float(sharpJacket.eval(uvB * sharpSize).b)
                    );

                    float omc = clamp(1.0 - n.z, 0.0, 1.0);
                    float fresnel = omc * omc * omc;
                    float rimDarken = 1.0 - fresnel * 0.56;
                    refrRgb = refrRgb * rimDarken + fresnel * float3(0.188, 0.227, 0.322);

                    if (n.y > 0.12 && n.z > 0.52) {
                        float caustic = (n.y - 0.12) * (n.z - 0.52) * 1.42;
                        refrRgb *= float3(1.0 + caustic, 1.0 + caustic * 0.98, 1.0 + caustic * 0.94);
                    }

                    float d1 = max(dot(n, float3(-0.36, -0.66, 0.66)), 0.0);
                    float d1_2 = d1 * d1;
                    float d1_4 = d1_2 * d1_2;
                    float d1_16 = d1_4 * d1_4;
                    float d1_32 = d1_16 * d1_16;
                    float spec1 = d1_32 * d1_32 * d1_32 * (0.96 + highDrive * 0.18);

                    float d2 = max(dot(n, float3(0.44, -0.48, 0.76)), 0.0);
                    float d2_2 = d2 * d2;
                    float d2_4 = d2_2 * d2_2;
                    float spec2 = d2_4 * d2_4 * d2_4 * 0.25;

                    float3 specRgb = float3(
                        spec1 + spec2 * 0.82,
                        spec1 * 0.98 + spec2 * 0.92,
                        spec1 * 0.94 + spec2 * 1.12
                    );

                    float px256 = 256.0 / max(scale, 1.0);
                    float gradMag = length(field.yz);
                    float edgeAlpha = smoothstep(0.0, 1.35, field.x / max(gradMag * px256, 0.016));
                    finalRgb = clamp(mix(glassRgb, refrRgb + specRgb, edgeAlpha), 0.0, 1.0);
                }
                return half4(half3(finalRgb), 1.0);
            }
        """.trimIndent()
    }

    private val sharpBitmap = Bitmap.createBitmap(SHARP_JACKET_SIZE, SHARP_JACKET_SIZE, Bitmap.Config.ARGB_8888)
    private var fogJacketBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    private var fogMaskBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    private val beadGridBitmap = Bitmap.createBitmap(
        RainGlassEngine.GPU_BEAD_TEX_WIDTH,
        RainGlassEngine.GPU_BEAD_TEX_HEIGHT,
        Bitmap.Config.ARGB_8888
    )

    private val sharpWorkPixels = IntArray(SHARP_JACKET_SIZE * SHARP_JACKET_SIZE)
    private var fogMaskTexels = IntArray(256 * 256)
    private val beadGridTexels = IntArray(RainGlassEngine.GPU_BEAD_TEX_WIDTH * RainGlassEngine.GPU_BEAD_TEX_HEIGHT)
    private val dropUniformFloats = FloatArray(40)

    private var sharpShader = createFilteredShader(sharpBitmap, linear = true)
    private var fogJacketShader = createFilteredShader(fogJacketBitmap, linear = true)
    private var fogMaskShader = createFilteredShader(fogMaskBitmap, linear = true)
    private val beadGridShader = createFilteredShader(beadGridBitmap, linear = false)

    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastArtworkRef: Bitmap? = null
    private var initializedArtwork = false

    private fun createFilteredShader(bitmap: Bitmap, linear: Boolean): BitmapShader {
        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = if (linear) BitmapShader.FILTER_MODE_LINEAR else BitmapShader.FILTER_MODE_NEAREST
        }
    }

    fun updateArtwork(artworkBitmap: Bitmap?, engine: RainGlassEngine) {
        engine.updateArtworkBitmap(artworkBitmap)
        if (initializedArtwork && artworkBitmap === lastArtworkRef &&
            fogJacketBitmap.width == engine.width && fogJacketBitmap.height == engine.height
        ) {
            return
        }
        lastArtworkRef = artworkBitmap
        initializedArtwork = true

        if (fogJacketBitmap.width != engine.width || fogJacketBitmap.height != engine.height) {
            fogJacketBitmap = Bitmap.createBitmap(engine.width, engine.height, Bitmap.Config.ARGB_8888)
            fogMaskBitmap = Bitmap.createBitmap(engine.width, engine.height, Bitmap.Config.ARGB_8888)
            fogMaskTexels = IntArray(engine.width * engine.height)
            fogJacketShader = createFilteredShader(fogJacketBitmap, linear = true)
            fogMaskShader = createFilteredShader(fogMaskBitmap, linear = true)
        }
        fogJacketBitmap.setPixels(engine.fogPixels, 0, engine.width, 0, 0, engine.width, engine.height)

        if (artworkBitmap != null && artworkBitmap.width > 0 && artworkBitmap.height > 0) {
            populateHighResJacketFromBitmap(artworkBitmap)
        } else {
            populateHighResProceduralNightJacket()
        }
        sharpShader = createFilteredShader(sharpBitmap, linear = true)
        fogJacketShader = createFilteredShader(fogJacketBitmap, linear = true)
    }

    private fun populateHighResJacketFromBitmap(src: Bitmap) {
        val side = min(src.width, src.height)
        val cropX = (src.width - side) / 2
        val cropY = (src.height - side) / 2
        val cropped = if (cropX == 0 && cropY == 0 && src.width == src.height) {
            src
        } else {
            Bitmap.createBitmap(src, cropX, cropY, side, side)
        }
        val scaled = if (cropped.width == SHARP_JACKET_SIZE && cropped.height == SHARP_JACKET_SIZE) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, SHARP_JACKET_SIZE, SHARP_JACKET_SIZE, true)
        }
        scaled.getPixels(sharpWorkPixels, 0, SHARP_JACKET_SIZE, 0, 0, SHARP_JACKET_SIZE, SHARP_JACKET_SIZE)
        if (scaled !== src && scaled !== cropped) scaled.recycle()
        if (cropped !== src) cropped.recycle()
        applyWetGlassVignetteAndStore()
    }

    private fun populateHighResProceduralNightJacket() {
        val n = SHARP_JACKET_SIZE
        val invN = 1f / n.toFloat()
        for (y in 0 until n) {
            val ny = y * invN
            val row = y * n
            for (x in 0 until n) {
                val nx = x * invN
                var r = 10f + 22f * (1f - ny)
                var g = 12f + 18f * ny
                var b = 32f + 46f * (1f - nx * 0.5f)
                val b1 = bokehFalloff(nx - 0.30f, ny - 0.34f, 0.032f)
                val b2 = bokehFalloff(nx - 0.72f, ny - 0.28f, 0.024f)
                val b3 = bokehFalloff(nx - 0.54f, ny - 0.66f, 0.045f)
                val b4 = bokehFalloff(nx - 0.22f, ny - 0.75f, 0.018f)
                r += b1 * 215f + b2 * 135f + b3 * 195f + b4 * 80f
                g += b1 * 158f + b2 * 52f + b3 * 132f + b4 * 125f
                b += b1 * 64f + b2 * 190f + b3 * 58f + b4 * 210f
                sharpWorkPixels[row + x] = (0xFF shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
        }
        applyWetGlassVignetteAndStore()
    }

    private fun bokehFalloff(dx: Float, dy: Float, sigmaSq: Float): Float {
        val d2 = (dx * dx + dy * dy) / sigmaSq
        return 1f / (1f + d2 + 0.48f * d2 * d2)
    }

    private fun applyWetGlassVignetteAndStore() {
        val n = SHARP_JACKET_SIZE
        val half = n * 0.5f
        val invMaxDistSq = 1f / (half * half * 2f)
        for (y in 0 until n) {
            val dy = y - half
            val row = y * n
            for (x in 0 until n) {
                val dx = x - half
                val vig = (1.0f - 0.28f * (dx * dx + dy * dy) * invMaxDistSq).coerceIn(0.62f, 1.0f)
                val c = sharpWorkPixels[row + x]
                val r = (((c ushr 16) and 0xFF) * 0.94f * vig).toInt().coerceIn(0, 255)
                val g = (((c ushr 8) and 0xFF) * 0.96f * vig).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * 1.00f * vig).toInt().coerceIn(0, 255)
                sharpWorkPixels[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        sharpBitmap.setPixels(sharpWorkPixels, 0, n, 0, 0, n, n)
    }

    fun syncFrameFromEngine(engine: RainGlassEngine) {
        if (!initializedArtwork) {
            updateArtwork(null, engine)
        }
        engine.packGpuFogMask(fogMaskTexels)
        fogMaskBitmap.setPixels(fogMaskTexels, 0, engine.width, 0, 0, engine.width, engine.height)

        engine.packGpuBeadGrid(beadGridTexels)
        beadGridBitmap.setPixels(
            beadGridTexels,
            0,
            RainGlassEngine.GPU_BEAD_TEX_WIDTH,
            0,
            0,
            RainGlassEngine.GPU_BEAD_TEX_WIDTH,
            RainGlassEngine.GPU_BEAD_TEX_HEIGHT
        )

        engine.packGpuDrops(dropUniformFloats)
    }

    fun drawNativeResolution(
        nativeCanvas: Canvas,
        width: Float,
        height: Float,
        highDrive: Float
    ) {
        val w = width.coerceAtLeast(1f)
        val h = height.coerceAtLeast(1f)
        runtimeShader.setFloatUniform("resolution", w, h)
        runtimeShader.setFloatUniform("sharpSize", SHARP_JACKET_SIZE.toFloat())
        runtimeShader.setFloatUniform("fogSize", fogJacketBitmap.width.toFloat())
        runtimeShader.setFloatUniform("highDrive", highDrive.coerceIn(0f, 1f))

        for (i in 0 until 10) {
            val b = i * 4
            runtimeShader.setFloatUniform(
                "drop$i",
                dropUniformFloats[b],
                dropUniformFloats[b + 1],
                dropUniformFloats[b + 2],
                dropUniformFloats[b + 3]
            )
        }

        runtimeShader.setInputShader("sharpJacket", sharpShader)
        runtimeShader.setInputShader("fogJacket", fogJacketShader)
        runtimeShader.setInputShader("fogMask", fogMaskShader)
        runtimeShader.setInputShader("beadGrid", beadGridShader)

        shaderPaint.shader = runtimeShader
        nativeCanvas.drawRect(0f, 0f, w, h, shaderPaint)
    }
}
