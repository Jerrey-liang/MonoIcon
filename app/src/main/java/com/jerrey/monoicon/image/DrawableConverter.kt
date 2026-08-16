package com.jerrey.monoicon.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.util.Log

/**
 * Converts a [Drawable] into a monochrome silhouette [Bitmap].
 *
 * The output bitmap is **ARGB_8888**:
 * - `Alpha` derived from per-pixel luminance (dark → opaque, light → transparent)
 * - `RGB` filled black (0xFF000000)
 *
 * The `setIconDrawable` hook path (the actual Phase 2 entry point) has NO
 * launcher-applied black tint, so the mask must carry its own color.
 * A black silhouette on a transparent background is self-sufficient and
 * directly displayable by the launcher.
 *
 * ## Supported types (Phase 2.1 scope)
 * - [AdaptiveIconDrawable] — renders the **whole** drawable
 *   (background + foreground + mask). This is intentional: some apps put
 *   meaningful visuals in the background layer, so foreground-only is a
 *   runtime-verified fallback, not the initial choice.
 * - [BitmapDrawable] — copies the inner bitmap directly.
 * - [VectorDrawable] — renders via a [Canvas].
 *
 * Any other [Drawable] subtype returns `null` (caller falls back to the
 * original icon). Support will be extended gradually after the module
 * is confirmed working.
 */
object DrawableConverter {

    private const val TAG = "MonoIcon.Convert"

    // Phase 2.5 性能优化：复用绘制对象，避免每次分配 Canvas/Bitmap 位图分配
    private val reusableCanvas = Canvas()

    /**
     * Converts [drawable] into a monochrome silhouette [Bitmap], or returns
     * `null` if the drawable type is not (yet) supported or conversion fails.
     *
     * @param drawable The drawable to convert. Must not be recycled.
     * @return An ARGB_8888 black silhouette bitmap, or `null` on failure.
     */
    fun toBitmap(drawable: Drawable): Bitmap? {
        try {
            val rendered = when (drawable) {
                is BitmapDrawable -> copyBitmapDrawable(drawable)
                is VectorDrawable -> renderToBitmap(drawable)
                is AdaptiveIconDrawable ->
                    // Phase 3.10: 只渲染前景（logo），不含背景。
                    // 整图渲染会包含不透明背景，导致亮度掩码找不到形状 → 全不透明。
                    // 前景提取避免背景主导亮度掩码，产生正确剪影。
                    drawable.foreground?.let { renderToBitmap(it) }
                        ?: renderToBitmap(drawable)
                else -> {
                    Log.d(TAG, "Unsupported drawable type: ${drawable.javaClass.simpleName}")
                    null
                }
            } ?: return null

            // Phase 2.3b: 用亮度掩码（替代纯 alpha，避免不透明图标退化为实心方块）
            return toLuminanceMask(rendered)
        } catch (e: Throwable) {
            Log.e(TAG, "toBitmap failed for ${drawable.javaClass.simpleName}: ${e.message}", e)
            return null
        }
    }

