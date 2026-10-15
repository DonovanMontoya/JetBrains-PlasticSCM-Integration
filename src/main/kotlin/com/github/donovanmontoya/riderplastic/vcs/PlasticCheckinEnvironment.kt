package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.cli.toNioPath
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinEnvironment
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.MultiMap
import com.intellij.vcsUtil.VcsUtil
import java.nio.file.Path

class PlasticCheckinEnvironment(private val project: Project) : CheckinEnvironment {

    override fun getHelpId(): String? = null

    override fun getCheckinOperationName(): String = "Check In"

    override fun isRefreshAfterCommitNeeded(): Boolean = true

    override fun commit(
        changes: List<Change>,
        commitMessage: String,
        commitContext: CommitContext,
        feedback: MutableSet<in String>,
    ): List<VcsException> {
        val errors = checkIn(changes, emptyList(), commitMessage)
        if (errors.isEmpty()) feedback.add("Checked in ${changes.size} item(s) to Plastic SCM")
        return errors
    }

    /**
     * Checks in [changes] plus the private files in [unversioned] (which are `cm add`-ed first), grouped per workspace.
     * Blocking; call from a background thread.
     */
    fun checkIn(changes: Collection<Change>, unversioned: Collection<FilePath>, comment: String): List<VcsException> {
        if (comment.isBlank()) return listOf(VcsException("Plastic SCM requires a check-in comment"))

        val toAdd = MultiMap.createLinkedSet<Path, Path>()
        for (path in unversioned) {
            val root = PlasticVcs.rootFor(project, path) ?: continue
            toAdd.putValue(root, path.toNioPath())
        }
        val byRoot = MultiMap.createLinkedSet<Path, Path>()
        for (change in changes) {
            // Moves need both ends so Plastic can check in the whole move.
            for (path in listOfNotNull(change.beforeRevision?.file, change.afterRevision?.file)) {
                val root = PlasticVcs.rootFor(project, path) ?: continue
                byRoot.putValue(root, path.toNioPath())
            }
        }
        for ((root, paths) in toAdd.entrySet()) byRoot.putValues(root, paths)

        val errors = mutableListOf<VcsException>()
        for ((root, paths) in byRoot.entrySet()) {
            try {
                PlasticCli.add(root, toAdd.get(root))
                PlasticCli.checkin(root, paths, comment)
            } catch (e: VcsException) {
                errors += e
            }
        }
        return errors
    }

    override fun scheduleMissingFileForDeletion(files: List<FilePath>): List<VcsException> =
        // Locally deleted items are picked up by `cm checkin --all`; nothing to schedule.
        emptyList()

    override fun scheduleUnversionedFilesForAddition(files: List<VirtualFile>): List<VcsException> =
        forEachRoot(files.map { VcsUtil.getFilePath(it) }) { root, paths -> PlasticCli.add(root, paths) }

    private fun forEachRoot(files: List<FilePath>, action: (Path, List<Path>) -> Unit): List<VcsException> {
        val errors = mutableListOf<VcsException>()
        files.groupBy { PlasticVcs.rootFor(project, it) }.forEach { (root, group) ->
            if (root == null) return@forEach
            try {
                action(root, group.map { it.toNioPath() })
            } catch (e: VcsException) {
                errors += e
            }
        }
        return errors
    }
}
