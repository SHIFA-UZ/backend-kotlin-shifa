package com.shifa.ai.llm

import com.shifa.ai.OutputLanguage

/**
 * Deterministic language signal for provider routing only.
 * The reply language stays whatever the request's [OutputLanguage] and prompt rules decide.
 *
 * Short inputs (under 3 words) fall back to the requested language so a bare patient name,
 * abbreviation, or borrowed medical term never changes the route.
 */
object RouteLanguageDetector {
    private val uzCyrillicLetters = setOf('ў', 'қ', 'ғ', 'ҳ', 'Ў', 'Қ', 'Ғ', 'Ҳ')
    private val uzLatinMarkers = listOf(
        "oʻ", "o'", "o`", "gʻ", "g'", "g`", "sh", "ch", "ng",
    )
    private val uzWords = setOf(
        "bugun", "ertaga", "kecha", "bemor", "qabul", "uchrashuv", "vaqt", "soat", "bo'sh", "bosh",
        "haftada", "hafta", "kun", "kunlar", "nechta", "qancha", "kim", "keyingi", "menga", "mening",
        "bilan", "uchun", "yozib", "yozing", "ko'rsat", "korsat", "ochib", "oching", "bekor", "qil",
        "ayting", "ayt", "bormi", "bor", "yo'q", "yoq", "salom", "rahmat", "iltimos", "shifa",
        "dushanba", "seshanba", "chorshanba", "payshanba", "juma", "shanba", "yakshanba",
    )
    private val ruWords = setOf(
        "сегодня", "завтра", "вчера", "пациент", "пациента", "приём", "прием", "запись", "записать",
        "свободные", "свободное", "время", "сколько", "кто", "следующий", "покажи", "открой", "отмени",
        "перенеси", "на", "в", "с", "для", "пожалуйста", "спасибо", "привет",
    )

    fun detect(text: String, fallback: OutputLanguage): OutputLanguage {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return fallback
        val words = trimmed.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size < 3) return fallback

        val letters = trimmed.filter { it.isLetter() }
        if (letters.isEmpty()) return fallback
        val cyrillic = letters.count { it in '\u0400'..'\u04FF' }
        val latin = letters.count { it in 'a'..'z' || it in 'A'..'Z' }
        val lower = trimmed.lowercase()
        val tokens = lower.split(Regex("""[^\p{L}'ʻ`]+""")).filter { it.isNotBlank() }

        if (cyrillic > latin) {
            if (letters.any { it in uzCyrillicLetters }) return OutputLanguage.UZ
            val uzHits = tokens.count { it in uzWords }
            val ruHits = tokens.count { it in ruWords }
            return if (uzHits > ruHits && uzHits >= 1) OutputLanguage.UZ else OutputLanguage.RU
        }
        if (latin > cyrillic) {
            val uzHits = tokens.count { it in uzWords } + uzLatinMarkers.count { m -> m.length > 2 && lower.contains(m) }
            val apostropheHits = uzLatinMarkers.filter { it.length == 2 }.count { lower.contains(it) }
            if (uzHits >= 2 || (uzHits >= 1 && apostropheHits >= 1)) return OutputLanguage.UZ
            return OutputLanguage.EN
        }
        return fallback
    }
}
