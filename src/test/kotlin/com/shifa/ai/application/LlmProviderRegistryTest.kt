package com.shifa.ai.application

import com.shifa.ai.infrastructure.LlmProviderConfig

import com.shifa.shared.config.OpenAiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LlmProviderRegistryTest {
    private fun openAiProps() = OpenAiProperties().apply {
        apiKey = "sk-legacy"
        projectId = "p"
        model = "gpt-4o-mini"
    }

    private fun provider(enabled: Boolean, key: String, model: String = "m", url: String = "https://x.example/v1") =
        AiRoutingProperties.Provider().apply {
            this.enabled = enabled
            this.apiKey = key
            this.chatModel = model
            this.baseUrl = url
        }

    @Test
    fun `no ai providers configured synthesizes openai from legacy properties`() {
        val reg = LlmProviderRegistry(AiRoutingProperties(), openAiProps())
        reg.init()
        val p = reg.get("openai")
        assertNotNull(p)
        assertEquals("sk-legacy", p!!.apiKey)
        assertEquals("gpt-4o-mini", p.chatModel)
        assertEquals("https://api.openai.com/v1", p.baseUrl)
        assertEquals(listOf("openai"), reg.eligibleIds())
    }

    @Test
    fun `optional providers without keys or disabled are excluded`() {
        val props = AiRoutingProperties().apply {
            providers["openai"] = provider(true, "sk", "gpt-4o-mini", "https://api.openai.com/v1")
            providers["gemini"] = provider(true, "", "gemini-2.5-flash")
            providers["grok"] = provider(false, "xai-key", "grok-3")
            providers["broken"] = provider(true, "k", "m", "not a url")
            providers["nomodel"] = provider(true, "k", "")
        }
        val reg = LlmProviderRegistry(props, openAiProps())
        reg.init()
        assertEquals(listOf("openai"), reg.eligibleIds())
        assertNull(reg.get("gemini"))
        assertNull(reg.get("grok"))
        assertNull(reg.get("broken"))
    }

    @Test
    fun `enabled and keyed optional providers become eligible`() {
        val props = AiRoutingProperties().apply {
            providers["openai"] = provider(true, "sk", "gpt-4o-mini", "https://api.openai.com/v1")
            providers["gemini"] = provider(true, "g-key", "gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta/openai")
        }
        val reg = LlmProviderRegistry(props, openAiProps())
        reg.init()
        assertEquals(listOf("openai", "gemini"), reg.eligibleIds())
    }

    @Test
    fun `cooldown expires with the clock`() {
        var now = Instant.parse("2026-10-19T08:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val props = AiRoutingProperties().apply { routing.cooldownMs = 10_000 }
        val reg = LlmProviderRegistry(props, openAiProps(), clock)
        reg.init()
        assertFalse(reg.isCoolingDown("openai"))
        reg.markCooldown("openai")
        assertTrue(reg.isCoolingDown("openai"))
        now = now.plusSeconds(11)
        assertFalse(reg.isCoolingDown("openai"))
    }

    @Test
    fun `provider toString never prints the key`() {
        val p = provider(true, "sk-very-secret")
        assertFalse(p.toString().contains("sk-very-secret"))
        val cfg = LlmProviderConfig(id = "a", baseUrl = "https://x/v1", apiKey = "sk-very-secret", chatModel = "m")
        assertFalse(cfg.toString().contains("sk-very-secret"))
    }
}
