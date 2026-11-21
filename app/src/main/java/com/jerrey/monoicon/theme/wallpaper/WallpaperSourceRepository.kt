package com.jerrey.monoicon.theme.wallpaper

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import com.jerrey.monoicon.logging.logw
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A published bitmap is immutable and repository owned. Consumers must never recycle it. */
data class WallpaperSnapshot(
    val bitmap: Bitmap?,
    val seedColor: Int,
    val provenance: WallpaperProvenance,
    val status: WallpaperStatus,
    val revision: Long,
    val detail: String,
    val imageDigest: String = "",
    val capturedAtMillis: Long = 0,
)

/**
 * One home-wallpaper image supplies the preview, seed and all Material palettes.
 * Image decoding, root access and quantization run exclusively on the serial worker.
 * App events/resume call [refresh]; render/composition reads only [state].
 */
object WallpaperSourceRepository {
    private const val MODULE = "com.jerrey.monoicon"
    internal const val AUTHORITY = "$MODULE.wallpaper.snapshot"
    private const val REFRESH_TTL_MS = 10_000L
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<(WallpaperSnapshot) -> Unit>()
    @Volatile private var workerThread: Thread? = null
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MonoIcon-Wallpaper").apply { isDaemon = true; workerThread = this }
    }
    private val mutableState = MutableStateFlow(WallpaperSnapshot(
        null, WallpaperImagePolicy.STABLE_FALLBACK_COLOR, WallpaperProvenance.STABLE_FALLBACK,
        WallpaperStatus.LOADING, 0, "Reading home wallpaper",
    ))
    val state = mutableState.asStateFlow()
    @Volatile private var appContext: Context? = null
    private var pending: CompletableFuture<WallpaperSnapshot>? = null
    private var dirty = false
    private var forceRequested = false
    private var lastRefreshAt = 0L
    private var localRecord: WallpaperCacheRecord? = null // worker owned
    private var diskRead = false // worker owned

    /** Idempotent in the app and injected processes. No binder, root or image work on the caller. */
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        val first = synchronized(lock) {
            if (appContext != null) false else {
                appContext = app
                true
            }
        }
        if (first) {
            registerObservers(app)
            refresh()
        }
    }

    /** Burst events coalesce to one running read and at most one pending read. */
    fun refresh(force: Boolean = false) { requestRefresh(force) }

    /** Invalidation retains the last usable image; it never makes icon draws read the wallpaper. */
    fun invalidate() = refresh(force = true)

    /** Publisher/loader workers can await the same request; main-thread callers always return immediately. */
    fun refreshAndAwait(context: Context, force: Boolean = false): WallpaperSnapshot {
        init(context)
        val future = requestRefresh(force)
        if (Looper.myLooper() == Looper.getMainLooper() || Thread.currentThread() === workerThread) return state.value
        return try {
            future?.get(12, TimeUnit.SECONDS) ?: state.value
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            state.value
        } catch (_: Exception) {
            state.value
        }
    }

    /** Notifications run on the wallpaper worker after a complete snapshot is available. */
    fun addListener(listener: (WallpaperSnapshot) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (WallpaperSnapshot) -> Unit) { listeners.remove(listener) }

    private fun requestRefresh(force: Boolean): CompletableFuture<WallpaperSnapshot>? = synchronized(lock) {
        if (appContext == null) return@synchronized null
        if (pending != null) {
            if (force) { dirty = true; forceRequested = true }
            return@synchronized pending
        }
        if (!force && lastRefreshAt != 0L && SystemClock.elapsedRealtime() - lastRefreshAt < REFRESH_TTL_MS) {
            return@synchronized CompletableFuture.completedFuture(state.value)
        }
        dirty = true
        forceRequested = force
        CompletableFuture<WallpaperSnapshot>().also {
            pending = it
            worker.execute(::drainRefreshes)
        }
    }

    private fun drainRefreshes() {
        while (true) {
            val request = synchronized(lock) {
                if (!dirty) {
                    pending?.complete(state.value)
                    pending = null
                    return
                }
                dirty = false
                val forced = forceRequested
                forceRequested = false
                appContext!! to forced
            }
            val previous = state.value
            mutableState.value = previous.copy(status = WallpaperStatus.LOADING, detail = "Reading home wallpaper")
            try {
                load(request.first, request.second)
            } catch (failure: Exception) {
                logw("MonoIcon.Wallpaper", "wallpaper retrieval failed: ${failure.javaClass.simpleName}")
                publishUnavailable(request.first, "Home wallpaper image is unavailable")
            } finally {
                synchronized(lock) { lastRefreshAt = SystemClock.elapsedRealtime() }
            }
        }
    }

    private fun load(context: Context, force: Boolean) {
        if (!diskRead) {
            diskRead = true
            localRecord = readCache(context)
            localRecord?.let { publishRecord(it, WallpaperStatus.CACHED, "Using saved home wallpaper image") }
        }
        if (context.packageName != MODULE) {
            // Only the app requests root. The provider verifies actual same-user caller UIDs.
            readPublishedSnapshot(context, force)?.let { record ->
                localRecord = record
                saveCache(context, record)
                val currentId = wallpaperId(context)
                val fallback = record.source == WallpaperProvenance.STABLE_FALLBACK
                val stale = currentId >= 0 && record.wallpaperId >= 0 && currentId != record.wallpaperId
                publishRecord(record, when {
                    fallback -> WallpaperStatus.UNAVAILABLE
                    stale -> WallpaperStatus.CACHED
                    else -> WallpaperStatus.READY
                }, when {
                    fallback -> "Home wallpaper unavailable; using stable fallback image"
                    stale -> "Current home wallpaper unavailable; using saved image"
                    else -> "Using app-retrieved home wallpaper image"
                })
                return
            }
        }
        val idBefore = wallpaperId(context)
        val image = readWallpaperFile(context) ?: if (context.packageName == MODULE) {
            readRootWallpaper(context) ?: readRootWallpaperScreenshot(context)
        } else null
        if (image != null) {
            val idAfter = wallpaperId(context)
            // Do not publish a bitmap obtained across an observed wallpaper replacement.
            if (idBefore >= 0 && idAfter >= 0 && idBefore != idAfter) {
                image.first.recycle()
                // A forced event may retry once; repeated replacements do not create an
                // unbounded automatic loop. The next real event/resume schedules another read.
                if (force) synchronized(lock) { dirty = true }
                publishUnavailable(context, "Home wallpaper changed while reading; retaining saved image")
                return
            }
            val bitmap = image.first
            try {
                val seed = seedFromBitmap(bitmap)
                if (seed == null) {
                    publishUnavailable(context, "Home wallpaper image has no visible pixels")
                    return
                }
                val png = ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
                val record = WallpaperCacheRecord(userId(), idAfter, System.currentTimeMillis(), seed,
                    image.second, WallpaperImagePolicy.digest(png), png)
                localRecord = record
                saveCache(context, record)
                publishRecord(record, WallpaperStatus.READY, when (image.second) {
                    WallpaperProvenance.ROOT_FILE -> "Home wallpaper image retrieved with root"
                    WallpaperProvenance.ROOT_WALLPAPER_SCREENSHOT -> "Captured the visible wallpaper layer with root"
                    else -> "Home wallpaper image retrieved through Android"
                }, bitmap)
            } finally {
                // Published images remain valid for retained Compose/Drawable references.
                if (state.value.bitmap !== bitmap) bitmap.recycle()
            }
        } else publishUnavailable(context, if (isLiveWallpaper(context))
            "Active live wallpaper frame cannot be read" else "Current home wallpaper image is unavailable")
    }

    private fun publishUnavailable(context: Context, reason: String) {
        val cached = localRecord
        if (cached != null && cached.source != WallpaperProvenance.STABLE_FALLBACK) {
            publishRecord(cached, WallpaperStatus.CACHED, "$reason; using saved image")
            return
        }
        val record = cached ?: stableFallbackRecord().also {
            localRecord = it
            saveCache(context, it)
        }
        publishRecord(record, WallpaperStatus.UNAVAILABLE, "$reason; using stable fallback image")
    }

    private fun publishRecord(record: WallpaperCacheRecord, status: WallpaperStatus, detail: String, readyBitmap: Bitmap? = null) {
        val previous = state.value
        val bitmap = readyBitmap ?: if (previous.imageDigest == record.imageDigest) previous.bitmap else decode(record.png)
        if (bitmap == null) return
        // Reject cache metadata not derived from the image beside it.
        if (readyBitmap == null && previous.imageDigest != record.imageDigest && seedFromBitmap(bitmap) != record.seedColor) {
            if (bitmap !== previous.bitmap) bitmap.recycle()
            throw IllegalArgumentException("Wallpaper seed/image mismatch")
        }
        val provenance = if (status == WallpaperStatus.CACHED) WallpaperProvenance.CACHED_IMAGE else record.source
        val changed = previous.imageDigest != record.imageDigest || previous.seedColor != record.seedColor
        val snapshot = WallpaperSnapshot(bitmap, record.seedColor, provenance, status,
            previous.revision + if (changed) 1 else 0, detail, record.imageDigest, record.capturedAtMillis)
        mutableState.value = snapshot
        if (changed) listeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    /** Sampling does not scale/recycle the caller's bitmap, including 1×1 and portrait images. */
    fun seedFromBitmap(bitmap: Bitmap): Int? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val (width, height) = WallpaperImagePolicy.dimensions(bitmap.width, bitmap.height,
            WallpaperImagePolicy.MAX_SEED_EDGE, WallpaperImagePolicy.MAX_SEED_EDGE * WallpaperImagePolicy.MAX_SEED_EDGE)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val sourceY = ((y + 0.5) * bitmap.height / height).toInt().coerceAtMost(bitmap.height - 1)
            for (x in 0 until width) {
                val sourceX = ((x + 0.5) * bitmap.width / width).toInt().coerceAtMost(bitmap.width - 1)
                pixels[y * width + x] = bitmap.getPixel(sourceX, sourceY)
            }
        }
        return WallpaperImagePolicy.seedFromPixels(pixels)
    }

    private fun readWallpaperFile(context: Context): Pair<Bitmap, WallpaperProvenance>? = runCatching {
        if (isLiveWallpaper(context)) return null
        WallpaperManager.getInstance(context).getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use { descriptor ->
            decodeDescriptor(context, descriptor)?.let { it to WallpaperProvenance.WALLPAPER_FILE }
        }
    }.getOrNull()

    private fun readRootWallpaper(context: Context): Pair<Bitmap, WallpaperProvenance>? {
        // A static file left by a previous wallpaper cannot represent the active live wallpaper.
        if (isLiveWallpaper(context)) return null
        val file = File.createTempFile("wallpaper-root-", ".image", cacheDirectory(context))
        val user = userId()
        val script = """
            for image in /data/system/users/$user/wallpaper /data/system/users/$user/wallpaper_orig; do
                if [ -f "${'$'}image" ]; then
                    size=${'$'}(wc -c < "${'$'}image")
                    if [ "${'$'}size" -gt 0 ] && [ "${'$'}size" -le ${WallpaperImagePolicy.MAX_ENCODED_BYTES} ]; then
                        exec dd if="${'$'}image" bs=65536 count=512 2>/dev/null
                    fi
                fi
            done
            exit 1
        """.trimIndent()
        var child: java.lang.Process? = null
        return try {
            child = ProcessBuilder("su", "-M", "-c", script)
                .redirectError(File("/dev/null")).redirectOutput(file).start()
            if (!child.waitFor(5, TimeUnit.SECONDS) || child.exitValue() != 0) return null
            if (file.length() !in 1..WallpaperImagePolicy.MAX_ENCODED_BYTES.toLong()) return null
            decodeFile(file)?.let { it to WallpaperProvenance.ROOT_FILE }
        } catch (_: Exception) {
            null
        } finally {
            child?.let {
                if (it.isAlive) it.destroyForcibly()
                runCatching { it.waitFor(1, TimeUnit.SECONDS) }
                runCatching { it.inputStream.close(); it.errorStream.close(); it.outputStream.close() }
            }
            file.delete()
        }
    }

    private fun readRootWallpaperScreenshot(context: Context): Pair<Bitmap, WallpaperProvenance>? {
        val file = File.createTempFile("wallpaper-layer-", ".png", cacheDirectory(context))
        val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
        val script = "exec env CLASSPATH='$apk' /system/bin/app_process /system/bin " +
            "com.jerrey.monoicon.theme.wallpaper.RootWallpaperScreenshot ${userId()}"
        var child: java.lang.Process? = null
        return try {
            child = ProcessBuilder("su", "-M", "-c", script)
                .redirectError(File("/dev/null")).redirectOutput(file).start()
            if (!child.waitFor(5, TimeUnit.SECONDS) || child.exitValue() != 0) return null
            if (file.length() !in 1..WallpaperImagePolicy.MAX_ENCODED_BYTES.toLong()) return null
            decodeFile(file)?.let { it to WallpaperProvenance.ROOT_WALLPAPER_SCREENSHOT }
        } catch (_: Exception) {
            null
        } finally {
            child?.let {
                if (it.isAlive) it.destroyForcibly()
                runCatching { it.waitFor(1, TimeUnit.SECONDS) }
                runCatching { it.inputStream.close(); it.errorStream.close(); it.outputStream.close() }
            }
            file.delete()
        }
    }

    private fun readPublishedSnapshot(context: Context, force: Boolean): WallpaperCacheRecord? = runCatching {
        val uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("home")
            .appendQueryParameter("force", if (force) "1" else "0").build()
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use {
                WallpaperCacheRecord.read(it, userId()).also(::validateRecord)
            }
        }
    }.getOrNull()

    private fun decodeDescriptor(context: Context, descriptor: ParcelFileDescriptor): Bitmap? {
        val file = File.createTempFile("wallpaper-api-", ".image", cacheDirectory(context))
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                FileOutputStream(file).use { WallpaperImagePolicy.copyBounded(input, it) }
            }
            decodeFile(file)
        } finally { file.delete() }
    }

    private fun decodeFile(file: File): Bitmap? = decodeBounded(
        { options -> BitmapFactory.decodeFile(file.path, options) },
    )
    private fun decode(bytes: ByteArray): Bitmap? = decodeBounded(
        { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) },
    )

    private fun decodeBounded(decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val dimensions = WallpaperImagePolicy.dimensions(bounds.outWidth, bounds.outHeight,
            WallpaperImagePolicy.MAX_IMAGE_EDGE, WallpaperImagePolicy.MAX_IMAGE_PIXELS)
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > WallpaperImagePolicy.MAX_IMAGE_PIXELS ||
            maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > WallpaperImagePolicy.MAX_IMAGE_EDGE) {
            if (sample > 1 shl 28) return null
            sample *= 2
        }
        val bitmap = decode(BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }) ?: return null
        if (bitmap.width <= dimensions.first && bitmap.height <= dimensions.second) return bitmap
        return Bitmap.createScaledBitmap(bitmap, dimensions.first, dimensions.second, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private fun stableFallbackRecord(): WallpaperCacheRecord {
        val bitmap = Bitmap.createBitmap(intArrayOf(WallpaperImagePolicy.STABLE_FALLBACK_COLOR), 1, 1, Bitmap.Config.ARGB_8888)
        return try {
            val png = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            WallpaperCacheRecord(userId(), -1, System.currentTimeMillis(), seedFromBitmap(bitmap)!!,
                WallpaperProvenance.STABLE_FALLBACK, WallpaperImagePolicy.digest(png), png)
        } finally { bitmap.recycle() }
    }

    internal fun cacheFile(context: Context): File = File(cacheDirectory(context), "home-v1.cache")
    private fun cacheDirectory(context: Context): File = File(
        context.createDeviceProtectedStorageContext().filesDir, "monoicon-wallpaper",
    ).apply { check(isDirectory || mkdirs()) }
    private fun readCache(context: Context): WallpaperCacheRecord? = runCatching {
        AtomicFile(cacheFile(context)).openRead().use { WallpaperCacheRecord.read(it, userId()) }.also(::validateRecord)
    }.getOrNull()

    private fun validateRecord(record: WallpaperCacheRecord) {
        val bitmap = decode(record.png) ?: throw IllegalArgumentException("Invalid cached wallpaper image")
        try { require(seedFromBitmap(bitmap) == record.seedColor) { "Wallpaper seed/image mismatch" } }
        finally { bitmap.recycle() }
    }

    private fun saveCache(context: Context, record: WallpaperCacheRecord) {
        runCatching {
            // Lock serializes app processes; AtomicFile commits image+metadata together.
            FileOutputStream(File(cacheDirectory(context), "write.lock"), true).channel.use { channel ->
                channel.lock().use {
                    val atomic = AtomicFile(cacheFile(context))
                    val output = atomic.startWrite()
                    try {
                        record.write(output)
                        atomic.finishWrite(output)
                    } catch (failure: Exception) {
                        atomic.failWrite(output)
                        throw failure
                    }
                }
            }
        }.onFailure { logw("MonoIcon.Wallpaper", "wallpaper cache unavailable: ${it.javaClass.simpleName}") }
    }

    private fun wallpaperId(context: Context): Int = runCatching {
        WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
    }.getOrDefault(-1)
    private fun isLiveWallpaper(context: Context): Boolean = runCatching {
        WallpaperManager.getInstance(context).wallpaperInfo != null
    }.getOrDefault(false)
    private fun userId(): Int = Process.myUid() / 100_000

    private fun registerObservers(context: Context) {
        val handler = Handler(Looper.getMainLooper())
        runCatching {
            WallpaperManager.getInstance(context).addOnColorsChangedListener({ _, which ->
                if (which and WallpaperManager.FLAG_SYSTEM != 0) invalidate()
            }, handler)
        }
        runCatching {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = invalidate()
            }, IntentFilter().apply {
                addAction(Intent.ACTION_WALLPAPER_CHANGED)
                addAction("miui.gallery.action.WALLPAPER_CHANGED")
                addAction("android.intent.action.UPDATE_DESKTOP_VIDEO_WALLPAPER")
            }, Context.RECEIVER_EXPORTED)
        }
    }
}
