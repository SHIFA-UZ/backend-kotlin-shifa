package com.shifa.web

import com.shifa.config.ScribeProperties
import com.shifa.domain.SubscriptionFeature
import com.shifa.repo.AppointmentRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.service.ScribePipelineService
import com.shifa.service.ScribeRecordingService
import com.shifa.service.SubscriptionTierService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Endpoints for consultation-related operations (e.g. AI scribe audio upload).
 */
@RestController
@RequestMapping("/api/consultations")
class ConsultationController(
    private val scribePipelineService: ScribePipelineService,
    private val scribeRecordingService: ScribeRecordingService,
    private val appointmentRepository: AppointmentRepository,
    private val scribeProps: ScribeProperties,
    private val subscriptionTierService: SubscriptionTierService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * POST /api/consultations/upload-recording
     * Upload in-person consultation audio for AI scribe processing.
     * Doctor only. Appointment must belong to the doctor.
     */
    @PostMapping("/upload-recording")
    fun uploadRecording(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam("audio") file: MultipartFile,
        @RequestParam("appointmentId") appointmentId: Long,
        @RequestParam("language", required = false) language: String?
    ): ResponseEntity<Map<String, Any>> {
        val doctor = principal.profile ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Doctor profile not found")

        subscriptionTierService.requireFeature(doctor.user, SubscriptionFeature.AI_NOTES)

        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio file is required")
        }
        if (file.size > scribeProps.maxAudioSizeBytes) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Audio file exceeds ${scribeProps.maxAudioSizeBytes / 1024 / 1024} MB limit"
            )
        }

        val appointment = appointmentRepository.findByIdWithDoctorAndPatient(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }

        if (appointment.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Appointment does not belong to this doctor")
        }

        val ext = file.originalFilename?.substringAfterLast('.')?.takeIf { it.length in 1..5 } ?: "wav"
        val tempPath = Path.of(scribeProps.tempDir).resolve("${UUID.randomUUID()}.$ext")
        Files.createDirectories(tempPath.parent)
        file.transferTo(tempPath.toFile())

        // Trigger async pipeline (pipeline will cleanup temp file when done)
        scribePipelineService.processInPersonRecording(
            appointmentId = appointmentId,
            doctorId = doctor.id,
            patientId = appointment.patient.id,
            localFilePath = tempPath,
            languageHint = language
        )

        val jobId = UUID.randomUUID().toString()
        log.info("Scribe recording uploaded: appointmentId={}, jobId={}", appointmentId, jobId)

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            mapOf(
                "message" to "Recording received. Notes will be ready in a few minutes.",
                "jobId" to jobId
            )
        )
    }
}
