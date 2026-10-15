package com.github.donovanmontoya.riderplastic.branch

import com.github.donovanmontoya.riderplastic.vcs.PlasticWorkspaceState
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import java.nio.file.Path

/** Base for actions that operate on "the" Plastic workspace of the current context. */
abstract class PlasticWorkspaceAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && PlasticWorkspaceState.getInstance(project).roots().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        perform(project, contextRoot(e, project) ?: return, e)
    }

    abstract fun perform(project: Project, root: Path, e: AnActionEvent)

    private fun contextRoot(e: AnActionEvent, project: Project): Path? {
        val state = PlasticWorkspaceState.getInstance(project)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val fromFile = file?.let { ProjectLevelVcsManager.getInstance(project).getVcsRootFor(it)?.toNioPath() }
        return fromFile?.takeIf { it in state.roots() } ?: state.roots().firstOrNull()
    }
}

class PlasticSwitchBranchAction : PlasticWorkspaceAction() {
    override fun perform(project: Project, root: Path, e: AnActionEvent) =
        PlasticBranchOperations.showBranchPopup(project, root, e.inputEvent?.component)
}

class PlasticCreateBranchAction : PlasticWorkspaceAction() {
    override fun perform(project: Project, root: Path, e: AnActionEvent) = PlasticBranchOperations.createBranch(project, root)
}
