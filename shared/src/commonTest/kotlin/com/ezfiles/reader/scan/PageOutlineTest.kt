package com.ezfiles.reader.scan

import com.ezfiles.reader.presentation.scan.PageQuad
import com.ezfiles.reader.presentation.scan.ScanPoint
import com.ezfiles.reader.presentation.scan.detectPageOutline
import com.ezfiles.reader.presentation.scan.orderCorners
import com.ezfiles.reader.presentation.scan.pageOutputSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageOutlineTest {
    private val w = 288
    private val h = 384

    /** Paints a page, given in fractions, over a background. */
    private fun picture(
        quad: PageQuad,
        page: Int = 225,
        background: (Int, Int) -> Int = { _, _ -> 45 },
        inkOnPage: Boolean = false,
    ): ByteArray {
        val corners = quad.corners.map { ScanPoint(it.x * w, it.y * h) }
        return ByteArray(w * h) { i ->
            val x = i % w + 0.5f
            val y = i / w + 0.5f
            val inside = corners.indices.all { c ->
                val a = corners[c]
                val b = corners[(c + 1) % 4]
                (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) >= 0f
            }
            val value = when {
                !inside -> background(i % w, i / w)
                // Lines of "text": short dark dashes, as real print breaks up into.
                inkOnPage && (i / w) % 14 < 3 && (i % w) % 11 < 7 -> 40
                else -> page
            }
            value.toByte()
        }
    }

    private fun assertNear(expected: PageQuad, actual: PageQuad?, tolerance: Float = 0.02f) {
        assertNotNull(actual, "No outline found")
        expected.corners.zip(actual.corners).forEach { (e, a) ->
            assertTrue(abs(e.x - a.x) <= tolerance && abs(e.y - a.y) <= tolerance, "Expected $expected, got $actual")
        }
    }

    private val upright = PageQuad(ScanPoint(0.15f, 0.1f), ScanPoint(0.85f, 0.1f), ScanPoint(0.85f, 0.9f), ScanPoint(0.15f, 0.9f))

    @Test
    fun finds_an_upright_page() = assertNear(upright, detectPageOutline(picture(upright), w, h))

    @Test
    fun finds_a_page_in_perspective() {
        val quad = PageQuad(ScanPoint(0.28f, 0.14f), ScanPoint(0.74f, 0.1f), ScanPoint(0.9f, 0.88f), ScanPoint(0.1f, 0.92f))
        assertNear(quad, detectPageOutline(picture(quad), w, h))
    }

    @Test
    fun finds_a_turned_page() {
        val angle = 0.35f
        val points = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).map { (sx, sy) ->
            // A rectangle in pixels, turned, then back to fractions.
            val px = sx * 85f
            val py = sy * 120f
            ScanPoint((w / 2f + px * cos(angle) - py * sin(angle)) / w, (h / 2f + px * sin(angle) + py * cos(angle)) / h)
        }
        val quad = orderCorners(points)
        assertNear(quad, detectPageOutline(picture(quad), w, h))
    }

    @Test
    fun printed_text_does_not_pull_the_outline_inward() {
        assertNear(upright, detectPageOutline(picture(upright, inkOnPage = true), w, h))
    }

    @Test
    fun finds_a_low_contrast_page() {
        assertNear(upright, detectPageOutline(picture(upright, page = 200, background = { _, _ -> 160 }), w, h))
    }

    @Test
    fun copes_with_a_lighting_gradient_and_noise() {
        val random = Random(7)
        val source = picture(upright, background = { x, y -> 30 + (x + y) / 16 })
        val noisy = ByteArray(source.size) { ((source[it].toInt() and 0xFF) + random.nextInt(-8, 9)).coerceIn(0, 255).toByte() }
        assertNear(upright, detectPageOutline(noisy, w, h), 0.03f)
    }

    @Test
    fun a_uniform_picture_has_no_page() {
        assertNull(detectPageOutline(ByteArray(w * h) { 120 }, w, h))
    }

    @Test
    fun pure_noise_has_no_page() {
        val random = Random(11)
        assertNull(detectPageOutline(ByteArray(w * h) { random.nextInt(256).toByte() }, w, h))
    }

    @Test
    fun a_small_shape_is_not_a_page() {
        val small = PageQuad(ScanPoint(0.4f, 0.4f), ScanPoint(0.6f, 0.4f), ScanPoint(0.6f, 0.6f), ScanPoint(0.4f, 0.6f))
        assertNull(detectPageOutline(picture(small), w, h))
    }

    @Test
    fun a_page_filling_the_picture_has_no_visible_edges() {
        assertNull(detectPageOutline(picture(PageQuad.FullFrame), w, h))
    }

    @Test
    fun too_small_or_oversized_inputs_are_refused() {
        assertNull(detectPageOutline(ByteArray(100), 10, 10))
        assertNull(detectPageOutline(ByteArray(2_000_000), 2000, 1000))
        assertFailsWith<IllegalArgumentException> { detectPageOutline(ByteArray(10), 10, 10) }
    }

    @Test
    fun corners_are_ordered_from_any_starting_order() {
        val tl = ScanPoint(0.1f, 0.2f)
        val tr = ScanPoint(0.9f, 0.1f)
        val br = ScanPoint(0.8f, 0.9f)
        val bl = ScanPoint(0.2f, 0.8f)
        val expected = PageQuad(tl, tr, br, bl)
        val all = listOf(tl, tr, br, bl)
        for (a in all) for (b in all - a) for (c in all - a - b) {
            assertEquals(expected, orderCorners(listOf(a, b, c, (all - a - b - c).single())))
        }
    }

    @Test
    fun ordering_needs_four_points() {
        assertFailsWith<IllegalArgumentException> { orderCorners(listOf(ScanPoint(0f, 0f))) }
    }

    @Test
    fun a_full_frame_is_convex_with_full_area() {
        assertTrue(PageQuad.FullFrame.isConvex())
        assertEquals(1f, PageQuad.FullFrame.area())
    }

    @Test
    fun rotating_shifts_corners_and_four_turns_return() {
        val turned = upright.rotatedClockwise()
        assertEquals(upright.bottomLeft, turned.topLeft)
        assertEquals(upright.topLeft, turned.topRight)
        assertTrue(turned.isConvex())
        assertEquals(upright, turned.rotatedClockwise().rotatedClockwise().rotatedClockwise())
    }

    @Test
    fun moving_a_corner_clamps_to_the_picture() {
        val moved = upright.moveCorner(0, ScanPoint(-1f, -1f))
        assertEquals(ScanPoint(0f, 0f), moved.topLeft)
    }

    @Test
    fun moving_a_corner_cannot_fold_the_outline() {
        assertEquals(upright, upright.moveCorner(0, ScanPoint(0.9f, 0.95f)))
    }

    @Test
    fun moving_a_corner_cannot_collapse_the_outline() {
        val tiny = PageQuad(ScanPoint(0.5f, 0.5f), ScanPoint(0.61f, 0.5f), ScanPoint(0.61f, 0.61f), ScanPoint(0.5f, 0.61f))
        assertEquals(tiny, tiny.moveCorner(2, ScanPoint(0.52f, 0.52f)))
    }

    @Test
    fun output_size_uses_the_longer_edges_and_respects_the_limit() {
        assertEquals(1000 to 2000, pageOutputSize(PageQuad.FullFrame, 1000, 2000, 2600))
        assertEquals(1300 to 2600, pageOutputSize(PageQuad.FullFrame, 2000, 4000, 2600))
        val narrowTop = PageQuad(ScanPoint(0.25f, 0f), ScanPoint(0.75f, 0f), ScanPoint(1f, 1f), ScanPoint(0f, 1f))
        assertEquals(1000, pageOutputSize(narrowTop, 1000, 1000, 2600).first)
    }

    @Test
    fun output_size_is_never_zero() {
        val point = ScanPoint(0.5f, 0.5f)
        assertEquals(1 to 1, pageOutputSize(PageQuad(point, point, point, point), 100, 100, 2600))
    }
}
