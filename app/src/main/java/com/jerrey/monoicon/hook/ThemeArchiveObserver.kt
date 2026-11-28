package com.jerrey.monoicon.hook

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.theme.archive.ThemeIconZip
import io.github.libxposed.api.XposedInterface
import android.os.SystemClock
import java.io.File

/** system_server can see theme replacements, including removal during rights validation. */
internal object ThemeArchiveObserver {
    private val directory = File("/data/system/theme")
    private var parentObserver: FileObserver? = null
    private var iconsObserver: FileObserver? = null

    private val icons = File(directory, "icons")
    @Volatile private var rejectedDirectoryInode = -1L
    @Volatile private var lastRejected = -60_000L

    fun start(api: XposedInterface, classLoader: ClassLoader) {
        if (!HyperOs4Environment.isVersion4()) return
        val thread = HandlerThread("MonoIcon-ThemeEvents", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        val handler = Handler(thread.looper)
        val checkArchive = Runnable {
            runCatching {
                val activityThread = Class.forName("android.app.ActivityThread")
                val instance = activityThread.getMethod("currentActivityThread").invoke(null)
                val system = activityThread.getMethod("getSystemContext").invoke(instance) as Context
                val reason = if (SystemClock.elapsedRealtime() - lastRejected < 10_000 &&
                    runCatching { android.system.Os.stat(directory.path).st_ino }.getOrNull() == rejectedDirectoryInode)
                    HyperOs4ThemeSync.VALIDATION else HyperOs4ThemeSync.CHECK
                HyperOs4SystemEvents.wake(system, reason)
            }.onFailure { logw("MonoIcon.Theme", "Theme observer delivery failed: ${it.message}") }
        }
        fun changed() {
            handler.removeCallbacks(checkArchive)
            handler.postDelayed(checkArchive, 1200)
        }
        // Record a vendor rejection only for the exact, self-identified archive. The stock
        // result and any exception always propagate to ThemeReceiver. Some ROMs lack this API.
        runCatching {
            val manager = classLoader.loadClass("miui.drm.DrmManager")
            for (method in manager.declaredMethods.filter {
                it.name == "isLegal" && it.parameterTypes.getOrNull(1) == File::class.java &&
                    it.returnType.name == "miui.drm.DrmManager\$DrmResult"
            }) {
                HookRegistry.intercept(api, method) { call ->
                    val result = call.proceed()
                    runCatching {
                        if (call.getArg(1) == icons && result is Enum<*> && result.name != "DRM_SUCCESS" &&
                            icons.length() in 1..ThemeIconZip.MAX_ARCHIVE && ThemeIconZip.isManaged(icons)) {
                            rejectedDirectoryInode = android.system.Os.stat(directory.path).st_ino
                            lastRejected = SystemClock.elapsedRealtime()
                            changed()
                        }
                    }
                    result
                }
            }
        }.onFailure { logw("MonoIcon.Theme", "Vendor validation reporting unavailable: ${it.message}") }
        fun watchIcons() {
            iconsObserver?.stopWatching()
            iconsObserver = object : FileObserver(directory, MOVED_TO or MOVED_FROM or CREATE or DELETE or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == "icons") changed()
                }
            }.also { it.startWatching() }
        }
        handler.post {
            runCatching {
                if (parentObserver != null) return@runCatching
                parentObserver = object : FileObserver(directory.parentFile!!, MOVED_TO or CREATE or MOVED_FROM or DELETE) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == "theme") handler.post { watchIcons(); changed() }
                    }
                }.also { it.startWatching() }
                watchIcons()
            }.onFailure { logw("MonoIcon.Theme", "Theme observer unavailable: ${it.message}") }
        }
    }
}
