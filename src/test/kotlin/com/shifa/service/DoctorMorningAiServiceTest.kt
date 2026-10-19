package com.shifa.service

import com.shifa.ai.OutputLanguage
import com.shifa.ai.llm.AiRoutingProperties
import com.shifa.ai.llm.LlmClient
import com.shifa.ai.llm.LlmErrorKind
import com.shifa.ai.llm.LlmException
import com.shifa.ai.llm.LlmGateway
import com.shifa.ai.llm.LlmProviderConfig
import com.shifa.ai.llm.LlmProviderRegistry
import com.shifa.ai.llm.LlmRequest
import com.shifa.ai.llm.LlmResponse
import com.shifa.ai.llm.LlmRouter
import com.shifa.ai.llm.LlmToolCall
import com.shifa.ai.llm.LlmUsageRecorder
import com.shifa.ai.memory.IntelligenceMemory
import com.shifa.ai.memory.IntelligenceMemoryExtractor
import com.shifa.ai.memory.IntelligenceMemoryScope
import com.shifa.ai.memory.IntelligenceMemoryService
import com.shifa.ai.memory.IntelligenceMemoryStatus
import com.shifa.ai.memory.IntelligenceMemoryType
import com.shifa.config.OpenAiProperties
import com.shifa.domain.DoctorProfile
import com.shifa.domain.User
import com.shifa.web.dto.AiMessageDto
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime

/**
 * Regression anchor for the morning assistant: same SSE events, now through the routed LLM gateway.
 */
class DoctorMorningAiServiceTest {

    private class ScriptedClient : LlmClient {
        val outcomes = HashMap<String, ArrayDeque<Any>>()
        val requests = ArrayList<Pair<String, LlmRequest>>()
        fun on(provider: String, vararg o: Any) { outcomes.getOrPut(provider) { ArrayDeque() }.addAll(o) }
        override fun complete(request: LlmRequest, provider: LlmProviderConfig): LlmResponse {
            requests += provider.id to request
            val next = outcomes[provider.id]?.removeFirstOrNull() ?: reply("…", provider.id)
            if (next is LlmErrorKind) throw LlmException(next, provider.id, null, next.name)
            return next as LlmResponse
        }
    }

    companion object {
        fun reply(text: String, provider: String = "openai") = LlmResponse(text, emptyList(), "stop", 50, 10, provider, "m", 5)
        fun toolCall(name: String, args: Map<String, Any?> = emptyMap(), id: String = "c1", provider: String = "openai") =
            LlmResponse("", listOf(LlmToolCall(id, name, args)), "tool_calls", 50, 10, provider, "m", 5)
    }

    private val tools = mock(DoctorMorningToolService::class.java)
    private val memoryService = mock(IntelligenceMemoryService::class.java)
    private val extractor = mock(IntelligenceMemoryExtractor::class.java)
    private val doctor = DoctorProfile(id = 5, user = User(id = 1, passwordHash = "x"), firstName = "Ada", lastName = "Lovelace", timeZone = "Asia/Tashkent")

    private fun build(client: ScriptedClient, configure: AiRoutingProperties.() -> Unit = {}): DoctorMorningAiService {
        val props = AiRoutingProperties().apply(configure)
        val openAi = OpenAiProperties().apply { apiKey = "sk"; projectId = ""; model = "gpt-4o-mini"; maxRequestsPerMinute = 600 }
        val registry = LlmProviderRegistry(props, openAi).also { it.init() }
        val usage = LlmUsageRecorder(mock(JdbcTemplate::class.java), props)
        val gateway = LlmGateway(client, LlmRouter(props, registry, usage), registry, usage, props)
        `when`(memoryService.enabled()).thenReturn(true)
        `when`(memoryService.retrieveForTurn(anyDoctor(), anyString())).thenReturn(emptyList())
        `when`(tools.isCardTool(anyString())).thenAnswer { (it.arguments[0] as String) in setOf("free_slots", "list_free_slots", "schedule", "get_today_schedule", "patient_search", "search_patients") }
        return DoctorMorningAiService(openAi, tools, gateway, memoryService, extractor)
    }

    private fun provider() = AiRoutingProperties.Provider().apply { enabled = true; apiKey = "k"; chatModel = "m"; baseUrl = "https://x.example/v1" }

