package com.bromano.mobile.perf

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class MainTest {
    @Test
    fun `shlex split supports whitespace quotes escapes and empty arguments`() {
        assertContentEquals(
            arrayOf("android", "start", "two words", "", "single quoted", "escaped value"),
            shlexSplit("android\tstart \"two words\" \"\" 'single quoted' escaped\\ value"),
        )
    }

    @Test
    fun `shlex split rejects malformed input`() {
        assertFailsWith<IllegalArgumentException> { shlexSplit("android start \\") }
        assertFailsWith<IllegalArgumentException> { shlexSplit("android start 'unterminated") }
    }
}
