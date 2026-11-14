package com.ezfiles.reader.presentation.scan

import com.ezfiles.reader.data.RustImaging
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

@Serializable
enum class ScanFilter(val label: String) {
    Original("Original"),
    Enhanced("Enhanced"),
    Grayscale("Grayscale"),
    BlackAndWhite("Black and white"),
}

/** Cells along the picture's longer side when estimating its lighting. */
private const val LIGHTING_GRID = 40

/** Paper is taken as this bright part of each cell, so print inside a cell doesn't darken it. */
private const val PAPER_PERCENTILE = 0.9f

/** Darker cells are treated as this, so an unlit area isn't amplified into noise. */
private const val MIN_LIGHTING = 48

/** Enhanced: levels at or above this become pure white, which cleans the paper. */
private const val ENHANCED_WHITE = 245

/** Enhanced: the black point never rises past this, so a pale page isn't crushed. */
private const val ENHANCED_MAX_BLACK = 96

/** Black and white: a page with no ink has nothing below this once its lighting is removed. */
private const val MAX_INK_THRESHOLD = 215

/** Rec. 601 brightness of an ARGB pixel, 0..255. */
fun lumaOf(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 77 + g * 150 + b * 29 + 128) shr 8
}

/** Rewrites opaque ARGB [pixels] in place. Costs a few small tables, not a second copy of the picture. */
fun applyScanFilter(filter: ScanFilter, pixels: IntArray, width: Int, height: Int) {
    require(width > 0 && height > 0 && pixels.size >= width.toLong() * height) { "The array is smaller than the picture." }
    when (filter) {
        ScanFilter.Original -> Unit
        ScanFilter.Grayscale -> for (i in 0 until width * height) pixels[i] = gray(lumaOf(pixels[i]))
        ScanFilter.Enhanced -> enhance(pixels, width, height)
        ScanFilter.BlackAndWhite -> blackAndWhite(pixels, width, height)
    }
}

private fun gray(level: Int): Int = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

/** Shadow removal plus a contrast stretch. Keeps colour: stamps and highlights stay as they are. */
private fun enhance(pixels: IntArray, width: Int, height: Int) {
    val lighting = LightingMap.estimate(pixels, width, height)
    val row = FloatArray(width)
    val histogram = IntArray(256)
    for (y in 0 until height) {
        lighting.gainsFor(y, row)
        for (x in 0 until width) {
            val i = y * width + x
            val p = pixels[i]
            val gain = row[x]
            val r = min(255, (((p shr 16) and 0xFF) * gain).toInt())
            val g = min(255, (((p shr 8) and 0xFF) * gain).toInt())
            val b = min(255, ((p and 0xFF) * gain).toInt())
            val flat = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            pixels[i] = flat
            histogram[lumaOf(flat)]++
        }
    }
    val black = min(percentileOf(histogram, 0.02f), ENHANCED_MAX_BLACK)
    val levels = IntArray(256) { (((it - black) * 255) / (ENHANCED_WHITE - black)).coerceIn(0, 255) }
    for (i in 0 until width * height) {
        val p = pixels[i]
        pixels[i] = (0xFF shl 24) or
            (levels[(p shr 16) and 0xFF] shl 16) or
            (levels[(p shr 8) and 0xFF] shl 8) or
            levels[p and 0xFF]
    }
}

/**
 * Lighting removed first, so a shadow across the page doesn't turn into a
 * black patch; then Sauvola's local threshold (in Rust), which keeps faint
 * pencil and small print that one threshold for the whole page would lose.
 */
private fun blackAndWhite(pixels: IntArray, width: Int, height: Int) {
    val lighting = LightingMap.estimate(pixels, width, height)
    val row = FloatArray(width)
    val levels = ByteArray(width * height)
    for (y in 0 until height) {
        lighting.gainsFor(y, row)
        for (x in 0 until width) {
            val i = y * width + x
            levels[i] = min(255, (lumaOf(pixels[i]) * row[x]).toInt()).toByte()
        }
    }
    clearScanEdgeBands(levels, width, height)
    val ink = RustImaging.binarize(levels, width, height)
    for (i in 0 until width * height) {
        // Near-white paper stays white whatever its neighbours, so no speckles appear on blank areas.
        val paper = (levels[i].toInt() and 0xFF) >= MAX_INK_THRESHOLD
        pixels[i] = if (ink[i].toInt() == 0 && !paper) gray(0) else gray(255)
    }
}

/**
 * A slightly loose crop can leave a thin strip of desk against the picture edge.
 * Only clear a near-continuous, narrow strip with a clean paper margin behind it.
 * Work before thresholding so the strip cannot also darken neighbouring paper.
 * Ambiguous edges (text, broad shadows or no paper margin) are left for manual crop.
 */
