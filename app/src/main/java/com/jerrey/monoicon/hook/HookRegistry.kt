package com.jerrey.monoicon.hook

import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Process-local installation evidence. The settings process reads [HookDiagnostics], never this map. */
object HookRegistry {
    enum class Status { INSTALLED, FAILED, SKIPPED, UNKNOWN }

    data class HookEntry(
        val name: String,
        val group: String,
        val member: String,
        val required: Boolean,
        val status: Status = Status.UNKNOWN,
        val detail: String = "No installation report",
        val hitObserved: Boolean = false,
        val firstHitElapsedMs: Long = 0L,
    ) {
        val installed: Boolean get() = status == Status.INSTALLED
    }

    private val entries = ConcurrentHashMap<String, HookEntry>()
    private val installationGroup = ThreadLocal<String?>()
    val size: Int get() = entries.size
    val installedCount: Int get() = entries.values.count { it.installed }

    internal fun configure(packageName: String) {
        entries.clear()
        val catalog = expectedPoints(packageName)
        catalog.forEach { entries[it.name] = it }
    }

    internal fun skipPending(reason: String) {
        entries.replaceAll { _, entry ->
            if (entry.status == Status.UNKNOWN) entry.copy(status = Status.SKIPPED, detail = reason) else entry
        }
        HookDiagnostics.changed()
    }

    /** A group is an exception boundary, not evidence that every method in it installed. */
    fun install(name: String, required: Boolean, install: () -> Unit): Boolean {
        val previous = installationGroup.get()
        installationGroup.set(name)
        return try {
            install()
            true
        } catch (t: Throwable) {
            failPending(name, required, t)
            android.util.Log.w(TAG, "$name: ${t.javaClass.simpleName} — ${t.message}")
            false
        } finally {
            installationGroup.set(previous)
            HookDiagnostics.changed()
        }
    }

