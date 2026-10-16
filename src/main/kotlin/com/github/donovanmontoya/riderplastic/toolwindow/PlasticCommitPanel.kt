package com.github.donovanmontoya.riderplastic.toolwindow

import com.github.donovanmontoya.riderplastic.ai.AiClient
import com.github.donovanmontoya.riderplastic.ai.AiClientException
import com.github.donovanmontoya.riderplastic.ai.AiCredentials
import com.github.donovanmontoya.riderplastic.ai.AiRequestConfig
import com.github.donovanmontoya.riderplastic.ai.CommitPromptBuilder
import com.github.donovanmontoya.riderplastic.ai.GenerationGuard
import com.github.donovanmontoya.riderplastic.ai.SelectedFileText
import com.github.donovanmontoya.riderplastic.settings.PlasticConfigurable
import com.github.donovanmontoya.riderplastic.settings.PlasticSettings
import com.github.donovanmontoya.riderplastic.branch.PlasticBranchOperations
import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.cli.BoundedContent
import com.github.donovanmontoya.riderplastic.vcs.PlasticCheckinEnvironment
import com.github.donovanmontoya.riderplastic.vcs.PlasticContentRevision
import com.github.donovanmontoya.riderplastic.vcs.PlasticNestedRootChanges
import com.github.donovanmontoya.riderplastic.vcs.PlasticRollbackEnvironment
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
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.rollback.RollbackProgressListener
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.update.Activatable
import com.intellij.util.ui.update.UiNotifyConnector
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Point
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.Callable
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel

