// src/main/kotlin/com/shifa/web/VideoController.kt
package com.shifa.web

import com.shifa.repo.AppointmentRepository
import com.shifa.security.DoctorPrincipal
import java.time.Instant
import com.shifa.security.PatientPrincipal
import com.shifa.service.DailyVideoService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/video")
class VideoController(
    private val dailyVideoService: DailyVideoService,
    private val appointmentRepository: AppointmentRepository,
    private val patientProfileRepository: com.shifa.repo.PatientProfileRepository
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)
    
    private fun currentPatientProfile(principal: PatientPrincipal): com.shifa.domain.PatientProfile {
        val user = principal.user
        val logger = org.slf4j.LoggerFactory.getLogger(javaClass)
        
        logger.info("Looking up patient profile for user ${user.id}, phone: ${user.phone}, email: ${user.email}")
        
        // Try to find by phone first (trim whitespace)
        user.phone?.trim()?.takeIf { it.isNotBlank() }?.let { phone ->
            try {
                val profile = patientProfileRepository.findByPhone(phone)
                if (profile.isPresent) {
                    logger.info("Found patient profile by phone: ${profile.get().id}")
                    return profile.get()
                } else {
                    logger.debug("No patient profile found by phone: $phone")
                }
            } catch (e: Exception) {
                logger.warn("Error looking up patient profile by phone $phone: ${e.message}", e)
            }
        }
        
        // Try to find by email (trim and lowercase for consistency)
        user.email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { email ->
            try {
                val profile = patientProfileRepository.findByEmail(email)
                if (profile.isPresent) {
                    logger.info("Found patient profile by email: ${profile.get().id}")
                    return profile.get()
                } else {
                    logger.debug("No patient profile found by email: $email")
                }
            } catch (e: Exception) {
                logger.warn("Error looking up patient profile by email $email: ${e.message}", e)
            }
        }
        
        // If not found, throw exception with helpful message
        logger.warn("Patient profile not found for user ${user.id} (phone: ${user.phone}, email: ${user.email})")
        throw ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND,
            "Patient profile not found for user ${user.id}. Please ensure your profile is complete with phone or email."
        )
    }

    data class VideoTokenRequest(
        val appointmentId: Long,
        val roomName: String? = null
    )

    data class VideoTokenResponse(
        val token: String,
        val roomUrl: String,
        val roomName: String
    )

    /**
     * Generate video token for doctor or patient
     */
    @PostMapping("/token")
    fun generateToken(
        @AuthenticationPrincipal principal: Any?,
        @RequestBody request: VideoTokenRequest
    ): VideoTokenResponse {
        logger.info("Video token request received: appointmentId=${request.appointmentId}, principal type=${principal?.javaClass?.simpleName}")
        try {
            // Check if Daily.co API key is configured
            if (!dailyVideoService.isApiKeyConfigured()) {
                logger.error("Daily.co API key is not configured")
                throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Daily.co API key is not configured. Please set DAILY_API_KEY environment variable in Railway."
                )
            }
            
            // Determine if user is doctor or patient
            val isDoctor = principal is DoctorPrincipal
            val isPatient = principal is PatientPrincipal
            logger.debug("User type - isDoctor: $isDoctor, isPatient: $isPatient")

            if (!isDoctor && !isPatient) {
                logger.warn("Unauthenticated request to video token endpoint")
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
            }

            // Verify appointment exists and user has access
            logger.debug("Looking up appointment: ${request.appointmentId}")
            val appointment = appointmentRepository.findById(request.appointmentId)
                .orElseThrow {
                    logger.warn("Appointment not found: ${request.appointmentId}")
                    ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: ${request.appointmentId}")
                }
            logger.debug("Appointment found: id=${appointment.id}, doctorId=${appointment.doctor?.id}, patientId=${appointment.patient?.id}")

            // Get user profile once (doctor or patient)
            val doctor = if (isDoctor) {
                logger.debug("Getting doctor profile")
                (principal as DoctorPrincipal).profile
                    ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Doctor profile not found")
            } else null
            
            val patient = if (isPatient) {
                logger.debug("Getting patient profile")
                try {
                    currentPatientProfile(principal as PatientPrincipal)
                } catch (e: ResponseStatusException) {
                    logger.warn("Patient profile lookup failed: ${e.message}")
                    // Re-throw ResponseStatusException as-is
                    throw e
                } catch (e: Exception) {
                    logger.error("Unexpected error retrieving patient profile: ${e.message}", e)
                    throw ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to retrieve patient profile: ${e.message}",
                        e
                    )
                }
            } else null

            // Join window: from 5 min before start to 15 min after end (all UTC)
            val now = Instant.now()
            if (now.isBefore(appointment.startAt.minusSeconds(5 * 60L))) {
                throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Video call is not yet available. You can join 5 minutes before the appointment start."
                )
            }
            if (now.isAfter(appointment.endAt.plusSeconds(15 * 60L))) {
                throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Video call has ended. The join window closes 15 minutes after the appointment end."
                )
            }

            // Payment gate for paid consultations: token can be issued only after payment is confirmed.
            val requiresPayment = (appointment.paymentAmountMinor ?: 0L) > 0L
            if (requiresPayment && appointment.paymentStatus != com.shifa.domain.Appointment.PaymentStatus.PAID) {
                logger.warn(
                    "Video token blocked for appointment {} due to unpaid status: paymentStatus={}, amountMinor={}",
                    appointment.id,
                    appointment.paymentStatus,
                    appointment.paymentAmountMinor
                )
                throw ResponseStatusException(
                    HttpStatus.PAYMENT_REQUIRED,
                    "Payment is required before joining this video consultation."
                )
            }

            // Verify user has access to this appointment
            if (isDoctor && doctor != null) {
                if (appointment.doctor?.id != doctor.id) {
                    logger.warn("Doctor ${doctor.id} attempted to access appointment ${appointment.id} belonging to doctor ${appointment.doctor?.id}")
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
                }
            } else if (isPatient && patient != null) {
                if (appointment.patient == null) {
                    logger.warn("Appointment ${appointment.id} has no patient assigned - patient cannot join video")
                    throw ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Appointment does not have a patient assigned. Please ensure the appointment is linked to your profile."
                    )
                }
                if (appointment.patient!!.id != patient.id) {
                    logger.warn("Patient ${patient.id} attempted to access appointment ${appointment.id} belonging to patient ${appointment.patient!!.id}")
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this patient")
                }
                logger.debug("Patient ${patient.id} verified for appointment ${appointment.id}")
            }

            // Generate room name if not provided
            val roomName = request.roomName ?: "appointment-${request.appointmentId}"
            logger.debug("Using room name: $roomName")

            // Get or create room
            logger.info("Getting or creating Daily.co room: $roomName")
            val room = try {
                dailyVideoService.getOrCreateRoom(roomName, maxParticipants = 2)
            } catch (e: Exception) {
                logger.error("Failed to get or create room: ${e.message}", e)
                throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to get or create room: ${e.message}",
                    e
                )
            }
            logger.info("Room created/retrieved: ${room.url}")

            // Generate user info (reuse already-fetched profile)
            val userId = if (isDoctor && doctor != null) {
                "doctor-${doctor.id}"
            } else if (isPatient && patient != null) {
                "patient-${patient.id}"
            } else {
                logger.error("Unable to determine user identity - isDoctor: $isDoctor, doctor: ${doctor != null}, isPatient: $isPatient, patient: ${patient != null}")
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to determine user identity")
            }

            val userName = if (isDoctor && doctor != null) {
                "${doctor.firstName} ${doctor.lastName}".trim().takeIf { it.isNotBlank() } ?: "Doctor ${doctor.id}"
            } else if (isPatient && patient != null) {
                patient.fullName.takeIf { it.isNotBlank() } ?: "Patient ${patient.id}"
            } else {
                logger.error("Unable to determine user name")
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to determine user name")
            }
            
            if (userName.isBlank()) {
                logger.error("User name is blank after processing")
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "User name cannot be blank")
            }

            logger.info("Generating token for userId: $userId, userName: $userName, isOwner: $isDoctor")

            // Generate token (doctor is owner)
            val token = try {
                dailyVideoService.generateToken(
                    roomName = roomName,
                    userId = userId,
                    userName = userName.trim(),
                    isOwner = isDoctor,
                    expiresInHours = 2
                )
            } catch (e: Exception) {
                logger.error("Failed to generate Daily.co token: ${e.message}", e)
                throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to generate token: ${e.message}",
                    e
                )
            }
            
            logger.info("Token generated successfully: token present=${token.token != null}")

            // Ensure we always have a room URL (fallback should have constructed it, but double-check)
            var roomUrl = room.url
            if (roomUrl == null || roomUrl.isBlank()) {
                logger.warn("Room URL is null/blank, constructing fallback URL")
                // Last resort: construct URL from domain and room name
                roomUrl = "https://shifauz.daily.co/$roomName"
            }

            logger.info("Returning video token response: roomUrl=$roomUrl, roomName=$roomName")
            return VideoTokenResponse(
                token = token.token ?: run {
                    logger.error("Token is null after generation")
                    throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate token")
                },
                roomUrl = roomUrl,
                roomName = roomName
            )
        } catch (e: ResponseStatusException) {
            // Re-throw ResponseStatusException as-is (these are intentional HTTP errors)
            logger.debug("ResponseStatusException: ${e.statusCode} - ${e.reason}")
            throw e
        } catch (e: Exception) {
            // Log unexpected errors and return 500
            logger.error("Unexpected error generating video token: ${e.message}", e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to generate video token: ${e.message ?: e.javaClass.simpleName}",
                e
            )
        }
    }

    /**
     * Get room info (optional endpoint)
     */
    @GetMapping("/room/{roomName}")
    fun getRoomInfo(
        @AuthenticationPrincipal principal: Any?,
        @PathVariable roomName: String
    ): Map<String, Any> {
        if (principal == null) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
        }

        val room = dailyVideoService.getOrCreateRoom(roomName)
        return mapOf(
            "roomName" to (room.name ?: roomName),
            "roomUrl" to (room.url ?: ""),
            "id" to (room.id ?: "")
        )
    }
}
