package com.jerrey.monoicon.hook

import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/** ART callbacks never install a second native patch engine. Native launcher
 * adaptation belongs to native_init; existing Java/SystemUI hooks keep their path. */
internal object HyperOs4NativeHooks {
    fun onPackageReady(
        @Suppress("UNUSED_PARAMETER") api: XposedModule,
        param: XposedModuleInterface.PackageReadyParam,
        isLauncherProcess: Boolean,
    ) {
        if (isLauncherProcess && param.isFirstPackage && param.packageName == "com.miui.home" &&
            param.applicationInfo.flags and ApplicationInfo.FLAG_HAS_CODE == 0) {
            Log.i("MonoIcon.HyperOS4", "Native launcher uses the published Profile through LSPosed native_init")
        }
    }
}
