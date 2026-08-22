package com.jerrey.monoicon.theme.color.dynamic

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HCT (Hue-Chroma-Tone) tonal palette — Phase 6.3.
 *
 * Ported from Pixel Launcher's `ColorScheme` + `TonalPalette` +
 * `DynamicColors` pipeline. Uses the CAM16 color appearance model
 * to generate perceptually uniform tonal shades from a single seed
 * color, matching Gooogle Material Color Utilities output.
 *
 * ## Pipeline
 * 1. Seed ARGB → CAM16 → (hue, chroma, lightness)
 * 2. For each tone (0–100): HCT solver finds CAM16 with matching
 *    hue+chroma at that tone → converts to ARGB
 * 3. Output: 13-tone palette with fixed hue+chroma, varying
 *    perceptual lightness only
 *
 * ## Key difference from Phase 6.2 RGB interpolation
 * CAM16 preserves chroma across the entire tonal range — dark tones
 * stay vivid (not muddy black), light tones stay colorful (not washed
 * out white). This matches Pixel Launcher's HCT-based tonal palette.
 */
object HctPalette {

    /** Standard CAM16 viewing conditions (D65, sRGB ambient). */
    private val VC: ViewingConditions? by lazy {
        try { ViewingConditions.DEFAULT } catch (_: Throwable) { null }
    }

