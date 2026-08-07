package com.jerrey.monoicon.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.logging.logd

/**
 * Unified monochrome mask generation pipeline (Phase 3.18-C).
 *
 * All mask-producing hooks (5: ShortcutIcon.setIconDrawable, 6/8:
 * folder preview refreshIconDrawable) route through [generate], replacing
 * the three ad-hoc mask code paths that previously existed in IconThemeHook.
 *
 * ## Source priority (preserved from Phase 3.17)
 * - [Priority.DESKTOP]: NATIVE > RAW_APK > FOREGROUND > LUMINANCE
 *   (Hook 5 — unchanged order and branch bodies)
 * - [Priority.FOLDER]: RAW_APK > FOREGROUND > LUMINANCE
 *   (Hooks 6/8 — RAW_FIRST, confirmed decision: folder previews never
 *   consult the native monochrome layer, preserving P3.17 folder results)
 *
 * ## Caching (preserved key format)
 * Key: `identity|WxH@fingerprint|source` (built by [MonochromeCache.buildKey]).
 * The fingerprint is computed over the *source render* — the full-color
 * render that drives generation (input of toLuminanceMask for NATIVE/RAW
 * branches; the generated mask itself for FG/LUMA branches where no
 * intermediate render exists). This makes the lookup meaningful: a refresh
 * of the same icon hits without re-running the pipeline, while an icon
 * visual change (theme switch, package update) changes the render →
 * different fingerprint → miss → regenerate. No staleness.
 *
 * When [identity] is null (folder fallback path), the mask is still
 * generated and returned but NOT cached — preserving Phase 3.17 folder
 * replacement behavior for null-identity icons.
 *
 * All per-call logging uses [logd] (off by default, Phase 3.18-A).
 */
object MaskGenerator {

    private const val TAG = "MonoIcon.Hook"
    private const val TAG_MASK = "MonoIcon.Mask"

    // ── Source constants (values synced with IconThemeHook / MonochromeCache) ──
    const val SOURCE_NATIVE = 1
    const val SOURCE_FOREGROUND = 2
    const val SOURCE_LUMINANCE = 3

    enum class Priority {
        /** Hook 5 desktop icons: NATIVE > RAW_APK > FOREGROUND > LUMINANCE. */
        DESKTOP,

        /** Hooks 6/8 folder previews: RAW_APK > FOREGROUND > LUMINANCE. */
        FOLDER,
    }

    /** Result of [generate]; [mask] is null on failure (caller passes through). */
    data class GenerateResult(
        val mask: Bitmap?,
        val source: Int,
        val rawUsed: Boolean,
        val cacheHit: Boolean,
    )

    // Phase 3.18-D: byte-based LRU (default = maxMemory/16)
    private val monochromeCache = MonochromeCache()

