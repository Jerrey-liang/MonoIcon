package com.jerrey.monoicon.hook

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
    private const val SIZE = 192
    private const val TAG = "MonoIcon.HyperOS4"
    private val safeName = Regex("[A-Za-z0-9_.$#]+\\.png")
    // 6144 ThemeIconLoader._iconMapping, ObjectPool offsets 0x2a208..0x2a2c0.
    // The loader resolves these legacy names before it tries the actual file.
    private val nativeAliases = mapOf(
        "com.android.contacts.activities.TwelveKeyDialer.png" to "com.android.contacts.TwelveKeyDialer.png",
        "com.miui.weather2.png" to "com.miui.weather.png",
        "com.miui.gallery.png" to "com.android.gallery.png",
        "com.android.gallery3d.png" to "com.cooliris.media.png",
        "com.xiaomi.market.png" to "com.miui.supermarket.png",
        "com.wali.miui.networkassistant.png" to "com.android.monitor.png",
        "com.xiaomi.scanner.png" to "com.miui.barcodescanner.png",
        "com.miui.calculator.png" to "com.android.calculator2.png",
        "com.xiaomi.misettings.png" to "com.android.settings.png",
        "com.miui.themestore.png" to "com.android.thememanager.png",
        "com.miui.themestore#com.miui.thememanagerstore.ThemeResourceTabActivity.png" to "com.android.thememanager.png",
        "com.miui.securitymanager.png" to "com.miui.securitycenter.png",
        "com.miui.securitymanager#com.miui.securitymain.SCMainEntryActivity.png" to "com.miui.securitycenter.png",
    )

    data class Result(val components: Int, val files: Int)

    fun export(context: Context, directory: File, isCurrent: () -> Boolean): Result? {
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
                    val names = candidateNames(pkg, cls).toMutableList()
                    // Prefer the package's primary launch activity for its fallback.
                    val main = pm.getLaunchIntentForPackage(pkg)?.component?.className
                    if (pkg !in packageFiles || cls == main) {
                        names += "$pkg.png"
                        packageFiles += pkg
                    }
                    names += names.mapNotNull(nativeAliases::get)
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
                    rendered++
                } finally {
                    bitmap.recycle()
                }
            } catch (failure: Exception) {
                logw(TAG, "icon export skipped for $identity: ${failure.message}")
            }
        }
        return Result(rendered, files)
    }

    /** Names consumed by 6144 ThemeIconLoader._getFileName / _getIconNames. */
    internal fun candidateNames(pkg: String, cls: String): List<String> =
        listOf(if (cls.startsWith(pkg)) "$cls.png" else "$pkg#$cls.png", "$cls.png")
            .distinct().filter { it.length <= 255 && safeName.matches(it) }

}
