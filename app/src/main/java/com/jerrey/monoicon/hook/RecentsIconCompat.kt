package com.jerrey.monoicon.hook

import android.content.ComponentName
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.color.IconDrawableCache

/**
 * Recents-icon helpers shared by the Phase 10 hook (Hook 11).
 *
 * HyperOS recents loads per-task icons through
 * `com.android.systemui.shared.recents.model.IconLoader`, which lives in the
 * launcher APK's dex — so `Task$TaskKey` is not on our compile class path and
 * its accessors are reflected here.
 *
 * Extracted from `IconThemeHook` so both functions stay unit-testable (the
 * real `Task$TaskKey` and `IconLoader` do not exist in the module's test
 * process; the tests use stubs with the same accessor names).
 */
internal object RecentsIconCompat {

    /**
     * `"pkg/cls"` identity for a recents task key, falling back to the bare
     * package name. Returns null when nothing usable is available — the caller
     * then keeps MIUI's original icon.
     *
     * Mirrors HyperOS `Task.TaskKey`: `getTopComponentOrBaseComponent()` →
     * `getComponent()` → `getPackageName()`.
     */
    fun identity(taskKey: Any?): String? {
        if (taskKey == null) return null
        return try {
            val cls = taskKey.javaClass
            val component = (cls.getMethod("getTopComponentOrBaseComponent").invoke(taskKey) as? ComponentName)
                ?: (cls.getMethod("getComponent").invoke(taskKey) as? ComponentName)
            val className = component?.className
            if (component != null && !className.isNullOrEmpty()) {
                "${component.packageName}/$className"
            } else {
                (cls.getMethod("getPackageName").invoke(taskKey) as? String)?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Mask source for a recents icon: the raw APK drawable captured by the
     * RawIconProvider hook — first by exact identity, then package-level (a
     * task's top component is frequently not the launcher activity that was
     * cached) — and finally the drawable MIUI composed.
     */
    fun sourceDrawable(identity: String?, fallback: Drawable): Drawable {
        if (identity != null) {
            IconDrawableCache.get(identity)?.let { return it }
            val pkg = identity.substringBefore('/')
            if (pkg.isNotEmpty()) {
                IconDrawableCache.getByPackage(pkg)?.let { return it }
            }
        }
        return fallback
    }
}
