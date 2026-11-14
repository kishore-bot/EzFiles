package com.ezfiles.reader.presentation.viewers

import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.platform.PdfPages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToLong

/**
 * Rendered pages and thumbnails of one open PDF, so scrolling back, switching
 * view modes or reaching a prefetched page does not render again. Call
 * everything from the main thread, like the composables that use it.
 *
 * - **Cache:** an LRU bounded by pixel memory.
 * - **Holders:** [hold] counts the composables showing a page, [release] uncounts.
 * - **Pool:** a bitmap is drawn over for another page only once it is out of
 *   the cache *and* nobody holds it. Callers release a frame after a page
 *   leaves the screen, so no recorded frame still draws it. Bitmaps from plain
 *   [render] are never reused, because those callers never say when they are done.
 * - **Shared renders:** asking for a page already being rendered waits for that render.
 */
internal class CachedPdfPages(
    private val pages: PdfPages,
    private val maxCacheBytes: Long = MAX_CACHE_BYTES,
    private val maxPoolBytes: Long = MAX_POOL_BYTES,
) : PdfPages by pages {
    class Page internal constructor(val bitmap: ImageBitmap, internal val key: Long, internal val bytes: Long) {
        internal var holders = 0
        internal var cached = true

        /** Handed out by [render]: may be in use anywhere, so never reused. */
        internal var escaped = false
    }

    private val cache = LinkedHashMap<Long, Page>(16, 0.75f, true)
    private var cachedBytes = 0L
    private val pool = ArrayDeque<Page>()
    private var pooledBytes = 0L
    private val rendering = HashMap<Long, CompletableDeferred<Page?>>()

    /** Width over height per page, from what was rendered, to pick a pooled bitmap's size. */
    private val aspects = HashMap<Int, Float>()
    private var lastAspect = A4_ASPECT

    internal val heldBytes: Long get() = cachedBytes
    internal val poolBytes: Long get() = pooledBytes

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? =
        page(index, widthPx)?.also { it.escaped = true }?.bitmap

    /** A page for a composable to show. Pair with [release]. */
    suspend fun hold(index: Int, widthPx: Int): Page? = page(index, widthPx)?.also { it.holders++ }

    fun release(page: Page) {
        page.holders--
        if (page.holders == 0 && !page.cached) recycle(page)
    }

    /** Renders a page into the cache before it is needed, unless it is there or on its way. */
    suspend fun prefetch(index: Int, widthPx: Int) {
        if (index in 0 until pageCount) page(index, widthPx)
    }

    /** Drops cached and pooled pages; on memory pressure. Pages on screen stay with their holders. */
    fun clear() {
        cache.values.forEach { it.cached = false }
        cache.clear()
        cachedBytes = 0
        pool.clear()
        pooledBytes = 0
    }

    private suspend fun page(index: Int, widthPx: Int): Page? {
        val key = index.toLong() shl 32 or widthPx.toLong()
        while (true) {
            cache[key]?.let { return it }
            val running = rendering[key] ?: break
            try {
                val page = running.await()
                // Evicted, and maybe pooled, before this caller resumed: look again.
                if (page == null || page.cached) return page
            } catch (e: CancellationException) {
                // Rethrows if this caller was cancelled; otherwise the renderer
                // was, and this caller renders instead.
                currentCoroutineContext().ensureActive()
            }
        }
        val done = CompletableDeferred<Page?>()
        rendering[key] = done
        val page = try {
            val reuse = takeFromPool(index, widthPx)
            pages.renderReusing(index, widthPx, reuse?.bitmap)?.let { bitmap ->
                val needed = bitmap.width.toLong() * bitmap.height * BYTES_PER_PIXEL
                // The platform's rule: the old memory is used when the new page fits it.
                Page(bitmap, key, if (reuse != null && reuse.bytes >= needed) reuse.bytes else needed)
            }
        } catch (e: Throwable) {
            rendering.remove(key)
            done.completeExceptionally(e)
            throw e
        }
        rendering.remove(key)
        if (page != null) {
            val aspect = page.bitmap.width.toFloat() / page.bitmap.height.coerceAtLeast(1)
            aspects[index] = aspect
            lastAspect = aspect
            cache[key] = page
            cachedBytes += page.bytes
            evict(keep = key)
        }
        done.complete(page)
        return page
    }

    private fun evict(keep: Long) {
        val eldestFirst = cache.values.iterator()
        while (cachedBytes > maxCacheBytes && eldestFirst.hasNext()) {
            val page = eldestFirst.next()
            if (page.key == keep) continue
            eldestFirst.remove()
            cachedBytes -= page.bytes
            page.cached = false
            if (page.holders == 0) recycle(page)
        }
    }

    private fun recycle(page: Page) {
        if (page.escaped || page.bytes > maxPoolBytes) return
        pool.addLast(page)
        pooledBytes += page.bytes
        while (pooledBytes > maxPoolBytes) pooledBytes -= pool.removeFirst().bytes
    }

    /** The smallest pooled bitmap that should hold the page, without wasting more than half of it. */
    private fun takeFromPool(index: Int, widthPx: Int): Page? {
        if (pool.isEmpty()) return null
        val aspect = aspects[index] ?: lastAspect
        val needed = widthPx.toLong() * (widthPx / aspect).roundToLong().coerceAtLeast(1) * BYTES_PER_PIXEL
        val best = pool.filter { it.bytes in needed..needed * 2 }.minByOrNull { it.bytes } ?: return null
        pool.remove(best)
        pooledBytes -= best.bytes
        return best
    }

    private companion object {
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024

        /** About four full-width phone pages. */
        const val MAX_POOL_BYTES = 16L * 1024 * 1024
        const val BYTES_PER_PIXEL = 4L
        const val A4_ASPECT = 1f / 1.414f
    }
}
