package com.github.donovanmontoya.riderplastic.toolwindow

import com.github.donovanmontoya.riderplastic.vcs.PlasticVcs
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.fileChooser.actions.VirtualFileDeleteProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.tree.TreeUtil

/**
 * The same tree the Commit tool window uses ("Changes" and "Unversioned Files" groups with checkboxes),
 * fed only with Plastic SCM items.
 */
class PlasticChangesTree(project: Project, parentDisposable: Disposable) : ChangesTree(project, true, false) {

    // Also takes over double-click and Enter.
    private val diffPreview = PlasticDiffPreview(this).also { Disposer.register(parentDisposable, it) }

    var changes: List<Change> = emptyList()
        private set
    var unversioned: List<FilePath> = emptyList()
        private set

    /** Replaces the displayed items. Newly appearing changes are checked by default; unversioned files are not. */
    fun setItems(newChanges: List<Change>, newUnversioned: List<FilePath>) {
        val previouslyShown = HashSet<Any>(changes)
        val shown = HashSet<Any>(newChanges).apply { addAll(newUnversioned) }
        val stillIncluded = includedSet.filter { it in shown }
        changes = newChanges
        unversioned = newUnversioned
        rebuildTree()
        setIncludedChanges(stillIncluded + newChanges.filter { it !in previouslyShown })
    }

    override fun rebuildTree() {
        updateTreeModel(
            TreeModelBuilder(project, grouping)
                .setChanges(changes, null)
                .setUnversioned(unversioned)
                .build(),
        )
    }

    val includedChanges: List<Change> get() = includedSet.filterIsInstance<Change>()

    val includedUnversioned: List<FilePath> get() = includedSet.filterIsInstance<FilePath>()

    val selectedChanges: List<Change> get() = VcsTreeModelData.selected(this).userObjects(Change::class.java)

    /** The user object of the lead selected node, such as a [Change] or an unversioned [FilePath]. */
    val leadObject: Any? get() = TreeUtil.getLastUserObject(leadSelectionPath)

    /** Shows the selected change in the single diff preview tab. */
    fun showDiff() {
        diffPreview.performDiffAction()
    }

    /** Opens the diff for every displayed change in a new tab, starting at the first selected one. */
    fun showDiffInNewTab() {
        val selected = selectedChanges.firstOrNull() ?: return
        ShowDiffAction.showDiffForChange(project, changes, changes.indexOf(selected).coerceAtLeast(0))
    }

    // Provides the same selection data as the IDE's Local Changes view, so its context menu actions work here too.
    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        val selection = VcsTreeModelData.selected(this)
        val exactSelection = VcsTreeModelData.exactlySelected(this)
        val unversionedSelection = VcsTreeModelData.selectedUnderTag(this, ChangesBrowserNode.UNVERSIONED_FILES_TAG)
        val selected = selection.userObjects()
        val selectedChanges = selected.filterIsInstance<Change>().toTypedArray()
        val lead = leadObject
        sink.set(DATA_KEY, this)
        sink.set(VcsDataKeys.CHANGES, selectedChanges)
        sink.set(VcsDataKeys.SELECTED_CHANGES, selectedChanges)
        sink.set(PlatformDataKeys.DELETE_ELEMENT_PROVIDER, if (selected.isNotEmpty()) VirtualFileDeleteProvider() else null)
        sink.lazy(VcsDataKeys.CHANGE_LEAD_SELECTION) { exactSelection.userObjects(Change::class.java).toTypedArray() }
        sink.lazy(ChangesListView.UNVERSIONED_FILE_PATHS_DATA_KEY) { unversionedSelection.userObjects(FilePath::class.java) }
        sink.lazy(OTHER_VCS_SELECTED) {
            val paths = ChangesUtil.iteratePaths(selectedChanges.asList()).toSet() + unversionedSelection.userObjects(FilePath::class.java)
            paths.any { !PlasticVcs.ownsInIde(project, it) }
        }
        sink.lazy(CommonDataKeys.VIRTUAL_FILE) { virtualFileOf(lead) }
        sink.lazy(CommonDataKeys.VIRTUAL_FILE_ARRAY) { selected.mapNotNull(::virtualFileOf).toTypedArray() }
        sink.lazy(VcsDataKeys.VIRTUAL_FILES) { selected.mapNotNull(::virtualFileOf) }
        sink.lazy(ChangesListView.EXACTLY_SELECTED_FILES_DATA_KEY) { VcsTreeModelData.mapToExactVirtualFile(exactSelection) }
        sink.lazy(CommonDataKeys.NAVIGATABLE_ARRAY) { ChangesUtil.getNavigatableArray(project, selected.mapNotNull(::virtualFileOf)) }
    }

    fun virtualFileOf(userObject: Any?): VirtualFile? {
        val file = when (userObject) {
            is Change -> ChangesUtil.getAfterPath(userObject)?.virtualFile
            is FilePath -> userObject.virtualFile
            is VirtualFile -> userObject
            else -> null
        }
        return file?.takeIf { it.isValid }
    }

    companion object {
        val DATA_KEY: DataKey<PlasticChangesTree> = DataKey.create("PlasticSCMIntegration.ChangesTree")

        /** Whether the selection includes files the IDE assigns to another VCS, such as a Git repository nested in the workspace. */
        val OTHER_VCS_SELECTED: DataKey<Boolean> = DataKey.create("PlasticSCMIntegration.OtherVcsSelected")
    }
}
