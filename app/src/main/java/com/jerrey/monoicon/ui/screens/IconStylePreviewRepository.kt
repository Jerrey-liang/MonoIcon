package com.jerrey.monoicon.ui.screens

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.theme.color.dynamic.IconThemeColors
import com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy
import com.jerrey.monoicon.theme.mask.LawniconsAssetSource
import com.jerrey.monoicon.theme.mask.MaskStrategy
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import com.jerrey.monoicon.theme.render.adjustsGeneratedBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A real mask and its source tier; border updates zoom artwork inside a fixed plate. */
data class IconStyleExample(
    val identity: String,
    val label: String?,
    val mask: Bitmap,
    val lawnicons: Boolean,
    val bundledSample: Boolean = false,
    val sourceDrawable: Drawable? = null,
    val source: Int = if (lawnicons) MaskStrategy.SOURCE_LAWNICONS else 0,
    val tonal: Boolean = false,
)

/** Worker-only package and mask loading for the small settings preview. */
object IconStylePreviewRepository {
    private const val SIZE = 192
    private const val COUNT = 6
    private data class MaskKey(
        val identity: String,
        val sourceStyle: IconSourceStyle,
        val allowLawniconsFallback: Boolean,
        val sourceVersion: Long = 0L,
    )
    private data class PreviewMask(val bitmap: Bitmap, val source: Int, val tonal: Boolean = false)
    private val masks = object : LruCache<MaskKey, PreviewMask>(4 * 1024 * 1024) {
        override fun sizeOf(key: MaskKey, value: PreviewMask): Int =
            value.bitmap.allocationByteCount
    }

    private val preferredPackages = listOf(
        "com.android.settings", "com.android.camera", "com.android.deskclock",
        "com.android.calendar", "com.android.contacts", "com.android.fileexplorer",
        "com.google.android.apps.photos", "com.android.chrome",
    )

    /** Compatibility for callers that only distinguish Lawnicons and native priority. */
    suspend fun loadExamples(context: Context, lawniconsEnabled: Boolean): List<IconStyleExample> =
        loadExamples(context, if (lawniconsEnabled) IconSourceStyle.LAWNICONS else IconSourceStyle.NATIVE_GENERATED,
            allowLawniconsFallback = lawniconsEnabled)

