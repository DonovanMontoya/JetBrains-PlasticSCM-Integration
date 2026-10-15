package com.github.donovanmontoya.riderplastic.ai

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import java.net.URI
import java.util.Locale

/** Pure credential identity logic. The provider and origin, never the model, identify a key. */
object AiCredentialScope {
    fun origin(config: AiRequestConfig): String {
        val uri = URI(AiClient.normalizeBaseUrl(config.provider, config.baseUrl))
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
        return "$scheme://$host${port?.let { ":$it" }.orEmpty()}"
    }

    fun key(config: AiRequestConfig): String = "${config.provider.name.lowercase(Locale.ROOT)}:${origin(config)}"

    /** Before 2025.3, remote-development PasswordSafe could store credentials as plaintext. */
    fun requiresSessionStorage(baselineVersion: Int): Boolean = baselineVersion < 253
}

/**
 * Plugin-owned keys only; never consult another plugin's PasswordSafe namespace.
 * All calls touching PasswordSafe must run on a background thread.
 */
object AiCredentials {
    val sessionOnly: Boolean
        get() = AiCredentialScope.requiresSessionStorage(ApplicationInfo.getInstance().build.baselineVersion)

    fun read(config: AiRequestConfig): String? = withPasswordSafe {
        PasswordSafe.instance.getPassword(attributes(config))
    }

    /** Caller must clear its char array after this call. Blank input never removes a key. */
    fun save(config: AiRequestConfig, apiKey: CharArray) {
        require(apiKey.isNotEmpty() && apiKey.any { !it.isWhitespace() }) { "Enter a key before saving." }
        require(apiKey.size <= 4096 && apiKey.all { it.code in 33..126 }) { "The API key format is invalid." }
        withPasswordSafe {
            // PasswordSafe may defer storage, so it must own a copy that the caller will not clear.
            // The public memoryOnly overload is also available in the 252 baseline.
            PasswordSafe.instance.set(attributes(config), Credentials(null, apiKey.copyOf()), memoryOnly = sessionOnly)
        }
    }

    fun remove(config: AiRequestConfig) {
        withPasswordSafe {
            PasswordSafe.instance.set(attributes(config), null, memoryOnly = sessionOnly)
        }
    }

    private fun attributes(config: AiRequestConfig) = CredentialAttributes(
        generateServiceName("Plastic SCM Integration AI", AiCredentialScope.key(config)),
    )

    private inline fun <T> withPasswordSafe(action: () -> T): T {
        check(!ApplicationManager.getApplication().isDispatchThread) { "API keys must be accessed on a background thread." }
        return try {
            action()
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: Exception) {
            // Never forward a storage exception/cause, which could contain credential data.
            throw IllegalStateException("Could not access the Plastic SCM API key. Check the IDE password storage settings and try again.")
        }
    }
}
