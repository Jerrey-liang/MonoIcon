package com.jerrey.monoicon.hook

import android.content.Context
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.profile.ProfileRepository
import java.io.File
import kotlin.math.roundToInt

/** Optional Profile-backed MAML large icons. Ordinary icons are installed in the system theme ZIP. */
internal object HyperOs4IconExporter {
    private const val SIZE = 512
    private const val MANIFEST_LIMIT = 512 * 1024
    data class Result(val components: Int, val files: Int)

    fun exportLargeIcons(context: Context, directory: File, largeIcons: HyperOs4LargeIconInputs,
                         isCurrent: () -> Boolean): Result? {
        check(directory.mkdirs() || directory.isDirectory)
        val pm = context.packageManager
        val settings = lazy { HyperOs4RenderSettings.current(context) }
        val geometry = largeIcons.geometry
        val packages = largeIcons.icons.groupBy { it.packageName }
        val manifest = StringBuilder()
        var rendered = 0
        var files = 0
        if (geometry != null) for ((pkg, icons) in packages) {
            if (!isCurrent()) return null
            val main = pm.getLaunchIntentForPackage(pkg)?.component?.className
            val activities = launcherActivities(pm, pkg).sortedByDescending { it.name == main }
            val app = activities.firstOrNull()?.applicationInfo ?: continue
            val drawable = try {
                HyperOs4ApkIcons(context, app, settings.value.configuration).use { resources ->
                    activities.firstNotNullOfOrNull { settings.value.drawable(resources, it, SIZE) }
                }
            } catch (failure: Exception) {
                logw("MonoIcon.HyperOS4", "Could not render large icon for $pkg: ${failure.message}")
                null
            } ?: continue
            val before = files
            for (icon in icons) {
                for ((spanX, spanY) in listOf(2 to 1, 1 to 2, 2 to 2)) {
                    if (!isCurrent()) return null
                    val size = geometry.dimensions(spanX, spanY) ?: continue
                    val scale = SIZE / minOf(size.width, size.height)
                    val width = (size.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (size.height * scale).roundToInt().coerceAtLeast(1)
                    if (maxOf(width, height) > 2048) continue
                    val source = "${icon.path}/${spanX}x$spanY"
                    val target = ".large/" + ProfileRepository.sha256(source.toByteArray())
                    val row = "$source\t$target\t$pkg.png\n"
                    if (manifest.length + row.length > MANIFEST_LIMIT) continue
                    val folder = File(directory, "$target/res/drawable")
                    check(folder.mkdirs() || folder.isDirectory)
                    drawable.forLargeIcon(width, height).writeHyperOsPng(File(folder, "$pkg.png"), width, height)
                    manifest.append(row)
                    files++
                }
            }
            if (files != before) rendered++
        }
        File(directory, ".packages").writeText(packages.keys.sorted().joinToString("") { "$it\n" })
        if (manifest.isNotEmpty()) File(directory, ".large-icons").writeText(manifest.toString())
        File(directory, ".large-signature").writeText(largeIcons.signature)
        return Result(rendered, files).takeIf { isCurrent() }
    }
}
