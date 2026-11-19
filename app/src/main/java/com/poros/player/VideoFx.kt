package com.poros.player

import android.graphics.BlendMode
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import kotlin.math.abs

/** Built-in video look presets. */
enum class FxPreset(val id: String, val label: String) {
    CINEMA("cinema", "Cinema"),
    HDR("hdr", "HDR"),
    VIVID("vivid", "Vivid"),
    WARM("warm", "Warm"),
    COOL("cool", "Cool"),
    BW("bw", "B&W"),
    VINTAGE("vintage", "Vintage"),
    ANIME("anime", "Anime"),
    NIGHT("night", "Night Vision"),
    SHARPEN("sharpen", "Sharpen"),
    SOFT("soft", "Soft"),
    GLOW("glow", "Glow"),
    FILM("film", "Film");

    companion object {
        fun fromId(id: String): FxPreset? = values().firstOrNull { it.id == id }
    }
}

/**
 * Everything the user can change: a preset + its intensity (0..1) and four manual
 * fine-tune sliders (-1..1). A custom preset is just a saved copy of this.
 */
data class FxState(
    val preset: FxPreset? = null,
    val intensity: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f
) {
    val isIdentity: Boolean
        get() = (preset == null || intensity <= 0.001f) &&
            abs(brightness) < 0.001f && abs(contrast) < 0.001f &&
            abs(saturation) < 0.001f && abs(warmth) < 0.001f
}

object VideoFx {

    private class FxResult(val matrix: ColorMatrix, val sharpen: Float, val blur: Float, val glow: Float)

