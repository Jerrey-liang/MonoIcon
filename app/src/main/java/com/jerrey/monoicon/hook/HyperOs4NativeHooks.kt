package com.jerrey.monoicon.hook

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Bootstrap for the verified arm64 HyperOS 4 Launcher build.
 *
 * Its APK has no DEX, so API 101's onPackageReady is used instead of relying on
 * onPackageLoaded. Native code supplies MonoIcon's computed background ARGB at
 * the controller's settings read. The existing widget tints cached images. Foreground,
 * masks, renderer and image cache remain launcher-owned. No Bitmap or Java/Dart
 * object bridge is created.
 * Preferences, wallpaper palette and light/dark mode are sampled once at startup;
 * applying changes requires a launcher restart so cached icons use one palette.
 */
@Keep
internal object HyperOs4NativeHooks {
    private const val TAG = "MonoIcon.HyperOS4"
    private const val PACKAGE = "com.miui.home"
    private const val VERSION_CODE = 801027726L
    private const val VERSION_NAME = "RELEASE-8.01.02.7726-260904-09231125-R"
    private const val LEGACY_ICON_CLASS = "com.miui.home.launcher.ShortcutIcon"
    private const val HOOK_NAME = "HyperOs4Palette"
    private const val STATUS_WAITING = 0
    private const val STATUS_INSTALLED = 1
    // Native scans for 30 s after validating disk metadata. Leave a margin for
    // that preparation, and sample once more at our observation deadline.
    private const val WAIT_LIMIT_MS = 35_000L
    private const val POLL_INTERVAL_MS = 100L
    private val dexEntry = Regex("classes(?:[0-9]+)?\\.dex")

    private val bootstrapRegistered = AtomicBoolean(false)
    private val attachClaimed = AtomicBoolean(false)
    private val installationReported = AtomicBoolean(false)

    @Volatile
    private var attachHook: XposedInterface.HookHandle? = null

