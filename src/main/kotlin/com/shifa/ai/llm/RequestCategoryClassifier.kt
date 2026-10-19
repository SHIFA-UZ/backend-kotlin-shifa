package com.shifa.ai.llm

/**
 * Cheap keyword classification used only to pick an optional per-category route.
 * Unknown input is [RequestCategory.GENERAL_ASSISTANCE]; it never gates tool execution.
 */
object RequestCategoryClassifier {
    private val cancel = listOf("cancel", "отмен", "bekor")
    private val reschedule = listOf("reschedule", "move ", "перенес", "перенос", "ko'chir", "kochir", "o'zgartir")
    private val booking = listOf("book", "make an appointment", "schedule an appointment", "new appointment", "запис", "yozib", "yozing", "uchrashuv")
    private val search = listOf("find patient", "search patient", "look up", "найди", "найти пациент", "bemorni top", "qidir")
    private val documentation = listOf("dictat", "note", "document", "consultation", "signature", "диктов", "заметк", "документ", "подпись", "hujjat", "imzo", "yozuv")
    private val schedule = listOf(
        "schedule", "appointments", "appointment", "today", "tomorrow", "free slot", "available", "who is next", "how many",
        "расписан", "сегодня", "завтра", "свободн", "сколько", "кто следующий",
        "bugun", "ertaga", "bo'sh", "bosh vaqt", "nechta", "qancha", "jadval",
    )

    fun classify(latestUserText: String): RequestCategory {
        val t = latestUserText.lowercase()
        if (t.isBlank()) return RequestCategory.GENERAL_ASSISTANCE
        return when {
            cancel.any { t.contains(it) } -> RequestCategory.APPOINTMENT_CANCELLATION
            reschedule.any { t.contains(it) } -> RequestCategory.APPOINTMENT_RESCHEDULE
            booking.any { t.contains(it) } -> RequestCategory.APPOINTMENT_BOOKING
            search.any { t.contains(it) } -> RequestCategory.PATIENT_SEARCH
            documentation.any { t.contains(it) } -> RequestCategory.CLINICAL_DOCUMENTATION
            schedule.any { t.contains(it) } -> RequestCategory.SCHEDULE_QUERY
            else -> RequestCategory.GENERAL_ASSISTANCE
        }
    }
}
