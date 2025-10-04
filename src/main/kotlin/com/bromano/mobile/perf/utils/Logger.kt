package com.bromano.mobile.perf.utils

import com.github.ajalt.mordant.rendering.TextColors.brightBlue
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.terminal.Terminal
import java.util.concurrent.TimeUnit

/**
 * Terminal logger
 */
object Logger {
    // open for testing
    internal var term = Terminal()

    fun warning(str: String) {
        term.println(yellow(str))
    }

    fun error(str: String) {
        term.println(red(str))
    }

    fun success(str: String) {
        term.println(green(str))
    }

    fun info(str: String) {
        term.println(str)
    }

    fun debug(str: String) {
        term.println(brightBlue(str))
    }

    fun <T> timedLog(
        message: String,
        block: () -> T,
    ): T {
        val startTime = System.currentTimeMillis()
        info("$message...")
        val result = block()
        info("$message... [Done] ${humanReadableTime(System.currentTimeMillis() - startTime)}")
        return result
    }

    private fun humanReadableTime(millisTotal: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millisTotal)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millisTotal) - minutes * 60
        val millis = millisTotal % 1000
        return StringBuilder()
            .apply {
                if (minutes > 0) {
                    append("${minutes}m ")
                }
                if (seconds > 0) {
                    append("${seconds}s ")
                }
                if (minutes == 0L) {
                    append("${millis}ms")
                }
            }.toString()
    }
}
