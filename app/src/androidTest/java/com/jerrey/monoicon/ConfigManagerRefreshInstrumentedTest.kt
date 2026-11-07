package com.jerrey.monoicon

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.config.ConfigManager
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigManagerRefreshInstrumentedTest {
    private val values = ConcurrentHashMap<String, Any>()
    private val providerCalls = AtomicInteger()
    private val saved = mutableMapOf<String, Any?>()
    private val timestampFields = listOf(
        "lastRefreshMs", "lastLawniconsRefreshMs", "lastCircleRefreshMs",
    )
    private val cachedDefaults = mapOf(
        "cachedEnabled" to true,
        "hookSafetyBlocked" to false,
        "cachedLawniconsEnabled" to true,
        "cachedCircleIconsEnabled" to false,
    )

    @Before
    fun installRemotePreferences() {
        val provider = field("remotePrefsProvider")
        saved[provider.name] = provider.get(null)
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getBoolean", "getString" -> values[arguments!![0] as String] ?: arguments[1]
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
        val testProvider: () -> SharedPreferences? = {
            providerCalls.incrementAndGet()
            prefs
        }
        provider.set(null, testProvider)
        timestampFields.forEach { name ->
            val timestamp = field(name).get(null) as AtomicLong
            saved[name] = timestamp.get()
            expire(name)
        }
        cachedDefaults.forEach { (name, default) ->
            val cached = field(name).get(null) as AtomicBoolean
            saved[name] = cached.get()
            cached.set(default)
        }
        mapOf("lastLawniconsValue" to true, "lastCircleValue" to false)
            .forEach { (name, default) ->
                val previous = field(name)
                saved[name] = previous.get(null)
                previous.set(null, default)
            }
    }

    @After
    fun restoreConfiguration() {
        saved.forEach { (name, value) ->
            val target = field(name)
            val current = target.get(null)
            if (current is AtomicLong) current.set(value as Long)
            else if (current is AtomicBoolean) current.set(value as Boolean)
            else target.set(null, value)
        }
    }

    @Test
    fun concurrentHooksRefreshEachFlagOnce() {
        val readers = listOf(
            ConfigManager::isEnabled to true,
            ConfigManager::isLawniconsEnabled to true,
            ConfigManager::isCircleIconsEnabled to false,
        )
        val executor = Executors.newFixedThreadPool(8)
        try {
            readers.forEachIndexed { index, (read, expected) ->
                expire(timestampFields[index])
                providerCalls.set(0)
                val start = CountDownLatch(1)
                val results = List(32) {
                    executor.submit<Boolean> {
                        start.await()
                        read()
                    }
                }
                start.countDown()
                results.forEach { assertEquals(expected, it.get(5, TimeUnit.SECONDS)) }
                assertEquals("one preference read per refresh window", 1, providerCalls.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun safetyLatchOverridesEnabledPreferencesAndCacheRefresh() {
        values["enabled"] = true
        values["safety_blocked"] = true
        expire("lastRefreshMs")
        assertFalse(ConfigManager.isEnabled())
        values["safety_blocked"] = false
        expire("lastRefreshMs")
        assertTrue(ConfigManager.isEnabled())
        ConfigManager.disableForHooks()
        expire("lastRefreshMs")
        assertFalse("a blocked process requires a new process, not a preference refresh", ConfigManager.isEnabled())
    }

    @Test
    fun remoteFailureRetainsPreviouslyObservedSafetyDisable() {
        values["safety_blocked"] = true
        assertFalse(ConfigManager.isEnabled())
        val unavailable: () -> SharedPreferences? = { error("Binder unavailable") }
        field("remotePrefsProvider").set(null, unavailable)
        expire("lastRefreshMs")
        assertFalse(ConfigManager.isEnabled())
        val missing: () -> SharedPreferences? = { null }
        field("remotePrefsProvider").set(null, missing)
        expire("lastRefreshMs")
        assertFalse(ConfigManager.isEnabled())
    }

    @Test
    fun notificationIconsIgnoreLegacyOptOutWithoutReadingRemotePreferences() {
        values["notification_icons"] = false
        providerCalls.set(0)
        repeat(32) { assertTrue(ConfigManager.isNotificationIconsEnabled()) }
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun enabledRetainsItsOneSecondCacheAndThemeVariantRemainLive() {
        assertTrue(ConfigManager.isEnabled())
        values["enabled"] = false
        assertTrue("the current one-second window is retained", ConfigManager.isEnabled())
        expire("lastRefreshMs")
        assertFalse(ConfigManager.isEnabled())

        assertEquals("pixel_default", ConfigManager.getThemeId())
        assertEquals("tonal_spot", ConfigManager.getVariantId())
        values["theme_id"] = "test_theme"
        values["variant_id"] = "expressive"
        assertEquals("test_theme", ConfigManager.getThemeId())
        assertEquals("expressive", ConfigManager.getVariantId())
    }

    private fun expire(name: String) {
        (field(name).get(null) as AtomicLong).set(SystemClock.elapsedRealtime() - 1_000L)
    }

    private fun field(name: String) = ConfigManager::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
}
