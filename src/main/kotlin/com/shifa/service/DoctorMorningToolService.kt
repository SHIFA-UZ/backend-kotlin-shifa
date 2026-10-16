package com.shifa.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.shifa.domain.PatientVisitFact
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ClinicVisitPatternRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.PatientVisitFactRepository
import com.shifa.web.dto.PatientDocumentDto
import kotlin.math.max
import kotlin.math.min
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
    private val patientDocumentService: PatientDocumentService,
) {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    /** Compact labels on UI cards (ok to abbreviate months). */
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
    /** Full month names for spokenHint — TTS spells "Oct" as O-C-T. */
    private val speakableDateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy")
    private val speakableDayDateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM")
    private val json = jacksonObjectMapper()

    /** Tools whose payloads should become UI cards on the client. */
    fun isCardTool(toolName: String): Boolean = toolName in CARD_TOOLS

    @Transactional(readOnly = true)
    fun execute(doctor: DoctorProfile, toolName: String, args: Map<String, Any?>): Map<String, Any?> {
        return when (toolName) {
            "get_today_schedule" -> getTodaySchedule(doctor)
            "list_free_slots" -> listFreeSlots(doctor, args)
            "list_patient_appointments" -> listPatientAppointments(doctor, args)
            "brief_patient" -> briefPatient(doctor, args)
            "get_clinic_patterns" -> getClinicPatterns(doctor)
            "get_most_visited_patients" -> getMostVisitedPatients(doctor, args)
            "search_patients" -> searchPatients(doctor, args)
            "propose_appointment" -> proposeAppointment(doctor, args)
            "propose_reschedule" -> proposeReschedule(doctor, args)
            "list_draft_visit_facts" -> listDraftVisitFacts(doctor, args)
            "list_patient_documents" -> listPatientDocuments(doctor, args)
            "open_patient_document" -> openPatientDocument(doctor, args)
            "open_dental_chart" -> openDentalChart(doctor, args)
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

    /** Free/open bookable times for a day — not the booked appointment list. */
    @Transactional(readOnly = true)
    fun listFreeSlots(doctor: DoctorProfile, args: Map<String, Any?>): Map<String, Any?> {
        val zone = zoneOf(doctor)
        val date = resolveDateArg(args["date"] as? String, zone) ?: LocalDate.now(zone)
        val partOfDay = (args["partOfDay"] as? String)?.trim()?.lowercase()
        val slots = patientDaySlotsService.availableSlotsForDay(doctor, date)
            .mapNotNull { slot ->
                val startZ = Instant.parse(slot.startAt).atZone(zone)
                val hour = startZ.hour
                val keep = when (partOfDay) {
                    "morning", "утро", "ertalab" -> hour < 12
                    "afternoon", "день", "после обеда", "tushdan keyin" -> hour in 12..16
                    "evening", "вечер", "kechqurun" -> hour >= 17
                    else -> true
                }
                if (!keep) return@mapNotNull null
                mapOf(
                    "startAt" to slot.startAt,
                    "endAt" to slot.endAt,
                    "slotMinutes" to slot.slotMinutes,
                    "startLocal" to startZ.format(timeFmt),
                    "locationId" to slot.locationId,
                    "locationLabel" to slot.locationLabel,
                )
            }
            .take(24)
        val times = slots.mapNotNull { it["startLocal"]?.toString() }
        val dayPartLabel = when (partOfDay) {
            "morning", "утро", "ertalab" -> " morning"
            "afternoon", "день", "после обеда", "tushdan keyin" -> " afternoon"
            "evening", "вечер", "kechqurun" -> " evening"
            else -> ""
        }
        // Keep card slots full; spokenHint stays short so TTS is not a time laundry list.
        val spokenDay = speakableDayDate(date)
        val spokenHint = when {
            slots.isEmpty() ->
                "You have no free$dayPartLabel slots on $spokenDay."
            slots.size == 1 ->
                "You have 1 free$dayPartLabel slot on $spokenDay: ${times.first()}."
            else ->
                "You have ${slots.size} free$dayPartLabel slots on $spokenDay. Which time works?"
        }
        return mapOf(
            "cardType" to "free_slots",
            "date" to date.toString(),
            "dateLabel" to date.format(dateFmt),
            "dayOfWeek" to date.dayOfWeek.name,
            "partOfDay" to partOfDay,
            "count" to slots.size,
            "slots" to slots,
            "spokenHint" to spokenHint,
        )
    }

    /**
     * Booked appointments for one patient in a date range (this doctor).
     * Use for "how many / which appointments with him next week" — not get_today_schedule.
     */
    @Transactional(readOnly = true)
    fun listPatientAppointments(doctor: DoctorProfile, args: Map<String, Any?>): Map<String, Any?> {
        val patientId = (args["patientId"] as? Number)?.toLong()
            ?: return mapOf(
                "cardType" to "patient_appointments",
                "error" to "patientId is required",
                "spokenHint" to "Which patient should I check appointments for?",
            )
        val patient = patientProfileRepository.findById(patientId).orElse(null)
            ?: return mapOf(
                "cardType" to "patient_appointments",
                "error" to "patient not found",
                "spokenHint" to "I could not find that patient.",
            )
        val zone = zoneOf(doctor)
        val range = resolveDateRange(args["date"] as? String, zone)
        val start = range.start.atStartOfDay(zone).toInstant()
        val end = range.endExclusive.atStartOfDay(zone).toInstant()
        val appointments = appointmentRepository
            .findByPatientIdAndDoctorIdOrderByStartAtDesc(patientId, doctor.id)
            .filter { it.status.name != "CANCELLED" }
            .filter { !it.startAt.isBefore(start) && it.startAt.isBefore(end) }
            .sortedBy { it.startAt }

        val patientName = patient.fullName.ifBlank { "this patient" }
        val rows = appointments.map { a ->
            val local = a.startAt.atZone(zone)
            mapOf(
                "appointmentId" to a.id,
                "patientId" to patientId,
                "patientName" to patientName,
                "date" to local.toLocalDate().toString(),
                "dateLabel" to local.format(dateFmt),
                "dayOfWeek" to local.dayOfWeek.name,
                "startLocal" to local.format(timeFmt),
                "startAt" to a.startAt.toString(),
                "status" to a.status.name,
                "isVideo" to a.location.contains("video", ignoreCase = true),
                "locationLabel" to a.location.takeIf { it.isNotBlank() },
            )
        }
        val spokenHint = buildPatientAppointmentsHint(
            patientName = patientName,
            rangeSpoken = range.spokenLabel,
            appointments = appointments,
            zone = zone,
        )
        return mapOf(
            "cardType" to "patient_appointments",
            "patientId" to patientId,
            "patientName" to patientName,
            "rangeStart" to range.start.toString(),
            "rangeEnd" to range.endExclusive.minusDays(1).toString(),
            "rangeLabel" to range.cardLabel,
            "count" to appointments.size,
            "appointments" to rows,
            "spokenHint" to spokenHint,
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
            val local = last.startAt.atZone(zone)
            mapOf(
                "appointmentId" to last.id,
                "date" to local.format(dateFmt),
                "speakableDate" to speakableDate(local.toLocalDate()),
                "startLocal" to local.format(timeFmt),
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
            "spokenHint" to buildBriefingHint(
                lastVisit = lastVisitSummary,
                timeline = timeline,
                briefingText = briefing?.briefing,
                visitCount = prior.size,
            ),
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
        if (page.content.isNotEmpty()) {
            val matches = page.content.map { p -> patientMatchRow(p) }
            val spokenHint = when {
                matches.size == 1 -> "Found ${matches.first()["patientName"]}."
                else -> "Found ${matches.size} patients matching \"$query\". Which one?"
            }
            return mapOf(
                "cardType" to "patient_search",
                "query" to query,
                "fuzzy" to false,
                "matches" to matches,
                "spokenHint" to spokenHint,
            )
        }

        // Exact substring miss (e.g. Botil vs Botir) — suggest closest roster names.
        val roster = patientProfileRepository.findClinicRosterScopedToDoctor(
            doctor.id,
            null,
            PageRequest.of(0, 300),
        )
        val ranked = roster.content
            .map { p -> p to nameSimilarity(query, p.fullName) }
            .filter { (_, score) -> score >= FUZZY_NAME_THRESHOLD }
            .sortedByDescending { it.second }
            .take(5)
        val matches = ranked.map { (p, score) ->
            patientMatchRow(p) + ("score" to String.format("%.2f", score))
        }
        val spokenHint = when {
            matches.isEmpty() ->
                "No patients matched \"$query\" in your roster."
            matches.size == 1 ->
                "No exact match for \"$query\". Did you mean ${matches.first()["patientName"]}?"
            else -> {
                val names = matches.take(3).joinToString(", ") { it["patientName"].toString() }
                "No exact match for \"$query\". Closest matches: $names. Which one did you mean?"
            }
        }
        return mapOf(
            "cardType" to "patient_search",
            "query" to query,
            "fuzzy" to matches.isNotEmpty(),
            "matches" to matches,
            "spokenHint" to spokenHint,
        )
    }

    private fun patientMatchRow(p: PatientProfile): Map<String, Any?> = mapOf(
        "patientId" to p.id,
        "patientName" to p.fullName.ifBlank { "Patient" },
        "phone" to (p.phone ?: ""),
    )

    /**
     * Best similarity of [query] against the full name and each name token.
     * Tuned so a one-letter typo (Botil → Botir) still ranks highly.
     */
    private fun nameSimilarity(query: String, fullName: String): Double {
        val q = normalizeName(query)
        val name = normalizeName(fullName)
        if (q.isEmpty() || name.isEmpty()) return 0.0
        if (name == q) return 1.0
        if (name.contains(q) || q.contains(name)) return 0.92

        var best = stringSimilarity(q, name)
        for (token in name.split(' ').filter { it.isNotBlank() }) {
            best = max(best, stringSimilarity(q, token))
            // Spelled first name vs longer token prefix (e.g. botir vs botirov)
            if (token.length >= q.length && q.length >= 3) {
                best = max(best, stringSimilarity(q, token.take(q.length)))
            }
        }
        // Multi-word query: score each query token against best name token.
        val qTokens = q.split(' ').filter { it.length >= 2 }
        if (qTokens.size > 1) {
            val nameTokens = name.split(' ').filter { it.isNotBlank() }
            val avg = qTokens.map { qt ->
                nameTokens.maxOfOrNull { nt -> stringSimilarity(qt, nt) } ?: 0.0
            }.average()
            best = max(best, avg)
        }
        return best
    }

    private fun stringSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 0.0
        val dist = levenshtein(a, b)
        // Hard allow small typos on short names (Botil/Botir).
        if (dist == 1 && min(a.length, b.length) >= 4) return 0.88
        if (dist == 2 && min(a.length, b.length) >= 6) return 0.78
        return 1.0 - dist.toDouble() / maxLen
    }

    private fun normalizeName(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost,
                )
            }
            for (j in prev.indices) prev[j] = curr[j]
        }
        return prev[b.length]
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
        val preferredTime = resolveTimeArg(
            (args["time"] as? String) ?: (args["preferredTime"] as? String),
        )
        val partOfDay = (args["partOfDay"] as? String)?.trim()?.lowercase()
        val allSlots = patientDaySlotsService.availableSlotsForDay(doctor, date)
            .mapNotNull { slot ->
                val startZ = Instant.parse(slot.startAt).atZone(zone)
                val hour = startZ.hour
                val keep = when (partOfDay) {
                    "morning", "утро", "ertalab" -> hour < 12
                    "afternoon", "день", "после обеда", "tushdan keyin" -> hour in 12..16
                    "evening", "вечер", "kechqurun" -> hour >= 17
                    else -> true
                }
                if (!keep) return@mapNotNull null
                mapOf(
                    "startAt" to slot.startAt,
                    "endAt" to slot.endAt,
                    "slotMinutes" to slot.slotMinutes,
                    "startLocal" to startZ.format(timeFmt),
                    "locationId" to slot.locationId,
                    "locationLabel" to slot.locationLabel,
                )
            }
        val preferredSlot = preferredTime?.let { want ->
            allSlots.firstOrNull { (it["startLocal"] as String) == want.format(timeFmt) }
        }
        val slots = when {
            preferredSlot != null ->
                listOf(preferredSlot) + allSlots.filter { it["startAt"] != preferredSlot["startAt"] }.take(11)
            else -> allSlots.take(12)
        }

        val spokenDay = speakableDayDate(date)
        val spokenHint = when {
            allSlots.isEmpty() ->
                "No free slots on $spokenDay for ${patient.fullName}. Please choose another day."
            preferredTime != null && preferredSlot == null ->
                "${preferredTime.format(timeFmt)} is not free for ${patient.fullName} on $spokenDay. Here are other open times."
            preferredSlot != null ->
                "Booking ${patient.fullName} at ${preferredSlot["startLocal"]} on $spokenDay."
            else ->
                "I found ${slots.size} free slots on $spokenDay for ${patient.fullName}. Which time works?"
        }

        return mapOf(
            "cardType" to "propose_appointment",
            "patientId" to patientId,
            "patientName" to patient.fullName.ifBlank { "Patient" },
            "date" to date.toString(),
            "dateLabel" to date.format(dateFmt),
            "speakableDateLabel" to spokenDay,
            "dayOfWeek" to date.dayOfWeek.name,
            "isVideo" to isVideo,
            "reason" to (args["reason"] as? String),
            "requestedTime" to preferredTime?.format(timeFmt),
            "partOfDay" to partOfDay,
            "preferredSlot" to preferredSlot,
            "autoConfirm" to (preferredSlot != null),
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

        val preferredTime = resolveTimeArg(
            (args["time"] as? String) ?: (args["preferredTime"] as? String),
        )
        val allSlots = patientDaySlotsService.availableSlotsForDay(
            doctor,
            date,
            excludeAppointmentId = appointment.id,
        ).map { slot ->
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
        val preferredSlot = preferredTime?.let { want ->
            allSlots.firstOrNull { (it["startLocal"] as String) == want.format(timeFmt) }
        }
        val slots = when {
            preferredSlot != null ->
                listOf(preferredSlot) + allSlots.filter { it["startAt"] != preferredSlot["startAt"] }.take(11)
            else -> allSlots.take(12)
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
            "speakableDateLabel" to speakableDayDate(date),
            "requestedTime" to preferredTime?.format(timeFmt),
            "preferredSlot" to preferredSlot,
            "autoConfirm" to (preferredSlot != null),
            "slots" to slots,
            "spokenHint" to when {
                allSlots.isEmpty() ->
                    "No free slots on ${speakableDayDate(date)} to move $patientName. Please choose another day."
                preferredTime != null && preferredSlot == null ->
                    "${preferredTime.format(timeFmt)} is not free on ${speakableDayDate(date)}. Here are other open times."
                preferredSlot != null ->
                    "Moving $patientName to ${preferredSlot["startLocal"]} on ${speakableDayDate(date)}."
                else ->
                    "$patientName is currently at ${speakableDayDate(currentLocal.toLocalDate())} ${currentLocal.format(timeFmt)}. Which new time?"
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

    @Transactional(readOnly = true)
    fun listPatientDocuments(doctor: DoctorProfile, args: Map<String, Any?>): Map<String, Any?> {
        val patientId = (args["patientId"] as? Number)?.toLong()
            ?: return mapOf(
                "cardType" to "patient_documents",
                "error" to "patientId is required",
                "spokenHint" to "Which patient should I list documents for?",
            )
        val patient = patientProfileRepository.findById(patientId).orElse(null)
            ?: return mapOf(
                "cardType" to "patient_documents",
                "error" to "Patient not found",
                "spokenHint" to "I could not find that patient.",
            )
        val query = (args["query"] as? String)?.trim().orEmpty()
        val docs = patientDocumentService.list(patientId, doctor.id)
            .filter { it.canView }
            .let { list ->
                if (query.isBlank()) list
                else {
                    val q = query.lowercase()
                    list.filter { d ->
                        d.title.lowercase().contains(q) ||
                            (d.category?.lowercase()?.contains(q) == true)
                    }.ifEmpty { list }
                }
            }
        val patientName = patient.fullName.ifBlank { "Patient" }
        return mapOf(
            "cardType" to "patient_documents",
            "patientId" to patientId,
            "patientName" to patientName,
            "query" to query.ifBlank { null },
            "documents" to docs.map { d -> documentRow(d) },
            "spokenHint" to when {
                docs.isEmpty() ->
                    "There are no viewable documents on file for $patientName yet."
                docs.size == 1 ->
                    "I found 1 document for $patientName: ${docs.first().title}. Opening it in chat."
                else ->
                    "I found ${docs.size} documents for $patientName. They are listed in the chat."
            },
            // Auto-open when only one match so voice "open the X-ray" feels instant.
            "autoOpenDocumentId" to docs.singleOrNull()?.id,
        )
    }

    @Transactional(readOnly = true)
    fun openPatientDocument(doctor: DoctorProfile, args: Map<String, Any?>): Map<String, Any?> {
        val patientId = (args["patientId"] as? Number)?.toLong()
            ?: return mapOf(
                "cardType" to "document_viewer",
                "error" to "patientId is required",
                "spokenHint" to "Which patient's document should I open?",
            )
        val patient = patientProfileRepository.findById(patientId).orElse(null)
            ?: return mapOf(
                "cardType" to "document_viewer",
                "error" to "Patient not found",
                "spokenHint" to "I could not find that patient.",
            )
        val patientName = patient.fullName.ifBlank { "Patient" }
        val docs = patientDocumentService.list(patientId, doctor.id).filter { it.canView }
        if (docs.isEmpty()) {
            return mapOf(
                "cardType" to "document_viewer",
                "patientId" to patientId,
                "patientName" to patientName,
                "error" to "No documents",
                "spokenHint" to "$patientName has no viewable documents yet.",
            )
        }

        val documentId = (args["documentId"] as? Number)?.toLong()
        val query = (args["query"] as? String)?.trim().orEmpty()
        val match = when {
            documentId != null -> docs.firstOrNull { it.id == documentId }
            query.isNotBlank() -> {
                val q = query.lowercase()
                docs.firstOrNull { it.title.lowercase() == q }
                    ?: docs.firstOrNull { it.title.lowercase().contains(q) }
                    ?: docs.firstOrNull { it.category?.lowercase()?.contains(q) == true }
                    ?: docs.maxByOrNull { d -> titleSimilarity(d.title, query) }
                        ?.takeIf { titleSimilarity(it.title, query) >= 0.45 }
            }
            docs.size == 1 -> docs.first()
            else -> null
        }

        if (match == null) {
            return mapOf(
                "cardType" to "patient_documents",
                "patientId" to patientId,
                "patientName" to patientName,
                "documents" to docs.map { d -> documentRow(d) },
                "spokenHint" to if (query.isNotBlank()) {
                    "I could not match \"$query\". Here are ${docs.size} documents for $patientName — tap one to open in chat."
                } else {
                    "Which document for $patientName should I open? They are listed in the chat."
                },
            )
        }

        return mapOf(
            "cardType" to "document_viewer",
            "patientId" to patientId,
            "patientName" to patientName,
            "documentId" to match.id,
            "title" to match.title,
            "date" to match.date.toString(),
            "category" to match.category,
            "spokenHint" to "Opening ${match.title} for $patientName in the chat.",
        )
    }

    @Transactional(readOnly = true)
    fun openDentalChart(doctor: DoctorProfile, args: Map<String, Any?>): Map<String, Any?> {
        val patientId = (args["patientId"] as? Number)?.toLong()
            ?: return mapOf(
                "cardType" to "dental_chart",
                "error" to "patientId is required",
                "spokenHint" to "Which patient's dental diagram should I open?",
            )
        val patient = patientProfileRepository.findById(patientId).orElse(null)
            ?: return mapOf(
                "cardType" to "dental_chart",
                "error" to "Patient not found",
                "spokenHint" to "I could not find that patient.",
            )
        val patientName = patient.fullName.ifBlank { "Patient" }
        val appointmentIdArg = (args["appointmentId"] as? Number)?.toLong()
        val appointments = appointmentRepository
            .findByPatientIdAndDoctorIdOrderByStartAtDesc(patientId, doctor.id)
            .filter { it.status.name != "CANCELLED" }

        val target = when {
            appointmentIdArg != null ->
                appointments.firstOrNull { it.id == appointmentIdArg }
            else ->
                appointments.firstOrNull { !it.dentalDocumentation.isNullOrBlank() }
                    ?: appointments.firstOrNull()
        }

        if (target == null) {
            return mapOf(
                "cardType" to "dental_chart",
                "patientId" to patientId,
                "patientName" to patientName,
                "error" to "No appointments",
                "spokenHint" to "$patientName has no visits on your calendar yet, so there is no dental diagram.",
            )
        }

        val raw = target.dentalDocumentation?.trim().orEmpty()
        if (raw.isEmpty()) {
            // Fall back: open a document whose title/category looks dental.
            val dentalDocs = patientDocumentService.list(patientId, doctor.id)
                .filter { it.canView }
                .filter { d ->
                    val hay = "${d.title} ${d.category.orEmpty()}".lowercase()
                    listOf("dental", "odontogram", "tooth", "teeth", "зуб", "стомат", "tish")
                        .any { hay.contains(it) }
                }
            if (dentalDocs.isNotEmpty()) {
                val doc = dentalDocs.first()
                return mapOf(
                    "cardType" to "document_viewer",
                    "patientId" to patientId,
                    "patientName" to patientName,
                    "documentId" to doc.id,
                    "title" to doc.title,
                    "date" to doc.date.toString(),
                    "category" to doc.category,
                    "spokenHint" to
                        "No visit dental chart yet. Opening ${doc.title} for $patientName in the chat.",
                )
            }
            return mapOf(
                "cardType" to "dental_chart",
                "patientId" to patientId,
                "patientName" to patientName,
                "appointmentId" to target.id,
                "date" to target.startAt.atZone(zoneOf(doctor)).format(dateFmt),
                "documentation" to emptyMap<String, Any?>(),
                "toothServiceCounts" to emptyMap<String, Int>(),
                "dentition" to "permanent",
                "spokenHint" to
                    "There is no dental diagram saved for $patientName's latest visit yet.",
            )
        }

        val documentation: Map<String, Any?> = try {
            json.readValue(raw, object : TypeReference<MutableMap<String, Any?>>() {})
        } catch (_: Exception) {
            emptyMap()
        }
        val dentition = (documentation["dentition"] as? String)?.trim()?.lowercase()
            ?.takeIf { it == "primary" || it == "permanent" }
            ?: "permanent"
        val toothCounts = toothServiceCountsFromDocumentation(documentation)
        val marked = toothCounts.values.count { it > 0 }
        val visitDate = target.startAt.atZone(zoneOf(doctor)).toLocalDate()
        val dateLabel = visitDate.format(dateFmt)
        val spokenDay = speakableDayDate(visitDate)

        return mapOf(
            "cardType" to "dental_chart",
            "patientId" to patientId,
            "patientName" to patientName,
            "appointmentId" to target.id,
            "date" to dateLabel,
            "dentition" to dentition,
            "documentation" to documentation,
            "toothServiceCounts" to toothCounts,
            "notes" to (documentation["notes"] as? String),
            "spokenHint" to when {
                marked == 0 ->
                    "Here is the dental diagram for $patientName from $spokenDay."
                marked == 1 ->
                    "Here is the dental diagram for $patientName from $spokenDay — 1 tooth has recorded services."
                else ->
                    "Here is the dental diagram for $patientName from $spokenDay — $marked teeth have recorded services."
            },
        )
    }

    private fun documentRow(d: PatientDocumentDto): Map<String, Any?> = mapOf(
        "documentId" to d.id,
        "title" to d.title,
        "date" to d.date.toString(),
        "category" to d.category,
        "creatorLabel" to d.creatorLabel,
        "canView" to d.canView,
    )

    @Suppress("UNCHECKED_CAST")
    private fun toothServiceCountsFromDocumentation(documentation: Map<String, Any?>): Map<String, Int> {
        val teeth = documentation["teeth"] as? Map<*, *> ?: return emptyMap()
        val out = linkedMapOf<String, Int>()
        for ((rawKey, rawVal) in teeth) {
            val key = rawKey?.toString()?.trim().orEmpty()
            if (key.isEmpty() || key.equals("general", ignoreCase = true) ||
                key.equals("G", ignoreCase = true)
            ) {
                continue
            }
            val count = when (rawVal) {
                is List<*> -> rawVal.size
                is Map<*, *> -> 1
                else -> 0
            }
            if (count > 0) out[key] = count
        }
        return out
    }

    /** Rough title similarity for document query matching (0..1). */
    private fun titleSimilarity(title: String, query: String): Double {
        val a = title.lowercase().trim()
        val b = query.lowercase().trim()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.85
        val aTokens = a.split(Regex("""\s+""")).filter { it.length > 1 }.toSet()
        val bTokens = b.split(Regex("""\s+""")).filter { it.length > 1 }.toSet()
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0.0
        val inter = aTokens.intersect(bTokens).size.toDouble()
        return inter / max(aTokens.size, bTokens.size).toDouble()
    }

    private fun resolveDateArg(raw: String?, zone: ZoneId): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim().lowercase()
            .replace(',', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        val today = LocalDate.now(zone)
        // "End of next week" → Friday of next calendar week (clinic default), not Monday.
        if (t.contains("end of next week") ||
            (t.contains("end of the week") && (t.contains("next") || t.contains("следующ") || t.contains("keyingi"))) ||
            (t.contains("end of") && t.contains("next week"))
        ) {
            return fridayOfNextWeek(today)
        }
        when (t) {
            "today", "сегодня", "bugun" -> return today
            "tomorrow", "завтра", "ertaga", "erta" -> return today.plusDays(1)
            "next week", "следующей неделе", "на следующей неделе", "keyingi hafta" ->
                return today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
            "this week", "этой неделе", "на этой неделе", "shu hafta" ->
                return today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        }
        weekdayFromText(t)?.let { dow ->
            val preferNextWeek = t.contains("next week") ||
                t.contains("next ") ||
                t.contains("следующ") ||
                t.contains("keyingi")
            return nextWeekday(today, dow, preferNextWeek = preferNextWeek)
        }
        return try {
            val parsed = LocalDate.parse(raw.trim())
            // Models sometimes invent a past year (e.g. 2023). Reject past days
            // for booking so we ask again instead of proposing a dead calendar.
            if (parsed.isBefore(today)) null else parsed
        } catch (_: Exception) {
            null
        }
    }

    /** Inclusive start / exclusive end for patient appointment listing. */
    private data class DateRangeWindow(
        val start: LocalDate,
        val endExclusive: LocalDate,
        val cardLabel: String,
        val spokenLabel: String,
    )

    private fun resolveDateRange(raw: String?, zone: ZoneId): DateRangeWindow {
        val today = LocalDate.now(zone)
        val t = raw?.trim()?.lowercase()
            ?.replace(',', ' ')
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()
        val isNextWeek = t.contains("next week") ||
            (t.contains("следующ") && t.contains("недел")) ||
            t.contains("keyingi hafta")
        // Full next Mon–Sun. "End of next week" is a single Friday via resolveDateArg.
        if (isNextWeek && !t.contains("end of")) {
            val start = today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
            return DateRangeWindow(
                start = start,
                endExclusive = start.plusWeeks(1),
                cardLabel = "Next week (${start.format(dateFmt)} – ${start.plusDays(6).format(dateFmt)})",
                spokenLabel = "next week",
            )
        }
        if (t.contains("this week") || t.contains("этой неделе") || t.contains("shu hafta")) {
            val start = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            return DateRangeWindow(
                start = start,
                endExclusive = start.plusWeeks(1),
                cardLabel = "This week (${start.format(dateFmt)} – ${start.plusDays(6).format(dateFmt)})",
                spokenLabel = "this week",
            )
        }
        val day = resolveDateArg(raw, zone) ?: today
        return DateRangeWindow(
            start = day,
            endExclusive = day.plusDays(1),
            cardLabel = day.format(dateFmt),
            spokenLabel = speakableDayDate(day),
        )
    }

    private fun fridayOfNextWeek(today: LocalDate): LocalDate {
        val nextMonday = today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
        return nextMonday.plusDays(4) // Friday
    }

    private fun speakableDate(date: LocalDate): String = date.format(speakableDateFmt)

    private fun speakableDayDate(date: LocalDate): String = date.format(speakableDayDateFmt)

    private fun buildPatientAppointmentsHint(
        patientName: String,
        rangeSpoken: String,
        appointments: List<com.shifa.domain.Appointment>,
        zone: ZoneId,
    ): String {
        if (appointments.isEmpty()) {
            return "You have no appointments with $patientName $rangeSpoken."
        }
        val count = appointments.size
        val base = if (count == 1) {
            "You have 1 appointment with $patientName $rangeSpoken"
        } else {
            "You have $count appointments with $patientName $rangeSpoken"
        }
        val details = appointments.take(4).joinToString("; ") { a ->
            val local = a.startAt.atZone(zone)
            "${local.dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }} at ${local.format(timeFmt)}"
        }
        val more = if (appointments.size > 4) ", and more" else ""
        return "$base: $details$more."
    }

    private fun weekdayFromText(t: String): java.time.DayOfWeek? {
        val map = listOf(
            java.time.DayOfWeek.MONDAY to listOf("monday", "понедельник", "dušanba", "dushanba"),
            java.time.DayOfWeek.TUESDAY to listOf("tuesday", "вторник", "seshanba"),
            java.time.DayOfWeek.WEDNESDAY to listOf("wednesday", "среда", "chorshanba"),
            java.time.DayOfWeek.THURSDAY to listOf("thursday", "четверг", "payshanba"),
            java.time.DayOfWeek.FRIDAY to listOf("friday", "пятница", "juma"),
            java.time.DayOfWeek.SATURDAY to listOf("saturday", "суббота", "shanba"),
            java.time.DayOfWeek.SUNDAY to listOf("sunday", "воскресенье", "yakshanba"),
        )
        for ((dow, keys) in map) {
            if (keys.any { t.contains(it) }) return dow
        }
        return null
    }

    /**
     * Next occurrence of [dow] after today.
     * With [preferNextWeek], skip this calendar week (Mon–Sun containing today).
     */
    private fun nextWeekday(
        today: LocalDate,
        dow: java.time.DayOfWeek,
        preferNextWeek: Boolean,
    ): LocalDate {
        var days = (dow.value - today.dayOfWeek.value + 7) % 7
        if (days == 0) days = 7
        var result = today.plusDays(days.toLong())
        if (preferNextWeek) {
            val endOfThisWeek =
                today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
            if (!result.isAfter(endOfThisWeek)) {
                result = result.plusWeeks(1)
            }
        }
        return result
    }

    /** Parse "10:00", "10", "10am", "10 a.m." into a LocalTime (minutes default 0). */
    private fun resolveTimeArg(raw: String?): LocalTime? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim().lowercase()
            .replace("o'clock", "")
            .replace("oclock", "")
            .replace(".", "")
            .trim()
        val ampm = when {
            t.endsWith("am") || t.endsWith("a m") -> false
            t.endsWith("pm") || t.endsWith("p m") -> true
            else -> null
        }
        val numeric = t
            .replace("am", "")
            .replace("pm", "")
            .replace("a m", "")
            .replace("p m", "")
            .trim()
        val match = Regex("""^(\d{1,2})(?::|h)?(\d{2})?$""").find(numeric) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        if (minute !in 0..59 || hour !in 0..23) return null
        if (ampm != null) {
            if (hour == 12) hour = 0
            if (ampm) hour += 12
            if (hour > 23) return null
        }
        return LocalTime.of(hour, minute)
    }

    private fun buildScheduleHint(count: Int, nextName: String?, minutesUntil: Long?): String {
        if (count == 0) return "You have no appointments today."
        val base = if (count == 1) "You have 1 appointment today." else "You have $count appointments today."
        if (nextName == null || minutesUntil == null) return base
        val whenPhrase = when {
            minutesUntil <= 0 -> "is starting now"
            minutesUntil == 1L -> "in about 1 minute"
            minutesUntil < 60 -> "in about $minutesUntil minutes"
            minutesUntil < 120 -> "in about 1 hour"
            else -> {
                val hours = (minutesUntil + 30) / 60
                "in about $hours hours"
            }
        }
        return when {
            minutesUntil <= 0 -> "$base The next one with $nextName $whenPhrase."
            else -> "$base The first is with $nextName $whenPhrase."
        }
    }

    private fun buildBriefingHint(
        lastVisit: Map<String, Any?>?,
        timeline: List<PatientVisitFact>,
        briefingText: String?,
        visitCount: Int,
    ): String {
        val fromBrief = spokenSummaryFromBriefing(briefingText)
        if (fromBrief != null) {
            val visitBit = lastVisit?.let {
                val d = it["speakableDate"] ?: it["date"]
                " Last visit $d at ${it["startLocal"]}."
            }.orEmpty()
            return fromBrief + visitBit
        }
        if (lastVisit == null && timeline.isEmpty()) {
            return "This looks like a new patient for you. There is no prior visit history yet."
        }
        val parts = mutableListOf<String>()
        if (visitCount > 0) {
            parts += "You have seen them $visitCount times."
        }
        lastVisit?.let {
            val d = it["speakableDate"] ?: it["date"]
            parts += "Last visit was on $d at ${it["startLocal"]}. Reason: ${it["reason"]}."
        }
        val complaints = timeline.filter {
            it.factType == PatientVisitFact.FactType.SYMPTOM || it.factType == PatientVisitFact.FactType.DIAGNOSIS
        }.take(3)
        if (complaints.isNotEmpty()) {
            parts += "Chart notes include: " + complaints.joinToString("; ") { it.text } + "."
        }
        val therapy = timeline.filter {
            it.factType == PatientVisitFact.FactType.THERAPY || it.factType == PatientVisitFact.FactType.MEDICATION
        }.take(2)
        if (therapy.isNotEmpty()) {
            parts += "Previously you planned: " + therapy.joinToString("; ") { it.text } + "."
        }
        return parts.joinToString(" ").ifBlank { "I have prior visits on file for this patient." }
    }

    /** Pull 1–2 plain sentences from markdown briefing for TTS / spokenHint. */
    private fun spokenSummaryFromBriefing(briefing: String?): String? {
        if (briefing.isNullOrBlank()) return null
        val plain = briefing
            .replace(Regex("""#{1,6}\s*"""), "")
            .replace(Regex("""\*\*?"""), "")
            .replace(Regex("""^\s*[-*]\s+""", RegexOption.MULTILINE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (plain.length < 24) return null
        val sentences = Regex("""[^.!?]+[.!?]""")
            .findAll(plain)
            .map { it.value.trim() }
            .filter { it.length in 20..180 }
            .take(2)
            .toList()
        if (sentences.isEmpty()) {
            return plain.take(220).trim().let { if (it.length < plain.length) "$it…" else it }
        }
        return sentences.joinToString(" ")
    }

    private fun zoneOf(doctor: DoctorProfile): ZoneId =
        try {
            ZoneId.of(doctor.timeZone.ifBlank { "Asia/Tashkent" })
        } catch (_: Exception) {
            ZoneId.of("Asia/Tashkent")
        }

    companion object {
        /** Minimum name similarity for fuzzy "Did you mean?" suggestions. */
        private const val FUZZY_NAME_THRESHOLD = 0.72

        private val CARD_TOOLS = setOf(
            "get_today_schedule",
            "list_free_slots",
            "list_patient_appointments",
            "search_patients",
            "propose_appointment",
            "propose_reschedule",
            "list_draft_visit_facts",
            "get_most_visited_patients",
            "get_clinic_patterns",
            "brief_patient",
            "list_patient_documents",
            "open_patient_document",
            "open_dental_chart",
        )
    }
}
