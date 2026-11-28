package com.jerrey.monoicon.hook

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import com.jerrey.monoicon.config.EffectiveIconStyle
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.theme.archive.ThemeIconZip
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat

internal object HyperOs4ThemeRenderer {
    private const val SIZE = 192
    private const val TAG = "MonoIcon.ThemeArchive"

    /** Changes affecting any installed icon, including per-app overrides. */
    fun signature(context: Context): String {
        val settings = HyperOs4RenderSettings.current(context)
        return digest(environment(context, settings), settings.style.signature())
    }

    /**
     * A null selection reconciles the entire launcher catalog, reusing current PNGs.
     * A package event forces those packages only, even when the APK timestamp is unchanged.
     * An absent package has an empty entry map, so the installer can remove all its old entries.
     */
    @Synchronized
    fun render(context: Context, directory: File, packages: Set<String>?,
               isCurrent: () -> Boolean = { true }): Map<String, Map<String, File>>? {
        if (!isCurrent()) return null
        require(packages == null || packages.all { ThemeIconEntries.validPackage(it) })
        val pm = context.packageManager
        val catalog = if (packages == null) launcherActivities(pm).groupBy { it.packageName }
            else packages.associateWith { launcherActivities(pm, it) }
        check(packages != null || catalog.isNotEmpty()) { "Launcher catalog unavailable" }
        val settings = HyperOs4RenderSettings.current(context)
        val environment = environment(context, settings)
        check(directory.mkdirs() || directory.isDirectory)
        val cache = ThemePngCache(File(context.cacheDir, "hyperos-theme-png"),
            Files.createTempDirectory(directory.toPath(), "icons-").toFile())
        val output = mutableMapOf<String, Map<String, File>>()
        for ((pkg, activities) in catalog) {
            if (!isCurrent()) return null
            if (activities.isEmpty()) {
                output[pkg] = emptyMap()
                cache.retain(pkg, emptySet())
                continue
            }
            val app = activities.first().applicationInfo
            val apk = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            val stamp = apkStamp(environment, settings.appearance(pkg), apk.longVersionCode,
                apk.lastUpdateTime, app.publicSourceDir, app.splitPublicSourceDirs)
            val main = pm.getLaunchIntentForPackage(pkg)?.component?.className
            val icons = lazy { HyperOs4ApkIcons(context, app, settings.configuration) }
            val byActivity = linkedMapOf<String, File>()
            val keep = mutableSetOf<String>()
            try {
                for (activity in activities) {
                    if (!isCurrent()) return null
                    val cls = activity.name
                    val key = digest(pkg, cls, stamp, activity.iconResource.toString(), activity.themeResource.toString())
                    val png = cache.png(pkg, key, force = packages != null) { file ->
                        val drawable = renderActivity(settings, icons, activity) ?: return@png false
                        drawable.writeHyperOsPng(file, SIZE, SIZE)
                        true
                    } ?: continue
                    keep += key
                    byActivity[cls] = png
                }
            } finally {
                if (icons.isInitialized()) icons.value.close()
            }
            output[pkg] = ThemeIconEntries.entries(pkg, main, byActivity)
            cache.retain(pkg, keep)
        }
        check(packages != null || output.values.any { it.isNotEmpty() }) { "No launcher icons were rendered" }
        if (packages == null) cache.retainPackages(catalog.keys)
        return output.takeIf { isCurrent() }
    }

    private fun renderActivity(settings: HyperOs4RenderSettings, icons: Lazy<HyperOs4ApkIcons>,
                               activity: ActivityInfo) = try {
        settings.drawable(icons.value, activity, SIZE)
    } catch (failure: Exception) {
        logw(TAG, "Could not load APK icon ${activity.packageName}/${activity.name}: ${failure.message}")
        null
    }

    private fun environment(context: Context, settings: HyperOs4RenderSettings): String {
        val module = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        // Exclude theme/configuration sequence counters. Applying our own ZIP changes them.
        val qualifiers = with(settings.configuration) {
            listOf(mcc, mnc, locales.toLanguageTags(), layoutDirection, densityDpi, screenLayout,
                screenWidthDp, screenHeightDp, smallestScreenWidthDp, orientation, uiMode, colorMode,
                touchscreen, keyboard, keyboardHidden, hardKeyboardHidden, navigation, navigationHidden,
                fontScale, fontWeightAdjustment, grammaticalGender).joinToString("/")
        }
        return digest("monoicon-theme-192-v1", Build.FINGERPRINT, module.longVersionCode.toString(),
            module.lastUpdateTime.toString(), qualifiers, settings.style.theme, settings.style.engine,
            settings.style.variant, settings.foreground.toString(), settings.background.toString())
    }

    internal fun apkStamp(environment: String, appearance: EffectiveIconStyle, version: Long, updated: Long,
                          base: String, splits: Array<String>?): String =
        digest(environment, appearance.toString(), version.toString(), updated.toString(), base, *splits.orEmpty())

    private fun digest(vararg parts: String): String {
        val bytes = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

/** Case-sensitive Xiaomi IconCustomizer ZIP lookup, including activities outside their app namespace. */
internal object ThemeIconEntries {
    private const val PREFIX = "res/drawable-xxhdpi/"
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")

    fun validPackage(pkg: String): Boolean = pkg.length <= 255 && packagePattern.matches(pkg)

    fun entries(pkg: String, main: String?, rendered: Map<String, File>): Map<String, File> = buildMap {
        val fallback = rendered[main] ?: rendered.values.firstOrNull() ?: return@buildMap
        put("$PREFIX$pkg.png", fallback)
        for ((activity, file) in rendered) {
            val cls = if (activity.startsWith('.')) pkg + activity else activity
            require(cls.isNotEmpty() && cls.none { it == '/' || it == '\\' || it.isISOControl() })
            val name = if (cls.startsWith("$pkg.")) cls else "$pkg#$cls"
            put("$PREFIX$name.png", file)
        }
    }
}

/** The installer owns the staged hard links. Replacing or pruning the cache leaves them readable. */
internal class ThemePngCache(root: File, batch: File) {
    private val root = root
    private val batch = batch

    fun png(pkg: String, key: String, force: Boolean, render: (File) -> Boolean): File? {
        val directory = File(root, pkg)
        check(directory.mkdirs() || directory.isDirectory)
        val cached = File(directory, "$key.png")
        if (force || !ThemeIconZip.isRenderedPng(cached)) {
            val temp = File.createTempFile(".render-", ".tmp", directory)
            try {
                if (!render(temp)) return null
                check(ThemeIconZip.isRenderedPng(temp)) { "Incomplete rendered icon" }
                Files.move(temp.toPath(), cached.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }
        val staged = File(batch, cached.name)
        try {
            Files.createLink(staged.toPath(), cached.toPath())
        } catch (_: IOException) {
            cached.copyTo(staged)
        }
        return staged
    }

    fun retain(pkg: String, keys: Set<String>) {
        val directory = File(root, pkg)
        directory.listFiles()?.forEach { if (it.nameWithoutExtension !in keys) it.delete() }
        if (keys.isEmpty()) directory.delete()
    }

    fun retainPackages(packages: Set<String>) {
        root.listFiles()?.filter { it.name !in packages }?.forEach { it.deleteRecursively() }
    }
}
