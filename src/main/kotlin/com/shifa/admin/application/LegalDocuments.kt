package com.shifa.admin.application

import com.shifa.shared.config.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The legal documents' links (`legal.*` rows of `system_config`, edited in the admin panel) for `GET /api/public/legal`.
 * An empty value is "not set": a per-language URL (`legal.terms_url.uz`) falls back to the default one.
 */
@Service
class LegalDocuments(private val configs: SystemConfigs) {

    data class Links(
        val terms: String?,
        val privacy: String?,
        val licences: String?,
        val version: String?,
        val updatedAt: String?,
    )

    /** [lang] is `en`, `uz` or `ru`. */
    @Cacheable(CacheConfig.PUBLIC_LEGAL_CACHE)
    @Transactional(readOnly = true)
    fun links(lang: String): Links {
        val values = configs.findAll()
            .filter { it.key.startsWith(PREFIX) }
            .associate { it.key.removePrefix(PREFIX) to it.value.trim().takeIf(String::isNotEmpty) }
        fun localized(name: String) = values["$name.$lang"] ?: values[name]
        return Links(
            terms = localized("terms_url"),
            privacy = localized("privacy_url"),
            licences = localized("licences_url"),
            version = values["version"],
            updatedAt = values["updated_at"],
        )
    }

    private companion object {
        const val PREFIX = "legal."
    }
}
