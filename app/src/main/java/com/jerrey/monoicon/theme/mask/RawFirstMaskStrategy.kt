package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.mask.MaskGenerator

/**
 * Folder preview mask strategy (Phase 5 — verbatim copy of Phase 4
 * MaskGenerator FOLDER branch).
 *
 * Source priority: RAW_APK > FOREGROUND > LUMINANCE
 *
 * Native monochrome layer is intentionally skipped for folder previews
 * (Phase 3.17 confirmed: NativeFirst on folders changes rendering for
 * apps that ship a native monochrome layer).
 */
class RawFirstMaskStrategy : MaskStrategy {

    private lateinit var themeId: String
    private lateinit var contextStr: String

    override fun configureCache(themeId: String, context: String) {
        this.themeId = themeId
        this.contextStr = context
    }

    override fun generate(d: Drawable, identity: String?): MaskGenerator.GenerateResult? {
        var source = MaskStrategy.SOURCE_LUMINANCE
        var rawUsed = false
        var keyBitmap: Bitmap? = null
        var mask: Bitmap? = null

        // Phase 3.16-A: raw APK drawable → 完整渲染 + luminance mask
        val rawCached = if (identity != null) IconDrawableCache.get(identity) else null
        if (rawCached != null) {
            source = MaskStrategy.SOURCE_FOREGROUND
            rawUsed = true
            keyBitmap = DrawableConverter.toRawBitmap(rawCached)
            mask = keyBitmap?.let { DrawableConverter.toLuminanceMask(it) }
        } else {
            // 回退：toBitmap → 不支持的类型用 Canvas 渲染
            source = MaskStrategy.SOURCE_LUMINANCE
            mask = DrawableConverter.toBitmap(d) ?: MaskStrategy.renderGenericToMask(d)
            keyBitmap = mask
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