    /**
     * Renders [drawable] to a raw ARGB_8888 [Bitmap] **without** applying
     * [toLuminanceMask] or any other mask generation.
     *
     * Used for native monochrome layers and foreground extractions that
     * are already in the correct visual form or will be processed by a
     * different mask strategy.
     *
     * @param drawable The drawable to render.
     * @return A raw ARGB_8888 bitmap, or `null` on failure.
     */
    fun toRawBitmap(drawable: Drawable): Bitmap? {
        try {
            return when (drawable) {
                is BitmapDrawable -> copyBitmapDrawable(drawable)
                is VectorDrawable -> renderToBitmap(drawable)
                is AdaptiveIconDrawable -> renderToBitmap(drawable)
                else -> {
                    Log.d(TAG, "toRawBitmap unsupported type: ${drawable.javaClass.simpleName}")
                    null
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "toRawBitmap failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Retrieves the native monochrome layer from an [AdaptiveIconDrawable]
     * via reflection.
     *
     * [AdaptiveIconDrawable.getMonochrome] is a hidden API (added in API 33).
     * On API < 33 or if reflection fails, returns `null` silently.
     *
     * @param drawable The adaptive icon to query.
     * @return The monochrome [Drawable], or `null` if unavailable.
     */
    fun getMonochromeLayer(drawable: AdaptiveIconDrawable): Drawable? {
        if (android.os.Build.VERSION.SDK_INT < 33) return null
        return try {
            val method = AdaptiveIconDrawable::class.java.getDeclaredMethod("getMonochrome")
            method.isAccessible = true
            method.invoke(drawable) as? Drawable
        } catch (t: Throwable) {
            Log.d(TAG, "getMonochromeLayer failed (no-op): ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * Normalizes a native monochrome bitmap into a pure alpha mask.
     *
     * Two encodings exist for native monochrome layers:
     *
     * 1. **Pixel-style (shape in alpha)** — the glyph is drawn opaque
     *    (often in BLACK) on a transparent background. Pixel Launcher draws
     *    this straight into an ALPHA_8 bitmap: the alpha channel IS the
     *    mask. Converting via luminance would collapse a black glyph to
     *    alpha 0 → invisible icons (e.g. Google Search / KernelSU folder
     *    previews under PixelMonochromeMaskStrategy).
     *
     * 2. **RGB-encoded shape** — the layer is a flat opaque image whose
     *    silhouette lives in the RGB channels (alpha is uniform). Here
     *    luminance must be mapped to alpha.
     *
     * This method detects which encoding is used: when the alpha channel
     * varies meaningfully it is kept as the mask (Pixel behavior); only
     * when alpha is (nearly) uniform is the luminance mapped to alpha.
     *
     * @param bitmap Raw bitmap rendered from a native monochrome Drawable.
     * @return A new ARGB_8888 alpha-only mask bitmap.
     */
    fun normalizeNativeMonochrome(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Pass 1: decide whether the alpha channel carries the shape.
        var minA = 255
        var maxA = 0
        for (i in pixels.indices) {
            val a = (pixels[i] ushr 24) and 0xFF
            if (a < minA) minA = a
            if (a > maxA) maxA = a
        }
        val alphaCarriesShape = maxA - minA >= 16

        // Pass 2: build the alpha-only mask.
        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color ushr 24) and 0xFF
            if (a == 0) {
                pixels[i] = 0x00000000
                continue
            }
            val newAlpha = if (alphaCarriesShape) {
                a // Pixel-style: the drawable's own alpha is the mask
            } else {
                val r = (color ushr 16) and 0xFF
                val g = (color ushr 8) and 0xFF
                val b = color and 0xFF
                val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                (a * y) / 255
            }
            pixels[i] = (newAlpha shl 24) or 0x00000000
        }

        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask.setPixels(pixels, 0, w, 0, 0, w, h)
        return mask
    }

    // ── Per-type converters ─────────────────────────────────────────

    /**
     * Copies the inner bitmap of a [BitmapDrawable].
     *
     * Returns a mutable ARGB_8888 copy so later stages never mutate the
     * drawable's own bitmap. If the inner bitmap is null, returns null.
     */
    private fun copyBitmapDrawable(drawable: BitmapDrawable): Bitmap? {
        val inner = drawable.bitmap ?: return null
        // Copying into a fresh ARGB_8888 bitmap is safe even if `inner` is
        // immutable or shared with other drawables.
        val copy = Bitmap.createBitmap(inner.width, inner.height, Bitmap.Config.ARGB_8888)
        synchronized(reusableCanvas) {
            reusableCanvas.setBitmap(copy)
            reusableCanvas.drawBitmap(inner, 0f, 0f, null)
            reusableCanvas.setBitmap(null)
        }
        return copy
    }

    /**
     * Renders [drawable] onto a fresh ARGB_8888 bitmap via a [Canvas].
     *
     * The drawable is laid out to its full intrinsic bounds and drawn.
     * Alpha is naturally preserved by the draw operation.
     */
    private fun renderToBitmap(drawable: Drawable): Bitmap {
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        synchronized(reusableCanvas) {
            reusableCanvas.setBitmap(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(reusableCanvas)
            reusableCanvas.setBitmap(null)
        }

        return bitmap
    }

    /**
     * Derives a monochrome silhouette mask from [bitmap] using **luminance**.
     *
     * This is the Phase 2.3b strategy (user-approved, replacing the pure-alpha
     * approach which degenerated to a solid white square on opaque icons):
     *
     * - **Alpha** is derived from per-pixel luminance:
     *   - dark pixels (low luminance) → opaque (foreground shape kept)
     *   - light pixels (high luminance) → transparent (background dropped)
     * - **RGB** is filled black (0xFF000000), because the `setIconDrawable`
     *   hook path has NO launcher-applied black tint (unlike the
     *   `getMonochrome` path). The mask must therefore be self-sufficient.
     * - Pixels that were fully transparent in the source stay transparent.
     *
     * This is NOT a grayscale output — the result is a black silhouette whose
     * alpha defines the recognizable icon shape. It is a runtime verification
     * point: if some icons are inverted (dark background + light glyph), the
     * luminance direction may need adjustment per-icon.
     *
     * @param bitmap Input ARGB_8888 bitmap (not recycled).
     * @return A new ARGB_8888 black silhouette mask, alpha from luminance.
     */
    /**
     * Derives a monochrome silhouette mask from [bitmap] using **background-estimation
     * polarity detection** (Phase 2.8).
     *
     * Instead of a fixed `alpha = 255 - luminance` direction, this method:
     * 1. estimates the dominant background luminance range (3-bin histogram)
     * 2. checks whether non-background details are darker or lighter than that background
     * 3. picks the appropriate alpha direction per-pixel
     *
     * This prevents "white logo on black background" icons from inverting into
     * a solid black square.
     *
     * ## Fallback safeguards
     * - No dominant background (ratio < 35%, dominance < 1.25) → current DARK_FG behavior
     * - Too few foreground pixels (< 8%) → FALLBACK
     * - Polarity ambiguous (< 60% direction dominance) → FALLBACK
     *
     * @param bitmap Input ARGB_8888 bitmap (not recycled).
     * @return A new ARGB_8888 black silhouette mask.
     */
    fun toLuminanceMask(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // ═══════════════════════════════════════════════════════════════
        // Pass 1: background estimation (3-bin luminance histogram)
        // ═══════════════════════════════════════════════════════════════
        var darkCount = 0
        var midCount = 0
        var lightCount = 0
        var midLuminanceSum = 0.0

        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color ushr 24) and 0xFF
            if (a <= 32) continue

            val y = luminance(color)
            when {
                y <= 85  -> { darkCount++ }
                y <= 170 -> { midCount++; midLuminanceSum += y }
                else     -> { lightCount++ }
            }
        }

        val totalCount = darkCount + midCount + lightCount
        if (totalCount == 0) {
            // 完全透明 → 返回全透明 bitmap
            val empty = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            empty.eraseColor(0x00000000)
            return empty
        }

        // 确定背景箱
        val maxBin = maxOf(darkCount, midCount, lightCount)
        val backgroundBin = when (maxBin) {
            darkCount  -> BG_DARK
            lightCount -> BG_LIGHT
            else       -> BG_MID
        }
        val backgroundCount = maxBin
        val secondLargestCount = when (backgroundBin) {
            BG_DARK  -> maxOf(midCount, lightCount)
            BG_MID   -> maxOf(darkCount, lightCount)
            BG_LIGHT -> maxOf(darkCount, midCount)
            else     -> 1 // unreachable, satisfies exhaustiveness
        }
        val backgroundRatio = backgroundCount.toDouble() / totalCount.toDouble()
        val dominanceRatio = backgroundCount.toDouble() / maxOf(secondLargestCount, 1).toDouble()
        val midMean = if (midCount > 0) midLuminanceSum / midCount else 128.0

        // Fallback: 无主导背景
        val decision = if (backgroundRatio < 0.35 || dominanceRatio < 1.25) {
            FALLBACK
        } else {
            null // 待 Pass 2 确定
        }

        // ═══════════════════════════════════════════════════════════════
        // Pass 2: foreground polarity estimation
        // ═══════════════════════════════════════════════════════════════
        var darkerThanBg = 0
        var lighterThanBg = 0

        // 前景比较基准
        val foregroundReference = when {
            decision != null           -> 128.0 // 已 fallback，不会用到
            backgroundBin == BG_LIGHT -> 170.0
            backgroundBin == BG_DARK  -> 85.0
            else                       -> midMean // BG_MID
        }

        val midTolerance = 32.0

        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color ushr 24) and 0xFF
            if (a <= 32) continue

            val y = luminance(color)

            // 跳过背景像素
            val isBg = when (backgroundBin) {
                BG_DARK  -> y <= 85
                BG_LIGHT -> y > 170
                else     -> Math.abs(y - midMean) <= midTolerance
            }
            if (isBg) continue

            // 分类
            if (y < foregroundReference) darkerThanBg++ else lighterThanBg++
        }

        val finalDecision = if (decision != null) {
            decision
        } else {
            val foregroundCount = darkerThanBg + lighterThanBg
            val foregroundRatio = foregroundCount.toDouble() / totalCount.toDouble()
            if (foregroundRatio < 0.08) {
                FALLBACK
            } else {
                val darkerRatio = darkerThanBg.toDouble() / foregroundCount.toDouble()
                val lighterRatio = lighterThanBg.toDouble() / foregroundCount.toDouble()
                if (darkerRatio >= 0.6) DARK_FG
                else if (lighterRatio >= 0.6) LIGHT_FG
                else FALLBACK
            }
        }

        // Temporary debug log (Phase 2.8, remove after verification)
        val bgStr = when (backgroundBin) {
            BG_DARK -> "DARK"; BG_MID -> "MID"; BG_LIGHT -> "LIGHT"; else -> "?"
        }
        val decisionStr = when (finalDecision) {
            DARK_FG -> "DARK_FG"; LIGHT_FG -> "LIGHT_FG"; FALLBACK -> "FALLBACK"; else -> "?"
        }
        Log.i(TAG, "[toLuminanceMask] bg=$bgStr\tratio=${"%.2f".format(backgroundRatio)}\tdom=${"%.1f".format(dominanceRatio)}\t→ $decisionStr")

        // ═══════════════════════════════════════════════════════════════
        // Pass 3: alpha mask generation
        // ═══════════════════════════════════════════════════════════════
        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color ushr 24) and 0xFF
            if (a == 0) {
                pixels[i] = 0x00000000
                continue
            }

            val y = luminance(color)
            val alpha = if (finalDecision == LIGHT_FG) y else 255 - y
            val finalAlpha = (alpha * a) / 255
            pixels[i] = (finalAlpha shl 24) or 0x00000000
        }

        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }

    /** Rec.601 luminance from an ARGB pixel. */
    private fun luminance(color: Int): Int {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    // ── Decision constants ──────────────────────────────────────────
    private const val DARK_FG = 0
    private const val LIGHT_FG = 1
    private const val FALLBACK = 3

    // ── Background bin constants ─────────────────────────────────────
    private const val BG_DARK = 10
    private const val BG_MID = 11
    private const val BG_LIGHT = 12
}
