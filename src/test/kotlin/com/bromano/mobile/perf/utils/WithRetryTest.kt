package com.bromano.mobile.perf.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WithRetryTest {
    @Test
    fun executesBlockOnceWhenSuccessful() {
        var invocationCount = 0

        val result =
            withRetry(maxAttempts = 3) {
                invocationCount++
                "success"
            }

        assertEquals(1, invocationCount)
        assertEquals("success", result)
    }

    @Test
    fun retriesUntilSuccessWithinMaxAttempts() {
        var attempts = 0

        val value =
            withRetry(maxAttempts = 3) {
                attempts++
                if (attempts < 3) {
                    throw IllegalStateException("failure $attempts")
                }
                "recovered"
            }

        assertEquals(3, attempts)
        assertEquals("recovered", value)
    }

    @Test
    fun stopsRetryingWhenPredicateReturnsFalse() {
        var attempts = 0

        val error =
            assertFailsWith<IllegalArgumentException> {
                withRetry(maxAttempts = 5, shouldRetry = { it !is IllegalArgumentException }) {
                    attempts++
                    throw IllegalArgumentException("do not retry")
                }
            }

        assertEquals(1, attempts)
        assertEquals("do not retry", error.message)
    }

    @Test
    fun throwsLastErrorAfterExhaustingAttempts() {
        var attempts = 0

        val error =
            assertFailsWith<RuntimeException> {
                withRetry(maxAttempts = 3) {
                    attempts++
                    throw RuntimeException("failure $attempts")
                }
            }

        assertEquals(3, attempts)
        assertEquals("failure 3", error.message)
    }

    @Test
    fun requiresAtLeastOneAttempt() {
        var executed = false

        val error =
            assertFailsWith<IllegalArgumentException> {
                withRetry(maxAttempts = 0) {
                    executed = true
                }
            }

        assertTrue(error.message!!.contains("maxAttempts"))
        assertFalse(executed)
    }
}
