package com.shifa.ai

object RedFlagDetector {

    private val RED_FLAG_KEYWORDS = listOf(
        // Cardiac / stroke
        "chest pain",
        "pressure in chest",
        "shortness of breath",
        "stroke",
        "slurred speech",
        "one-sided weakness",

        // Bleeding / trauma
        "severe bleeding",
        "uncontrolled bleeding",
        "vomiting blood",
        "blood in stool",

        // Pregnancy
        "pregnant",
        "pregnancy bleeding",
        "severe abdominal pain pregnancy",

        // Neurological
        "loss of consciousness",
        "seizure",
        "sudden confusion",

        // Children
        "child",
        "infant",
        "baby",
        "newborn",

        // General danger
        "suicidal",
        "self harm"
    )

    fun hasRedFlags(text: String): Boolean {
        val lower = text.lowercase()
        return RED_FLAG_KEYWORDS.any { lower.contains(it) }
    }
}