    /**
     * Generates a 13-tone ARGB palette from [seedColor].
     * Tones: 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100.
     */
    fun generate(seed: Int): IntArray {
        // Keep a deterministic hue-preserving fallback. The previous local
        // CAM16 port contained decompilation remnants and frequently returned
        // grayscale tones, which made the plate react only to light/dark mode.
        val tones = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)
        return IntArray(tones.size) { i -> hslTone(seed, tones[i].toFloat()) }
    }

    /**
     * Single-tone ARGB at Hue-Chroma from seed.
     */
    fun argb(seed: Int, tone: Float): Int {
        return hslTone(seed, tone)
    }

    /** Generates a wallpaper-hued tone without depending on the broken local HCT port. */
    private fun hslTone(seed: Int, tone: Float): Int {
        val r = Color.red(seed) / 255f
        val g = Color.green(seed) / 255f
        val b = Color.blue(seed) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val lightness = (max + min) / 2f
        val hue = when {
            delta == 0f -> 0f
            max == r -> ((g - b) / delta).let { if (it < 0f) it + 6f else it }
            max == g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } / 6f
        val sourceSaturation = if (delta == 0f) 0f else
            delta / (1f - kotlin.math.abs(2f * lightness - 1f))
        val saturation = (sourceSaturation * 0.75f).coerceIn(0.12f, 0.72f)
        val l = (tone / 100f).coerceIn(0f, 1f)
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * saturation
        val x = c * (1f - kotlin.math.abs((hue * 6f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when ((hue * 6f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color.rgb(
            ((r1 + m) * 255f).roundToInt().coerceIn(0, 255),
            ((g1 + m) * 255f).roundToInt().coerceIn(0, 255),
            ((b1 + m) * 255f).roundToInt().coerceIn(0, 255),
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAM16 Color Appearance Model
    // ═══════════════════════════════════════════════════════════════════

    data class Cam16(val hue: Double, val chroma: Double, val j: Double, val argb: Int) {

        companion object {
            /** RGB → CAM16 forward model (i8/b.java constructor). */
            fun fromArgb(argb: Int): Cam16 {
                val r = Color.red(argb); val g = Color.green(argb); val b = Color.blue(argb)

                // sRGB linearization (m8/a.java c())
                fun linearize(c: Int): Double {
                    val d = c / 255.0
                    return if (d <= 0.040449936) d / 12.92
                    else ((d + 0.055) / 1.055).pow(2.4)
                }
                val rl = linearize(r); val gl = linearize(g); val bl = linearize(b)

                // Linear RGB → XYZ (D65 matrix, m8/a.java a[][])
                val x = 0.41233895 * rl + 0.35762064 * gl + 0.18051042 * bl
                val y = 0.2126    * rl + 0.7152    * gl + 0.0722    * bl
                val z = 0.01932141 * rl + 0.11916382 * gl + 0.95034478 * bl

                // XYZ → CAT02 adapted (i8/a.java matrix)
                val aM = i8MatrixA
                val xa = aM[0][0]*x + aM[0][1]*y + aM[0][2]*z
                val ya = aM[1][0]*x + aM[1][1]*y + aM[1][2]*z
                val za = aM[2][0]*x + aM[2][1]*y + aM[2][2]*z

                // Chromatic adaptation (i8/b.java lines 45-51)
                val vc = VC ?: throw IllegalStateException("ViewingConditions unavailable")
                val powBase = vc.h / 100.0
                val ac = (if (xa >= 0.0) 1.0 else -1.0) * 400.0 * (abs(xa) * powBase).pow(0.42) / ((abs(xa) * powBase).pow(0.42) + 27.13)
                val bc = (if (ya >= 0.0) 1.0 else -1.0) * 400.0 * (abs(ya) * powBase).pow(0.42) / ((abs(ya) * powBase).pow(0.42) + 27.13)
                val cc = (if (za >= 0.0) 1.0 else -1.0) * 400.0 * (abs(za) * powBase).pow(0.42) / ((abs(za) * powBase).pow(0.42) + 27.13)

                val a = (11.0 * ac - 12.0 * bc + cc) / 11.0
                val bVal = (ac + bc - 2.0 * cc) / 9.0
                val u = (20.0 * ac + 20.0 * bc + 21.0 * cc) / 20.0

                // Hue from atan2 (line 57)
                var hue = Math.toDegrees(atan2(bVal, a))
                if (hue < 0.0) hue += 360.0 else if (hue >= 360.0) hue -= 360.0
                val hueRad = Math.toRadians(hue)

                // Achromatic response (line 64)
                val p2 = (vc.b * u / vc.a).pow(vc.j * vc.d) * 100.0 / 100.0

                // Chroma from CAM16 model (line 66)
                val et = (cos(Math.toRadians(if (hue < 20.14) hue + 360.0 else hue)) + 2.0 + 3.8) * 0.25 * 3846.153846153846 * vc.e * vc.c
                val p1 = (50000.0 / 13.0) * vc.nTick * vc.nCB
                val t = (hypot(a, bVal) * p1) / (u + 0.305)
                val chroma = sqrt(p2) * (t.pow(0.9) * (1.64 - 0.29.pow(vc.f)).pow(0.73))

                // J (lightness) from L* (line 75)
                val j = ((Lstar(rl, gl, bl) + 16.0) / 116.0).pow(3.0) * 100.0

                return Cam16(hue, chroma, j, argb)
            }

            /** Reverse: hue + chroma + tone → ARGB (i8/b.java static a()). */
            fun fromHct(hue: Double, chroma: Double, tone: Double): Cam16 {
                if (chroma < 1e-4 || tone < 1e-4 || tone > 99.9999) {
                    val g = (LstarFromTone(tone) * 255.0).roundToInt().coerceIn(0, 255)
                    val gray = 0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
                    return Cam16.fromArgb(gray)
                }

                val hueRad = (hue % 360.0 / 180.0 * Math.PI)

                // Target lightness → L* → Y
                val yTarget = LstarFromTone(tone)
                val vc = VC ?: throw IllegalStateException("ViewingConditions unavailable")
                val p = 1.0 / (1.64 - 0.29.pow(vc.f)).pow(0.73)
                val cosF = (cos(hueRad + 2.0) + 3.8) * 0.25 * 3846.153846153846 * vc.e * vc.c

                // Newton iteration to solve for J matching the target
                var y = sqrt(yTarget) * 11.0
                for (iter in 0 until 5) {
                    val yNorm = y / 100.0
                    val t = if (chroma == 0.0 || y == 0.0) 0.0
                    else (chroma / sqrt(yNorm)).pow(1.0 / 0.9) * p
                    val p3 = (yNorm.pow((1.0 / vc.d) / vc.j) * vc.a) / vc.b
                    val tDenom = (t * 108.0) * sin(hueRad) + ((t * 11.0) * cos(hueRad) + (23.0 * cos(hueRad)))
                    val tNum = ((0.305 + p3) * 23.0) * t
                    val aVal = tNum * cos(hueRad) / tDenom
                    val bVal = tNum * sin(hueRad) / tDenom
                    val u = p3 * 460.0

                    // CAT02 inverse (from m8/b.java c() with inverse matrix)
                    val cat02Inv = cat02InvMatrix
                    val xC = (cat02Inv[0][0] * u + cat02Inv[0][1] * (451.0 * aVal + u) + cat02Inv[0][2] * (cat02Inv[0][2])) * 0.0
                    // Use direct CAM16 inverse from the decompiled code
                    val cam = doubleArrayOf(
                        (u + 451.0 * aVal) / 1403.0,
                        (u - 891.0 * aVal - 261.0 * bVal) / 1403.0,
                        (u - 220.0 * aVal - 6300.0 * bVal) / 1403.0
                    )

                    if (cam[0] >= 0.0 && cam[1] >= 0.0 && cam[2] >= 0.0) {
                        val cat = m8Bc(cam, catSrgb)
                        val yNow = cat[1] * vc.f15321c[0] + cat[1] * vc.f15321c[1] + cat[2] * vc.f15321c[2]
                        if (yNow > 0.0 && (iter == 4 || abs(yNow - yTarget) < 0.002)) {
                            // Converged: gamut-map to sRGB
                            val argb = gamutMap(cam)
                            if (argb != 0) return Cam16(hue, chroma, tone, argb)
                        } else {
                            // Newton step
                            y -= (yNow - yTarget) * y / (yNow * 2.0)
                        }
                    }
                }

                // Fallback: grayscale at target tone
                val g = (LstarFromTone(tone) * 255.0 / 100.0).roundToInt().coerceIn(0, 255)
                return Cam16(hue, chroma, tone,
                    0xFF000000.toInt() or (g shl 16) or (g shl 8) or g)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HCT Solver (j8/a.java + j8/b.java)
    // ═══════════════════════════════════════════════════════════════════

    class HctSolver private constructor(
        private val hue: Double,
        private val chroma: Double,
        private val viewCond: i8bRef
    ) {
        private val cache = HashMap<Int, Int>()

        fun argb(tone: Int): Int {
            val t = tone.coerceIn(0, 100)
            return cache.getOrPut(t) {
                val cam = Cam16.fromHct(hue, chroma, t.toDouble())
                cam.argb
            }
        }

        companion object {
            /** Binary search: find the CAM16 where chroma at a reference tone matches target. */
            fun solveToInt(hue: Double, chroma: Double): HctSolver {
                return HctSolver(hue, chroma, i8bRef(hue, chroma))
            }
        }
    }

    /** Thin ref holder matching i8/b internal state. */
    private class i8bRef(val hue: Double, val chroma: Double)

    // ═══════════════════════════════════════════════════════════════════
    // Viewing Conditions (i8/d.java)
    // ═══════════════════════════════════════════════════════════════════

    class ViewingConditions(
        val a: Double, val b: Double, val c: Double, val d: Double,
        val e: Double, val f: Double, val g: DoubleArray,
        val h: Double, val i: Double, val j: Double,
        val nTick: Double, val nCB: Double,
        val f15321c: DoubleArray = doubleArrayOf(0.2126, 0.7152, 0.0722)
    ) {
        companion object {
            val DEFAULT: ViewingConditions by lazy {
                val xyz = doubleArrayOf(95.047, 100.000, 108.883)
                val aM = i8MatrixA
                val xyzA = doubleArrayOf(
                    aM[0][0] * xyz[0] + aM[0][1] * xyz[1] + aM[0][2] * xyz[2],
                    aM[1][0] * xyz[0] + aM[1][1] * xyz[1] + aM[1][2] * xyz[2],
                    aM[2][0] * xyz[0] + aM[2][1] * xyz[1] + aM[2][2] * xyz[2]
                )
                val d0 = (LstarFromTone(50.0) * 63.66197723675813) / 100.0
                val dMax = max(0.1, 50.0)
                val dB = bClamp(0.59, 0.69, 0.9999999999999998)
                val dA = bClamp(0.0, 1.0, (1.0 - exp((-d0 - 42.0) / 92.0) * 0.2777777777777778) * 1.0)
                val dArr6 = doubleArrayOf(
                    ((100.0 / xyzA[0]) * dA + 1.0) - dA,
                    ((100.0 / xyzA[1]) * dA + 1.0) - dA,
                    ((100.0 / xyzA[2]) * dA + 1.0) - dA
                )
                val d19 = 5.0 * d0
                val d20 = 1.0 / (d19 + 1.0)
                val d21 = d20 * d20 * d20 * d20
                val d22 = 1.0 - d21
                val dCbrt = (kotlin.math.cbrt(d19) * 0.1 * d22 * d22) + (d21 * d0)
                val d23 = LstarFromTone(dMax) / xyz[1]
                val dSqrt = sqrt(d23) + 1.48
                val dPow = 0.725 / d23.pow(0.2)
                val dArr7 = doubleArrayOf(
                    ((dArr6[0] * dCbrt) * xyzA[0] / 100.0).pow(0.42),
                    ((dArr6[1] * dCbrt) * xyzA[1] / 100.0).pow(0.42),
                    ((dArr6[2] * dCbrt) * xyzA[2] / 100.0).pow(0.42)
                )
                fun sig(c: Double): Double = (c * 400.0) / (c + 27.13)
                val dArr8 = doubleArrayOf(sig(dArr7[0]), sig(dArr7[1]), sig(dArr7[2]))
                val d29 = ((dArr8[2] * 0.05) + (dArr8[0] * 2.0) + dArr8[1]) * dPow
                ViewingConditions(
                    a = d29, b = dPow, c = dPow, d = dB, e = 1.0,
                    f = d23, g = dArr6, h = dCbrt, i = dCbrt.pow(0.25),
                    j = dSqrt, nTick = 1.0, nCB = 1.0
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Shared math utilities
    // ═══════════════════════════════════════════════════════════════════

    /** CAT02 adaptation matrix (i8/a.java). */
    private val i8MatrixA = arrayOf(
        doubleArrayOf(0.401288, 0.650173, -0.051461),
        doubleArrayOf(-0.250268, 1.204414, 0.045854),
        doubleArrayOf(-0.002079, 0.048952, 0.953127)
    )

    /** m8/b.java c() — matrix-vector multiply. */
    private fun m8Bc(v: DoubleArray, m: Array<DoubleArray>): DoubleArray {
        return doubleArrayOf(
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]
        )
    }

    /** sRGB → XYZ conversion matrix. */
    private val catSrgb = arrayOf(
        doubleArrayOf(0.41233895, 0.35762064, 0.18051042),
        doubleArrayOf(0.2126, 0.7152, 0.0722),
        doubleArrayOf(0.01932141, 0.11916382, 0.95034478)
    )

    /** CAT02 inverse matrix (m8/b.java c() inverse). */
    private val cat02InvMatrix = arrayOf(
        doubleArrayOf(1373.2198709594231, -1100.4251190754821, -7.278681089101213),
        doubleArrayOf(-271.815969077903, 559.6580465940733, -32.46047482791194),
        doubleArrayOf(1.9622899599665666, -57.173814538844006, 308.7233197812385)
    )

    /** m8/b.java b() — clamp. */
    private fun bClamp(lo: Double, hi: Double, v: Double) =
        if (v < lo) lo else if (v > hi) hi else v

    /** L* from sRGB (m8/a.java b()). */
    private fun Lstar(rl: Double, gl: Double, bl: Double): Double {
        val y = 0.2126 * rl + 0.7152 * gl + 0.0722 * bl
        val fy = if (y > 0.008856451679035631) y.pow(1.0 / 3.0)
        else (903.2962962962963 * y + 16.0) / 116.0
        return 116.0 * fy - 16.0
    }

    /** Tone → L* inverse (m8/a.java d()). */
    private fun LstarFromTone(tone: Double): Double {
        val t = tone.coerceIn(0.0, 100.0)
        val y = (t + 16.0) / 116.0
        val y3 = y * y * y
        return if (y3 <= 0.008856451679035631) ((y * 116.0) - 16.0) / 903.2962962962963 * 100.0
        else y3 * 100.0
    }

    /** sRGB linearization table helper. */
    private fun linearize(c: Double): Double =
        if (c <= 0.040449936) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    /** sRGB gamma encode. */
    private fun delinearize(c: Double): Double {
        val d = if (c <= 0.0031308) c * 12.92 else c.pow(0.4166666666666667) * 1.055 - 0.055
        return d.coerceIn(0.0, 1.0)
    }

    /** XYZ → sRGB. */
    private fun xyzToSrgb(xyz: DoubleArray): Int {
        val r = (delinearize(xyz[0] / 100.0) * 255.0).roundToInt().coerceIn(0, 255)
        val g = (delinearize(xyz[1] / 100.0) * 255.0).roundToInt().coerceIn(0, 255)
        val b = (delinearize(xyz[2] / 100.0) * 255.0).roundToInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    /** Gamut-map CAM16 channel values → sRGB ARGB. */
    private fun gamutMap(cam: DoubleArray): Int {
        // Binary search along the tonal axis to find a valid sRGB color
        // This is a simplified gamut mapper — the full Pixel implementation
        // samples 12 corner points and bisects to find a valid gamut entry.
        // For practical icon colors, direct XYZ→sRGB usually works.
        val xyz = m8Bc(cam, cat02InvMatrix)
        return if (xyz[0] >= 0.0 && xyz[1] >= 0.0 && xyz[2] >= 0.0) {
            xyzToSrgb(xyz)
        } else {
            // Out of gamut: fall back to achromatic at the same tone
            val g = LstarFromTone(cam[1])
            val g8 = (g * 255.0 / 100.0).roundToInt().coerceIn(0, 255)
            0xFF000000.toInt() or (g8 shl 16) or (g8 shl 8) or g8
        }
    }
}
