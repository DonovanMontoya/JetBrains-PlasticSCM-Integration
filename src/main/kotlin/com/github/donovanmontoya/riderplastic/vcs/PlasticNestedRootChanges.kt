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

        /** The items of [status], read in the workspace at [root], that the IDE assigns to another VCS. */
        fun of(project: Project, root: Path, status: PlasticStatus): PlasticNestedRootChanges {
            // Most workspaces have no other VCS inside; that skips the ownership lookup for every item.
            if (otherVcsRoots(project).none { it.startsWith(root) }) return EMPTY
            val base = PlasticChangeProvider.baseRevision(status.changeset)
            val changes = LinkedHashSet<Change>()
            val unversioned = LinkedHashSet<FilePath>()
            for (entry in status.entries) {
                val path = PlasticChangeProvider.filePath(entry.path, entry.isDirectory)
                // Items the IDE gives to Plastic are reported by the change provider.
                if (PlasticVcs.ownsInIde(project, path)) continue
                if (entry.type == PlasticChangeType.PRIVATE) {
                    unversioned += path
                } else {
                    PlasticChangeProvider.toChange(project, root, entry, path, base)?.let { changes += it }
                }
            }
            return if (changes.isEmpty() && unversioned.isEmpty()) EMPTY else PlasticNestedRootChanges(changes.toList(), unversioned.toList())
        }

        /**
         * Blocking: runs `cm status` for each nested folder, by workspace root. For change list updates that only
         * another VCS took part in; otherwise the change provider's workspace status already covers these folders.
         * Returns no roots without running `cm` when there are no such folders.
         */
        fun collect(project: Project): Map<Path, PlasticNestedRootChanges> =
            otherVcsRoots(project)
                .mapNotNull { folder -> PlasticVcs.rootFor(project, PlasticChangeProvider.filePath(folder, true))?.let { it to folder } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (root, folders) ->
                    // A folder inside another one is already covered by that one's status.
                    val outermost = folders.filter { folder -> folders.none { it != folder && folder.startsWith(it) } }
                    merge(
                        outermost.map {
                            ProgressManager.checkCanceled()
                            of(project, root, PlasticCli.status(root, it))
                        },
                    )
                }

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
