package com.github.donovanmontoya.riderplastic.cli

import com.github.donovanmontoya.riderplastic.settings.PlasticSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Thin wrapper around the Plastic SCM / Unity Version Control command line client (`cm`).
 * All methods block and must be called from a background thread.
 */
object PlasticCli {

    private val LOG = logger<PlasticCli>()
    private const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000

    fun run(workingDir: Path, vararg args: String): String = run(workingDir, args.toList())

    fun run(workingDir: Path, args: List<String>, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String {
        val output = execute(workingDir, args, timeoutMs)
        if (output.exitCode != 0 || output.isTimeout) {
            val message = output.stderr.ifBlank { output.stdout }.trim()
                .ifBlank { if (output.isTimeout) "timed out" else "exit code ${output.exitCode}" }
            throw VcsException("cm ${args.firstOrNull().orEmpty()} failed: $message")
        }
        return output.stdout
    }

    fun execute(workingDir: Path, args: List<String>, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ProcessOutput {
        val commandLine = GeneralCommandLine(PlasticSettings.getInstance().resolveCmExecutable())
            .withParameters(args)
            .withWorkDirectory(workingDir.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        LOG.debug { "Running ${commandLine.commandLineString} in $workingDir" }

        val handler = try {
            CapturingProcessHandler(commandLine)
        } catch (e: Exception) {
            throw VcsException(
                "Unable to start the Plastic SCM client '${commandLine.exePath}'. " +
                    "Install Unity Version Control or set the cm path in Settings | Version Control | Plastic SCM.",
                e,
            )
        }
        val indicator: ProgressIndicator? = ProgressManager.getInstance().progressIndicator
        val output = if (indicator != null) {
            handler.runProcessWithProgressIndicator(indicator, timeoutMs)
        } else {
            handler.runProcess(timeoutMs)
        }
        // Report cancellation as such so callers stop quietly instead of logging an error.
        if (output.isCancelled) throw ProcessCanceledException()
        LOG.debug { "cm exited with ${output.exitCode}" }
        return output
    }

    // ---------------------------------------------------------------- commands

    fun version(workingDir: Path): String = run(workingDir, "version").trim()

    /** Pending changes in the workspace at [root], limited to [scope] (a folder inside it) when given. */
    fun status(root: Path, scope: Path = root): PlasticStatus {
        val out = run(
            root,
            listOf(
                "status", scope.toString(),
                "--all",
                "--machinereadable",
                "--fieldseparator=${PlasticStatusParser.FIELD_SEPARATOR}",
            ),
        )
        // The loaded changeset decides which revision every diff is taken against. The machine-readable STATUS line
        // carries the working changeset, also when it is behind head (`STATUS#~#0#~#repo#~#server` at cs:0 - head:206).
        // Only if it is missing, ask `cm status --header`; if that fails too, leave it unknown: a bare-path `cm cat`
        // is better than a wrong changeset.
        val status = PlasticStatusParser.parse(out, root)
        if (status.changeset != null) return status
        return status.copy(changeset = PlasticOutputParsers.parseHeaderChangeset(run(root, "status", "--header")))
    }

    /** Returns the branch (or `cs:N` / `lb:name`) the workspace currently points at. */
    fun currentSelector(root: Path): String? =
        PlasticOutputParsers.parseWorkspaceSelector(run(root, "workspaceinfo", root.toString()))

    /** Writes the content of [revisionSpec] (a local path, or e.g. `revid:42`) to a byte array. */
    fun cat(root: Path, revisionSpec: String): ByteArray {
        val tmp = FileUtil.createTempFile("riderplastic", ".cat", true)
        try {
            run(root, "cat", revisionSpec, "--file=${tmp.absolutePath}")
            return tmp.readBytes()
        } finally {
            FileUtil.delete(tmp)
        }
    }

    /** AI preparation must not load an unbounded historical file into memory. */
    fun catLimited(root: Path, revisionSpec: String, maxBytes: Int): ByteArray {
        require(maxBytes in 1..1024 * 1024) { "Invalid content limit." }
        val tmp = FileUtil.createTempFile("riderplastic-ai", ".cat", true)
        try {
            run(root, listOf("cat", revisionSpec, "--file=${tmp.absolutePath}"), timeoutMs = 60_000)
            return tmp.inputStream().use { BoundedContent.read(it, maxBytes) }
        } finally {
            FileUtil.delete(tmp)
        }
    }

    fun history(root: Path, file: Path): List<PlasticHistoryEntry> {
        val out = run(
            root,
            listOf(
                "history", file.toString(),
                "--machinereadable",
                "--format=${PlasticOutputParsers.HISTORY_FORMAT}",
                "--dateformat=${PlasticOutputParsers.CM_DATE_FORMAT}",
            ),
        )
        return PlasticOutputParsers.parseHistory(out)
    }

    fun annotate(root: Path, file: Path): List<PlasticAnnotationLine> {
        val out = run(
            root,
            listOf(
                "annotate", file.toString(),
                "--format=${PlasticOutputParsers.ANNOTATE_FORMAT}",
                "--dateformat=${PlasticOutputParsers.CM_DATE_FORMAT}",
            ),
        )
        return PlasticOutputParsers.parseAnnotate(out)
    }

    fun add(root: Path, files: Collection<Path>) {
        if (files.isEmpty()) return
        run(root, listOf("add", "--parents") + files.map { it.toString() })
    }

    fun undo(root: Path, files: Collection<Path>) {
        if (files.isEmpty()) return
        run(root, listOf("undo") + files.map { it.toString() })
    }

    fun checkin(root: Path, files: Collection<Path>, comment: String): String {
        val commentFile: File = FileUtil.createTempFile("riderplastic", ".comment", true)
        try {
            commentFile.writeText(comment, StandardCharsets.UTF_8)
            return run(
                root,
                listOf("checkin", "--all", "--commentsfile=${commentFile.absolutePath}") + files.map { it.toString() },
            )
        } finally {
            FileUtil.delete(commentFile)
        }
    }

    fun update(root: Path): String = run(root, "update", root.toString())

    fun branches(root: Path): List<String> =
        PlasticOutputParsers.parseBranches(run(root, "find", "branch", "--format={name}", "--nototal"))

    fun switchTo(root: Path, branch: String): String = run(root, "switch", "br:$branch")

    fun createBranch(root: Path, branch: String): String = run(root, "branch", "create", "br:$branch")
}

fun FilePath.toNioPath(): Path = ioFile.toPath()
