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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Standalone Profile page, using KernelSU SettingsMiuix's 12 dp card gutters. */
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
    LaunchedEffect(context, lifecycleOwner, isActive, revision) {
        if (!isActive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                loading = true
                runtime = runtime.copy(processes = runtime.processes.map { it.copy(current = false) })
                try {
                    runtime = withContext(Dispatchers.IO) { ConfigurationDiagnosticsRepository.load(context) }
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
        ConfigurationRuntimeContent(runtime, loading, error, onRefresh = { revision++ }) {
            ProfileSettings(isActive = isActive)
        }
    }
}
