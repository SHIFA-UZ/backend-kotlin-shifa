package com.shifa.ai

/**
 * Dense prompt biasing for clinical speech-to-text (Uzbek / Russian / English code-switching).
 * Used with OpenAI Audio Transcriptions API `prompt` field.
 */
object MedicalBiasPrompt {

    private const val SHARED_CLINICAL = """
Clinical conversation, telemedicine consultation, doctor and patient.
UZ Latin: shifokor, bemor, qabul, konsultatsiya, simptom, og'riq, bosh aylanishi, bosim holati, isitma, harorat,
qon bosimi, shamollash, yo'tal, allergiya, diabet, yurak, oshqozon, ichak, jig'ar, buyrak,
bronxit, pnevmoniya, nevrologiya, nevropatolog, kardiolog, terapevt, pediatr, dermatolog,
ginekolog, urolog, jarroh, stomatolog, ko'z shifokori, quloq-burn-tomoq ENT,
retinopatiya, tromboz, vitiligo, psoriasis, EEG, EKG, EKO, KT, MRI, PCR, laboratoriya HIS,
ultratovush UZI, rentgenografiya Rentgen fluorografiya,
qon tahlili, bioximiya, glyukoza, lipidlar, gistologiya, tsitologiya, analiz sidik,
retsept, tabletkalari, antibiotic, antifungal, probiotik, Vitamin D, fizioterapiya nebulizer,
inzeksiya kapya kapiller, operatsiya, plastika, blokada blokirovka epidural blok,

RU cyrillic mixed: врач, пациент, приём, консультация, симптом, боль, температура, давление,
аллергия, диабет, инсулин, антибиотик, УЗИ, рентген, МРТ, КТ ЭКГ, анализ крови анализ мочи биохимия,
рецепт, таблетки, клиника больница поликлиника скорая помощь,

ENLatin drug names helpers: Paracetamol acetaminophen Ibuprofen Aspirin Amoxicillin
Azithromycin Omeprazole Metformin Atorvastatin Losartan Bisoprolol Salbutamol Budesonide
Mezym No-Shpa Drotaverine Papaverine Citramon Corvalol.

ICD-10 ICD MKB morphology codes.
"""

    private fun langTail(iso: String): String = when (iso.lowercase()) {
        "uz" ->
            """
Primary decoding: Uzbek (O‘zbek latin). Medical dialogue in Uzbek clinics; Russian and English mixed in.
Prefer Latin Uzbek script. Terms: sog'liqni saqlash, kasallik, aniqlik, tasdiqlash,
poliklinika, shoshilinch yordam, vitamylar, fizioterapiya, profilaktika.

""".trimIndent()
        "ru" ->
            """
Primary decoding: Russian medical dialogue. Uzbek or English words may appear; keep Russian spelling for Russian clauses.

""".trimIndent()
        "en" ->
            """
Primary decoding: English medical dialogue with possible Uzbek/Russian names or drug labels.

""".trimIndent()
        else -> ""
    }

    /** Full bias string: shared lexicon + specialty aliases + optional language emphasis. */
    fun build(languageHintIso639OrNull: String?): String {
        val specs = SpecialtyTaxonomy.speechBiasSpecialtyTerms()
        val specialtyBlock = "\nMedical specialty search vocabulary: $specs.\n"
        val shared = SHARED_CLINICAL.trim().replace("\\s+".toRegex(), " ") + specialtyBlock
        val lang = languageHintIso639OrNull?.lowercase()?.trim().orEmpty()
        val hint = langTail(lang)
        return (shared.trim() + " " + hint.trim()).trim()
    }
}
