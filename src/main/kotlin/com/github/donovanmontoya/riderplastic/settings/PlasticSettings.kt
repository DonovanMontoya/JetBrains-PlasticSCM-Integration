package com.github.donovanmontoya.riderplastic.settings

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.SystemInfo
import java.io.File
import javax.swing.Icon

class PlasticSettingsState : BaseState() {
    /** Explicit path to the `cm` executable. Empty means auto-detect. */
    var cmExecutable by string("")

    /** Name of a [PlasticToolWindowIcon] constant. */
    var toolWindowIcon by string(PlasticToolWindowIcon.LANES.name)
}

/** Icon choices for the Plastic SCM tool window stripe button. */
enum class PlasticToolWindowIcon(val displayName: String, private val path: String) {
    LANES("Branch Explorer lanes", "/icons/plasticLanes.svg"),
    CM("cm monogram", "/icons/plasticCm.svg"),
    ;

    /** `_dark.svg` variants are picked up automatically by the icon loader. */
    val icon: Icon by lazy { IconLoader.getIcon(path, PlasticToolWindowIcon::class.java) }
}

@Service(Service.Level.APP)
@State(name = "RiderPlasticSettings", storages = [Storage("riderPlastic.xml")])
class PlasticSettings : SimplePersistentStateComponent<PlasticSettingsState>(PlasticSettingsState()) {

    var cmExecutable: String
        get() = state.cmExecutable.orEmpty()
        set(value) {
            state.cmExecutable = value.trim()
        }

    var toolWindowIcon: PlasticToolWindowIcon
        get() = PlasticToolWindowIcon.entries.firstOrNull { it.name == state.toolWindowIcon } ?: PlasticToolWindowIcon.LANES
        set(value) {
            state.toolWindowIcon = value.name
        }

    /** The executable that will actually be launched. */
    fun resolveCmExecutable(): String = cmExecutable.ifBlank { detectCmExecutable() ?: "cm" }

    companion object {
        fun getInstance(): PlasticSettings = service()

        private val WELL_KNOWN_LOCATIONS = listOf(
            "C:\\Program Files\\PlasticSCM5\\client\\cm.exe",
            "C:\\Program Files (x86)\\PlasticSCM5\\client\\cm.exe",
            "/usr/local/bin/cm",
            "/opt/plasticscm5/client/cm",
            "/Applications/PlasticSCM.app/Contents/MonoBundle/cm",
            "/Applications/PlasticSCM.app/Contents/MacOS/cm",
        )

        fun detectCmExecutable(): String? {
            val name = if (SystemInfo.isWindows) "cm.exe" else "cm"
            PathEnvironmentVariableUtil.findInPath(name)?.let { return it.absolutePath }
            return WELL_KNOWN_LOCATIONS.firstOrNull { File(it).canExecute() }
        }
    }
}
