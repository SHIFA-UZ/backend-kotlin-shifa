package com.shifa.ai.memory

import com.shifa.ai.llm.AiRoutingProperties
import com.shifa.ai.llm.LlmClient
import com.shifa.ai.llm.LlmProviderConfig
import com.shifa.ai.llm.LlmProviderRegistry
import com.shifa.ai.llm.LlmRequest
import com.shifa.ai.llm.LlmResponse
import com.shifa.ai.llm.LlmUsageRecorder
import com.shifa.config.OpenAiProperties
import com.shifa.domain.DoctorProfile
import com.shifa.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate

class IntelligenceMemoryExtractorTest {
    private val props = AiRoutingProperties()
    private val registry = LlmProviderRegistry(props, OpenAiProperties().apply { apiKey = "sk"; projectId = ""; model = "m" }).also { it.init() }
    private val memoryService = mock(IntelligenceMemoryService::class.java)
    private val usage = LlmUsageRecorder(mock(JdbcTemplate::class.java), props)
    private val doctor = DoctorProfile(id = 3, user = User(id = 1, passwordHash = "x"), firstName = "A", lastName = "B")

    private fun extractorReturning(json: String, captured: MutableList<LlmRequest> = ArrayList()): IntelligenceMemoryExtractor {
        val client = object : LlmClient {
            override fun complete(request: LlmRequest, provider: LlmProviderConfig): LlmResponse {
                captured += request
                return LlmResponse(json, emptyList(), "stop", 5, 5, provider.id, provider.chatModel, 3)
            }
        }
        return IntelligenceMemoryExtractor(client, registry, usage, props, memoryService)
    }

    @Test
    fun `trigger words gate extraction`() {
        val e = extractorReturning("{}")
        assertTrue(e.shouldExtract("From now on use 24-hour time please"))
        assertTrue(e.shouldExtract("Запомни, я предпочитаю короткие ответы"))
        assertTrue(e.shouldExtract("Bundan keyin doim qisqa javob ber"))
        assertFalse(e.shouldExtract("What is my schedule today?"))
        assertFalse(e.shouldExtract("ok"))
    }

    @Test
    fun `parses memories array and ignores junk`() {
        val e = extractorReturning("{}")
        val out = e.parseCandidates(
            """{"memories":[
                {"type":"DOCTOR_PREFERENCE","content":"Prefers 24-hour time.","key":"time_format","confidence":0.92,"temporary":false},
                {"type":"NOT_A_TYPE","content":"x","confidence":0.9},
                {"type":"TERMINOLOGY","content":"","confidence":0.9},
                {"type":"TEMPORARY_CONTEXT","content":"Only urgent visits this hour.","confidence":0.8,"temporary":true}
            ]}""",
        )
        assertEquals(2, out.size)
        assertEquals("time_format", out[0].contentKey)
        assertTrue(out[1].temporary)
        assertTrue(e.parseCandidates("not json").isEmpty())
        assertTrue(e.parseCandidates("").isEmpty())
    }

    @Test
    fun `extraction sends no tools, uses json mode, and forwards patient names as blocked`() {
        val captured = ArrayList<LlmRequest>()
        val e = extractorReturning("""{"memories":[{"type":"DOCTOR_PREFERENCE","content":"Prefers 24-hour time.","key":"time_format","confidence":0.9}]}""", captured)
        `when`(memoryService.persistCandidates(eq(doctor) ?: doctor, anyCands(), eq("req1"), eq(listOf("Botir Muzrabov")) ?: emptyList())).thenReturn(listOf(1L))

        val ids = e.extract(doctor, "From now on use 24-hour time", "Noted.", listOf("list_free_slots"), listOf("Botir Muzrabov"), "req1")
        assertEquals(listOf(1L), ids)
        val req = captured.single()
        assertTrue(req.tools.isNullOrEmpty())
        assertTrue(req.jsonObject)
        assertFalse(req.messages.joinToString().contains("Muzrabov"))
    }

    @Test
    fun `missing extraction provider skips quietly`() {
        props.memory.extractionProvider = "nope"
        val e = extractorReturning("{}")
        assertTrue(e.extract(doctor, "remember this", "", emptyList(), emptyList(), "r").isEmpty())
        verify(memoryService, never()).persistCandidates(anyDoctor(), anyCands(), any(), anyNames())
        props.memory.extractionProvider = "openai"
    }

    private fun anyDoctor(): DoctorProfile = any(DoctorProfile::class.java) ?: doctor
    private fun anyCands(): List<IntelligenceMemoryCandidate> = any<List<IntelligenceMemoryCandidate>>() ?: emptyList()
    private fun anyNames(): Collection<String> = any<Collection<String>>() ?: emptyList()
}
