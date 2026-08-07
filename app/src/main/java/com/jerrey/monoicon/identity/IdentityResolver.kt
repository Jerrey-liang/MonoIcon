package com.jerrey.monoicon.identity

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.logging.loge
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified identity resolution for the MonoIcon hook pipeline (Phase 3.18-B).
 *
 * Replaces the four ad-hoc resolvers previously embedded in IconThemeHook
 * (resolveIdentity / resolveIdentityFromShortcutInfo / resolveFolderIconIdentity /
 * extractIdentityFromDrawable) plus the viewIdentityMap timing bridge.
 *
 * ## Behavior contract (preserved from Phase 3.17)
 * - Identity string format: `"pkg/cls"` (package + full class name),
 *   falling back to `"pkg"` where the class is unavailable.
 * - [resolve] never returns null — unresolvable targets resolve to the
 *   literal `"unknown"` (icons with unknown identity are still replaced and
 *   cached under `"unknown|WxH@fp|src"`, matching Phase 3.17 exactly).
 * - [consumeView] removes the mapping on read — one-shot timing bridge
 *   (was `viewIdentityMap.remove(viewHash)`).
 * - All reflection Method/Field handles are resolved once per class and
 *   cached; no per-call getMethod/getDeclaredField/getDeclaredFields.
 *
 * Thread-safe: all caches are ConcurrentHashMap; stateless resolution.
 */
object IdentityResolver {

    private const val TAG = "MonoIcon.Identity"

    /** HyperOS LayerAdaptiveIconDrawable full class name. */
    private const val LAYER_ADAPTIVE_CLASS = "com.miui.home.common.drawable.LayerAdaptiveIconDrawable"

    // ── Timing bridge (was viewIdentityMap in IconThemeHook) ──────────

    private val viewIdentityMap = ConcurrentHashMap<Int, String>()

    /** Stores identity for a folder preview view before Hook 6/8 fires. */
    fun bindView(viewHash: Int, identity: String) {
        viewIdentityMap[viewHash] = identity
    }

    /** Removes and returns the identity bound to [viewHash] (one-shot). */
    fun consumeView(viewHash: Int): String? = viewIdentityMap.remove(viewHash)

    // ── Reflection handle caches (per declaring class) ────────────────

    /** Method handle cache keyed by `"ClassName#methodName"`. */
    private val methodCache = ConcurrentHashMap<String, java.lang.reflect.Method>()

    /**
     * ComponentName field cache keyed by ConstantState subclass.
     * HyperOS LayerState classes store the original ComponentName in a
     * declared field; the field differs per class, so it is cached per class.
     */
    private val componentFieldCache =
        ConcurrentHashMap<Class<*>, java.lang.reflect.Field>()

    /** ConstantState classes verified to have no ComponentName field. */
    private val noComponentFieldClasses =
        ConcurrentHashMap.newKeySet<Class<*>>()

    private fun methodOf(clazz: Class<*>, name: String): java.lang.reflect.Method? {
        val key = clazz.name + "#" + name
        val cached = methodCache[key]
        if (cached != null) return cached
        val method = try {
            clazz.getMethod(name)
        } catch (_: Throwable) {
            null
        } ?: return null
        methodCache[key] = method
        return method
    }

    /** Finds the declared ComponentName field of a ConstantState class. */
    private fun componentFieldOf(csClass: Class<*>): java.lang.reflect.Field? {
        if (noComponentFieldClasses.contains(csClass)) return null
        val cached = componentFieldCache[csClass]
        if (cached != null) return cached
        for (f in csClass.declaredFields) {
            if (f.type.name == "android.content.ComponentName") {
                componentFieldCache[csClass] = f
                return f
            }
        }
        noComponentFieldClasses.add(csClass)
        return null
    }