    // The manifest declares bundle package resources and MAIN/LAUNCHER queries;
    // this intentionally enumerates only that visible set, with no broad permission.
    @SuppressLint("QueryPermissionsNeeded")
    suspend fun loadExamples(
        context: Context,
        sourceStyle: IconSourceStyle,
        allowLawniconsFallback: Boolean = true,
        generatedBorderOnly: Boolean = false,
    ): List<IconStyleExample> =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val pm = appContext.packageManager
            LawniconsAssetSource.init(appContext)
            val available = LawniconsAssetSource.availablePackages()
            // A launch intent can resolve CATEGORY_INFO rather than a desktop
            // icon. The border preview must use actual enabled LAUNCHER entries.
            val desktopEntries = if (generatedBorderOnly) {
                LawniconsAppsRepository.queryLauncherEntries(pm).associateBy { it.activityInfo.packageName }
            } else emptyMap()
            val installed = if (generatedBorderOnly) {
                desktopEntries.values.map { it.activityInfo.applicationInfo }
            } else runCatching {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            }.getOrDefault(emptyList())
            val apps = installed.filter {
                generatedBorderOnly || it.packageName == appContext.packageName ||
                    it.packageName in available || pm.getLaunchIntentForPackage(it.packageName) != null
            }.sortedWith(compareBy(
                { if (allowLawniconsFallback && sourceStyle == IconSourceStyle.LAWNICONS && it.packageName in available) 0 else 1 },
                { preferredPackages.indexOf(it.packageName).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
                { it.packageName },
            ))
            val examples = mutableListOf<IconStyleExample>()
            for (info in apps) {
                currentCoroutineContext().ensureActive()
                val pkg = info.packageName
                val desktopEntry = desktopEntries[pkg]
                val component = if (generatedBorderOnly) {
                    desktopEntry!!.activityInfo.let { ComponentName(it.packageName, it.name).flattenToString() }
                } else pm.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
                val identity = component ?: pkg
                val prefersPack = allowLawniconsFallback && sourceStyle == IconSourceStyle.LAWNICONS &&
                    LawniconsAssetSource.hasAvailableMask(identity)
                // Keep the first six packs as a fallback, then look for one real
                // non-pack app without changing any app's selected source.
                if (examples.size == COUNT && prefersPack) continue
                val original = runCatching { loadSource(pm, pkg, component) }.getOrNull() ?: continue
                val key = MaskKey(identity, sourceStyle, allowLawniconsFallback)
                val mask = masks.get(key) ?: runCatching {
                    sourceMask(original, identity, sourceStyle, allowLawniconsFallback)
                }.getOrNull()?.also { masks.put(key, it) } ?: continue
                // Resolve the selected source first: resource availability alone
                // does not tell us whether this icon actually uses generation.
                if (generatedBorderOnly && !adjustsGeneratedBorder(mask.source)) continue
                val label = runCatching {
                    (desktopEntry?.loadLabel(pm) ?: info.loadLabel(pm)).toString()
                }.getOrDefault(pkg)
                val example = IconStyleExample(identity, label, mask.bitmap, mask.source == MaskStrategy.SOURCE_LAWNICONS,
                    sourceDrawable = original, source = mask.source, tonal = mask.tonal)
                if (examples.size == COUNT) examples[COUNT - 1] = example else examples += example
                if (examples.size == COUNT && (generatedBorderOnly || examples.any { !it.lawnicons })) break
            }

            // The border page demonstrates installed apps affected by its slider.
            // Keep the real count instead of filling it with unrelated samples.
            if (generatedBorderOnly) return@withContext examples

            // A fresh install can have no matching installed app. A genuine bundle
            // example remains explicitly identified as an example, not an app.
            if (allowLawniconsFallback && sourceStyle == IconSourceStyle.LAWNICONS && examples.none { it.lawnicons }) {
                available.sortedWith(compareBy(
                    { preferredPackages.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }, { it },
                )).firstOrNull()?.let { identity ->
                    LawniconsAssetSource.lookupMask(identity, SIZE)?.let { mask ->
                        if (examples.size == COUNT) examples.removeAt(examples.lastIndex)
                        examples += IconStyleExample(identity, null, mask, true, bundledSample = true)
                    }
                }
            }

            // Ensure the module's own source icon is usable even when package
            // enumeration is restricted. Never invent installed app names.
            if (examples.isEmpty()) {
                val original = appContext.getDrawable(com.jerrey.monoicon.R.mipmap.ic_launcher)!!
                sourceMask(original, null, sourceStyle, allowLawniconsFallback)?.let {
                    examples += IconStyleExample(appContext.packageName, "MonoIcon", it.bitmap, false,
                        sourceDrawable = original, source = it.source, tonal = it.tonal)
                }
            }
            val frameworkIcons = listOf(
                android.R.drawable.ic_menu_camera,
                android.R.drawable.ic_menu_recent_history,
                android.R.drawable.ic_menu_manage,
                android.R.drawable.ic_menu_agenda,
                android.R.drawable.ic_menu_compass,
                android.R.drawable.ic_menu_gallery,
            )
            for (resource in frameworkIcons) {
                if (examples.size == COUNT) break
                currentCoroutineContext().ensureActive()
                val identity = "framework:$resource"
                val original = appContext.getDrawable(resource)!!
                val key = MaskKey(identity, sourceStyle, allowLawniconsFallback)
                val mask = masks.get(key) ?: sourceMask(original, null, sourceStyle, allowLawniconsFallback)
                    ?.also { masks.put(key, it) } ?: continue
                examples += IconStyleExample(identity, null, mask.bitmap, false,
                    sourceDrawable = original, source = mask.source, tonal = mask.tonal)
            }
            examples
        }

