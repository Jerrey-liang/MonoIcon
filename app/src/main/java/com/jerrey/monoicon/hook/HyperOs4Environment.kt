package com.jerrey.monoicon.hook

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import java.util.concurrent.TimeUnit

/** The theme archive is a system-wide resource owned by Android user 0. */
internal object HyperOs4Environment {
    private val major = Regex("""^(?:OS|V)?(\d+)(?:\.|$)""", RegexOption.IGNORE_CASE)

    internal fun isVersion4(incremental: String, osVersion: String): Boolean {
        val firmware = incremental.takeIf { it.startsWith("OS", ignoreCase = true) }
        val version = firmware ?: osVersion
        return major.find(version.trim())?.groupValues?.get(1)?.toIntOrNull() == 4
    }

    private val deviceVersion4 by lazy {
        val incremental = Build.VERSION.INCREMENTAL
        if (incremental.startsWith("OS", ignoreCase = true)) isVersion4(incremental, "")
        else isVersion4(incremental, runCatching {
            val process = ProcessBuilder("/system/bin/getprop", "ro.mi.os.version.name").start()
            try {
                if (process.waitFor(500, TimeUnit.MILLISECONDS) && process.exitValue() == 0)
                    process.inputStream.bufferedReader().use { it.readLine().orEmpty() } else ""
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                process.outputStream.close()
                process.errorStream.close()
            }
        }.getOrDefault(""))
    }

    fun isVersion4() = deviceVersion4

    fun isSupported(context: Context): Boolean =
        Process.myUserHandle() == UserHandle.getUserHandleForUid(Process.SYSTEM_UID) &&
            isVersion4() && runCatching {
                context.packageManager.getApplicationInfo("com.miui.home", PackageManager.ApplicationInfoFlags.of(0))
            }.isSuccess
}
