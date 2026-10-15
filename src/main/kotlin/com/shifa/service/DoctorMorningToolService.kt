package com.shifa.service

import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientVisitFact
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ClinicVisitPatternRepository
import com.shifa.repo.PatientProfileRepository
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
 * Read-only tools for the morning / Intelligence session.
 * Never books, reschedules, or commits chart facts — the client confirms those writes.
 */
@Service
class DoctorMorningToolService(
    private val appointmentRepository: AppointmentRepository,
    private val patientBriefingService: PatientBriefingService,
    private val visitFactRepository: PatientVisitFactRepository,
    private val clinicVisitPatternRepository: ClinicVisitPatternRepository,
    private val patientProfileRepository: PatientProfileRepository,
    private val patientDaySlotsService: PatientDaySlotsService,
) {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

    /** Tools whose payloads should become UI cards on the client. */
    fun isCardTool(toolName: String): Boolean = toolName in CARD_TOOLS

    @Transactional(readOnly = true)
    fun execute(doctor: DoctorProfile, toolName: String, args: Map<String, Any?>): Map<String, Any?> {
        return when (toolName) {
            "get_today_schedule" -> getTodaySchedule(doctor)
            "brief_patient" -> briefPatient(doctor, args)
            "get_clinic_patterns" -> getClinicPatterns(doctor)
            "get_most_visited_patients" -> getMostVisitedPatients(doctor, args)
            "search_patients" -> searchPatients(doctor, args)
            "propose_appointment" -> proposeAppointment(doctor, args)
            "propose_reschedule" -> proposeReschedule(doctor, args)
            "list_draft_visit_facts" -> listDraftVisitFacts(doctor, args)
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
            "cardType" to "schedule",
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
            "cardType" to "patient_brief",
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
            "cardType" to "clinic_patterns",
            "patterns" to patterns.map {
                mapOf(
                    "key" to it.patternKey,
                    "label" to it.patternLabel,
                    "factType" to it.factType.name,
                    "count" to it.occurrenceCount,
                )
            },
            "spokenHint" to if (patterns.isEmpty()) {
                "No clinic patterns recorded yet."
            } else {
                "Top patterns: " + patterns.take(3).joinToString("; ") { "${it.patternLabel} (${it.occurrenceCount})" } + "."
            },
        )
    }

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
            "cardType" to "most_visited",
            "since" to since.toString(),
            "daysBack" to daysBack,
            "patients" to patients,
            "spokenHint" to spokenHint,
        )
    }

    @Transactional(readOnly = true)
    fun searchPatients(doctor: DoctorProfile, args: Map<String, Any?>): Map<String, Any?> {
        val query = (args["query"] as? String)?.trim().orEmpty()
        if (query.isBlank()) {
            return mapOf(
                "cardType" to "patient_search",
                "query" to query,
                "matches" to emptyList<Map<String, Any?>>(),
                "spokenHint" to "Please give a patient name to search.",
            )
        }
        val page = patientProfileRepository.findClinicRosterScopedToDoctor(
            doctor.id,
            query,
            PageRequest.of(0, 8),
        )
        val matches = page.content.map { p ->
            mapOf(
                "patientId" to p.id,
                "patientName" to p.fullName.ifBlank { "Patient" },
                "phone" to (p.phone ?: ""),
            )
        }
        val spokenHint = when {
            matches.isEmpty() -> "No patients matched \"$query\" in your roster."
            matches.size == 1 -> "Found ${matches.first()["patientName"]}."
            else -> "Found ${matches.size} patients matching \"$query\". Ask the doctor to pick one."
        }
        return mapOf(
            "cardType" to "patient_search",
            "query" to query,
            "matches" to matches,
            "spokenHint" to spokenHint,
        )
    }

    @Transactional(readOnly = true)
    fun proposeAppointment(doctor: DoctorProfile, args: Map<String, Any?>): Map<String, Any?> {
        val patientId = (args["patientId"] as? Number)?.toLong()
            ?: return mapOf(
                "cardType" to "propose_appointment",
                "error" to "patientId is required",
                "spokenHint" to "I need a patient id before I can propose a booking.",
            )
        val patient = patientProfileRepository.findById(patientId).orElse(null)
            ?: return mapOf(
                "cardType" to "propose_appointment",
                "error" to "Patient not found",
                "spokenHint" to "I could not find that patient.",
            )

        val zone = zoneOf(doctor)
        val date = resolveDateArg(args["date"] as? String, zone)
            ?: return mapOf(
                "cardType" to "propose_appointment",
                "patientId" to patientId,
                "patientName" to patient.fullName,
                "error" to "date is required",
                "spokenHint" to "Which day should I check for free slots?",
            )

        val isVideo = (args["isVideo"] as? Boolean) == true
        val slots = patientDaySlotsService.availableSlotsForDay(doctor, date)
            .take(12)
            .map { slot ->
                val startZ = Instant.parse(slot.startAt).atZone(zone)
                mapOf(
                    "startAt" to slot.startAt,
                    "endAt" to slot.endAt,
                    "slotMinutes" to slot.slotMinutes,
                    "startLocal" to startZ.format(timeFmt),
                    "locationId" to slot.locationId,
                    "locationLabel" to slot.locationLabel,
                )
            }

        val spokenHint = when {
            slots.isEmpty() ->
                "No free slots on ${date.format(dateFmt)} for ${patient.fullName}. Ask them to pick another day."
            else ->
                "I found ${slots.size} free slots on ${date.format(dateFmt)} for ${patient.fullName}. They need to pick a time — do not claim it is booked."
        }

        return mapOf(
            "cardType" to "propose_appointment",
            "patientId" to patientId,
            "patientName" to patient.fullName.ifBlank { "Patient" },
            "date" to date.toString(),
            "dateLabel" to date.format(dateFmt),
            "isVideo" to isVideo,
            "reason" to (args["reason"] as? String),
            "slots" to slots,
            "spokenHint" to spokenHint,
        )
    }

    @Transactional(readOnly = true)
    fun proposeReschedule(doctor: DoctorProfile, args: Map<String, Any?>): Map<String, Any?> {
        val patientId = (args["patientId"] as? Number)?.toLong()
        val appointmentId = (args["appointmentId"] as? Number)?.toLong()
        val zone = zoneOf(doctor)
        val now = Instant.now()

        val appointment = when {
            appointmentId != null -> appointmentRepository.findById(appointmentId).orElse(null)
                ?.takeIf { it.doctor.id == doctor.id }
            patientId != null -> appointmentRepository
                .findByPatientIdAndDoctorIdOrderByStartAtDesc(patientId, doctor.id)
                .firstOrNull { it.status.name != "CANCELLED" && it.startAt.isAfter(now) }
            else -> null
        } ?: return mapOf(
            "cardType" to "propose_reschedule",
            "error" to "No upcoming appointment found",
            "spokenHint" to "I could not find an upcoming appointment to reschedule.",
        )

        val date = resolveDateArg(args["date"] as? String, zone)
            ?: appointment.startAt.atZone(zone).toLocalDate()

        val slots = patientDaySlotsService.availableSlotsForDay(
            doctor,
            date,
            excludeAppointmentId = appointment.id,
        ).take(12).map { slot ->
            val startZ = Instant.parse(slot.startAt).atZone(zone)
            mapOf(
                "startAt" to slot.startAt,
                "endAt" to slot.endAt,
                "slotMinutes" to slot.slotMinutes,
                "startLocal" to startZ.format(timeFmt),
                "locationId" to slot.locationId,
                "locationLabel" to slot.locationLabel,
            )
        }

        val patientName = appointment.patient.fullName.ifBlank { "Patient" }
        val currentLocal = appointment.startAt.atZone(zone)
        return mapOf(
            "cardType" to "propose_reschedule",
            "appointmentId" to appointment.id,
            "patientId" to appointment.patient.id,
            "patientName" to patientName,
            "currentStartAt" to appointment.startAt.toString(),
            "currentStartLocal" to currentLocal.format(timeFmt),
            "currentDateLabel" to currentLocal.format(dateFmt),
            "date" to date.toString(),
            "dateLabel" to date.format(dateFmt),
            "slots" to slots,
            "spokenHint" to when {
                slots.isEmpty() ->
                    "No free slots on ${date.format(dateFmt)} to move $patientName. Ask for another day."
                else ->
                    "$patientName is currently at ${currentLocal.format(dateFmt)} ${currentLocal.format(timeFmt)}. Pick a new time — nothing moves until they confirm."
            },
        )
    }

    @Transactional(readOnly = true)
    fun listDraftVisitFacts(doctor: DoctorProfile, args: Map<String, Any?>): Map<String, Any?> {
        val appointmentId = (args["appointmentId"] as? Number)?.toLong()
            ?: return mapOf(
                "cardType" to "draft_visit_facts",
                "error" to "appointmentId is required",
                "spokenHint" to "I need an appointment to list draft visit facts.",
            )
        val appointment = appointmentRepository.findById(appointmentId).orElse(null)
            ?: return mapOf(
                "cardType" to "draft_visit_facts",
                "error" to "Appointment not found",
                "spokenHint" to "That appointment was not found.",
            )
        if (appointment.doctor.id != doctor.id) {
            return mapOf(
                "cardType" to "draft_visit_facts",
                "error" to "Forbidden",
                "spokenHint" to "That appointment is not on your calendar.",
            )
        }

        val drafts = visitFactRepository
            .findByAppointmentIdAndStatus(appointmentId, PatientVisitFact.Status.DRAFT)
            .filter { it.doctorId == doctor.id && it.reflectionKept }

        return mapOf(
            "cardType" to "draft_visit_facts",
            "appointmentId" to appointmentId,
            "patientId" to appointment.patient.id,
            "patientName" to appointment.patient.fullName.ifBlank { "Patient" },
            "facts" to drafts.map { f ->
                mapOf(
                    "factId" to f.id,
                    "factType" to f.factType.name,
                    "text" to f.text,
                )
            },
            "spokenHint" to when {
                drafts.isEmpty() ->
                    "There are no draft visit facts ready to document for this visit."
                else ->
                    "There are ${drafts.size} draft facts. Ask the doctor to confirm Document everything — do not claim they are saved yet."
            },
        )
    }

    private fun resolveDateArg(raw: String?, zone: ZoneId): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim().lowercase()
        val today = LocalDate.now(zone)
        return when (t) {
            "today" -> today
            "tomorrow" -> today.plusDays(1)
            else -> try {
                LocalDate.parse(raw.trim())
            } catch (_: Exception) {
                null
            }
        }
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

    companion object {
        private val CARD_TOOLS = setOf(
            "get_today_schedule",
            "search_patients",
            "propose_appointment",
            "propose_reschedule",
            "list_draft_visit_facts",
            "get_most_visited_patients",
            "get_clinic_patterns",
            "brief_patient",
        )
    }
}