    /**
     * Extracts `"pkg/cls"` from a ConstantState holding a ComponentName.
     * Mirrors the Phase 3.17 fallback (extractIdentityFromDrawable) exactly.
     */
    private fun scanComponentField(cs: Any?): String? {
        if (cs == null) return null
        val field = componentFieldOf(cs.javaClass) ?: return null
        return try {
            field.isAccessible = true
            val cn = field.get(cs) as? android.content.ComponentName
            if (cn != null) "${cn.packageName}/${cn.className}" else null
        } catch (_: Throwable) {
            null
        }
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Resolves a hook target object (e.g. ShortcutIcon) to `"pkg/cls"`.
     *
     * Priority (preserved from Phase 3.17):
     * 1. getShortcutInfo() → getComponentName() → `"pkg/cls"`
     * 2. getShortcutInfo() → getClassName() → class name
     * 3. getShortcutInfo() → getPackageName() → `"pkg"`
     * 4. `"unknown"` — never null
     */
    fun resolve(target: Any?): String {
        if (target == null) return "unknown"
        return try {
            val shortcutInfo = methodOf(target.javaClass, "getShortcutInfo")
                ?.invoke(target) ?: return "unknown"

            // 1. getComponentName() → "$pkg/$cls"
            val component = methodOf(shortcutInfo.javaClass, "getComponentName")
                ?.invoke(shortcutInfo)
            if (component != null) {
                val cls = methodOf(component.javaClass, "getClassName")
                    ?.invoke(component) as? String ?: ""
                val pkg = methodOf(component.javaClass, "getPackageName")
                    ?.invoke(component) as? String ?: ""
                return "$pkg/$cls"
            }

            // 2. getClassName() (full class name)
            val className = methodOf(shortcutInfo.javaClass, "getClassName")
                ?.invoke(shortcutInfo) as? String
            if (!className.isNullOrBlank()) return className

            // 3. getPackageName()
            val pkg = methodOf(shortcutInfo.javaClass, "getPackageName")
                ?.invoke(shortcutInfo) as? String
            pkg ?: "unknown"
        } catch (t: Throwable) {
            loge(TAG, "[setIconDrawable] resolveIdentity failed: ${t.message}")
            "unknown"
        }
    }

    /**
     * Resolves identity from an IShortcutInfo argument (Hooks 9/10).
     *
     * Priority (preserved from Phase 3.17):
     * 1. getPackageName() + getComponentName().getClassName() → `"pkg/cls"`
     * 2. getPackageName() only → `"pkg"`
     * 3. null if even the package name is unavailable
     */
    fun resolveShortcutInfo(info: Any?): String? {
        if (info == null) return null
        return try {
            val pkg = methodOf(info.javaClass, "getPackageName")
                ?.invoke(info) as? String ?: return null
            val component = try {
                methodOf(info.javaClass, "getComponentName")?.invoke(info)
            } catch (_: Throwable) {
                null
            }
            if (component != null) {
                val cls = methodOf(component.javaClass, "getClassName")
                    ?.invoke(component) as? String
                if (cls != null) return "$pkg/$cls"
            }
            pkg
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves identity from a folder preview view (Hooks 6/8).
     *
     * Priority (preserved from Phase 3.17):
     * 1. getMBuddyInfo() → getPackageName() + getIntent().getComponent().getClassName()
     * 2. getDrawable() → LayerAdaptiveIconDrawable constantState ComponentName
     *
     * Returns null when neither tier succeeds (caller falls through to
     * [consumeView] via the Hook 9/10 timing bridge).
     */
    fun resolveView(view: Any?): String? {
        if (view == null) return null

        // Primary: getMBuddyInfo()
        try {
            val buddy = methodOf(view.javaClass, "getMBuddyInfo")?.invoke(view)
            if (buddy != null) {
                val pkg = methodOf(buddy.javaClass, "getPackageName")
                    ?.invoke(buddy) as? String
                if (pkg != null) {
                    // intent/component may be null → fall back to "pkg" (Phase 3.17)
                    val intent = methodOf(buddy.javaClass, "getIntent")?.invoke(buddy)
                    val component = intent?.let { methodOf(it.javaClass, "getComponent")?.invoke(it) }
                    val cls = component?.let {
                        methodOf(it.javaClass, "getClassName")?.invoke(it) as? String
                    }
                    return if (cls != null) "$pkg/$cls" else pkg
                }
            }
        } catch (_: Throwable) { /* fall through to drawable identity */ }

        // Fallback: ComponentName in LayerAdaptiveIconDrawable constantState
        try {
            val d = methodOf(view.javaClass, "getDrawable")?.invoke(view) as? Drawable
                ?: return null
            val className = d.javaClass.name
            if (className != LAYER_ADAPTIVE_CLASS) return null
            return scanComponentField(d.constantState)
        } catch (_: Throwable) { }

        return null
    }

    /**
     * Extracts identity from a drawable's constantState (Phase 3.17
     * extractIdentityFromDrawable — fallback tier 2b for the drawable
     * argument passed to refreshIconDrawable).
     */
    fun resolveDrawable(d: Drawable?): String? {
        if (d == null) return null
        return try {
            scanComponentField(d.constantState)
        } catch (_: Throwable) {
            null
        }
    }
}
