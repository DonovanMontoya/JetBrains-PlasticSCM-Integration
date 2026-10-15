package com.github.donovanmontoya.riderplastic.toolwindow

import com.github.donovanmontoya.riderplastic.branch.PlasticBranchOperations
import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.vcs.PlasticCheckinEnvironment
import com.github.donovanmontoya.riderplastic.vcs.PlasticVcs
import com.github.donovanmontoya.riderplastic.vcs.PlasticWorkspaceListener
import com.github.donovanmontoya.riderplastic.vcs.PlasticWorkspaceState
import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.vcs.changes.ui.RollbackChangesDialog
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Content of the "Plastic SCM" tool window: a Commit-tool-window look-alike that only shows Plastic items,
 * so it can live next to Git's Commit tool window in mixed Git + Plastic projects.
 */
class PlasticCommitPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val tree = PlasticChangesTree(project)
    private val commitMessage = CommitMessage(project, false, false, true, "Check-in comment")
    private val branchLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val checkInButton = JButton("Check In")

    init {
        Disposer.register(this, commitMessage)
        tree.setDoubleClickHandler { showDiff(); true }
        tree.emptyText.text = "No Plastic SCM changes"

        val expander = DefaultTreeExpander(tree)
        val actions = DefaultActionGroup().apply {
            add(action("Refresh", "Refresh Plastic SCM status", AllIcons.Actions.Refresh) { refresh() })
            add(action("Undo Changes...", "Undo the selected changes (cm undo)", AllIcons.Actions.Rollback) { rollbackSelected() })
            add(action("Show Diff", "Show diff for the selected change", AllIcons.Actions.Diff) { showDiff() })
            add(action("Update Workspace", "Update the workspace to the latest changeset (cm update)", AllIcons.Actions.CheckOut) { updateWorkspace() })
            add(action("Branches...", "Switch or create Plastic SCM branches", AllIcons.Vcs.Branch) { e -> showBranches(e) })
            addSeparator()
            add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
            add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("PlasticCommitPanel", actions, true)
        toolbar.targetComponent = tree

        val top = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        }

        checkInButton.addActionListener { checkIn() }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(checkInButton)
            add(branchLabel)
        }
        val bottom = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(6)
            add(commitMessage, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }

        add(
            OnePixelSplitter(true, "RiderPlastic.CommitPanel.Splitter", 0.6f).apply {
                firstComponent = top
                secondComponent = bottom
            },
            BorderLayout.CENTER,
        )

        val connection = project.messageBus.connect(this)
        connection.subscribe(
            ChangeListListener.TOPIC,
            object : ChangeListListener {
                override fun changeListUpdateDone() = reloadLater()
            },
        )
        connection.subscribe(PlasticWorkspaceListener.TOPIC, PlasticWorkspaceListener { _, _ -> updateBranchLabelLater() })

        reload()
        updateBranchLabel()
    }

    // ---------------------------------------------------------------- model

    private fun reloadLater() = ApplicationManager.getApplication().invokeLater({ reload() }, project.disposed)

    private fun reload() {
        val clm = ChangeListManager.getInstance(project)
        val changes = clm.allChanges.filter { ChangesUtil.getVcsForChange(it, project)?.name == PlasticVcs.NAME }
        val unversioned = clm.unversionedFilesPaths.filter { PlasticVcs.rootFor(project, it) != null }
        tree.setItems(changes, unversioned)
    }

    private fun updateBranchLabelLater() = ApplicationManager.getApplication().invokeLater({ updateBranchLabel() }, project.disposed)

    private fun updateBranchLabel() {
        val state = PlasticWorkspaceState.getInstance(project)
        val roots = state.roots()
        branchLabel.text = when (roots.size) {
            0 -> "No Plastic SCM workspace mapped"
            1 -> "on ${state.info(roots[0])?.selector ?: "…"}"
            else -> roots.joinToString("  ") { "${it.fileName}: ${state.info(it)?.selector ?: "…"}" }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun refresh() {
        val dirtyScopeManager = VcsDirtyScopeManager.getInstance(project)
        PlasticWorkspaceState.getInstance(project).roots()
            .mapNotNull { LocalFileSystem.getInstance().findFileByNioFile(it) }
            .forEach { dirtyScopeManager.dirDirtyRecursively(it) }
    }

    private fun showDiff() {
        val selected = tree.selectedChanges
        if (selected.isEmpty()) return
        val all = tree.changes
        ShowDiffAction.showDiffForChange(project, all, all.indexOf(selected.first()).coerceAtLeast(0))
    }

    private fun rollbackSelected() {
        val selected = tree.selectedChanges.ifEmpty { tree.includedChanges }
        if (selected.isNotEmpty()) RollbackChangesDialog.rollbackChanges(project, selected)
    }

    private fun showBranches(e: AnActionEvent) {
        val root = PlasticWorkspaceState.getInstance(project).roots().firstOrNull() ?: return
        PlasticBranchOperations.showBranchPopup(project, root, e.inputEvent?.component)
    }

    private fun updateWorkspace() {
        FileDocumentManager.getInstance().saveAllDocuments()
        val roots = PlasticWorkspaceState.getInstance(project).roots()
        object : Task.Backgroundable(project, "Updating Plastic SCM workspace", true) {
            override fun run(indicator: ProgressIndicator) {
                for (root in roots) {
                    indicator.text = "Updating $root"
                    PlasticCli.update(root)
                    PlasticWorkspaceState.getInstance(project).refreshSelector(root)
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
                        ?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
                }
            }

            override fun onFinished() = refresh()

            override fun onThrowable(error: Throwable) =
                PlasticBranchOperations.notifyError(project, "Plastic SCM update failed", error)
        }.queue()
    }

    private fun checkIn() {
        val changes: List<Change> = tree.includedChanges
        val unversioned: List<FilePath> = tree.includedUnversioned
        val comment = commitMessage.comment.trim()
        if (changes.isEmpty() && unversioned.isEmpty()) {
            Messages.showInfoMessage(project, "Select the files to check in.", "Plastic SCM")
            return
        }
        if (comment.isEmpty()) {
            Messages.showErrorDialog(project, "Enter a check-in comment.", "Plastic SCM")
            return
        }

        FileDocumentManager.getInstance().saveAllDocuments()
        checkInButton.isEnabled = false
        val checkin = PlasticVcs.getInstance(project).checkinEnvironment as PlasticCheckinEnvironment
        object : Task.Backgroundable(project, "Checking in to Plastic SCM", false) {
            private var errors: List<VcsException> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                errors = checkin.checkIn(changes, unversioned, comment)
            }

            override fun onSuccess() {
                val group = NotificationGroupManager.getInstance().getNotificationGroup(PlasticBranchOperations.NOTIFICATION_GROUP)
                if (errors.isEmpty()) {
                    commitMessage.setCommitMessage("")
                    group.createNotification("Checked in ${changes.size + unversioned.size} file(s)", NotificationType.INFORMATION)
                        .notify(project)
                } else {
                    group.createNotification("Check-in failed", errors.joinToString("\n") { it.message }, NotificationType.ERROR)
                        .notify(project)
                }
            }

            override fun onThrowable(error: Throwable) =
                PlasticBranchOperations.notifyError(project, "Check-in failed", error)

            override fun onFinished() {
                checkInButton.isEnabled = true
                (changes.mapNotNull { it.afterRevision?.file ?: it.beforeRevision?.file } + unversioned)
                    .map { it.ioFile }
                    .let { LocalFileSystem.getInstance().refreshIoFiles(it, true, false, null) }
                refresh()
            }
        }.queue()
    }

    override fun dispose() {}

    private fun action(
        text: String,
        description: String,
        icon: Icon,
        perform: (AnActionEvent) -> Unit,
    ) = object : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = perform(e)
    }
}
