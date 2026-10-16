package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.annotate.PlasticAnnotationProvider
import com.github.donovanmontoya.riderplastic.history.PlasticHistoryProvider
import com.github.donovanmontoya.riderplastic.settings.PlasticConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.VcsType
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.changes.ChangeProvider
import com.intellij.openapi.vcs.checkin.CheckinEnvironment
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.history.VcsHistoryProvider
import com.intellij.openapi.vcs.rollback.RollbackEnvironment
import com.intellij.openapi.vcs.update.UpdateEnvironment
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
     * Plastic supports distributed workflows, and reporting it as such matters in mixed projects: the platform only
     * keeps the docked Commit tool window when *every* active VCS is distributed, so a "centralized" Plastic would
     * push Git back to the modal commit dialog. Plastic check-ins themselves happen in the Plastic SCM tool window.
     */
    override fun getType(): VcsType = VcsType.distributed

    companion object {
        const val NAME = "PlasticSCM"
        const val DISPLAY_NAME = "Plastic SCM"
        val KEY: VcsKey = createKey(NAME)

        fun getInstance(project: Project): PlasticVcs =
            ProjectLevelVcsManager.getInstance(project).findVcsByName(NAME) as PlasticVcs

        /**
         * The mapped Plastic workspace root that contains [path]. This includes folders the IDE assigns to another VCS,
         * such as a Git repository nested in the workspace, whose files Plastic still tracks.
         */
        fun rootFor(project: Project, path: FilePath): Path? =
            ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(getInstance(project))
                .filter { FileUtil.isAncestor(it.path, path.path, false) }
                .maxByOrNull { it.path.length }
                ?.toNioPath()

        /** Whether the IDE assigns [path] to Plastic, rather than to another VCS mapped inside the workspace. */
        fun ownsInIde(project: Project, path: FilePath): Boolean =
            ProjectLevelVcsManager.getInstance(project).getVcsFor(path)?.name == NAME
    }
}
