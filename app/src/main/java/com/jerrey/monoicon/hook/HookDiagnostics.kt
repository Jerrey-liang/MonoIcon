package com.jerrey.monoicon.hook

import android.app.Application
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.jerrey.monoicon.BuildConfig
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, read-only diagnostic snapshots sent by the actual hooked process. No root or remote-pref writes. */
object HookDiagnostics {
    internal const val MODULE = "com.jerrey.monoicon"
    internal const val AUTHORITY = "$MODULE.hook.diagnostics"
    internal const val METHOD_REPORT = "report"
    internal const val EXTRA_SNAPSHOT = "snapshot"
    internal const val PREFS = "monoicon_hook_diagnostics"
    internal const val MAX_BYTES = 128 * 1024
    private const val ACTION_REQUEST = "$MODULE.action.REQUEST_HOOK_DIAGNOSTICS"
    private const val EXTRA_NONCE = "request_nonce"
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "MonoIcon-HookDiagnostics").apply { isDaemon = true }
    }
    private val started = AtomicBoolean()
    private val pending = AtomicBoolean()
    private val receiverRegistered = AtomicBoolean()
    @Volatile private var hookContext: Context? = null
    @Volatile private var packageName = ""
    @Volatile private var processName = ""
    @Volatile private var requestNonce: String? = null
    private var startedElapsedMs = 0L
    private var processStartTicks: Long? = null
    private var systemBootId: String? = null
    private var moduleUid = -1

    data class Snapshot(
        val packageName: String,
        val processName: String,
        val pid: Int,
        val startedElapsedMs: Long,
        val capturedElapsedMs: Long,
        val capturedAtMillis: Long,
        val moduleVersion: String,
        val systemBootId: String?,
        val processStartTicks: Long?,
        val requestNonce: String?,
        val points: List<HookRegistry.HookEntry>,
    )

    /** Called before enabled/safety gates so a skipped point is still observable. */
    internal fun start(api: XposedInterface, pkg: String, process: String) {
        if (!started.compareAndSet(false, true)) return
        packageName = pkg
        processName = process
        startedElapsedMs = SystemClock.elapsedRealtime()
        processStartTicks = runCatching {
            File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19].toLong()
        }.getOrNull()
        systemBootId = currentBootId()
        // Framework-owned metadata avoids package-visibility restrictions in the host app.
        moduleUid = runCatching { api.moduleApplicationInfo.uid }.getOrDefault(-1)
        HookRegistry.configure(pkg)
        HookRegistry.install("DiagnosticsPublisher", required = false) {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            HookRegistry.intercept(api, attach) { chain ->
                val result = chain.proceed()
                runCatching { (chain.getArg(0) as? Context)?.takeIf { it.packageName == pkg }?.let(::attachContext) }
                result
            }
        }
        // Also handle a framework callback delivered after Application.attach.
        executor.execute {
            runCatching {
                val activityThread = Class.forName("android.app.ActivityThread")
                val app = activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Context
                app?.takeIf { it.packageName == pkg }?.let(::attachContext)
            }
        }
        changed()
    }

    internal fun attachContext(context: Context) {
        if (!started.get() || context.packageName != packageName) return
        hookContext = context.applicationContext ?: context
        if (receiverRegistered.compareAndSet(false, true)) {
            runCatching {
                hookContext!!.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action != ACTION_REQUEST) return
                        val sender = getSentFromUid()
                        if (moduleUid < 0 || sender != moduleUid || sender / 100_000 != Process.myUid() / 100_000) return
                        requestNonce = intent.getStringExtra(EXTRA_NONCE)?.take(80)
                        changed()
                    }
                }, IntentFilter(ACTION_REQUEST), Context.RECEIVER_EXPORTED)
            }.onFailure { receiverRegistered.set(false) }
        }
        changed()
    }

    /** Coalesces installation/first-hit updates. Hook callbacks perform no binder or disk work. */
    internal fun changed() {
        if (!started.get() || !pending.compareAndSet(false, true)) return
        runCatching {
            executor.schedule({
                pending.set(false)
                val context = hookContext ?: return@schedule
                runCatching {
                    val snapshot = Snapshot(packageName, processName, Process.myPid(), startedElapsedMs,
                        SystemClock.elapsedRealtime(), System.currentTimeMillis(), BuildConfig.VERSION_NAME,
                        systemBootId, processStartTicks, requestNonce, HookRegistry.snapshot())
                    val encoded = encode(snapshot)
                    if (encoded.length <= MAX_BYTES) {
                        context.contentResolver.call(Uri.parse("content://$AUTHORITY"), METHOD_REPORT, null,
                            Bundle().apply { putString(EXTRA_SNAPSHOT, encoded) })
                    }
                }.onFailure { android.util.Log.w("MonoIcon.HookDiagnostics", "report unavailable: ${it.javaClass.simpleName}") }
            }, 250, TimeUnit.MILLISECONDS)
        }.onFailure { pending.set(false) }
    }

    /** Requests a fresh report from a running scope; absence of a response remains unknown/stale. */
    fun requestSnapshots(context: Context, packages: List<String>): String {
        val nonce = UUID.randomUUID().toString()
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        packages.forEach { pkg ->
            runCatching {
                context.sendBroadcast(Intent(ACTION_REQUEST).setPackage(pkg).putExtra(EXTRA_NONCE, nonce), null, options)
            }
        }
        return nonce
    }

    /** App-private persisted evidence, not the manager process's empty [HookRegistry]. */
    fun readSnapshots(context: Context): List<Snapshot> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.values.mapNotNull { value ->
            runCatching { (value as? String)?.let(::decode) }.getOrNull()
        }
    }

    fun expectedPoints(packageName: String): List<HookRegistry.HookEntry> = HookRegistry.expectedPoints(packageName)

    fun currentBootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
    }.getOrNull()

    internal fun encode(snapshot: Snapshot): String = JSONObject().apply {
        put("schema", 1)
        put("package", snapshot.packageName)
        put("process", snapshot.processName)
        put("pid", snapshot.pid)
        put("startedElapsedMs", snapshot.startedElapsedMs)
        put("capturedElapsedMs", snapshot.capturedElapsedMs)
        put("capturedAtMillis", snapshot.capturedAtMillis)
        put("moduleVersion", snapshot.moduleVersion)
        put("mode", "java")
        put("bootId", snapshot.systemBootId)
        put("processStartTicks", snapshot.processStartTicks)
        put("requestNonce", snapshot.requestNonce)
        put("points", JSONArray().apply {
            snapshot.points.forEach { point -> put(JSONObject().apply {
                put("id", point.name); put("group", point.group); put("member", point.member)
                put("required", point.required); put("status", point.status.name); put("detail", point.detail)
                put("hitObserved", point.hitObserved); put("firstHitElapsedMs", point.firstHitElapsedMs)
            }) }
        })
    }.toString()

    internal fun decode(encoded: String): Snapshot {
        require(encoded.length <= MAX_BYTES)
        val json = JSONObject(encoded)
        require(json.getInt("schema") == 1 && json.getString("mode") == "java")
        val array = json.getJSONArray("points")
        require(array.length() <= 96)
        val points = (0 until array.length()).map { index ->
            val point = array.getJSONObject(index)
            HookRegistry.HookEntry(point.getString("id").take(512), point.getString("group").take(120),
                point.getString("member").take(1024), point.getBoolean("required"),
                HookRegistry.Status.valueOf(point.getString("status")), point.getString("detail").take(320),
                point.getBoolean("hitObserved"), point.getLong("firstHitElapsedMs"))
        }
        fun optionalString(key: String) = if (json.isNull(key)) null else json.getString(key).take(80)
        return Snapshot(json.getString("package"), json.getString("process").take(160), json.getInt("pid"),
            json.getLong("startedElapsedMs"), json.getLong("capturedElapsedMs"), json.getLong("capturedAtMillis"),
            json.getString("moduleVersion").take(80), optionalString("bootId"),
            if (json.isNull("processStartTicks")) null else json.getLong("processStartTicks"),
            optionalString("requestNonce"), points)
    }
}
