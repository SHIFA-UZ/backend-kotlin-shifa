package com.shifa.patients.api.dto

data class InviteRequest(
    val patientId: Long,
    val partnerDoctorId: Long,
    val specialtyRequested: String? = null,
    val message: String? = null,
    val originatingAppointmentId: Long? = null,
)

data class ProgressRequest(val body: String)
