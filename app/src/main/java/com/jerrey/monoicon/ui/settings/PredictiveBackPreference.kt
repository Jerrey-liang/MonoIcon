// Adapted from KernelSUApplication (GPL-3.0-or-later), commit 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
package com.jerrey.monoicon.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Android reads this application flag when it creates the Activity's back dispatcher. */
object PredictiveBackPreference {
    fun apply(context: Context, enabled: Boolean): Boolean = runCatching {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
        val method = ApplicationInfo::class.java.getDeclaredMethod(
            "setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(context.applicationContext.applicationInfo, enabled)
    }.onFailure {
        android.util.Log.w("MonoIcon.Settings", "Cannot set predictive back flag", it)
    }.isSuccess
}
