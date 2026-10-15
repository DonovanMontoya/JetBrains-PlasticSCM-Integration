package com.github.donovanmontoya.riderplastic.history

import com.github.donovanmontoya.riderplastic.vcs.PlasticRevisionNumber
import com.github.donovanmontoya.riderplastic.vcs.PlasticVcs
import com.github.donovanmontoya.riderplastic.vcs.PlasticWorkspaceState
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.history.DiffFromHistoryHandler
import com.intellij.openapi.vcs.history.VcsAbstractHistorySession
import com.intellij.openapi.vcs.history.VcsAppendableHistorySessionPartner
import com.intellij.openapi.vcs.history.VcsDependentHistoryComponents
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsHistoryProvider
import com.intellij.openapi.vcs.history.VcsHistorySession
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.JComponent

/** Show History for files, backed by `cm history`. */
class PlasticHistoryProvider(private val project: Project) : VcsHistoryProvider {

    override fun getUICustomization(session: VcsHistorySession, forShortcutRegistration: JComponent): VcsDependentHistoryComponents =
        VcsDependentHistoryComponents.createOnlyColumns(emptyArray())

    override fun getAdditionalActions(refresher: Runnable): Array<AnAction> = AnAction.EMPTY_ARRAY

    override fun isDateOmittable(): Boolean = false

    override fun getHelpId(): String? = null

    override fun createSessionFor(filePath: FilePath): VcsHistorySession? = createSession(filePath)

    override fun reportAppendableHistory(path: FilePath, partner: VcsAppendableHistorySessionPartner) {
        val session = createSession(path) ?: return
        partner.reportCreatedEmptySession(PlasticHistorySession(emptyList(), session.currentRevisionNumber))
        session.revisionList.forEach { partner.acceptRevision(it) }
    }

    override fun supportsHistoryForDirectories(): Boolean = false

    override fun getHistoryDiffHandler(): DiffFromHistoryHandler? = null

    override fun canShowHistoryFor(file: VirtualFile): Boolean = !file.isDirectory

    private fun createSession(filePath: FilePath): PlasticHistorySession? {
        val root = PlasticVcs.rootFor(project, filePath) ?: return null
        val revisions = PlasticFileRevision.list(project, filePath)
        val loaded = PlasticWorkspaceState.getInstance(project).info(root)?.changeset
        // The file's current revision is the newest one at or below the loaded changeset.
        val current = revisions.map { it.revisionNumber }
            .filterIsInstance<PlasticRevisionNumber>()
            .filter { loaded == null || it.changeset <= loaded }
            .maxByOrNull { it.changeset }
        return PlasticHistorySession(revisions, current)
    }
}

/**
 * Note: the base constructor caches [calcCurrentRevisionNumber], so the current revision is computed up front
 * and handed to the protected two-argument constructor instead.
 */
class PlasticHistorySession(
    revisions: List<VcsFileRevision>,
    private val current: VcsRevisionNumber?,
) : VcsAbstractHistorySession(revisions, current) {

    override fun calcCurrentRevisionNumber(): VcsRevisionNumber? = current

    override fun copy(): VcsHistorySession = PlasticHistorySession(revisionList, current)

    override fun isContentAvailable(revision: VcsFileRevision): Boolean = true

    override fun shouldBeRefreshed(): Boolean = false
}
