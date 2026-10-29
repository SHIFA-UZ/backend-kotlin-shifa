package com.shifa.ai.domain

/**
 * Example-style prompt for OpenAI audio transcription (matches spoken clinic dialogue).
 * Do not put glossary / specialty lists here — Whisper/gpt-4o-transcribe will echo them
 * into the transcript as if they were spoken.
 */
object MedicalBiasPrompt {

    fun build(languageHintIso639OrNull: String?): String {
        val sample = when (languageHintIso639OrNull?.lowercase()) {
            "ru" ->
                "Здравствуйте, доктор. Беспокоит боль в животе справа после жирной пищи и головокружение. Какие лекарства принимаете сейчас? Приём окончен."
            "en" ->
                "Hello doctor. I have abdominal pain on the right after fatty food, and dizziness. What medicines are you taking now? The visit is finished."
            else ->
                "Assalomu alaykum, doktor. Qorin og'rig'i o'ng tarafda, yog'liq ovqatdan keyin, bosh aylanishi. Hozirda qanday dori olyapsiz? Qabul tugadi."
        }
        return sample.trim()
    }
}
