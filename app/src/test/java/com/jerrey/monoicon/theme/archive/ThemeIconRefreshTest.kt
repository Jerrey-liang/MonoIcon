package com.jerrey.monoicon.theme.archive

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ThemeIconRefreshTest {
    class Api {
        fun resetIcons() {}
        companion object {
            @JvmStatic fun clearCustomizedIcons(pkg: String?) { check(pkg == null) }
            @JvmStatic fun getSystem() = Api()
            @JvmStatic fun sendThemeConfigurationChangeMsg(flags: Long) { check(flags == 8L) }
        }
    }

    @Test fun plainSuFallbackRequiresAnExplicitOptionErrorBeforeAnyWork() {
        val marker = "root shell entered"
        for (error in listOf("su: invalid option -- 'M'", "su: unrecognized option -M", "Unknown argument: --mount-master")) {
            assertTrue(ThemeRootMount.unsupportedOption(1, error, marker))
            assertFalse(ThemeRootMount.unsupportedOption(1, "$marker\n$error", marker))
            assertFalse(ThemeRootMount.unsupportedOption(0, error, marker))
        }
        assertFalse(ThemeRootMount.unsupportedOption(1, "Permission denied", marker))
        assertFalse(ThemeRootMount.unsupportedOption(2, "su: unknown option -x", marker))
    }

    @Test fun alternateResetAndBestEffortBroadcastDoNotHideConfigurationDelivery() {
        val calls = mutableListOf<String>()
        val errors = mutableMapOf<String, Throwable>()
        val ok = ThemeIconRefresh.request(
            broadcast = { throw IOException("receiver unavailable") },
            warn = { name, error -> errors[name] = error },
            resolve = { name ->
                calls += name
                if (name.endsWith(".ThemeResources")) throw ClassNotFoundException(name)
                Api::class.java
            },
        )
        assertTrue(ok)
        assertTrue(calls.indexOf("miui.content.res.ThemeResourcesSystem") > calls.indexOf("miui.content.res.ThemeResources"))
        assertTrue(errors.values.any { it is ClassNotFoundException })
        assertEquals("receiver unavailable", errors["ACTION_THEME_CHANGED broadcast"]?.message)
    }

    @Test fun broadcastAloneCannotAcknowledgeConfigurationAndPrimaryResetSkipsFallback() {
        var broadcast = false
        val calls = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val ok = ThemeIconRefresh.request(
            broadcast = { broadcast = true },
            warn = { name, _ -> errors += name },
            resolve = { name ->
                calls += name
                if (name.endsWith(".MiuiConfiguration")) throw ClassNotFoundException(name)
                Api::class.java
            },
        )
        assertFalse(ok)
        assertTrue(broadcast)
        assertFalse("miui.content.res.ThemeResourcesSystem" in calls)
        assertEquals(listOf("MiuiConfiguration.sendThemeConfigurationChangeMsg(8)"), errors)
    }
}
