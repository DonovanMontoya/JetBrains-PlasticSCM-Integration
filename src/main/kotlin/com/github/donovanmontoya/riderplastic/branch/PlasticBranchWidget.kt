package com.github.donovanmontoya.riderplastic.branch

import com.github.donovanmontoya.riderplastic.vcs.PlasticWorkspaceListener
import com.github.donovanmontoya.riderplastic.vcs.PlasticWorkspaceState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

class PlasticBranchWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = PlasticBranchWidget.ID

    override fun getDisplayName(): String = "Plastic SCM Branch"

    override fun isAvailable(project: Project): Boolean = PlasticWorkspaceState.getInstance(project).roots().isNotEmpty()

    override fun createWidget(project: Project): StatusBarWidget = PlasticBranchWidget(project)
}

/** Shows the current Plastic branch in the status bar; clicking it opens the branch chooser. */
class PlasticBranchWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        project.messageBus.connect(this).subscribe(
            PlasticWorkspaceListener.TOPIC,
            PlasticWorkspaceListener { _, _ ->
                ApplicationManager.getApplication().invokeLater({ this.statusBar?.updateWidget(ID) }, project.disposed)
            },
        )
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val (_, info) = PlasticWorkspaceState.getInstance(project).primary() ?: return ""
        return "⎇ " + (info?.selector ?: "Plastic")
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String {
        val (root, info) = PlasticWorkspaceState.getInstance(project).primary() ?: return "Plastic SCM"
        val cs = info?.changeset?.let { ", changeset $it" }.orEmpty()
        return "Plastic SCM workspace $root: ${info?.selector ?: "unknown"}$cs. Click to switch branch."
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        val root = PlasticWorkspaceState.getInstance(project).primary()?.first ?: return@Consumer
        PlasticBranchOperations.showBranchPopup(project, root, event.component)
    }

    override fun dispose() {
        statusBar = null
    }

    companion object {
        const val ID = "PlasticSCMIntegration.BranchWidget"
    }
}
