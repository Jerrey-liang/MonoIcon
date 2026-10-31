package com.jerrey.monoicon.ui.settings

import android.content.Context
import com.jerrey.monoicon.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class AppRelease(val name: String, val url: String, val notes: String)
data class UpdateState(val checking: Boolean = false, val availableRelease: AppRelease? = null, val error: String? = null)

/** Checks only the public release feed; opening/downloading a release remains a user action. */
object AppUpdateChecker {
    private val mutableState = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private val lock = Mutex()
    private var checkedThisSession = false

    suspend fun check(context: Context, force: Boolean = false) {
        UiPreferences.init(context)
        if (!UiPreferences.state.value.checkUpdate && !force) return
        lock.withLock {
            if (checkedThisSession && !force) return
            mutableState.value = mutableState.value.copy(checking = true, error = null)
            try {
                val release = withContext(Dispatchers.IO) {
                    val connection = URI("https://api.github.com/repos/Jerrey-liang/MonoIcon/releases/latest")
                        .toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("User-Agent", "MonoIcon/${BuildConfig.VERSION_NAME}")
                    try {
                        if (connection.responseCode == 404) return@withContext null
                        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val tag = json.getString("tag_name")
                        if (!isNewerVersion(tag, BuildConfig.VERSION_NAME)) return@withContext null
                        AppRelease(
                            name = json.optString("name").ifBlank { tag },
                            url = "https://github.com/Jerrey-liang/MonoIcon/releases/tag/${URLEncoder.encode(tag, "UTF-8")}",
                            notes = json.optString("body"),
                        )
                    } finally {
                        connection.disconnect()
                    }
                }
                checkedThisSession = true
                mutableState.value = UpdateState(availableRelease = release)
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(checking = false)
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(checking = false, error = failure.message)
            }
        }
    }

    internal fun isNewerVersion(candidate: String, installed: String): Boolean {
        fun parts(value: String) = Regex("\\d+").findAll(value.substringBefore('-'))
            .map { it.value.toIntOrNull() ?: 0 }.take(4).toList()
        val remote = parts(candidate)
        val local = parts(installed)
        if (remote.isEmpty()) return false
        for (index in 0 until maxOf(remote.size, local.size)) {
            val comparison = (remote.getOrElse(index) { 0 }).compareTo(local.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return false
    }
}
