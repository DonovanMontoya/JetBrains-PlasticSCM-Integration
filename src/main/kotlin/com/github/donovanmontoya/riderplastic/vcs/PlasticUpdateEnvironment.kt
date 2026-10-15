package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.update.SequentialUpdatesContext
import com.intellij.openapi.vcs.update.UpdateEnvironment
import com.intellij.openapi.vcs.update.UpdateSession
import com.intellij.openapi.vcs.update.UpdateSessionAdapter
import com.intellij.openapi.vcs.update.UpdatedFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

/** VCS | Update Project: runs `cm update` on every Plastic workspace root. */
class PlasticUpdateEnvironment(private val project: Project) : UpdateEnvironment {

    override fun fillGroups(updatedFiles: UpdatedFiles) {}

    override fun updateDirectories(
        contentRoots: Array<out FilePath>,
        updatedFiles: UpdatedFiles,
        progressIndicator: ProgressIndicator,
        context: Ref<SequentialUpdatesContext>,
    ): UpdateSession {
        val errors = mutableListOf<VcsException>()
        val roots = contentRoots.mapNotNull { PlasticVcs.rootFor(project, it) }.distinct()
        val state = PlasticWorkspaceState.getInstance(project)
        for (root in roots) {
            progressIndicator.checkCanceled()
            progressIndicator.text = "Updating Plastic SCM workspace $root"
            try {
                PlasticCli.update(root)
                state.refreshSelector(root)
            } catch (e: VcsException) {
                errors += e
            }
            LocalFileSystem.getInstance().findFileByNioFile(root)?.let { VfsUtil.markDirtyAndRefresh(false, true, false, it) }
        }
        return UpdateSessionAdapter(errors, false)
    }

    override fun createConfigurable(files: Collection<FilePath>): Configurable? = null

    override fun validateOptions(roots: Collection<FilePath>): Boolean = true
}
