package com.shifa.ai

/**
 * Lightweight normalized specialty taxonomy used by patient copilot recommendation ranking.
 * Maps free-text specialty labels (LLM output or user terms) to canonical domains used for matching.
 */
object SpecialtyTaxonomy {
    private val aliases: Map<String, String> = mapOf(
        "cardiologist" to "CARDIOLOGY",
        "cardiology" to "CARDIOLOGY",
        "heart doctor" to "CARDIOLOGY",
        "heart specialist" to "CARDIOLOGY",
        "dermatologist" to "DERMATOLOGY",
        "skin doctor" to "DERMATOLOGY",
        "dermatology" to "DERMATOLOGY",
        "gastroenterologist" to "GASTROENTEROLOGY",
        "gi doctor" to "GASTROENTEROLOGY",
        "gastro" to "GASTROENTEROLOGY",
        "neurologist" to "NEUROLOGY",
        "neurology" to "NEUROLOGY",
        "ent" to "ENT",
        "otolaryngologist" to "ENT",
        "ear nose throat" to "ENT",
        "urologist" to "UROLOGY",
        "urology" to "UROLOGY",
        "gynecologist" to "GYNECOLOGY",
        "gynaecologist" to "GYNECOLOGY",
        "obgyn" to "GYNECOLOGY",
        "endocrinologist" to "ENDOCRINOLOGY",
        "endocrinology" to "ENDOCRINOLOGY",
        "orthopedist" to "ORTHOPEDICS",
        "orthopedic" to "ORTHOPEDICS",
        "orthopaedic" to "ORTHOPEDICS",
        "psychiatrist" to "PSYCHIATRY",
        "psychiatry" to "PSYCHIATRY",
        "dentist" to "DENTISTRY",
        "dental" to "DENTISTRY",
        "stomatologist" to "DENTISTRY",
        "pediatrician" to "PEDIATRICS",
        "paediatrician" to "PEDIATRICS",
        "children doctor" to "PEDIATRICS",
        "general practitioner" to "GENERAL_MEDICINE",
        "gp" to "GENERAL_MEDICINE",
        "family doctor" to "GENERAL_MEDICINE",
        "therapist" to "GENERAL_MEDICINE",
        "internal medicine" to "GENERAL_MEDICINE"
    )

    fun normalize(input: String?): String? {
        val raw = input?.trim()?.lowercase() ?: return null
        if (raw.isBlank()) return null
        aliases[raw]?.let { return it }
        val hit = aliases.entries.firstOrNull { (k, _) -> raw.contains(k) || k.contains(raw) }
        return hit?.value
    }

    /**
     * Comma-separated specialty / role terms for speech-to-text prompt biasing
     * (shared with [MedicalBiasPrompt]).
     */
    fun speechBiasSpecialtyTerms(): String =
        aliases.keys.distinct().sorted().joinToString(", ")
}

