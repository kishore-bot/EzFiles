package com.ezfiles.reader.presentation.viewers

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.ezfiles.reader.platform.PdfPages
import com.ezfiles.reader.platform.PdfTextMatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CachedPdfPagesTest {
    /** Stands in for a platform bitmap. Mutable, like Android's: a reuse redraws the same memory. */
    private class FakeBitmap(override var width: Int, override var height: Int) : ImageBitmap {
        val capacity = width.toLong() * height * 4
        var page = -1
        override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
        override val hasAlpha: Boolean get() = true
        override val config: ImageBitmapConfig get() = ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
        override fun prepareToDraw() = Unit
    }

    /** Square pages, so a page at width w is w × w pixels: 4·w² bytes. */
    private class FakePages(override val pageCount: Int = 10) : PdfPages {
        val renders = mutableListOf<Int>()
        val reused = mutableListOf<FakeBitmap>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun aspectRatio(index: Int) = 1f
        override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = renderReusing(index, widthPx, null)
        override suspend fun renderReusing(index: Int, widthPx: Int, reuse: ImageBitmap?): ImageBitmap? {
            gate?.await()
            renders += index
            val old = reuse as FakeBitmap?
            val bitmap = if (old != null && old.capacity >= widthPx.toLong() * widthPx * 4) {
                reused += old
                old.apply {
                    width = widthPx
                    height = widthPx
                }
            } else {
                FakeBitmap(widthPx, widthPx)
            }
            return bitmap.apply { page = index }
        }

        override val canSearch = false
        override suspend fun search(index: Int, query: String) = emptyList<PdfTextMatch>()
        override suspend fun hasText(index: Int) = false
        override suspend fun close() = Unit
    }

    /** One 100 × 100 page is 40,000 bytes: the cache holds two, the pool one. */
    private fun cache(pages: FakePages) = CachedPdfPages(pages, maxCacheBytes = 80_000, maxPoolBytes = 40_000)

    @Test
    fun a_cached_page_is_not_rendered_again() = runTest {
        val pages = FakePages()
        val cache = cache(pages)
        val first = cache.hold(0, 100)!!
        cache.release(first)
        assertSame(first, cache.hold(0, 100))
        assertEquals(listOf(0), pages.renders)
    }

    @Test
    fun requests_for_a_page_being_rendered_share_the_render() = runTest {
        val pages = FakePages().apply { gate = CompletableDeferred() }
        val cache = cache(pages)
        val a = async { cache.hold(3, 100) }
        val b = async { cache.prefetch(3, 100) }
        runCurrent()
        pages.gate!!.complete(Unit)
        assertSame(a.await(), cache.hold(3, 100))
        b.await()
        assertEquals(listOf(3), pages.renders)
    }

    @Test
    fun a_waiter_whose_shared_page_was_evicted_before_it_resumed_looks_again() = runTest {
        val pages = FakePages().apply { gate = CompletableDeferred() }
        val cache = cache(pages)
        // The renderer's own caller drops the cache before the waiter gets to run.
        val a = async { cache.hold(3, 100).also { cache.clear() } }
        val b = async { cache.hold(3, 100) }
        runCurrent()
        pages.gate!!.complete(Unit)
        val first = a.await()!!
        val second = b.await()!!
        assertTrue(first !== second)
        assertEquals(listOf(3, 3), pages.renders)
    }

    @Test
    fun a_held_page_is_never_drawn_over_even_after_eviction() = runTest {
        val pages = FakePages()
        val cache = cache(pages)
        val shown = cache.hold(0, 100)!!
        cache.hold(1, 100)
        cache.hold(2, 100) // Evicts page 0 from the cache, but it is still on screen.
        cache.hold(3, 100)
        assertTrue(pages.reused.isEmpty())
        assertEquals(0, (shown.bitmap as FakeBitmap).page)
    }

    @Test
    fun a_released_evicted_page_lends_its_memory_to_the_next_render() = runTest {
        val pages = FakePages()
        val cache = cache(pages)
        val old = cache.hold(0, 100)!!
        cache.release(old)
        cache.hold(1, 100)
        cache.hold(2, 100) // Evicts page 0, which nobody holds: it goes to the pool.
        assertEquals(40_000L, cache.poolBytes)
        val next = cache.hold(3, 100)!!
        assertEquals(listOf(old.bitmap), pages.reused)
        assertEquals(3, (next.bitmap as FakeBitmap).page)
        assertEquals(0L, cache.poolBytes)
    }

    @Test
    fun a_bitmap_from_plain_render_is_never_reused() = runTest {
        val pages = FakePages()
        val cache = cache(pages)
        val handedOut = cache.render(0, 100)
        cache.hold(1, 100)
        cache.hold(2, 100) // Evicts page 0.
        assertEquals(0L, cache.poolBytes)
        cache.hold(3, 100)
        assertTrue(pages.reused.isEmpty())
        assertEquals(0, (handedOut as FakeBitmap).page)
    }

    @Test
    fun a_much_larger_pooled_bitmap_is_not_spent_on_a_thumbnail() = runTest {
        val pages = FakePages()
        val cache = CachedPdfPages(pages, maxCacheBytes = 80_000, maxPoolBytes = 80_000)
        cache.release(cache.hold(0, 100)!!)
        cache.hold(1, 100)
        cache.hold(2, 100) // Page 0 goes to the pool.
        val thumbnail = cache.hold(4, 20)!!
        assertTrue(pages.reused.isEmpty())
        assertEquals(20, thumbnail.bitmap.width)
        assertEquals(40_000L, cache.poolBytes)
    }

    @Test
    fun clearing_drops_cached_and_pooled_pages_but_spares_held_ones() = runTest {
        val pages = FakePages()
        val cache = cache(pages)
        cache.release(cache.hold(0, 100)!!)
        cache.hold(1, 100)
        val shown = cache.hold(2, 100)!!
        cache.clear()
        assertEquals(0L, cache.heldBytes)
        assertEquals(0L, cache.poolBytes)
        assertEquals(2, (shown.bitmap as FakeBitmap).page)
        cache.hold(5, 100)
        assertTrue(pages.reused.isEmpty())
    }

    @Test
    fun a_closed_document_renders_nothing() = runTest {
        val pages = object : PdfPages by FakePages() {
            override suspend fun renderReusing(index: Int, widthPx: Int, reuse: ImageBitmap?): ImageBitmap? = null
        }
        assertNull(CachedPdfPages(pages).hold(0, 100))
    }
}
