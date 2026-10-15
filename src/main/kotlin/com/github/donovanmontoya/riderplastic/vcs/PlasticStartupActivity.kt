package com.github.donovanmontoya.riderplastic.vcs

import com.github.donovanmontoya.riderplastic.branch.PlasticBranchWidgetFactory
import com.github.donovanmontoya.riderplastic.toolwindow.PlasticToolWindowFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsMappingListener
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager

/** Keeps the branch widget, the Plastic SCM tool window and cached workspace info in sync with the project's VCS mappings. */
class PlasticStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val connection = project.messageBus.connect(PlasticWorkspaceState.getInstance(project))
        connection.subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, VcsMappingListener { refresh(project) })
        refresh(project)
    }

    private fun refresh(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val state = PlasticWorkspaceState.getInstance(project)
            state.roots().forEach { state.refreshSelector(it) }
            ApplicationManager.getApplication().invokeLater(
                {
                    project.service<StatusBarWidgetsManager>().updateWidget(PlasticBranchWidgetFactory::class.java)
                    PlasticToolWindowFactory.updateAvailability(project)
                },
                project.disposed,
            )
        }
    }
}
