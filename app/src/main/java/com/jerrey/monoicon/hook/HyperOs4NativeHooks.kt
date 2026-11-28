package com.jerrey.monoicon.hook

import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/** ART callbacks never install a second native patch engine. Native launcher
 * adaptation belongs to native_init; existing Java/SystemUI hooks keep their path. */
internal object HyperOs4NativeHooks {
    fun onPackageReady(
        api: XposedModule,
        param: XposedModuleInterface.PackageReadyParam,
        isLauncherProcess: Boolean,
    ) {
        if (isLauncherProcess && param.isFirstPackage && param.packageName == "com.miui.home" &&
            param.applicationInfo.flags and ApplicationInfo.FLAG_HAS_CODE == 0) {
            HookDiagnostics.start(api, "com.miui.home", "com.miui.home", nativeLauncher = true)
            Log.i("MonoIcon.HyperOS4", "Theme archive handles app icons; native_init is for optional large icons")
        }
    }
}
