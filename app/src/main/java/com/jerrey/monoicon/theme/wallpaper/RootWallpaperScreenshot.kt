package com.jerrey.monoicon.theme.wallpaper

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.OutputStream

/**
 * Standalone app_process entry point, invoked only by the app's root image worker.
 * AOSP IWindowManager.screenshotWallpaper() captures the wallpaper SurfaceControl's
 * layers, never a screenshot of app/launcher windows. OEM incompatibility returns no image.
 */
object RootWallpaperScreenshot {
    // This entry runs as root in app_process, after explicit hidden-API exemption.
    // OEMs that remove or refuse the wallpaper-only API produce no image.
    @SuppressLint("SoonBlockedPrivateApi", "PrivateApi")
    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() != 0 || args.size != 1) return
        val requestedUser = args[0].toIntOrNull()?.takeIf { it >= 0 } ?: return
        var hardware: Bitmap? = null
        var software: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/")
            val currentUserMethod = Class.forName("android.app.ActivityManager").getDeclaredMethod("getCurrentUser")
            val foregroundUser = currentUserMethod.invoke(null) as? Int ?: return
            // This API has no user argument. Never capture another user's visible wallpaper.
            if (foregroundUser != requestedUser) return
            val binder = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java).invoke(null, "window") as? IBinder ?: return
            val manager = Class.forName("android.view.IWindowManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return
            val contract = Class.forName("android.view.IWindowManager")
            val screenshot = contract.getDeclaredMethod("screenshotWallpaper")
            if (!Bitmap::class.java.isAssignableFrom(screenshot.returnType)) return
            hardware = screenshot.invoke(manager) as? Bitmap ?: return
            if (currentUserMethod.invoke(null) as? Int != requestedUser) return
            // The capture itself is a device-sized SurfaceControl result. Bound software readback
            // before copying an OEM result, then bound the bitmap sent back to the app.
            if (maxOf(hardware.width, hardware.height) > 8192 ||
                hardware.width.toLong() * hardware.height > 16_000_000L) return
            software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return
            val (width, height) = WallpaperImagePolicy.dimensions(software.width, software.height,
                WallpaperImagePolicy.MAX_IMAGE_EDGE, WallpaperImagePolicy.MAX_IMAGE_PIXELS)
            scaled = Bitmap.createScaledBitmap(software, width, height, true)
            scaled.compress(Bitmap.CompressFormat.PNG, 100, object : OutputStream() {
                private var written = 0
                override fun write(value: Int) {
                    check(++written <= WallpaperImagePolicy.MAX_ENCODED_BYTES)
                    System.out.write(value)
                }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    written += length
                    check(written <= WallpaperImagePolicy.MAX_ENCODED_BYTES)
                    System.out.write(buffer, offset, length)
                }
            })
            System.out.flush()
        } catch (_: Throwable) {
            // Empty/invalid output is rejected by the bounded decoder; no full-screen fallback.
        } finally {
            scaled?.let { if (it !== software) it.recycle() }
            software?.let { if (it !== hardware) it.recycle() }
            hardware?.recycle()
        }
    }
}