/** A Plastic-only inline commit panel, independent of JetBrains AI Assistant APIs. */
class PlasticCommitPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val tree = PlasticChangesTree(project, this)
    private val commitMessage = CommitMessage(project, false, true, true, "Check-in comment")
    private val branchLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val checkInButton = JButton("Check In")
    private val aiToolbar = ActionManager.getInstance()
        .createActionToolbar("PlasticCommitPanel.Ai", DefaultActionGroup(GenerateCommentAction()), true)
    private val generationGuard = GenerationGuard()
    private val undoAction = UndoChangesAction()
    private val workspaceState = PlasticWorkspaceState.getInstance(project)
    private val nestedRootScan = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    @Volatile private var statusReadsSeen = workspaceState.statusReadCount
    @Volatile private var shown = false
    @Volatile private var nestedRootScanPending = false
    @Volatile private var generationIndicator: ProgressIndicator? = null
    @Volatile private var disposed = false
    @Volatile private var generationActive = false

    init {
        Disposer.register(this, commitMessage)
        tree.emptyText.text = "No Plastic SCM changes"
        val popup = PlasticChangesPopupGroup(
            mapOf(PlasticChangesPopupGroup.CHECKIN_FILES to CheckInSelectionAction(), PlasticChangesPopupGroup.ROLLBACK to undoAction),
        )
        PopupHandler.installPopupMenu(tree, popup, ActionPlaces.CHANGES_VIEW_POPUP)
        // The IDE's Rollback shortcut would revert files a nested Git repository owns through Git; keep it on cm undo here.
        undoAction.registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(PlasticChangesPopupGroup.ROLLBACK), tree)

        val expander = DefaultTreeExpander(tree)
        val actions = DefaultActionGroup().apply {
            add(action("Refresh", "Refresh Plastic SCM status", AllIcons.Actions.Refresh) { refresh() })
            add(undoAction)
            add(ActionManager.getInstance().getAction(IdeActions.ACTION_SHOW_DIFF_COMMON))
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

        // Swing listeners run without the write-intent lock that saving documents needs; invokeLater provides it.
        checkInButton.addActionListener {
            ApplicationManager.getApplication().invokeLater({ checkIn() }, ModalityState.stateForComponent(this), project.disposed)
        }
        aiToolbar.targetComponent = this
        aiToolbar.component.apply {
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(checkInButton)
            add(aiToolbar.component)
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
                override fun changeListUpdateDone() {
                    // The change provider's workspace status also covers nested folders; without it, another VCS changed.
                    val statusReads = workspaceState.statusReadCount
                    if (statusReads == statusReadsSeen) scheduleNestedRootScan() else statusReadsSeen = statusReads
                    reloadLater()
                }
            },
        )
        connection.subscribe(PlasticWorkspaceListener.TOPIC, PlasticWorkspaceListener { _, _ -> updateBranchLabelLater() })
        val visibility = UiNotifyConnector.installOn(
            this,
            object : Activatable {
                override fun showNotify() {
                    shown = true
                    if (nestedRootScanPending) scheduleNestedRootScan()
                }

                override fun hideNotify() {
                    shown = false
                }
            },
        )
        Disposer.register(this, visibility)

        reload()
        // Updates that only another VCS took part in may have happened before this panel existed. Before the first
        // workspace status, there is nothing to catch up on: that status covers the nested folders too.
        if (workspaceState.statusReadCount > 0) scheduleNestedRootScan()
        updateBranchLabel()
    }

    // ---------------------------------------------------------------- model

    private fun reloadLater() = ApplicationManager.getApplication().invokeLater({ reload() }, project.disposed)

    private fun reload() {
        val clm = ChangeListManager.getInstance(project)
        val changes = clm.allChanges.filter { ChangesUtil.getVcsForChange(it, project)?.name == PlasticVcs.NAME }
        val unversioned = clm.unversionedFilesPaths.filter { PlasticVcs.ownsInIde(project, it) }
        val nested = workspaceState.nestedChanges()
        tree.setItems(changes.union(nested.changes).toList(), unversioned.union(nested.unversioned).toList())
    }

    /**
     * Plastic changes in folders another VCS owns (such as a nested Git repository) never reach the IDE's change list.
     * The change provider records them with the rest of the workspace; after updates that only another VCS took part
     * in, they are read with `cm status` here, once the panel is visible. This does nothing without such folders.
     */
    private fun scheduleNestedRootScan() {
        if (disposed || project.isDisposed) return
        nestedRootScanPending = true
        if (!shown) return
        nestedRootScanPending = false
        nestedRootScan.cancelAllRequests()
        nestedRootScan.addRequest({
            val statusReads = workspaceState.statusReadCount
            val result = try {
                PlasticNestedRootChanges.collect(project)
            } catch (_: ProcessCanceledException) {
                return@addRequest
            } catch (e: VcsException) {
                LOG.info("Unable to read Plastic SCM changes in nested VCS roots", e)
                return@addRequest
            }
            // A workspace status read meanwhile is newer than this scan.
            if (workspaceState.statusReadCount == statusReads && workspaceState.updateNestedChanges(result)) reloadLater()
        }, NESTED_ROOT_SCAN_DELAY_MS)
    }

    private fun updateBranchLabelLater() = ApplicationManager.getApplication().invokeLater({ updateBranchLabel() }, project.disposed)

    private fun updateBranchLabel() {
        val roots = workspaceState.roots()
        branchLabel.text = when (roots.size) {
            0 -> "No Plastic SCM workspace mapped"
            1 -> "on ${workspaceState.info(roots[0])?.selector ?: "…"}"
            else -> roots.joinToString("  ") { "${it.fileName}: ${workspaceState.info(it)?.selector ?: "…"}" }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun refresh() {
        val dirtyScopeManager = VcsDirtyScopeManager.getInstance(project)
        workspaceState.roots()
            .mapNotNull { LocalFileSystem.getInstance().findFileByNioFile(it) }
            .forEach { dirtyScopeManager.dirDirtyRecursively(it) }
    }

    /** Undoes changes with `cm undo` for every item, including those in folders another VCS owns. */
    private inner class UndoChangesAction : DumbAwareAction("Undo Changes...", "Undo the selected changes (cm undo)", AllIcons.Actions.Rollback) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = VcsTreeModelData.selected(tree).iterateUserObjects(Change::class.java).isNotEmpty ||
                tree.includedSet.any { it is Change }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val changes = tree.selectedChanges.ifEmpty { tree.includedChanges }
            if (changes.isEmpty()) return
            val answer = Messages.showYesNoDialog(
                project,
                "Undo local changes to ${changes.size} file(s) with cm undo? Added files stay on disk as private files.",
                "Undo Plastic SCM Changes",
                Messages.getWarningIcon(),
            )
            if (answer == Messages.YES) undoChanges(changes)
        }
    }

    private fun undoChanges(changes: List<Change>) {
        FileDocumentManager.getInstance().saveAllDocuments()
        val rollback = PlasticVcs.getInstance(project).rollbackEnvironment as PlasticRollbackEnvironment
        object : Task.Backgroundable(project, "Undoing Plastic SCM changes", false) {
            private val errors = mutableListOf<VcsException>()

            override fun run(indicator: ProgressIndicator) = rollback.rollbackChanges(changes, errors, RollbackProgressListener.EMPTY)

            override fun onSuccess() = PlasticBranchOperations.notifyErrors(project, "Undo failed", errors)

            override fun onThrowable(error: Throwable) = PlasticBranchOperations.notifyError(project, "Undo failed", error)

            // The rollback environment refreshes the undone files itself.
            override fun onFinished() = refresh()
        }.queue()
    }

    /** Checks only the selected files and moves focus to the comment, like "Commit File..." in the Commit tool window. */
    private inner class CheckInSelectionAction : DumbAwareAction("Check In File...", "Check in the selected files from this tool window", null) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            val count = selection(e).size
            e.presentation.text = if (count == 1) "Check In File..." else "Check In Files..."
            e.presentation.isEnabled = count > 0
        }

        override fun actionPerformed(e: AnActionEvent) {
            val selected = selection(e)
            if (selected.isEmpty()) return
            tree.setIncludedChanges(selected)
            IdeFocusManager.getInstance(project).requestFocus(commitMessage.editorField, true)
        }

        private fun selection(e: AnActionEvent): List<Any> =
            e.getData(VcsDataKeys.CHANGES).orEmpty().toList() + (e.getData(ChangesListView.UNVERSIONED_FILE_PATHS_DATA_KEY) ?: emptyList())
    }

    private fun showBranches(e: AnActionEvent) {
        val root = workspaceState.roots().firstOrNull() ?: return
        PlasticBranchOperations.showBranchPopup(project, root, e.inputEvent?.component)
    }

    private fun updateWorkspace() {
        FileDocumentManager.getInstance().saveAllDocuments()
        val roots = workspaceState.roots()
        object : Task.Backgroundable(project, "Updating Plastic SCM workspace", true) {
            override fun run(indicator: ProgressIndicator) {
                for (root in roots) {
                    indicator.text = "Updating $root"
                    PlasticCli.update(root)
                    workspaceState.refreshSelector(root)
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

        cancelGeneration()
        FileDocumentManager.getInstance().saveAllDocuments()
        checkInButton.isEnabled = false
        val checkin = PlasticVcs.getInstance(project).checkinEnvironment as PlasticCheckinEnvironment
        object : Task.Backgroundable(project, "Checking in to Plastic SCM", false) {
            private var errors: List<VcsException> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                errors = checkin.checkIn(changes, unversioned, comment)
            }

            override fun onSuccess() {
                if (errors.isEmpty()) {
                    commitMessage.setCommitMessage("")
                    NotificationGroupManager.getInstance().getNotificationGroup(PlasticBranchOperations.NOTIFICATION_GROUP)
                        .createNotification("Checked in ${changes.size + unversioned.size} file(s)", NotificationType.INFORMATION)
                        .notify(project)
                } else {
                    PlasticBranchOperations.notifyErrors(project, "Check-in failed", errors)
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

    // ---------------------------------------------------------------- plugin-owned AI generation

    /** One small button: generates a comment, cancels a running generation, or opens AI settings when none are set up. */
    private inner class GenerateCommentAction : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            val presentation = e.presentation
            if (generationActive) {
                presentation.icon = AllIcons.Actions.Suspend
                presentation.text = "Cancel Comment Generation"
                presentation.description = null
                return
            }
            val config = currentAiConfig()
            presentation.icon = AI_ICON
            presentation.text = if (config == null) "Set Up AI Check-In Comments..." else "Generate Check-In Comment"
            // The tooltip names where the checked files' diffs go, so clicking is an informed choice.
            presentation.description = config?.let {
                "Sends the checked files' text diffs to ${AiClient.destinationLabel(it)} (${it.model}). Provider charges may apply."
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (generationActive) return cancelGeneration()
            val config = currentAiConfig() ?: return ShowSettingsUtil.getInstance().showSettingsDialog(project, PlasticConfigurable::class.java)
            generateComment(config)
        }
    }

    private fun currentAiConfig(): AiRequestConfig? = try {
        AiClient.validateConfig(PlasticSettings.getInstance().aiConfig())
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: AiClientException) {
        null
    }

    private fun showAiMessage(text: String, type: MessageType = MessageType.WARNING) {
        val anchor = aiToolbar.component
        if (disposed || !anchor.isShowing) return
        JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(StringUtil.escapeXmlEntities(text), type, null)
            .setFadeoutTime(6000)
            .createBalloon()
            .show(RelativePoint(anchor, Point(anchor.width / 2, 0)), Balloon.Position.above)
    }

    private fun setGenerationActive(active: Boolean) {
        generationActive = active
        if (!disposed) aiToolbar.updateActionsAsync()
    }

    private fun selectedKeys(changes: List<Change>, unversioned: List<FilePath>): List<String> =
        (changes.map { "${it.beforeRevision?.file?.path.orEmpty()} -> ${it.afterRevision?.file?.path.orEmpty()}" } +
            unversioned.map { "+ ${it.path}" }).sorted()

    private fun generateComment(config: AiRequestConfig) {
        if (generationActive || disposed || project.isDisposed) return
        val changes = tree.includedChanges.toList()
        val unversioned = tree.includedUnversioned.toList()
        if (changes.isEmpty() && unversioned.isEmpty()) {
            showAiMessage("Check the Plastic SCM files to include before generating.")
            return
        }
        // Each click authorizes only this captured selection/configuration, never later changes.
        val ticket = generationGuard.begin(commitMessage.editorField.document.modificationStamp, selectedKeys(changes, unversioned))
        setGenerationActive(true)
        object : Task.Backgroundable(project, "Generating Plastic SCM check-in comment", true) {
            private var result: String? = null

            override fun run(indicator: ProgressIndicator) {
                generationIndicator = indicator
                val checkCancelled = {
                    indicator.checkCanceled()
                    if (disposed || project.isDisposed || !generationGuard.isCurrent(ticket)) throw ProcessCanceledException()
                }
                checkCancelled()
                val snapshots = readSelectedFiles(changes, unversioned, indicator)
                val prompt = try {
                    CommitPromptBuilder.build(snapshots)
                } catch (invalid: IllegalArgumentException) {
                    // This pure builder guarantees content-free validation messages.
                    throw AiClientException(invalid.message ?: "The selected files cannot be sent for AI generation.")
                }
                checkCancelled()
                val key = AiCredentials.read(config)
                checkCancelled()
                result = AiClient().generate(config, key, prompt, checkCancelled)
                checkCancelled()
            }

            override fun onSuccess() {
                if (disposed || project.isDisposed || !generationGuard.isCurrent(ticket)) return
                val document = commitMessage.editorField.document
                if (!generationGuard.canApply(ticket, document.modificationStamp, selectedKeys(tree.includedChanges, tree.includedUnversioned))) {
                    showAiMessage("Your message or checked selection changed; the generated draft was not applied.")
                    return
                }
                result?.let { commitMessage.setCommitMessage(it) }
            }

            override fun onThrowable(error: Throwable) {
                if (disposed || project.isDisposed || !generationGuard.isCurrent(ticket)) return
                // Only our bounded, content-free validation errors are shown. Never log the key, diff, or provider body.
                showAiMessage(
                    when (error) {
                        is AiClientException -> error.message ?: "Generation could not complete."
                        else -> "Generation could not complete. Check the selected files and AI settings, then retry."
                    },
                    MessageType.ERROR,
                )
            }

            override fun onFinished() {
                if (!generationGuard.isCurrent(ticket)) return
                generationIndicator = null
                setGenerationActive(false)
            }
        }.queue()
    }

    private fun cancelGeneration() {
        generationGuard.cancel()
        generationIndicator?.cancel()
        generationIndicator = null
        setGenerationActive(false)
    }

    private fun relativePath(path: FilePath): String {
        val root = PlasticVcs.rootFor(project, path)?.toAbsolutePath()?.normalize()
            ?: throw AiClientException("A checked file is no longer mapped to Plastic SCM. Refresh and try again.")
        val file = path.ioFile.toPath().toAbsolutePath().normalize()
        requireAiInput(file.startsWith(root)) { "A checked file is outside the Plastic SCM workspace." }
        requireAiInput(!path.isDirectory) { "Select individual text files rather than directories for AI generation." }
        return root.relativize(file).joinToString("/")
    }

    private fun checkCurrentFile(path: FilePath) {
        val file = path.ioFile.toPath()
        requireAiInput(!Files.isSymbolicLink(file)) { "Symbolic links cannot be included in AI generation." }
        if (Files.exists(file)) {
            val root = PlasticVcs.rootFor(project, path) ?: throw AiClientException("A checked file has no Plastic SCM workspace.")
            requireAiInput(file.toRealPath().startsWith(root.toRealPath())) { "A checked file resolves outside its Plastic SCM workspace." }
            requireAiInput(Files.size(file) <= CommitPromptBuilder.MAX_REVISION_BYTES) { "A checked file is too large for AI generation. Uncheck it and retry." }
        }
        requireAiInput(!path.fileType.isBinary) { "Binary files cannot be included in AI generation. Uncheck them and retry." }
    }

    private fun readSelectedFiles(changes: List<Change>, unversioned: List<FilePath>, indicator: ProgressIndicator): List<SelectedFileText> {
        requireAiInput(changes.size + unversioned.size <= CommitPromptBuilder.MAX_FILES) { "Too many checked files for one AI request." }
        // Every checked change is validated before any content is read.
        val pending = changes.map { change ->
            indicator.checkCanceled()
            val beforePath = change.beforeRevision?.file
            val afterPath = change.afterRevision?.file
            val beforeLabel = beforePath?.let(::relativePath)
            val afterLabel = afterPath?.let(::relativePath)
            beforePath?.let { requireAiInput(!it.fileType.isBinary) { "Binary files cannot be included in AI generation." } }
            afterPath?.let(::checkCurrentFile)
            val label = if (beforeLabel != null && afterLabel != null && beforeLabel != afterLabel) "$beforeLabel -> $afterLabel"
                else afterLabel ?: beforeLabel ?: throw AiClientException("A checked change has no readable path.")
            val base = change.beforeRevision?.let {
                it as? PlasticContentRevision ?: throw AiClientException("A checked base revision is not a Plastic SCM revision.")
            }
            PendingFile(label, base, afterPath)
        }
        val bases = readBaseRevisions(pending.map { it.base }, indicator)
        val files = pending.mapIndexedTo(mutableListOf()) { index, file ->
            indicator.checkCanceled()
            SelectedFileText(file.label, bases[index]?.let(::decodeUtf8), file.afterPath?.let(::readWorkingText))
        }
        for (path in unversioned) {
            indicator.checkCanceled()
            val label = relativePath(path)
            checkCurrentFile(path)
            val text = readWorkingText(path)
            files.add(SelectedFileText(label, null, text))
        }
        return files
    }

    /** Most of the half second `cm cat` takes is process startup, so base revisions are read a few at a time. */
    private fun readBaseRevisions(revisions: List<PlasticContentRevision?>, indicator: ProgressIndicator): List<ByteArray?> {
        val reader = AppExecutorUtil.createBoundedApplicationPoolExecutor("Plastic SCM base revision reads", PARALLEL_BASE_READS)
        val reads = revisions.map { revision ->
            revision?.let {
                reader.submit(
                    Callable {
                        runCatching {
                            var bytes: ByteArray? = null
                            // Under the generation's indicator, so cancelling it also stops the running cm processes.
                            ProgressManager.getInstance().executeProcessUnderProgress(
                                { bytes = it.getContentAsBytesLimited(CommitPromptBuilder.MAX_REVISION_BYTES) },
                                indicator,
                            )
                            checkNotNull(bytes)
                        }
                    },
                )
            }
        }
        try {
            return reads.map { read ->
                read?.let { ProgressIndicatorUtils.awaitWithCheckCanceled(it, indicator) }?.getOrElse { error ->
                    if (error is IllegalArgumentException) {
                        throw AiClientException("A checked base revision is too large for AI generation. Uncheck it and retry.")
                    }
                    throw error
                }
            }
        } finally {
            reads.forEach { it?.cancel(false) }
            reader.shutdown()
        }
    }

    private fun readWorkingText(path: FilePath): String {
        checkCurrentFile(path)
        val documentText = ReadAction.compute<String?, RuntimeException> {
            path.virtualFile?.let { FileDocumentManager.getInstance().getCachedDocument(it) }?.let {
                requireAiInput(it.textLength <= CommitPromptBuilder.MAX_REVISION_BYTES) { "A checked editor document is too large for AI generation." }
                it.immutableCharSequence.toString()
            }
        }
        return documentText ?: Files.newInputStream(path.ioFile.toPath()).use { stream ->
            val bytes = try {
                BoundedContent.read(stream, CommitPromptBuilder.MAX_REVISION_BYTES)
            } catch (_: IllegalArgumentException) {
                throw AiClientException("A checked file is too large for AI generation.")
            }
            decodeUtf8(bytes)
        }
    }

    /** Strict: malformed input fails instead of sending replacement characters. */
    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()

    private class PendingFile(val label: String, val base: PlasticContentRevision?, val afterPath: FilePath?)

    private inline fun requireAiInput(condition: Boolean, message: () -> String) {
        if (!condition) throw AiClientException(message())
    }

    override fun dispose() {
        disposed = true
        cancelGeneration()
    }

    private fun action(
        text: String,
        description: String,
        icon: Icon,
        perform: (AnActionEvent) -> Unit,
    ) = object : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = perform(e)
    }

    private companion object {
        val LOG = logger<PlasticCommitPanel>()
        val AI_ICON = IconLoader.getIcon("/icons/aiGenerate.svg", PlasticCommitPanel::class.java)
        const val NESTED_ROOT_SCAN_DELAY_MS = 500
        const val PARALLEL_BASE_READS = 4
    }
}
