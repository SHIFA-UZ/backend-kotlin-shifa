package com.shifa.service

import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientVisitFact
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ClinicVisitPatternRepository
import com.shifa.repo.PatientVisitFactRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tools for the spoken morning doctor session: today's schedule and patient briefing.
 */
@Service
class DoctorMorningToolService(
    private val appointmentRepository: AppointmentRepository,
    private val patientBriefingService: PatientBriefingService,
    private val visitFactRepository: PatientVisitFactRepository,
    private val clinicVisitPatternRepository: ClinicVisitPatternRepository,
) {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

    @Transactional(readOnly = true)
    fun execute(doctor: DoctorProfile, toolName: String, args: Map<String, Any?>): Map<String, Any?> {
        return when (toolName) {
            "get_today_schedule" -> getTodaySchedule(doctor)
            "brief_patient" -> briefPatient(doctor, args)
            "get_clinic_patterns" -> getClinicPatterns(doctor)
            "get_most_visited_patients" -> getMostVisitedPatients(doctor, args)
            else -> mapOf("error" to "Unknown tool: $toolName")
        }
    }

    @Transactional(readOnly = true)
    fun getTodaySchedule(doctor: DoctorProfile): Map<String, Any?> {
        val zone = zoneOf(doctor)
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        val appointments = appointmentRepository.findForDay(doctor.id, start, end)
        val now = Instant.now()

        val next = appointments.firstOrNull { it.startAt.isAfter(now) || Duration.between(now, it.startAt).abs().toMinutes() <= 5 }
            ?: appointments.firstOrNull { !it.startAt.isBefore(now.minusSeconds(300)) }

        val minutesUntil = next?.let {
            Duration.between(now, it.startAt).toMinutes()
        }

        return mapOf(
            "date" to today.toString(),
            "count" to appointments.size,
            "appointments" to appointments.map { a ->
                val localStart = a.startAt.atZone(zone)
                mapOf(
                    "appointmentId" to a.id,
                    "patientId" to a.patient.id,
                    "patientName" to a.patient.fullName.ifBlank { "Patient" },
                    "startLocal" to localStart.format(timeFmt),
                    "startAt" to a.startAt.toString(),
                    "status" to a.status.name,
                    "isVideo" to a.location.contains("video", ignoreCase = true),
                )
            },
            "next" to next?.let { a ->
                mapOf(
                    "appointmentId" to a.id,
                    "patientId" to a.patient.id,
                    "patientName" to a.patient.fullName.ifBlank { "Patient" },
                    "startLocal" to a.startAt.atZone(zone).format(timeFmt),
                    "minutesUntil" to minutesUntil,
                )
            },
            "spokenHint" to buildScheduleHint(appointments.size, next?.let {
                it.patient.fullName.ifBlank { "your next patient" }
            }, minutesUntil),
        )
    }

    @Transactional(readOnly = true)
    fun briefPatient(doctor: DoctorProfile, args: Map<String, Any?>): Map<String, Any?> {
        val patientId = (args["patientId"] as? Number)?.toLong()
            ?: return mapOf("error" to "patientId is required")

        val zone = zoneOf(doctor)
        val prior = appointmentRepository.findByPatientIdAndDoctorIdOrderByStartAtDesc(patientId, doctor.id)
            .filter { it.status.name != "CANCELLED" }
        val now = Instant.now()
        val last = prior.firstOrNull { it.endAt.isBefore(now) || it.startAt.isBefore(now) }
            ?: prior.drop(1).firstOrNull()
            ?: prior.firstOrNull()

        val timeline = visitFactRepository.findCommittedTimeline(doctor.id, patientId).take(12)
        val timelineLines = timeline.map { f ->
            val whenLabel = (f.committedAt ?: f.createdAt).atZone(zone).format(dateFmt)
            "${f.factType.name.lowercase()}: ${f.text} ($whenLabel)"
        }

        val briefing = try {
            patientBriefingService.generateBriefing(patientId, doctor.id, "uz")
        } catch (e: Exception) {
            null
        }

        val lastVisitSummary = if (last != null) {
            mapOf(
                "appointmentId" to last.id,
                "date" to last.startAt.atZone(zone).format(dateFmt),
                "startLocal" to last.startAt.atZone(zone).format(timeFmt),
                "reason" to (last.reason?.takeIf { it.isNotBlank() } ?: "not recorded"),
                "type" to if (last.location.contains("video", ignoreCase = true)) "video" else "in-person",
            )
        } else {
            null
        }

        return mapOf(
            "patientId" to patientId,
            "lastVisit" to lastVisitSummary,
            "visitCountWithYou" to prior.size,
            "timelineFacts" to timelineLines,
            "briefing" to (briefing?.briefing ?: "No prior chart notes available yet."),
            "documentCount" to (briefing?.documentCount ?: 0),
            "appointmentCount" to (briefing?.appointmentCount ?: prior.size),
            "spokenHint" to buildBriefingHint(lastVisitSummary, timeline),
        )
    }

    fun getClinicPatterns(doctor: DoctorProfile): Map<String, Any?> {
        val patterns = clinicVisitPatternRepository.findByDoctorIdOrderByOccurrenceCountDesc(doctor.id).take(8)
        return mapOf(
            "patterns" to patterns.map {
                mapOf(
                    "key" to it.patternKey,
                    "label" to it.patternLabel,
                    "factType" to it.factType.name,
                    "count" to it.occurrenceCount,
                )
            }
        )
    }

    /**
     * Rank patients by non-cancelled appointments with this doctor.
     * Args: limit (default 5), daysBack (default 365; 0 = all available history capped at 5y).
     */
    @Transactional(readOnly = true)
    fun getMostVisitedPatients(doctor: DoctorProfile, args: Map<String, Any?>): Map<String, Any?> {
        val limit = ((args["limit"] as? Number)?.toInt() ?: 5).coerceIn(1, 15)
        val daysBack = ((args["daysBack"] as? Number)?.toInt() ?: 365).coerceIn(0, 3650)
        val since = if (daysBack == 0) {
            Instant.now().minus(Duration.ofDays(3650))
        } else {
            Instant.now().minus(Duration.ofDays(daysBack.toLong()))
        }

        val rows = appointmentRepository.findMostVisitedPatients(
            doctor.id,
            since,
            PageRequest.of(0, limit),
        )

        val patients = rows.mapNotNull { row ->
            val patientId = (row.getOrNull(0) as? Number)?.toLong() ?: return@mapNotNull null
            val rawName = (row.getOrNull(1) as? String)?.trim()
            val name = if (rawName.isNullOrBlank()) "Patient" else rawName
            val visits = (row.getOrNull(2) as? Number)?.toLong() ?: 0L
            mapOf(
                "patientId" to patientId,
                "patientName" to name,
                "visitCount" to visits,
            )
        }

        val top = patients.firstOrNull()
        val spokenHint = when {
            patients.isEmpty() ->
                "You do not have ranked visit history yet - no non-cancelled appointments were found in this period."
            top != null && patients.size == 1 ->
                "Your most visited patient is ${top["patientName"]} with ${top["visitCount"]} visits."
            top != null -> {
                val rest = patients.drop(1).take(2).joinToString("; ") {
                    "${it["patientName"]} (${it["visitCount"]})"
                }
                "Your most visited patient is ${top["patientName"]} with ${top["visitCount"]} visits. Next: $rest."
            }
            else -> "No patients found."
        }

        return mapOf(
            "since" to since.toString(),
            "daysBack" to daysBack,
            "patients" to patients,
            "spokenHint" to spokenHint,
        )
    }

    private fun buildScheduleHint(count: Int, nextName: String?, minutesUntil: Long?): String {
        if (count == 0) return "You have no appointments today."
        val base = if (count == 1) "You have 1 appointment today." else "You have $count appointments today."
        if (nextName == null || minutesUntil == null) return base
        return when {
            minutesUntil <= 0 -> "$base The next one with $nextName is starting now."
            minutesUntil == 1L -> "$base The first is with $nextName in about 1 minute."
            else -> "$base The first is with $nextName in about $minutesUntil minutes."
        }
    }

    private fun buildBriefingHint(
        lastVisit: Map<String, Any?>?,
        timeline: List<PatientVisitFact>,
    ): String {
        if (lastVisit == null && timeline.isEmpty()) {
            return "This looks like a new patient for you. There is no prior visit history yet."
        }
        val parts = mutableListOf<String>()
        lastVisit?.let {
            parts += "Last visit was on ${it["date"]}. Reason: ${it["reason"]}."
        }
        val complaints = timeline.filter {
            it.factType == PatientVisitFact.FactType.SYMPTOM || it.factType == PatientVisitFact.FactType.DIAGNOSIS
        }.take(3)
        if (complaints.isNotEmpty()) {
            parts += "Chart notes include: " + complaints.joinToString("; ") { it.text } + "."
        }
        val therapy = timeline.filter { it.factType == PatientVisitFact.FactType.THERAPY || it.factType == PatientVisitFact.FactType.MEDICATION }
            .take(2)
        if (therapy.isNotEmpty()) {
            parts += "Previously you planned: " + therapy.joinToString("; ") { it.text } + "."
        }
        return parts.joinToString(" ").ifBlank { "I have prior visits on file for this patient." }
    }

    private fun zoneOf(doctor: DoctorProfile): ZoneId =
        try {
            ZoneId.of(doctor.timeZone.ifBlank { "Asia/Tashkent" })
        } catch (_: Exception) {
            ZoneId.of("Asia/Tashkent")
        }
}
