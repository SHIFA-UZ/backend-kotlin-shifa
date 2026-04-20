package com.shifa.web.dto

/**
 * LLM-inferred routing for doctor suggestions.
 *
 * [specialties] are normalized, lowercase medical specialty tokens matching the `profession` field on DoctorProfile
 * as closely as possible (e.g. "cardiologist", "dermatologist", "general practitioner", "pediatrician", "dentist").
 * [searchTerms] are free-text keywords (symptom/condition/body part) that can be used as a fallback full-text search
 * against the doctor catalog when specialty matching does not return candidates.
 * When [hasEnoughInfo] is false the server will NOT return doctors and will surface [clarifyingQuestion] to the
 * client so the copilot can ask one concise follow-up question instead of guessing.
 */
data class PatientCopilotSpecialtyInference(
    val specialties: List<String> = emptyList(),
    val searchTerms: List<String> = emptyList(),
    val hasEnoughInfo: Boolean = false,
    val clarifyingQuestion: String? = null
)
