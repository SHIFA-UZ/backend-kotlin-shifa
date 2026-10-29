package com.shifa.appointments.api.dto

data class BlockDto(
    val id: Long? = null,
    val startAt: String,
    val endAt: String,
    val reason: String? = null,
    /** When true, overlapping future/in-progress appointments are cancelled. */
    val cancelOverlappingAppointments: Boolean = false,
    /** Calendar doctor (defaults to authenticated doctor). */
    val resourceDoctorId: Long? = null,
    val cancelledAppointmentCount: Int? = null,
)

data class OverlapCountDto(val count: Int)
