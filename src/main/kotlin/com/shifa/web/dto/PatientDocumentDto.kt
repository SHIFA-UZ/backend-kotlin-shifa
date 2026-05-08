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
    val creatorLabel: String,
    /**
     * Optional uploader-chosen category code (e.g. "MRI", "BLOOD_TEST",
     * "FORM_025_2"). Null when the document was uploaded before categories
     * were introduced or no category was chosen.
     */
    val category: String? = null,
    /**
     * When true the document is visible to every doctor of the patient
     * (no access request required). Mirrors the backend persisted flag and
     * the canonical visibility rule.
     */
    val isSharedWithTeam: Boolean = false
)
