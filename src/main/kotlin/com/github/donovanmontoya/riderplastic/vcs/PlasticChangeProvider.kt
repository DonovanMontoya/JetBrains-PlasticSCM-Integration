package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.cli.PlasticChangeType
import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.cli.PlasticStatusEntry
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManagerGate
import com.intellij.openapi.vcs.changes.ChangeProvider
import com.intellij.openapi.vcs.changes.ChangelistBuilder
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.VcsDirtyScope
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.vcsUtil.VcsUtil
import java.nio.file.Path

/**
 * Feeds `cm status` into the IDE's local changes model, which powers the Commit tool window,
 * gutter change markers and file status colors.
 */
class PlasticChangeProvider(private val project: Project) : ChangeProvider {

    override fun getChanges(
        dirtyScope: VcsDirtyScope,
        builder: ChangelistBuilder,
        progress: ProgressIndicator,
        addGate: ChangeListManagerGate,
    ) {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val roots = dirtyScope.affectedContentRoots
            .mapNotNull { vcsManager.getVcsRootObjectFor(it) }
            .filter { it.vcs?.name == PlasticVcs.NAME }
            .map { it.path }
            .distinct()

        for (rootFile in roots) {
            progress.checkCanceled()
            val root = rootFile.toNioPath()
            val status = PlasticCli.status(root)
            PlasticWorkspaceState.getInstance(project).updateChangeset(root, status.changeset)
            val baseRevision: VcsRevisionNumber = status.changeset?.let(::PlasticRevisionNumber) ?: VcsRevisionNumber.NULL

            for (entry in status.entries) {
                val path = filePath(entry.path, entry.isDirectory)
                if (!dirtyScope.belongsTo(path) && entry.oldPath?.let { dirtyScope.belongsTo(filePath(it, entry.isDirectory)) } != true) {
                    continue
                }
                when (entry.type) {
                    PlasticChangeType.PRIVATE -> builder.processUnversionedFile(path)
                    PlasticChangeType.IGNORED -> builder.processIgnoredFile(path)
                    else -> if (!entry.isDirectory || entry.type == PlasticChangeType.MOVED) {
                        builder.processChange(toChange(root, entry, path, baseRevision), PlasticVcs.KEY)
                    }
                }
            }
        }
    }

    private fun toChange(root: Path, entry: PlasticStatusEntry, path: FilePath, base: VcsRevisionNumber): Change {
        val current = CurrentContentRevision.create(path)
        return when (entry.type) {
            PlasticChangeType.ADDED -> Change(null, current)
            PlasticChangeType.DELETED -> Change(PlasticContentRevision(root, path, base, path.path), null)
            PlasticChangeType.MOVED -> {
                val before = filePath(entry.oldPath ?: entry.path, entry.isDirectory)
                // The item keeps its identity across the move, so the new path resolves to the loaded revision.
                Change(PlasticContentRevision(root, before, base, path.path), current)
            }
            else -> Change(PlasticContentRevision(root, path, base, path.path), current)
        }
    }

    override fun isModifiedDocumentTrackingRequired(): Boolean = false

    private fun filePath(path: Path, isDirectory: Boolean): FilePath = VcsUtil.getFilePath(path.toFile(), isDirectory)
}
