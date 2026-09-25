package com.jerrey.monoicon.theme.mask

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.BuildConfig
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.logging.logd
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Lawnicons bundle 来源抽象（Phase 8）。
 *
 * 目前只有一个实现 [ApkAssetBundle]：读取模块自身 APK 内
 * `assets/lawnicons/`（由桌面端 MonetIconGenerator 的
 * `MonoIconBundleExporter` 生成：index.tsv + masks 目录下的 PNG + aliases.tsv）。
 * 该接口为将来的"运行时下载预渲染掩码包 / 外部目录"预留，掩码生成与缓存
 * 逻辑无需改动。
 */
interface LawniconsBundle {
    /** bundle 版本（index.tsv 首行的 `#bundle=`，例如 `v2.18.0`）。 */
    fun version(): String

    /** `pkg/cls` 或 `pkg` → drawable 名。 */
    fun index(): Map<String, String>

    /** `pkg` → drawable 名（手动别名，优先于 index）。 */
    fun aliases(): Map<String, String>

    /** 打开 `masks/<name>.png`；不存在返回 null。 */
    fun open(asset: String): InputStream?
}

/** 读取模块 APK 内 `assets/lawnicons/` 的实现。 */
internal class ApkAssetBundle(private val apkPath: String) : LawniconsBundle {

    private val versionValue: String
    private val indexMap: Map<String, String>
    private val aliasMap: Map<String, String>

    init {
        var parsedVersion = "unknown"
        val parsedIndex = HashMap<String, String>(1024)
        val parsedAliases = HashMap<String, String>(16)
        ZipFile(apkPath).use { zip ->
            zip.getEntry(INDEX_ENTRY)?.let { entry ->
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEachIndexed { lineIndex, raw ->
                        val line = raw.trim()
                        if (line.isEmpty()) return@forEachIndexed
                        if (lineIndex == 0 && line.startsWith("#bundle=")) {
                            parsedVersion = line.removePrefix("#bundle=").substringBefore('#').trim()
                            return@forEachIndexed
                        }
                        if (line.startsWith('#')) return@forEachIndexed
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@forEachIndexed
                        val key = line.substring(0, tab).trim()
                        val value = line.substring(tab + 1).trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) parsedIndex[key] = value
                    }
                }
            }
            zip.getEntry(ALIASES_ENTRY)?.let { entry ->
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith('#')) return@forEach
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@forEach
                        val key = line.substring(0, tab).trim()
                        val value = line.substring(tab + 1).trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) parsedAliases[key] = value
                    }
                }
            }
        }
        versionValue = parsedVersion
        indexMap = parsedIndex
        aliasMap = parsedAliases
    }

    override fun version(): String = versionValue

    override fun index(): Map<String, String> = indexMap

    override fun aliases(): Map<String, String> = aliasMap

    override fun open(asset: String): InputStream? {
        val zip = ZipFile(apkPath)
        val entry = zip.getEntry(MASKS_ENTRY_PREFIX + asset + ".png")
        if (entry == null) {
            zip.close()
            return null
        }
        val stream = zip.getInputStream(entry)
        // 包装成关流时同步关闭 ZipFile，避免文件句柄泄漏。
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                try {
                    stream.close()
                } finally {
                    zip.close()
                }
            }
        }
    }

    private companion object {
        const val INDEX_ENTRY = "assets/lawnicons/index.tsv"
        const val ALIASES_ENTRY = "assets/lawnicons/aliases.tsv"
        const val MASKS_ENTRY_PREFIX = "assets/lawnicons/masks/"
    }
}

/**
 * Lawnicons 掩码查找层（Phase 8 分层：NATIVE → LAWNICONS → AOSP → LUMA）。
 *
 * ## 几何约定（重要）
 * bundle 内的 PNG 由**原始 SVG**渲染而来（215×215，内容占画布约 67–83%），
 * 而 MonetIconGenerator 的最终图标画布是 **320×320**（`assets/clip.png`）——
 * 它把 215 的 PNG **按 1:1 居中贴进 320 画布**（`IconProcessor.GenerateIcon`），
 * 因此那边的字形占 tile = 源内容% × 215/320 ≈ **47–56%**（≈56.5% 上限）。
 *
 * 为与该观感（以及模块内启发式 AOSP 层的 56.5%）视觉统一，这里同样按
 * `CONTENT_SCALE = 215 / 320` 居中绘制：
 *
 * - 正确：`size × 0.6719` 居中 → 字形占 tile 约 47–56%；
 * - 错误 A：原尺寸直接铺满（1.0）→ 字形 67–83%，明显偏大；
 * - 错误 B：套用原生 mono 的 `InsetDrawable(−extraInset)`（×1.5 + 中心裁切）
 *   → 字形 101–125% 并被裁边（此前实测的"放大"缺陷）。
 *
 * 应用自带的 `<monochrome>` 层仍走 `extractPixelNativeMonochrome`（那类资源
 * 是按 adaptive 前景约定绘制的，×1.5 才正确）。
 *
 * 任何失败（未初始化、assets 缺失、解码失败、entry 缺失）都返回 null，
 * 由策略层继续走既有 AOSP 分支 —— 该层永远不能让图标消失。
 */
object LawniconsAssetSource {

    private const val TAG = "MonoIcon.Lawnicons"

    /**
     * 内容缩放：Lawnicons 渲染画布 215 ÷ MonetIconGenerator 图标画布 320。
     * 与 `IconProcessor.GenerateIcon` 的"215 PNG 居中贴进 320 画布"完全等价。
     */
    private const val CONTENT_SCALE = 215f / 320f

    @Volatile
    private var bundle: LawniconsBundle? = null

    @Volatile
    private var unavailable = false

    @Volatile
    private var initialized = false