internal fun clearScanEdgeBands(levels: ByteArray, width: Int, height: Int) {
    val band = min(24, min(width, height) / 64)
    if (band == 0) return
    val margin = max(3, band / 2)
    val runs = IntArray(max(width, height))
    for (side in 0..3) {
        val length = if (side < 2) height else width
        fun index(along: Int, depth: Int): Int = when (side) {
            0 -> along * width + depth
            1 -> along * width + width - 1 - depth
            2 -> depth * width + along
            else -> (height - 1 - depth) * width + along
        }
        var continuous = 0
        var longest = 0
        for (along in 0 until length) {
            var depth = 0
            while (depth <= band && (levels[index(along, depth)].toInt() and 0xFF) < 100) depth++
            val separated = depth in 1..band && (depth until depth + margin).all {
                (levels[index(along, it)].toInt() and 0xFF) >= MAX_INK_THRESHOLD
            }
            runs[along] = if (separated) depth else 0
            continuous = if (separated) continuous + 1 else 0
            longest = max(longest, continuous)
        }
        // Sparse letters and edge marks do not qualify as a background strip.
        if (longest.toLong() * 5 < length.toLong() * 4) continue
        for (along in 0 until length) {
            for (depth in 0 until runs[along]) levels[index(along, depth)] = 255.toByte()
        }
    }
}

/** The level from which a share [fraction] of the histogram's samples lies below. */
internal fun percentileOf(histogram: IntArray, fraction: Float): Int {
    val total = histogram.sumOf { it.toLong() }
    val wanted = (total * fraction).toLong()
    var seen = 0L
    for (level in histogram.indices) {
        seen += histogram[level]
        if (seen > wanted) return level
    }
    return histogram.lastIndex
}

/**
 * Otsu's method: the level that best splits a 256-bin histogram into two
 * groups. Levels below the result are the darker group.
 */
internal fun otsuThreshold(histogram: IntArray): Int {
    val total = histogram.sumOf { it.toLong() }
    if (total == 0L) return 0
    var allSum = 0.0
    for (level in histogram.indices) allSum += level.toDouble() * histogram[level]
    var darkCount = 0L
    var darkSum = 0.0
    var best = 0.0
    var bestLevel = 0
    for (level in 0 until histogram.lastIndex) {
        darkCount += histogram[level]
        if (darkCount == 0L) continue
        val lightCount = total - darkCount
        if (lightCount == 0L) break
        darkSum += level.toDouble() * histogram[level]
        val difference = darkSum / darkCount - (allSum - darkSum) / lightCount
        val between = darkCount.toDouble() * lightCount * difference * difference
        if (between > best) {
            best = between
            bestLevel = level + 1
        }
    }
    return bestLevel
}

/**
 * How brightly each part of the page is lit, on a coarse grid: in each cell the
 * paper's brightness, widened over neighbours (a headline can fill a cell) and
 * smoothed. A coarse grid keeps this small at any picture size.
 */
internal class LightingMap private constructor(
    private val width: Int,
    private val columns: Int,
    private val rows: Int,
    private val cell: Int,
    private val levels: IntArray,
) {
    /** Fills [out] with the factor that brings row [y]'s lighting up to full white. */
    fun gainsFor(y: Int, out: FloatArray) {
        val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (rows - 1).toFloat())
        val top = fy.toInt()
        val bottom = min(top + 1, rows - 1)
        val mixY = fy - top
        for (x in 0 until width) {
            val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (columns - 1).toFloat())
            val left = fx.toInt()
            val right = min(left + 1, columns - 1)
            val mixX = fx - left
            val above = levels[top * columns + left] * (1 - mixX) + levels[top * columns + right] * mixX
            val below = levels[bottom * columns + left] * (1 - mixX) + levels[bottom * columns + right] * mixX
            out[x] = 255f / (above * (1 - mixY) + below * mixY)
        }
    }

    companion object {
        fun estimate(pixels: IntArray, width: Int, height: Int): LightingMap {
            val cell = max(1, (max(width, height) + LIGHTING_GRID - 1) / LIGHTING_GRID)
            val columns = (width + cell - 1) / cell
            val rows = (height + cell - 1) / cell
            val paper = IntArray(columns * rows)
            val histogram = IntArray(256)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    histogram.fill(0)
                    for (y in (row * cell) until min((row + 1) * cell, height)) {
                        for (x in (column * cell) until min((column + 1) * cell, width)) {
                            histogram[lumaOf(pixels[y * width + x])]++
                        }
                    }
                    paper[row * columns + column] = max(percentileOf(histogram, PAPER_PERCENTILE), MIN_LIGHTING)
                }
            }
            val widened = neighbourhood(paper, columns, rows) { values, count -> var m = 0; for (i in 0 until count) m = max(m, values[i]); m }
            var smooth = widened
            repeat(2) { smooth = neighbourhood(smooth, columns, rows) { values, count -> values.take(count).sum() / count } }
            return LightingMap(width, columns, rows, cell, smooth)
        }

        private inline fun neighbourhood(source: IntArray, columns: Int, rows: Int, combine: (IntArray, Int) -> Int): IntArray {
            val result = IntArray(source.size)
            val values = IntArray(9)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    var count = 0
                    for (r in max(row - 1, 0)..min(row + 1, rows - 1)) {
                        for (c in max(column - 1, 0)..min(column + 1, columns - 1)) {
                            values[count++] = source[r * columns + c]
                        }
                    }
                    result[row * columns + column] = combine(values, count)
                }
            }
            return result
        }
    }
}
