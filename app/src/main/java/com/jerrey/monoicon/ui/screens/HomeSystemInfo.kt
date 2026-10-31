// Device/system fields mirror KernelSU HomeViewModel, GPL-3.0-or-later.
package com.jerrey.monoicon.ui.screens

import android.os.Build
import android.system.Os
import com.jerrey.monoicon.BuildConfig
import java.util.concurrent.TimeUnit

internal data class HomeSystemInfo(
    val managerVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    val kernelVersion: String = "—",
    val deviceModel: String = "—",
    val fingerprint: String = Build.FINGERPRINT,
) {
    companion object {
        /** Read-only probes run on Dispatchers.IO; the home page never requests root. */
        fun load() = HomeSystemInfo(
            kernelVersion = runCatching { Os.uname().release }.getOrDefault("—"),
            deviceModel = resolveDeviceName(),
        )

        private fun resolveDeviceName(): String {
            val property = when (Build.MANUFACTURER.orEmpty().lowercase()) {
                "xiaomi" -> "ro.product.marketname"
                "oppo", "oneplus", "realme", "oplus" -> "ro.vendor.oplus.market.name"
                "vivo" -> "ro.vivo.market.name"
                "honor", "huawei" -> "ro.config.marketing_name"
                "zte", "nubia" -> "ro.vendor.product.ztename"
                else -> null
            }
            val marketName = property?.let { commandOutput("/system/bin/getprop", it) }
                ?.takeIf { it.isNotBlank() && it != "unknown" }
            if (marketName != null) return marketName
            return buildString {
                append(Build.MANUFACTURER)
                if (!Build.BRAND.equals(Build.MANUFACTURER, ignoreCase = true)) append(" ").append(Build.BRAND)
                append(" ").append(Build.MODEL)
            }
        }

        private fun commandOutput(vararg command: String): String? = runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) return@runCatching null
                process.inputStream.bufferedReader().use { it.readText().trim() }.takeIf { process.exitValue() == 0 }
            } finally {
                process.inputStream.close()
                process.outputStream.close()
                process.errorStream.close()
                process.destroy()
            }
        }.getOrNull()
    }
}
