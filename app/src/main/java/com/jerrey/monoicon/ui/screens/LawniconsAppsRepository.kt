package com.jerrey.monoicon.ui.screens

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.EffectiveIconStyle
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy
import com.jerrey.monoicon.theme.mask.LabMonochromeExtractor
import com.jerrey.monoicon.theme.mask.LawniconsAssetSource
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import com.jerrey.monoicon.theme.render.adjustsGeneratedBorder
import java.text.Collator
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One desktop-launchable package, with its launcher component and actual resource availability. */
data class LawniconsApp(
    val packageName: String,
    val label: String,
    val identity: String,
    val hasNativeMonochrome: Boolean = false,
    val hasLawnicons: Boolean = false,
)

/** Package queries and icon generation never run on the Compose/UI thread. */
object LawniconsAppsRepository {
    private const val PREVIEW_SIZE = 192
    private val previewWorkers = Semaphore(2)
    private val previewGeneration = AtomicLong()
    private data class OriginalIconKey(val generation: Long, val identity: String, val size: Int)
    private val originalIcons = object : LruCache<OriginalIconKey, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: OriginalIconKey, value: Bitmap): Int = value.allocationByteCount
    }
    private val previews = object : LruCache<PreviewKey, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: PreviewKey, value: Bitmap): Int = value.allocationByteCount
    }
    @Volatile private var colorEngineInitialized = false

    private data class PreviewKey(
        val generation: Long,
        val identity: String,
        val settings: EffectiveIconStyle,
        val allowLawniconsFallback: Boolean,
        val foreground: Int,
        val background: Int,
    )

    /** An old in-flight render cannot repopulate the current cache after a reload. */
    fun invalidatePreviews() {
        previewGeneration.incrementAndGet()
        previews.evictAll()
        originalIcons.evictAll()
    }

    /** List artwork uses the APK's original layers and the framework adaptive mask. */
    suspend fun loadOriginalIcon(context: Context, app: LawniconsApp): Bitmap? = withContext(Dispatchers.IO) {
        previewWorkers.withPermit {
            currentCoroutineContext().ensureActive()
            val size = context.resources.getDimensionPixelSize(android.R.dimen.app_icon_size).coerceAtLeast(1)
            val key = OriginalIconKey(previewGeneration.get(), app.identity, size)
            originalIcons.get(key)?.let { return@withPermit it }
            val source = runCatching { loadSourceIcon(context.packageManager, app) }
                .getOrElse { context.packageManager.defaultActivityIcon.mutate() }
            val drawable = prepareOriginalIcon(source, size)
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
                currentCoroutineContext().ensureActive()
                if (key.generation == previewGeneration.get()) originalIcons.put(key, bitmap)
            }
        }
    }

    /** Compatibility for the existing navigation and injectable list loaders. */
    suspend fun loadInstalledApps(context: Context): List<LawniconsApp> = loadLauncherApps(context)

    // Package overrides apply to every activity, so show one stable launcher entry per package.
    // The manifest's MAIN/LAUNCHER query exposes desktop apps without broad package visibility.
    @SuppressLint("QueryPermissionsNeeded")
    suspend fun loadLauncherApps(context: Context): List<LawniconsApp> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        LawniconsAssetSource.init(appContext)
        val pm = appContext.packageManager
        val launchers = queryLauncherEntries(pm)
        val apps = launchers.map { entry ->
            currentCoroutineContext().ensureActive()
            val info = entry.activityInfo
            val pkg = info.packageName
            val identity = ComponentName(pkg, info.name).flattenToString()
            val label = runCatching { entry.loadLabel(pm).toString() }.getOrDefault(pkg).ifBlank { pkg }
            val app = LawniconsApp(pkg, label, identity)
            readAppResources(pm, app)
        }
        val collator = Collator.getInstance()
        apps.sortedWith { first, second ->
            collator.compare(first.label, second.label).takeIf { it != 0 }
                ?: first.packageName.compareTo(second.packageName)
        }
    }

    /** Refresh restored detail metadata without depending on the selected source or generated masks. */
    suspend fun loadAppResources(context: Context, app: LawniconsApp): LawniconsApp = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        LawniconsAssetSource.init(appContext)
        currentCoroutineContext().ensureActive()
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val launchers = queryLauncherActivities(pm, intent)
        if (launchers.isEmpty()) {
            app.copy(hasNativeMonochrome = false, hasLawnicons = false)
        } else {
            val entry = selectRepresentativeLauncher(pm, app.packageName, launchers)
            val info = entry.activityInfo
            val refreshed = app.copy(
                label = runCatching { entry.loadLabel(pm).toString() }.getOrDefault(app.packageName)
                    .ifBlank { app.packageName },
                identity = ComponentName(info.packageName, info.name).flattenToString(),
            )
            readAppResources(pm, refreshed)
        }
    }

    /** Worker-only desktop entries, shared by the app list and generated-border preview. */
    internal fun queryLauncherEntries(pm: PackageManager) =
        queryLauncherActivities(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            .groupBy { it.activityInfo.packageName }
            .map { (pkg, entries) -> selectRepresentativeLauncher(pm, pkg, entries) }

    private fun queryLauncherActivities(pm: PackageManager, intent: Intent) =
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .filter { it.activityInfo?.let { info ->
                info.enabled && info.applicationInfo.enabled && info.exported
            } == true }

    private fun selectRepresentativeLauncher(
        pm: PackageManager,
        packageName: String,
        entries: List<android.content.pm.ResolveInfo>,
    ): android.content.pm.ResolveInfo {
        val preferred = runCatching { pm.getLaunchIntentForPackage(packageName)?.component }.getOrNull()
        return preferred?.let { component ->
            entries.firstOrNull { entry ->
                val info = entry.activityInfo
                info.packageName == component.packageName && info.name == component.className
            }
        } ?: entries.first()
    }

    private fun readAppResources(pm: PackageManager, app: LawniconsApp): LawniconsApp {
        val native = runCatching { hasUsableNativeMonochrome(loadSourceIcon(pm, app)) }.getOrDefault(false)
        return app.copy(hasNativeMonochrome = native,
            hasLawnicons = LawniconsAssetSource.hasAvailableMask(app.identity))
    }

    /** Match the render pipeline: empty and solid monochrome placeholders are not authored sources. */
    internal fun hasUsableNativeMonochrome(source: Drawable): Boolean {
        val adaptive = source as? AdaptiveIconDrawable ?: return false
        val size = maxOf(source.intrinsicWidth, source.intrinsicHeight).coerceAtLeast(1)
        val candidate = DrawableConverter.getMonochromeLayer(adaptive)?.let {
            LabMonochromeExtractor.extractPixelNativeMonochrome(it, size)
        } ?: return false
        return try {
            AospMonochromeMaskStrategy.hasUsableGlyph(candidate)
        } finally {
            candidate.recycle()
        }
    }

    internal fun prepareOriginalIcon(source: Drawable, size: Int): Drawable =
        if (source is AdaptiveIconDrawable) source
        else DrawableConverter.wrapAospLegacyIcon(source, size) ?: source

    /** Only visible cards request previews; at most two expensive renders run together. */
    suspend fun renderPreview(context: Context, app: LawniconsApp, overrides: AppIconOverrides): Bitmap? =
        withContext(Dispatchers.Default) {
            previewWorkers.withPermit {
                currentCoroutineContext().ensureActive()
                initializeColors(context.applicationContext)
                LawniconsAssetSource.init(context.applicationContext)
                val colors = PixelMonetColorEngine.getIconColors()
                // Resolve drafts against global defaults, including individual resets.
                val settings = overrides.resolve(ConfigManager.getEffectiveIconStyleFromUi(null))
                val allowLawnicons = ConfigManager.permitsLawniconsFallbackFromUi(app.identity, overrides)
                val key = PreviewKey(previewGeneration.get(), app.identity, settings, allowLawnicons,
                    colors.foreground, colors.background)
                previews.get(key)?.let { return@withPermit it }
                val source = loadSourceIcon(context.packageManager, app)
                val generated = AospMonochromeMaskStrategy(
                    generatedBorderRatio = settings.generatedBorderRatio,
                    sourceStyle = settings.style,
                    allowLawniconsFallback = allowLawnicons,
                ).generate(source, app.identity)
                val mask = generated?.mask
                val drawable = if (mask == null) source else ColoredMonochromeDrawable(
                    mask, colors.foreground, colors.background,
                    roundnessPercent = settings.roundnessPercent,
                    generatedArtworkRadiusFraction = settings.generatedArtworkRadiusFraction,
                    sizePercent = settings.sizePercent,
                    dynamicColors = false,
                    generatedBorderRatio = settings.generatedBorderRatio,
                    adjustGeneratedBorder = adjustsGeneratedBorder(generated.source),
                    tonal = generated.tonal,
                )
                currentCoroutineContext().ensureActive()
                Bitmap.createBitmap(PREVIEW_SIZE, PREVIEW_SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
                    drawable.setBounds(0, 0, PREVIEW_SIZE, PREVIEW_SIZE)
                    drawable.draw(Canvas(bitmap))
                    if (key.generation == previewGeneration.get()) previews.put(key, bitmap)
                }
            }
        }

    private fun loadSourceIcon(pm: PackageManager, app: LawniconsApp): Drawable {
        val component = ComponentName.unflattenFromString(app.identity)
        val activity = component?.let {
            runCatching { pm.getActivityInfo(it, PackageManager.ComponentInfoFlags.of(0)) }.getOrNull()
        }
        val info = activity?.applicationInfo
            ?: pm.getApplicationInfo(app.packageName, PackageManager.ApplicationInfoFlags.of(0))
        val iconId = activity?.iconResource?.takeIf { it != 0 } ?: info.icon
        // Preserve the original AdaptiveIconDrawable layers, matching export.
        return if (iconId != 0) pm.getResourcesForApplication(info).getDrawable(iconId, null).mutate()
        else pm.defaultActivityIcon.mutate()
    }

    private fun initializeColors(context: Context) {
        if (colorEngineInitialized) return
        synchronized(this) {
            if (colorEngineInitialized) return
            PixelMonetColorEngine.init(context)
            colorEngineInitialized = true
        }
    }
}
