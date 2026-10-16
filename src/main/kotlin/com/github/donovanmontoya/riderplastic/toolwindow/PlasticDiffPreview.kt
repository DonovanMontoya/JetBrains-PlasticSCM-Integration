package com.github.donovanmontoya.riderplastic.toolwindow

import com.intellij.ide.DataManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vcs.VcsApplicationSettings
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeViewDiffRequestProcessor
import com.intellij.openapi.vcs.changes.ui.DefaultChangesTreeDiffPreviewHandler
import com.intellij.openapi.vcs.changes.ui.TreeHandlerEditorDiffPreview
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.OpenSourceUtil
import java.awt.event.MouseEvent

/**
 * Diffs of the Plastic SCM tree in a single editor tab that follows the selection, like the Commit tool window's.
 *
 * Double-click and Enter open the selected files. On a change whose file is already the one in the editor, they show
 * its diff instead, so a second double-click goes from the file to its diff.
 */
class PlasticDiffPreview(private val changesTree: PlasticChangesTree) :
    TreeHandlerEditorDiffPreview(changesTree, DefaultChangesTreeDiffPreviewHandler) {

    override fun handleDoubleClick(e: MouseEvent): Boolean =
        !EditSourceOnDoubleClickHandler.isToggleEvent(changesTree, e) && openSelection()

    override fun handleEnterKey(): Boolean = openSelection()

    private fun openSelection(): Boolean {
        val lead = changesTree.leadObject
        val file = changesTree.virtualFileOf(lead)?.takeUnless { it.isDirectory }
        if (lead is Change) {
            // The IDE's "diff on double-click" choice for the Commit tool window applies here too.
            val showDiff = VcsApplicationSettings.getInstance().SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK || file == null ||
                file in FileEditorManager.getInstance(project).selectedFiles
            if (showDiff) return performDiffAction()
        }
        if (file == null) return false
        OpenSourceUtil.openSourcesFrom(DataManager.getInstance().getDataContext(changesTree), true)
        return true
    }

    override fun getEditorTabName(wrapper: ChangeViewDiffRequestProcessor.Wrapper?): String =
        wrapper?.presentableName?.let { "Plastic: $it" } ?: "Plastic Diff"
}
