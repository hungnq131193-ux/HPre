package com.hpre.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCachePolicyTest {
    @Test
    fun low_ram_devices_use_sixteen_mib() {
        assertEquals(16L * 1024 * 1024, imageMemoryCacheBytes(isLowRam = true, memoryClassMb = 512))
    }

    @Test
    fun normal_devices_use_one_eighth_memory_class_capped_at_thirty_two_mib() {
        assertEquals(16L * 1024 * 1024, imageMemoryCacheBytes(isLowRam = false, memoryClassMb = 128))
        assertEquals(24L * 1024 * 1024, imageMemoryCacheBytes(isLowRam = false, memoryClassMb = 192))
        assertEquals(32L * 1024 * 1024, imageMemoryCacheBytes(isLowRam = false, memoryClassMb = 512))
    }
}
