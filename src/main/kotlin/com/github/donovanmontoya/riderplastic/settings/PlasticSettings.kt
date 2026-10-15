package com.github.donovanmontoya.riderplastic.settings

import com.github.donovanmontoya.riderplastic.ai.AiClient
import com.github.donovanmontoya.riderplastic.ai.AiProvider
import com.github.donovanmontoya.riderplastic.ai.AiRequestConfig
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

    // Non-secret metadata only. API keys belong exclusively in AiCredentials/PasswordSafe.
    var aiProvider by string(AiProvider.OPENAI.name)
    var openAiModel by string("")
    var anthropicModel by string("")
    var localModel by string("")
    var localBaseUrl by string(AiClient.DEFAULT_LOCAL_BASE_URL)
}

/** A non-secret snapshot. Switching providers preserves each provider's model. */
data class PlasticAiSettings(
    val provider: AiProvider = AiProvider.OPENAI,
    val openAiModel: String = "",
    val anthropicModel: String = "",
    val localModel: String = "",
    val localBaseUrl: String = AiClient.DEFAULT_LOCAL_BASE_URL,
) {
    fun requestConfig(): AiRequestConfig = when (provider) {
        AiProvider.OPENAI -> AiRequestConfig(provider, openAiModel.trim(), AiClient.OPENAI_BASE_URL)
        AiProvider.ANTHROPIC -> AiRequestConfig(provider, anthropicModel.trim(), AiClient.ANTHROPIC_BASE_URL)
        AiProvider.LOCAL -> AiRequestConfig(provider, localModel.trim(), localBaseUrl.trim())
    }
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

    @get:Synchronized
    @set:Synchronized
    var aiSettings: PlasticAiSettings
        get() = PlasticAiSettings(
            provider = AiProvider.entries.firstOrNull { it.name == state.aiProvider } ?: AiProvider.OPENAI,
            openAiModel = state.openAiModel.orEmpty(),
            anthropicModel = state.anthropicModel.orEmpty(),
            localModel = state.localModel.orEmpty(),
            localBaseUrl = state.localBaseUrl.orEmpty().ifBlank { AiClient.DEFAULT_LOCAL_BASE_URL },
        )
        set(value) {
            // Validate before any mutation so a URL containing a key cannot reach riderPlastic.xml.
            val localUrl = AiClient.normalizeBaseUrl(AiProvider.LOCAL, value.localBaseUrl)
            state.aiProvider = value.provider.name
            state.openAiModel = value.openAiModel.trim()
            state.anthropicModel = value.anthropicModel.trim()
            state.localModel = value.localModel.trim()
            state.localBaseUrl = localUrl
        }

    /** Immutable snapshot for background generation. No credential is stored in settings. */
    fun aiConfig(): AiRequestConfig = aiSettings.requestConfig()

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
