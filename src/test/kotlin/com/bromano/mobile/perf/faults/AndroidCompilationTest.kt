package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AndroidCompilationTest {
    @Test fun `compilation evidence retains actual filters without inferring AOT from artifact names`() {
        val dump =
            """
            arm64: [status=verify] [reason=install] [primary-abi]
            [location is /data/app/example/oat/arm64/base.odex]
            arm64: [status=speed] [reason=cmdline] [primary-abi]
            arm: [status=verify] [reason=install]
            """.trimIndent()
        assertEquals(listOf("speed", "verify"), compilationStatuses(dump, 0))
        assertEquals(emptyList(), compilationStatuses(dump, 1))
        assertEquals(emptyList(), compilationStatuses("base.odex exists", 0))
        assertEquals(listOf("speed-profile"), compilationStatuses("[status=speed-profile]", 0))
    }
}
