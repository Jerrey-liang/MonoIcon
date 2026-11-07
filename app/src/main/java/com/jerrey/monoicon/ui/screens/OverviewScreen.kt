// Adapted from KernelSU HomeMiuix.kt, GPL-3.0-or-later.
// Source revision: 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
package com.jerrey.monoicon.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.ScopeApp
import com.jerrey.monoicon.ui.XposedState
import com.jerrey.monoicon.ui.requestScope
import com.jerrey.monoicon.ui.settings.AppUpdateChecker
import com.jerrey.monoicon.ui.settings.rememberUiSettings
import com.jerrey.monoicon.ui.stringsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog

/** The navigation host owns the collapsing top bar and scrolling viewport. */
@Composable
fun OverviewScreen(
    @Suppress("UNUSED_PARAMETER") onOpenLogs: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenAbout: () -> Unit = {},
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") onTitleBottomPositioned: ((Int) -> Unit)? = null,
    isActive: Boolean = true,
) {
    val strings = LocalStrings.current
    val labels = homeLabels()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(XposedState.UNAVAILABLE) }
    var enabled by remember { mutableStateOf(ConfigManager.isEnabledFromUi()) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val listener: (String?) -> Unit = { scope.launch { enabled = ConfigManager.isEnabledFromUi() } }
        ConfigManager.addOnConfigChangedListener(listener)
        onDispose { ConfigManager.removeOnConfigChangedListener(listener) }
    }
    var systemInfo by remember { mutableStateOf(HomeSystemInfo()) }
    var scopeApps by remember { mutableStateOf<List<ScopeApp>>(emptyList()) }
    var showScope by remember { mutableStateOf(false) }
    var scopeRequested by remember { mutableStateOf(false) }
    var scopeRefresh by remember { mutableStateOf(0) }

    LaunchedEffect(context, lifecycleOwner, isActive, scopeRefresh) {
        if (!isActive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            enabled = ConfigManager.isEnabledFromUi()
            launch {
                systemInfo = withContext(Dispatchers.IO) { HomeSystemInfo.load() }
                scopeApps = withContext(Dispatchers.IO) { ScopeApp.load(context, XposedState.REQUIRED_SCOPE) }
            }
            ConfigManager.serviceState.collectLatest { service ->
                repeat(6) { attempt ->
                    val snapshot = withContext(Dispatchers.IO) { XposedState.snapshot(service) }
                    status = snapshot
                    if (service == null || snapshot.bound || attempt == 5) return@collectLatest
                    delay(500)
                }
            }
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 12.dp).padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeUpdateCard(labels)
        StatusHeroCard(status, enabled, labels, onClick = { showScope = true })
        SystemInfoCards(systemInfo, labels)
    }

    WindowDialog(
        show = showScope,
        title = strings.lsposedStatus,
        onDismissRequest = { showScope = false; scopeRefresh++ },
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (!status.active) Text(labels.activateHint)
            XposedState.REQUIRED_SCOPE.forEach { packageName ->
                BasicComponent(
                    title = when (packageName) {
                        "com.miui.home" -> strings.scopeAppLauncher
                        "com.android.systemui" -> strings.scopeAppSystemUi
                        else -> packageName
                    },
                    summary = "$packageName\n${scopeApps.firstOrNull { it.packageName == packageName }?.versionLabel(strings.versionPrefix) ?: ScopeApp.VERSION_UNAVAILABLE}",
                    endActions = {
                        Text(
                            text = if (status.hasScope(packageName)) strings.scopeGranted else strings.scopeMissing,
                            color = if (status.hasScope(packageName)) colorScheme.primary else colorScheme.error,
                            fontSize = 13.sp,
                        )
                    },
                )
            }
            val missing = XposedState.REQUIRED_SCOPE.filterNot(status::hasScope)
            if (missing.isNotEmpty() && status.bound) {
                TextButton(
                    text = if (scopeRequested) strings.scopeRequested else strings.requestScope,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { scopeRequested = requestScope(missing) },
                )
            }
            TextButton(
                text = labels.close,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                onClick = { showScope = false; scopeRefresh++ },
            )
        }
    }
}

