package com.jerrey.monoicon.hook

import com.jerrey.monoicon.profile.CompatibilityProfile
import com.jerrey.monoicon.profile.ProfileRepository
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.theme.mask.LawniconsAssetSource
import com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy
import com.jerrey.monoicon.theme.render.ThemedIconBuilder
import java.io.File

/** Render outside the Dart UI isolate, using the same mask/color/shape pipeline as Java hooks. */
internal object HyperOs4IconExporter {
    // Keep enough detail for desktop zoom and 2x2 icons. Rendering a larger
    // drawable from a 192px export cannot recover the source glyph's edges.
    private const val SIZE = 512
    private const val TAG = "MonoIcon.HyperOS4"
    data class Result(val components: Int, val files: Int)

    fun export(context: Context, directory: File, profile: CompatibilityProfile,
               largeIcons: HyperOs4LargeIconInputs = HyperOs4LargeIconInputs.EMPTY,
               isCurrent: () -> Boolean): Result? {
        check(directory.mkdirs() || directory.isDirectory)
        LawniconsAssetSource.init(context)
        val pm = context.packageManager
        val activities = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(0),
        ).mapNotNull { it.activityInfo }.distinctBy { it.packageName to it.name }
            .sortedWith(compareBy({ it.packageName }, { it.name }))
        var rendered = 0
        var files = 0
        val packageFiles = mutableSetOf<String>()
        val largePaths = mutableMapOf<String, String>()
        var largeManifestBytes = 0
        for (activity in activities) {
            if (!isCurrent()) return null
            val pkg = activity.packageName
            val cls = activity.name.let { if (it.startsWith('.')) pkg + it else it }
            val identity = "$pkg/$cls"
            try {
                // Load the APK resource directly; PackageManager.getActivityIcon can
                // return an OEM-themed drawable with the original layers already lost.
                val resources = pm.getResourcesForApplication(activity.applicationInfo)
                val iconId = activity.iconResource
                if (iconId == 0) continue
                val source = resources.getDrawable(iconId, null).mutate()
                val result = ThemedIconBuilder.build(source, identity) ?: continue
                if (!AospMonochromeMaskStrategy.hasUsableGlyph(result.mask)) {
                    logw(TAG, "no usable glyph for $identity; retaining launcher icon")
                    continue
                }
                val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                try {
                    result.drawable.setBounds(0, 0, SIZE, SIZE)
                    result.drawable.draw(Canvas(bitmap))
                    val names = profile.icons.componentNames(pkg, cls).toMutableList()
                    if (names.isEmpty()) continue
                    // Prefer the package's primary launch activity for its fallback.
                    val main = pm.getLaunchIntentForPackage(pkg)?.component?.className
                    if ("package" in profile.icons.names && (pkg !in packageFiles || cls == main)) {
                        names += "$pkg.png"
                        packageFiles += pkg
                    }
                    names += names.mapNotNull(profile.icons.aliases::get)
                    val primary = File(directory, names.first())
                    primary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    files++
                    for (name in names.drop(1).distinct()) {
                        val alias = File(directory, name)
                        // A generic activity name can be shared by unrelated packages.
                        // The package-qualified filename always precedes that fallback.
                        if (name == "$cls.png" && alias.exists() && !cls.startsWith(pkg)) continue
                        primary.copyTo(alias, overwrite = true)
                        files++
                    }
                    val geometry = largeIcons.geometry
                    if (geometry != null && (cls == main || main == null)) {
                        for (icon in largeIcons.icons.filter { it.packageName == pkg }) {
                            for ((spanX, spanY) in listOf(2 to 1, 1 to 2, 2 to 2)) {
                                if (!isCurrent()) return null
                                val size = geometry.dimensions(spanX, spanY) ?: continue
                                // A common scale preserves the measured aspect, including cell gaps.
                                val scale = SIZE / minOf(size.width, size.height)
                                val width = kotlin.math.round(size.width * scale).toInt().coerceAtLeast(1)
                                val height = kotlin.math.round(size.height * scale).toInt().coerceAtLeast(1)
                                if (maxOf(width, height) > 2048) continue
                                val source = "${icon.path}/${spanX}x$spanY"
                                val target = ".large/" + ProfileRepository.sha256(source.toByteArray())
                                val mapping = "$source\t$target\t$pkg.png\n"
                                val previousBytes = largePaths[source]?.length ?: 0
                                if (largeManifestBytes - previousBytes + mapping.length > 512 * 1024) continue
                                val folder = File(directory, "$target/res/drawable")
                                check(folder.mkdirs() || folder.isDirectory)
                                val large = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                try {
                                    result.drawable.forLargeIcon(width, height).apply {
                                        setBounds(0, 0, width, height)
                                        draw(Canvas(large))
                                    }
                                    File(folder, "$pkg.png").outputStream().use {
                                        check(large.compress(Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                    // Publish ownership only after a complete PNG exists. The native
                                    // side checks it again before redirecting directory enumeration.
                                    largePaths[source] = mapping
                                    largeManifestBytes += mapping.length - previousBytes
                                    files++
                                } finally {
                                    large.recycle()
                                }
                            }
                        }
                    }
                    rendered++
                } finally {
                    bitmap.recycle()
                }
            } catch (failure: Exception) {
                logw(TAG, "icon export skipped for $identity: ${failure.message}")
            }
        }
        // A committed generation also carries the real installed package names
        // for the launcher's package refresh receiver. Include skipped packages:
        // their previous themed image must fall back when no new PNG is usable.
        File(directory, ".packages").writeText(activities.map { it.packageName }.distinct().sorted()
            .joinToString("\n", postfix = "\n"))
        if (largePaths.isNotEmpty()) File(directory, ".large-icons").writeText(largePaths.toSortedMap().values.joinToString(""))
        File(directory, ".large-signature").writeText(largeIcons.signature)
        return Result(rendered, files)
    }

    /** Test/export callers use the same selected profile as the publisher. */
    fun export(context: Context, directory: File, isCurrent: () -> Boolean): Result? =
        ProfileRepository.resolve(context).profile?.let { export(context, directory, it, isCurrent = isCurrent) }
}
