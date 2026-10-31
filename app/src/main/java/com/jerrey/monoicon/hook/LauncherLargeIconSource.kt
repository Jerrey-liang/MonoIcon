package com.jerrey.monoicon.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.theme.render.LauncherIconRenderer
import com.jerrey.monoicon.theme.render.LauncherIconRequest

/** Raw APK capture shared with tests; does not load the Xposed hook classes. */
internal object LauncherLargeIconSource {
    /**
     * BigIconUtils loads MAML/theme assets directly, bypassing getActivityIcon.
     * Resolve the exact component in its actual profile, then read its APK
     * resource without invoking an OEM-themed icon getter. The capture cache
     * includes user, density and configuration; re-publish it on every bind
     * because IconDrawableCache is a smaller, component-keyed LRU.
     */
    fun capture(
        context: Context,
        request: LauncherIconRequest,
        onCaptured: (LauncherActivityInfo, Drawable) -> Unit = { _, _ -> },
    ): Drawable? = try {
        val source = LauncherIconRenderer.capture(request) load@{
            val component = ComponentName.unflattenFromString(request.identity)
                ?: return@load null
            val launcherApps = context.getSystemService(LauncherApps::class.java)
                ?: return@load null
            val info = launcherApps.resolveActivity(Intent().setComponent(component), request.user)
                ?: return@load null
            if (info.componentName != component || info.user != request.user) return@load null
            val activity = info.activityInfo
            val iconResource = activity.iconResource
            if (iconResource == 0) return@load null
            val resources = context.packageManager.getResourcesForApplication(activity.applicationInfo)
            val raw = resources.getDrawableForDensity(iconResource, request.densityDpi, null)
                ?: return@load null
            val isolated = raw.constantState?.newDrawable(resources)?.mutate() ?: raw.mutate()
            onCaptured(info, isolated)
            isolated
        }
        if (source != null) IconDrawableCache.put(request.identity, source)
        source
    } catch (_: Throwable) {
        // A missing/locked profile never falls back to the owner's app icon.
        null
    }
}
