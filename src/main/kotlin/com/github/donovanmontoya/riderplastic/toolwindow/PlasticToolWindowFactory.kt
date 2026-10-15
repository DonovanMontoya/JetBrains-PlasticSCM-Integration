package com.github.donovanmontoya.riderplastic.toolwindow

import com.github.donovanmontoya.riderplastic.settings.PlasticSettings
import com.github.donovanmontoya.riderplastic.vcs.PlasticWorkspaceState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/** Registers the "Plastic SCM" tool window, a dedicated commit panel that sits beside Git's Commit tool window. */
class PlasticToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setIcon(PlasticSettings.getInstance().toolWindowIcon.icon)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PlasticCommitPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = PlasticWorkspaceState.getInstance(project).roots().isNotEmpty()

    companion object {
        const val ID = "Plastic SCM"

        /** Shows/hides the tool window stripe button as Plastic mappings come and go. Call on the EDT. */
        fun updateAvailability(project: Project) {
            if (project.isDisposed) return
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.isAvailable = PlasticWorkspaceState.getInstance(project).roots().isNotEmpty()
        }

        /** Re-applies the icon chosen in Settings | Version Control | Plastic SCM. Call on the EDT. */
        fun applyIconToOpenProjects() {
            val icon = PlasticSettings.getInstance().toolWindowIcon.icon
            for (project in ProjectManager.getInstance().openProjects) {
                ToolWindowManager.getInstance(project).getToolWindow(ID)?.setIcon(icon)
            }
        }
    }
}