    /** bundle 版本（未初始化/不可用时为 "none"）。 */
    fun version(): String = bundle?.version() ?: "none"

    /**
     * 懒初始化：由 [com.jerrey.monoicon.hook.IconThemeHook] 在拿到第一个
     * View context 时调用（与 `PixelMonetColorEngine.init` 同一位置）。
     * 多次调用幂等；失败只记一次日志并永久禁用该层。
     */
    fun init(context: Context?) {
        if (initialized || unavailable) return
        // A null context must not permanently disable the tier: the first
        // View-based hook may run before any context is reachable.
        if (context == null) return
        synchronized(this) {
            if (initialized || unavailable) return
            initialized = true
            try {
                val apkPath = resolveModuleApkPath(context)
                if (apkPath == null) {
                    unavailable = true
                    logd(TAG, "module apk path unavailable → tier disabled")
                    return
                }
                val loaded = ApkAssetBundle(apkPath)
                val entries = loaded.index().size
                if (entries == 0) {
                    unavailable = true
                    logd(TAG, "empty index → tier disabled (apk=$apkPath)")
                    return
                }
                bundle = loaded
                logd(
                    TAG,
                    "bundle=${loaded.version()} index=$entries aliases=${loaded.aliases().size} apk=$apkPath",
                )
            } catch (t: Throwable) {
                unavailable = true
                logd(TAG, "init failed → tier disabled: ${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    /**
     * 生成 pack 掩码；未命中/不可用时返回 null。
     *
     * @param identity `pkg/cls`（可退化为 `pkg` 或 `unknown`）
     * @param size     目标掩码边长（与 AOSP 分支的 `aospTargetSize` 一致）
     */
    fun lookupMask(identity: String?, size: Int): Bitmap? {
        if (unavailable) return null
        val loaded = bundle ?: return null
        return lookupMask(identity, size, loaded, MonochromeCache.shared)
    }

    /**
     * Bundle PNGs are immutable and independent of app resources, shape and
     * colours. Look up their rasterized masks before opening the APK or decoding
     * a PNG; the strategy's content fingerprint otherwise runs only afterwards.
     * Reuse the existing byte budget and clearAll lifecycle, and share aliases
     * that resolve to the same asset. Returned masks must be treated as read-only.
     */
    internal fun lookupMask(
        identity: String?,
        size: Int,
        loaded: LawniconsBundle,
        cache: MonochromeCache,
    ): Bitmap? {
        if (size <= 0) return null
        val asset = resolveAsset(identity, loaded.index(), loaded.aliases()) ?: return null
        val cacheKey = "lawnicons|${loaded.version()}|$asset|$size"
        cache.get(cacheKey)?.takeUnless { it.isRecycled }?.let { return it }
        val mask = decodeMask(asset, size) { loaded.open(it) } ?: return null
        cache.put(cacheKey, mask)
        return mask
    }

    /**
     * 纯函数入口（仪器测试用）：索引与读取器由调用方注入，无 Android 资产依赖。
     */
    internal fun lookupMask(
        identity: String?,
        size: Int,
        index: Map<String, String>,
        aliases: Map<String, String>,
        reader: (String) -> InputStream?,
    ): Bitmap? {
        if (size <= 0) return null
        val asset = resolveAsset(identity, index, aliases) ?: return null
        return decodeMask(asset, size, reader)
    }

    private fun decodeMask(
        asset: String,
        size: Int,
        reader: (String) -> InputStream?,
    ): Bitmap? {
        var source: Bitmap? = null
        return try {
            val input = reader(asset) ?: return null
            source = input.use { BitmapFactory.decodeStream(it) } ?: return null
            rasterize(BitmapDrawable(null, source), size)
        } catch (_: Throwable) {
            null
        } finally {
            source?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    /**
     * 把 bundle PNG 按 [CONTENT_SCALE] 居中绘制成 ALPHA_8 掩码：与
     * MonetIconGenerator（215 PNG 贴进 320 画布）及模块启发式层（字形约占
     * tile 56.5%）视觉统一。不做 `−extraInset` 扩张。
     */
    private fun rasterize(drawable: Drawable, size: Int): Bitmap? = try {
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        val drawSize = (size * CONTENT_SCALE).toInt().coerceIn(1, size)
        val offset = (size - drawSize) / 2
        drawable.setBounds(offset, offset, offset + drawSize, offset + drawSize)
        drawable.draw(canvas)
        mask
    } catch (_: Throwable) {
        null
    }

    /** 别名 → 组件级 → 包级；`unknown`/空身份不查。 */
    internal fun resolveAsset(
        identity: String?,
        index: Map<String, String>,
        aliases: Map<String, String>,
    ): String? {
        val id = identity?.trim().orEmpty()
        if (id.isEmpty() || id == "unknown") return null
        val slash = id.indexOf('/')
        val pkg = if (slash > 0) id.substring(0, slash) else id
        if (pkg.isEmpty() || pkg == "unknown") return null
        aliases[pkg]?.let { return it }
        if (slash > 0) index[id]?.let { return it }
        return index[pkg]
    }

    /**
     * 模块 APK 路径：优先 PackageManager（launcher 进程可查询已安装模块），
     * 回退类加载器的 code source。
     */
    private fun resolveModuleApkPath(context: Context?): String? {
        try {
            val pm = context?.packageManager
            if (pm != null) {
                val info = pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
                if (info.sourceDir.isNotEmpty()) return info.sourceDir
            }
        } catch (_: Throwable) {
            // 继续回退
        }
        return try {
            val location = javaClass.protectionDomain?.codeSource?.location ?: return null
            val path = java.io.File(location.toURI()).absolutePath
            if (path.endsWith(".apk", ignoreCase = true)) path else null
        } catch (_: Throwable) {
            null
        }
    }
}
