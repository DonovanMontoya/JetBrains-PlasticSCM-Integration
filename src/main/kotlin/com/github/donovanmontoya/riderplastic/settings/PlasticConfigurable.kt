package com.github.donovanmontoya.riderplastic.settings

import com.github.donovanmontoya.riderplastic.ai.AiClient
import com.github.donovanmontoya.riderplastic.ai.AiClientException
import com.github.donovanmontoya.riderplastic.ai.AiCredentialScope
import com.github.donovanmontoya.riderplastic.ai.AiCredentials
import com.github.donovanmontoya.riderplastic.ai.AiProvider
import com.github.donovanmontoya.riderplastic.ai.AiRequestConfig
import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.toolwindow.PlasticToolWindowFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selectedValueIs
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Settings | Version Control | Plastic SCM. */
class PlasticConfigurable : BoundConfigurable("Plastic SCM") {

    private val settings = PlasticSettings.getInstance()
    private lateinit var providerField: JComboBox<AiProvider>
    private lateinit var openAiModelField: JTextField
    private lateinit var anthropicModelField: JTextField
    private lateinit var localModelField: JTextField
    private lateinit var localBaseUrlField: JTextField
    private lateinit var keyField: JPasswordField
    private lateinit var keyScopeLabel: JLabel
    private lateinit var keyStatusLabel: JLabel
    private lateinit var saveKeyButton: JButton
    private lateinit var removeKeyButton: JButton
    private lateinit var checkKeyButton: JButton
    private var uiReady = false
    private var keyOperationRunning = false
    private var uiGeneration = 0L
    private var lastKeyScope: String? = null