    // ------------------------------------------------------------ matrix helpers
    private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s.coerceAtLeast(0f)) }

    private fun gains(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }

    /** out = c * (in - 0.5) + 0.5 + b   (b is -1..1 of full range) */
    private fun contrast(c: Float, b: Float = 0f): ColorMatrix {
        val tr = (0.5f * (1f - c) + b) * 255f
        return ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, tr,
                0f, c, 0f, 0f, tr,
                0f, 0f, c, 0f, tr,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun sepia(a: Float) = ColorMatrix(
        floatArrayOf(
            1f - a + a * 0.393f, a * 0.769f, a * 0.189f, 0f, 0f,
            a * 0.349f, 1f - a + a * 0.686f, a * 0.168f, 0f, 0f,
            a * 0.272f, a * 0.534f, 1f - a + a * 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    /** Blend towards a green-phosphor monochrome image. */
    private fun nightGreen(a: Float): ColorMatrix {
        val k = 1.8f
        return ColorMatrix(
            floatArrayOf(
                1f - a, 0f, 0f, 0f, 0f,
                a * 0.299f * k, 1f - a + a * 0.587f * k, a * 0.114f * k, 0f, 0f,
                0f, 0f, 1f - a, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    // ------------------------------------------------------------ preset maths
    private fun compute(s: FxState): FxResult {
        val t = s.intensity.coerceIn(0f, 1f)
        val m = ColorMatrix()
        var sharpen = 0f
        var blur = 0f
        var glow = 0f
        val hasBlur = Build.VERSION.SDK_INT >= 31
        val hasAgsl = Build.VERSION.SDK_INT >= 33

        val p = s.preset
        if (p != null && t > 0f) {
            when (p) {
                FxPreset.CINEMA -> {
                    m.postConcat(contrast(1f + 0.22f * t, -0.03f * t))
                    m.postConcat(sat(1f - 0.12f * t))
                    m.postConcat(gains(1f + 0.07f * t, 1f, 1f - 0.08f * t))
                }
                FxPreset.HDR -> {
                    m.postConcat(contrast(1f + 0.30f * t, 0.03f * t))
                    m.postConcat(sat(1f + 0.35f * t))
                }
                FxPreset.VIVID -> {
                    m.postConcat(sat(1f + 0.9f * t))
                    m.postConcat(contrast(1f + 0.10f * t))
                }
                FxPreset.WARM -> m.postConcat(gains(1f + 0.14f * t, 1f + 0.03f * t, 1f - 0.16f * t))
                FxPreset.COOL -> m.postConcat(gains(1f - 0.12f * t, 1f, 1f + 0.16f * t))
                FxPreset.BW -> {
                    m.postConcat(sat(1f - t))
                    m.postConcat(contrast(1f + 0.12f * t))
                }
                FxPreset.VINTAGE -> {
                    m.postConcat(sepia(0.55f * t))
                    m.postConcat(sat(1f - 0.25f * t))
                    m.postConcat(contrast(1f - 0.12f * t, 0.05f * t))
                    m.postConcat(gains(1f + 0.04f * t, 1f, 1f - 0.05f * t))
                }
                FxPreset.ANIME -> {
                    m.postConcat(sat(1f + 0.55f * t))
                    m.postConcat(contrast(1f + 0.15f * t, 0.02f * t))
                    if (hasAgsl) sharpen = 0.25f * t
                }
                FxPreset.NIGHT -> {
                    m.postConcat(nightGreen(t))
                    m.postConcat(contrast(1f + 0.30f * t, 0.12f * t))
                }
                FxPreset.SHARPEN -> {
                    if (hasAgsl) {
                        sharpen = 0.8f * t
                        m.postConcat(contrast(1f + 0.05f * t))
                    } else {
                        m.postConcat(contrast(1f + 0.20f * t))   // fallback: punchier edges
                    }
                }
                FxPreset.SOFT -> {
                    m.postConcat(contrast(1f - 0.10f * t, 0.02f * t))
                    if (hasBlur) blur = 3.5f * t else m.postConcat(contrast(1f - 0.12f * t))
                }
                FxPreset.GLOW -> {
                    m.postConcat(contrast(1f, 0.04f * t))
                    m.postConcat(sat(1f + 0.15f * t))
                    if (hasBlur) glow = t else m.postConcat(contrast(1f - 0.10f * t, 0.05f * t))
                }
                FxPreset.FILM -> {
                    m.postConcat(sepia(0.18f * t))
                    m.postConcat(sat(1f - 0.12f * t))
                    m.postConcat(contrast(1f + 0.10f * t, 0.03f * t))
                    m.postConcat(gains(1f + 0.03f * t, 1f, 1f - 0.03f * t))
                }
            }
        }

        // manual fine-tune, applied on top of any preset
        if (abs(s.brightness) > 0.001f || abs(s.contrast) > 0.001f) {
            m.postConcat(contrast(1f + s.contrast * 0.5f, s.brightness * 0.3f))
        }
        if (abs(s.saturation) > 0.001f) m.postConcat(sat(1f + s.saturation))
        if (abs(s.warmth) > 0.001f) m.postConcat(gains(1f + s.warmth * 0.2f, 1f, 1f - s.warmth * 0.2f))

        return FxResult(m, sharpen, blur, glow)
    }

    // ------------------------------------------------------------ apply to the video view
    /** Applies [s] to the video surface (a TextureView) instantly. Playback is never touched. */
    fun apply(view: View, s: FxState) {
        if (s.isIdentity) { clear(view); return }
        val r = compute(s)
        val cf = ColorMatrixColorFilter(r.matrix)
        if (Build.VERSION.SDK_INT >= 31) {
            Fx31.apply(view, r, cf)
        } else {
            val paint = Paint().apply { colorFilter = cf }
            view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }

    fun clear(view: View) {
        if (Build.VERSION.SDK_INT >= 31) Fx31.clear(view)
        view.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    @RequiresApi(31)
    private object Fx31 {
        fun clear(view: View) { view.setRenderEffect(null) }

        fun apply(view: View, r: FxResult, cf: ColorMatrixColorFilter) {
            var out: RenderEffect = RenderEffect.createColorFilterEffect(cf)

            if (r.blur > 0.05f) {
                out = RenderEffect.createChainEffect(
                    RenderEffect.createBlurEffect(r.blur, r.blur, Shader.TileMode.CLAMP), out
                )
            }
            if (r.sharpen > 0.01f && Build.VERSION.SDK_INT >= 33) {
                out = Fx33.sharpen(out, r.sharpen)
            }
            if (r.glow > 0.01f) {
                val k = 0.55f * r.glow
                val dim = RenderEffect.createColorFilterEffect(
                    ColorMatrixColorFilter(ColorMatrix().apply { setScale(k, k, k, 1f) })
                )
                val bloom = RenderEffect.createChainEffect(
                    dim, RenderEffect.createChainEffect(
                        RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP), out
                    )
                )
                out = RenderEffect.createBlendModeEffect(out, bloom, BlendMode.SCREEN)
            }
            view.setRenderEffect(out)
        }
    }

    @RequiresApi(33)
    private object Fx33 {
        private const val SRC = """
            uniform shader inputShader;
            uniform float amount;
            half4 main(float2 c) {
                half4 o = inputShader.eval(c);
                half4 n = inputShader.eval(c + float2(0.0, -1.0));
                half4 s = inputShader.eval(c + float2(0.0, 1.0));
                half4 w = inputShader.eval(c + float2(-1.0, 0.0));
                half4 e = inputShader.eval(c + float2(1.0, 0.0));
                half3 r = o.rgb * (1.0 + 4.0 * amount) - (n.rgb + s.rgb + w.rgb + e.rgb) * amount;
                return half4(clamp(r, 0.0, 1.0), o.a);
            }
        """

        fun sharpen(inner: RenderEffect, amount: Float): RenderEffect {
            val shader = RuntimeShader(SRC)
            shader.setFloatUniform("amount", amount)
            return RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "inputShader"), inner
            )
        }
    }
}
