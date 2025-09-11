package com.bromano.mobile.perf

import com.bromano.mobile.perf.commands.android.CollectCommand
import com.bromano.mobile.perf.commands.android.StartCommand
import com.bromano.mobile.perf.profilers.ProfilerExecutor
import com.bromano.mobile.perf.utils.ShellExecutor
import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.MordantMarkdownHelpFormatter
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.core.parse
import kotlin.io.path.readText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Entry point to generate docs to docs/cli.md */
object DocsGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = Paths.get("docs", "cli.md")
        out.parent.toFile().mkdirs()
        writeCliDocsMarkdown(out)
    }

    /**
     * Produce a Markdown document describing all commands and their options.
     */
    fun generateCliDocsMarkdown(): String {
        // Build the CLI tree without performing any side effects
        val config = com.charleskorn.kaml.Yaml.default.decodeFromString(Config.serializer(), getConfig().readText())
        val shell = ShellExecutor()
        val noop =
            object : ProfilerExecutor {
                override fun execute(
                    profilerOptionGroup: ProfilerOptionGroup,
                    shell: com.bromano.mobile.perf.utils.Shell,
                    device: String,
                    packageName: String,
                    output: Path,
                    profileViewerOverride: com.bromano.mobile.perf.utils.ProfileViewer?,
                ) = Unit

                override fun executeTest(
                    profilerOptionGroup: ProfilerOptionGroup,
                    shell: com.bromano.mobile.perf.utils.Shell,
                    device: String,
                    packageName: String,
                    instrumentationPackageName: String,
                    testCase: String,
                    output: Path,
                    profileViewerOverride: com.bromano.mobile.perf.utils.ProfileViewer?,
                ) = Unit
            }

        val root = MobilePerfCommand()
            .subcommands(
                StartCommand(shell, config, noop),
                CollectCommand(shell, config, noop),
            )

        // Switch help formatter to Markdown
        root.configureContext { helpFormatter = { ctx -> MordantMarkdownHelpFormatter(ctx) } }
        // Initialize context without invoking subcommands
        try {
            root.parse(emptyList())
        } catch (_: Exception) {
            // ignore; we only need context initialized
        }

        fun render(cmd: BaseCliktCommand<*>): String {
            val title = cmd.commandName.ifBlank { "mobileperf" }
            val sb = StringBuilder()
            sb.append("# ").append(title).append('\n')

            // Description (omitted here to avoid requiring a parse context)

            // Parameters as markdown tables
            fun renderParamsAsTables(c: BaseCliktCommand<*>): String {
                val params = c.allHelpParams()
                val options = params.filterIsInstance<HelpFormatter.ParameterHelp.Option>()
                val arguments = params.filterIsInstance<HelpFormatter.ParameterHelp.Argument>()
                val subcommands = params.filterIsInstance<HelpFormatter.ParameterHelp.Subcommand>()

                val b = StringBuilder()
                if (options.isNotEmpty()) {
                    b.append("**Options**\n\n")
                    b.append("| Name(s) | Metavar | Description |\n")
                    b.append("|---|---|---|\n")
                    options.forEach { o ->
                        val names = (o.names + o.secondaryNames).sorted().joinToString(", ")
                        val mv = o.metavar ?: ""
                        val help = o.help
                        b.append("| ")
                            .append(escapePipes(escapeAngleBracketsOutsideCode(names))).append(" | ")
                            .append(escapePipes(escapeAngleBracketsOutsideCode(mv))).append(" | ")
                            .append(escapePipes(help)).append(" |\n")
                    }
                    b.append('\n')
                }
                if (arguments.isNotEmpty()) {
                    b.append("**Arguments**\n\n")
                    b.append("| Name | Description |\n")
                    b.append("|---|---|\n")
                    arguments.forEach { a ->
                        val name = a.name
                        val help = a.help
                        b.append("| ").append(escapePipes(name)).append(" | ")
                            .append(escapePipes(help)).append(" |\n")
                    }
                    b.append('\n')
                }
                if (subcommands.isNotEmpty()) {
                    b.append("**Commands**\n\n")
                    b.append("| Name | Description |\n")
                    b.append("|---|---|\n")
                    subcommands.forEach { s ->
                        val name = s.name
                        val help = s.help
                        b.append("| ").append(escapePipes(name)).append(" | ")
                            .append(escapePipes(help)).append(" |\n")
                    }
                    b.append('\n')
                }
                return b.toString()
            }

            sb.append(renderParamsAsTables(cmd))

            // Render subcommands recursively (one level)
            for (sub in cmd.registeredSubcommands()) {
                sb.append("## ").append(sub.commandName).append('\n')
                // Ensure sub has a valid context
                try { sub.resetContext(cmd.currentContext) } catch (_: Exception) {}
                sb.append(renderParamsAsTables(sub))
            }
            return sb.toString()
        }

        return render(root)
    }

    /**
     * Convenience helper to write CLI docs to a file.
     */
    fun writeCliDocsMarkdown(path: Path): Path {
        val content = generateCliDocsMarkdown()
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    /**
     * Escape angle brackets that Markdown may interpret as HTML tags, but avoid touching code spans and fences.
     */
    private fun escapeAngleBracketsOutsideCode(text: String?): String {
        val safe = text ?: ""
        var inFence = false
        return safe
            .lines()
            .joinToString("\n") { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    inFence = !inFence
                    line
                } else if (inFence) {
                    line
                } else {
                    // Split on backticks to preserve inline code spans
                    val parts = line.split("`")
                    val rebuilt = StringBuilder()
                    parts.forEachIndexed { idx, part ->
                        if (idx % 2 == 0) {
                            // Outside code span: escape < and >
                            rebuilt.append(part.replace("<", "&lt;").replace(">", "&gt;"))
                        } else {
                            // Inside code span: put backticks and raw content
                            rebuilt.append('`').append(part).append('`')
                        }
                    }
                    rebuilt.toString()
                }
            }
    }

    private fun escapePipes(text: String): String =
        text.replace("|", "\\|")
}

