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
        "andrologist" to "UROLOGY",
        "andrology" to "UROLOGY",
        "gynecologist" to "GYNECOLOGY",
        "gynaecologist" to "GYNECOLOGY",
        "obgyn" to "GYNECOLOGY",
        "endocrinologist" to "ENDOCRINOLOGY",
        "endocrinology" to "ENDOCRINOLOGY",
        "hepatologist" to "HEPATOLOGY",
        "hepatology" to "HEPATOLOGY",
        "proctologist" to "COLORECTAL",
        "colorectal" to "COLORECTAL",
        "hospitalist" to "HOSPITAL_MEDICINE",
        "pain medicine" to "PAIN_MEDICINE",
        "pain specialist" to "PAIN_MEDICINE",
        "radiation oncologist" to "RADIATION_ONCOLOGY",
        "interventional cardiologist" to "INTERVENTIONAL_CARDIOLOGY",
        "interventional radiologist" to "INTERVENTIONAL_RADIOLOGY",
        "narcologist" to "ADDICTION_MEDICINE",
        "addiction medicine" to "ADDICTION_MEDICINE",
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
     * Comma-separated specialty / role terms for speech-to-text prompt biasing.
     * Kept for search/matching; do not inject this list into the STT prompt.
     */
    fun speechBiasSpecialtyTerms(): String =
        aliases.keys.distinct().sorted().joinToString(", ")
}

