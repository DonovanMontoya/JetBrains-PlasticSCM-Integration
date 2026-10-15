package com.github.donovanmontoya.riderplastic.history

import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.cli.PlasticHistoryEntry
import com.github.donovanmontoya.riderplastic.cli.toNioPath
import com.github.donovanmontoya.riderplastic.vcs.PlasticRevisionNumber
import com.github.donovanmontoya.riderplastic.vcs.PlasticVcs
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.RepositoryLocation
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import java.nio.file.Path
import java.util.Date

class PlasticFileRevision(
    private val root: Path,
    val filePath: FilePath,
    val entry: PlasticHistoryEntry,
) : VcsFileRevision {

    private val number = PlasticRevisionNumber(entry.changeset)

    /** `cm cat` spec for this exact revision. */
    val spec: String
        get() = if (entry.revisionId.isNotBlank()) "revid:${entry.revisionId}" else specAtChangeset(filePath, entry.changeset)

    override fun getRevisionNumber(): VcsRevisionNumber = number

    override fun getBranchName(): String = entry.branch

    override fun getRevisionDate(): Date? = entry.date

    override fun getAuthor(): String = entry.owner

    override fun getCommitMessage(): String = entry.comment

    override fun getChangedRepositoryPath(): RepositoryLocation? = null

    @Throws(VcsException::class)
    override fun loadContent(): ByteArray = PlasticCli.cat(root, spec)

    @Deprecated("Deprecated in Java")
    override fun getContent(): ByteArray = loadContent()

    companion object {
        fun specAtChangeset(filePath: FilePath, changeset: Long): String = "${filePath.path}#cs:$changeset"

        fun list(project: Project, filePath: FilePath): List<PlasticFileRevision> {
            val root = PlasticVcs.rootFor(project, filePath) ?: return emptyList()
            return PlasticCli.history(root, filePath.toNioPath()).map { PlasticFileRevision(root, filePath, it) }
        }

        fun latest(project: Project, filePath: FilePath): PlasticFileRevision? = list(project, filePath).firstOrNull()
    }
}
