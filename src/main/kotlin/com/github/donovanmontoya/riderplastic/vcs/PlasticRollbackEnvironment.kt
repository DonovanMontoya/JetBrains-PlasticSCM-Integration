package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.cli.toNioPath
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.rollback.RollbackEnvironment
import com.intellij.openapi.vcs.rollback.RollbackProgressListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/** Rollback = `cm undo`. Undoing an added file leaves it on disk as a private (unversioned) file. */
class PlasticRollbackEnvironment(private val project: Project) : RollbackEnvironment {

    override fun getRollbackOperationName(): String = "Undo Changes"

    override fun rollbackChanges(
        changes: List<Change>,
        vcsExceptions: MutableList<VcsException>,
        listener: RollbackProgressListener,
    ) {
        val paths = changes.flatMap { listOfNotNull(it.beforeRevision?.file, it.afterRevision?.file) }.distinct()
        undo(paths, vcsExceptions)
        changes.forEach { listener.accept(it) }
    }

    override fun rollbackMissingFileDeletion(
        files: List<FilePath>,
        exceptions: MutableList<in VcsException>,
        listener: RollbackProgressListener,
    ) {
        val errors = mutableListOf<VcsException>()
        undo(files, errors)
        exceptions.addAll(errors)
        files.forEach { listener.accept(it) }
    }

    override fun rollbackModifiedWithoutCheckout(
        files: List<VirtualFile>,
        exceptions: MutableList<in VcsException>,
        listener: RollbackProgressListener,
    ) {
        // Plastic does not require explicit checkouts; modified files are handled by rollbackChanges.
    }

    override fun rollbackIfUnchanged(file: VirtualFile) {}

    private fun undo(paths: List<FilePath>, errors: MutableList<VcsException>) {
        val byRoot = LinkedHashMap<Path, MutableList<Path>>()
        for (path in paths) {
            val root = PlasticVcs.rootFor(project, path) ?: continue
            byRoot.getOrPut(root) { mutableListOf() }.add(path.toNioPath())
        }
        for ((root, rootPaths) in byRoot) {
            try {
                PlasticCli.undo(root, rootPaths)
            } catch (e: VcsException) {
                errors += e
            }
        }
        val ioFiles = paths.map { it.ioFile }
        LocalFileSystem.getInstance().refreshIoFiles(ioFiles, false, false, null)
    }
}
