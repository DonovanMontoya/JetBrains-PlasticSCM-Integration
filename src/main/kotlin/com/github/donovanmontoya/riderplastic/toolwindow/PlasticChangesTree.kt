package com.github.donovanmontoya.riderplastic.toolwindow

import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData

/**
 * The same tree the Commit tool window uses ("Changes" and "Unversioned Files" groups with checkboxes),
 * fed only with Plastic SCM items.
 */
class PlasticChangesTree(project: Project) : ChangesTree(project, true, false) {

    var changes: List<Change> = emptyList()
        private set
    var unversioned: List<FilePath> = emptyList()
        private set

    /** Replaces the displayed items. Newly appearing changes are checked by default; unversioned files are not. */
    fun setItems(newChanges: List<Change>, newUnversioned: List<FilePath>) {
        val previouslyShown = HashSet<Any>(changes)
        val stillIncluded = includedSet.filter { it in newChanges || it in newUnversioned }
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

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        val selected = selectedChanges
        sink.set(VcsDataKeys.CHANGES, selected.toTypedArray())
        sink.set(VcsDataKeys.SELECTED_CHANGES, selected.toTypedArray())
    }
}
