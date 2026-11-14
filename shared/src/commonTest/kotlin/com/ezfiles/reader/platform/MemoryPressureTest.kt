package com.ezfiles.reader.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryPressureTest {
    @Test
    fun trims_registered_caches_until_they_unregister() {
        var trims = 0
        val unregister = MemoryPressure.register { trims++ }
        MemoryPressure.trim()
        assertEquals(1, trims)
        unregister()
        MemoryPressure.trim()
        assertEquals(1, trims)
    }

    @Test
    fun a_listener_may_unregister_itself_while_trimming() {
        var calls = 0
        lateinit var unregister: () -> Unit
        unregister = MemoryPressure.register {
            calls++
            unregister()
        }
        MemoryPressure.trim()
        MemoryPressure.trim()
        assertEquals(1, calls)
    }
}
