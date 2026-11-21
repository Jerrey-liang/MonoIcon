package com.jerrey.monoicon.theme.render

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/** Independent shape and whole-icon size controls shared by previews and launcher output. */
data class IconAppearance(val roundnessPercent: Int, val sizePercent: Int) {
    /** HyperOS' ordinary rounded tile has a quarter-short-side corner radius. */
    fun radiusFraction(): Float = 0.25f + 0.25f * roundnessPercent.coerceIn(0, 100) / 100f

    fun cornerRadius(width: Float, height: Float): Float = minOf(width, height) * radiusFraction()

    /** Size zero retains MonoIcon's Pixel normalization; size 100 fills the system tile. */
    fun contentBounds(bounds: Rect): Rect {
        if (bounds.isEmpty || sizePercent >= 100) return Rect(bounds)
        val originalInset = CircleIconShape.insetBounds(bounds).left - bounds.left
        val inset = Math.round(originalInset * (1f - sizePercent.coerceIn(0, 100) / 100f))
        return Rect(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
    }

    /** A rectangular icon ends in a capsule at 100, with unstretched semicircular ends. */
    fun clipPath(bounds: Rect): Path? {
        if (bounds.isEmpty) return null
        if (roundnessPercent >= 100) return CircleIconShape.clipPath(IconShape.CIRCLE, bounds)
        val radius = cornerRadius(bounds.width().toFloat(), bounds.height().toFloat())
        return Path().apply { addRoundRect(RectF(bounds), radius, radius, Path.Direction.CW) }
    }

    /** Full-bleed unit mask also drives MIUI's animation clipping and icon stroke. */
    fun maskPathData(): String {
        if (roundnessPercent >= 100) return CircleIconShape.MASK_PATH_DATA
        val radius = 100f * radiusFraction()
        val end = 100f - radius
        // Keep the same four-cubic topology as the existing circle mask.
        val tangent = radius * 0.5522f
        return "M$radius,0 L$end,0 C${end + tangent},0 100,${radius - tangent} 100,$radius " +
            "L100,$end C100,${end + tangent} ${end + tangent},100 $end,100 " +
            "L$radius,100 C${radius - tangent},100 0,${end + tangent} 0,$end " +
            "L0,$radius C0,${radius - tangent} ${radius - tangent},0 $radius,0 Z"
    }
}
