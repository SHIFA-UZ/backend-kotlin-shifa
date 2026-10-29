package com.shifa.shared

import com.shifa.shared.config.AppProperties
import org.springframework.stereotype.Component

/**
 * Absolute URLs under `app.publicBaseUrl`, the API's own domain (files are served from it; it is also a CORS origin).
 * Stored values are relative paths ("doctors/jane-7.jpg"); older rows and external links are already absolute and
 * pass through unchanged. Replaces a dozen private `normalize*Url` copies.
 */
@Component
class PublicUrls(private val appProperties: AppProperties) {

    fun absolute(pathOrUrl: String?): String? = absolute(appProperties.publicBaseUrl, pathOrUrl)

    companion object {
        fun absolute(base: String, pathOrUrl: String?): String? {
            val trimmed = pathOrUrl?.trim()?.replace("\\", "/") ?: return null
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
            return "${base.removeSuffix("/")}/${trimmed.removePrefix("/")}"
        }
    }
}
