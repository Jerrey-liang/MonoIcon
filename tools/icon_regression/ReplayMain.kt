package com.jerrey.monoicon.tools.iconregression

import com.jerrey.monoicon.theme.mask.GeneratedMaskProcessor
import com.jerrey.monoicon.theme.render.GeneratedIconBorder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Standalone JVM adapter; production pixel/geometry code is compiled unchanged. */
fun main(args: Array<String>) {
    require(args.size == 3) { "requests.tsv geometries.tsv results.tsv" }
    val geometries = File(args[1]).readLines().filter { it.isNotBlank() }.map {
        it.split('\t').map(String::toFloat)
    }
    File(args[2]).bufferedWriter().use { result ->
        result.appendLine("case\twidth\theight\tradius\tratio\tqualified\tscale\tratio_invariant")
        for (line in File(args[0]).readLines().filter { it.isNotBlank() }) {
            val p = line.split('\t')
            val id = p[0]
            val width = p[1].toInt()
            val height = p[2].toInt()
            val mode = p[3]
            val inverted = p[4].toBooleanStrict()
            val pixels = DataInputStream(File(p[5]).inputStream().buffered()).use { input ->
                IntArray(width * height) { input.readInt() }
            }
            val baseline = if (p[6] == "-") null else File(p[6]).readBytes()
            val tonal = if (mode == "tonal") {
                val method = GeneratedMaskProcessor::class.java.getDeclaredMethod(
                    "tonal", IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                )
                method.invoke(GeneratedMaskProcessor, pixels, width, height) as IntArray
            } else null
            fun process(ratio: Float): ByteArray = when (mode) {
                "extract" -> GeneratedMaskProcessor.extract(pixels, width, height, inverted, ratio)
                "refine" -> GeneratedMaskProcessor.refine(pixels, width, height, requireNotNull(baseline), ratio)
                "mask" -> ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }
                "tonal" -> ByteArray(pixels.size) { (requireNotNull(tonal)[it] ushr 24).toByte() }
                else -> error("Unknown operation $mode")
            }
            val mask = process(1f)
            File(p[7]).writeBytes(mask)
            if (tonal != null) {
                fun writeArgb(suffix: String, values: IntArray) {
                    DataOutputStream(File(p[7].removeSuffix(".alpha") + suffix).outputStream().buffered()).use { output ->
                        values.forEach(output::writeInt)
                    }
                }
                writeArgb(".argb", tonal)
                val paletteClass = Class.forName("com.jerrey.monoicon.theme.render.GeneratedTonePalette")
                val palette = paletteClass.getField("INSTANCE").get(null)
                val map = paletteClass.getDeclaredMethod("map", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                for ((name, foreground, background) in listOf(
                    Triple("light", 0xFF2C2031.toInt(), 0xFFF6DAFD.toInt()),
                    Triple("dark", 0xFFECD5F2.toInt(), 0xFF35263C.toInt()),
                )) {
                    val mapped = IntArray(tonal.size) { map.invoke(palette, tonal[it], foreground, background) as Int }
                    writeArgb(".$name.argb", mapped)
                }
            }
            val invariant = geometries.map { it[3] }.distinct().all { process(it).contentEquals(mask) }
            val borderAlpha = if (p[8] == "-") mask else File(p[8]).readBytes()
            val borderWidth = p[9].toInt()
            val borderHeight = p[10].toInt()
            val border = GeneratedIconBorder.fromAlpha(borderAlpha, borderWidth, borderHeight)
            for ((w, h, radius, ratio) in geometries) {
                val scale = border?.glyphScale(w, h, radius, ratio) ?: 1f
                result.appendLine("$id\t$w\t$h\t$radius\t$ratio\t${border != null}\t$scale\t$invariant")
            }
        }
    }
}
