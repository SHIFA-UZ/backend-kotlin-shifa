// src/main/kotlin/com/shifa/web/DoctorReviewController.kt
package com.shifa.web

import com.shifa.domain.Appointment
import com.shifa.domain.DoctorReview
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.DoctorReviewRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.PatientPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api/public/doctors/{doctorId}/reviews")
class DoctorReviewController(
    private val doctorProfiles: DoctorProfileRepository,
    private val patientProfiles: PatientProfileRepository,
    private val reviewRepository: DoctorReviewRepository,
    private val appointmentRepository: AppointmentRepository
) {

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

    /**
     * GET /api/public/doctors/{doctorId}/reviews
     * Get all reviews for a doctor
     */
    @GetMapping
    fun getReviews(@PathVariable doctorId: Long): List<ReviewDto> {
        val doctor = doctorProfiles.findById(doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }

        val reviews = reviewRepository.findByDoctorIdOrderByCreatedAtDesc(doctorId)
        
        return reviews.map { review ->
            ReviewDto(
                id = review.id,
                patientName = review.patient.fullName ?: "Anonymous",
                patientPhotoUrl = review.patient.photoUrl,
                rating = review.rating,
                comment = review.comment,
                createdAt = review.createdAt.toString()
            )
        }
    }

    /**
     * POST /api/public/doctors/{doctorId}/reviews
     * Create a review for a doctor (requires patient authentication)
     * Requires appointmentId and validates that:
     * - Appointment exists and belongs to the patient
     * - Appointment is not cancelled
     * - Appointment is in the past
     * - No review already exists for this appointment
     */
    @PostMapping
    fun createReview(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable doctorId: Long,
        @RequestBody req: CreateReviewRequest
    ): ReviewDto {
        val doctor = doctorProfiles.findById(doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }

        val user = principal.user
        val patient = user.phone?.let { patientProfiles.findByPhone(it) }
            ?.orElseGet {
                user.email?.let { patientProfiles.findByEmail(it) }
                    ?.orElse(null)
            }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found")

        if (req.rating !in 1..5) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Rating must be between 1 and 5")
        }

        // Validate appointment if provided
        val appointment: Appointment? = req.appointmentId?.let { appointmentId ->
            val apt = appointmentRepository.findById(appointmentId)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }
            
            // Verify appointment belongs to this patient
            if (apt.patient.id != patient.id) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this patient")
            }
            
            // Verify appointment is for this doctor
            if (apt.doctor.id != doctorId) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment is not for this doctor")
            }
            
            // Verify appointment is not cancelled
            if (apt.status == Appointment.Status.CANCELLED) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot review a cancelled appointment")
            }
            
            // Verify appointment is in the past
            if (apt.startAt.isAfter(java.time.Instant.now())) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Can only review past appointments")
            }
            
            // Check if review already exists for this appointment
            reviewRepository.findByAppointmentId(appointmentId)?.let {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Review already exists for this appointment")
            }
            
            apt
        }

        val review = DoctorReview(
            doctor = doctor,
            patient = patient,
            appointment = appointment,
            rating = req.rating,
            comment = req.comment?.trim()?.takeIf { it.isNotEmpty() }
        )

        val saved = reviewRepository.save(review)

        return ReviewDto(
            id = saved.id,
            patientName = saved.patient.fullName ?: "Anonymous",
            patientPhotoUrl = saved.patient.photoUrl,
            rating = saved.rating,
            comment = saved.comment,
            createdAt = saved.createdAt.toString()
        )
    }
}
