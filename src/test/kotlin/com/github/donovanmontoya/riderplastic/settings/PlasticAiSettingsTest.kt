package com.github.donovanmontoya.riderplastic.settings

import com.github.donovanmontoya.riderplastic.ai.AiClient
import com.github.donovanmontoya.riderplastic.ai.AiCredentialScope
import com.github.donovanmontoya.riderplastic.ai.AiProvider
import com.github.donovanmontoya.riderplastic.ai.AiRequestConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No IDE, PasswordSafe instance, real credentials, or network access is needed by these tests. */
class PlasticAiSettingsTest {
    @Test
    fun `switching providers retains each model and local endpoint`() {
        val settings = PlasticAiSettings(
            provider = AiProvider.OPENAI,
            openAiModel = " openai-model ",
            anthropicModel = " claude-model ",
            localModel = " local-model ",
            localBaseUrl = " http://localhost:11434/v1 ",
        )
        assertEquals(AiRequestConfig(AiProvider.OPENAI, "openai-model", AiClient.OPENAI_BASE_URL), settings.requestConfig())
        assertEquals(
            AiRequestConfig(AiProvider.ANTHROPIC, "claude-model", AiClient.ANTHROPIC_BASE_URL),
            settings.copy(provider = AiProvider.ANTHROPIC).requestConfig(),
        )
        assertEquals(
            AiRequestConfig(AiProvider.LOCAL, "local-model", "http://localhost:11434/v1"),
            settings.copy(provider = AiProvider.LOCAL).requestConfig(),
        )
        assertEquals(settings.requestConfig(), settings.copy(provider = AiProvider.LOCAL).copy(provider = AiProvider.OPENAI).requestConfig())
    }

    @Test
    fun `credential scope ignores model and normalizes case port and trailing slash`() {
        val first = AiRequestConfig(AiProvider.LOCAL, "model-a", "HTTPS://EXAMPLE.COM:443/v1/")
        val second = first.copy(model = "model-b", baseUrl = "https://example.com/v1")
        assertEquals("https://example.com", AiCredentialScope.origin(first))
        assertEquals(AiCredentialScope.key(first), AiCredentialScope.key(second))
    }

    @Test
    fun `keys are distinct by provider and origin`() {
        val hosted = AiRequestConfig(AiProvider.OPENAI, "model", AiClient.OPENAI_BASE_URL)
        val compatible = hosted.copy(provider = AiProvider.LOCAL)
        assertNotEquals(AiCredentialScope.key(hosted), AiCredentialScope.key(compatible))
        val local = compatible.copy(baseUrl = "http://localhost:11434/v1")
        assertNotEquals(AiCredentialScope.key(local), AiCredentialScope.key(local.copy(baseUrl = "http://localhost:1234/v1")))
        assertNotEquals(AiCredentialScope.key(local), AiCredentialScope.key(local.copy(baseUrl = "http://127.0.0.1:11434/v1")))
        assertNotEquals(AiCredentialScope.key(local), AiCredentialScope.key(local.copy(baseUrl = "https://localhost:11434/v1")))
    }

    @Test
    fun `IPv6 origin retains brackets and explicit port`() {
        val config = AiRequestConfig(AiProvider.LOCAL, "model", "http://[::1]:1234/v1/")
        assertEquals("local:http://[::1]:1234", AiCredentialScope.key(config))
    }

    @Test
    fun `unsafe URLs cannot become credential identities`() {
        listOf(
            "https://user:password@example.com/v1",
            "https://example.com/v1?key=not-a-real-key",
            "https://example.com/v1#not-a-real-key",
            "http://example.com/v1",
            "not a url",
        ).forEach { url ->
            val failure = runCatching { AiCredentialScope.key(AiRequestConfig(AiProvider.LOCAL, "model", url)) }.exceptionOrNull()
            assertTrue("Expected endpoint rejection", failure != null)
            assertFalse(failure!!.message.orEmpty().contains(url))
            assertFalse(failure.message.orEmpty().contains("not-a-real-key"))
        }
    }

    @Test
    fun `pre 253 IDEs always use session storage including local desktops`() {
        assertTrue(AiCredentialScope.requiresSessionStorage(251))
        assertTrue(AiCredentialScope.requiresSessionStorage(252))
        assertFalse(AiCredentialScope.requiresSessionStorage(253))
        assertFalse(AiCredentialScope.requiresSessionStorage(261))
    }
}
