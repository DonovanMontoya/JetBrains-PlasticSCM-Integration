package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.annotate.PlasticAnnotationProvider
import com.github.donovanmontoya.riderplastic.history.PlasticHistoryProvider
import com.github.donovanmontoya.riderplastic.settings.PlasticConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.changes.ChangeProvider
import com.intellij.openapi.vcs.checkin.CheckinEnvironment
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.history.VcsHistoryProvider
import com.intellij.openapi.vcs.rollback.RollbackEnvironment
import com.intellij.openapi.vcs.update.UpdateEnvironment
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.commit.CommitMode
import java.nio.file.Path

class PlasticVcs(project: Project) : AbstractVcs(project, NAME) {

    private val changeProvider by lazy { PlasticChangeProvider(project) }
    private val checkinEnvironment by lazy { PlasticCheckinEnvironment(project) }
    private val rollbackEnvironment by lazy { PlasticRollbackEnvironment(project) }
    private val diffProvider by lazy { PlasticDiffProvider(project) }
    private val historyProvider by lazy { PlasticHistoryProvider(project) }
    private val annotationProvider by lazy { PlasticAnnotationProvider(project) }
    private val updateEnvironment by lazy { PlasticUpdateEnvironment(project) }

    override fun getDisplayName(): String = DISPLAY_NAME

    override fun getShortName(): String = "Plastic"

    override fun getChangeProvider(): ChangeProvider = changeProvider

    override fun getCheckinEnvironment(): CheckinEnvironment = checkinEnvironment

    override fun getRollbackEnvironment(): RollbackEnvironment = rollbackEnvironment

    override fun getDiffProvider(): DiffProvider = diffProvider

    override fun getVcsHistoryProvider(): VcsHistoryProvider = historyProvider

    override fun getVcsBlockHistoryProvider(): VcsHistoryProvider = historyProvider

    override fun getAnnotationProvider(): AnnotationProvider = annotationProvider

    override fun getUpdateEnvironment(): UpdateEnvironment = updateEnvironment

    override fun getConfigurable(): Configurable = PlasticConfigurable()

    /**
     * The platform only docks the commit UI into the Commit tool window (like Git) for distributed VCSes;
     * anything else gets the legacy modal "Commit Changes" dialog. Plastic works fine with the docked UI,
     * so force it, while still honouring the user's "toggle commit UI" advanced setting.
     */
    override fun getForcedCommitMode(): CommitMode =
        CommitMode.NonModalCommitMode(AdvancedSettings.getBoolean("vcs.non.modal.commit.toggle.ui"))

    override fun isVersionedDirectory(dir: VirtualFile): Boolean = PlasticRootChecker.findWorkspaceRoot(dir) != null

    companion object {
        const val NAME = "PlasticSCM"
        const val DISPLAY_NAME = "Plastic SCM"
        val KEY: VcsKey = createKey(NAME)

        fun getInstance(project: Project): PlasticVcs =
            ProjectLevelVcsManager.getInstance(project).findVcsByName(NAME) as PlasticVcs

        /** The Plastic workspace root that contains [path], as configured in the project's VCS mappings. */
        fun rootFor(project: Project, path: FilePath): Path? {
            val manager = ProjectLevelVcsManager.getInstance(project)
            if (manager.getVcsFor(path)?.name != NAME) return null
            return manager.getVcsRootFor(path)?.toNioPath()
        }
    }
}
