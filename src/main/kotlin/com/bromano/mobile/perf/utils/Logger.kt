package com.bromano.mobile.perf.utils

import com.github.ajalt.mordant.rendering.TextColors.brightBlue
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.terminal.Terminal

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
}
