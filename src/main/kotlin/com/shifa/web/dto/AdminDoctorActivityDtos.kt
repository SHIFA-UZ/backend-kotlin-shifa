package com.shifa.web.dto

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
)

data class DoctorActivityDetailDto(
    @JsonProperty("row") val row: DoctorActivityRowDto,
    @JsonProperty("dailySeries") val dailySeries: Map<String, List<DoctorActivityDailyPointDto>>,
)
