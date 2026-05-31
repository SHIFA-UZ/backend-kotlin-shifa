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

data class DevSmsSendResult(val success: Boolean, val smsId: String? = null)

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
        val digits = phone.filter { it.isDigit() }
        return digits.takeIf { it.isNotEmpty() }
    }

    /**
     * Sends SMS via DevSMS API.
     * When not configured, logs and returns failure without throwing.
     */
    fun sendSms(phone: String, message: String): DevSmsSendResult {
        val formatted = formatPhoneForDevSms(phone)
        if (formatted == null) {
            log.warn("DevSMS skip: invalid phone")
            return DevSmsSendResult(success = false)
        }
        if (!isConfigured()) {
            log.warn("DevSMS not configured (DEVSMS_API_TOKEN missing) — would send to {}***", formatted.take(5))
            return DevSmsSendResult(success = false)
        }
        val base = props.baseUrl.trimEnd('/')
        val bodyJson = mapper.writeValueAsString(
            mapOf(
                "phone" to formatted,
                "message" to message,
                "from" to props.senderFrom,
            )
        )
        val request = Request.Builder()
            .url("$base/send_sms.php")
            .addHeader("Authorization", "Bearer ${props.apiToken}")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(jsonMediaType))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    log.warn("DevSMS HTTP {}: {}", response.code, responseBody.take(500))
                    return DevSmsSendResult(success = false)
                }
                val root: JsonNode = mapper.readTree(responseBody)
                val success = root.path("success").asBoolean(false)
                if (success) {
                    val smsIdNode = root.path("data").path("sms_id")
                    val smsId = if (smsIdNode.isMissingNode || smsIdNode.isNull) null
                    else smsIdNode.asText().takeIf { it.isNotBlank() }
                    log.info("DevSMS sent sms_id={} phone={}***", smsId, formatted.take(5))
                    DevSmsSendResult(success = true, smsId = smsId)
                } else {
                    log.warn("DevSMS API error: {}", root.path("message").asText(responseBody.take(200)))
                    DevSmsSendResult(success = false)
                }
            }
        } catch (e: Exception) {
            log.warn("DevSMS send failed: {}", e.message)
            DevSmsSendResult(success = false)
        }
    }
}
