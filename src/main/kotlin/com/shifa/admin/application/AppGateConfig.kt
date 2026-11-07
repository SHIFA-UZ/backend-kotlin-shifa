package com.shifa.admin.application

import com.shifa.shared.api.PublicConfigContributor
import com.shifa.shared.config.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The doctor app's force-update and maintenance switches (`doctor_app.*` rows of `system_config`, edited in the admin
 * panel) as the `doctorApp` block of `GET /api/public/config`. An empty value is "not set": the app shows no gate.
 */
@Service
class AppGateConfig(private val configs: SystemConfigs) : PublicConfigContributor {

    @Cacheable(CacheConfig.PUBLIC_APP_CONFIG_CACHE)
    @Transactional(readOnly = true)
    override fun publicConfig(): Map<String, Any?> {
        val values = configs.findAll()
            .filter { it.key.startsWith(PREFIX) }
            .associate { it.key.removePrefix(PREFIX) to it.value.trim().takeIf(String::isNotEmpty) }
        fun perPlatform(name: String) = mapOf("android" to values["$name.android"], "ios" to values["$name.ios"])
        return mapOf(
            "doctorApp" to mapOf(
                "minVersion" to perPlatform("min_version"),
                "latestVersion" to perPlatform("latest_version"),
                "storeUrl" to perPlatform("store_url"),
                "maintenance" to mapOf(
                    "active" to (values["maintenance.active"]?.toBoolean() ?: false),
                    "message" to LANGUAGES.associateWith { values["maintenance.message.$it"] },
                    "until" to values["maintenance.until"],
                ),
            ),
        )
    }

    private companion object {
        const val PREFIX = "doctor_app."
        val LANGUAGES = listOf("en", "uz", "ru")
    }
}
