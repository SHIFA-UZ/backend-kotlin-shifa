package com.shifa.ai

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptPromptLeakStripperTest {
    @Test
    fun `strips glossary dump but keeps spoken visit lines`() {
        val raw = """
            Assalomu alaykum, doktor. Qorin og'rig'idan shikoyatim bor, dedim. Dori yozib beray. Bosh aylanishi va bosim holati bezovta qilyaptimi? Hozirda nima olib yatyapmiz? Qornim asosan yog'liq ovqatlar yeganimda og'riq tushib boshlaydi o'ng tarafga. Ha, albatta. Shifokor, bemor, qabul, konsultatsiya, simptom, og'riq, bosh aylanishi, bosim holati, isitma, harorat, qon bosimi, shamollash, yo'tal, allergiya, diabet, yurak, oshqozon, ichak, jig'ar, buyrak, bronxit, pnevmoniya, nevrologiya, nevropatolog, kardiolog, terapevt, pediatr, dermatolog, ginekolog, urolog, jarroh, stomatolog, ko'z shifokori, quloq-burn-tomoq ENT, retinopatiya, tromboz, vitiligo, psoriasis, EEG, EKG, EKO, KT, MRI, PCR, laboratoriya HIS, ultratovush UZI, rentgenografiya Rentgen fluorografiya, qon tahlili, bioximiya, glyukoza, lipidlar, gistologiya, tsitologiya, analiz sidik, retsept, tabletkalari, antibiotik, antifungal, probiotik, Vitamin D, fizioterapiya nebulizer, in'ektsiya kapya kapiller, operatsiya, plastika, blokada blokirovka epidural blok. Mezym masalan Pantoprazol dedimi? Pediatrga murojaat qilish. Mening belim juda og'riyapti. Har kuni dori ichib olsam. So, tugatdim.
        """.trimIndent()

        val cleaned = TranscriptPromptLeakStripper.strip(raw)
        assertTrue(cleaned.contains("Qorin og'rig'idan"))
        assertTrue(cleaned.contains("belim juda og'riyapti"))
        assertFalse(cleaned.contains("quloq-burn-tomoq"))
        assertFalse(cleaned.contains("vitiligo"))
        assertFalse(cleaned.contains("epidural blok"))
    }
}