    /**
     * Generates (or retrieves from cache) the monochrome mask for [d].
     *
     * @param d The drawable to convert (Hook 5: launcher drawable;
     *          Hooks 6/8: drawable argument, raw APK drawable after Hook 9/10).
     * @param identity Resolved `"pkg/cls"` identity; null → generate without cache.
     * @param priority Source priority chain (see class doc).
     */
    fun generate(d: Drawable, identity: String?, priority: Priority): GenerateResult {
        var source = SOURCE_LUMINANCE
        var rawUsed = false
        // Fingerprint source: input render for NATIVE/RAW branches, mask otherwise
        var keyBitmap: Bitmap? = null
        var mask: Bitmap? = null

        when (priority) {
            Priority.DESKTOP -> {
                if (d is AdaptiveIconDrawable) {
                    // ① Native monochrome layer (API 33+, 反射 hidden API)
                    val mono = DrawableConverter.getMonochromeLayer(d)
                    if (mono != null) {
                        source = SOURCE_NATIVE
                        keyBitmap = DrawableConverter.toRawBitmap(mono)
                        mask = keyBitmap?.let { DrawableConverter.normalizeNativeMonochrome(it) }
                        logd(TAG, "[toBitmap] source=NATIVE")
                    } else {
                        // Phase 3.16-A: 优先使用缓存的 Raw APK Drawable
                        // 避免 HyperOS LayerAdaptiveIconDrawable 前景中已生成的 mask
                        val rawCached = if (identity != null) IconDrawableCache.get(identity) else null
                        if (rawCached != null) {
                            // 渲染完整 AdaptiveIcon（background + foreground）
                            // toBitmap() 仅取 foreground → 纯白剪影无色彩对比度 → toLuminanceMask 全透明
                            // 完整渲染保留 background 色彩，提供 toLuminanceMask 所需的对比度
                            source = SOURCE_FOREGROUND
                            rawUsed = true
                            keyBitmap = DrawableConverter.toRawBitmap(rawCached)
                            mask = keyBitmap?.let { DrawableConverter.toLuminanceMask(it) }
                            logd(TAG_MASK, "[MaskSource] component=$identity source=RAW_APK_DRAWABLE drawable=${rawCached.javaClass.simpleName}")
                        } else {
                            // ② Foreground extraction (Phase 3.1)
                            val fg = d.foreground
                            if (fg != null) {
                                val srcBounds = d.bounds
                                val w = if (srcBounds.width() > 0) srcBounds.width()
                                        else d.intrinsicWidth.coerceAtLeast(1)
                                val h = if (srcBounds.height() > 0) srcBounds.height()
                                        else d.intrinsicHeight.coerceAtLeast(1)
                                fg.setBounds(0, 0, w, h)
                                source = SOURCE_FOREGROUND
                                mask = DrawableConverter.toBitmap(fg)
                                keyBitmap = mask
                                logd(TAG_MASK, "[MaskSource] component=$identity source=FOREGROUND reason=cache_miss")
                            } else {
                                // ③ Fallback to whole drawable
                                source = SOURCE_LUMINANCE
                                mask = DrawableConverter.toBitmap(d)
                                keyBitmap = mask
                                logd(TAG_MASK, "[MaskSource] component=$identity source=LUMA reason=no_foreground")
                            }
                        }
                    }
                } else {
                    source = SOURCE_LUMINANCE
                    mask = DrawableConverter.toBitmap(d)
                    keyBitmap = mask
                    logd(TAG, "[toBitmap] source=LUMINANCE")
                }
            }

            Priority.FOLDER -> {
                // Phase 3.16-A: raw APK drawable → 完整渲染 + luminance mask
                val rawCached = if (identity != null) IconDrawableCache.get(identity) else null
                if (rawCached != null) {
                    source = SOURCE_FOREGROUND
                    rawUsed = true
                    keyBitmap = DrawableConverter.toRawBitmap(rawCached)
                    mask = keyBitmap?.let { DrawableConverter.toLuminanceMask(it) }
                } else {
                    // 回退：toBitmap → 不支持的类型用 Canvas 渲染
                    source = SOURCE_LUMINANCE
                    mask = DrawableConverter.toBitmap(d) ?: renderGenericToMask(d)
                    keyBitmap = mask
                }
            }
        }

        if (mask == null) return GenerateResult(null, source, rawUsed, cacheHit = false)

        // Cache lookup — fingerprint over the source render (or the mask itself)
        val cacheKey = monochromeCache.buildKey(identity, keyBitmap ?: mask, source)
        if (cacheKey != null) {
            val cached = monochromeCache.get(cacheKey)
            if (cached != null) {
                return GenerateResult(cached, source, rawUsed, cacheHit = true)
            }
            monochromeCache.put(cacheKey, mask)
        }
        return GenerateResult(mask, source, rawUsed, cacheHit = false)
    }

    /**
     * 通用回退：对 [DrawableConverter.toBitmap] 不支持的类型，
     * 直接 Canvas 渲染 + toLuminanceMask 生成 monochrome mask。
     */
    private fun renderGenericToMask(drawable: Drawable): Bitmap? {
        return try {
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            DrawableConverter.toLuminanceMask(bmp)
        } catch (_: Throwable) { null }
    }
}
