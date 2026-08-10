package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.mask.MaskGenerator

/**
 * Desktop mask strategy (Phase 5 — verbatim copy of Phase 4
 * MaskGenerator DESKTOP branch).
 *
 * Source priority: NATIVE > RAW_APK > FOREGROUND > LUMINANCE
 *
 * Each branch body is the unmodified Phase 4 code from
 * [MaskGenerator.generate], preserving the exact mask-generation
 * behavior verified in Phase 3.17.
 */
class NativeFirstMaskStrategy : MaskStrategy {

    private val TAG = "MonoIcon.Hook"
    private val TAG_MASK = "MonoIcon.Mask"

    private var themeId: String = ""
    private var contextStr: String = ""

    override fun configureCache(themeId: String, context: String) {
        this.themeId = themeId
        this.contextStr = context
    }

    override fun generate(d: Drawable, identity: String?): MaskGenerator.GenerateResult? {
        var source = MaskStrategy.SOURCE_LUMINANCE
        var rawUsed = false
        var keyBitmap: Bitmap? = null
        var mask: Bitmap? = null

        if (d is AdaptiveIconDrawable) {
            // ① Native monochrome layer (API 33+, 反射 hidden API)
            val mono = DrawableConverter.getMonochromeLayer(d)
            if (mono != null) {
                source = MaskStrategy.SOURCE_NATIVE
                keyBitmap = DrawableConverter.toRawBitmap(mono)
                mask = keyBitmap?.let { DrawableConverter.normalizeNativeMonochrome(it) }
                logd(TAG, "[toBitmap] source=NATIVE")
            } else {
                // Phase 3.16-A: 优先使用缓存的 Raw APK Drawable
                // 避免 HyperOS LayerAdaptiveIconDrawable 前景中已生成的 mask
                val rawCached = if (identity != null) IconDrawableCache.get(identity) else null
                if (rawCached != null) {
                    source = MaskStrategy.SOURCE_FOREGROUND
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
                        source = MaskStrategy.SOURCE_FOREGROUND
                        mask = DrawableConverter.toBitmap(fg)
                        keyBitmap = mask
                        logd(TAG_MASK, "[MaskSource] component=$identity source=FOREGROUND reason=cache_miss")
                    } else {
                        // ③ Fallback to whole drawable
                        source = MaskStrategy.SOURCE_LUMINANCE
                        mask = DrawableConverter.toBitmap(d)
                        keyBitmap = mask
                        logd(TAG_MASK, "[MaskSource] component=$identity source=LUMA reason=no_foreground")
                    }
                }
            }
        } else {
            source = MaskStrategy.SOURCE_LUMINANCE
            mask = DrawableConverter.toBitmap(d)
            keyBitmap = mask
            logd(TAG, "[toBitmap] source=LUMINANCE")
        }

        if (mask == null) return null

        // Phase 5: cache key with themeId|context| prefix
        val cacheKey = MonochromeCache.shared.buildKey(
            themeId, contextStr, identity, keyBitmap ?: mask, source)
        if (cacheKey != null) {
            val cached = MonochromeCache.shared.get(cacheKey)
            if (cached != null) {
                return MaskGenerator.GenerateResult(cached, source, rawUsed, cacheHit = true)
            }
            MonochromeCache.shared.put(cacheKey, mask)
        }
        return MaskGenerator.GenerateResult(mask, source, rawUsed, cacheHit = false)
    }
}
