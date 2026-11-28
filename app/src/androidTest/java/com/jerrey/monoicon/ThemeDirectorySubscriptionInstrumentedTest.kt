package com.jerrey.monoicon

import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.hook.ThemeDirectorySubscription
import com.jerrey.monoicon.theme.archive.LargeIconResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

@RunWith(AndroidJUnit4::class)
class ThemeDirectorySubscriptionInstrumentedTest {
    @Test fun tracksFirstUseAndThemeReplacementWithoutWatchingImageWrites() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val fixture = Files.createTempDirectory(cache.toPath(), "theme-observer-").toFile()
        val theme = File(fixture, "theme").also { check(it.mkdir()) }
        val thread = HandlerThread("Theme observer test").also { it.start() }
        val handler = Handler(thread.looper)
        val events = LinkedBlockingQueue<String>()
        val observer = ThemeDirectorySubscription(theme, handler, { events.add("icons") }, { events.add("large") })
        val started = CountDownLatch(1)
        try {
            handler.post { observer.start(); started.countDown() }
            assertTrue("Theme observer did not start", started.await(5, SECONDS))

            val initialLarge = File(theme, "large_icons").also { check(it.mkdir()) }
            expect(events, "large")
            val initialTree = File(fixture, "0")
            check(File(initialTree, "apps/desktop").mkdirs())
            rename(initialTree, File(initialLarge, "0"))
            expect(events, "large")
            publish(fixture, config(theme), "{}")
            expect(events, "large")
            publish(fixture, config(theme), "first replacement")
            expect(events, "large")
            publish(fixture, File(theme, "icons"), "ordinary icons")
            expect(events, "icons")

            val replacement = File(fixture, "replacement")
            populateLarge(File(replacement, "large_icons"))
            rename(theme, File(fixture, "previous"))
            expect(events, "icons", "large")
            rename(replacement, theme)
            expect(events, "icons", "large")

            publish(fixture, config(theme), "new theme replacement")
            expect(events, "large")
            publish(fixture, File(theme, "icons"), "new ordinary icons")
            expect(events, "icons")

            val desktop = config(theme).parentFile!!
            val packagePng = File(desktop, "com.example/2x2/res/drawable-nxhdpi/com.example.png")
            checkNotNull(packagePng.parentFile).mkdirs()
            publish(fixture, packagePng, "rendered PNG bytes")
            publish(fixture, File(desktop, "png-outside-package.png"), "other PNG bytes")
            assertNull("PNG writes must not schedule another sync", events.poll(600, MILLISECONDS))
            publish(fixture, config(theme), "config after image writes")
            expect(events, "large")
        } finally {
            handler.post { observer.stop() }
            thread.quitSafely()
            thread.join(5000)
            fixture.deleteRecursively()
        }
    }

    private fun expect(events: LinkedBlockingQueue<String>, vararg expected: String) {
        for (event in expected) assertEquals(event, events.poll(5, SECONDS))
    }

    private fun config(theme: File) = File(theme, "large_icons/0/apps/desktop/${LargeIconResources.CONFIG}")

    private fun populateLarge(directory: File) {
        val config = File(directory, "0/apps/desktop/${LargeIconResources.CONFIG}")
        checkNotNull(config.parentFile).mkdirs()
        config.writeText("{}")
    }

    private fun publish(fixture: File, live: File, contents: String) {
        val staged = Files.createTempFile(fixture.toPath(), "staged-", ".tmp").toFile()
        staged.writeText(contents)
        rename(staged, live)
    }

    private fun rename(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
}
