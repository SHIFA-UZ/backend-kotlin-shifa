package com.shifa.util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

object PatientPhones {
    private val mapper = jacksonObjectMapper()

    fun parseAdditional(json: String?): List<String> {
        val trimmed = json?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            mapper.readValue<List<String>>(trimmed)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeAdditional(phones: List<String>): String? {
        val cleaned = phones.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        return mapper.writeValueAsString(cleaned)
    }

    fun allPhones(primary: String?, additionalJson: String?): List<String> {
        val result = mutableListOf<String>()
        primary?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        parseAdditional(additionalJson).forEach { phone ->
            if (!result.contains(phone)) result.add(phone)
        }
        return result
    }
}