    private fun failPending(group: String, required: Boolean, failure: Throwable) {
        val detail = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}".take(320)
        val interrupted = entries.values.any { (it.group == group || it.name == group) && it.status == Status.FAILED }
        var recorded = false
        entries.replaceAll { _, entry ->
            if ((entry.group == group || entry.name == group) && entry.status == Status.UNKNOWN) {
                recorded = true
                entry.copy(status = if (interrupted) Status.SKIPPED else Status.FAILED,
                    detail = if (interrupted) "Installation group interrupted: $detail" else detail)
            } else entry
        }
        if (!recorded && entries.values.none { it.group == group || it.name == group }) {
            entries[group] = HookEntry(group, group, "Method discovery unavailable", required, Status.FAILED, detail)
        }
    }

    /** Discovery failures for independent overloads must not disappear behind a successful sibling. */
    internal fun discoveryFailed(id: String, failure: Throwable) {
        entries.computeIfPresent(id) { _, entry ->
            if (entry.installed) entry else entry.copy(status = Status.FAILED,
                detail = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}".take(320))
        }
        HookDiagnostics.changed()
    }

    /** Shared static description only; reading this never touches the process-local installation map. */
    internal fun expectedPoints(packageName: String): List<HookEntry> = when (packageName) {
        "com.miui.home" -> launcherPoints() + debugPoints()
        "com.android.systemui" -> systemUiPoints()
        else -> emptyList()
    }

    /** Records success only after libxposed accepts the interceptor. Protection is always enabled. */
    fun intercept(api: XposedInterface, method: Method, callback: (XposedInterface.Chain) -> Any?) {
        val member = member(method)
        val group = installationGroup.get() ?: "RuntimeSupport"
        val candidates = entries.values.filter { it.group == group || it.name == group }
        val known = candidates.firstOrNull { it.member == member }
            ?: candidates.singleOrNull { it.status == Status.UNKNOWN }
        val id = known?.name ?: "$group/$member"
        val entry = known?.copy(member = member) ?: HookEntry(id, group, member, required = false)
        entries[id] = entry
        try {
            api.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    runCatching { observed(id) }
                    callback(chain)
                }
            entries.compute(id) { _, current ->
                (current ?: entry).copy(member = member, status = Status.INSTALLED, detail = "Interceptor installed")
            }
        } catch (t: Throwable) {
            entries.compute(id) { _, current ->
                (current ?: entry).copy(status = Status.FAILED,
                    detail = "${t.javaClass.simpleName}: ${t.message.orEmpty()}".take(320))
            }
            throw t
        } finally {
            HookDiagnostics.changed()
        }
    }

    private fun observed(id: String) {
        val current = entries[id] ?: return
        if (current.hitObserved) return
        var changed = false
        entries.computeIfPresent(id) { _, entry ->
            if (entry.hitObserved) entry else {
                changed = true
                entry.copy(hitObserved = true, firstHitElapsedMs = SystemClock.elapsedRealtime())
            }
        }
        if (changed) HookDiagnostics.changed()
    }

    fun snapshot(): List<HookEntry> = entries.values.sortedBy { it.name }

    fun statusReport(): String = buildString {
        append("Hook status:")
        snapshot().forEach { entry ->
            append("\n  ").append(entry.name).append(" ").append(entry.status)
                .append(" ").append(entry.member).append(" — ").append(entry.detail)
        }
        append("\n  total=$installedCount/$size")
    }

    internal fun member(method: Method): String =
        "${method.declaringClass.name}#${method.name}(${method.parameterTypes.joinToString(",") { it.name }})"

    private fun point(id: String, member: String, required: Boolean = false, group: String = id) =
        HookEntry(id, group, member, required)

    private fun launcherPoints(): List<HookEntry> = listOf(
        point("DiagnosticsPublisher", "android.app.Application#attach(android.content.Context)"),
        point("DesktopIcon", "com.miui.home.launcher.ShortcutIcon#setIconDrawable(android.graphics.drawable.Drawable,android.graphics.Bitmap)", true),
        point("FolderPreview", "com.miui.home.folder.FolderPreviewIconView#refreshIconDrawable(android.graphics.drawable.Drawable)", true),
        point("RawIconProvider", "com.miui.home.icon.IconProvider#getActivityIcon(android.content.pm.LauncherActivityInfo)", true),
        point("SmallFolder", "com.miui.home.folder.FolderIconPreviewContainer1X1\$PreviewIconView#refreshIconDrawable(android.graphics.drawable.Drawable)", true),
        point("FolderIdentity", "com.miui.home.folder.BaseFolderIconPreviewContainer2X2#setViewDrawable(com.miui.home.data.IShortcutInfo,com.miui.home.folder.FolderPreviewIconView,android.graphics.drawable.Drawable)", true),
        point("FolderIdentity1x1", "com.miui.home.folder.FolderIconPreviewContainer1X1#loadItemIcons\$lambda\$0(com.miui.home.data.IShortcutInfo,com.miui.home.folder.FolderIconPreviewContainer1X1,int,android.graphics.drawable.Drawable)"),
        point("ResetBackAnim", "com.miui.home.launcher.ShortcutIcon#resetBackAnim()", true),
        point("DrawableToBitmap", "com.miui.home.launcher.anim.util.FloatingIconAnimHelper#drawableToBitmap(android.graphics.drawable.Drawable,int,int)"),
        point("DrawBackgroundUniform", "com.miui.home.launcher.anim.util.FloatingIconAnimHelper#drawBackground(android.graphics.Canvas,android.graphics.Path,android.widget.FrameLayout\$LayoutParams,int)"),
        point("LayerIconClamp", "com.miui.home.recents.views.FloatingIconLayer2#getMIsClamp()", true),
        point("FillDrawableSkipJni", "com.miui.home.recents.views.FloatingIconUtils\$Companion#fillDrawable(android.graphics.drawable.Drawable,boolean,int,int,boolean,boolean,int)", true),
        point("LargeIconRoundedDrawable", "com.miui.home.launcher.bigicon.BigShortcutIcon#roundedIcon(android.graphics.drawable.Drawable)", group = "LargeIconGeometry"),
        point("LargeIconUninstallDrawable", "com.miui.home.launcher.ShortcutIcon#roundedBigIcon(android.graphics.drawable.Drawable,com.miui.home.launcher.ShortcutInfo,float)", group = "LargeIconGeometry"),
        point("CircleIconConfig", "miui.content.res.IconCustomizer#ensureIconConfigLoaded()"),
        point("CircleIconMaskValue", "miui.content.res.IconCustomizer#getConfigIconMaskValue()"),
        point("CircleIconThemeMask", "miui.content.res.IconCustomizer#getRawIcon(java.lang.String)"),
        point("CircleIconComposeInset", "miui.content.res.IconCustomizer#composeIconForDefaultIcon(miui.content.res.IconCustomizer\$IconConfig,android.graphics.Bitmap)"),
        point("RecentsInvalidate", "com.android.systemui.shared.recents.model.IconLoader#getAndInvalidateIfModified(com.android.systemui.shared.recents.model.Task\$TaskKey,android.app.ActivityManager\$TaskDescription,android.content.res.Resources,boolean)", group = "RecentsIcon"),
        point("RecentsGetIcon", "com.android.systemui.shared.recents.model.IconLoader#getIcon(com.android.systemui.shared.recents.model.Task\$TaskKey,android.app.ActivityManager\$TaskDescription)", group = "RecentsIcon"),
        point("RecentsCachePut", "com.android.systemui.shared.recents.model.TaskKeyLruCache#put(com.android.systemui.shared.recents.model.Task\$TaskKey,java.lang.Object)", group = "RecentsIcon"),
    )

    private fun systemUiPoints(): List<HookEntry> = listOf(
        point("DiagnosticsPublisher", "android.app.Application#attach(android.content.Context)"),
        point("HyperOs4SystemEvents", "android.app.Application#attach(android.content.Context)"),
        point("SystemUiDrawable", "com.miui.systemui.graphics.AppIconsManager#getAppIconInner(java.lang.String,int,android.content.pm.ApplicationInfo,android.content.pm.PackageManager)", group = "SystemUiNotificationIcons"),
        point("SystemUiBitmap", "com.miui.systemui.graphics.AppIconsManager#getAppIconBitmap(int,java.lang.String)", group = "SystemUiNotificationIcons"),
        point("SystemUiStyleDrawable", "miui.content.res.IconCustomizer#generateIconStyleDrawable(android.graphics.drawable.Drawable)", group = "SystemUiStyledIconGuard"),
        point("SystemUiStyleDrawableBoolean", "miui.content.res.IconCustomizer#generateIconStyleDrawable(android.graphics.drawable.Drawable,boolean)", group = "SystemUiStyledIconGuard"),
    )

    private fun debugPoints(): List<HookEntry> = listOf(
        point("Debug/getMonochrome", "com.miui.home.icon.MonochromeUtils#getMonochrome(android.graphics.drawable.AdaptiveIconDrawable)"),
        point("Debug/isSupportMonochrome", "com.miui.home.icon.MonochromeUtils#isSupportMonochrome()"),
        point("Debug/isMonoEnable", "com.miui.home.icon.MonochromeUtils#isMonoEnable()"),
        point("Debug/getColor", "com.miui.home.icon.MonochromeUtils#getColor()"),
    ).map { entry ->
        if (DebugHooks.ENABLED) entry else entry.copy(status = Status.SKIPPED, detail = "Diagnostic hooks disabled in this build")
    }

    private const val TAG = "MonoIcon.Hook"
}