    fun onPackageReady(
        api: XposedModule,
        param: XposedModuleInterface.PackageReadyParam,
        isLauncherProcess: Boolean,
    ) {
        try {
            val info = param.applicationInfo
            if (!isLauncherProcess || !param.isFirstPackage || param.packageName != PACKAGE ||
                info.packageName != PACKAGE || info.processName != PACKAGE ||
                info.flags and ApplicationInfo.FLAG_HAS_CODE != 0 ||
                !Process.is64Bit() || "arm64-v8a" !in Build.SUPPORTED_ABIS
            ) return

            val apkPath = info.sourceDir ?: return
            if (!bootstrapRegistered.compareAndSet(false, true)) return
            val classLoader = param.classLoader
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attach.isAccessible = true
            attachHook = api.hook(attach)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val context = chain.getArg(0) as? Context
                    val claimed = context?.packageName == PACKAGE &&
                        attachClaimed.compareAndSet(false, true)
                    try {
                        // Keep the host's exception/result and invoke its original exactly once.
                        val result = chain.proceed()
                        if (claimed) {
                            try {
                                configure(api, context, classLoader, apkPath)
                            } catch (t: Throwable) {
                                Log.w(TAG, "Native bootstrap skipped: ${t.message}", t)
                            }
                        }
                        result
                    } finally {
                        if (claimed) {
                            try {
                                attachHook?.unhook()
                            } catch (t: Throwable) {
                                Log.w(TAG, "Cannot remove bootstrap hook: ${t.message}")
                            } finally {
                                attachHook = null
                            }
                        }
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot register native bootstrap: ${t.message}", t)
        }
    }

    private fun configure(api: XposedModule, context: Context, classLoader: ClassLoader, apkPath: String) {
        if (context.packageName != PACKAGE || context.applicationInfo.sourceDir != apkPath) return
        val packageInfo = context.packageManager.getPackageInfo(
            PACKAGE,
            PackageManager.PackageInfoFlags.of(0),
        )
        val info = packageInfo.applicationInfo ?: return
        if (packageInfo.packageName != PACKAGE || packageInfo.longVersionCode != VERSION_CODE ||
            packageInfo.versionName != VERSION_NAME || info.sourceDir != apkPath ||
            info.processName != PACKAGE || info.flags and ApplicationInfo.FLAG_HAS_CODE != 0 ||
            !info.splitSourceDirs.isNullOrEmpty()
        ) return

        // A matching version string must never divert an APK that still has the Java pipeline.
        val hasLegacyPipeline = try {
            classLoader.loadClass(LEGACY_ICON_CLASS)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        if (hasLegacyPipeline) return
        val nativeApk = ZipFile(apkPath).use { zip ->
            zip.getEntry("lib/arm64-v8a/libapp.so") != null &&
                zip.getEntry("lib/arm64-v8a/libapp_launcher.so") != null &&
                zip.entries().asSequence().none { dexEntry.matches(it.name) }
        }
        if (!nativeApk) return

        ConfigManager.initForHooks(api)
        if (!ConfigManager.isEnabled()) {
            Log.i(TAG, "Module disabled; native bootstrap skipped")
            return
        }

        // Reuse the same Material 2025 color engine as the current built-in theme.
        // It reads the selected variant and system light/dark mode without loading
        // an app icon. Compute once and pass only the supported background tint,
        // before patch installation; no per-icon JNI or pooled ColorFilter writes.
        PixelMonetColorEngine.init(StartupPaletteContext(context))
        val palette = PixelMonetColorEngine.getIconColors()
        System.loadLibrary("monoicon_hyperos4")
        val status = nativeConfigure(
            info.nativeLibraryDir.orEmpty(), apkPath, palette.background,
        )
        if (reportStatus(status)) return

        // One bounded startup observer. No polling, parsing or rendering in icon/UI callbacks.
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val deadline = SystemClock.elapsedRealtime() + WAIT_LIMIT_MS
                while (true) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    Thread.sleep(minOf(POLL_INTERVAL_MS, remaining))
                    if (reportStatus(nativeStatus())) return@Thread
                }
                if (!reportStatus(nativeStatus())) {
                    Log.w(TAG, "Native status still pending after 35 s; hook installation unconfirmed")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                Log.w(TAG, "Native installation observer failed: ${t.message}", t)
            }
        }, "MonoIcon-HyperOS4").apply {
            isDaemon = true
            start()
        }
    }

    /** Returns true for a terminal result; waiting must never be reported as installed. */
    private fun reportStatus(status: Int): Boolean = when (status) {
        STATUS_WAITING -> false
        STATUS_INSTALLED -> {
            if (installationReported.compareAndSet(false, true)) {
                HookRegistry.install(HOOK_NAME, required = false) {}
                Log.i(
                    TAG,
                    "Custom background tint instructions installed; rendering RUNTIME UNVERIFIED; " +
                        "palette and preference changes require launcher restart",
                )
            }
            true
        }
        else -> {
            val reason = when (status) {
                -1 -> "unsupported architecture"
                -2 -> "invalid launcher paths"
                -3 -> "launcher ELF identity mismatch"
                -4 -> "process mappings or memory unavailable"
                -5 -> "target mapping not found within the native startup window"
                -6 -> "executable memory protection denied; original instructions restored"
                -7 -> "instruction verification mismatch"
                -8 -> "native worker failure"
                -9 -> "patch recovery incomplete; launcher restart required"
                -10 -> "cannot safely pause all launcher threads; instructions unchanged"
                -11 -> "background settings code is active; instructions unchanged"
                else -> "unknown native status"
            }
            Log.w(TAG, "Native custom background tint installation incomplete: status=$status ($reason)")
            true
        }
    }

    // During Application.attach, ContextImpl may not yet expose the Application.
    // Keep the unchanged color engine's application-context contract using the
    // already attached process context, without waiting or registering listeners.
    private class StartupPaletteContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }

    private external fun nativeConfigure(
        nativeDir: String,
        apkPath: String,
        background: Int,
    ): Int
    private external fun nativeStatus(): Int
}
