package com.jerrey.monoicon

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.hook.HyperOs4LargeIconInputs
import com.jerrey.monoicon.hook.HyperOs4PalettePublisher
import com.jerrey.monoicon.profile.ProfileRepository
import com.jerrey.monoicon.theme.color.dynamic.Material2025ColorEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Explicit, single-step device acceptance driver. It never launches HOME, takes
 * screenshots, kills a process, or changes launcher layout. Only the explicit
 * post-notification action posts a local test notification; it never grants permission.
 * Visual acceptance belongs to the operator and the saved screenshots/recording.
 *
 * Required: -e hyperos4Acceptance true
 * Actions: -e acceptanceAction snapshot|configure|restore|check-large
 * Fixture actions: prepare-profile|cleanup-profile|post-notification|cancel-notification
 * Optional: acceptanceEnabled/Circle/Lawnicons true|false; acceptanceVariant ID;
 * acceptanceProfile ID|auto; acceptanceHold true; acceptanceRequireAck true;
 * acceptanceFlush true; acceptanceBaseline NAME; acceptanceLabel NAME;
 * acceptanceLargePackage PACKAGE (check-large defaults to com.android.settings).
 *
 * configure restores its entry settings in finally unless acceptanceHold=true.
 * The first snapshot/configure saves an immutable baseline, so held scenarios can
 * survive test-process death and later restore with the same acceptanceBaseline.
 * All JSON reports and that baseline are under files/hyperos4-acceptance/.
 * prepare-profile imports an equivalent official r9 copy and reports its unique
 * profileFixture.id; use configure to select it. Select the official ID before
 * preparing if auto matching would become ambiguous. Restore, then cleanup-profile
 * removes only the owned copy and republishes the restored configuration.
 */
