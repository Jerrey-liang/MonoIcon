package com.jerrey.monoicon.hook

import com.jerrey.monoicon.BuildConfig
import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import com.jerrey.monoicon.color.IconColorCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.color.IconColorExtractor
import com.jerrey.monoicon.color.PixelStyleColorExtractor
import com.jerrey.monoicon.identity.IdentityResolver
import com.jerrey.monoicon.image.MonochromeGenerator
import com.jerrey.monoicon.logging.LogcatLogger
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.logging.loge
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.mask.MaskGenerator
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MonoIcon.Hook"
private const val TAG_COLOR = "MonoIcon.Color"
private const val TAG_MASK = "MonoIcon.Mask"
private const val TAG_FOLDER = "MonoIcon.FolderLifecycle"
private const val MODULE_VERSION = "1.0.1"

/** Boot timestamp for relative timing logs (ms since boot approx). */
private val bootTimeNs: Long = System.nanoTime()
private fun relMs(): Long = (System.nanoTime() - bootTimeNs) / 1_000_000L

/**
 * MonoIcon — Modern LSPosed API 101 module entry point.
 *
 * Hooks 6 production methods in HyperOS Launcher (com.miui.home) to
 * intercept the icon loading pipeline (Phase 3.18-E: diagnostic
 * MonochromeUtils hooks 1–4 live in [DebugHooks], default off). All hooks
 * use the official libxposed interceptor-chain API.
 *
 * Pipeline (Phase 3.18): identity via [IdentityResolver], masks via
 * [MaskGenerator], caches via IconDrawableCache / IconColorCache /
 * MonochromeCache, launcher handoff copies via [MonochromeGenerator].
 *
 * Single entry class. No legacy adapters. No IXposedHookLoadPackage.
 * Listed in [META-INF/xposed/java_init.list].
 */
class IconThemeHook : XposedModule() {

    private val stats = HookStats(TAG)

    // Phase 3.11: Pixel-style icon color extraction（独立管线，不影响 mask 生成）
    private val colorExtractor = PixelStyleColorExtractor()

    // Phase 3.18-B: cached reflection handles (per declaring class)
    @Volatile
    private var cachedItemIconsField: java.lang.reflect.Field? = null

    /** Cached `getMBuddyInfo()` handle per view class (diagnostic log). */
    private val buddyMethodCache =
        ConcurrentHashMap<String, java.lang.reflect.Method>()

    // ═══════════════════════════════════════════════════════════════
    // Bootstrap
    // ═══════════════════════════════════════════════════════════════

