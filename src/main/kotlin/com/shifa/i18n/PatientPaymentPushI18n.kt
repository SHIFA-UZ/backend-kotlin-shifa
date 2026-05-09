package com.shifa.i18n

/**
 * Push + persisted [com.shifa.domain.Notification] title/body for payment-related patient alerts.
 * Wording aligned with apps-flutter-patient [AppLocalizations] (en, de, uz, ru).
 */
object PatientPaymentPushI18n {

    private fun code(lang: String?): String = when (lang?.lowercase()?.trim()?.substringBefore('-')?.take(2)) {
        "uz" -> "uz"
        "ru" -> "ru"
        "de" -> "de"
        else -> "en"
    }

    /** Notification title for any "pay for this consultation" alert (push tray + history). */
    fun paymentTitle(lang: String?): String = when (code(lang)) {
        "uz" -> "To'lovni yakunlang"
        "ru" -> "Завершите оплату"
        "de" -> "Zahlung abschließen"
        else -> "Complete your payment"
    }

    fun paymentDueBody(lang: String?, hoursFromNow: Int): String = when (code(lang)) {
        "uz" -> when (hoursFromNow) {
            24 -> "Video maslahatingiz 24 soatdan keyin. Bronni tasdiqlash uchun to'lovni yakunlang."
            6 -> "Video maslahatingiz 6 soatdan keyin. Bronni tasdiqlash uchun to'lovni yakunlang."
            else -> "Video maslahatingiz 1 soatdan keyin. Bronni tasdiqlash uchun to'lovni yakunlang."
        }
        "ru" -> when (hoursFromNow) {
            24 -> "Видеоконсультация через 24 часа. Завершите оплату, чтобы подтвердить запись."
            6 -> "Видеоконсультация через 6 часов. Завершите оплату, чтобы подтвердить запись."
            else -> "Видеоконсультация через 1 час. Завершите оплату, чтобы подтвердить запись."
        }
        "de" -> when (hoursFromNow) {
            24 -> "Ihre Videosprechstunde ist in 24 Stunden. Bitte schließen Sie die Zahlung ab, um die Buchung zu bestätigen."
            6 -> "Ihre Videosprechstunde ist in 6 Stunden. Bitte schließen Sie die Zahlung ab, um die Buchung zu bestätigen."
            else -> "Ihre Videosprechstunde ist in 1 Stunde. Bitte schließen Sie die Zahlung ab, um die Buchung zu bestätigen."
        }
        else -> when (hoursFromNow) {
            24 -> "Your video consultation is in 24 hours. Please complete payment to confirm your booking."
            6 -> "Your video consultation is in 6 hours. Please complete payment to confirm your booking."
            else -> "Your video consultation is in 1 hour. Please complete payment to confirm your booking."
        }
    }

    /**
     * Message when the doctor taps "remind patient to pay".
     * [doctorDisplayName] is "First Last" or empty — we add a localized "Dr." prefix where appropriate.
     */
    fun doctorNudgeBody(lang: String?, doctorDisplayName: String): String {
        val name = doctorDisplayName.trim()
        val c = code(lang)
        if (name.isEmpty()) {
            return when (c) {
                "uz" -> "Iltimos, video maslahatni tasdiqlash uchun to'lovni yakunlang."
                "ru" -> "Пожалуйста, завершите оплату, чтобы подтвердить видеоконсультацию."
                "de" -> "Bitte schließen Sie die Zahlung ab, um die Videosprechstunde zu bestätigen."
                else -> "Please complete payment to confirm your video consultation."
            }
        }
        val drName = if (name.startsWith("dr.", ignoreCase = true) || name.startsWith("dr ", ignoreCase = true)) {
            name
        } else {
            when (c) {
                "ru" -> "Др. $name"
                "de" -> "Dr. $name"
                "uz" -> "Dr. $name"
                else -> "Dr. $name"
            }
        }
        return when (c) {
            "uz" -> "$drName video maslahatni tasdiqlash uchun to'lovni yakunlashingizni so'ramoqda."
            "ru" -> "$drName просит вас завершить оплату, чтобы подтвердить видеоконсультацию."
            "de" -> "$drName bittet Sie, die Zahlung abzuschließen, um die Videosprechstunde zu bestätigen."
            else -> "$drName asks you to complete payment to confirm your video consultation."
        }
    }
}
