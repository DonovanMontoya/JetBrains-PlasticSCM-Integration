package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.cli.PlasticChangeType
import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.cli.PlasticStatus
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.Change
import java.nio.file.Path

/**
 * Plastic pending changes in folders the IDE assigns to another VCS, such as a Git repository nested in a Plastic
 * workspace. The IDE gives each file to a single VCS, so these never reach its change list; [PlasticWorkspaceState]
 * keeps them for the Plastic SCM tool window so both systems' changes stay visible.
 */
data class PlasticNestedRootChanges(val changes: List<Change>, val unversioned: List<FilePath>) {

    companion object {
        val EMPTY = PlasticNestedRootChanges(emptyList(), emptyList())

        /** The items of [status], read in the workspace at [root], that the IDE assigns to another VCS at [vcsRoots]. */
        fun of(project: Project, root: Path, status: PlasticStatus, vcsRoots: List<Path> = otherVcsRoots(project)): PlasticNestedRootChanges {
            val folders = vcsRoots.filter { it.startsWith(root) }
            // Most workspaces have no other VCS inside.
            if (folders.isEmpty()) return EMPTY
            val base = PlasticChangeProvider.baseRevision(status.changeset)
            val changes = LinkedHashSet<Change>()
            val unversioned = LinkedHashSet<FilePath>()
            for (entry in status.entries) {
                // Items outside these folders are the change provider's.
                if (folders.none { entry.path.startsWith(it) }) continue
                val path = PlasticChangeProvider.filePath(entry.path, entry.isDirectory)
                if (entry.type == PlasticChangeType.PRIVATE) {
                    unversioned += path
                } else {
                    PlasticChangeProvider.toChange(project, root, entry, path, base)?.let { changes += it }
                }
            }
            return if (changes.isEmpty() && unversioned.isEmpty()) EMPTY else PlasticNestedRootChanges(changes.toList(), unversioned.toList())
        }

        /**
         * Blocking: runs `cm status` for each nested folder of the workspaces at [roots]. For change list updates that
         * did not read those workspaces; otherwise the change provider's workspace status already covers these folders.
         * Runs no `cm` for workspaces without such folders.
         */
        fun collect(project: Project, roots: Collection<Path>): Map<Path, PlasticNestedRootChanges> {
            val foldersByRoot = otherVcsRoots(project)
                .mapNotNull { folder -> PlasticVcs.rootFor(project, PlasticChangeProvider.filePath(folder, true))?.let { it to folder } }
                .filter { it.first in roots }
                .groupBy({ it.first }, { it.second })
            return roots.associateWith { root ->
                val folders = foldersByRoot[root].orEmpty()
                merge(
                    outermost(folders).map {
                        ProgressManager.checkCanceled()
                        of(project, root, PlasticCli.status(root, it), folders)
                    },
                )
            }
        }

        /** [folders] without those inside another of them, which that one's status already covers. */
        internal fun outermost(folders: List<Path>): List<Path> =
            folders.filter { folder -> folders.none { it != folder && folder.startsWith(it) } }

        fun merge(all: Collection<PlasticNestedRootChanges>): PlasticNestedRootChanges = when (all.size) {
            0 -> EMPTY
            1 -> all.single()
            else -> PlasticNestedRootChanges(all.flatMap { it.changes }.distinct(), all.flatMap { it.unversioned }.distinct())
        }

        /** Roots of other VCSs in the project, such as Git repositories nested in a Plastic workspace. */
        private fun otherVcsRoots(project: Project): List<Path> =
            ProjectLevelVcsManager.getInstance(project).allVcsRoots
                .filter { it.vcs?.name != PlasticVcs.NAME }
                .map { it.path.toNioPath() }
    }
}