    init {
        try {
            // Phase 3.18-A: 热路径日志默认关闭；debug 构建自动开启便于验证
            LogcatLogger.setDebugEnabled(BuildConfig.DEBUG)
            val pid = Process.myPid()
            android.util.Log.i(TAG, "═══ MonoIcon v$MODULE_VERSION loaded — PID=$pid SDK=${Build.VERSION.SDK_INT} debugLog=${BuildConfig.DEBUG} ═══")
        } catch (_: Throwable) { /* never crash */ }
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        android.util.Log.i(TAG, "onModuleLoaded: process=${param.processName} systemServer=${param.isSystemServer}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "com.miui.home") return

        val cl = param.getDefaultClassLoader()
        android.util.Log.i(TAG, "onPackageLoaded: package=${param.packageName} firstPackage=${param.isFirstPackage}")
        android.util.Log.i(TAG, "ClassLoader: $cl")

        installHooks(cl)
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook installation
    // ═══════════════════════════════════════════════════════════════

    private fun installHooks(cl: ClassLoader) {
        // Phase 3.18-E: diagnostic hooks 1–4 (MonochromeUtils) moved to
        // DebugHooks — production default off.
        DebugHooks.install(this, cl)

        // Phase 4.0-C: HookRegistry — stable names, required/optional flags,
        // availability reporting. Same protective install behavior as before.
        HookRegistry.install("DesktopIcon", required = true) { installSetIconDrawable(cl) }
        HookRegistry.install("FolderPreview", required = true) { installFolderSetImageDrawable(cl) }
        HookRegistry.install("RawIconProvider", required = true) { installGetActivityIcon(cl) }
        HookRegistry.install("SmallFolder", required = true) { installFolderSmallIconDrawable(cl) }
        HookRegistry.install("FolderIdentity", required = true) { installSetViewDrawable(cl) }
        // Kotlin synthetic lambda name — fragile across launcher builds → optional
        HookRegistry.install("FolderIdentity1x1", required = false) { installSetViewDrawable1x1(cl) }

        android.util.Log.i(TAG, "Hooks installed: ${HookRegistry.installedCount}/${HookRegistry.size}")
        android.util.Log.i(TAG, HookRegistry.statusReport())
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 5: ShortcutIcon.setIconDrawable(Drawable, Bitmap)
    //
    // Phase 2 架构调整后的唯一主入口：getMonochrome() 在 HyperOS 桌面
    // 图标加载中从不被调用（桌面图标是 FancyDrawable/BitmapDrawable，
    // 不走 AdaptiveIconDrawable 分支）。setIconDrawable 是每个图标
    // 显示时必定调用的方法，因此在拦截器中把 drawable 替换为生成的
    // monochrome 版本。
    //
    // packageName 通过反射 ShortcutIcon.getShortcutInfo().getPackageName()
    // 获取（链上已有：thisObject → getShortcutInfo → getPackageName）。
    // ═══════════════════════════════════════════════════════════════

    private fun installSetIconDrawable(cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.launcher.ShortcutIcon")
            .getDeclaredMethod("setIconDrawable", Drawable::class.java, Bitmap::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val start = System.nanoTime()

                // Phase 4.0-B: lazy package-change receiver registration
                ensurePackageChangeReceiver(chain.thisObject)

                // Phase 2.7: 生成逻辑整体 try/catch，任何异常回落原始 drawable
                val replacement = try {
                    processIconReplacement(chain)
                } catch (t: Throwable) {
                    // 绝不 crash launcher：记录后回落原始行为
                    android.util.Log.e(TAG, "[setIconDrawable] process failed, falling back: ${t.message}")
                    null
                }

                val result = if (replacement != null) {
                    val b = chain.getArg(1) as? Bitmap
                    chain.proceed(arrayOf<Any>(replacement, b ?: replacement.bitmap))
                } else {
                    chain.proceed()
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                // Phase 2.7: 降噪 — 仅记录替换与否，完整描述交给 HookStats 统计
                logd(TAG, "[setIconDrawable] replaced=${replacement != null} cost=${elapsed}ms")
                stats.record("setIconDrawable", elapsed)
                result
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════════
    // Hook 6: FolderPreviewIconView.refreshIconDrawable(Drawable)
    //
    // Phase 3.10: 直接替换参数 → chain.proceed(replacement)
    // ═══════════════════════════════════════════════════════════════

    private fun installFolderSetImageDrawable(cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.folder.FolderPreviewIconView")
            .getDeclaredMethod("refreshIconDrawable", Drawable::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                processFolderIcon(chain, "Folder6", "folderSetImageDrawable")
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 8: FolderIconPreviewContainer1X1$PreviewIconView.refreshIconDrawable
    //
    // 小文件夹预览（1x1 容器内的 PreviewIconView）。
    // 与 FolderPreviewIconView 共用相同的处理管线。
    // ═══════════════════════════════════════════════════════════════

    private fun installFolderSmallIconDrawable(cl: ClassLoader) {
        val method = cl.loadClass(
            "com.miui.home.folder.FolderIconPreviewContainer1X1\$PreviewIconView"
        ).getDeclaredMethod("refreshIconDrawable", Drawable::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                processFolderIcon(chain, "Folder8", null)
            }
    }

    // Phase 4.0-B: package change receiver (lazy, one-time registration)
    private val packageReceiverLock = Any()
    @Volatile
    private var packageReceiverRegistered = false

    /**
     * Lazily registers [PackageChangeReceiver] using the first available
     * view context. No context exists at onPackageLoaded time, so the
     * registration piggybacks on the first view-based hook invocation.
     * Silent no-op when the hook target is not a View (e.g. Hook 7).
     */
    private fun ensurePackageChangeReceiver(view: Any?) {
        if (packageReceiverRegistered) return
        val context = try {
            (view as? android.view.View)?.context
        } catch (_: Throwable) {
            null
        } ?: return
        synchronized(packageReceiverLock) {
            if (packageReceiverRegistered) return
            try {
                PackageChangeReceiver.register(context)
                packageReceiverRegistered = true
            } catch (_: Throwable) {
                // isolation — registration must never crash the launcher
            }
        }
    }

    /**
     * Returns "set" / "null" / "err" for the view's mBuddyInfo presence
     * (diagnostic log only). Method handle cached per view class.
     */
    private fun buddyInfoState(view: Any?): String {
        if (view == null) return "null"
        val clazz = view.javaClass
        val method = buddyMethodCache[clazz.name] ?: run {
            val m = try {
                clazz.getMethod("getMBuddyInfo")
            } catch (_: Throwable) {
                null
            } ?: return "err"
            buddyMethodCache[clazz.name] = m
            m
        }
        return try {
            if (method.invoke(view) != null) "set" else "null"
        } catch (_: Throwable) {
            "err"
        }
    }

    /**
     * Shared folder preview processing for Hook 6 (FolderPreviewIconView)
     * and Hook 8 (FolderIconPreviewContainer1X1$PreviewIconView).
     *
     * Phase 3.18-A: extracted from the two identical intercept bodies.
     * Behavior identical to Phase 3.17:
     * - identity resolution order: mBuddyInfo → drawable constantState → viewIdentityMap.remove
     * - mask priority: rawCached → toLuminanceMask(toRawBitmap(rawCached));
     *   else toBitmap(d); else renderGenericToMask(d)
     * - replacement via MonochromeGenerator.create; null-safe passthrough
     * - debug log lines keep the "[Folder6X]/[Folder8X]" prefix via [logPrefix]
     *
     * @param logPrefix Log tag prefix for debug output ("Folder6" / "Folder8").
     * @param statsName HookStats entry name; null disables stats recording (Hook 8).
     */
    private fun processFolderIcon(chain: Chain, logPrefix: String, statsName: String?): Any? {
        val start = if (statsName != null) System.nanoTime() else 0L
        val tMs = relMs()
        val viewHash = System.identityHashCode(chain.thisObject)

        // Phase 4.0-B: lazy package-change receiver registration
        ensurePackageChangeReceiver(chain.thisObject)

        try {
            val d = chain.getArg(0) as? Drawable
            val dClass = d?.javaClass?.simpleName ?: "null"

            // Phase 3.17: try mBuddyInfo → drawable constantState → viewIdentityMap (from Hook 9/10)
            val identity = IdentityResolver.resolveView(chain.thisObject)
                ?: d?.let { IdentityResolver.resolveDrawable(it) }
                ?: IdentityResolver.consumeView(viewHash)
            val cacheHit = if (identity != null) IconDrawableCache.get(identity) != null else false
            val mBuddyInfoNow = buddyInfoState(chain.thisObject)

            logd(TAG_FOLDER,
                "[${logPrefix}Enter] t=$tMs vh=@${Integer.toHexString(viewHash)} " +
                "dClass=$dClass identity=${identity ?: "NULL"} " +
                "cacheHit=$cacheHit mBuddyInfo=$mBuddyInfoNow")

            if (d == null) {
                logd(TAG_FOLDER, "[${logPrefix}Pass] t=$tMs vh=@${Integer.toHexString(viewHash)} reason=drawable_null")
                return chain.proceed()
            }

            // Phase 3.18-C: unified mask pipeline (RAW_APK > FG > LUMA, cached)
            val result = MaskGenerator.generate(d, identity, MaskGenerator.Priority.FOLDER)
            val mask = result.mask
            if (mask == null) {
                logw(TAG_FOLDER,
                    "[${logPrefix}Fail] t=$tMs vh=@${Integer.toHexString(viewHash)} " +
                    "identity=${identity ?: "NULL"} reason=mask_null")
                return chain.proceed()
            }

            // Phase 3.18-D: hand the launcher a private copy (cache bitmap never shared)
            val replacement = MonochromeGenerator.createForLauncher(mask)
            if (replacement == null) {
                logw(TAG_FOLDER,
                    "[${logPrefix}Fail] t=$tMs vh=@${Integer.toHexString(viewHash)} reason=replacement_null")
                return chain.proceed()
            }

            logd(TAG_FOLDER,
                "[${logPrefix}Replace] t=$tMs vh=@${Integer.toHexString(viewHash)} " +
                "identity=${identity ?: "NULL"} " +
                "original=$dClass " +
                "replacement=${replacement.javaClass.simpleName} " +
                "rawCached=${result.rawUsed} " +
                "maskW=${mask.width} maskH=${mask.height}")
            return chain.proceed(arrayOf<Any>(replacement))
        } catch (t: Throwable) {
            loge(TAG_FOLDER,
                "[${logPrefix}Crash] t=$tMs vh=@${Integer.toHexString(viewHash)} " +
                "error=${t.message}", t)
            return chain.proceed()
        } finally {
            if (statsName != null) {
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                stats.record(statsName, elapsed)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 9: BaseFolderIconPreviewContainer2X2.setViewDrawable
    //
    // Phase 3.17: Primary fix for folder preview lifecycle.
    // setViewDrawable receives IShortcutInfo BEFORE calling setImageDrawable,
    // but the internal sequence is:
    //   1. drawable.setColorFilter(iShortcutInfo.getColorFilter())
    //   2. folderPreviewIconView.setImageDrawable(drawable)  ← our hook 6/8 fires
    //   3. folderPreviewIconView.setMBuddyInfo(iShortcutInfo)
    //
    // This hook intercepts BEFORE step 1 so we can resolve identity from
    // iShortcutInfo directly. We replace the drawable argument with the
    // raw APK AdaptiveIconDrawable from IconDrawableCache, which Hook 6/8
    // then renders into a proper luminance mask.
    // ═══════════════════════════════════════════════════════════════

    private fun installSetViewDrawable(cl: ClassLoader) {
        val iShortcutInfoClass = cl.loadClass("com.miui.home.data.IShortcutInfo")
        val folderPreviewIconViewClass = cl.loadClass("com.miui.home.folder.FolderPreviewIconView")
        val method = cl.loadClass("com.miui.home.folder.BaseFolderIconPreviewContainer2X2")
            .getDeclaredMethod("setViewDrawable",
                iShortcutInfoClass,
                folderPreviewIconViewClass,
                Drawable::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val tMs = relMs()
                try {
                    // Phase 4.0-B: lazy package-change receiver registration
                    ensurePackageChangeReceiver(chain.thisObject)

                    val si = chain.getArg(0)  // IShortcutInfo
                    val d = chain.getArg(2) as? Drawable
                    val dClass = d?.javaClass?.simpleName ?: "null"
                    val identity = IdentityResolver.resolveShortcutInfo(si)
                    val rawCached = if (identity != null) IconDrawableCache.get(identity) else null

                    logd(TAG_FOLDER,
                        "[ViewDrawable] t=$tMs identity=${identity ?: "NULL"} " +
                        "originalDrawable=$dClass rawCached=${rawCached != null} " +
                        "cacheSize=${IconDrawableCache.size}")

                    if (rawCached != null && identity != null) {
                        logd(TAG_FOLDER,
                            "[ViewDrawableReplace] t=$tMs identity=$identity " +
                            "replacing $dClass with ${rawCached.javaClass.simpleName}")
                        // Phase 3.17: store identity for Hook 6/8 to find
                        // (replacing drawable loses LayerAdaptiveIconDrawable constantState ComponentName)
                        val view = chain.getArg(1)
                        if (view != null) {
                            IdentityResolver.bindView(System.identityHashCode(view), identity)
                        }
                        // Pass all three args, replacing only the drawable
                        return@intercept chain.proceed(
                            arrayOf<Any>(chain.getArg(0), chain.getArg(1), rawCached))
                    }

                    // Cache miss — let original through, Hook 6/8 will fallback
                    chain.proceed()
                } catch (t: Throwable) {
                    loge(TAG_FOLDER,
                        "[ViewDrawableCrash] t=$tMs error=${t.message}", t)
                    chain.proceed()
                }
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 10: FolderIconPreviewContainer1X1.loadItemIcons$lambda$0
    //
    // Phase 3.17: 1x1 (small folder) equivalent of Hook 9.
    // The 1x1 container does NOT use setViewDrawable — it calls
    // refreshIconDrawable directly on PreviewIconView items.
    // loadItemIcons$lambda$0 is the Kotlin synthetic that receives
    // IShortcutInfo (identity) + Drawable before refreshIconDrawable.
    //
    // Signature: (IShortcutInfo, FolderIconPreviewContainer1X1, int, Drawable)
    //   arg 0 = IShortcutInfo → identity source
    //   arg 1 = container → to access mItemIcons[i]
    //   arg 2 = index i
    //   arg 3 = Drawable → to replace
    // ═══════════════════════════════════════════════════════════════

    private fun installSetViewDrawable1x1(cl: ClassLoader) {
        val iShortcutInfoClass = cl.loadClass("com.miui.home.data.IShortcutInfo")
        val containerClass = cl.loadClass("com.miui.home.folder.FolderIconPreviewContainer1X1")
        // Synthetic method: loadItemIcons$lambda$0 — escape $ for Kotlin string interpolation
        val methodName = "loadItemIcons\$lambda\$0"
        val method = containerClass.getDeclaredMethod(methodName,
            iShortcutInfoClass, containerClass, Integer.TYPE, Drawable::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val tMs = relMs()
                try {
                    // Phase 4.0-B: lazy package-change receiver registration
                    ensurePackageChangeReceiver(chain.thisObject)

                    val si = chain.getArg(0)  // IShortcutInfo
                    val container = chain.getArg(1)
                    val idx = chain.getArg(2) as? Int ?: 0
                    val d = chain.getArg(3) as? Drawable
                    val dClass = d?.javaClass?.simpleName ?: "null"
                    val identity = IdentityResolver.resolveShortcutInfo(si)
                    val rawCached = if (identity != null) IconDrawableCache.get(identity) else null

                    logd(TAG_FOLDER,
                        "[ViewDrawable1x1] t=$tMs idx=$idx identity=${identity ?: "NULL"} " +
                        "originalDrawable=$dClass rawCached=${rawCached != null} " +
                        "cacheSize=${IconDrawableCache.size}")

                    if (rawCached != null && identity != null) {
                        // Get the target PreviewIconView to store identity
                        try {
                            val itemIconsField = cachedItemIconsField ?: run {
                                containerClass.getDeclaredField("mItemIcons").also {
                                    it.isAccessible = true
                                    cachedItemIconsField = it
                                }
                            }
                            val itemIcons = itemIconsField.get(container) as? Array<*>
                            if (itemIcons != null && idx < itemIcons.size) {
                                val view = itemIcons[idx]
                                if (view != null) {
                                    IdentityResolver.bindView(System.identityHashCode(view), identity)
                                }
                            }
                        } catch (_: Throwable) { }

                        logd(TAG_FOLDER,
                            "[ViewDrawable1x1Replace] t=$tMs identity=$identity " +
                            "replacing $dClass with ${rawCached.javaClass.simpleName}")
                        return@intercept chain.proceed(
                            arrayOf<Any>(chain.getArg(0), chain.getArg(1), chain.getArg(2), rawCached))
                    }

                    chain.proceed()
                } catch (t: Throwable) {
                    loge(TAG_FOLDER,
                        "[ViewDrawable1x1Crash] t=$tMs error=${t.message}", t)
                    chain.proceed()
                }
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hook 7: IconProvider.getActivityIcon(LauncherActivityInfo)
    //
    // Phase 3.15: 在图标加载阶段提取原始色彩。
    // 此时 AdaptiveIconDrawable 还保留着 APK 中的完整颜色，
    // HyperOS 的 theme 处理尚未将其替换为 monochrome mask。
    // ═══════════════════════════════════════════════════════════════

    private fun installGetActivityIcon(cl: ClassLoader) {
        val method = cl.loadClass("com.miui.home.icon.IconProvider")
            .getDeclaredMethod("getActivityIcon", LauncherActivityInfo::class.java)
        deoptimize(method)

        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val tMs = relMs()
                val info = chain.getArg(0) as? LauncherActivityInfo
                // 在 HyperOS 处理之前，从 APK 直接获取原始彩色图标
                // launcherActivityInfo.getIcon(0) 返回未经 theme 修改的 AdaptiveIconDrawable
                if (info != null) {
                    try {
                        val rawIcon = info.getIcon(0)
                        if (rawIcon != null) {
                            // mutate() 创建隔离副本 — HyperOS 会在构造
                            // LayerAdaptiveIconDrawable 时原地修改 foreground。
                            // 没有 mutate(), our cached reference 会共享 HyperOS 的修改。
                            val isolated = rawIcon.constantState?.newDrawable()?.mutate()
                                ?: rawIcon.mutate()
                            val cn = info.componentName
                            if (cn != null) {
                                val ident = "${cn.packageName}/${cn.className}"
                                // Phase 3.16-A: cache isolated raw drawable for mask generation
                                IconDrawableCache.put(ident, isolated)
                                logd(TAG_FOLDER,
                                    "[CachePut] t=$tMs component=$ident " +
                                    "drawable=${isolated.javaClass.simpleName} " +
                                    "cacheSize=${IconDrawableCache.size}")
                            }
                            extractEarlyIconColor(info, isolated, "RawAPK")
                        }
                    } catch (_: Throwable) { }
                }
                chain.proceed()
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 3.15: 早期颜色提取（IconProvider 加载阶段）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从刚加载完成的图标 drawable 中提取代表色并存入 [IconColorCache]。
     *
     * 此时 [drawable] 可能是：
     * - AdaptiveIconDrawable（保留完整色彩，尚未被 HyperOS theme 覆盖）
     * - BitmapDrawable
     * - 其他
     *
     * @param info LauncherActivityInfo（用于获取组件名）。
     * @param drawable 刚加载的图标（完整色彩）。
     */
    private fun extractEarlyIconColor(info: LauncherActivityInfo, drawable: Drawable, sourceLabel: String = "Adaptive") {
        try {
            val cn = info.componentName ?: return
            val component = "${cn.packageName}/${cn.className}"

            val (colorBitmap, srcLabel) = when {
                drawable is AdaptiveIconDrawable -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    Pair(bmp, sourceLabel)
                }
                drawable is BitmapDrawable -> {
                    Pair(drawable.bitmap, "Bitmap")
                }
                else -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    Pair(bmp, "Canvas")
                }
            }

            if (colorBitmap != null && !colorBitmap.isRecycled) {
                val extractedColor = colorExtractor.extractDominantColor(colorBitmap)
                IconColorCache.put(component, extractedColor)
                logd(TAG_COLOR,
                    "[EarlyColorExtract] component=$component " +
                    "drawable=${drawable.javaClass.simpleName} " +
                    "color=0x${extractedColor.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                    "source=$srcLabel " +
                    "width=${colorBitmap.width} " +
                    "height=${colorBitmap.height}")
                if (drawable !is BitmapDrawable) {
                    colorBitmap.recycle()
                }
            }
        } catch (t: Throwable) {
            logw(TAG_COLOR, "[EarlyColorExtractFail] component=${info.componentName} reason=${t.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 3.16: Mask input diagnostics
    // ═══════════════════════════════════════════════════════════════

    /** 记录生成的 mask bitmap 质量信息。 */
    private fun diagMaskRender(mask: Bitmap, identity: String, source: Int) {
        // Phase 3.18-A: 诊断采样成本较高，日志关闭时整体跳过
        if (!LogcatLogger.isDebugEnabled) return
        val w = mask.width
        val h = mask.height
        var alpha255 = 0
        var nonZeroAlpha = 0
        var rgbNonZero = 0
        var lumSum = 0L
        val step = maxOf(1, minOf(w, h) / 16)
        var sampleCount = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = mask.getPixel(x, y)
                val a = (p shr 24) and 0xFF
                if (a == 255) alpha255++
                if (a > 0) {
                    nonZeroAlpha++
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    if ((r or g or b) != 0) rgbNonZero++
                    lumSum += ((0.299 * r + 0.587 * g + 0.114 * b).toInt())
                }
                sampleCount++
                x += step
            }
            y += step
        }
        val avgLum = if (nonZeroAlpha > 0) lumSum / nonZeroAlpha else 0L
        val center = mask.getPixel(w / 2, h / 2)
        val corner = mask.getPixel(0, 0)
        val srcLabel = when (source) {
            SOURCE_NATIVE -> "NATIVE"
            SOURCE_FOREGROUND -> "FOREGROUND"
            else -> "LUMA"
        }
        logd(TAG_MASK,
            "[MaskRender] package=$identity source=$srcLabel " +
            "w=$w h=$h alpha255=$alpha255 nonZeroAlpha=$nonZeroAlpha " +
            "rgbNonZero=$rgbNonZero avgLum=$avgLum " +
            "center=0x${Integer.toHexString(center)} " +
            "corner=0x${Integer.toHexString(corner)} " +
            "sampleCount=$sampleCount")
    }

    /** 记录进入 mask 生成管线的 drawable 结构信息。 */
    private fun diagMaskInput(drawable: Drawable, identity: String) {
        // Phase 3.18-A: 诊断反射成本较高，日志关闭时整体跳过
        if (!LogcatLogger.isDebugEnabled) return
        try {
            val cls = drawable.javaClass.name
            val bounds = drawable.bounds
            val sb = StringBuilder()
            sb.append("[MaskInput] package=$identity ")
            sb.append("drawable=$cls ")
            sb.append("intrinsicW=${drawable.intrinsicWidth} intrinsicH=${drawable.intrinsicHeight} ")
            sb.append("bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] ")

            if (drawable is AdaptiveIconDrawable) {
                val bg = drawable.background
                val fg = drawable.foreground
                sb.append("bg=${bg?.javaClass?.simpleName ?: "null"} ")
                sb.append("fg=${fg?.javaClass?.simpleName ?: "null"} ")
                if (bg != null) {
                    sb.append("bgW=${bg.intrinsicWidth} bgH=${bg.intrinsicHeight} ")
                }
                if (fg != null) {
                    sb.append("fgW=${fg.intrinsicWidth} fgH=${fg.intrinsicHeight} ")
                }

                // LayerAdaptiveIconDrawable specific diagnostics
                val lcls = "com.miui.home.common.drawable.LayerAdaptiveIconDrawable"
                if (cls == lcls) {
                    try {
                        val getBgLayer = drawable.javaClass.getMethod("getBackgroundLayer")
                        val bgLayer = getBgLayer.invoke(drawable)
                        if (bgLayer != null) {
                            val getBgDrawable = bgLayer.javaClass.getMethod("getDrawable")
                            val bgLayerDrawable = getBgDrawable.invoke(bgLayer) as? Drawable
                            sb.append("layerBg=${bgLayerDrawable?.javaClass?.simpleName ?: "null"} ")
                        }
                        val getFgLayers = drawable.javaClass.getMethod("getForegroundLayers")
                        val fgLayers = getFgLayers.invoke(drawable) as? List<*>
                        sb.append("fgLayerCount=${fgLayers?.size ?: 0} ")
                    } catch (_: Throwable) { }
                }
            }

            logd(TAG_MASK, sb.toString())
        } catch (_: Throwable) { }
    }

    private fun processIconReplacement(chain: Chain): BitmapDrawable? {
        val d = chain.getArg(0) as? Drawable ?: return null
        val identity = IdentityResolver.resolve(chain.thisObject)

        // Phase 3.16: Mask quality diagnostics — log drawable structure before conversion
        diagMaskInput(d, identity)

        // Phase 3.12-A: 在 mask 生成之前提取原始图标颜色
        extractOriginalIconColor(d, identity)

        // Phase 3.18-C: unified mask pipeline (NATIVE > RAW_APK > FG > LUMA, cached)
        val result = MaskGenerator.generate(d, identity, MaskGenerator.Priority.DESKTOP)
        val maskBitmap = result.mask ?: return null

        // Phase 3.16: log mask bitmap quality
        diagMaskRender(maskBitmap, identity, result.source)

        // Phase 3.18-D: hand the launcher a private copy (cache bitmap never shared)
        return MonochromeGenerator.createForLauncher(maskBitmap)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════════
    // Phase 3.14: 从 LayerAdaptiveIconDrawable 背景层提取原始色彩
    // ═══════════════════════════════════════════════════════════════

    /**
     * 当背景层为透明 ColorDrawable（monochrome 模式覆盖）时，
     * 从 LayerState 获取构造时保存的原始背景 drawable。
     * LayerState 在 LayerAdaptiveIconDrawable 构造时保存了原始 drawable 引用。
     */
    private fun extractFromLayerState(drawable: Drawable, identity: String): ExtractResult? {
        try {
            val cs = drawable.constantState ?: return null
            // LayerState 保存了 mBackground / mForeground / mBadge
            val bgField = cs.javaClass.getDeclaredField("mBackground")
            bgField.isAccessible = true
            val origBg = bgField.get(cs) as? Drawable ?: return null

            val w = origBg.intrinsicWidth.coerceAtLeast(1)
            val h = origBg.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            origBg.setBounds(0, 0, w, h)
            origBg.draw(canvas)
            val color = colorExtractor.extractDominantColor(bmp)
            bmp.recycle()

            logd(TAG_COLOR,
                "[LayerAdaptiveColor] package=$identity " +
                "layerStateBg=${origBg.javaClass.simpleName} " +
                "color=0x${color.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "source=LayerState renderWidth=$w renderHeight=$h")

            return ExtractResult(color, "LayerState", w, h)
        } catch (t: Throwable) {
            logw(TAG_COLOR, "[LayerAdaptiveColor] LayerState extraction failed: ${t.message}")
            return null
        }
    }

    /**
     * 当背景层为透明 ColorDrawable（monochrome 模式覆盖）时，
     * 从前景层获取图标 artwork 并提取色彩。
     *
     * @return ExtractResult with color from foreground drawable, or null.
     */
    private fun extractFromForegroundLayers(drawable: Drawable, identity: String): ExtractResult? {
        try {
            val getFgMethod = drawable.javaClass.getMethod("getForegroundLayers")
            val fgLayers = getFgMethod.invoke(drawable) as? List<*> ?: return null
            if (fgLayers.isEmpty()) return null

            // 取第一个前景层
            val firstLayer = fgLayers[0] ?: return null
            val getDrawableMethod = firstLayer.javaClass.getMethod("getDrawable")
            val fgDrawable = getDrawableMethod.invoke(firstLayer) as? Drawable ?: return null

            val w = fgDrawable.intrinsicWidth.coerceAtLeast(1)
            val h = fgDrawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            fgDrawable.setBounds(0, 0, w, h)
            fgDrawable.draw(canvas)
            val color = colorExtractor.extractDominantColor(bmp)
            bmp.recycle()

            logd(TAG_COLOR,
                "[LayerAdaptiveColor] package=$identity " +
                "layerFg=${fgDrawable.javaClass.simpleName} " +
                "color=0x${color.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "source=Foreground renderWidth=$w renderHeight=$h")

            return ExtractResult(color, "Foreground", w, h)
        } catch (t: Throwable) {
            logw(TAG_COLOR, "[LayerAdaptiveColor] foreground extraction failed: ${t.message}")
            return null
        }
    }

    /**
     * 从 [LayerAdaptiveIconDrawable] 的背景层提取图标代表色。
     *
     * HyperOS 将原始 AdaptiveIconDrawable 存入 LayerAdaptiveIconDrawable 的
     * mBackgroundLayer。渲染整个 LayerAdaptiveIconDrawable 会产生黑色（因为
     * monochrome foreground mask 覆盖在背景之上），所以必须直接访问背景层。
     *
     * @return ARGB color int，或 null 表示不是 LayerAdaptiveIconDrawable。
     */
    private fun extractLayerAdaptiveColor(drawable: Drawable, identity: String): Int? {
        // 通过类名字符串检测（避免 instanceof，因为类在 launcher 私有 classloader 中）
        val className = drawable.javaClass.name
        if (className != LAYER_ADAPTIVE_CLASS) return null

        try {
            // 反射获取 backgroundLayer（public API: getBackgroundLayer()）
            val getBgMethod = drawable.javaClass.getMethod("getBackgroundLayer")
            val bgLayer = getBgMethod.invoke(drawable) ?: return null

            // 反射获取原始 drawable（public API: Layer.getDrawable()）
            val getDrawableMethod = bgLayer.javaClass.getMethod("getDrawable")
            val bgDrawable = getDrawableMethod.invoke(bgLayer) as? Drawable ?: return null

            // 根据背景 drawable 类型提取颜色
            val result: ExtractResult = when {
                bgDrawable is AdaptiveIconDrawable -> {
                    // 原始彩色 AdaptiveIconDrawable — 完整渲染获取色彩
                    val w = bgDrawable.intrinsicWidth.coerceAtLeast(1)
                    val h = bgDrawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    bgDrawable.setBounds(0, 0, w, h)
                    bgDrawable.draw(canvas)
                    val color = colorExtractor.extractDominantColor(bmp)
                    bmp.recycle()
                    ExtractResult(color, "Adaptive", w, h)
                }
                bgDrawable is ColorDrawable && (bgDrawable.color ushr 24) == 0 -> {
                    // ColorDrawable 背景透明 → monochrome 模式覆盖了颜色
                    // 尝试从 LayerState 获取原始背景（保存于构造时）
                    extractFromLayerState(drawable, identity)
                        ?: ExtractResult(bgDrawable.color, "ColorDrawable", 0, 0)
                }
                bgDrawable is ColorDrawable -> {
                    // ColorDrawable 有不透明颜色 → 系统主题色或 adaptive 背景色
                    ExtractResult(bgDrawable.color, "ColorDrawable", 0, 0)
                }
                else -> {
                    // 其他类型 — 尝试 Canvas 渲染
                    val w = bgDrawable.intrinsicWidth.coerceAtLeast(1)
                    val h = bgDrawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    bgDrawable.setBounds(0, 0, w, h)
                    bgDrawable.draw(canvas)
                    val color = colorExtractor.extractDominantColor(bmp)
                    bmp.recycle()
                    ExtractResult(color, "Fallback", w, h)
                }
            }

            logd(TAG_COLOR,
                "[LayerAdaptiveColor] package=$identity " +
                "layerBg=${bgDrawable.javaClass.simpleName} " +
                "color=0x${result.color.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "source=${result.source}" +
                (if (result.renderWidth > 0) " renderWidth=${result.renderWidth} renderHeight=${result.renderHeight}" else ""))

            return result.color
        } catch (t: Throwable) {
            logw(TAG_COLOR, "[LayerAdaptiveColor] failed for $identity: ${t.message}")
            return null
        }
    }

    /** 颜色提取结果数据类。 */
    private data class ExtractResult(
        val color: Int,
        val source: String,
        val renderWidth: Int,
        val renderHeight: Int
    )

    /**
     * 从原始 [Drawable]（未经 [DrawableConverter.toBitmap] 或 luminance mask 处理）
     * 中提取代表色。优先尝试 LayerAdaptive 背景层，回退到通用渲染。
     *
     * @param drawable 原始图标 Drawable（色彩未被销毁）。
     * @param identity 组件标识（用于日志）。
     */
    private fun extractOriginalIconColor(drawable: Drawable, identity: String) {
        try {
            // Phase 3.15 优先: 检查早期提取缓存
            val cachedColor = IconColorCache.get(identity)
            if (cachedColor != null) {
                logd(TAG_COLOR,
                    "[ColorCacheHit] component=$identity " +
                    "color=0x${cachedColor.toUInt().toString(16).uppercase().padStart(8, '0')}")
                return
            }

            // Phase 3.14: 从 LayerAdaptiveIconDrawable 背景层提取
            val layerColor = extractLayerAdaptiveColor(drawable, identity)
            if (layerColor != null) return

            // 回退: 通用渲染 → PixelStyleColorExtractor
            val (colorBitmap, sourceLabel) = when {
                drawable is AdaptiveIconDrawable -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    Pair(bmp, "Adaptive")
                }
                drawable is BitmapDrawable -> {
                    Pair(drawable.bitmap, "Bitmap")
                }
                else -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    Pair(bmp, "Canvas")
                }
            }

            if (colorBitmap != null && !colorBitmap.isRecycled) {
                val extractedColor = colorExtractor.extractDominantColor(colorBitmap)
                logd(TAG_COLOR,
                    "[ColorExtract] package=$identity " +
                    "drawable=${drawable.javaClass.simpleName} " +
                    "color=0x${extractedColor.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                    "source=$sourceLabel " +
                    "width=${colorBitmap.width} " +
                    "height=${colorBitmap.height}")
                if (drawable !is BitmapDrawable) {
                    colorBitmap.recycle()
                }
            }
        } catch (t: Throwable) {
            logw(TAG_COLOR, "[ColorExtract] failed for $identity: ${t.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════════

    companion object {
        // Phase 3 source constants
        const val SOURCE_NATIVE = 1
        const val SOURCE_FOREGROUND = 2
        const val SOURCE_LUMINANCE = 3

        // Phase 3.14: HyperOS LayerAdaptiveIconDrawable 全限定类名
        const val LAYER_ADAPTIVE_CLASS = "com.miui.home.common.drawable.LayerAdaptiveIconDrawable"
    }
}
