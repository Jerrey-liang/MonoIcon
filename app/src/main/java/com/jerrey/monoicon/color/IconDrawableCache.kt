package com.jerrey.monoicon.color

import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory cache storing raw APK [Drawable] references
 * for monochrome mask generation.
 *
 * Populated at [com.miui.home.icon.IconProvider.getActivityIcon] hook time
 * (Phase 3.15), before HyperOS replaces the original colored
 * [android.graphics.drawable.AdaptiveIconDrawable] with a
 * [com.miui.home.common.drawable.LayerAdaptiveIconDrawable]
 * containing a monochrome mask.
 *
 * Consumed in [com.jerrey.monoicon.hook.IconThemeHook.processIconReplacement]
 * to bypass the pre-generated HyperOS mask and feed the original drawable
 * into [com.jerrey.monoicon.image.DrawableConverter.toBitmap].
 *
 * ## Key format
 * `"pkg/full.ClassName"` — matches [IconColorCache] and [resolveIdentity].
 *
 * ## Drawable lifecycle
 * Stored drawables are NOT cloned. They are references to the original
 * AdaptiveIconDrawable obtained from [android.content.pm.LauncherActivityInfo.getIcon].
 * The launcher retains ownership; we only reference them for rendering.
 */
object IconDrawableCache {

    private val store = ConcurrentHashMap<String, Drawable>()

    /** Number of cached entries. */
    val size: Int get() = store.size

    fun put(identity: String, drawable: Drawable) {
        store[identity] = drawable
    }

    fun get(identity: String): Drawable? = store[identity]

    fun remove(identity: String) {
        store.remove(identity)
    }

    fun clear() {
        store.clear()
    }
}
