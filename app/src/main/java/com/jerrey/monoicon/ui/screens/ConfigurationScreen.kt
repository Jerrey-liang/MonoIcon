package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jerrey.monoicon.hook.HyperOs4ThemeSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Runtime diagnostics, using KernelSU SettingsMiuix's 12 dp card gutters. */
@Composable
fun ConfigurationScreen(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var runtime by remember { mutableStateOf(ConfigurationRuntime()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    val themeStatus by HyperOs4ThemeSync.status.collectAsStateWithLifecycle()
    val themeConflict by HyperOs4ThemeSync.conflict.collectAsStateWithLifecycle()
    val canRecoverOriginal by HyperOs4ThemeSync.canRecoverOriginal.collectAsStateWithLifecycle()
    LaunchedEffect(context, lifecycleOwner, isActive, revision) {
        if (!isActive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                loading = true
                runtime = runtime.copy(processes = runtime.processes.map { it.copy(current = false) })
                try {
                    // Show the system and launcher before requesting host reports.
                    val environment = withContext(Dispatchers.IO) {
                        ConfigurationDiagnosticsRepository.environment(context)
                    }
                    runtime = runtime.copy(environment = environment)
                    runtime = withContext(Dispatchers.IO) { ConfigurationDiagnosticsRepository.load(context, environment) }
                    error = ""
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message.orEmpty()
                    runtime = runtime.copy(processes = runtime.processes.map { it.copy(current = false) })
                } finally { loading = false }
                delay(10_000)
            }
        }
    }
    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        ConfigurationRuntimeContent(
            runtime, loading, error,
            onRefresh = {
                if (runtime.environment.themeArchiveAvailable) {
                    HyperOs4ThemeSync.enqueue(context, HyperOs4ThemeSync.CHECK)
                }
                revision++
            },
            themeStatus = themeStatus, themeConflict = themeConflict,
            onAcceptTheme = { HyperOs4ThemeSync.acceptCurrentTheme(context) },
            canRecoverOriginal = canRecoverOriginal,
            onRecoverOriginal = { HyperOs4ThemeSync.recoverOriginal(context) },
        )
    }
}
