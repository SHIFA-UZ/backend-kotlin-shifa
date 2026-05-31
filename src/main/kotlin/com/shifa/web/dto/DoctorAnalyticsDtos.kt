// DTOs for doctor analytics API. Aggregate values only; no PII (GDPR-safe).

package com.shifa.web.dto

/** GET /api/doctor/analytics/overview */
data class DoctorAnalyticsOverviewDto(
    val appointmentsToday: Int,
    val completedToday: Int,
    val cancelledToday: Int,
    val newPatientsToday: Int
)

/** GET /api/doctor/analytics/appointments-trend?days=7 */
data class AppointmentTrendPointDto(
    val date: String, // yyyy-MM-dd
    val count: Int
)

/** GET /api/doctor/analytics/consultation-types */
data class ConsultationTypesDto(
    val video: Int,
    val inPerson: Int
)

/** GET /api/doctor/analytics/engagement */
data class DoctorEngagementDto(
    val activePatients: Int,
    val documentsReceived: Int
)

/** GET /api/doctor/analytics/sms-usage */
data class DoctorSmsUsageDto(
    val sentCount: Long,
    val totalCostMinor: Long,
    val currency: String,
    val pricePerSmsMinor: Long,
    val smsRemindersAllowed: Boolean,
)