@RunWith(AndroidJUnit4::class)
class HyperOs4AcceptanceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val outputDirectory get() = File(context.filesDir, "hyperos4-acceptance").apply {
        check(mkdirs() || isDirectory)
    }

    @Test
    fun runAcceptanceStep() {
        assumeTrue(arguments.getString("hyperos4Acceptance") == "true")
        val action = arguments.getString("acceptanceAction") ?: "snapshot"
        require(action in setOf("snapshot", "configure", "restore", "check-large",
            "prepare-profile", "cleanup-profile", "post-notification", "cancel-notification"))
        val hold = flag("acceptanceHold") ?: false
        val baselineName = safeName(arguments.getString("acceptanceBaseline") ?: "default")
        val label = safeName(arguments.getString("acceptanceLabel") ?: action)
        val baselineFile = File(outputDirectory, "baseline-$baselineName.json")
        val reportFile = File(outputDirectory, "${System.currentTimeMillis()}-$label.json")
        // Capture established framework preferences, not an unsynchronized mirror.
        val bindingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (ConfigManager.xposedService() == null && System.nanoTime() < bindingDeadline) Thread.sleep(100)
        assertNotNull("LSPosed preference service must bind before saving/restoring the user's settings", ConfigManager.xposedService())
        val entrySettings = settings()
        val report = JSONObject()
            .put("schema", 1).put("timeMillis", System.currentTimeMillis())
            .put("action", action).put("hold", hold).put("baselineFile", baselineFile.path)
            .put("reportFile", reportFile.path).put("device", Build.MODEL)
            .put("fingerprint", Build.FINGERPRINT).put("sdk", Build.VERSION.SDK_INT)
            .put("moduleApk", context.applicationInfo.sourceDir).put("entrySettings", entrySettings)
            .put("visualAcceptance", "Not asserted: inspect separately captured launcher/animation/notification evidence")
            .put("restoreArguments", "-e hyperos4Acceptance true -e acceptanceAction restore -e acceptanceBaseline $baselineName")
        var mutationStarted = false
        var failure: Throwable? = null
        try {
            if (action in setOf("snapshot", "configure", "prepare-profile", "post-notification")) {
                if (!baselineFile.exists()) atomicWrite(baselineFile, JSONObject()
                    .put("schema", 1).put("uid", Process.myUid()).put("package", context.packageName)
                    .put("createdAtMillis", System.currentTimeMillis()).put("settings", entrySettings))
                validateBaseline(JSONObject(baselineFile.readText()))
            }
            when (action) {
                "configure" -> {
                    // Parse every argument before changing the first setting.
                    val requested = JSONObject(entrySettings.toString())
                    flag("acceptanceEnabled")?.let { requested.put("enabled", it) }
                    flag("acceptanceCircle")?.let { requested.put("circle", it) }
                    flag("acceptanceLawnicons")?.let { requested.put("lawnicons", it) }
                    arguments.getString("acceptanceVariant")?.let { id ->
                        require(Material2025ColorEngine.availableVariants.any { it.id == id }) { "Unknown variant: $id" }
                        requested.put("variant", id)
                    }
                    arguments.getString("acceptanceProfile")?.let { id ->
                        require(id == "auto" || ProfileRepository.catalog(context).profiles.any { it.id == id })
                        requested.put("selectedProfile", if (id == "auto") JSONObject.NULL else id)
                    }
                    mutationStarted = true
                    applySettings(requested)
                }
                "restore" -> {
                    require(baselineFile.isFile) { "No saved baseline: ${baselineFile.path}" }
                    val baseline = JSONObject(baselineFile.readText())
                    validateBaseline(baseline)
                    mutationStarted = true
                    applySettings(baseline.getJSONObject("settings"))
                }
                "prepare-profile" -> prepareProfile(baselineName, report)
                "cleanup-profile" -> {
                    require(baselineFile.isFile) { "Restore the saved baseline before cleaning up the test Profile" }
                    val baseline = JSONObject(baselineFile.readText())
                    validateBaseline(baseline)
                    assertSettings(baseline.getJSONObject("settings"))
                    mutationStarted = cleanupProfile(baselineName, report)
                }
                "post-notification" -> postNotification(baselineName, report)
                "cancel-notification" -> cancelNotification(baselineName, report)
            }
            if (mutationStarted || flag("acceptanceFlush") == true) {
                val flushed = HyperOs4PalettePublisher.flushBeforeRestart(context)
                report.put("flushCommitted", flushed)
                assertTrue("Publication did not commit: ${HyperOs4PalettePublisher.status.value}", flushed)
            }
            val observation = observe()
            report.put("observation", observation.report)
            if (flag("acceptanceRequireAck") == true || action == "check-large") assertAcknowledged(observation)
            if (action == "check-large") checkLargeIcons(observation, report)
            report.put("stepPassed", true)
        } catch (caught: Throwable) {
            failure = caught
            report.put("stepPassed", false).put("error", caught.stackTraceToString())
            if (!report.has("observation")) runCatching { observe().report }.onSuccess { report.put("observation", it) }
        } finally {
            if (action == "configure" && mutationStarted && !hold) {
                try {
                    applySettings(entrySettings)
                    val committed = HyperOs4PalettePublisher.flushBeforeRestart(context)
                    report.put("entrySettingsRestored", committed).put("restoredSettings", settings())
                    assertTrue("Restoring entry settings did not commit", committed)
                    assertSettings(entrySettings)
                } catch (restoreFailure: Throwable) {
                    report.put("restoreError", restoreFailure.stackTraceToString())
                    if (failure == null) failure = restoreFailure else failure.addSuppressed(restoreFailure)
                }
            }
            report.put("exitSettings", settings()).put("publisherStatus", HyperOs4PalettePublisher.status.value)
            atomicWrite(reportFile, report)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nMONOICON_ACCEPTANCE_REPORT=${reportFile.path}\n" +
                    "MONOICON_ACCEPTANCE_BASELINE=${baselineFile.path}\n" +
                    report.toString(2) + "\n")
            })
        }
        failure?.let { throw it }
    }

    private fun settings() = JSONObject()
        .put("enabled", ConfigManager.isEnabledFromUi())
        .put("circle", ConfigManager.isCircleIconsEnabledFromUi())
        .put("lawnicons", ConfigManager.isLawniconsEnabledFromUi())
        .put("variant", ConfigManager.getVariantIdFromUi()).put("theme", ConfigManager.getThemeIdFromUi())
        .put("language", ConfigManager.getLanguageFromUi())
        .put("notificationIcons", ConfigManager.isNotificationIconsEnabledFromUi())
        .put("selectedProfile", ProfileRepository.selected(context) ?: JSONObject.NULL)

    private fun applySettings(saved: JSONObject) {
        val selected = if (saved.isNull("selectedProfile")) null else saved.getString("selectedProfile")
        require(selected == null || ProfileRepository.catalog(context).profiles.any { it.id == selected }) {
            "Saved Profile is unavailable; baseline is retained"
        }
        val current = settings()
        if (current.getString("theme") != saved.getString("theme")) ConfigManager.setThemeId(saved.getString("theme"))
        if (current.getString("variant") != saved.getString("variant")) ConfigManager.setVariantId(saved.getString("variant"))
        if (current.getBoolean("circle") != saved.getBoolean("circle")) ConfigManager.setCircleIconsEnabled(saved.getBoolean("circle"))
        if (current.getBoolean("lawnicons") != saved.getBoolean("lawnicons")) ConfigManager.setLawniconsEnabled(saved.getBoolean("lawnicons"))
        if (current.getBoolean("enabled") != saved.getBoolean("enabled")) ConfigManager.setEnabled(saved.getBoolean("enabled"))
        if (current.getString("language") != saved.getString("language")) ConfigManager.setLanguage(saved.getString("language"))
        if (ProfileRepository.selected(context) != selected) {
            ProfileRepository.select(context, selected)
            HyperOs4PalettePublisher.profileChanged(context)
        }
        assertSettings(saved)
    }

    private fun assertSettings(expected: JSONObject) {
        val actual = settings()
        expected.keys().forEach { key -> assertEquals("Restored/selected setting $key", expected.get(key), actual.get(key)) }
    }

    private fun validateBaseline(baseline: JSONObject) {
        require(baseline.getInt("schema") == 1 && baseline.getInt("uid") == Process.myUid() &&
            baseline.getString("package") == context.packageName) { "Baseline belongs to a different app/user" }
        require(baseline.getJSONObject("settings").has("selectedProfile"))
    }

    private fun ownershipFile(kind: String, baselineName: String) = File(outputDirectory, "$kind-owner-$baselineName.json")

    private fun newOwnership(kind: String, baselineName: String): JSONObject = JSONObject()
        .put("schema", 1).put("uid", Process.myUid()).put("package", context.packageName)
        .put("kind", kind).put("baseline", baselineName).put("token", UUID.randomUUID().toString().replace("-", ""))
        .put("createdAtMillis", System.currentTimeMillis()).put("phase", "prepared")

    private fun readOwnership(file: File, kind: String, baselineName: String): JSONObject =
        JSONObject(file.readText()).also { owner ->
            require(owner.getInt("schema") == 1 && owner.getInt("uid") == Process.myUid() &&
                owner.getString("package") == context.packageName && owner.getString("kind") == kind &&
                owner.getString("baseline") == baselineName && Regex("[a-f0-9]{32}").matches(owner.getString("token"))) {
                "Fixture ownership does not belong to this baseline/app/user"
            }
        }

    private fun prepareProfile(baselineName: String, report: JSONObject) = synchronized(ProfileRepository) {
        val ownerFile = ownershipFile("profile", baselineName)
        if (ownerFile.isFile) {
            val owner = readOwnership(ownerFile, "profile", baselineName)
            if (owner.getString("phase") != "cleaned") {
                require(owner.getString("id") == "hyperos4-6144-device-acceptance-${owner.getString("token").take(12)}")
                val existing = ProfileRepository.catalog(context).profiles.singleOrNull { it.id == owner.getString("id") }
                require(existing != null && !existing.official && existing.digest == owner.getString("digest")) {
                    "Owned test Profile is absent or changed; inspect and cleanup before preparing again"
                }
                report.put("profileFixture", JSONObject(owner.toString()).put("status", "reused").put("ownershipFile", ownerFile.path))
                return@synchronized
            }
        }
        val official = ProfileRepository.catalog(context).profiles.single { it.official && it.id == "hyperos4-6144" }
        require(official.revision == 9) { "This acceptance fixture requires the bundled official 6144 r9" }
        val owner = newOwnership("profile", baselineName)
        val id = "hyperos4-6144-device-acceptance-${owner.getString("token").take(12)}"
        val target = File(context.filesDir, "hyperos4-profiles/$id.json")
        require(listOf(target, File("${target.path}.bak"), File("${target.path}.new")).none(File::exists) &&
            ProfileRepository.catalog(context).profiles.none { it.id == id }) { "Test Profile id already exists; nothing was overwritten" }
        val source = JSONObject(official.source.toString(Charsets.UTF_8))
        val candidate = JSONObject(source.toString())
            .put("id", id).put("name", "HyperOS 4 · 6144 r9 · local acceptance test copy")
        val evidence = candidate.optJSONObject("x-evidence") ?: JSONObject().also { candidate.put("x-evidence", it) }
        evidence.put("deviceAcceptance", JSONObject()
            .put("analysis", "Equivalent official r9 test copy for local Profile switching; not an independently adapted Profile.")
            .put("sourceDigest", official.digest).put("baseline", baselineName).put("ownerToken", owner.getString("token")))
        source.keys().forEach { key ->
            if (key !in setOf("id", "name", "x-evidence")) {
                assertEquals("Test copy must preserve executable field $key", source.get(key).toString(), candidate.get(key).toString())
            }
        }
        val bytes = candidate.toString(2).toByteArray(Charsets.UTF_8)
        val parsed = ProfileRepository.parse(bytes)
        owner.put("id", id).put("digest", parsed.digest).put("sourceDigest", official.digest).put("sourceRevision", official.revision)
        // Record intent first, so cleanup remains possible if the process dies
        // after import and before the final report is saved.
        atomicWrite(ownerFile, owner)
        val imported = ProfileRepository.import(context, bytes)
        assertEquals(parsed.digest, imported.digest)
        owner.put("phase", "imported")
        atomicWrite(ownerFile, owner)
        report.put("profileFixture", JSONObject(owner.toString()).put("status", "imported").put("ownershipFile", ownerFile.path))
    }

    private fun cleanupProfile(baselineName: String, report: JSONObject): Boolean = synchronized(ProfileRepository) {
        val ownerFile = ownershipFile("profile", baselineName)
        if (!ownerFile.isFile) {
            report.put("profileFixture", JSONObject().put("status", "skipped").put("reason", "no-owned-test-profile"))
            return@synchronized false
        }
        val owner = readOwnership(ownerFile, "profile", baselineName)
        if (owner.getString("phase") == "cleaned") {
            report.put("profileFixture", JSONObject(owner.toString()).put("status", "already-cleaned"))
            return@synchronized false
        }
        val id = owner.getString("id")
        require(id == "hyperos4-6144-device-acceptance-${owner.getString("token").take(12)}")
        require(ProfileRepository.selected(context) != id) { "Restore the original Profile selection before cleanup" }
        val target = File(context.filesDir, "hyperos4-profiles/$id.json")
        val existing = ProfileRepository.catalog(context).profiles.singleOrNull { it.id == id }
        require(existing != null || !target.exists()) { "Owned Profile is now invalid; refusing to delete changed user data" }
        if (existing != null) {
            require(!existing.official && existing.digest == owner.getString("digest")) { "Owned Profile was edited; refusing to delete it" }
            ProfileRepository.delete(context, id)
        }
        owner.put("phase", "cleaned").put("cleanedAtMillis", System.currentTimeMillis())
        atomicWrite(ownerFile, owner)
        report.put("profileFixture", JSONObject(owner.toString()).put("status", if (existing != null) "removed" else "already-absent"))
        // Removing the equivalent copy can restore unique automatic matching.
        HyperOs4PalettePublisher.profileChanged(context)
        true
    }

    private fun postNotification(baselineName: String, report: JSONObject) {
        val result = JSONObject().put("status", "skipped").put("posted", false)
        report.put("notificationFixture", result)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            result.put("reason", "notification-permission-not-granted")
            return
        }
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        if (!manager.areNotificationsEnabled()) {
            result.put("reason", "app-notifications-disabled")
            return
        }
        val ownerFile = ownershipFile("notification", baselineName)
        val previous = ownerFile.takeIf(File::isFile)?.let { readOwnership(it, "notification", baselineName) }
        val owner = previous?.takeUnless { it.getString("phase") == "cleaned" }
            ?: newOwnership("notification", baselineName).also { fresh ->
                val channel = "monoicon-device-acceptance-${fresh.getString("token")}"
                require(manager.getNotificationChannel(channel) == null) { "Notification channel already exists; nothing was changed" }
                fresh.put("channel", channel).put("tag", channel).put("id", NOTIFICATION_ID)
                atomicWrite(ownerFile, fresh)
            }
        val channelId = owner.getString("channel")
        require(channelId == "monoicon-device-acceptance-${owner.getString("token")}" &&
            owner.getString("tag") == channelId && owner.getInt("id") == NOTIFICATION_ID)
        manager.createNotificationChannel(NotificationChannel(channelId, "MonoIcon 本机图标验收", NotificationManager.IMPORTANCE_LOW).apply {
            description = "临时本机测试通知；验收结束后清理。"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        result.put("channel", channelId).put("id", NOTIFICATION_ID).put("ownershipFile", ownerFile.path)
        if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) {
            result.put("reason", "test-channel-disabled")
            return
        }
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("MonoIcon 本机图标验收")
            .setContentText("仅用于检查通知图标；验收后会移除此通知。")
            .setOnlyAlertOnce(true).setLocalOnly(true).setShowWhen(false).setAutoCancel(true).build()
        try {
            manager.notify(channelId, NOTIFICATION_ID, notification)
            owner.put("phase", "posted")
            atomicWrite(ownerFile, owner)
            val active = (0 until 10).any { attempt ->
                if (attempt > 0) Thread.sleep(100)
                manager.activeNotifications.any { it.tag == channelId && it.id == NOTIFICATION_ID }
            }
            result.put("status", if (active) "posted" else "skipped").put("posted", active)
                .put("reason", if (active) "active-notification-confirmed; visual-inspection-required" else "notification-not-active")
        } catch (_: SecurityException) {
            result.put("reason", "notification-permission-denied-during-post")
        }
    }

    private fun cancelNotification(baselineName: String, report: JSONObject) {
        val ownerFile = ownershipFile("notification", baselineName)
        if (!ownerFile.isFile) {
            report.put("notificationFixture", JSONObject().put("status", "skipped").put("reason", "no-owned-test-notification"))
            return
        }
        val owner = readOwnership(ownerFile, "notification", baselineName)
        if (owner.getString("phase") == "cleaned") {
            report.put("notificationFixture", JSONObject().put("status", "already-cancelled"))
            return
        }
        val channelId = owner.getString("channel")
        require(channelId == "monoicon-device-acceptance-${owner.getString("token")}" &&
            owner.getString("tag") == channelId && owner.getInt("id") == NOTIFICATION_ID)
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        manager.cancel(channelId, NOTIFICATION_ID)
        manager.deleteNotificationChannel(channelId)
        owner.put("phase", "cleaned").put("cleanedAtMillis", System.currentTimeMillis())
        atomicWrite(ownerFile, owner)
        report.put("notificationFixture", JSONObject().put("status", "cancelled").put("channel", channelId).put("id", NOTIFICATION_ID))
    }

    private data class Observation(
        val report: JSONObject,
        val directory: String,
        val fields: List<String>,
        val inputs: HyperOs4LargeIconInputs,
        val mappings: List<List<String>>,
    )

    private fun observe(): Observation {
        val resolution = ProfileRepository.resolve(context)
        val launcher = requireNotNull(resolution.launcher) { resolution.reason }
        val directory = "/data/user_de/${launcher.uid / 100000}/com.miui.home/files"
        val beforePid = rootText("pidof com.miui.home || true").trim()
        val inputs = HyperOs4LargeIconInputs.read(context, resolution.profile, launcher.uid)
        val report = JSONObject().put("environment", resolution.environment).put("resolution", resolution.reason)
            .put("settings", settings()).put("pidBefore", beforePid).put("launcherFiles", directory)
            .put("launcherApk", launcher.sourceDir).put("largeInputSignature", inputs.signature)
        resolution.profile?.let {
            report.put("profile", JSONObject().put("id", it.id).put("revision", it.revision)
                .put("digest", it.digest).put("official", it.official))
        }
        val geometry = inputs.geometry
        report.put("geometry", geometry?.let {
            JSONObject().put("cellWidth", it.cellWidth).put("cellHeight", it.cellHeight)
                .put("normalIconSize", it.normalIconSize).put("signature", it.signature)
                .put("sizes", JSONArray().apply {
                    for ((x, y) in SPANS) {
                        val size = requireNotNull(it.dimensions(x, y))
                        put(JSONObject().put("span", "${x}x$y").put("width", size.width).put("height", size.height))
                    }
                })
        } ?: JSONObject.NULL)
        report.put("largeInputs", JSONArray().apply {
            inputs.icons.forEach { put(JSONObject().put("path", it.path).put("package", it.packageName)) }
        })
        val files = JSONObject()
        for (name in listOf("monoicon_palette.txt", "monoicon_palette.applied", "monoicon_refresh.applied",
            "monoicon_hyos_palette.txt", "monoicon_hyos_icon_files.txt", "monoicon_profile.active",
            "monoicon_geometry.request", "monoicon_geometry.response", "monoicon_geometry.status")) files.put(name, readRootFile("$directory/$name"))
        report.put("files", files)
        val palette = files.getString("monoicon_palette.txt")
        val fields = palette.trim().split(Regex("\\s+"))
        val batch = fields.getOrNull(7)?.takeIf { GENERATION.matches(it) }
        val mappings = if (batch != null) {
            val manifest = readRootFile("$directory/monoicon_icons/$batch/.large-icons")
            report.put("batch", batch).put("largeManifest", manifest)
                .put("publishedLargeSignature", readRootFile("$directory/monoicon_icons/$batch/.large-signature").trim())
                .put("batchReady", readRootFile("$directory/monoicon_icons/$batch/.ready"))
            manifest.lineSequence().filter(String::isNotBlank).map { it.split('\t') }.toList()
        } else emptyList()
        report.put("paletteAcknowledged", palette.isNotBlank() && palette == files.getString("monoicon_palette.applied"))
            .put("pidAfter", rootText("pidof com.miui.home || true").trim())
        return Observation(report, directory, fields, inputs, mappings)
    }

    private fun assertAcknowledged(observation: Observation) {
        val report = observation.report
        assertTrue("Palette is pending native acknowledgement", report.getBoolean("paletteAcknowledged"))
        val pid = report.getString("pidAfter")
        assertTrue("One live launcher is required: $pid", Regex("[0-9]+").matches(pid))
        assertEquals("Launcher changed during observation", report.getString("pidBefore"), pid)
        val status = report.getJSONObject("files").getString("monoicon_hyos_palette.txt")
        val statusFields = Regex("(?:^|\\s)(profile|digest|result|pid)=([^\\s]+)").findAll(status)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("Native status belongs to a stale PID", pid, statusFields["pid"])
        assertEquals("Native digest differs from the published configuration", observation.fields.getOrNull(8), statusFields["digest"])
        assertEquals("Native apply/restore failed", if (observation.fields.getOrNull(2) == "1") "1" else "12", statusFields["result"])
    }

    private fun checkLargeIcons(observation: Observation, report: JSONObject) {
        assertEquals("Large resources require the enabled module", "1", observation.fields.getOrNull(2))
        val geometry = requireNotNull(observation.inputs.geometry) { "Native phone geometry is unavailable" }
        val packageName = arguments.getString("acceptanceLargePackage") ?: "com.android.settings"
        require(Regex("[A-Za-z0-9_.]+").matches(packageName))
        val icons = observation.inputs.icons.filter { it.packageName == packageName }
        assertTrue("No unambiguous real large-icon owner for $packageName", icons.isNotEmpty())
        assertEquals("Export uses stale geometry/resource ownership", observation.inputs.signature,
            observation.report.getString("publishedLargeSignature"))
        val checked = JSONArray()
        report.put("checkedLargePngs", checked)
        val batch = observation.fields[7]
        for (icon in icons) for ((x, y) in SPANS) {
            val source = "${icon.path}/${x}x$y"
            val row = observation.mappings.singleOrNull { it.size == 3 && it[0] == source }
            assertNotNull("Real $packageName span ${x}x$y was not exported", row)
            val target = row!![1]
            val name = row[2]
            require(Regex("\\.large/[a-f0-9]{64}").matches(target) && name == "$packageName.png")
            val path = "${observation.directory}/monoicon_icons/$batch/$target/res/drawable/$name"
            val bytes = rootBytes("cat ${quote(path)}", 8 * 1024 * 1024)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val size = requireNotNull(geometry.dimensions(x, y))
            checked.put(JSONObject().put("source", source).put("path", path).put("sha256", ProfileRepository.sha256(bytes))
                .put("width", options.outWidth).put("height", options.outHeight))
            assertTrue("Large PNG has no high-resolution glyph", minOf(options.outWidth, options.outHeight) >= 512)
            assertEquals("Actual geometry aspect ratio", size.width / size.height,
                options.outWidth.toDouble() / options.outHeight, 0.003)
        }
    }

    private fun readRootFile(path: String): String = rootText("if [ -f ${quote(path)} ]; then head -c 524288 ${quote(path)}; fi")
    private fun rootText(command: String): String = rootBytes(command, 1024 * 1024).toString(Charsets.UTF_8)

    private fun rootBytes(command: String, maxBytes: Int): ByteArray {
        val output = File.createTempFile("acceptance-root-", ".out", context.cacheDir)
        val error = File.createTempFile("acceptance-root-", ".err", context.cacheDir)
        val process = ProcessBuilder("su", "-M", "-c", command).redirectOutput(output).redirectError(error).start()
        try {
            assertTrue("Root query timed out", process.waitFor(15, TimeUnit.SECONDS))
            assertEquals("Root query failed: ${error.readText().take(2000)}", 0, process.exitValue())
            require(output.length() <= maxBytes) { "Root output exceeds $maxBytes bytes" }
            return output.readBytes()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
            output.delete(); error.delete()
        }
    }

    private fun flag(name: String): Boolean? = arguments.getString(name)?.let {
        require(it == "true" || it == "false") { "$name must be true or false" }
        it == "true"
    }
    private fun safeName(name: String): String = name.also { require(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}").matches(it)) }
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun atomicWrite(file: File, json: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(json.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    private companion object {
        const val NOTIFICATION_ID = 614408
        val SPANS = listOf(2 to 1, 1 to 2, 2 to 2)
        val GENERATION = Regex("g-[a-f0-9]{32}")
    }
}
