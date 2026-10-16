package com.github.donovanmontoya.riderplastic.toolwindow

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PlasticChangesPopupTest : BasePlatformTestCase() {

    fun `test menu is the IDE local changes menu with check in and undo kept on plastic`() {
        val checkIn = object : AnAction("Check In File...") {
            override fun actionPerformed(e: AnActionEvent) = Unit
        }
        val undo = object : AnAction("Undo Changes...") {
            override fun actionPerformed(e: AnActionEvent) = Unit
        }
        val group = PlasticChangesPopupGroup(mapOf(PlasticChangesPopupGroup.CHECKIN_FILES to checkIn, PlasticChangesPopupGroup.ROLLBACK to undo))
        val children = group.getChildren(null)
        val ids = children.map { ActionManager.getInstance().getId(it) }

        assertSame(checkIn, children.first())
        assertTrue(undo in children)
        assertFalse(PlasticChangesPopupGroup.CHECKIN_FILES in ids)
        assertFalse(PlasticChangesPopupGroup.ROLLBACK in ids)
        assertTrue(ids.containsAll(listOf("Diff.ShowDiff", "Diff.ShowStandaloneDiff", "EditSource", "\$Delete", "ChangesView.CreatePatch", "ChangesView.Refresh")))
        // Actions that act through the owning VCS or IDE changelists are wrapped so they can be disabled for nested Git files.
        children.single { it is AnActionWrapper && ActionManager.getInstance().getId(it.delegate) == "ChangesView.Shelve" }
    }

    fun `test tree exposes only what is selected to menu actions`() {
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        val tree = PlasticChangesTree(project, testRootDisposable)
        val modified = Change(null, CurrentContentRevision(LocalFilePath("/workspace/Modified.cs", false)))
        val other = Change(null, CurrentContentRevision(LocalFilePath("/workspace/Other.cs", false)))
        val privateFile = LocalFilePath("/workspace/Private.cs", false)
        tree.setItems(listOf(modified, other), listOf(privateFile))

        tree.setSelectedChanges(listOf(modified))
        var data = dataOf(tree)
        assertSame(tree, data.getData(PlasticChangesTree.DATA_KEY))
        assertEquals(listOf(modified), data.getData(VcsDataKeys.CHANGES)?.toList())
        assertEmpty(data.getData(ChangesListView.UNVERSIONED_FILE_PATHS_DATA_KEY)!!.toList())

        // An unversioned-only selection must not fall back to every change, or Rollback would offer all of them.
        tree.setSelectedChanges(listOf(privateFile))
        data = dataOf(tree)
        assertEmpty(data.getData(VcsDataKeys.CHANGES)!!.toList())
        assertEquals(listOf(privateFile), data.getData(ChangesListView.UNVERSIONED_FILE_PATHS_DATA_KEY)?.toList())
    }

    private fun dataOf(tree: PlasticChangesTree): DataContext = DataManager.getInstance().getDataContext(tree)
}
