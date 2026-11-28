package com.jerrey.monoicon.hook

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.drawable.toDrawable
import android.graphics.drawable.Drawable
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.EffectiveIconStyle
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy
import com.jerrey.monoicon.theme.mask.LawniconsAssetSource
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import com.jerrey.monoicon.theme.render.ThemedIconBuilder
import java.io.Closeable
import java.io.File

/** Mount the declared base/split APKs at highest priority in a private Resources instance. */
internal class HyperOs4ApkIcons(context: Context, app: ApplicationInfo, config: Configuration) : Closeable {
    private val resources = (context.applicationContext ?: context).createPackageContext("android", 0)
        .createConfigurationContext(config).resources
    private val loader = ResourcesLoader()
    private val providers = mutableListOf<ResourcesProvider>()
    private var attached = false

    init {
        try {
            for (path in listOf(app.publicSourceDir) + app.splitPublicSourceDirs.orEmpty()) {
                val provider = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use {
                    ResourcesProvider.loadFromApk(it)
                }
                providers += provider
                loader.addProvider(provider)
            }
            resources.addLoaders(loader)
            attached = true
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun drawable(activity: ActivityInfo): Drawable {
        val theme = resources.newTheme()
        if (activity.themeResource != 0) theme.applyStyle(activity.themeResource, true)
        val id = activity.iconResource.takeIf { it != 0 } ?: android.R.drawable.sym_def_app_icon
        val value = TypedValue()
        resources.getValue(id, value, true)
        if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data.toDrawable()
        }
        val path = value.string?.toString() ?: throw Resources.NotFoundException("No icon asset for $id")
        // Inflate the selected APK entry directly, bypassing the OEM's icon drawable lookup.
        return if (path.endsWith(".xml")) {
            resources.assets.openXmlResourceParser(value.assetCookie, path).use {
                Drawable.createFromXml(resources, it, theme)
            }
        } else {
            resources.openRawResource(id, value).use {
                Drawable.createFromResourceStream(resources, value, it, path)
                    ?: throw Resources.NotFoundException("Cannot decode $path")
            }
        }.mutate()
    }

    override fun close() {
        try {
            if (attached) resources.removeLoaders(loader)
        } finally {
            loader.clearProviders()
            providers.forEach { it.close() }
        }
    }
}

internal fun launcherActivities(pm: PackageManager, packageName: String? = null): List<ActivityInfo> =
    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setPackage(packageName), PackageManager.ResolveInfoFlags.of(0))
        .mapNotNull { it.activityInfo }
        .distinctBy { it.packageName to it.name }
        .sortedWith(compareBy({ it.packageName }, { it.name }))

/** An export uses one snapshot even if preferences or the wallpaper change midway. */
internal data class HyperOs4RenderSettings(
    val style: HyperOs4IconStyle,
    val foreground: Int,
    val background: Int,
    val configuration: Configuration,
) {
    private val defaults = EffectiveIconStyle(style.sourceStyle, style.roundnessPercent,
        style.sizePercent, style.generatedBorderPercent, style.generatedArtworkRoundnessPercent,
        style.generatedArtworkFollowIconRoundness)

    fun appearance(pkg: String): EffectiveIconStyle =
        (style.appOverrides[pkg] ?: AppIconOverrides()).resolve(defaults)

    fun drawable(apkIcons: HyperOs4ApkIcons, activity: ActivityInfo, size: Int): ColoredMonochromeDrawable? {
        val appearance = appearance(activity.packageName)
        val strategy = AospMonochromeMaskStrategy(
            generatedBorderRatio = appearance.generatedBorderRatio,
            sourceStyle = appearance.style,
            allowLawniconsFallback = appearance.style.permitsLawniconsFallback,
            apkRenderSize = size,
        ).also { it.configureCache(style.theme, "desktop") }
        val identity = "${activity.packageName}/${activity.name}"
        val generated = strategy.generate(apkIcons.drawable(activity), identity)
        val mask = generated?.mask ?: return null
        if (!AospMonochromeMaskStrategy.hasUsableGlyph(mask, generated.tonal)) return null
        val recipe = ThemedIconBuilder.Recipe(mask, foreground, background, generated.source, generated.tonal, identity)
        return ThemedIconBuilder.drawable(recipe, appearance, dynamicColors = false).drawable
    }

    companion object {
        fun current(context: Context): HyperOs4RenderSettings {
            LawniconsAssetSource.init(context)
            PixelMonetColorEngine.init(context)
            val colors = PixelMonetColorEngine.getIconColors()
            return HyperOs4RenderSettings(HyperOs4IconStyle.current(), colors.foreground, colors.background,
                Configuration(context.resources.configuration))
        }
    }
}

internal fun Drawable.writeHyperOsPng(destination: File, width: Int, height: Int) {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        setBounds(0, 0, width, height)
        draw(Canvas(bitmap))
        destination.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
        bitmap.recycle()
    }
}
