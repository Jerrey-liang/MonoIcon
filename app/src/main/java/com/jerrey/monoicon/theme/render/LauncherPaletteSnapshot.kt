package com.jerrey.monoicon.theme.render

import android.app.Activity
import android.app.Application
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.jerrey.monoicon.theme.color.dynamic.IconThemeColors
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Launcher-only draw-time snapshot. The original engine still computes every color. */
object LauncherPaletteSnapshot {
    private const val TAG = "MonoIcon.Palette"
    private const val OVERLAY_CHANGED = "android.intent.action.OVERLAY_CHANGED"
    private val lock = Any()
    private val drawables = WeakHashMap<Drawable, Unit>()
    private val firstRead = CountDownLatch(1)
    private val invalidationPosted = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var workerThread: Thread? = null
    private val executor by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "MonoIcon-Palette").apply {
                isDaemon = true
                workerThread = this
            }
        }
    }

    @Volatile private var applicationContext: Context? = null
    @Volatile private var palette: IconThemeColors? = null
    private var requestedPrefs: SharedPreferences? = null
    private var scheduled = false
    private var dirty = false
    // The following fields are owned by the single worker.
    private var engineInitialized = false
    private var systemListenersRegistered = false
    private var observedPrefs: SharedPreferences? = null

    val isReady: Boolean get() = palette != null

    /** Null until this process explicitly initializes the Launcher bridge. */
    fun currentOrNull(): IconThemeColors? = palette

    /**
     * A loader-thread call waits for the first original-engine result. A main-thread
     * call never waits; drawables retain the original read path until it is ready.
     */
    fun initialize(context: Context, prefs: SharedPreferences?) {
        val app = context.applicationContext ?: return
        synchronized(lock) {
            val firstInitialization = applicationContext == null
            if (firstInitialization) applicationContext = app
            val changedPrefs = prefs != null && requestedPrefs !== prefs
            if (changedPrefs) requestedPrefs = prefs
            if (firstInitialization || changedPrefs || palette == null) scheduleLocked()
        }
        if (Looper.myLooper() != Looper.getMainLooper() && Thread.currentThread() !== workerThread) {
            try {
                firstRead.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Coalesces event bursts into one queued worker and, at most, one pending reread. */
    fun refresh() {
        synchronized(lock) {
            if (applicationContext != null) scheduleLocked()
        }
    }

    /** Only weak references are retained; callbacks and their Views remain drawable-owned. */
    internal fun observe(drawable: Drawable): Boolean {
        if (applicationContext == null) return false
        synchronized(drawables) { drawables[drawable] = Unit }
        return true
    }

    private fun scheduleLocked() {
        dirty = true
        if (scheduled) return
        scheduled = true
        executor.execute(::drainRefreshes)
    }

    private fun drainRefreshes() {
        while (true) {
            val (context, prefs) = synchronized(lock) {
                if (!dirty) {
                    scheduled = false
                    return
                }
                dirty = false
                applicationContext!! to requestedPrefs
            }
            try {
                registerSystemListeners(context)
                observePreferences(prefs)
                if (!engineInitialized) {
                    PixelMonetColorEngine.init(context)
                    engineInitialized = true
                }
                val updated = PixelMonetColorEngine.getIconColors()
                val previous = palette
                palette = updated
                if (previous == null || previous.foreground != updated.foreground || previous.background != updated.background) {
                    invalidateObservedDrawables()
                }
            } catch (failure: Throwable) {
                // A transient read failure keeps the previous result, or the original
                // drawable fallback if no read has succeeded yet.
                android.util.Log.w(TAG, "palette refresh failed: ${failure.message}")
            } finally {
                firstRead.countDown()
            }
        }
    }

    private val colorsListener = WallpaperManager.OnColorsChangedListener { _, _ -> refresh() }
    private val configurationListener = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = refresh()
        override fun onLowMemory() = Unit
    }
    private val wallpaperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "variant_id") refresh()
    }
    private val activityListener = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = refresh()
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun registerSystemListeners(context: Context) {
        if (systemListenersRegistered) return
        systemListenersRegistered = true
        registerSafely("wallpaper colors") {
            WallpaperManager.getInstance(context).addOnColorsChangedListener(colorsListener, mainHandler)
        }
        registerSafely("configuration") { context.registerComponentCallbacks(configurationListener) }
        if (context is Application) {
            registerSafely("activity resume") { context.registerActivityLifecycleCallbacks(activityListener) }
        }
        registerSafely("wallpaper broadcast") {
            context.registerReceiver(
                wallpaperReceiver,
                IntentFilter(Intent.ACTION_WALLPAPER_CHANGED).apply {
                    // The same additional actions observed by HyperOS 3's
                    // DesktopWallpaperManager for gallery/video wallpapers.
                    addAction("miui.gallery.action.WALLPAPER_CHANGED")
                    addAction("android.intent.action.UPDATE_DESKTOP_VIDEO_WALLPAPER")
                },
                Context.RECEIVER_EXPORTED,
            )
        }
        registerSafely("overlay broadcast") {
            context.registerReceiver(
                wallpaperReceiver,
                IntentFilter(OVERLAY_CHANGED).apply { addDataScheme("package") },
                Context.RECEIVER_EXPORTED,
            )
        }
    }

    private fun observePreferences(prefs: SharedPreferences?) {
        if (prefs == null || observedPrefs === prefs) return
        observedPrefs?.let { previous ->
            runCatching { previous.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        }
        observedPrefs = null
        registerSafely("preferences") {
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            observedPrefs = prefs
        }
    }

    private inline fun registerSafely(source: String, register: () -> Unit) {
        try {
            register()
        } catch (failure: Throwable) {
            android.util.Log.w(TAG, "$source listener unavailable: ${failure.message}")
        }
    }

    private fun invalidateObservedDrawables() {
        if (!invalidationPosted.compareAndSet(false, true)) return
        mainHandler.post {
            invalidationPosted.set(false)
            val live = synchronized(drawables) { drawables.keys.toList() }
            live.forEach { drawable -> runCatching { drawable.invalidateSelf() } }
        }
    }
}
