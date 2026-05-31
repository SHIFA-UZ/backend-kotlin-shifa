package com.shifa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.config.DevSmsProperties
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

data class DevSmsSendResult(
    val success: Boolean,
    val smsId: String? = null,
    val errorMessage: String? = null,
)

@Service
class DevSmsService(
    private val props: DevSmsProperties,
) {
    private val log = LoggerFactory.getLogger(DevSmsService::class.java)
    private val mapper = jacksonObjectMapper()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .writeTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(30))
        .build()

    fun isConfigured(): Boolean = props.apiToken.isNotBlank()

    /** DevSMS expects digits only, e.g. 998901234567. */
    fun formatPhoneForDevSms(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        var digits = phone.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        // Local UZ mobile without country code (9 digits starting with 9).
        if (digits.length == 9 && digits.startsWith('9')) {
            digits = "998$digits"
        }
        return digits.takeIf { it.length >= 10 }
    }

    /**
     * Sends SMS via DevSMS API.
     * When not configured, logs and returns failure without throwing.
     */
    fun sendSms(phone: String, message: String): DevSmsSendResult {
        val formatted = formatPhoneForDevSms(phone)
        if (formatted == null) {
            val msg = "Invalid phone number"
            log.warn("DevSMS skip: {}", msg)
            return DevSmsSendResult(success = false, errorMessage = msg)
        }
        if (message.isBlank()) {
            val msg = "Message is empty"
            log.warn("DevSMS skip: {}", msg)
            return DevSmsSendResult(success = false, errorMessage = msg)
        }
        if (!isConfigured()) {
            log.warn("DevSMS not configured (DEVSMS_API_TOKEN missing) — would send to {}***", formatted.take(5))
            return DevSmsSendResult(success = false, errorMessage = "SMS service not configured")
        }
        val payload = buildMap {
            put("phone", formatted)
            put("message", message)
            val sender = props.senderFrom.trim()
            if (sender.isNotEmpty()) {
                put("from", sender)
            }
        }
        val bodyJson = mapper.writeValueAsString(payload)
        val base = props.baseUrl.trimEnd('/')
        val request = Request.Builder()
            .url("$base/send_sms.php")
            .addHeader("Authorization", "Bearer ${props.apiToken}")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(jsonMediaType))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val parsed = parseDevSmsResponse(responseBody)
                if (!response.isSuccessful) {
                    val detail = parsed.errorMessage ?: responseBody.trim().ifBlank { "HTTP ${response.code}" }
                    log.warn(
                        "DevSMS HTTP {} for phone={}*** messageLen={} from={}: {}",
                        response.code,
                        formatted.take(5),
                        message.length,
                        props.senderFrom.trim().ifBlank { "(default)" },
                        detail,
                    )
                    return DevSmsSendResult(success = false, errorMessage = detail)
                }
                if (parsed.success) {
                    log.info("DevSMS sent sms_id={} phone={}***", parsed.smsId, formatted.take(5))
                    DevSmsSendResult(success = true, smsId = parsed.smsId)
                } else {
                    val detail = parsed.errorMessage ?: "DevSMS rejected the request"
                    log.warn("DevSMS API error for phone={}***: {}", formatted.take(5), detail)
                    DevSmsSendResult(success = false, errorMessage = detail)
                }
            }
        } catch (e: Exception) {
            log.warn("DevSMS send failed: {}", e.message)
            DevSmsSendResult(success = false, errorMessage = e.message ?: "Network error")
        }
    }

    internal data class ParsedDevSmsResponse(
        val success: Boolean,
        val smsId: String? = null,
        val errorMessage: String? = null,
    )

    internal fun parseDevSmsResponse(responseBody: String): ParsedDevSmsResponse {
        val trimmed = responseBody.trim()
        if (trimmed.isEmpty()) {
            return ParsedDevSmsResponse(success = false, errorMessage = "Empty response from DevSMS")
        }
        return try {
            val root: JsonNode = mapper.readTree(trimmed)
            val success = root.path("success").asBoolean(false)
            if (success) {
                val smsIdNode = root.path("data").path("sms_id")
                val smsId = if (smsIdNode.isMissingNode || smsIdNode.isNull) null
                else smsIdNode.asText().takeIf { it.isNotBlank() }
                ParsedDevSmsResponse(success = true, smsId = smsId)
            } else {
                ParsedDevSmsResponse(success = false, errorMessage = extractDevSmsError(root, trimmed))
            }
        } catch (e: Exception) {
            ParsedDevSmsResponse(success = false, errorMessage = trimmed.take(300))
        }
    }

    private fun extractDevSmsError(root: JsonNode, fallback: String): String {
        listOf("error", "message", "detail").forEach { field ->
            val text = root.path(field).asText("").trim()
            if (text.isNotEmpty()) return text
        }
        return fallback.take(300)
    }
}
