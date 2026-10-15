package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.history.PlasticFileRevision
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.diff.ItemLatestState
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil

class PlasticDiffProvider(private val project: Project) : DiffProvider {

    override fun getCurrentRevision(file: VirtualFile): VcsRevisionNumber? {
        val root = PlasticVcs.rootFor(project, VcsUtil.getFilePath(file)) ?: return null
        return PlasticWorkspaceState.getInstance(project).info(root)?.changeset?.let(::PlasticRevisionNumber)
    }

    override fun getLastRevision(virtualFile: VirtualFile): ItemLatestState? = getLastRevision(VcsUtil.getFilePath(virtualFile))

    override fun getLastRevision(filePath: FilePath): ItemLatestState? {
        val revision = PlasticFileRevision.latest(project, filePath) ?: return null
        return ItemLatestState(revision.revisionNumber, true, false)
    }

    override fun getLatestCommittedRevision(vcsRoot: VirtualFile): VcsRevisionNumber? = null

    override fun createFileContent(revisionNumber: VcsRevisionNumber, selectedFile: VirtualFile): ContentRevision? {
        val filePath = VcsUtil.getFilePath(selectedFile)
        val root = PlasticVcs.rootFor(project, filePath) ?: return null
        val cs = (revisionNumber as? PlasticRevisionNumber)?.changeset ?: revisionNumber.asString().toLongOrNull() ?: return null
        return PlasticContentRevision(root, filePath, PlasticRevisionNumber(cs), PlasticFileRevision.specAtChangeset(filePath, cs))
    }
}
