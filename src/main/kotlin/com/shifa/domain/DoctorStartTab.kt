package com.shifa.domain

object DoctorStartTab {
    const val CHAT = "chat"
    const val HOME = "home"
    const val CALENDAR = "calendar"
    const val PATIENTS = "patients"
    const val CLINIC = "clinic"
    const val TASKS = "tasks"
    const val REPORTS = "reports"
    const val NOTIFICATIONS = "notifications"
    const val PROFILE = "profile"

    val DOCTOR_KEYS = setOf(
        CHAT, HOME, CALENDAR, PATIENTS, CLINIC, TASKS, REPORTS, NOTIFICATIONS, PROFILE
    )

    val CLINIC_STAFF_KEYS = setOf(CHAT, CLINIC, NOTIFICATIONS, PROFILE)

    fun normalize(raw: String?, clinicStaff: Boolean = false): String {
        val allowed = if (clinicStaff) CLINIC_STAFF_KEYS else DOCTOR_KEYS
        val trimmed = raw?.trim()?.lowercase().orEmpty()
        return trimmed.takeIf { it in allowed } ?: HOME
    }
}
