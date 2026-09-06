package com.bromano.mobile.perf.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbTransportTest {
    @Test
    fun `structured commands preserve serial arguments status and timeout`() {
        val shell = FakeShell()
        val adb = Adb("device serial;quoted", shell)
        val expected = CommandResult(1, "1234", "not found")
        shell.runArgumentsHandler = { command, check, timeout ->
            assertEquals(listOf("adb", "-s", "device serial;quoted", "shell", "pidof -s 'app'"), command)
            assertFalse(check)
            assertEquals(Duration.ofSeconds(7), timeout)
            expected
        }

        assertEquals(expected, adb.shellResult("pidof -s 'app'", check = false, timeout = Duration.ofSeconds(7)))
    }

    @Test
    fun `rooted daemon needs no su and is reused across both command APIs`() {
        val shell = FakeShell()
        shell.runArgumentsHandler = { command, _, _ ->
            if (command.last() == "sh -c 'id'") CommandResult(0, "uid=0(root)", "") else CommandResult(0, "", "")
        }
        val adb = Adb("serial", shell)
        adb.ensureRoot()
        val command = "printf '%s' 'a b'"
        val remoteCommand = "sh -c ${shellQuote(command)}"

        assertEquals(listOf("adb", "-s", "serial", "shell", remoteCommand), adb.rootCommand(command))
        assertEquals("shell ${shellQuote(remoteCommand)}", adb.getShellEscapedCommand(command, withRoot = true))
        adb.shell(command, withRoot = true)
        assertEquals("adb -s serial shell ${shellQuote(remoteCommand)}", shell.runCommandCalls.single())
        assertEquals(3, shell.runArgumentsCalls.size)
        assertTrue(adb.isRootable())
        assertEquals(3, shell.runArgumentsCalls.size)

        adb.clearRoot()
        assertThrows<IllegalArgumentException> { adb.rootCommand(command) }
    }

    @Test
    fun `root probes fall back to su dash c and reject misleading output`() {
        val shell = FakeShell()
        shell.runArgumentsHandler = { command, _, _ ->
            when (command.last()) {
                "sh -c 'id'" -> CommandResult(0, "uid=2000(shell) groups=0(root)", "")
                "su 0 sh -c 'id'" -> CommandResult(1, "uid=0(root)", "denied")
                "su -c 'id'" -> CommandResult(0, "uid=0(root)", "")
                else -> CommandResult(0, "", "")
            }
        }
        val adb = Adb(null, shell)
        assertTrue(adb.isRootable())
        assertEquals(listOf("adb", "shell", "su -c 'id'"), adb.rootCommand("id"))
        assertEquals(3, shell.runArgumentsCalls.size)
    }

    @Test
    fun `root must be reacquired after invalidation`() {
        val shell = FakeShell()
        shell.runArgumentsHandler = { _, _, _ -> CommandResult(0, "uid=0(root)", "") }
        val adb = Adb("serial", shell)
        adb.ensureRoot()
        adb.clearRoot()
        shell.runArgumentsHandler = { _, _, _ -> CommandResult(0, "uid=2000(shell)", "") }

        assertThrows<IllegalArgumentException> { adb.ensureRoot() }
        assertThrows<IllegalArgumentException> { adb.rootCommand("id") }
    }

    @Test
    fun `legacy transfer commands quote paths and serials`() {
        val shell = FakeShell()
        val adb = Adb("a'b", shell)
        adb.push("/tmp/a b", "/data/local/tmp/a'b")

        assertEquals(
            "adb -s ${shellQuote("a'b")} push ${shellQuote("/tmp/a b")} ${shellQuote("/data/local/tmp/a'b")}",
            shell.runCommandCalls.single(),
        )
    }
}
