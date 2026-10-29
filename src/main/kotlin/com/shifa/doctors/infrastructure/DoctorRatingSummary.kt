package com.shifa.doctors.infrastructure

/** One doctor's review aggregate, as [DoctorReviewRepository.summarizeByDoctorIds] projects it. */
interface DoctorRatingSummary {
    val doctorId: Long
    val averageRating: Double?
    val reviewCount: Long
}
