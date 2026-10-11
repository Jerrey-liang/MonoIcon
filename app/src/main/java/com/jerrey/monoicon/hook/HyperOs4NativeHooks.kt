package com.jerrey.monoicon.hook

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.Keep
import com.jerrey.monoicon.BuildConfig
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
 * the controller's settings read and foreground ARGB at the existing mode filter
 * conversion. Masks, renderer and image cache remain launcher-owned. No Bitmap or Java/Dart
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
    // Native owns the 30 s discovery timeout after metadata validation. A slow
    // preparation must not make Java abandon a subsequently successful install.
    private const val WAIT_LIMIT_MS = 35_000L
    private const val POLL_INTERVAL_MS = 100L
    private const val REPLAY_INTERVAL_MS = 250L
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

        // Package/shape gates first: they identify the launcher generation and
        // hold for every build of it.
        if (packageInfo.packageName != PACKAGE || info.sourceDir != apkPath ||
            info.processName != PACKAGE || info.flags and ApplicationInfo.FLAG_HAS_CODE != 0 ||
            !info.splitSourceDirs.isNullOrEmpty()
        ) return

        // Runtime trace diagnostics run before the exact-build gate, so a launcher
        // build the production patch table does not support can still report which
        // Dart icon functions actually execute. The native tracer refuses any
        // libapp.so whose GNU build ID it was not generated for, so this is a
        // no-op on an unrelated build. Debug builds only.
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "device launcher versionCode=${packageInfo.longVersionCode} " +
                    "versionName=${packageInfo.versionName} " +
                    "supported=$VERSION_CODE/$VERSION_NAME " +
                    "matches=${packageInfo.longVersionCode == VERSION_CODE && packageInfo.versionName == VERSION_NAME}",
            )
            HyperOs4TraceProbe.install(apkPath, "preflight before exact-build gate")
        }

        // Capability gate: does this launcher still have a Java icon pipeline?
        //
        // HyperOS 4's launcher is not an ART process — hyos_spawner forks
        // com.miui.home without a Java runtime, so no class loading happens and
        // this whole object can never run inside it. The Kotlin bootstrap and the
        // JNI patch library therefore apply only to an ART launcher (older HyperOS
        // and the Java pipeline). The HyperOS 4 launcher is covered by the
        // LSPosed native_init payload in hyperos4_native_init.cpp, which is
        // loaded into the HYOS spawner process family and does not need Java.
        val hasJavaIconPipeline = try {
            classLoader.loadClass(LEGACY_ICON_CLASS)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        if (!hasJavaIconPipeline) {
            Log.i(
                TAG,
                "no Java icon pipeline on ${packageInfo.versionName} " +
                    "(${packageInfo.longVersionCode}); launcher is handled by the " +
                    "native_init payload, no JNI patch installed",
            )
            return
        }

        // The exact-build gate. Everything below installs the production patches.
        if (packageInfo.longVersionCode != VERSION_CODE || packageInfo.versionName != VERSION_NAME) {
            Log.w(
                TAG,
                "unsupported launcher build ${packageInfo.versionName} (${packageInfo.longVersionCode}); " +
                    "native patch not installed",
            )
            return
        }

        // A matching version string must never divert an APK that still has the Java pipeline.
        // The capability gate above already proved the Java pipeline is absent, so only the
        // no-DEX arm64 shape remains to be checked before loading the patch library.
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
        // an app icon. Compute once and pass the two packed ARGB scalars,
        // before patch installation; no per-icon JNI or pooled ColorFilter writes.
        PixelMonetColorEngine.init(StartupPaletteContext(context))
        val palette = PixelMonetColorEngine.getIconColors()
        System.loadLibrary("monoicon_hyperos4")
        val status = nativeConfigure(
            info.nativeLibraryDir.orEmpty(), apkPath, palette.background, palette.foreground,
        )
        if (status < STATUS_WAITING) {
            reportStatus(status)
            return
        }

        // Wait for the complete native transaction before requesting settings
        // reloads. Registration generations also cover observers installed later.
        // This worker performs no work in icon, Dart, or UI callbacks.
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val deadline = SystemClock.elapsedRealtime() + WAIT_LIMIT_MS
                var current = status
                var pendingReported = false
                while (current == STATUS_WAITING) {
                    if (!pendingReported && SystemClock.elapsedRealtime() >= deadline) {
                        Log.w(TAG, "Native status still pending after 35 s; continuing installation observation")
                        pendingReported = true
                    }
                    Thread.sleep(POLL_INTERVAL_MS)
                    current = nativeStatus()
                }
                reportStatus(current)
                if (current == STATUS_INSTALLED) {
                    observeSettingsReplay(context)
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

    private fun observeSettingsReplay(context: Context) {
        // Exact-build pool/URI evidence: SETTINGS_REPLAY_STATIC_EVIDENCE.md.
        // notifyChange changes no persisted setting. The existing observer reloads
        // both controller settings and invokes the launcher's original refresh.
        val uri = Settings.System.getUriFor("key_monochrome_color")
        var requestedGeneration = -1L
        var failureReported = false
        while (!Thread.currentThread().isInterrupted) {
            // Sample BEFORE notifying. A registration completing between this
            // load and notify is either reached now or causes a later replay.
            val generation = nativeReplayGeneration()
            if (generation >= 0 && generation != requestedGeneration) {
                try {
                    context.contentResolver.notifyChange(uri, null, 0)
                    requestedGeneration = generation
                    failureReported = false
                } catch (t: RuntimeException) {
                    if (!failureReported) {
                        Log.w(TAG, "Settings replay request failed; will retry: ${t.message}", t)
                        failureReported = true
                    }
                }
            }
            // Keep observing late registrations for the process lifetime. Stable
            // generations do no Binder work and never invalidate the icon cache.
            Thread.sleep(if (failureReported) 1_000L else REPLAY_INTERVAL_MS)
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
                    "Custom foreground/background instructions installed; rendering RUNTIME UNVERIFIED; " +
                        "settings replay requests enabled; palette changes require launcher restart",
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
                -12 -> "cannot prepare reachable executable palette/observer code pages"
                else -> "unknown native status"
            }
            Log.w(TAG, "Native custom palette installation incomplete: status=$status ($reason)")
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
        foreground: Int,
    ): Int
    private external fun nativeStatus(): Int
    private external fun nativeReplayGeneration(): Long
}
