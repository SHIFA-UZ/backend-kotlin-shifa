package com.shifa.admin.api.dto

/** `GET /api/public/legal`: the links to show (and accept) in the apps; [updatedAt] is `yyyy-MM-dd`. */
data class LegalDocumentsDto(
    val terms: String?,
    val privacy: String?,
    val licences: String?,
    val version: String?,
    val updatedAt: String?,
)
