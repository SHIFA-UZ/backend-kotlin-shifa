package com.shifa.service

import com.shifa.ai.PatientAiContext
import com.shifa.ai.PatientCopilotPromptBuilder
import com.shifa.ai.RedFlagEngine
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.ai.MedicalPromptBuilder
import com.shifa.ai.OutputLanguage
import com.shifa.config.OpenAiProperties
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.fasterxml.jackson.databind.JsonNode
import com.shifa.web.AiStreamException
import com.shifa.web.dto.AiMessageDto
import com.shifa.web.dto.PatientBookingIntentResolution
import com.shifa.web.dto.PatientCopilotSpecialtyInference
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import com.shifa.service.PatientVisitAskResult

@Service
class OpenAiResponsesService(
    private val props: OpenAiProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val rateLimiter = SimpleRateLimiter(props.maxRequestsPerMinute)

    private val client = OkHttpClient.Builder()
		.connectTimeout(Duration.ofSeconds(10))
		.writeTimeout(Duration.ofSeconds(10))
		.readTimeout(Duration.ZERO) // 🔴 REQUIRED for SSE (infinite stream)
		.build()

    /** Client for non-streaming completion (e.g. briefing) with finite read timeout. */
    private val completionClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .writeTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(90))
        .build()


    // Verify OpenAI key injection once at startup (no key material logged)
    @PostConstruct
    fun debugOpenAiKey() {
        log.info(
            "OpenAI key configured: present={}, length={}",
            props.apiKey.isNotBlank(),
            props.apiKey.length
        )
    }

    /**
     * SSE streaming doctor assistant.
     * Deltas are appended and emitted exactly as received from OpenAI; no token-level spacing logic.
     */
    fun streamDoctorAssistant(
        doctor: DoctorProfile,
        patientContext: PatientAiContext?,
        messages: List<AiMessageDto>,
        language: OutputLanguage
    ): Flow<String> = flow {
        val combinedInput = buildString {
            if (patientContext != null) {
                append(patientContext.toString())
                append(" ")
            }
            append(messages.joinToString(" ") { it.content })
        }


		val redFlagResult = RedFlagEngine.analyze(combinedInput)
		if (redFlagResult.hasEmergency) {
			throw AiStreamException(
				code = "SAFETY_BLOCK",
				message = "This may represent a medical emergency. I cannot provide medical advice for this situation. Please seek immediate professional medical care or contact emergency services."
			)
		}

		if (!rateLimiter.tryAcquire()) {
			throw AiStreamException(
				code = "RATE_LIMIT",
				message = "AI rate limit exceeded. Please try again later."
			)
		}
		

// 🧠 Optional patient-aware context (read-only, abstracted)
// 🔐 Build system messages (hard guardrails first)
val systemMessages = mutableListOf<Map<String, String>>(
    mapOf(
        "role" to "system",
        "content" to MedicalPromptBuilder.systemPrompt(doctor, language)
    )
)

// 🧠 Optional patient-aware context (read-only, abstracted)
patientContext?.let { ctx ->
    systemMessages += mapOf(
        "role" to "system",
        "content" to MedicalPromptBuilder.patientContextPrompt(ctx)
    )
}

// 📦 Final payload for Chat Completions API
val payload = mapper.writeValueAsString(
    mapOf(
        "model" to props.model,
        "stream" to true,
        "messages" to systemMessages + messages.map { mapOf("role" to it.role, "content" to it.content) }
    )
)

val request = Request.Builder()
    .url("https://api.openai.com/v1/chat/completions")
    .addHeader("Authorization", "Bearer ${props.apiKey}")
    .addHeader("OpenAI-Project", props.projectId) // ✅ REQUIRED FOR sk-proj keys
    .addHeader("Content-Type", "application/json")
    .addHeader("Accept", "text/event-stream")
    .post(payload.toRequestBody("application/json".toMediaType()))
    .build()


        client.newCall(request).execute().use { response ->

            log.info("OpenAI SSE status={}", response.code)

            if (!response.isSuccessful) {
                throw AiStreamException(
                    code = "AI_UNAVAILABLE",
                    message = "AI is temporarily unavailable. Please try again later."
                )
            }

            val source = response.body?.source() ?: return@use
            val buffer = StringBuilder()

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue

                // Do not trim SSE frame payloads; preserve all token spacing.
                val data = line.removePrefix("data:")
                if (data.isBlank() || data.trim() == "[DONE]") continue

                try {
                    val json = mapper.readTree(data)
                    val delta = json.path("choices")
                        .path(0)
                        .path("delta")
                        .path("content")
                        .asText(null)

                    if (delta != null && delta.isNotEmpty()) {
                        buffer.append(delta)
                        emit(delta)
                    }
                } catch (e: Exception) {
                    log.debug("Skipping SSE frame: {}", e.message)
                }
            }
        }
    }

    /**
     * SSE streaming patient co-pilot (Shifa AI in the patient app). Text-only replies; same token streaming as doctor assistant.
     */
    fun streamPatientCopilot(
        patient: PatientProfile,
        messages: List<AiMessageDto>,
        language: OutputLanguage
    ): Flow<String> = flow {
        val recordContext = PatientCopilotPromptBuilder.patientRecordContextPrompt(patient)
        val combinedInput = buildString {
            append(recordContext)
            append(" ")
            append(messages.joinToString(" ") { it.content })
        }

        val redFlagResult = RedFlagEngine.analyze(combinedInput)
        if (redFlagResult.hasEmergency) {
            throw AiStreamException(
                code = "SAFETY_BLOCK",
                message = "This may represent a medical emergency. I cannot provide medical advice for this situation. Please seek immediate professional medical care or contact emergency services."
            )
        }

        if (!rateLimiter.tryAcquire()) {
            throw AiStreamException(
                code = "RATE_LIMIT",
                message = "AI rate limit exceeded. Please try again later."
            )
        }

        val systemMessages = mutableListOf(
            mapOf(
                "role" to "system",
                "content" to PatientCopilotPromptBuilder.patientCopilotSystemPrompt(language)
            ),
            mapOf(
                "role" to "system",
                "content" to recordContext
            )
        )

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to true,
                "messages" to systemMessages + messages.map { mapOf("role" to it.role, "content" to it.content) }
            )
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->

            log.info("OpenAI patient copilot SSE status={}", response.code)

            if (!response.isSuccessful) {
                throw AiStreamException(
                    code = "AI_UNAVAILABLE",
                    message = "AI is temporarily unavailable. Please try again later."
                )
            }

            val source = response.body?.source() ?: return@use

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue

                val data = line.removePrefix("data:")
                if (data.isBlank() || data.trim() == "[DONE]") continue

                try {
                    val json = mapper.readTree(data)
                    val delta = json.path("choices")
                        .path(0)
                        .path("delta")
                        .path("content")
                        .asText(null)

                    if (delta != null && delta.isNotEmpty()) {
                        emit(delta)
                    }
                } catch (e: Exception) {
                    log.debug("Skipping SSE frame: {}", e.message)
                }
            }
        }
    }

    /**
     * Non-streaming JSON extraction: whether the patient clearly asked to auto-book with consent,
     * doctor, preferred time (interpreted in [patientTimeZone]), and video vs onsite.
     */
    fun resolvePatientBookingIntent(
        messages: List<AiMessageDto>,
        language: OutputLanguage,
        patientTimeZone: String?,
        allowedDoctorIds: List<Long>?
    ): PatientBookingIntentResolution {
        val combined = messages.joinToString(" ") { it.content }
        if (RedFlagEngine.analyze(combined).hasEmergency) {
            return PatientBookingIntentResolution()
        }
        if (!rateLimiter.tryAcquire()) {
            return PatientBookingIntentResolution()
        }

        val tz = patientTimeZone?.trim()?.takeIf { it.isNotBlank() } ?: "UTC"
        val allowedLine = if (!allowedDoctorIds.isNullOrEmpty()) {
            "doctorId MUST be one of these numeric ids only: ${allowedDoctorIds.joinToString(", ")}. If none match the patient's chosen doctor, return doctorId null and bookNow false."
        } else {
            "Return doctorId only if the transcript clearly and uniquely identifies one doctor; otherwise null and bookNow false."
        }

        val systemPrompt = """
You extract structured booking intent from a Shifa patient AI chat. The user message is a JSON string whose key "messages" is an array of {role, content} in order.

Return ONLY a JSON object with exactly these keys:
bookNow (boolean), doctorId (number or null), preferredStartAtUtc (string ISO-8601 instant in UTC with Z suffix, or null), isVideo (boolean or null), userExplicitConsentToAutoBook (boolean).

STRICT RULES:
1. userExplicitConsentToAutoBook true ONLY if a message with role "user" contains clear authorization to automatically book on their behalf (e.g. "yes book it for me", "please schedule that", "go ahead and book", "confirm auto-booking"). A vague "ok" or "yes" to a general question is NOT sufficient.
2. bookNow true ONLY if userExplicitConsentToAutoBook is true AND doctorId is non-null AND preferredStartAtUtc is non-null AND isVideo is non-null AND they refer to the same booking request.
3. $allowedLine
4. Interpret the patient's stated date and time in IANA time zone "$tz", then set preferredStartAtUtc to the correct UTC instant. If the patient only said a date, assume a reasonable time they mentioned or midday local only if they implied "any time"; otherwise prefer null and bookNow false.
5. Use the MOST RECENT user booking details in the transcript. If older and newer date/time or doctor details conflict, use the latest explicit user instruction and ignore older turns.
6. preferredStartAtUtc MUST be in the future relative to now; if the transcript points to a past time/date or unclear year/date, set preferredStartAtUtc null and bookNow false.
7. Never infer doctorId from assistant suggestions alone. Only set doctorId if the user explicitly picked a doctor or there is exactly one unambiguous doctor choice in user messages.
8. isVideo: true for video/online/remote; false for clinic/in-person/onsite.
9. Use assistant messages only as context; consent must be inferred from user messages.
10. If anything is ambiguous or multiple doctors, return bookNow false.

Language hint for understanding user text: ${language.name}
""".trimIndent()

        val transcript = mapper.writeValueAsString(
            mapOf("messages" to messages.map { mapOf("role" to it.role, "content" to it.content) })
        )

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "temperature" to 0.1,
                "max_tokens" to 450,
                "response_format" to mapOf("type" to "json_object"),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to transcript)
                )
            )
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        completionClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log.warn("OpenAI booking intent resolution failed: {}", response.code)
                return PatientBookingIntentResolution()
            }
            val body = response.body?.string() ?: return PatientBookingIntentResolution()
            val tree = mapper.readTree(body)
            val content = tree.path("choices").path(0).path("message").path("content").asText("").trim()
            if (content.isBlank()) return PatientBookingIntentResolution()
            return parseBookingIntentJson(content)
        }
    }

    /**
     * Infer which medical specialty (or specialties) best match the patient's chat so far and whether the copilot
     * already has enough clinical context to recommend doctors. When info is insufficient, returns a single concise
     * clarifying question in the patient's language so the copilot can ask for the missing piece.
     */
    fun inferSpecialtiesAndClarification(
        messages: List<AiMessageDto>,
        language: OutputLanguage
    ): PatientCopilotSpecialtyInference {
        val combined = messages.joinToString(" ") { it.content }
        if (combined.isBlank()) return PatientCopilotSpecialtyInference()
        if (RedFlagEngine.analyze(combined).hasEmergency) return PatientCopilotSpecialtyInference()
        if (!rateLimiter.tryAcquire()) return PatientCopilotSpecialtyInference()

        val systemPrompt = """
You help Shifa route a patient to the right medical specialist based on their chat so far.
You receive a JSON string whose key "messages" is an ordered array of {role, content}.

Return ONLY a JSON object with exactly these keys:
specialties (array of short lowercase strings), searchTerms (array of short lowercase strings),
hasEnoughInfo (boolean), clarifyingQuestion (string or null).

RULES:
1. specialties: 1 to 3 medical specialty labels that would treat the patient's likely issue, as short English lowercase nouns that match a doctor's profession field (e.g. "cardiologist", "dermatologist", "gastroenterologist", "pediatrician", "neurologist", "urologist", "gynecologist", "otolaryngologist", "endocrinologist", "orthopedist", "psychiatrist", "general practitioner", "dentist"). If multiple specialties are plausible, put the most likely first.
2. searchTerms: 2-6 short lowercase keywords (symptom name, affected organ, condition) that could be used as a fallback text search if specialty matching fails (e.g. ["chest pain", "shortness of breath"]). Use English clinical terms even if the patient wrote in another language.
3. hasEnoughInfo: true ONLY if the patient has described at least a concrete symptom or body system AND you are confident which specialty to suggest. If the chat is still generic (e.g. "I feel bad", "need a doctor") set false.
4. clarifyingQuestion: when hasEnoughInfo is false, return ONE short friendly question in ${language.name} (${language.isoCode}) asking for the single most useful missing piece (symptom detail, duration/severity, location on body, preferred date/time, or onsite vs video). When hasEnoughInfo is true, set clarifyingQuestion to null.
5. Never invent specialties; pick from common medical specialties only. Prefer "general practitioner" when the issue is vague but clearly medical.
6. Output must be valid JSON only; no prose, no Markdown.
""".trimIndent()

        val transcript = mapper.writeValueAsString(
            mapOf("messages" to messages.map { mapOf("role" to it.role, "content" to it.content) })
        )

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "temperature" to 0.1,
                "max_tokens" to 350,
                "response_format" to mapOf("type" to "json_object"),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to transcript)
                )
            )
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            completionClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("OpenAI specialty inference failed: {}", response.code)
                    return PatientCopilotSpecialtyInference()
                }
                val body = response.body?.string() ?: return PatientCopilotSpecialtyInference()
                val tree = mapper.readTree(body)
                val content = tree.path("choices").path(0).path("message").path("content").asText("").trim()
                if (content.isBlank()) return PatientCopilotSpecialtyInference()
                val n = mapper.readTree(content)
                val specialties = n.path("specialties")
                    .takeIf { it.isArray }
                    ?.mapNotNull { it.asText("").trim().lowercase().ifBlank { null } }
                    ?.distinct()
                    ?: emptyList()
                val searchTerms = n.path("searchTerms")
                    .takeIf { it.isArray }
                    ?.mapNotNull { it.asText("").trim().lowercase().ifBlank { null } }
                    ?.distinct()
                    ?: emptyList()
                val hasEnough = jsonBool(n.path("hasEnoughInfo"))
                val clarify = n.path("clarifyingQuestion").asText("").trim().ifBlank { null }
                PatientCopilotSpecialtyInference(
                    specialties = specialties,
                    searchTerms = searchTerms,
                    hasEnoughInfo = hasEnough,
                    clarifyingQuestion = if (hasEnough) null else clarify
                )
            }
        } catch (e: Exception) {
            log.debug("Specialty inference error: {}", e.message)
            PatientCopilotSpecialtyInference()
        }
    }

    private fun parseBookingIntentJson(content: String): PatientBookingIntentResolution {
        return try {
            val n = mapper.readTree(content)
            PatientBookingIntentResolution(
                bookNow = jsonBool(n.path("bookNow")),
                doctorId = jsonLong(n.path("doctorId")),
                preferredStartAtUtc = n.path("preferredStartAtUtc").asText("").trim().ifBlank { null },
                isVideo = jsonBoolOrNull(n.path("isVideo")),
                userExplicitConsentToAutoBook = jsonBool(n.path("userExplicitConsentToAutoBook"))
            )
        } catch (e: Exception) {
            log.debug("Failed to parse booking intent JSON: {}", e.message)
            PatientBookingIntentResolution()
        }
    }

    private fun jsonBool(node: JsonNode): Boolean = when {
        node.isMissingNode || node.isNull -> false
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> node.asLong() != 0L
        node.isTextual -> node.asText().equals("true", ignoreCase = true)
        else -> false
    }

    private fun jsonLong(node: JsonNode): Long? = when {
        node.isMissingNode || node.isNull -> null
        node.isIntegralNumber -> node.asLong()
        node.isFloatingPointNumber -> node.asDouble().toLong()
        node.isTextual -> node.asText().trim().toLongOrNull()
        else -> null
    }

    private fun jsonBoolOrNull(node: JsonNode): Boolean? = when {
        node.isMissingNode || node.isNull -> null
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> node.asLong() != 0L
        node.isTextual -> when (node.asText().trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    /**
     * Single completion for patient briefing. Context may contain multilingual document text.
     * @param documentContext Patient demographics + document titles and extracted text (may be Uzbek, Russian, English, etc.)
     * @param outputLanguage Preferred language for the briefing: "en", "uz", "ru", etc.
     */
    fun completeBriefing(documentContext: String, outputLanguage: String): String {
        if (!rateLimiter.tryAcquire()) {
            throw AiStreamException(
                code = "RATE_LIMIT",
                message = "AI rate limit exceeded. Please try again later."
            )
        }
        val systemPrompt = """
You are a medical assistant preparing a concise clinical briefing for a doctor.

RULES:
- Use ONLY the information provided below: (1) appointment history with this doctor (date, reason, status, location), and (2) document content. The documents may be in multiple languages (e.g. Uzbek, Russian, English). Read and summarize in whatever language it is written; you may produce the briefing in the requested output language or keep key terms in the original language where appropriate.
- Output language requested: $outputLanguage. Write the briefing in this language when possible, but preserve important clinical terms and findings from the sources.
- Summarize key information: appointment history (reasons, dates), and from documents: findings, diagnoses, medications, procedures, and other relevant clinical details. Be concise (under 400 words).
- Do NOT diagnose, prescribe, or give medical advice. Only summarize what is provided.
- If a document is empty or unreadable, skip it. Do not invent content.
- IMPORTANT: Use plain text only. Do NOT use Markdown formatting (no **bold**, no bullet markers like '-', no numbered lists with '1.', no headings with '#'). Write paragraphs and short lists as simple sentences separated by newlines.
""".trimIndent()
        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "max_tokens" to 800,
                "temperature" to 0.3,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to "Appointment history and document content:\n\n$documentContext")
                )
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        completionClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log.warn("OpenAI briefing completion failed: {}", response.code)
                throw AiStreamException(
                    code = "AI_UNAVAILABLE",
                    message = "AI is temporarily unavailable. Please try again later."
                )
            }
            val body = response.body?.string() ?: throw AiStreamException("AI_UNAVAILABLE", "Empty response")
            val json = mapper.readTree(body)
            val content = json.path("choices").path(0).path("message").path("content").asText("")
            return content.trim().ifEmpty { "No summary could be generated." }
        }
    }

    /**
     * Extract diagnosis *terms* from clinical text. MUST NOT return ICD codes.
     * Output is a small list of short terms/phrases, one per line.
     *
     * This is used as a first step for ICD-10 mapping against our local catalog.
     */
    fun extractDiagnosisTerms(text: String, outputLanguage: String = "en"): List<String> {
        if (!rateLimiter.tryAcquire()) {
            throw AiStreamException(
                code = "RATE_LIMIT",
                message = "AI rate limit exceeded. Please try again later."
            )
        }
        val systemPrompt = """
You are a medical assistant helping a doctor code diagnoses.

TASK:
- Read the clinical text and extract the most likely *specific* clinical diagnosis terms ONLY (short phrases).
- Return a list of terms, one per line.

STRICT RULES:
- DO NOT output any ICD-10 codes.
- DO NOT output numbering like "1." and DO NOT use Markdown bullets like "-" or "*".
- Return at most 5 lines.
- Output language: $outputLanguage (but keep clinical terms as written if they are Russian/Uzbek/English).
- If no diagnosis terms are present, return an empty response.

QUALITY RULES:
- Prefer billable/clinically specific diagnoses over vague symptoms.
- Avoid vague terms like: infection, pain, inflammation, fever.
- Prefer: acute bronchitis, type 2 diabetes mellitus, dental caries, pulpitis, periodontitis, etc.
- If only symptoms are present, return the most specific *syndrome/condition* implied, otherwise return empty.
""".trimIndent()

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "max_tokens" to 120,
                "temperature" to 0.0,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to text.take(12_000))
                )
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        completionClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log.warn("OpenAI diagnosis-term extraction failed: {}", response.code)
                throw AiStreamException(
                    code = "AI_UNAVAILABLE",
                    message = "AI is temporarily unavailable. Please try again later."
                )
            }
            val body = response.body?.string() ?: return emptyList()
            val json = mapper.readTree(body)
            val content = json.path("choices").path(0).path("message").path("content").asText("").trim()
            if (content.isBlank()) return emptyList()

            return content
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { line ->
                    // remove common accidental prefixes (still don't allow codes)
                    line.removePrefix("•").removePrefix("-").removePrefix("*").trim()
                }
                .take(5)
        }
    }

    /**
     * Generate patient-facing structured visit summary JSON from doctor-approved note/context.
     * Returns raw JSON string.
     */
    fun generatePatientVisitSummary(
        sourceContext: String,
        outputLanguage: String
    ): String {
        if (!rateLimiter.tryAcquire()) {
            throw AiStreamException(
                code = "RATE_LIMIT",
                message = "AI rate limit exceeded. Please try again later."
            )
        }
        val systemPrompt = """
You are a medical assistant creating a patient-friendly after-visit summary.

OUTPUT REQUIREMENTS:
- Return ONLY valid JSON.
- Use this exact top-level object schema:
  {
    "summaryPlain": string,
    "carePlan": [string],
    "medicationGuidance": [{"name": string, "instructions": string, "missedDose": string}],
    "redFlags": [{"sign": string, "urgency": "emergency"|"urgent"|"routine"}],
    "nextSteps": [string],
    "disclaimer": string
  }
- Output language: $outputLanguage.

SAFETY RULES:
- Use ONLY facts from provided context. Do not invent diagnoses, medicines, doses, or dates.
- If a section has no data, return an empty array for that section.
- Keep patient language simple and concise.
- Do not provide new treatment advice beyond documented plan.
""".trimIndent()

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "max_tokens" to 1200,
                "temperature" to 0.2,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to sourceContext)
                )
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        completionClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log.warn("OpenAI visit-summary generation failed: {}", response.code)
                throw AiStreamException(
                    code = "AI_UNAVAILABLE",
                    message = "AI is temporarily unavailable. Please try again later."
                )
            }
            val body = response.body?.string() ?: throw AiStreamException("AI_UNAVAILABLE", "Empty response")
            val json = mapper.readTree(body)
            val content = json.path("choices").path(0).path("message").path("content").asText("").trim()
            return content
        }
    }

    /**
     * Answer a patient follow-up question using generated visit summary JSON as source of truth.
     */
    fun answerPatientVisitQuestion(
        summaryJson: String,
        question: String,
        outputLanguage: String
    ): PatientVisitAskResult {
        if (!rateLimiter.tryAcquire()) {
            throw AiStreamException(
                code = "RATE_LIMIT",
                message = "AI rate limit exceeded. Please try again later."
            )
        }
        val systemPrompt = """
You answer patient follow-up questions based ONLY on the provided visit summary JSON.

RULES:
- Output language: $outputLanguage.
- Do NOT add new diagnosis or treatment.
- If the answer is not present in summary, say that it is not specified and advise contacting doctor.
- Keep answer concise and plain language.
- Return ONLY valid JSON with this schema:
  {"answer": string, "citations": [string]}
- citations should reference summary sections by keys (e.g. "summaryPlain", "carePlan[0]", "medicationGuidance[1]").
""".trimIndent()

        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "max_tokens" to 350,
                "temperature" to 0.2,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to "Summary JSON:\n$summaryJson\n\nQuestion:\n$question")
                )
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${props.apiKey}")
            .addHeader("OpenAI-Project", props.projectId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        completionClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AiStreamException(
                    code = "AI_UNAVAILABLE",
                    message = "AI is temporarily unavailable. Please try again later."
                )
            }
            val body = response.body?.string() ?: return PatientVisitAskResult("", emptyList())
            val json = mapper.readTree(body)
            val content = json.path("choices").path(0).path("message").path("content").asText("").trim()
            if (content.isBlank()) return PatientVisitAskResult("", emptyList())
            return try {
                val parsed = mapper.readTree(content)
                val answer = parsed.path("answer").asText("").trim()
                val citations = parsed.path("citations")
                    .takeIf { it.isArray }
                    ?.mapNotNull { n -> n.asText("").trim().ifEmpty { null } }
                    ?: emptyList()
                PatientVisitAskResult(answer = answer, citations = citations)
            } catch (_: Exception) {
                // Backward compatibility if model returns plain text.
                PatientVisitAskResult(answer = content, citations = emptyList())
            }
        }
    }
}
