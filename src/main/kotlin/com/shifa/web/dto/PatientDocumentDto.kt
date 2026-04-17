// src/main/kotlin/com/shifa/web/dto/PatientDocumentDto.kt
package com.shifa.web.dto

import java.time.LocalDate

data class PatientDocumentDto(
    val id: Long,
    val title: String,
    val date: LocalDate,
    /** Absolute URL to open the document; null when locked (no access). */
    val url: String?,
    /** Whether the current doctor can open this document (creator or granted). */
    val canView: Boolean,
    /** Label for who created/uploaded: "Doctor", "Patient", or "Unknown". */
    val creatorLabel: String
)
