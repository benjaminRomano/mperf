package com.bromano.mobile.perf.commands.faults

import org.junit.jupiter.api.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FaultCommandSupportTest {
    @Test
    fun `default capture paths are unique for concurrent invocations`() {
        val first = defaultFaultOutput("android")
        val second = defaultFaultOutput("android")

        assertNotEquals(first, second)
        assertTrue(first.fileName.toString().startsWith("android-"))
        assertTrue(second.fileName.toString().startsWith("android-"))
    }
}
