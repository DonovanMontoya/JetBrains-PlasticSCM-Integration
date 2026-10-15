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
import com.github.donovanmontoya.riderplastic.vcs.PlasticCheckinEnvironment
import com.github.donovanmontoya.riderplastic.vcs.PlasticContentRevision
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
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
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
import java.nio.file.Files
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel

/** A Plastic-only inline commit panel, independent of JetBrains AI Assistant APIs. */
class PlasticCommitPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val tree = PlasticChangesTree(project)
    private val commitMessage = CommitMessage(project, false, true, true, "Check-in comment")
    private val branchLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val checkInButton = JButton("Check In")
    private val generateButton = JButton("Generate")
    private val cancelGenerationButton = JButton("Cancel generation").apply { isEnabled = false }
    private val aiConsent = JCheckBox("Send the checked files' text diffs to the destination below for this request")
    private val aiDestination = JBLabel()
    private val aiStatus = JBLabel("AI generation uses your provider API account; separate charges may apply.")
    private val generationGuard = GenerationGuard()
    private var displayedAiConfig: AiRequestConfig? = null
    @Volatile private var generationIndicator: ProgressIndicator? = null
    @Volatile private var disposed = false
    private var generationActive = false

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
        generateButton.addActionListener { generateComment() }
        cancelGenerationButton.addActionListener { cancelGeneration() }
        val aiSettings = JButton("AI Settings...").apply {
            addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, PlasticConfigurable::class.java)
                updateAiDestination()
            }
        }
        val aiControls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(generateButton)
            add(cancelGenerationButton)
            add(aiSettings)
        }
        val aiPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(aiControls)
            add(aiDestination)
            add(aiConsent)
            add(aiStatus)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(checkInButton)
            add(branchLabel)
        }
        val bottom = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(6)
            add(aiPanel, BorderLayout.NORTH)
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
        updateAiDestination()
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

    // ---------------------------------------------------------------- plugin-owned AI generation

    private fun updateAiDestination() {
        aiConsent.isSelected = false
        displayedAiConfig = try {
            AiClient.validateConfig(PlasticSettings.getInstance().aiConfig())
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: AiClientException) {
            null
        }
        aiDestination.text = displayedAiConfig?.let {
            "Destination: ${AiClient.destinationLabel(it)} | Model: ${it.model}"
        } ?: "Configure a provider and model in AI Settings."
    }

    private fun selectedKeys(changes: List<Change>, unversioned: List<FilePath>): List<String> =
        (changes.map { "${it.beforeRevision?.file?.path.orEmpty()} -> ${it.afterRevision?.file?.path.orEmpty()}" } +
            unversioned.map { "+ ${it.path}" }).sorted()

    private fun generateComment() {
        if (generationActive || disposed || project.isDisposed) return
        val config = try {
            AiClient.validateConfig(PlasticSettings.getInstance().aiConfig())
        } catch (_: Exception) {
            aiStatus.text = "Choose a valid provider, model, and endpoint in AI Settings."
            return
        }
        if (displayedAiConfig != config) {
            updateAiDestination()
            aiStatus.text = "Provider settings changed. Review the destination and sharing checkbox."
            return
        }
        val changes = tree.includedChanges.toList()
        val unversioned = tree.includedUnversioned.toList()
        if (changes.isEmpty() && unversioned.isEmpty()) {
            aiStatus.text = "Check the Plastic SCM files to include before generating."
            return
        }
        if (!aiConsent.isSelected) {
            aiStatus.text = "Review the destination and allow this request using the sharing checkbox."
            return
        }
        // Each click authorizes only this captured selection/configuration, never later changes.
        aiConsent.isSelected = false
        val ticket = generationGuard.begin(commitMessage.editorField.document.modificationStamp, selectedKeys(changes, unversioned))
        generationActive = true
        generateButton.isEnabled = false
        cancelGenerationButton.isEnabled = true
        aiStatus.text = "Preparing checked file diffs..."
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
                    aiStatus.text = "Your message or checked selection changed; the generated draft was not applied."
                    return
                }
                result?.let { commitMessage.setCommitMessage(it) }
                aiStatus.text = "Draft generated. Review it before checking in."
            }

            override fun onCancel() {
                if (!disposed && generationGuard.isCurrent(ticket)) aiStatus.text = "Generation cancelled."
            }

            override fun onThrowable(error: Throwable) {
                if (disposed || project.isDisposed || !generationGuard.isCurrent(ticket)) return
                // Only our bounded, content-free validation errors are shown. Never log the key, diff, or provider body.
                aiStatus.text = when (error) {
                    is AiClientException -> error.message ?: "Generation could not complete."
                    else -> "Generation could not complete. Check the selected files and AI settings, then retry."
                }
            }

            override fun onFinished() {
                if (!generationGuard.isCurrent(ticket)) return
                generationIndicator = null
                generationActive = false
                if (!disposed && !project.isDisposed) {
                    generateButton.isEnabled = true
                    cancelGenerationButton.isEnabled = false
                }
            }
        }.queue()
    }

    private fun cancelGeneration() {
        val wasActive = generationActive
        generationGuard.cancel()
        generationIndicator?.cancel()
        generationIndicator = null
        generationActive = false
        if (!disposed) {
            generateButton.isEnabled = true
            cancelGenerationButton.isEnabled = false
            if (wasActive) aiStatus.text = "Generation cancelled; your comment was kept."
        }
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
        val files = mutableListOf<SelectedFileText>()
        for (change in changes) {
            indicator.checkCanceled()
            requireAiInput(ChangesUtil.getVcsForChange(change, project)?.name == PlasticVcs.NAME) { "Only checked Plastic SCM files can be sent." }
            val beforePath = change.beforeRevision?.file
            val afterPath = change.afterRevision?.file
            val beforeLabel = beforePath?.let(::relativePath)
            val afterLabel = afterPath?.let(::relativePath)
            beforePath?.let { requireAiInput(!it.fileType.isBinary) { "Binary files cannot be included in AI generation." } }
            afterPath?.let(::checkCurrentFile)
            val label = if (beforeLabel != null && afterLabel != null && beforeLabel != afterLabel) "$beforeLabel -> $afterLabel"
                else afterLabel ?: beforeLabel ?: throw AiClientException("A checked change has no readable path.")
            val before = change.beforeRevision?.let {
                val revision = it as? PlasticContentRevision
                    ?: throw AiClientException("A checked base revision is not a Plastic SCM revision.")
                val bytes = try {
                    revision.getContentAsBytesLimited(CommitPromptBuilder.MAX_REVISION_BYTES)
                } catch (_: IllegalArgumentException) {
                    throw AiClientException("A checked base revision is too large for AI generation. Uncheck it and retry.")
                }
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }
            indicator.checkCanceled()
            val after = afterPath?.let(::readWorkingText)
            files.add(SelectedFileText(label, before, after))
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

    private fun readWorkingText(path: FilePath): String {
        checkCurrentFile(path)
        val documentText = ReadAction.compute<String?, RuntimeException> {
            path.virtualFile?.let { FileDocumentManager.getInstance().getCachedDocument(it) }?.let {
                requireAiInput(it.textLength <= CommitPromptBuilder.MAX_REVISION_BYTES) { "A checked editor document is too large for AI generation." }
                it.immutableCharSequence.toString()
            }
        }
        return documentText ?: Files.newInputStream(path.ioFile.toPath()).use { stream ->
            val bytes = stream.readNBytes(CommitPromptBuilder.MAX_REVISION_BYTES + 1)
            requireAiInput(bytes.size <= CommitPromptBuilder.MAX_REVISION_BYTES) { "A checked file is too large for AI generation." }
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
    }

    private inline fun requireAiInput(condition: Boolean, message: () -> String) {
        if (!condition) throw AiClientException(message())
    }

    override fun dispose() {
        disposed = true
        generationGuard.cancel()
        generationIndicator?.cancel()
        generationIndicator = null
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
}
