// Adapted from KernelSU (GPL-3.0-or-later), commit 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
// Mirrored from compose-miuix-ui example.

package com.jerrey.monoicon.ui.settings.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.jerrey.monoicon.ui.settings.isManagerDarkTheme
import androidx.compose.ui.platform.LocalConfiguration
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun BgEffectBackground(
    dynamicBackground: Boolean,
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    effectBackground: Boolean = true,
    alpha: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit,
) {
    if (!isRuntimeShaderSupported()) {
        Box(modifier = modifier, content = content)
        return
    }
    Box(
        modifier = modifier,
    ) {
        val surface = MiuixTheme.colorScheme.surface
        val deviceType = if (LocalConfiguration.current.screenWidthDp >= 840) DeviceType.PAD else DeviceType.PHONE
        val isDarkTheme = isManagerDarkTheme()
        val painter = remember { BgEffectPainter() }

        val preset = remember(deviceType, isDarkTheme) {
            BgEffectConfig.get(deviceType, isDarkTheme)
        }

        val colorStage = remember { Animatable(0f) }

        LaunchedEffect(dynamicBackground, preset) {
            if (!dynamicBackground) return@LaunchedEffect
            val animatesColors = preset.colors1 !== preset.colors2 || preset.colors2 !== preset.colors3
            if (!animatesColors) return@LaunchedEffect

            var targetStage = floor(colorStage.value) + 1f
            while (isActive) {
                delay((preset.colorInterpPeriod * 500).toLong().milliseconds)
                colorStage.animateTo(
                    targetValue = targetStage,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 35f),
                )
                targetStage += 1f
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .then(bgModifier)
                .bgEffectDraw(
                    painter = painter,
                    preset = preset,
                    deviceType = deviceType,
                    isDarkTheme = isDarkTheme,
                    surface = surface,
                    effectBackground = effectBackground,
                    isFullSize = isFullSize,
                    playing = dynamicBackground,
                    colorStage = { colorStage.value },
                    alpha = alpha,
                ),
        )
        content()
    }
}
