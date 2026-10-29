package com.shifa.doctors.api.dto

data class ReviewDto(
    val id: Long?,
    val patientName: String,
    val patientPhotoUrl: String?,
    val rating: Int,
    val comment: String?,
    val createdAt: String
)

data class CreateReviewRequest(
    val rating: Int,
    val comment: String?,
    val appointmentId: Long?
)
