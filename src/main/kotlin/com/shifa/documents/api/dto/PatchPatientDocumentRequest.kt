package com.shifa.documents.api.dto

/** `PATCH /api/patients/{patientId}/documents/{documentId}` (BE-21): only the fields sent change. */
data class PatchPatientDocumentRequest(
    val title: String? = null,
    /** A [com.shifa.documents.domain.PatientDocumentCategory] code (e.g. `BLOOD_TEST`); 400 when unknown. */
    val category: String? = null,
)
