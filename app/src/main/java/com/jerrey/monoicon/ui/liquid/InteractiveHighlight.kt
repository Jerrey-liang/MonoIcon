// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
//
// Mirrored from KernelSU's component/miuix/animation/InteractiveHighlight.kt
// at 08a3b087e49227c8a6731c5f1114998b5e25255b.
// Originally adapted from Kyant0/AndroidLiquidGlass (Apache 2.0).

package com.jerrey.monoicon.ui.liquid

import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {

    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var startPosition = Offset.Zero
    val offset: Offset get() = positionAnimation.value - startPosition

    private val spotShader = RuntimeShader(SPOT_SHADER)
    private val spotBrush = ShaderBrush(spotShader)

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            drawRect(
                color = Color.White.copy(alpha = 0.06f * progress),
                blendMode = BlendMode.Plus,
            )
            val pos = position(size, positionAnimation.value)
            val radius = size.minDimension * 1.2f
            val center = Offset(
                x = pos.x.coerceIn(0f, size.width),
                y = pos.y.coerceIn(0f, size.height),
            )
            val spotColor = Color.White.copy(alpha = 0.12f * progress)
            spotShader.setColorUniform("color", spotColor.toArgb())
            spotShader.setFloatUniform("radius", radius)
            spotShader.setFloatUniform("position", center.x, center.y)
            drawRect(
                brush = spotBrush,
                blendMode = BlendMode.Plus,
            )
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = { release() },
            onDragCancel = { release() },
        ) { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }

    // On release the spot springs back to the press-down point while the press fades out.
    private fun release() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
        }
    }
}

// Press spot: solid within half the radius, smoothstep falloff toward the edge.
private const val SPOT_SHADER = """
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;

    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.5, dist);
        return color * intensity;
    }"""
