package com.ezfiles.reader.scan

import com.ezfiles.reader.presentation.scan.ScanFilter
import com.ezfiles.reader.presentation.scan.applyScanFilter
import com.ezfiles.reader.presentation.scan.clearScanEdgeBands
import com.ezfiles.reader.presentation.scan.lumaOf
import com.ezfiles.reader.presentation.scan.otsuThreshold
import com.ezfiles.reader.presentation.scan.percentileOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScanFiltersTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun gray(level: Int) = rgb(level, level, level)

    private val w = 200
    private val h = 200

    /** Paper that darkens from left to right, like a shadow, with optional dark text bars. */
    private fun shadowedPage(text: Boolean): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val paper = 230 - x * 130 / w
        val ink = text && y % 20 < 4 && x % 12 < 8
        gray(if (ink) paper / 4 else paper)
    }

    @Test
    fun luma_weights_green_most_and_keeps_the_ends() {
        assertEquals(0, lumaOf(rgb(0, 0, 0)))
        assertEquals(255, lumaOf(rgb(255, 255, 255)))
        assertTrue(lumaOf(rgb(0, 255, 0)) > lumaOf(rgb(255, 0, 0)))
        assertTrue(lumaOf(rgb(255, 0, 0)) > lumaOf(rgb(0, 0, 255)))
    }

    @Test
    fun original_changes_nothing() {
        val pixels = intArrayOf(rgb(10, 20, 30), rgb(200, 100, 50))
        applyScanFilter(ScanFilter.Original, pixels, 2, 1)
        assertContentEquals(intArrayOf(rgb(10, 20, 30), rgb(200, 100, 50)), pixels)
    }

    @Test
    fun grayscale_sets_every_channel_to_the_luma() {
        val source = rgb(200, 100, 50)
        val pixels = intArrayOf(source)
        applyScanFilter(ScanFilter.Grayscale, pixels, 1, 1)
        assertEquals(gray(lumaOf(source)), pixels[0])
    }

    @Test
    fun enhanced_removes_a_shadow_from_blank_paper() {
        val pixels = shadowedPage(text = false)
        applyScanFilter(ScanFilter.Enhanced, pixels, w, h)
        val levels = pixels.map(::lumaOf)
        // Before: 100 to 230. After: close to even, and bright.
        assertTrue(levels.max() - levels.min() < 30, "range ${levels.min()}..${levels.max()}")
        assertTrue(levels.min() > 220, "darkest ${levels.min()}")
    }

    @Test
    fun enhanced_keeps_text_dark_in_the_shadow() {
        val pixels = shadowedPage(text = true)
        applyScanFilter(ScanFilter.Enhanced, pixels, w, h)
        val inkInShadow = lumaOf(pixels[2 * w + 192])
        val paperInShadow = lumaOf(pixels[10 * w + 190])
        assertTrue(inkInShadow < 90, "ink $inkInShadow")
        assertTrue(paperInShadow > 200, "paper $paperInShadow")
    }

    @Test
    fun enhanced_keeps_colour() {
        val pixels = IntArray(w * h) { if (it % w in 50..150 && it / w in 50..150) rgb(200, 40, 40) else gray(240) }
        applyScanFilter(ScanFilter.Enhanced, pixels, w, h)
        val stamp = pixels[100 * w + 100]
        val r = (stamp shr 16) and 0xFF
        val g = (stamp shr 8) and 0xFF
        assertTrue(r > g + 60, "red $r, green $g")
    }

    @Test
    fun black_and_white_gives_only_pure_black_and_white() {
        val pixels = shadowedPage(text = true)
        applyScanFilter(ScanFilter.BlackAndWhite, pixels, w, h)
        assertTrue(pixels.all { it == gray(0) || it == gray(255) })
    }

    @Test
    fun black_and_white_separates_ink_from_paper_across_a_shadow() {
        val pixels = shadowedPage(text = true)
        applyScanFilter(ScanFilter.BlackAndWhite, pixels, w, h)
        assertEquals(gray(0), pixels[2 * w + 2], "ink in the light")
        assertEquals(gray(0), pixels[2 * w + 192], "ink in the shadow")
        assertEquals(gray(255), pixels[10 * w + 2], "paper in the light")
        assertEquals(gray(255), pixels[10 * w + 190], "paper in the shadow")
    }

    @Test
    fun black_and_white_leaves_a_blank_shadowed_page_white() {
        val pixels = shadowedPage(text = false)
        applyScanFilter(ScanFilter.BlackAndWhite, pixels, w, h)
        assertTrue(pixels.all { it == gray(255) })
    }

    @Test
    fun black_and_white_cleans_a_loose_crop_without_erasing_interior_ink() {
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            gray(if (x < 2 || x in 50..90 && y in 70..74) 20 else 240)
        }
        applyScanFilter(ScanFilter.BlackAndWhite, pixels, w, h)
        assertTrue((0 until h).all { y -> pixels[y * w] == gray(255) && pixels[y * w + 1] == gray(255) })
        assertEquals(gray(0), pixels[72 * w + 60])
    }

    @Test
    fun edge_cleanup_removes_thin_sloping_background_on_every_side_without_resizing() {
        val width = 256
        val height = 192
        for (side in 0..3) {
            val levels = ByteArray(width * height) { 240.toByte() }
            val length = if (side < 2) height else width
            for (along in 0 until length) {
                // A slightly sloping paper edge, not a fixed rectangular inset.
                val thickness = 1 + along * 2 / length
                for (depth in 0 until thickness) {
                    val i = when (side) {
                        0 -> along * width + depth
                        1 -> along * width + width - 1 - depth
                        2 -> depth * width + along
                        else -> (height - 1 - depth) * width + along
                    }
                    levels[i] = 20
                }
            }
            val text = 80 * width + 80
            levels[text] = 30
            clearScanEdgeBands(levels, width, height)
            assertEquals(30, levels[text].toInt(), "interior ink on side $side")
            assertEquals(1, levels.count { (it.toInt() and 0xFF) < 100 }, "background on side $side")
            assertEquals(width * height, levels.size)
        }
    }

    @Test
    fun edge_cleanup_preserves_sparse_edge_text_and_broad_or_unseparated_dark_regions() {
        val width = 256
        val height = 192
        val fixtures = listOf(
            ByteArray(width * height) { i -> if (i % width < 2 && i / width % 16 < 4) 20 else 240.toByte() },
            ByteArray(width * height) { i -> if (i % width < 8) 20 else 240.toByte() },
            ByteArray(width * height) { i -> when (i % width) { 0, 1 -> 20; 2 -> 240.toByte(); else -> 130.toByte() } },
            ByteArray(width * height) { i -> if (i % width in 1..2) 20 else 240.toByte() },
        )
        for (levels in fixtures) {
            val original = levels.copyOf()
            clearScanEdgeBands(levels, width, height)
            assertContentEquals(original, levels)
        }
    }

    @Test
    fun edge_cleanup_does_not_touch_small_or_low_contrast_pages() {
        for ((width, height) in listOf(1 to 1, 1 to 256, 256 to 1, 32 to 32, 192 to 256)) {
            val levels = ByteArray(width * height) { i -> if (i % width < 2) 150.toByte() else 190.toByte() }
            val original = levels.copyOf()
            clearScanEdgeBands(levels, width, height)
            assertContentEquals(original, levels)
        }
    }

    @Test
    fun filters_handle_a_single_pixel_and_extreme_shapes() {
        for (filter in ScanFilter.entries) {
            applyScanFilter(filter, intArrayOf(gray(128)), 1, 1)
            applyScanFilter(filter, IntArray(1000) { gray(it % 256) }, 1000, 1)
            applyScanFilter(filter, IntArray(1000) { gray(it % 256) }, 1, 1000)
            applyScanFilter(filter, IntArray(100) { 0 }, 10, 10)
        }
    }

    @Test
    fun a_short_array_is_refused() {
        assertFailsWith<IllegalArgumentException> { applyScanFilter(ScanFilter.Enhanced, IntArray(3), 2, 2) }
    }

    @Test
    fun otsu_splits_two_clear_groups() {
        val histogram = IntArray(256)
        histogram[40] = 100
        histogram[200] = 300
        val threshold = otsuThreshold(histogram)
        assertTrue(threshold in 41..200, "threshold $threshold")
    }

    @Test
    fun otsu_of_nothing_or_one_level_is_harmless() {
        assertEquals(0, otsuThreshold(IntArray(256)))
        assertEquals(0, otsuThreshold(IntArray(256).also { it[77] = 10 }))
    }

    @Test
    fun percentile_finds_the_level() {
        val histogram = IntArray(256)
        histogram[10] = 90
        histogram[250] = 10
        assertEquals(10, percentileOf(histogram, 0.5f))
        assertEquals(250, percentileOf(histogram, 0.95f))
    }
}
