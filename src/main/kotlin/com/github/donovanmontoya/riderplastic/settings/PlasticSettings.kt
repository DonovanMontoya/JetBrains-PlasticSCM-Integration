package com.github.donovanmontoya.riderplastic.settings

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import java.io.File

class PlasticSettingsState : BaseState() {
    /** Explicit path to the `cm` executable. Empty means auto-detect. */
    var cmExecutable by string("")
}

@Service(Service.Level.APP)
@State(name = "RiderPlasticSettings", storages = [Storage("riderPlastic.xml")])
class PlasticSettings : SimplePersistentStateComponent<PlasticSettingsState>(PlasticSettingsState()) {

    var cmExecutable: String
        get() = state.cmExecutable.orEmpty()
        set(value) {
            state.cmExecutable = value.trim()
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
