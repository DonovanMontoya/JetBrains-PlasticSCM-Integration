package com.github.donovanmontoya.riderplastic.settings

import com.github.donovanmontoya.riderplastic.cli.PlasticCli
import com.github.donovanmontoya.riderplastic.toolwindow.PlasticToolWindowFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path

/** Settings | Version Control | Plastic SCM. */
class PlasticConfigurable : BoundConfigurable("Plastic SCM") {

    private val settings = PlasticSettings.getInstance()

    override fun createPanel(): DialogPanel {
        lateinit var pathField: TextFieldWithBrowseButton
        return panel {
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
        }
    }

    override fun apply() {
        super.apply()
        PlasticToolWindowFactory.applyIconToOpenProjects()
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
