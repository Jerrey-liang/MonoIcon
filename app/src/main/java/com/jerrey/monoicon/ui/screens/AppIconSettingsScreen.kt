// Settings controls and motion follow KernelSU's Miuix pages (GPL-3.0-or-later).
package com.jerrey.monoicon.ui.screens

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BorderOuter
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.material.icons.rounded.Style
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.theme.color.dynamic.IconThemeColors
import com.jerrey.monoicon.theme.mask.MaskStrategy
import com.jerrey.monoicon.theme.render.adjustsGeneratedBorder
import com.jerrey.monoicon.ui.LocalStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class AppPreviewSourceKey(
    val style: IconSourceStyle,
    val allowLawniconsFallback: Boolean,
    val revision: Int,
)

private data class AppPreviewSource(
    val key: AppPreviewSourceKey,
    val style: IconSourceStyle?,
    val borderSupported: Boolean,
)

/** An app's draft uses the same wallpaper, mask renderer and pinned host as global controls. */
@Composable
fun AppIconSettingsScreen(
    app: LawniconsApp,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loadExample: suspend (Context, LawniconsApp, IconSourceStyle, Boolean) -> List<IconStyleExample> =
        IconStylePreviewRepository::loadAppExample,
    renderExamples: (suspend (List<IconStyleExample>, IconThemeColors, Int, Int, Float) -> List<Bitmap>)? = null,
    loadAppResources: suspend (Context, LawniconsApp) -> LawniconsApp =
        LawniconsAppsRepository::loadAppResources,
) {
    val strings = LocalStrings.current
    val words = rememberIconPreferencesStrings()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var overrides by remember(app.packageName) { mutableStateOf(ConfigManager.getAppIconOverridesFromUi(app.packageName)) }
    var defaults by remember { mutableStateOf(ConfigManager.getEffectiveIconStyleFromUi(null)) }
    var colorEngine by remember { mutableStateOf(ConfigManager.getColorEngineIdFromUi()) }
    var variant by remember { mutableStateOf(ConfigManager.getVariantIdFromUi()) }
    var allowLawniconsFallback by remember(app.packageName) {
        mutableStateOf(ConfigManager.permitsLawniconsFallbackFromUi(app.identity, overrides))
    }
    var dragging by remember(app.packageName) { mutableStateOf<IconStyleParameter?>(null) }
    var revision by remember(app.packageName) { mutableIntStateOf(0) }
    var resources by remember(app.packageName, app.identity) { mutableStateOf(app) }
    var confirmedResourceRevision by remember(app.identity) { mutableStateOf<Int?>(null) }
    var resourceLookupFailed by remember(app.identity, revision) { mutableStateOf(false) }
    var restartHint by rememberSaveable(app.packageName) { mutableStateOf(false) }
    val effective = overrides.resolve(defaults)
    // A -> B -> A is a new load, even if A had previously supported a border.
    // Geometry changes retain the capability already confirmed by the real mask.
    var loadedSource by remember(app.identity, effective.style, allowLawniconsFallback, revision) {
        mutableStateOf<AppPreviewSource?>(null)
    }
    val previewSourceKey = AppPreviewSourceKey(effective.style, allowLawniconsFallback, revision)
    val currentSource = loadedSource?.takeIf { it.key == previewSourceKey }
    val borderSupported = currentSource?.borderSupported == true
    val resourcesConfirmed = confirmedResourceRevision == revision
    val defaultFallback = defaults.style.permitsLawniconsFallback
    val resolvedDefault = if (effective.style == defaults.style && allowLawniconsFallback == defaultFallback) {
        currentSource?.style ?: resolvedAppIconSource(resources, defaults.style, defaultFallback)
    } else resolvedAppIconSource(resources, defaults.style, defaultFallback)
    val availableStyles = if (resourcesConfirmed) customIconSourceStyles(resources, resolvedDefault) else emptyList()
    val resolvedOverride = overrides.style?.let { preferred ->
        currentSource?.style ?: resolvedAppIconSource(resources, preferred, allowLawniconsFallback)
    }
    val customMatchesDefault = overrides.style != null && resolvedOverride == resolvedDefault
    val followDefaultLabel = if (resourcesConfirmed) words.followDefaultStyle(resolvedDefault) else
        "${words.followDefault}(${if (resourceLookupFailed) words.sourceUnavailable else words.sourceLoading})"

    fun save(value: AppIconOverrides, replaceLegacySource: Boolean = false) {
        overrides = value.normalized()
        ConfigManager.setAppIconOverrides(app.packageName, overrides, replaceLegacySource)
        allowLawniconsFallback = ConfigManager.permitsLawniconsFallbackFromUi(app.identity, overrides)
        restartHint = true
    }

    DisposableEffect(app.packageName, app.identity) {
        val listener: (String?) -> Unit = { key ->
            scope.launch {
                val saved = ConfigManager.getAppIconOverridesFromUi(app.packageName)
                // A default update must not replace a slider's unsaved drag position.
                overrides = when (dragging) {
                    IconStyleParameter.ROUNDNESS -> saved.copy(roundnessPercent = overrides.roundnessPercent)
                    IconStyleParameter.SIZE -> saved.copy(sizePercent = overrides.sizePercent)
                    IconStyleParameter.BORDER -> saved.copy(borderPercent = overrides.borderPercent)
                    IconStyleParameter.ARTWORK_ROUNDNESS -> saved.copy(generatedArtworkRoundnessPercent = overrides.generatedArtworkRoundnessPercent)
                    null -> saved
                }
                defaults = ConfigManager.getEffectiveIconStyleFromUi(null)
                colorEngine = ConfigManager.getColorEngineIdFromUi()
                variant = ConfigManager.getVariantIdFromUi()
                allowLawniconsFallback = ConfigManager.permitsLawniconsFallbackFromUi(app.identity, overrides)
                if (key == null) revision++
            }
        }
        ConfigManager.addOnConfigChangedListener(listener)
        onDispose { ConfigManager.removeOnConfigChangedListener(listener) }
    }
    DisposableEffect(context, activity, app.packageName) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        var firstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstResume) firstResume = false else revision++
            }
        }
        lifecycle?.addObserver(observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.data?.schemeSpecificPart == app.packageName) revision++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycle?.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }

    KernelLazyPage(
        title = app.label,
        onBack = onBack,
        modifier = modifier.testTag("app-icon-detail-${app.packageName}"),
        actions = { HomeTopBarActions(Modifier.testTag("app-icon-detail-restart")) },
        pinnedContent = {
            IconStylePreview(
                roundnessPercent = effective.roundnessPercent,
                sizePercent = effective.sizePercent,
                colorEngineId = colorEngine,
                variantId = variant,
                lawniconsEnabled = effective.style == IconSourceStyle.LAWNICONS,
                sourceStyle = effective.style,
                allowLawniconsFallback = allowLawniconsFallback,
                configRevision = revision,
                generatedIconBorderRatio = effective.generatedBorderRatio,
                generatedArtworkRadiusFraction = effective.generatedArtworkRadiusFraction,
                loadExamples = { context, _ ->
                    // Retry also discards old capabilities before querying resources.
                    loadedSource = null
                    // Refresh resource metadata even when generating the preview fails.
                    // Source revisions include package updates and activity resumes.
                    resourceLookupFailed = false
                    val refreshed = try {
                        loadAppResources(context, app)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        confirmedResourceRevision = null
                        resourceLookupFailed = true
                        app.copy(hasNativeMonochrome = false, hasLawnicons = false)
                    }
                    currentCoroutineContext().ensureActive()
                    resources = refreshed
                    if (!resourceLookupFailed) confirmedResourceRevision = revision
                    val examples = try {
                        loadExample(context, refreshed, effective.style, allowLawniconsFallback)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        currentCoroutineContext().ensureActive()
                        loadedSource = AppPreviewSource(previewSourceKey, null, false)
                        throw failure
                    }
                    currentCoroutineContext().ensureActive()
                    val first = examples.firstOrNull()
                    val source = when {
                        first == null -> null
                        first.lawnicons || first.source == MaskStrategy.SOURCE_LAWNICONS -> IconSourceStyle.LAWNICONS
                        first.source == MaskStrategy.SOURCE_NATIVE -> IconSourceStyle.NATIVE
                        adjustsGeneratedBorder(first.source) -> IconSourceStyle.GENERATED
                        else -> null
                    }
                    loadedSource = AppPreviewSource(previewSourceKey, source,
                        first != null && !first.lawnicons && adjustsGeneratedBorder(first.source))
                    examples
                },
                renderExamples = renderExamples,
                previewKey = app.identity,
                singleIcon = true,
                modifier = Modifier.padding(horizontal = PageSpacing.gutter).padding(vertical = 12.dp),
            )
        },
    ) {
        item(key = "app-package") {
            Text(app.packageName, style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = PageSpacing.titleStart).padding(bottom = 12.dp))
        }
        item(key = "app-style") {
            Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter).padding(bottom = 12.dp)) {
                OverlayDropdownPreference(
                    title = words.iconStyle,
                    summary = (when {
                        currentSource == null -> words.sourceLoading
                        currentSource.style != null -> words.currentSource(currentSource.style)
                        else -> words.sourceUnavailable
                    }) + if (customMatchesDefault && resourcesConfirmed) "\n${words.customMatchesDefault}" else "",
                    startAction = { IconStylePreferenceIcon(Icons.Rounded.Style) },
                    items = listOf(followDefaultLabel) + availableStyles.map(words::styleName),
                    selectedIndex = (overrides.style?.takeIf { it in availableStyles } ?: resolvedOverride)
                        ?.let { availableStyles.indexOf(it) + 1 } ?: 0,
                    enabled = resourcesConfirmed,
                    modifier = Modifier.testTag("app-icon-style-${app.packageName}"),
                    onSelectedIndexChange = { index ->
                        save(overrides.copy(style = availableStyles.getOrNull(index - 1)), replaceLegacySource = true)
                    },
                )
            }
        }
        for (parameter in IconStyleParameter.entries) {
            if ((parameter == IconStyleParameter.BORDER || parameter == IconStyleParameter.ARTWORK_ROUNDNESS) && !borderSupported) continue
            item(key = parameter.tag) {
                val value = when (parameter) {
                    IconStyleParameter.ROUNDNESS -> effective.roundnessPercent
                    IconStyleParameter.SIZE -> effective.sizePercent
                    IconStyleParameter.BORDER -> effective.borderPercent
                    IconStyleParameter.ARTWORK_ROUNDNESS -> effective.generatedArtworkRoundnessPercent
                }
                val custom = when (parameter) {
                    IconStyleParameter.ROUNDNESS -> overrides.roundnessPercent != null
                    IconStyleParameter.SIZE -> overrides.sizePercent != null
                    IconStyleParameter.BORDER -> overrides.borderPercent != null
                    IconStyleParameter.ARTWORK_ROUNDNESS -> overrides.generatedArtworkRoundnessPercent != null
                }
                val tag = "app-${parameter.tag}-${app.packageName}"
                val icon = when (parameter) {
                    IconStyleParameter.ROUNDNESS -> Icons.Rounded.RoundedCorner
                    IconStyleParameter.SIZE -> Icons.Rounded.AspectRatio
                    IconStyleParameter.BORDER -> Icons.Rounded.BorderOuter
                    IconStyleParameter.ARTWORK_ROUNDNESS -> Icons.Rounded.RoundedCorner
                }
                fun changed(percent: Int?): AppIconOverrides = when (parameter) {
                    IconStyleParameter.ROUNDNESS -> overrides.copy(roundnessPercent = percent)
                    IconStyleParameter.SIZE -> overrides.copy(sizePercent = percent)
                    IconStyleParameter.BORDER -> overrides.copy(borderPercent = percent)
                    IconStyleParameter.ARTWORK_ROUNDNESS -> overrides.copy(generatedArtworkRoundnessPercent = percent)
                }
                Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter).padding(bottom = 12.dp)) {
                    OverlayDropdownPreference(
                        title = parameter.title(strings),
                        summary = if (parameter == IconStyleParameter.ARTWORK_ROUNDNESS && effective.generatedArtworkFollowIconRoundness)
                            words.artworkFollowingIcon else if (custom) words.customValue(value) else words.defaultValue(value),
                        startAction = { IconStylePreferenceIcon(icon) },
                        items = listOf(words.followDefault, words.custom),
                        selectedIndex = if (custom) 1 else 0,
                        enabled = parameter != IconStyleParameter.ARTWORK_ROUNDNESS || !effective.generatedArtworkFollowIconRoundness,
                        modifier = Modifier.testTag("$tag-mode"),
                        onSelectedIndexChange = { index ->
                            dragging = null
                            save(changed(if (index == 0) null else value))
                        },
                    )
                    AnimatedIconSettingsContent(visible = custom) {
                        IconStyleSlider(
                            title = parameter.title(strings),
                            summary = when (parameter) {
                                IconStyleParameter.ROUNDNESS -> strings.iconRoundnessSummary
                                IconStyleParameter.SIZE -> strings.iconSizeSummary
                                IconStyleParameter.BORDER -> strings.iconBorderSizeSummary
                                IconStyleParameter.ARTWORK_ROUNDNESS -> if (effective.generatedArtworkFollowIconRoundness)
                                    words.artworkFollowingIcon else words.artworkRoundnessSummary
                            },
                            icon = icon,
                            value = value,
                            startLabel = when (parameter) {
                                IconStyleParameter.ROUNDNESS -> strings.iconSystemShape
                                IconStyleParameter.SIZE -> strings.iconMonoiconSize
                                IconStyleParameter.BORDER -> strings.iconNoBorder
                                IconStyleParameter.ARTWORK_ROUNDNESS -> words.artworkNoRoundness
                            },
                            endLabel = when (parameter) {
                                IconStyleParameter.ROUNDNESS -> strings.iconCircleShape
                                IconStyleParameter.SIZE -> strings.iconSystemSize
                                IconStyleParameter.BORDER -> strings.iconDefaultBorder
                                IconStyleParameter.ARTWORK_ROUNDNESS -> strings.iconCircleShape
                            },
                            tag = tag,
                            enabled = parameter != IconStyleParameter.ARTWORK_ROUNDNESS || !effective.generatedArtworkFollowIconRoundness,
                            onValueChange = { percent -> dragging = parameter; overrides = changed(percent) },
                            onValueChangeFinished = { dragging = null; save(overrides) },
                        )
                    }
                    if (parameter == IconStyleParameter.ROUNDNESS && borderSupported) {
                        val followCustom = overrides.generatedArtworkFollowIconRoundness != null
                        OverlayDropdownPreference(
                            title = words.artworkFollowIconRoundness,
                            summary = "${if (followCustom) words.custom else words.followDefault} · ${if (effective.generatedArtworkFollowIconRoundness) words.on else words.off}",
                            items = listOf(words.followDefault, words.custom),
                            selectedIndex = if (followCustom) 1 else 0,
                            startAction = { IconStylePreferenceIcon(Icons.Rounded.RoundedCorner) },
                            modifier = Modifier.testTag("app-artwork-follow-icon-roundness-${app.packageName}-mode"),
                            onSelectedIndexChange = { index ->
                                save(overrides.copy(generatedArtworkFollowIconRoundness = if (index == 0) null else effective.generatedArtworkFollowIconRoundness))
                            },
                        )
                        AnimatedIconSettingsContent(visible = followCustom) {
                            SwitchPreference(
                                title = words.artworkFollowIconRoundness,
                                summary = if (effective.generatedArtworkFollowIconRoundness) words.artworkFollowIconSummary else words.artworkIndependentSummary,
                                checked = effective.generatedArtworkFollowIconRoundness,
                                startAction = { IconStylePreferenceIcon(Icons.Rounded.RoundedCorner) },
                                modifier = Modifier.testTag("app-artwork-follow-icon-roundness-${app.packageName}"),
                                onCheckedChange = { save(overrides.copy(generatedArtworkFollowIconRoundness = it)) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "app-reset") {
            AnimatedIconSettingsContent(visible = overrides.hasOverrides) {
                Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter).padding(bottom = 12.dp)) {
                    TextButton(
                        text = words.resetApp,
                        onClick = { dragging = null; save(AppIconOverrides(), replaceLegacySource = true) },
                        modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("app-icon-reset-${app.packageName}"),
                    )
                }
            }
        }
        item(key = "app-restart-hint") {
            AnimatedIconSettingsContent(visible = restartHint) {
                Text(strings.restartHint, style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = PageSpacing.gutter).padding(bottom = 12.dp))
            }
        }
    }
}
