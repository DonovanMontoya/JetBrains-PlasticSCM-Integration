package com.github.donovanmontoya.riderplastic.branch

import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.vcs.PlasticWorkspaceState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.SimpleListCellRenderer
import java.awt.Component
import java.nio.file.Path

object PlasticBranchOperations {

    private const val NEW_BRANCH = "+ New Branch..."

    /** Loads the branch list in the background, then shows a chooser. */
    fun showBranchPopup(project: Project, root: Path, anchor: Component?) {
        object : Task.Backgroundable(project, "Loading Plastic SCM branches", true) {
            private var branches: List<String> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                branches = PlasticCli.branches(root)
            }

            override fun onSuccess() {
                val current = PlasticWorkspaceState.getInstance(project).info(root)?.selector
                val items = listOf(NEW_BRANCH) + branches
                val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(items)
                    .setTitle("Plastic SCM Branches" + (current?.let { " (on $it)" } ?: ""))
                    .setNamerForFiltering { it }
                    .setRenderer(SimpleListCellRenderer.create<String>("") { if (it == current) "$it  ✓" else it })
                    .setItemChosenCallback { chosen ->
                        if (chosen == NEW_BRANCH) createBranch(project, root) else switchTo(project, root, chosen)
                    }
                    .createPopup()
                if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
            }

            override fun onThrowable(error: Throwable) = notifyError(project, "Unable to list branches", error)
        }.queue()
    }

    fun createBranch(project: Project, root: Path) {
        val current = PlasticWorkspaceState.getInstance(project).info(root)?.selector?.takeIf { it.startsWith("/") } ?: "/main"
        val name = Messages.showInputDialog(
            project,
            "Full name of the new child branch (it will be created from the workspace's current branch):",
            "Create Plastic SCM Branch",
            null,
            "$current/",
            null,
        )?.trim()?.takeIf { it.isNotEmpty() && it != "$current/" } ?: return
        val switch = Messages.showYesNoDialog(project, "Switch the workspace to $name now?", "Create Plastic SCM Branch", null) == Messages.YES

        object : Task.Backgroundable(project, "Creating branch $name", false) {
            override fun run(indicator: ProgressIndicator) {
                PlasticCli.createBranch(root, name)
                if (switch) {
                    indicator.text = "Switching to $name"
                    PlasticCli.switchTo(root, name)
                }
                afterWorkspaceChanged(project, root)
            }

            override fun onThrowable(error: Throwable) = notifyError(project, "Unable to create branch $name", error)
        }.queue()
    }

    fun switchTo(project: Project, root: Path, branch: String) {
        ApplicationManager.getApplication().invokeLater {
            FileDocumentManager.getInstance().saveAllDocuments()
            object : Task.Backgroundable(project, "Switching to $branch", false) {
                override fun run(indicator: ProgressIndicator) {
                    PlasticCli.switchTo(root, branch)
                    afterWorkspaceChanged(project, root)
                }

                override fun onSuccess() {
                    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                        .createNotification("Switched to $branch", NotificationType.INFORMATION)
                        .notify(project)
                }

                override fun onThrowable(error: Throwable) = notifyError(project, "Unable to switch to $branch", error)
            }.queue()
        }
    }

    private fun afterWorkspaceChanged(project: Project, root: Path) {
        PlasticWorkspaceState.getInstance(project).refreshSelector(root)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
    }

    fun notifyError(project: Project, title: String, error: Throwable) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, error.message ?: error.toString(), NotificationType.ERROR)
            .notify(project)
    }

    /** One notification for every error of a batch operation; nothing if there were none. */
    fun notifyErrors(project: Project, title: String, errors: List<VcsException>) {
        if (errors.isEmpty()) return
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, errors.joinToString("\n") { it.message }, NotificationType.ERROR)
            .notify(project)
    }

    const val NOTIFICATION_GROUP = "Plastic SCM"
}
