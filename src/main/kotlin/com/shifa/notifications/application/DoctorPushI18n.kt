package com.shifa.notifications.application


import com.shifa.notifications.domain.Notification

/**
 * Localizes the FCM tray title/body for the doctor app.
 * In-app notification rows stay English; Flutter localizes the list from [Notification.type].
 */
object DoctorPushI18n {

    data class TrayText(val title: String, val body: String)

    fun normalizeLang(raw: String?): String {
        val t = raw?.trim()?.lowercase().orEmpty()
        return when {
            t.startsWith("uz") -> "uz"
            t.startsWith("ru") -> "ru"
            else -> "en"
        }
    }

    fun trayText(
        notification: Notification,
        language: String,
        patientName: String,
    ): TrayText {
        val lang = normalizeLang(language)
        val name = patientName.trim().ifEmpty { patientFallback(lang) }
        val type = notification.type
        val fallback = TrayText(notification.title, notification.message)

        return when (type) {
            Notification.Type.APPOINTMENT_BOOKED_BY_PATIENT,
            Notification.Type.APPOINTMENT_REMINDER,
            Notification.Type.APPOINTMENT_ASSIGNED -> TrayText(
                title = t(
                    lang,
                    en = "Appointment booked",
                    uz = "Uchrashuv band qilindi",
                    ru = "Запись создана",
                ),
                body = t(
                    lang,
                    en = "Patient $name booked an appointment.",
                    uz = "Bemor $name uchrashuvni band qildi.",
                    ru = "Пациент $name записался на приём.",
                ),
            )
            Notification.Type.APPOINTMENT_CANCELLED_BY_PATIENT,
            Notification.Type.APPOINTMENT_CANCELLED -> TrayText(
                title = t(
                    lang,
                    en = "Appointment cancelled",
                    uz = "Uchrashuv bekor qilindi",
                    ru = "Запись отменена",
                ),
                body = t(
                    lang,
                    en = "Patient $name cancelled their appointment.",
                    uz = "Bemor $name uchrashuvni bekor qildi.",
                    ru = "Пациент $name отменил запись.",
                ),
            )
            Notification.Type.APPOINTMENT_RESCHEDULED_BY_PATIENT,
            Notification.Type.APPOINTMENT_CHANGED -> TrayText(
                title = t(
                    lang,
                    en = "Appointment rescheduled",
                    uz = "Uchrashuv vaqti o'zgartirildi",
                    ru = "Запись перенесена",
                ),
                body = t(
                    lang,
                    en = "Patient $name rescheduled their appointment.",
                    uz = "Bemor $name uchrashuv vaqtini o'zgartirdi.",
                    ru = "Пациент $name перенёс запись.",
                ),
            )
            Notification.Type.APPOINTMENT_PAID_BY_PATIENT -> TrayText(
                title = t(
                    lang,
                    en = "Payment received",
                    uz = "To'lov qabul qilindi",
                    ru = "Оплата получена",
                ),
                body = t(
                    lang,
                    en = "Patient $name paid for their appointment.",
                    uz = "Bemor $name uchrashuv uchun to'lov qildi.",
                    ru = "Пациент $name оплатил приём.",
                ),
            )
            Notification.Type.CHAT_MESSAGE -> TrayText(
                title = t(
                    lang,
                    en = "New message",
                    uz = "Yangi xabar",
                    ru = "Новое сообщение",
                ),
                body = t(
                    lang,
                    en = "You have a new patient message.",
                    uz = "Bemordan yangi xabar bor.",
                    ru = "Новое сообщение от пациента.",
                ),
            )
            Notification.Type.CARE_PARTNERSHIP_INVITE -> TrayText(
                title = t(
                    lang,
                    en = "Therapy partner invite",
                    uz = "Hamkorlik taklifi",
                    ru = "Приглашение в партнёрство",
                ),
                body = t(
                    lang,
                    en = "A doctor invited you to co-treat a patient.",
                    uz = "Shifokor sizni bemorni birga davolashga taklif qildi.",
                    ru = "Врач пригласил вас совместно вести пациента.",
                ),
            )
            Notification.Type.CARE_PARTNERSHIP_ACCEPTED -> TrayText(
                title = t(
                    lang,
                    en = "Partner accepted",
                    uz = "Hamkor qabul qildi",
                    ru = "Партнёр принял приглашение",
                ),
                body = t(
                    lang,
                    en = "Your therapy partnership invite was accepted.",
                    uz = "Hamkorlik taklifingiz qabul qilindi.",
                    ru = "Ваше приглашение в партнёрство принято.",
                ),
            )
            Notification.Type.CARE_PARTNERSHIP_PROGRESS -> TrayText(
                title = t(
                    lang,
                    en = "Partnership update",
                    uz = "Hamkorlik yangilanishi",
                    ru = "Обновление партнёрства",
                ),
                body = t(
                    lang,
                    en = "There is a new partnership progress update.",
                    uz = "Hamkorlik bo'yicha yangi yangilanish bor.",
                    ru = "Есть новое обновление по партнёрству.",
                ),
            )
            Notification.Type.DOCUMENT_ACCESS_REQUEST -> TrayText(
                title = t(
                    lang,
                    en = "Document access request",
                    uz = "Hujjatga kirish so'rovi",
                    ru = "Запрос доступа к документу",
                ),
                body = fallback.body,
            )
            Notification.Type.DOCUMENT_ACCESS_APPROVED -> TrayText(
                title = t(
                    lang,
                    en = "Document access approved",
                    uz = "Hujjatga kirish tasdiqlandi",
                    ru = "Доступ к документу одобрен",
                ),
                body = fallback.body,
            )
            Notification.Type.DOCUMENT_ACCESS_REJECTED -> TrayText(
                title = t(
                    lang,
                    en = "Document access rejected",
                    uz = "Hujjatga kirish rad etildi",
                    ru = "Доступ к документу отклонён",
                ),
                body = fallback.body,
            )
            Notification.Type.TASK_COMPLETED -> TrayText(
                title = t(
                    lang,
                    en = "Task completed",
                    uz = "Vazifa bajarildi",
                    ru = "Задача выполнена",
                ),
                body = t(
                    lang,
                    en = "Patient $name completed a task.",
                    uz = "Bemor $name vazifani bajardi.",
                    ru = "Пациент $name выполнил задачу.",
                ),
            )
            Notification.Type.TASK_ASSIGNED -> TrayText(
                title = t(
                    lang,
                    en = "Task assigned",
                    uz = "Vazifa berildi",
                    ru = "Задача назначена",
                ),
                body = fallback.body,
            )
            Notification.Type.AI_SCRIBE_READY -> TrayText(
                title = t(
                    lang,
                    en = "AI Scribe summary ready",
                    uz = "AI yozuvchi xulosasi tayyor",
                    ru = "Готово резюме AI-секретаря",
                ),
                body = t(
                    lang,
                    en = "Consultation summary is ready. Open the appointment to view.",
                    uz = "Konsultatsiya xulosasi tayyor. Ko'rish uchun uchrashuvni oching.",
                    ru = "Резюме консультации готово. Откройте приём, чтобы посмотреть.",
                ),
            )
            Notification.Type.APPOINTMENT_BRIEFING_READY -> TrayText(
                title = t(
                    lang,
                    en = "Appointment briefing ready",
                    uz = "Uchrashuv brifingi tayyor",
                    ru = "Брифинг приёма готов",
                ),
                body = t(
                    lang,
                    en = "AI briefing is ready for $name.",
                    uz = "$name uchun AI brifing tayyor.",
                    ru = "Брифинг ИИ готов для пациента $name.",
                ),
            )
            else -> fallback
        }
    }

    fun patientNameFrom(notification: Notification, extraData: Map<String, String> = emptyMap()): String {
        extraData["patientName"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val message = notification.message.trim()
        val match = Regex(
            """^Patient\s+(.+?)\s+(cancelled|booked|paid|moved|rescheduled|completed)\b""",
            RegexOption.IGNORE_CASE,
        ).find(message)
        if (match != null) return match.groupValues[1].trim()
        return ""
    }

    private fun patientFallback(lang: String): String = when (lang) {
        "uz" -> "Bemor"
        "ru" -> "Пациент"
        else -> "Patient"
    }

    private fun t(lang: String, en: String, uz: String, ru: String): String = when (lang) {
        "uz" -> uz
        "ru" -> ru
        else -> en
    }
}
