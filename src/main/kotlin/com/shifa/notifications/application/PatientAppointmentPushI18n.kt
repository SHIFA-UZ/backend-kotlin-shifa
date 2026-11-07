package com.shifa.notifications.application

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Title and body (push tray and inbox row) of the patient's visit alerts that carry a reason: a cancellation by the
 * doctor's side with a reason code, and a no-show. In the patient's language (en, uz, ru; English otherwise).
 */
object PatientAppointmentPushI18n {

    data class Text(val title: String, val body: String)

    /** The visit's day in the doctor's zone: "7 November 2026", "7 ноября 2026", "7 noyabr 2026". */
    fun visitDate(startAt: Instant, zone: ZoneId, language: String?): String {
        val lang = DoctorPushI18n.normalizeLang(language)
        return DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag(lang)).format(startAt.atZone(zone))
    }

    /** [reasonCode] is an `Appointment.CancelReason` name; an unknown one reads as "other". */
    fun cancelled(language: String?, date: String, reasonCode: String): Text {
        val lang = DoctorPushI18n.normalizeLang(language)
        val title = pick(lang, en = "Appointment Cancelled", uz = "Qabul bekor qilindi", ru = "Запись отменена")
        val body = pick(
            lang,
            en = "Doctor has cancelled your appointment on $date. Please make another appointment.",
            uz = "Shifokor $date kungi qabulingizni bekor qildi. Iltimos, yangi qabulga yoziling.",
            ru = "Врач отменил вашу запись на $date. Пожалуйста, запишитесь снова.",
        )
        return Text(title, "$body ${reasonSentence(lang, reasonCode)}")
    }

    fun noShow(language: String?, date: String): Text {
        val lang = DoctorPushI18n.normalizeLang(language)
        return Text(
            title = pick(lang, en = "Missed appointment", uz = "O'tkazib yuborilgan qabul", ru = "Пропущенный приём"),
            body = pick(
                lang,
                en = "Your appointment on $date was marked as missed. Please book a new one if you still need a visit.",
                uz = "$date kungi qabulingiz o'tkazib yuborilgan deb belgilandi. Kerak bo'lsa, yangi qabulga yoziling.",
                ru = "Ваш приём $date отмечен как пропущенный. Если приём всё ещё нужен, запишитесь снова.",
            ),
        )
    }

    internal fun reasonSentence(lang: String, reasonCode: String): String = when (reasonCode) {
        "DOCTOR_UNAVAILABLE" -> pick(lang, "Reason: the doctor is unavailable.", "Sabab: shifokor band.", "Причина: врач недоступен.")
        "PATIENT_REQUEST" -> pick(lang, "Reason: at your request.", "Sabab: sizning so'rovingiz bo'yicha.", "Причина: по вашей просьбе.")
        "RESCHEDULED" -> pick(
            lang,
            "Reason: the visit is moving to another time.",
            "Sabab: qabul boshqa vaqtga ko'chirilmoqda.",
            "Причина: приём переносится на другое время.",
        )
        else -> pick(lang, "Reason: other.", "Sabab: boshqa.", "Причина: другое.")
    }

    private fun pick(lang: String, en: String, uz: String, ru: String): String = when (lang) {
        "uz" -> uz
        "ru" -> ru
        else -> en
    }
}