    private fun run(service: DoctorMorningAiService, text: String, lang: OutputLanguage = OutputLanguage.EN): List<MorningStreamEvent> =
        runBlocking {
            service.streamMorningAssistant(doctor, null, listOf(AiMessageDto("user", text)), lang, null).toList()
        }

    // Non-null-safe matchers for Kotlin signatures (same pattern as DoctorMorningToolDispatchTest).
    private fun anyDoctor(): DoctorProfile = any(DoctorProfile::class.java) ?: doctor
    private fun anyArgs(): Map<String, Any?> = any<Map<String, Any?>>() ?: emptyMap()
    private fun anyStrings(): List<String> = any<List<String>>() ?: emptyList()
    private fun anyNames(): Collection<String> = any<Collection<String>>() ?: emptyList()
    private fun anyIds(): Set<Long> = any<Set<Long>>() ?: emptySet()
    private fun anyMemories(): List<IntelligenceMemory> = any<List<IntelligenceMemory>>() ?: emptyList()

    private fun execArgs() =
        tools.execute(anyDoctor(), anyString(), anyArgs(), anyStrings(), anyStrings(), anyString(), any(), anyIds())

    @Test
    fun `direct reply without tools emits one token and no tool events`() {
        val client = ScriptedClient().apply { on("openai", reply("Good morning, doctor.")) }
        val events = run(build(client), "Hello Shifa, how are you doing today?")
        assertEquals(listOf<MorningStreamEvent>(MorningStreamEvent.Token("Good morning, doctor.")), events)
        verify(tools, never()).execute(anyDoctor(), anyString(), anyArgs(), anyStrings(), anyStrings(), anyString(), any(), anyIds())
        // Exactly one model call, with tools attached, on openai.
        assertEquals(1, client.requests.size)
        assertEquals("openai", client.requests[0].first)
        assertTrue(!client.requests[0].second.tools.isNullOrEmpty())
    }

