package com.shifa.admin.api

import com.fasterxml.jackson.annotation.JsonProperty

data class DoctorActivityDailyPointDto(
    @JsonProperty("date") val date: String,
    @JsonProperty("count") val count: Int,
)

data class DoctorActivityRowDto(
    @JsonProperty("doctorId") val doctorId: Long,
    @JsonProperty("doctorName") val doctorName: String,
    @JsonProperty("email") val email: String?,
    @JsonProperty("clinicId") val clinicId: Long?,
    @JsonProperty("clinicName") val clinicName: String?,
    @JsonProperty("appointmentsBooked") val appointmentsBooked: Int,
    @JsonProperty("appointmentsCompleted") val appointmentsCompleted: Int,
    @JsonProperty("appointmentsCancelled") val appointmentsCancelled: Int,
    @JsonProperty("cancellationRate") val cancellationRate: Double,
    @JsonProperty("videoAppointments") val videoAppointments: Int,
    @JsonProperty("activePatients") val activePatients: Int,
    @JsonProperty("patientsCreated") val patientsCreated: Int,
    @JsonProperty("documentsUploaded") val documentsUploaded: Int,
    @JsonProperty("treatmentPlans") val treatmentPlans: Int,
    @JsonProperty("remoteTasks") val remoteTasks: Int,
    @JsonProperty("consultationNotes") val consultationNotes: Int,
    @JsonProperty("patientForms") val patientForms: Int,
    @JsonProperty("aiRequests") val aiRequests: Long,
    @JsonProperty("aiDraftNotes") val aiDraftNotes: Int,
    @JsonProperty("lastActiveAt") val lastActiveAt: String?,
    @JsonProperty("earlyPartnerContractNumber") val earlyPartnerContractNumber: String? = null,
    @JsonProperty("smsRemindersAllowed") val smsRemindersAllowed: Boolean = false,
    @JsonProperty("smsSentCount") val smsSentCount: Long = 0,
    @JsonProperty("smsOwedMinor") val smsOwedMinor: Long = 0,
    @JsonProperty("smsCurrency") val smsCurrency: String = "UZS",
    @JsonProperty("smsPricePerUnitMinor") val smsPricePerUnitMinor: Long = 500,
    @JsonProperty("dateJoinedAt") val dateJoinedAt: String?,
    @JsonProperty("trialPeriodMonths") val trialPeriodMonths: Int,
    @JsonProperty("monthlyChargeUsd") val monthlyChargeUsd: Int,
    @JsonProperty("monthsAfterTrial") val monthsAfterTrial: Int,
    @JsonProperty("totalDebtUsd") val totalDebtUsd: Int,
    /** The account's user id. Tells apart the rows of doctor accounts without a profile, which all have `doctorId` 0. */
    @JsonProperty("userId") val userId: Long? = null,
    /** False for a doctor account without a doctor profile (`doctorId` is 0 then and the row cannot be opened). */
    @JsonProperty("hasProfile") val hasProfile: Boolean = true,
)

data class DoctorActivityDetailDto(
    @JsonProperty("row") val row: DoctorActivityRowDto,
    @JsonProperty("dailySeries") val dailySeries: Map<String, List<DoctorActivityDailyPointDto>>,
    /** The days [row] counts, inclusive (`yyyy-MM-dd`); both null when the row is all time (no `from`/`to` sent). */
    @JsonProperty("rowFrom") val rowFrom: String? = null,
    @JsonProperty("rowTo") val rowTo: String? = null,
    /** The days [dailySeries] covers, inclusive: the last 90 days without dates, at most the last 366 of a longer range. */
    @JsonProperty("seriesFrom") val seriesFrom: String? = null,
    @JsonProperty("seriesTo") val seriesTo: String? = null,
)
