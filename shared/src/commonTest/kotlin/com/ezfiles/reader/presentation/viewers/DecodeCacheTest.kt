package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.platform.MemoryPressure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecodeCacheTest {
    /** A fake picture whose size in bytes is its value, so tests need no bitmaps. */
    private class Harness(scope: CoroutineScope, maxEntries: Int = 32, maxBytes: Long = 100) {
        val computed = mutableListOf<String>()
        val cache = DecodeCache<Long>(scope, maxEntries, maxBytes) { it }

        suspend fun get(key: String, size: Long): Long? = cache.get(key) {
            computed += key
            size
        }
    }

    private fun kotlinx.coroutines.test.TestScope.cacheScope() =
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun a_cached_result_is_not_computed_again() = runTest {
        val h = Harness(cacheScope())
        assertEquals(10L, h.get("a", 10))
        assertEquals(10L, h.get("a", 10))
        assertEquals(listOf("a"), h.computed)
        assertEquals(10L, h.cache.heldBytes)
    }

    @Test
    fun the_byte_limit_evicts_the_least_recently_used() = runTest {
        val h = Harness(cacheScope())
        h.get("a", 40)
        h.get("b", 40)
        h.get("a", 40) // Now "b" is the oldest.
        h.get("c", 40)
        assertEquals(80L, h.cache.heldBytes)
        h.get("a", 40)
        h.get("c", 40)
        assertEquals(listOf("a", "b", "c"), h.computed)
        h.get("b", 40)
        assertEquals(listOf("a", "b", "c", "b"), h.computed)
        assertEquals(80L, h.cache.heldBytes)
    }

    @Test
    fun one_new_result_can_evict_several() = runTest {
        val h = Harness(cacheScope())
        h.get("a", 30)
        h.get("b", 30)
        h.get("c", 30)
        h.get("d", 90)
        assertEquals(90L, h.cache.heldBytes)
    }

    @Test
    fun many_small_results_fit_where_few_big_ones_do() = runTest {
        val h = Harness(cacheScope(), maxEntries = 1000)
        repeat(50) { h.get("small$it", 2) }
        assertEquals(100L, h.cache.heldBytes)
        repeat(50) { h.get("small$it", 2) }
        assertEquals(50, h.computed.size)
        h.get("big", 50)
        assertEquals(100L, h.cache.heldBytes)
        // The 25 least recently used made room; the newest small ones are still there.
        h.get("small49", 2)
        h.get("small0", 2)
        assertEquals(listOf("big", "small0"), h.computed.drop(50))
    }

    @Test
    fun a_result_exactly_at_the_limit_is_kept() = runTest {
        val h = Harness(cacheScope())
        h.get("a", 10)
        h.get("full", 100)
        assertEquals(100L, h.cache.heldBytes)
        h.get("full", 100)
        assertEquals(listOf("a", "full"), h.computed)
    }

    @Test
    fun a_result_over_the_limit_alone_is_returned_but_not_kept() = runTest {
        val h = Harness(cacheScope())
        h.get("small", 10)
        assertEquals(500L, h.get("huge", 500))
        // It pushed nothing out and holds nothing itself.
        assertEquals(10L, h.cache.heldBytes)
        assertEquals(500L, h.get("huge", 500))
        h.get("small", 10)
        assertEquals(listOf("small", "huge", "huge"), h.computed)
    }

    @Test
    fun callers_waiting_on_an_oversized_result_still_share_it() = runTest {
        val h = Harness(cacheScope())
        val gate = CompletableDeferred<Long>()
        var runs = 0
        val compute: suspend () -> Long? = {
            runs++
            gate.await()
        }
        val first = async(UnconfinedTestDispatcher(testScheduler)) { h.cache.get("huge", compute) }
        val second = async(UnconfinedTestDispatcher(testScheduler)) { h.cache.get("huge", compute) }
        gate.complete(500)
        assertEquals(500L, first.await())
        assertEquals(500L, second.await())
        assertEquals(1, runs)
        assertEquals(0L, h.cache.heldBytes)
    }

    @Test
    fun memory_pressure_empties_a_registered_cache() = runTest {
        val h = Harness(cacheScope())
        h.get("a", 30)
        h.get("b", 30)
        val unregister = MemoryPressure.register(h.cache::clearFinished)
        try {
            MemoryPressure.trim()
        } finally {
            unregister()
        }
        assertEquals(0L, h.cache.heldBytes)
        h.get("a", 30)
        assertEquals(listOf("a", "b", "a"), h.computed)
        // The byte count starts again from nothing, so the limit still holds after a trim.
        h.get("c", 30)
        h.get("d", 30)
        h.get("e", 30)
        assertEquals(90L, h.cache.heldBytes)
    }

    @Test
    fun the_entry_limit_still_applies() = runTest {
        val h = Harness(cacheScope(), maxEntries = 2)
        h.get("a", 1)
        h.get("b", 1)
        h.get("c", 1)
        assertEquals(2L, h.cache.heldBytes)
        h.get("a", 1)
        assertEquals(listOf("a", "b", "c", "a"), h.computed)
    }

    @Test
    fun failures_and_empty_results_are_kept_and_cost_nothing() = runTest {
        val h = Harness(cacheScope())
        var attempts = 0
        val compute: suspend () -> Long? = {
            attempts++
            error("bad file")
        }
        assertNull(h.cache.get("bad", compute))
        assertNull(h.cache.get("bad", compute))
        assertEquals(1, attempts)
        assertEquals(0L, h.cache.heldBytes)
    }

    @Test
    fun concurrent_requests_share_one_computation() = runTest {
        val scope = cacheScope()
        val h = Harness(scope)
        val gate = CompletableDeferred<Long>()
        var runs = 0
        val compute: suspend () -> Long? = {
            runs++
            gate.await()
        }
        val first = async(UnconfinedTestDispatcher(testScheduler)) { h.cache.get("a", compute) }
        val second = async(UnconfinedTestDispatcher(testScheduler)) { h.cache.get("a", compute) }
        gate.complete(7)
        assertEquals(7L, first.await())
        assertEquals(7L, second.await())
        assertEquals(1, runs)
        assertEquals(7L, h.cache.heldBytes)
    }

    @Test
    fun a_caller_going_away_does_not_cancel_the_shared_computation() = runTest {
        val h = Harness(cacheScope())
        val gate = CompletableDeferred<Long>()
        val compute: suspend () -> Long? = { gate.await() }
        val leaving = async(UnconfinedTestDispatcher(testScheduler)) { h.cache.get("a", compute) }
        val staying = async(UnconfinedTestDispatcher(testScheduler)) { h.cache.get("a", compute) }
        leaving.cancel()
        gate.complete(5)
        assertEquals(5L, staying.await())
    }

    @Test
    fun the_byte_limit_does_not_evict_a_running_computation() = runTest {
        val h = Harness(cacheScope())
        val gate = CompletableDeferred<Long>()
        var runs = 0
        val slow: suspend () -> Long? = {
            runs++
            gate.await()
        }
        val waiting = async(UnconfinedTestDispatcher(testScheduler)) { h.cache.get("slow", slow) }
        h.get("a", 60)
        h.get("b", 60) // Over the limit, with "slow" the oldest: "a" goes instead.
        assertEquals(60L, h.cache.heldBytes)
        val joining = async(UnconfinedTestDispatcher(testScheduler)) { h.cache.get("slow", slow) }
        gate.complete(10)
        assertEquals(10L, waiting.await())
        assertEquals(10L, joining.await())
        assertEquals(1, runs)
        assertEquals(70L, h.cache.heldBytes)
    }

    @Test
    fun a_result_evicted_while_running_is_not_counted() = runTest {
        val h = Harness(cacheScope(), maxEntries = 1)
        val gate = CompletableDeferred<Long>()
        val waiting = async(UnconfinedTestDispatcher(testScheduler)) { h.cache.get("slow") { gate.await() } }
        h.get("a", 5) // Pushes "slow" out by count; its caller still gets the result.
        gate.complete(50)
        assertEquals(50L, waiting.await())
        assertEquals(5L, h.cache.heldBytes)
    }

    @Test
    fun clearing_forgets_finished_results_but_keeps_running_ones() = runTest {
        val h = Harness(cacheScope())
        h.get("a", 10)
        val gate = CompletableDeferred<Long>()
        val running = async { h.cache.get("slow") { gate.await() } }
        h.cache.clearFinished()
        assertEquals(0L, h.cache.heldBytes)
        // The running one is still shared: a second caller does not start it again.
        var startedAgain = false
        val second = async { h.cache.get("slow") { startedAgain = true; 0L } }
        gate.complete(25)
        assertEquals(25L, running.await())
        assertEquals(25L, second.await())
        assertEquals(false, startedAgain)
        assertEquals(25L, h.cache.heldBytes)
        // The finished one is computed again.
        h.get("a", 10)
        assertEquals(listOf("a", "a"), h.computed)
    }
}
