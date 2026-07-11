package com.shifa.clinicalengine.service

object ClinicalSynthesisTemplateBuilder {

    fun build(field: String, label: String, locale: String, variables: List<String>): String {
        val normalized = label
            .replace("[X]", "{X}", ignoreCase = true)
            .replace("[x]", "{X}")
            .replace("[drug]", "{drug}", ignoreCase = true)
            .replace("[tooth]", "{tooth}", ignoreCase = true)

        if (field == "xray" && (normalized.contains("деструкц") || normalized.contains("destruc") || normalized.contains("nuqson"))) {
            return when (locale) {
                "uz" -> "Rentgenologik tekshiruvda ildiz uchining proyeksiyasida {X} mm hajmli suyak destruksiyasi maydoni aniqlangan."
                "en" -> "Radiographic examination revealed a {X} mm area of bone destruction in the projection of the root apex."
                else -> "При рентгенологическом исследовании в проекции верхушки корня выявлен очаг деструкции костной ткани размером {X} мм."
            }
        }

        return when (field) {
            "complaints" -> complaintsTemplate(normalized, locale)
            "morbi" -> morbiTemplate(normalized, locale)
            "objective" -> objectiveTemplate(normalized, locale)
            "oral_cavity" -> oralCavityTemplate(normalized, locale)
            "xray" -> xrayTemplate(normalized, locale)
            "treatment_1" -> treatmentTemplate(normalized, locale, stage = 1)
            "treatment_2" -> treatmentTemplate(normalized, locale, stage = 2)
            "recommendations" -> recommendationsTemplate(normalized, locale)
            "occlusion" -> occlusionTemplate(normalized, locale)
            else -> ensureSentence(normalized, locale)
        }
    }

    fun renderTemplate(template: String, variables: Map<String, String>): String {
        var out = template
        for ((key, value) in variables) {
            out = out.replace("{$key}", value.trim())
        }
        return out.replace(Regex("\\{[A-Za-z0-9_]+\\}"), "").trim()
    }

    private fun complaintsTemplate(text: String, locale: String): String = when (locale) {
        "uz" -> when {
            isAsymptomatic(text) -> "Ko'rik vaqtida shikoyatlar yo'q."
            else -> "Bemor shikoyat qiladi: $text."
        }
        "en" -> when {
            isAsymptomatic(text) -> "No complaints at the time of examination."
            else -> "Patient reports: $text."
        }
        else -> when {
            isAsymptomatic(text) -> "Жалоб на момент осмотра не предъявляет."
            else -> "Предъявляет жалобы: $text."
        }
    }

    private fun morbiTemplate(text: String, locale: String): String = when (locale) {
        "uz" -> "Kasallik rivoji: $text."
        "en" -> "History of present illness: $text."
        else -> "Anamnesis morbi: $text."
    }

    private fun objectiveTemplate(text: String, locale: String): String = when (locale) {
        "uz" -> "Ob'ektiv ko'rikda: $text."
        "en" -> "On objective examination: $text."
        else -> "При объективном осмотре: $text."
    }

    private fun oralCavityTemplate(text: String, locale: String): String = when (locale) {
        "uz" -> "Og'iz bo'shlig'i holati: $text."
        "en" -> "Oral cavity findings: $text."
        else -> "Состояние полости рта: $text."
    }

    private fun xrayTemplate(text: String, locale: String): String = when (locale) {
        "uz" -> "Rentgenologik tekshiruvda $text."
        "en" -> "Radiographic examination revealed $text."
        else -> "При рентгенологическом исследовании $text."
    }

    private fun treatmentTemplate(text: String, locale: String, stage: Int): String = when (locale) {
        "uz" -> if (stage == 1) "Birinchi bosqich davolash: $text." else "Davolash natijasi: $text."
        "en" -> if (stage == 1) "Treatment provided: $text." else "Treatment outcome: $text."
        else -> if (stage == 1) "Проведено лечение: $text." else "Результат лечения: $text."
    }

    private fun recommendationsTemplate(text: String, locale: String): String = when (locale) {
        "uz" -> "Tavsiya etiladi: $text."
        "en" -> "Recommendations: $text."
        else -> "Рекомендовано: $text."
    }

    private fun occlusionTemplate(text: String, locale: String): String = when (locale) {
        "uz" -> "Tishlarning ishlashi (prikus): $text."
        "en" -> "Occlusion: $text."
        else -> "Прикус: $text."
    }

    private fun ensureSentence(text: String, locale: String): String {
        val trimmed = text.trim()
        if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) return trimmed
        return "$trimmed."
    }

    private fun isAsymptomatic(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("отсутств") ||
            lower.contains("asymptomatic") ||
            lower.contains("mavjud emas") ||
            lower.contains("yo'q")
    }
}