/** KernelSU's intrinsic card, clipped corner icon, text offsets and tilt feedback. */
@Composable
private fun StatusHeroCard(status: XposedState, enabled: Boolean, labels: HomeLabels, onClick: () -> Unit) {
    val strings = LocalStrings.current
    if (!status.active) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(modifier = Modifier.weight(1f), onClick = onClick, pressFeedbackType = PressFeedbackType.Tilt) {
                BasicComponent(
                    title = strings.inactive,
                    summary = labels.activateHint,
                    startAction = {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                            tint = colorScheme.onBackground,
                        )
                    },
                )
            }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(
                color = when {
                    isDynamicColor -> colorScheme.secondaryContainer
                    colorScheme.surface.luminance() < 0.5f -> Color(0xFF1A3825)
                    else -> Color(0xFFDFFAE4)
                },
            ),
            onClick = onClick,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box {
                Box(
                    modifier = Modifier.fillMaxSize().offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(110.dp),
                        imageVector = Icons.Rounded.CheckCircleOutline,
                        tint = if (isDynamicColor) colorScheme.primary.copy(alpha = 0.8f) else Color(0xFF36D167),
                        contentDescription = null,
                    )
                }
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp, 10.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Text(text = status.frameworkName ?: "LSPosed", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(
                            text = if (enabled) labels.working else "${strings.active} [${labels.moduleDisabled}]",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(1.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${labels.version}${status.frameworkLabel}-${status.apiVersion}",
                                modifier = Modifier.weight(1f, fill = false),
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemInfoCards(systemInfo: HomeSystemInfo, labels: HomeLabels) {
    @Composable
    fun InfoText(icon: ImageVector, title: String, content: String, bottomPadding: Dp = 24.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(24.dp), tint = colorScheme.onSurface)
            Column {
                Text(text = title, fontSize = MiuixTheme.textStyles.headline1.fontSize, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                Text(text = content, fontSize = MiuixTheme.textStyles.body2.fontSize, color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoText(Icons.Filled.Tag, labels.managerVersion, systemInfo.managerVersion)
            InfoText(Icons.Filled.DeveloperBoard, labels.kernel, systemInfo.kernelVersion)
            InfoText(Icons.Filled.Smartphone, labels.deviceModel, systemInfo.deviceModel)
            InfoText(Icons.Filled.Fingerprint, labels.fingerprint, systemInfo.fingerprint, 0.dp)
        }
    }
}

/** KernelSU's update notice animation, warning colors and sink press feedback. */
@Composable
private fun HomeUpdateCard(labels: HomeLabels) {
    val update by AppUpdateChecker.state.collectAsState()
    val release = update.availableRelease.takeIf { rememberUiSettings().checkUpdate }
    var showDetails by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    AnimatedVisibility(visible = release != null, enter = fadeIn() + expandVertically(), exit = shrinkVertically() + fadeOut()) {
        Card(
            colors = CardDefaults.defaultColors(
                color = when {
                    isDynamicColor -> colorScheme.tertiaryContainer
                    colorScheme.surface.luminance() < 0.5f -> Color(0xFF3E2F1B)
                    else -> Color(0xFFFFF0DB)
                },
                contentColor = if (isDynamicColor) colorScheme.onTertiaryContainer else Color(0xFFF5A623),
            ),
            onClick = { showDetails = true },
            pressFeedbackType = PressFeedbackType.Sink,
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "${labels.updateAvailable}: ${release?.name.orEmpty()}", fontSize = 14.sp)
            }
        }
    }
    WindowDialog(show = showDetails && release != null, title = release?.name, onDismissRequest = { showDetails = false }) {
        Column {
            Text(text = release?.notes.orEmpty(), modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
            TextButton(
                text = labels.update,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    release?.url?.let { runCatching { uriHandler.openUri(it) } }
                    showDetails = false
                },
            )
            TextButton(text = labels.close, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), onClick = { showDetails = false })
        }
    }
}

@Composable
private fun homeLabels() = HomeLabels(LocalStrings.current == stringsFor(AppLanguage.CHINESE, true))

private class HomeLabels(private val chinese: Boolean) {
    private fun text(zh: String, en: String) = if (chinese) zh else en
    val working = text("工作中", "Working")
    val version = text("版本：", "Version: ")
    val moduleDisabled = text("模块已关闭", "Module disabled")
    val managerVersion = text("管理器版本", "Manager version")
    val kernel = text("内核版本", "Kernel version")
    val deviceModel = text("设备型号", "Device model")
    val fingerprint = text("系统指纹", "Fingerprint")
    val close = text("关闭", "Close")
    val activateHint = text("在 LSPosed 中启用 MonoIcon 并激活作用域", "Enable MonoIcon and its scope in LSPosed")
    val updateAvailable = text("发现新版本", "New version available")
    val update = text("更新", "Update")
}
