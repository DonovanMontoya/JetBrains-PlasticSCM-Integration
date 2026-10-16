package com.github.donovanmontoya.riderplastic.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionExtensionProvider
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction

/**
 * Handles "Show Diff" in the Plastic SCM tree like the Commit tool window does: in the single diff preview tab.
 * The platform only handles these actions for its own Local Changes view.
 */
open class PlasticShowDiffActionProvider : AnActionExtensionProvider {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isActive(e: AnActionEvent) = e.getData(PlasticChangesTree.DATA_KEY) != null

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = ShowDiffAction.canShowDiff(e.project, e.getData(VcsDataKeys.CHANGES))
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(PlasticChangesTree.DATA_KEY)?.let(::showDiff)
    }

    protected open fun showDiff(tree: PlasticChangesTree) = tree.showDiff()
}

/** Handles "Show Diff in a New Tab": a new tab covering every displayed change, starting at the selection. */
class PlasticShowStandaloneDiffActionProvider : PlasticShowDiffActionProvider() {
    override fun showDiff(tree: PlasticChangesTree) = tree.showDiffInNewTab()
}
