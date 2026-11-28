package com.jerrey.monoicon.hook

import android.content.Context
import com.jerrey.monoicon.theme.archive.LargeIconPatch
import com.jerrey.monoicon.theme.archive.ThemeArchiveInstaller
import com.jerrey.monoicon.theme.archive.ThemeArchiveResult
import java.io.File

internal object HyperOs4ThemeLargeIcons {
    fun apply(context: Context, work: File, isCurrent: () -> Boolean): ThemeArchiveResult? {
        val catalog = ThemeArchiveInstaller.largeIconCatalog(context)
        val settings = lazy { HyperOs4RenderSettings.current(context) }
        val pm = context.packageManager
        val images = mutableMapOf<String, File>()
        for ((pkg, assets) in catalog.groupBy { it.pkg }) {
            if (!isCurrent()) return null
            val main = pm.getLaunchIntentForPackage(pkg)?.component?.className
            val activities = launcherActivities(pm, pkg).sortedByDescending { it.name == main }
            val app = activities.firstOrNull()?.applicationInfo ?: continue
            val size = assets.maxOf { minOf(it.width, it.height) }
            val drawable = HyperOs4ApkIcons(context, app, settings.value.configuration).use { resources ->
                activities.firstNotNullOfOrNull { settings.value.drawable(resources, it, size) }
            } ?: continue
            for ((sizePx, variants) in assets.groupBy { it.width to it.height }) {
                if (!isCurrent()) return null
                val (width, height) = sizePx
                val image = File(work, "$pkg-$width-$height.png")
                drawable.forLargeIcon(width, height).writeHyperOsPng(image, width, height)
                variants.forEach { images[it.path] = image }
            }
        }
        if (!isCurrent()) return null
        val patch = File(work, "large-images.bin")
        LargeIconPatch.write(patch, catalog, images)
        if (!isCurrent()) return null
        return ThemeArchiveInstaller.applyLargeIcons(context, patch)
    }
}
