package com.shifa.service

import com.shifa.ai.SpecialtyTaxonomy
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.DoctorReviewRepository
import com.shifa.web.dto.AiMessageDto
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PatientCopilotToolService(
    private val contextService: PatientCopilotContextService,
    private val doctorProfiles: DoctorProfileRepository,
    private val reviewRepository: DoctorReviewRepository,
    private val daySlotsService: PatientDaySlotsService,
    private val bookingService: PatientCopilotBookingService
) {
    data class ToolCall(
        val name: String,
        val arguments: Map<String, Any?> = emptyMap()
    )

    fun execute(
        patient: PatientProfile,
        messages: List<AiMessageDto>,
        toolCall: ToolCall
    ): Map<String, Any?> {
        return when (toolCall.name) {
            "get_patient_context" -> {
                val latestUser = messages.asReversed().firstOrNull { it.role == "user" }?.content.orEmpty()
                val intent = contextService.detectIntent(latestUser)
                contextService.getPatientContextJson(patient, intent)
            }
            "get_patient_documents" -> {
                val include = (toolCall.arguments["includeSnippets"] as? Boolean) ?: true
                val limit = (toolCall.arguments["limit"] as? Number)?.toInt() ?: 5
                contextService.getPatientDocumentsJson(patient, include, limit)
            }
            "find_doctors" -> {
                val specialties = (toolCall.arguments["specialties"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                val symptoms = (toolCall.arguments["symptoms"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                findDoctors(patient, specialties, symptoms)
            }
            "get_doctor_availability" -> {
                val doctorId = (toolCall.arguments["doctorId"] as? Number)?.toLong()
                    ?: return mapOf("error" to "doctorId is required")
                val doctor = doctorProfiles.findById(doctorId).orElse(null)
                    ?: return mapOf("error" to "doctor not found")
                val slot = daySlotsService.nextAvailableStartAt(doctor, Instant.now(), 28)
                mapOf("doctorId" to doctorId, "nextAvailableStartAt" to slot?.toString())
            }
            "book_appointment" -> {
                val doctorId = (toolCall.arguments["doctorId"] as? Number)?.toLong()
                    ?: return mapOf("error" to "doctorId is required")
                val preferred = (toolCall.arguments["preferredStartAtUtc"] as? String)?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                } ?: return mapOf("error" to "preferredStartAtUtc must be ISO-8601 UTC")
                val isVideo = (toolCall.arguments["isVideo"] as? Boolean) ?: false
                val consent = (toolCall.arguments["consentConfirmed"] as? Boolean) ?: false
                if (!consent) return mapOf("error" to "consentConfirmed is required")
                val booked = bookingService.bookNearestToPreferred(
                    patient = patient,
                    doctorId = doctorId,
                    preferredStartAt = preferred,
                    isVideo = isVideo,
                    reason = "Booked via Shifa AI tools",
                    consentConfirmed = true
                )
                mapOf(
                    "booked" to true,
                    "appointmentId" to booked.id,
                    "doctorId" to booked.doctorId,
                    "startAt" to booked.startAt
                )
            }
            else -> mapOf("error" to "unknown tool ${toolCall.name}")
        }
    }

    private fun findDoctors(
        patient: PatientProfile,
        specialties: List<String>,
        symptoms: List<String>
    ): Map<String, Any?> {
        val normalized = specialties.mapNotNull { SpecialtyTaxonomy.normalize(it) }.toSet()
        val candidates = mutableListOf<DoctorProfile>()
        val seen = mutableSetOf<Long>()
        val all = doctorProfiles.findAllByUserEnabled()
        if (normalized.isNotEmpty()) {
            for (d in all) {
                val p = d.profession?.trim().orEmpty()
                val pn = SpecialtyTaxonomy.normalize(p)
                if (pn != null && pn in normalized) {
                    if (seen.add(d.id)) candidates += d
                }
            }
        }
        if (candidates.isEmpty()) {
            for (term in symptoms) {
                for (d in doctorProfiles.searchWithFilters(term.take(80), null)) {
                    if (seen.add(d.id)) candidates += d
                }
            }
        }
        val now = Instant.now()
        val ranked = candidates.take(25).map { d ->
            val rating = reviewRepository.findAverageRatingByDoctorId(d.id) ?: 0.0
            val slot = daySlotsService.nextAvailableStartAt(d, now, 14)
            val specialtyScore = if (SpecialtyTaxonomy.normalize(d.profession) in normalized) 1.0 else 0.0
            val availabilityScore = if (slot != null) 1.0 else 0.0
            val ratingScore = (rating / 5.0).coerceIn(0.0, 1.0)
            val distanceScore = if (patient.latitude != null && patient.longitude != null && d.latitude != null && d.longitude != null) {
                val km = haversineKm(patient.latitude!!, patient.longitude!!, d.latitude!!, d.longitude!!)
                (1.0 - (km / 50.0)).coerceIn(0.0, 1.0)
            } else 0.5
            val total = specialtyScore * 0.55 + availabilityScore * 0.2 + ratingScore * 0.15 + distanceScore * 0.10
            mapOf(
                "id" to d.id,
                "fullName" to "${d.firstName} ${d.lastName}".trim(),
                "profession" to d.profession,
                "rating" to rating,
                "nextAvailableStartAt" to slot?.toString(),
                "score" to total
            )
        }.sortedByDescending { (it["score"] as Number).toDouble() }

        return mapOf("doctors" to ranked.take(12), "normalizedSpecialties" to normalized.toList())
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }
}