    suspend fun renderExamples(
        examples: List<IconStyleExample>,
        colors: IconThemeColors,
        roundnessPercent: Int,
        sizePercent: Int,
        generatedIconBorderRatio: Float = 1f,
    ): List<Bitmap> = renderExamples(examples, colors, roundnessPercent, sizePercent, generatedIconBorderRatio, 0f)

    suspend fun renderExamples(
        examples: List<IconStyleExample>,
        colors: IconThemeColors,
        roundnessPercent: Int,
        sizePercent: Int,
        generatedIconBorderRatio: Float,
        generatedArtworkRadiusFraction: Float,
    ): List<Bitmap> = withContext(Dispatchers.Default) {
        examples.map { example ->
            currentCoroutineContext().ensureActive()
            val drawable = ColoredMonochromeDrawable(
                example.mask, colors.foreground, colors.background,
                roundnessPercent = roundnessPercent, sizePercent = sizePercent, dynamicColors = false,
                generatedBorderRatio = generatedIconBorderRatio,
                adjustGeneratedBorder = !example.lawnicons && adjustsGeneratedBorder(example.source),
                tonal = example.tonal,
                generatedArtworkRadiusFraction = generatedArtworkRadiusFraction,
            )
            Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.setBounds(0, 0, SIZE, SIZE)
                drawable.draw(Canvas(bitmap))
            }
        }
    }

    /** The per-app page uses the same mask and renderer, without adding sample icons. */
    suspend fun loadAppExample(
        context: Context,
        app: LawniconsApp,
        sourceStyle: IconSourceStyle,
        allowLawniconsFallback: Boolean,
    ): List<IconStyleExample> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        LawniconsAssetSource.init(appContext)
        currentCoroutineContext().ensureActive()
        // A package update may replace its icon without changing its identity.
        val version = pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0)).lastUpdateTime
        val original = loadSource(pm, app.packageName, app.identity)
        val key = MaskKey(app.identity, sourceStyle, allowLawniconsFallback, version)
        val mask = masks.get(key) ?: sourceMask(original, app.identity, sourceStyle, allowLawniconsFallback)
            ?.also { masks.put(key, it) } ?: return@withContext emptyList()
        currentCoroutineContext().ensureActive()
        listOf(IconStyleExample(
            app.identity, app.label, mask.bitmap, mask.source == MaskStrategy.SOURCE_LAWNICONS,
            sourceDrawable = original, source = mask.source, tonal = mask.tonal,
        ))
    }

    private fun sourceMask(
        source: Drawable,
        identity: String?,
        sourceStyle: IconSourceStyle,
        allowLawniconsFallback: Boolean,
    ): PreviewMask? {
        // Global preview values are explicit: per-app overrides must not change
        // what the global controls demonstrate. Geometry is applied at render time.
        val strategy = AospMonochromeMaskStrategy(generatedBorderRatio = 1f, sourceStyle = sourceStyle,
            allowLawniconsFallback = allowLawniconsFallback)
        val copy = source.constantState?.newDrawable()?.mutate()
        val generated = if (copy != null) strategy.generate(copy, identity) else synchronized(source) {
            val originalBounds = Rect(source.bounds)
            try {
                strategy.generate(source.mutate(), identity)
            } finally {
                source.bounds = originalBounds
            }
        }
        val mask = generated?.mask ?: return null
        val scaled = if (mask.width == SIZE && mask.height == SIZE) mask
            else Bitmap.createScaledBitmap(mask, SIZE, SIZE, true)
        return PreviewMask(scaled, generated.source, generated.tonal)
    }

    private fun loadSource(pm: PackageManager, pkg: String, identity: String?): Drawable {
        val activity = identity?.let(ComponentName::unflattenFromString)?.let {
            runCatching { pm.getActivityInfo(it, PackageManager.ComponentInfoFlags.of(0)) }.getOrNull()
        }
        val info = activity?.applicationInfo ?: pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        val icon = activity?.iconResource?.takeIf { it != 0 } ?: info.icon
        return if (icon != 0) pm.getResourcesForApplication(info).getDrawable(icon, null).mutate()
        else pm.defaultActivityIcon.mutate()
    }
}