    override fun createPanel(): DialogPanel {
        uiReady = false
        uiGeneration++
        lateinit var pathField: TextFieldWithBrowseButton
        val result = panel {
            row("Path to cm executable:") {
                pathField = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select cm Executable"),
                ).bindText(settings::cmExecutable)
                    .align(AlignX.FILL)
                    .comment("Leave empty to auto-detect (currently: ${PlasticSettings.detectCmExecutable() ?: "not found"})")
                    .component
                button("Test") { testExecutable(pathField.text) }
            }
            row("Tool window icon:") {
                comboBox(
                    PlasticToolWindowIcon.entries,
                    SimpleListCellRenderer.create<PlasticToolWindowIcon> { label, value, _ ->
                        label.text = value?.displayName.orEmpty()
                        label.icon = value?.icon
                    },
                ).bindItem({ settings.toolWindowIcon }, { settings.toolWindowIcon = it ?: PlasticToolWindowIcon.LANES })
            }
            group("AI Commit Messages (Your API Key)") {
                row {
                    comment("Checked file diffs are sent to the selected provider only when you click the AI button in the Plastic SCM tool window.")
                }
                row {
                    comment("Hosted API usage is billed separately by the provider; an IDE AI subscription does not cover these calls.")
                }
                row("Provider:") {
                    providerField = comboBox(
                        AiProvider.entries,
                        SimpleListCellRenderer.create { label, value, _ -> label.text = value?.let(::providerName).orEmpty() },
                    ).component
                }
                // Only the selected provider's fields are shown; the others keep their values for switching back.
                row("OpenAI model:") {
                    openAiModelField = textField().align(AlignX.FILL)
                        .comment("Enter a model ID available to your OpenAI API account.")
                        .component
                }.visibleIf(providerField.selectedValueIs(AiProvider.OPENAI))
                row("Claude model:") {
                    anthropicModelField = textField().align(AlignX.FILL)
                        .comment("Enter a model ID available to your Anthropic API account. Uses the native Messages API.")
                        .component
                }.visibleIf(providerField.selectedValueIs(AiProvider.ANTHROPIC))
                rowsRange {
                    row("Local model:") {
                        localModelField = textField().align(AlignX.FILL)
                            .comment("Enter the model ID served by your OpenAI-compatible endpoint.")
                            .component
                    }
                    row("Local base URL:") {
                        localBaseUrlField = textField().align(AlignX.FILL)
                            .comment("Include /v1. HTTP is allowed only for loopback hosts; other endpoints require HTTPS. Never put a key in this URL.")
                            .component
                    }
                }.visibleIf(providerField.selectedValueIs(AiProvider.LOCAL))
                row {
                    keyScopeLabel = label("").component
                }
                row("API key:") {
                    keyField = cell(JPasswordField(32)).align(AlignX.FILL)
                        .comment("Optional for local endpoints. Leave blank to keep the existing key; stored keys are never displayed.")
                        .component
                }
                row {
                    saveKeyButton = button("Save / Replace Key") { saveKey() }.component
                    removeKeyButton = button("Remove Key") { runKeyOperation(KeyOperation.REMOVE) }.component
                    checkKeyButton = button("Check Saved Key") { runKeyOperation(KeyOperation.CHECK) }.component
                }
                row {
                    keyStatusLabel = label("Keys are kept separately for each provider and endpoint.").component
                }
                row {
                    comment("Key buttons take effect immediately, including if you later cancel Settings. Apply saves provider, models and URL.")
                }
                row {
                    comment(
                        if (AiCredentials.sessionOnly) {
                            "Session-only key storage on IDE 2025.2. Re-enter keys after restarting the IDE (or remote backend)."
                        } else {
                            "Keys use this plugin's IDE PasswordSafe entries and follow your IDE password storage settings."
                        },
                    )
                }
            }
        }
        loadAiFields()
        providerField.addActionListener { refreshKeyScope() }
        localBaseUrlField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = refreshKeyScope()
            override fun removeUpdate(event: DocumentEvent) = refreshKeyScope()
            override fun changedUpdate(event: DocumentEvent) = refreshKeyScope()
        })
        uiReady = true
        refreshKeyScope()
        return result
    }

    override fun isModified(): Boolean = super.isModified() ||
        (uiReady && (readAiFields() != settings.aiSettings || hasPendingKey()))

    override fun apply() {
        if (uiReady) {
            if (keyOperationRunning) throw ConfigurationException("Wait for the API key operation to finish.")
            if (hasPendingKey()) throw ConfigurationException("Use Save / Replace Key, or clear the API key field, before applying settings.")
            try {
                AiClient.normalizeBaseUrl(AiProvider.LOCAL, localBaseUrlField.text)
            } catch (_: AiClientException) {
                throw ConfigurationException("Enter a valid OpenAI-compatible /v1 URL. Use HTTPS except for loopback hosts, and omit credentials, queries and fragments.")
            }
        }
        super.apply()
        if (uiReady) {
            settings.aiSettings = readAiFields()
            loadAiFields()
            refreshKeyScope()
        }
        PlasticToolWindowFactory.applyIconToOpenProjects()
    }

    override fun reset() {
        super.reset()
        if (uiReady && !keyOperationRunning) {
            loadAiFields()
            refreshKeyScope()
        }
    }

    override fun disposeUIResources() {
        uiGeneration++
        uiReady = false
        if (::keyField.isInitialized) keyField.text = ""
        super.disposeUIResources()
    }

    private fun loadAiFields() {
        val values = settings.aiSettings
        providerField.selectedItem = values.provider
        openAiModelField.text = values.openAiModel
        anthropicModelField.text = values.anthropicModel
        localModelField.text = values.localModel
        localBaseUrlField.text = values.localBaseUrl
        keyField.text = ""
        keyOperationRunning = false
    }

    private fun readAiFields() = PlasticAiSettings(
        provider = providerField.selectedItem as? AiProvider ?: AiProvider.OPENAI,
        openAiModel = openAiModelField.text.trim(),
        anthropicModel = anthropicModelField.text.trim(),
        localModel = localModelField.text.trim(),
        localBaseUrl = localBaseUrlField.text.trim(),
    )

    private fun hasPendingKey(): Boolean {
        val key = keyField.password
        return try { key.isNotEmpty() } finally { key.fill('\u0000') }
    }

    private fun refreshKeyScope() {
        if (!uiReady) return
        val config = readAiFields().requestConfig()
        val scope = try { AiCredentialScope.key(config) } catch (_: AiClientException) { null }
        if (scope != lastKeyScope) {
            // Never carry a typed key into a different provider/origin.
            keyField.text = ""
            keyStatusLabel.text = "Choose Check Saved Key to check this provider and endpoint."
            lastKeyScope = scope
        }
        keyScopeLabel.text = if (scope == null) {
            "Fix the local URL before saving or looking up a key."
        } else {
            "Key for ${providerName(config.provider)} at ${AiCredentialScope.origin(config)}"
        }
        setKeyControlsEnabled(!keyOperationRunning && scope != null)
    }

    private fun setKeyControlsEnabled(enabled: Boolean) {
        saveKeyButton.isEnabled = enabled
        removeKeyButton.isEnabled = enabled
        checkKeyButton.isEnabled = enabled
        keyField.isEnabled = enabled
        providerField.isEnabled = !keyOperationRunning
        localBaseUrlField.isEnabled = !keyOperationRunning
    }

    private fun saveKey() {
        val key = keyField.password
        if (key.isEmpty() || key.all { it.isWhitespace() }) {
            key.fill('\u0000')
            keyStatusLabel.text = "Enter a key to save or replace it. A blank field leaves the existing key unchanged."
            return
        }
        if (key.size > 4096 || key.any { it.code !in 33..126 }) {
            key.fill('\u0000')
            keyStatusLabel.text = "Use a key of at most 4096 characters without spaces or control characters."
            return
        }
        runKeyOperation(KeyOperation.SAVE, key)
    }

    private fun runKeyOperation(operation: KeyOperation, key: CharArray? = null) {
        if (!uiReady || keyOperationRunning) {
            key?.fill('\u0000')
            return
        }
        val config: AiRequestConfig = readAiFields().requestConfig()
        try {
            AiCredentialScope.key(config)
        } catch (_: AiClientException) {
            key?.fill('\u0000')
            keyStatusLabel.text = "Fix the local /v1 URL first."
            return
        }
        val generation = uiGeneration
        keyOperationRunning = true
        if (operation != KeyOperation.CHECK) keyField.text = ""
        setKeyControlsEnabled(false)
        keyStatusLabel.text = "Accessing IDE password storage..."
        val application = ApplicationManager.getApplication()
        // Capture the provider/origin before dispatch. Never read mutable UI state in this worker.
        application.executeOnPooledThread {
            val message = try {
                when (operation) {
                    KeyOperation.SAVE -> {
                        AiCredentials.save(config, requireNotNull(key))
                        if (AiCredentials.sessionOnly) "API key saved for this IDE session." else "API key saved in IDE PasswordSafe."
                    }
                    KeyOperation.REMOVE -> {
                        AiCredentials.remove(config)
                        "API key removed for this provider and endpoint."
                    }
                    KeyOperation.CHECK -> if (AiCredentials.read(config).isNullOrBlank()) {
                        "No saved key for this provider and endpoint. Local endpoints may not need one."
                    } else {
                        "A key is saved for this provider and endpoint."
                    }
                }
            } catch (_: ProcessCanceledException) {
                "API key operation cancelled."
            } catch (_: Exception) {
                "Could not access the API key. Check the IDE password storage settings and try again."
            } finally {
                key?.fill('\u0000')
            }
            application.invokeLater({
                if (uiReady && uiGeneration == generation) {
                    keyOperationRunning = false
                    refreshKeyScope()
                    keyStatusLabel.text = message
                }
            }, ModalityState.any())
        }
    }

    private enum class KeyOperation { SAVE, REMOVE, CHECK }

    private fun providerName(provider: AiProvider): String = when (provider) {
        AiProvider.OPENAI -> "OpenAI"
        AiProvider.ANTHROPIC -> "Anthropic Claude"
        AiProvider.LOCAL -> "Local / OpenAI-compatible"
    }

    private fun testExecutable(path: String) {
        val previous = settings.cmExecutable
        settings.cmExecutable = path
        val result = runCatching {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<String, Exception>(
                { PlasticCli.version(Path.of(FileUtil.getTempDirectory())) },
                "Running cm version",
                true,
                null,
            )
        }
        settings.cmExecutable = previous
        result.onSuccess { Messages.showInfoMessage("Plastic SCM client version: $it", "Plastic SCM") }
            .onFailure { Messages.showErrorDialog(it.message ?: it.toString(), "Plastic SCM") }
    }
}
