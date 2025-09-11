package com.bromano.mobile.perf.utils

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class LoggerTest {
    private lateinit var terminal: Terminal
    private lateinit var logger: Logger

    @BeforeEach
    fun setup() {
        terminal = mock()
        logger = Logger
        logger.term = terminal
    }

    @Test
    fun `warning logs the correct string`() {
        logger.warning("test")
        verify(terminal).println(TextColors.yellow("test"))
    }

    @Test
    fun `error logs the correct string`() {
        logger.error("test")
        verify(terminal).println(TextColors.red("test"))
    }

    @Test
    fun `success logs the correct string`() {
        logger.success("test")
        verify(terminal).println(TextColors.green("test"))
    }

    @Test
    fun `info logs the correct string`() {
        logger.info("test")
        verify(terminal).println("test")
    }

    @Test
    fun `debug logs the correct string`() {
        logger.debug("test")
        verify(terminal).println(TextColors.brightBlue("test"))
    }
}