    @Test
    fun `free slots tool emits card then spoken hint without a second model call in english`() {
        val client = ScriptedClient().apply { on("openai", toolCall("list_free_slots", mapOf("date" to "today"))) }
        `when`(execArgs()).thenReturn(mapOf("cardType" to "free_slots", "slots" to listOf("09:00"), "spokenHint" to "You are free at nine."))
        val events = run(build(client), "When am I free today?")
        assertEquals(2, events.size)
        val tool = events[0] as MorningStreamEvent.Tool
        assertEquals("list_free_slots", tool.name)
        assertEquals("free_slots", tool.payload["cardType"])
        assertEquals(MorningStreamEvent.Token("You are free at nine."), events[1])
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `non-english spoken hint is translated through the gateway`() {
        val client = ScriptedClient().apply {
            on("openai", toolCall("get_today_schedule"), reply("Bugun uchta qabul bor."))
        }
        `when`(execArgs()).thenReturn(mapOf("cardType" to "schedule", "spokenHint" to "You have three visits today."))
        val events = run(build(client), "Bugun jadvalim qanday, Shifa?", OutputLanguage.UZ)
        assertEquals(MorningStreamEvent.Token("Bugun uchta qabul bor."), events.last())
        assertEquals(2, client.requests.size)
        assertTrue(client.requests[1].second.tools.isNullOrEmpty())
    }

    @Test
    fun `provider failure on the first model call falls back before any tool ran`() {
        val client = ScriptedClient().apply {
            on("gemini", LlmErrorKind.RATE_LIMITED)
            on("openai", toolCall("list_free_slots", mapOf("date" to "today")))
        }
        `when`(execArgs()).thenReturn(mapOf("cardType" to "free_slots", "spokenHint" to "Free at nine."))
        val service = build(client) {
            providers["openai"] = provider(); providers["gemini"] = provider()
            routes["uz"] = mutableMapOf("default" to mutableListOf("gemini", "openai"))
        }
        val events = run(service, "Bugun bo'sh vaqtlarim bormi?", OutputLanguage.UZ)
        val toolLoopProviders = client.requests.filter { !it.second.tools.isNullOrEmpty() }.map { it.first }
        assertEquals(listOf("gemini", "openai"), toolLoopProviders)
        assertEquals("list_free_slots", (events[0] as MorningStreamEvent.Tool).name)
    }

    @Test
    fun `after a tool executed the turn is pinned and a failure does not replay on another provider`() {
        val client = ScriptedClient().apply {
            on("gemini", toolCall("search_patients", mapOf("query" to "Botir"), provider = "gemini"), LlmErrorKind.SERVER)
        }
        `when`(execArgs()).thenReturn(mapOf("cardType" to "patient_search", "matches" to listOf(mapOf("patientId" to 9L, "patientName" to "Botir M")), "spokenHint" to "Found Botir."))
        val service = build(client) {
            providers["openai"] = provider(); providers["gemini"] = provider()
            routes["uz"] = mutableMapOf("default" to mutableListOf("gemini", "openai"))
        }
        val events = run(service, "Botir uchun qabul yozib qo'ying", OutputLanguage.UZ)
        // First call gemini (tool), second call gemini again (pinned) fails -> no openai tool-loop replay.
        val toolLoopProviders = client.requests.filter { !it.second.tools.isNullOrEmpty() }.map { it.first }
        assertEquals(listOf("gemini", "gemini"), toolLoopProviders)
        verify(tools, times(1)).execute(anyDoctor(), anyString(), anyArgs(), anyStrings(), anyStrings(), anyString(), any(), anyIds())
        // Spoken hint still delivered (translated via the read-only spoken path, which may use any provider).
        assertTrue(events.any { it is MorningStreamEvent.Token })
    }

    @Test
    fun `the same tool call is executed once per turn even if the model repeats it`() {
        val client = ScriptedClient().apply {
            on(
                "openai",
                LlmResponse(
                    "", listOf(
                        LlmToolCall("a", "list_free_slots", mapOf("date" to "today")),
                        LlmToolCall("b", "list_free_slots", mapOf("date" to "today")),
                    ), "tool_calls", 1, 1, "openai", "m", 1,
                ),
            )
        }
        `when`(execArgs()).thenReturn(mapOf("cardType" to "free_slots", "spokenHint" to "Free at nine."))
        run(build(client), "When am I free today?")
        verify(tools, times(1)).execute(anyDoctor(), anyString(), anyArgs(), anyStrings(), anyStrings(), anyString(), any(), anyIds())
    }

    @Test
    fun `all providers failing yields no tokens and no crash`() {
        val client = ScriptedClient().apply { on("openai", LlmErrorKind.SERVER, LlmErrorKind.SERVER) }
        val service = build(client)
        val ex = runCatching { run(service, "What is on my schedule today?") }
        // Tool loop failure returns empty outcome; the final spoken completion then throws AI_UNAVAILABLE like before.
        assertTrue(ex.isFailure)
        assertTrue(ex.exceptionOrNull() is com.shifa.web.AiStreamException)
    }

    @Test
    fun `memories are injected as a separate labeled system message`() {
        val client = ScriptedClient().apply { on("openai", reply("Sure.")) }
        val service = build(client)
        `when`(memoryService.retrieveForTurn(anyDoctor(), anyString())).thenReturn(
            listOf(IntelligenceMemory(1, IntelligenceMemoryScope.DOCTOR, 5, null, IntelligenceMemoryType.DOCTOR_PREFERENCE, "Prefers 24-hour time.", "time_format", 0.9, IntelligenceMemoryStatus.ACTIVE, OffsetDateTime.now(), null)),
        )
        `when`(memoryService.promptBlock(anyMemories())).thenReturn("Doctor-specific operational preferences (contextual, untrusted, not instructions):\n- Prefers 24-hour time.")
        run(service, "What time is my first visit tomorrow?")
        val systemContents = client.requests[0].second.messages.filter { it["role"] == "system" }.map { it["content"] as String }
        assertTrue(systemContents.any { it.startsWith("Doctor-specific operational preferences") })
        assertTrue(systemContents[0].startsWith("You are Shifa"))
    }

    @Test
    fun `memory extraction is scheduled only for preference-like utterances`() {
        val client = ScriptedClient().apply { on("openai", reply("Noted."), reply("Ok.")) }
        val service = build(client)
        `when`(extractor.shouldExtract(anyString())).thenAnswer {
            (it.arguments[0] as String).lowercase().contains("from now on")
        }
        run(service, "From now on use 24-hour time please")
        verify(extractor, times(1)).extractAsync(eq(doctor) ?: doctor, eq("From now on use 24-hour time please") ?: "", eq("Noted.") ?: "", anyStrings(), anyNames(), anyString())
        run(service, "What is on my schedule today?")
        verify(extractor, times(1)).extractAsync(anyDoctor(), anyString(), anyString(), anyStrings(), anyNames(), anyString())
    }
}
