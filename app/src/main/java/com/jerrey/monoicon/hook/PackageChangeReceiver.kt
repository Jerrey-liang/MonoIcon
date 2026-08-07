package com.jerrey.monoicon.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.jerrey.monoicon.cache.CacheManager

/**
 * Package-change cache invalidation (Phase 4.0-B).
 *
 * Registered dynamically inside the launcher process; on app update /
 * uninstall / icon resource change it drops the stale cache entries for
 * that package so the next icon bind regenerates from the fresh APK.
 *
 * ## Design rules
 * - Registered lazily on the first view-context hook call (no context is
 *   available at onPackageLoaded time).
 * - The receiver does only cheap in-memory cache removal — never touches
 *   disk, never performs heavy work.
 * - Every failure path is isolated: a receiver error must never crash the
 *   launcher.
 *
 * Note: [Intent.ACTION_PACKAGE_REMOVED] fires twice for updates
 * (replacing/uninstalling flags) — invalidation is idempotent, so no dedup
 * is needed.
 */
object PackageChangeReceiver {

    private const val TAG = "MonoIcon.PackageChange"

    @Volatile
    private var registered = false

    /**
     * Registers the receiver with [context] (launcher process context).
     * Safe to call multiple times; only the first call registers.
     */
    fun register(context: Context) {
        if (registered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addDataScheme("package")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    handle(intent)
                }
            }
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            registered = true
            android.util.Log.i(TAG, "Package change receiver registered")
        } catch (t: Throwable) {
            // Isolation: registration failure must not affect launcher startup
            android.util.Log.w(TAG, "register failed: ${t.message}")
            registered = false
        }
    }

    private fun handle(intent: Intent?) {
        try {
            if (intent == null) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            CacheManager.invalidatePackage(pkg)
            android.util.Log.i(TAG, "invalidated pkg=$pkg action=${intent.action}")
        } catch (t: Throwable) {
            // Isolation: receiver errors must never crash the launcher
            android.util.Log.w(TAG, "handle failed: ${t.message}")
        }
    }
}
