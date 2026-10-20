package com.shifa.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.domain.Appointment
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.shifa.domain.PatientVisitFact
import com.shifa.intelligence.appointment.AppointmentActionService
import com.shifa.intelligence.appointment.AppointmentIntelligenceService
import com.shifa.intelligence.appointment.AppointmentSpeechReference
import com.shifa.intelligence.appointment.CancellationIntent
import com.shifa.intelligence.appointment.ClinicCalendarDay
import com.shifa.intelligence.appointment.BookingTurnType
import com.shifa.intelligence.appointment.IntelligenceBookingWorkflowService
import com.shifa.intelligence.appointment.IntelligenceToolReferenceService
import com.shifa.intelligence.appointment.PendingPatientCandidate
import com.shifa.intelligence.appointment.RescheduleIntent
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ClinicVisitPatternRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.PatientVisitFactRepository
import com.shifa.web.dto.PatientDocumentDto
import kotlin.math.max
import kotlin.math.min
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Clock
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
    private val appointmentIntelligenceService: AppointmentIntelligenceService,
    private val appointmentActionService: AppointmentActionService,
    private val bookingWorkflowService: IntelligenceBookingWorkflowService,
    private val toolReferenceService: IntelligenceToolReferenceService,
    private val clock: Clock,
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

    @Transactional
    fun execute(
        doctor: DoctorProfile,
        toolName: String,
        args: Map<String, Any?>,
        priorAssistantMessages: List<String> = emptyList(),
        priorUserMessages: List<String> = emptyList(),
        latestUserText: String = "",
        focusPatientId: Long? = null,
        searchedPatientIds: Set<Long> = emptySet(),
        conversationId: String? = null,
    ): Map<String, Any?> {
        return when (toolName) {
            "get_today_schedule" -> getTodaySchedule(doctor).also { result ->
                @Suppress("UNCHECKED_CAST")
                val appointments = result["appointments"] as? List<Map<String, Any?>> ?: emptyList()
                toolReferenceService.storeSchedule(doctor.id, conversationId, appointments)
            }
            "list_free_slots" -> listFreeSlots(doctor, args)
            "list_patient_appointments" -> listPatientAppointments(doctor, args).also { result ->
                @Suppress("UNCHECKED_CAST")
                val appointments = result["appointments"] as? List<Map<String, Any?>> ?: emptyList()
                toolReferenceService.storeSchedule(
                    doctor.id,
                    conversationId,
                    appointments,
                    toolName = "list_patient_appointments",
                )
            }
            "query_appointment_intelligence" -> queryAppointmentIntelligence(
                doctor, args, priorAssistantMessages, latestUserText, focusPatientId, searchedPatientIds,
            )
            "preview_appointment_cancellation" -> previewCancellation(
                doctor,
                args,
                latestUserText,
                priorAssistantMessages,
                priorUserMessages,
                focusPatientId,
                searchedPatientIds,
                conversationId,
            )
            "confirm_appointment_cancellation" -> appointmentActionService.confirmCancellation(
                doctor, latestUserText, priorAssistantMessages, conversationId,
            )
            "open_appointment" -> openAppointment(
                doctor, args, latestUserText, focusPatientId, searchedPatientIds, conversationId, startIfNeeded = false,
            )
            "start_appointment" -> openAppointment(
                doctor, args, latestUserText, focusPatientId, searchedPatientIds, conversationId, startIfNeeded = true,
            )
            "complete_appointment" -> appointmentActionService.completeAppointment(
                doctor,
                (args["appointmentId"] as? Number)?.toLong(),
                priorAssistantMessages,
            )
            "request_appointment_signature" -> appointmentActionService.requestSignature(
                doctor,
                (args["appointmentId"] as? Number)?.toLong(),
                priorAssistantMessages,
            )
            "append_consultation_draft" -> appendConsultationDraft(
                doctor = doctor,
                args = args,
                latestUserText = latestUserText,
                priorAssistantMessages = priorAssistantMessages,
                conversationId = conversationId,
            )
            "brief_patient" -> briefPatient(doctor, args)
            "get_clinic_patterns" -> getClinicPatterns(doctor)
            "get_most_visited_patients" -> getMostVisitedPatients(doctor, args)
            "search_patients" -> searchPatients(doctor, args)
            "propose_appointment" -> proposeAppointment(doctor, args)
            "manage_booking_workflow" -> manageBookingWorkflow(
                doctor = doctor,
                args = args,
                focusPatientId = focusPatientId,
                conversationId = conversationId,
                latestUserText = latestUserText,
            )
            "propose_reschedule" -> when (args["turnType"]?.toString()?.uppercase()) {
                "CONFIRM" -> bookingWorkflowService.confirmReschedule(
                    doctor,
                    conversationId.orEmpty(),
                )
                "REJECT", "ABANDON" -> bookingWorkflowService.rejectReschedule(
                    doctor.id,
                    conversationId.orEmpty(),
                )
                else -> {
                    val proposal = proposeReschedule(
                        doctor, args, latestUserText, priorAssistantMessages, priorUserMessages, conversationId,
                    )
                    if (!conversationId.isNullOrBlank() && proposal["preferredSlot"] is Map<*, *>) {
                        bookingWorkflowService.attachRescheduleProposal(doctor, conversationId, proposal)
                    } else {
                        proposal
                    }
                }
            }
            "list_draft_visit_facts" -> listDraftVisitFacts(doctor, args)
            "list_patient_documents" -> listPatientDocuments(doctor, args)
            "open_patient_document" -> openPatientDocument(doctor, args)
            "open_dental_chart" -> openDentalChart(doctor, args)
            else -> mapOf("error" to "Unknown tool: $toolName")
        }
    }

    private fun manageBookingWorkflow(
        doctor: DoctorProfile,
        args: Map<String, Any?>,
        focusPatientId: Long?,
        conversationId: String?,
        latestUserText: String,
    ): Map<String, Any?> {
        val conversation = conversationId?.trim().orEmpty()
        if (conversation.isEmpty()) {
            return mapOf(
                "cardType" to "propose_appointment",
                "error" to "conversationId is required",
                "spokenHint" to "Please reopen this Intelligence chat and try the booking again.",
            )
        }
        val action = runCatching {
            BookingTurnType.valueOf(args["turnType"]?.toString()?.uppercase() ?: "SLOT_UPDATE")
        }.getOrDefault(BookingTurnType.AMBIGUOUS)
        var active = bookingWorkflowService.active(doctor.id, conversation)
        var resolvedPendingPatient = false
        if (active?.pendingQuestionType != null) {
            val candidates = bookingWorkflowService.pendingPatientCandidates(active)
            val suppliedName = (args["patientName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val selected = when {
                action == BookingTurnType.CONFIRM && candidates.size == 1 -> candidates.single()
                action == BookingTurnType.SLOT_UPDATE || action == BookingTurnType.CORRECT_SLOT ->
                    suppliedName?.let { name ->
                        val normalized = normalizeName(name)
                        candidates.singleOrNull {
                            val candidate = normalizeName(it.patientName)
                            candidate == normalized || candidate.contains(normalized) || normalized.contains(candidate)
                        }
                    }
                else -> null
            }
            if (selected != null) {
                active = bookingWorkflowService.resolvePendingPatient(
                    doctorId = doctor.id,
                    conversationId = conversation,
                    patientId = selected.patientId,
                )
                resolvedPendingPatient = active != null
            } else if (action == BookingTurnType.REJECT) {
                bookingWorkflowService.clearPendingPatient(doctor.id, conversation, clearPatientName = true)
                return mapOf(
                    "cardType" to "propose_appointment",
                    "workflowStatus" to "COLLECTING_DETAILS",
                    "spokenHint" to "Okay. Which patient should I book instead?",
                )
            } else if (action == BookingTurnType.CORRECT_SLOT && suppliedName != null) {
                active = bookingWorkflowService.clearPendingPatient(
                    doctor.id,
                    conversation,
                    clearPatientName = true,
                )
            } else {
                return pendingPatientCard(active, candidates)
            }
        }
        if (action == BookingTurnType.CONFIRM && !resolvedPendingPatient) {
            return bookingWorkflowService.confirm(doctor, conversation)
        }
        if (action == BookingTurnType.REJECT || action == BookingTurnType.ABANDON) {
            return bookingWorkflowService.reject(
                doctorId = doctor.id,
                conversationId = conversation,
                abandon = action == BookingTurnType.ABANDON,
            )
        }
        val trustedPatientId = (args["_trustedPatientId"] as? Number)?.toLong()
        var patientId = active?.patientId ?: focusPatientId ?: trustedPatientId
        var patientName = (args["patientName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: active?.patientName
        if (patientId == null && patientName != null) {
            val found = searchPatients(doctor, mapOf("query" to patientName))
            @Suppress("UNCHECKED_CAST")
            val matches = found["matches"] as? List<Map<String, Any?>> ?: emptyList()
            val usable = matches.size == 1 && (found["fuzzy"] != true || found["uniqueFirstName"] == true)
            if (!usable) {
                val candidates = matches.mapNotNull { row ->
                    val id = (row["patientId"] as? Number)?.toLong() ?: return@mapNotNull null
                    PendingPatientCandidate(
                        patientId = id,
                        patientName = row["patientName"]?.toString().orEmpty().ifBlank { "Patient" },
                    )
                }
                if (candidates.isEmpty()) {
                    bookingWorkflowService.saveDetails(
                        doctor = doctor,
                        conversationId = conversation,
                        patientId = null,
                        patientName = patientName,
                        date = active?.appointmentDate,
                        time = active?.appointmentTime,
                    )
                } else {
                    bookingWorkflowService.setPendingPatientQuestion(
                        doctor = doctor,
                        conversationId = conversation,
                        originalName = patientName,
                        candidates = candidates,
                        date = active?.appointmentDate,
                        time = active?.appointmentTime,
                    )
                }
                return LinkedHashMap(found).apply {
                    put("workflowStatus", "COLLECTING_DETAILS")
                    put("pendingQuestion", if (candidates.size == 1) "CONFIRM_PATIENT" else "CHOOSE_PATIENT")
                }
            }
            patientId = (matches.first()["patientId"] as? Number)?.toLong()
            patientName = matches.first()["patientName"]?.toString() ?: patientName
        }
        if (patientId == null) {
            bookingWorkflowService.saveDetails(
                doctor = doctor,
                conversationId = conversation,
                patientId = null,
                patientName = patientName,
                date = active?.appointmentDate,
                time = active?.appointmentTime,
            )
            return mapOf(
                "cardType" to "propose_appointment",
                "workflowStatus" to "COLLECTING_DETAILS",
                "spokenHint" to "Which patient should I book?",
            )
        }
        val date = (args["date"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: active?.appointmentDate?.toString()
        val time = (args["time"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: active?.appointmentTime?.toString()
        if (date == null) {
            bookingWorkflowService.saveDetails(
                doctor = doctor,
                conversationId = conversation,
                patientId = patientId,
                patientName = patientName,
                date = null,
                time = time?.let { resolveTimeArg(it) },
            )
            return mapOf(
                "cardType" to "propose_appointment",
                "patientId" to patientId,
                "patientName" to patientName,
                "workflowStatus" to "COLLECTING_DETAILS",
                "spokenHint" to "Which day should I book for $patientName?",
            )
        }
        val proposal = proposeAppointment(
            doctor,
            mapOf(
                "patientId" to patientId,
                "date" to date,
                "time" to time,
                "isVideo" to (args["isVideo"] == true),
                "reason" to args["reason"],
            ),
        )
        return bookingWorkflowService.attachProposal(doctor, conversation, proposal)
    }

    private fun pendingPatientCard(
        workflow: com.shifa.intelligence.appointment.IntelligenceBookingWorkflow,
        candidates: List<PendingPatientCandidate>,
    ): Map<String, Any?> {
        val rows = candidates.map {
            mapOf("patientId" to it.patientId, "patientName" to it.patientName)
        }
        val question = when {
            candidates.size == 1 -> "Did you mean ${candidates.single().patientName}?"
            candidates.isNotEmpty() -> "Which patient did you mean?"
            else -> "Which patient should I book?"
        }
        return mapOf(
            "cardType" to if (candidates.isEmpty()) "propose_appointment" else "patient_search",
            "workflowId" to workflow.id.toString(),
            "workflowStatus" to workflow.status.name,
            "pendingQuestion" to workflow.pendingQuestionType?.name,
            "matches" to rows,
            "spokenHint" to question,
        )
    }

    private fun looksLikeReadBack(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("read the note") || t.contains("read it back") || t.contains("show me the note") ||
            t.contains("show the documentation") || t.contains("what does the note say")
    }

    private fun previewCancellation(
        doctor: DoctorProfile,
        args: Map<String, Any?>,
        latestUserText: String,
        priorAssistantMessages: List<String>,
        priorUserMessages: List<String>,
        focusPatientId: Long?,
        searchedPatientIds: Set<Long>,
        conversationId: String?,
    ): Map<String, Any?> {
        val aboutPronoun = Regex("""\b(him|her|them|this patient)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(latestUserText)
        var patientId = (args["patientId"] as? Number)?.toLong()
        val name = if (CancellationIntent.isCountQuestion(latestUserText)) {
            ""
        } else {
            (args["patientName"] as? String)?.trim().orEmpty()
                .ifEmpty { CancellationIntent.patientName(latestUserText).orEmpty() }
        }
        if (name.isNotEmpty() && patientId == null) {
            val found = searchPatients(doctor, mapOf("query" to name))
            @Suppress("UNCHECKED_CAST")
            val matches = found["matches"] as? List<Map<String, Any?>> ?: emptyList()
            val usable = matches.size == 1 &&
                (found["fuzzy"] != true || found["uniqueFirstName"] == true)
            when {
                usable -> patientId = (matches.first()["patientId"] as? Number)?.toLong()
                // Several candidates: the doctor has to pick one, so the search card is the answer.
                matches.size > 1 -> return found
                // No such patient: stay in the cancellation and list what can be cancelled,
                // instead of answering with an empty roster search.
                else -> Unit
            }
        }
        if (patientId == null && aboutPronoun && searchedPatientIds.size == 1) {
            patientId = searchedPatientIds.first()
        }
        if (patientId == null && aboutPronoun && focusPatientId != null) {
            patientId = focusPatientId
        }
        var appointmentId = (args["appointmentId"] as? Number)?.toLong()
        if (appointmentId != null) {
            val row = appointmentRepository.findById(appointmentId).orElse(null)
            if (row == null || row.doctor.id != doctor.id) appointmentId = null
        }
        val shown = RescheduleIntent.shownIds(priorAssistantMessages)
        if (appointmentId == null && CancellationIntent.refersToShown(latestUserText)) {
            appointmentId = when {
                CancellationIntent.refersToLast(latestUserText) && shown.isNotEmpty() -> shown.last()
                shown.size == 1 -> shown.first()
                else -> null
            }
            if (appointmentId == null && shown.size > 1) return clarifyShownVisits(doctor, shown)
        }
        return appointmentActionService.previewCancellation(
            doctor = doctor,
            userText = CancellationIntent.rangeText(latestUserText, priorUserMessages),
            patientId = patientId,
            appointmentId = appointmentId,
            conversationId = conversationId,
        )
    }

    private fun clarifyShownVisits(doctor: DoctorProfile, ids: List<Long>): Map<String, Any?> {
        val zone = zoneOf(doctor)
        val rows = ids.mapNotNull { id ->
            val appointment = appointmentRepository.findById(id).orElse(null) ?: return@mapNotNull null
            if (appointment.doctor.id != doctor.id) return@mapNotNull null
            val local = appointment.startAt.atZone(zone)
            mapOf(
                "appointmentId" to appointment.id,
                "patientId" to appointment.patient.id,
                "patientName" to appointment.patient.fullName.ifBlank { "Patient" },
                "whenLabel" to "${local.format(speakableDayDateFmt)} at ${local.format(timeFmt)}",
            )
        }
        val names = rows.take(6).joinToString("; ") { "${it["patientName"]} ${it["whenLabel"]}" }
        return mapOf(
            "supported" to true,
            "status" to "AWAITING_CLARIFICATION",
            "cardType" to "cancel_preview",
            "appointments" to rows,
            "spokenHint" to "Which of those visits should I cancel? $names",
        )
    }

    private fun openAppointment(
        doctor: DoctorProfile,
        args: Map<String, Any?>,
        latestUserText: String,
        focusPatientId: Long?,
        searchedPatientIds: Set<Long>,
        conversationId: String?,
        startIfNeeded: Boolean,
    ): Map<String, Any?> {
        val name = (args["patientName"] as? String)?.trim().orEmpty()
        var patientId = (args["patientId"] as? Number)?.toLong()
        var resolvedName = name
        if (name.isNotEmpty() && patientId == null) {
            val found = searchPatients(doctor, mapOf("query" to name))
            @Suppress("UNCHECKED_CAST")
            val matches = found["matches"] as? List<Map<String, Any?>> ?: emptyList()
            if (matches.size != 1 || found["fuzzy"] == true) {
                rememberOpenPatientQuestion(doctor, conversationId, name, matches)
                return found
            }
            patientId = (matches.first()["patientId"] as? Number)?.toLong() ?: return found
            resolvedName = matches.first()["patientName"]?.toString().orEmpty().ifBlank { name }
        }
        val aboutFocus = Regex("""\b(him|her|them|this patient)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(latestUserText)
        if (patientId == null && aboutFocus && searchedPatientIds.size == 1) {
            patientId = searchedPatientIds.first()
        }
        if (patientId == null && aboutFocus) patientId = focusPatientId
        val target = when ((args["target"] as? String)?.lowercase()) {
            "documentation", "note" -> "documentation"
            "patient", "chart" -> "patient"
            else -> if (latestUserText.contains("document", ignoreCase = true) || startIfNeeded) {
                "documentation"
            } else {
                "appointment"
            }
        }
        val suppliedId = (args["appointmentId"] as? Number)?.toLong()
        val resolvedId = suppliedId ?: resolveTrustedAppointmentId(
            doctor = doctor,
            conversationId = conversationId,
            patientName = resolvedName.ifBlank { null },
            userText = latestUserText,
        )
        if (resolvedId == null && suppliedId == null) {
            val several = trustedAppointmentMatches(doctor, conversationId, resolvedName.ifBlank { null }, latestUserText)
            if (several.size > 1) return clarifyVisitOperation(several, "Which visit should I open?")
        }
        LOGGER.info(
            "intelligence_workflow operation=OPEN_APPOINTMENT conversationId={} patientId={} appointmentId={}",
            conversationId,
            patientId,
            resolvedId,
        )
        return appointmentActionService.openAppointment(
            doctor = doctor,
            userText = latestUserText,
            patientId = patientId,
            appointmentId = resolvedId,
            target = target,
            startIfNeeded = startIfNeeded,
        )
    }

    private fun appendConsultationDraft(
        doctor: DoctorProfile,
        args: Map<String, Any?>,
        latestUserText: String,
        priorAssistantMessages: List<String>,
        conversationId: String?,
    ): Map<String, Any?> {
        val suppliedId = (args["appointmentId"] as? Number)?.toLong()
        val speechName = (args["patientName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val matches = if (suppliedId == null) {
            trustedAppointmentMatches(doctor, conversationId, speechName, latestUserText)
        } else {
            emptyList()
        }
        if (suppliedId == null && matches.size > 1) {
            return clarifyVisitOperation(matches, "Which visit should I add that note to?")
        }
        val appointmentId = suppliedId ?: matches.singleOrNull()?.appointmentId
        val body = (args["text"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: com.shifa.intelligence.appointment.MorningVisitIntent.noteBody(latestUserText)
        LOGGER.info(
            "intelligence_workflow operation=DOCUMENT_ENCOUNTER conversationId={} appointmentId={} noteChars={} matches={}",
            conversationId,
            appointmentId,
            body.length,
            matches.size,
        )
        return appointmentActionService.appendDictation(
            doctor = doctor,
            userText = body,
            appointmentId = appointmentId,
            priorAssistantMessages = priorAssistantMessages,
            readBack = args["readBack"] == true || looksLikeReadBack(latestUserText),
        )
    }

    private fun rememberOpenPatientQuestion(
        doctor: DoctorProfile,
        conversationId: String?,
        originalName: String,
        matches: List<Map<String, Any?>>,
    ) {
        if (conversationId.isNullOrBlank()) return
        val candidates = matches.mapNotNull { row ->
            val id = (row["patientId"] as? Number)?.toLong() ?: return@mapNotNull null
            PendingPatientCandidate(id, row["patientName"]?.toString().orEmpty().ifBlank { "Patient" })
        }
        if (candidates.isEmpty()) return
        bookingWorkflowService.setPendingOpenPatient(doctor, conversationId, originalName, candidates)
        LOGGER.info(
            "intelligence_workflow operation=OPEN_APPOINTMENT conversationId={} pending=CONFIRM_PATIENT candidates={}",
            conversationId,
            candidates.size,
        )
    }

    private fun resolveTrustedAppointmentId(
        doctor: DoctorProfile,
        conversationId: String?,
        patientName: String?,
        userText: String,
    ): Long? = trustedAppointmentMatches(doctor, conversationId, patientName, userText).singleOrNull()?.appointmentId

    private fun trustedAppointmentMatches(
        doctor: DoctorProfile,
        conversationId: String?,
        patientName: String?,
        userText: String,
    ): List<com.shifa.intelligence.appointment.TrustedAppointmentReference> {
        if (conversationId.isNullOrBlank()) return emptyList()
        val zone = zoneOf(doctor)
        val today = LocalDate.now(clock.withZone(zone))
        val speech = AppointmentSpeechReference.parse(userText, today)
        val name = patientName?.trim()?.takeIf { it.isNotEmpty() } ?: speech.patientName
        val time = speech.sourceTime ?: speech.destinationTime
        return toolReferenceService.resolveAppointment(
            doctorId = doctor.id,
            conversationId = conversationId,
            appointmentId = null,
            patientName = name,
            sourceDate = null,
            sourceTime = time,
        )
    }

    private fun clarifyVisitOperation(
        matches: List<com.shifa.intelligence.appointment.TrustedAppointmentReference>,
        spokenHint: String,
    ): Map<String, Any?> = mapOf(
        "supported" to true,
        "status" to "AWAITING_CLARIFICATION",
        "operation" to "OPEN_APPOINTMENT",
        "appointments" to matches.map {
            mapOf(
                "appointmentId" to it.appointmentId,
                "patientId" to it.patientId,
                "patientName" to it.patientName,
                "date" to it.localDate.toString(),
                "startLocal" to it.localTime.format(timeFmt),
                "status" to it.status,
            )
        },
        "spokenHint" to spokenHint,
    )

    /**
     * Name lookup reuses [searchPatients], including the fuzzy roster match.
     * A single exact match is passed through. Several matches, or a fuzzy guess,
     * stop here so appointment rows are not returned for the wrong person.
     */
    private fun queryAppointmentIntelligence(
        doctor: DoctorProfile,
        args: Map<String, Any?>,
        priorAssistantMessages: List<String>,
        latestUserText: String,
        focusPatientId: Long?,
        searchedPatientIds: Set<Long>,
    ): Map<String, Any?> {
        val filters = (args["filters"] as? Map<*, *>)?.entries
            ?.associate { it.key.toString() to it.value }
            ?: emptyMap()
        val name = (filters["patientName"] as? String)?.trim().orEmpty()
            .ifEmpty { (args["patientName"] as? String)?.trim().orEmpty() }
        val alreadyId = (filters["patientId"] as? Number)?.toLong()
        if (name.isNotEmpty() && alreadyId == null) {
            val found = searchPatients(doctor, mapOf("query" to name))
            @Suppress("UNCHECKED_CAST")
            val matches = found["matches"] as? List<Map<String, Any?>> ?: emptyList()
            val fuzzy = found["fuzzy"] == true
            if (matches.size != 1 || fuzzy) return found
            val resolvedId = (matches.first()["patientId"] as? Number)?.toLong()
                ?: return found
            val nextFilters = filters.toMutableMap()
            nextFilters["patientId"] = resolvedId
            nextFilters.remove("patientName")
            val nextArgs = args.toMutableMap()
            nextArgs["filters"] = nextFilters
            return appointmentIntelligenceService.execute(
                doctor = doctor,
                args = nextArgs,
                priorAssistantMessages = priorAssistantMessages,
                latestUserText = latestUserText,
                focusPatientId = focusPatientId,
                searchedPatientIds = searchedPatientIds,
                resolvedPatientId = resolvedId,
            )
        }
        return appointmentIntelligenceService.execute(
            doctor = doctor,
            args = args,
            priorAssistantMessages = priorAssistantMessages,
            latestUserText = latestUserText,
            focusPatientId = focusPatientId,
            searchedPatientIds = searchedPatientIds,
        )
    }

    @Transactional(readOnly = true)
    fun getTodaySchedule(doctor: DoctorProfile): Map<String, Any?> {
        val zone = zoneOf(doctor)
        val today = LocalDate.now(clock.withZone(zone))
        val start = today.atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        val appointments = appointmentRepository.findForDay(doctor.id, start, end)
        val now = clock.instant()

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
                    "date" to localStart.toLocalDate().toString(),
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
        val date = resolveDateArg(args["date"] as? String, zone) ?: LocalDate.now(clock.withZone(zone))
        val partOfDay = (args["partOfDay"] as? String)?.trim()?.lowercase()
        val slots = patientDaySlotsService.availableSlotsForDay(doctor, date)
            .mapNotNull { slot ->
                val startInstant = Instant.parse(slot.startAt)
                if (startInstant.isBefore(clock.instant())) return@mapNotNull null
                val startZ = startInstant.atZone(zone)
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
        }.toMutableList()
        var uniqueFirstName = false
        if (matches.isEmpty()) {
            uniqueByFirstName(query, roster.content)?.let { person ->
                uniqueFirstName = true
                matches += patientMatchRow(person)
            }
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
            "uniqueFirstName" to uniqueFirstName,
            "matches" to matches,
            "spokenHint" to spokenHint,
        )
    }

    /** "Franco Harrison man" still means the only Franco on the roster. */
    private fun uniqueByFirstName(query: String, roster: List<PatientProfile>): PatientProfile? {
        val first = CancellationIntent.nameTokens(query).firstOrNull() ?: return null
        val matches = roster.filter { person ->
            val nameFirst = person.fullName.lowercase().split(Regex("""\s+"""))
                .firstOrNull { it.length >= 3 } ?: return@filter false
            nameFirst == first || stringSimilarity(first, nameFirst) >= 0.88
        }
        return matches.singleOrNull()
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
            .mapNotNull { slot -> toLocalSlot(slot, zone, date) }
            .filter { slot ->
                val hour = LocalTime.parse(slot["startLocal"] as String).hour
                when (partOfDay) {
                    "morning", "утро", "ertalab" -> hour < 12
                    "afternoon", "день", "после обеда", "tushdan keyin" -> hour in 12..16
                    "evening", "вечер", "kechqurun" -> hour >= 17
                    else -> true
                }
            }
        val preferredSlot = preferredTime?.let { want ->
            allSlots.firstOrNull { (it["startLocal"] as String) == want.format(timeFmt) }
        }
        val slots = presentSlots(allSlots, preferredTime, preferredSlot)

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
            "autoConfirm" to false,
            "slots" to slots,
            "spokenHint" to spokenHint,
        )
    }

    @Transactional(readOnly = true)
    fun proposeReschedule(
        doctor: DoctorProfile,
        args: Map<String, Any?>,
        latestUserText: String = "",
        priorAssistantMessages: List<String> = emptyList(),
        priorUserMessages: List<String> = emptyList(),
        conversationId: String? = null,
    ): Map<String, Any?> {
        val zone = zoneOf(doctor)
        val now = clock.instant()
        val today = java.time.LocalDate.now(clock.withZone(zone))
        val upcoming = appointmentRepository.findByDoctorIdAndStartAtBetween(
            doctor.id,
            now.minus(java.time.Duration.ofDays(1)),
            now.plus(java.time.Duration.ofDays(120)),
        ).filter {
            it.status == Appointment.Status.REQUESTED ||
                it.status == Appointment.Status.CONFIRMED ||
                it.status == Appointment.Status.IN_PROGRESS
        }
        val listed = upcoming.map { appointment ->
            val local = appointment.startAt.atZone(zone)
            RescheduleIntent.Visit(
                id = appointment.id,
                patientName = appointment.patient.fullName,
                day = local.toLocalDate(),
                time = local.toLocalTime(),
            )
        }
        val window = RescheduleIntent.queryWindow(priorAssistantMessages)
        val lastListedId = window?.let { (startRaw, endRaw) ->
            val start = runCatching { Instant.parse(startRaw) }.getOrNull() ?: return@let null
            val end = runCatching { Instant.parse(endRaw) }.getOrNull() ?: return@let null
            upcoming.filter { !it.startAt.isBefore(start) && it.startAt.isBefore(end) }
                .maxByOrNull { it.startAt }
                ?.id
        }
        val speech = AppointmentSpeechReference.parse(latestUserText, today)
        val suppliedId = (args["appointmentId"] as? Number)?.toLong()
        val sourcePatientName = (args["sourcePatientName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (args["patientName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: speech.patientName
        val sourceDate = (args["sourceDate"] as? String)?.let { resolveDateArg(it, zone) }
            ?: speech.sourceDate
        val sourceTime = resolveTimeArg(args["sourceTime"] as? String) ?: speech.sourceTime
        val speechDestination = speech.destinationDate
        val speechDestTime = speech.destinationTime
        val hasStructuredReference = suppliedId != null || sourcePatientName != null ||
            sourceDate != null || sourceTime != null
        val trustedMatches = if (!conversationId.isNullOrBlank() && hasStructuredReference) {
            toolReferenceService.resolveAppointment(
                doctorId = doctor.id,
                conversationId = conversationId,
                appointmentId = suppliedId,
                patientName = sourcePatientName,
                sourceDate = sourceDate,
                sourceTime = sourceTime,
            )
        } else {
            emptyList()
        }
        if (trustedMatches.size > 1) {
            return clarifyRescheduleReferences(trustedMatches)
        }
        if (hasStructuredReference && !conversationId.isNullOrBlank() && trustedMatches.isEmpty()) {
            val listedCount = toolReferenceService.latestSchedule(doctor.id, conversationId).size
            val category = if (suppliedId != null) "AUTHORIZATION" else "CONTEXT_RESOLUTION"
            LOGGER.info(
                "intelligence_reference_resolution conversationId={} outcome={} " +
                    "suppliedId={} hasPatientName={} sourceTime={} listedCount={}",
                conversationId,
                category,
                suppliedId,
                sourcePatientName != null,
                sourceTime,
                listedCount,
            )
            return mapOf(
                "cardType" to "propose_reschedule",
                "error" to "Appointment reference is not available",
                "failureCategory" to category,
                "spokenHint" to if (suppliedId != null) {
                    "That appointment is not available on your calendar."
                } else if (listedCount == 0) {
                    "I need the listed appointment in front of us first. Ask me to show today's visits, then tell me which one to move."
                } else {
                    "I could not match that description to a recent appointment on your calendar."
                },
            )
        }
        val modelId = trustedMatches.singleOrNull()?.appointmentId
            ?: suppliedId?.takeIf { conversationId.isNullOrBlank() }
        val modelVisit = modelId?.let { id -> upcoming.firstOrNull { it.id == id } }
        if (modelId != null && modelVisit == null) {
            val owned = appointmentRepository.findById(modelId).orElse(null)
            return when {
                owned == null || owned.doctor.id != doctor.id -> mapOf(
                    "cardType" to "propose_reschedule",
                    "error" to "Appointment reference is not available",
                    "failureCategory" to "AUTHORIZATION",
                    "spokenHint" to "That appointment is not available on your calendar.",
                )
                owned.status !in RESCHEDULABLE_STATUSES -> mapOf(
                    "cardType" to "propose_reschedule",
                    "appointmentId" to owned.id,
                    "error" to "Appointment status ${owned.status.name} is not reschedulable",
                    "failureCategory" to "VALIDATION",
                    "spokenHint" to "That visit is ${owned.status.name.lowercase().replace('_', ' ')} and cannot be moved.",
                )
                owned.startAt.atZone(zone).toLocalDate().isBefore(today) -> mapOf(
                    "cardType" to "propose_reschedule",
                    "appointmentId" to owned.id,
                    "error" to "Cannot change a past appointment",
                    "failureCategory" to "VALIDATION",
                    "spokenHint" to "That visit time has already passed, so it cannot be moved.",
                )
                else -> mapOf(
                    "cardType" to "propose_reschedule",
                    "appointmentId" to owned.id,
                    "error" to "Appointment is outside the rescheduling window",
                    "failureCategory" to "APPOINTMENT_QUERY",
                    "spokenHint" to "That appointment is outside the period that can be moved.",
                )
            }
        }
        val trustedScheduleIds = toolReferenceService.latestSchedule(doctor.id, conversationId)
            .mapTo(linkedSetOf()) { it.appointmentId }
        val trustedListed = listed.filter { trustedScheduleIds.isEmpty() || it.id in trustedScheduleIds }
        val modelIsStale = modelVisit != null &&
            RescheduleIntent.textNamesSomeoneElse(
                (priorUserMessages + latestUserText).joinToString("\n"),
                modelVisit.patient.fullName,
            )
        val choice = if (modelVisit != null && !modelIsStale) {
            RescheduleIntent.Choice(
                visitId = modelVisit.id,
                destination = speechDestination ?: resolveDateArg(args["date"] as? String, zone),
                time = speechDestTime ?: resolveTimeArg(
                    (args["time"] as? String) ?: (args["preferredTime"] as? String),
                ),
            )
        } else {
            RescheduleIntent.choose(
                visits = trustedListed,
                current = latestUserText,
                priorUsers = priorUserMessages,
                priorAssistants = priorAssistantMessages,
                today = today,
                lastListedId = lastListedId,
            )
        }
        val picked = choice ?: return mapOf(
            "cardType" to "propose_reschedule",
            "error" to if (upcoming.isEmpty()) "No upcoming appointment found" else "Could not identify which visit to move",
            "failureCategory" to if (upcoming.isEmpty()) "APPOINTMENT_QUERY" else "CONTEXT_RESOLUTION",
            "spokenHint" to "I could not tell which visit to move. Say the patient and the day.",
        )
        val appointment = upcoming.firstOrNull { it.id == picked.visitId }
            ?: appointmentRepository.findById(picked.visitId).orElse(null)?.takeIf { it.doctor.id == doctor.id }
            ?: return mapOf(
                "cardType" to "propose_reschedule",
                "error" to if (upcoming.isEmpty()) "No upcoming appointment found" else "Could not identify which visit to move",
                "failureCategory" to if (upcoming.isEmpty()) "APPOINTMENT_QUERY" else "CONTEXT_RESOLUTION",
                "spokenHint" to "I could not tell which visit to move. Say the patient and the day.",
            )
        if (appointment.status !in RESCHEDULABLE_STATUSES) {
            return mapOf(
                "cardType" to "propose_reschedule",
                "appointmentId" to appointment.id,
                "error" to "Appointment status ${appointment.status.name} is not reschedulable",
                "failureCategory" to "VALIDATION",
                "spokenHint" to "That visit is ${appointment.status.name.lowercase().replace('_', ' ')} and cannot be moved.",
            )
        }
        if (appointment.startAt.atZone(zone).toLocalDate().isBefore(today)) {
            return mapOf(
                "cardType" to "propose_reschedule",
                "appointmentId" to appointment.id,
                "error" to "Cannot change a past appointment",
                "failureCategory" to "VALIDATION",
                "spokenHint" to "That visit time has already passed, so it cannot be moved.",
            )
        }
        val currentLocal = appointment.startAt.atZone(zone)
        val destination = picked.destination ?: speechDestination ?: resolveDateArg(args["date"] as? String, zone)
        if (destination == null) {
            val patientName = appointment.patient.fullName.ifBlank { "The patient" }
            val past = picked.pastDay
            if (past != null) {
                return mapOf(
                    "cardType" to "propose_reschedule",
                    "appointmentId" to appointment.id,
                    "patientId" to appointment.patient.id,
                    "patientName" to patientName,
                    "spokenHint" to "${speakableDayDate(past)} has already passed. Name a future day for $patientName.",
                )
            }
            return mapOf(
                "cardType" to "propose_reschedule",
                "appointmentId" to appointment.id,
                "patientId" to appointment.patient.id,
                "patientName" to patientName,
                "currentStartAt" to appointment.startAt.toString(),
                "currentStartLocal" to currentLocal.format(timeFmt),
                "currentDateLabel" to currentLocal.format(dateFmt),
                "spokenHint" to "$patientName is on ${speakableDayDate(currentLocal.toLocalDate())} at ${currentLocal.format(timeFmt)}. Which day should I move that visit to?",
            )
        }

        val date = destination

        val preferredTime = picked.time ?: speechDestTime ?: resolveTimeArg(
            (args["time"] as? String) ?: (args["preferredTime"] as? String),
        )
        val allSlots = patientDaySlotsService.availableSlotsForDay(
            doctor,
            date,
            excludeAppointmentId = appointment.id,
        ).mapNotNull { slot -> toLocalSlot(slot, zone, date) }
        val preferredSlot = preferredTime?.let { want ->
            allSlots.firstOrNull { (it["startLocal"] as String) == want.format(timeFmt) }
        }
        val slots = presentSlots(allSlots, preferredTime, preferredSlot)

        val patientName = appointment.patient.fullName.ifBlank { "Patient" }
        return mapOf(
            "cardType" to "propose_reschedule",
            "appointmentId" to appointment.id,
            "patientId" to appointment.patient.id,
            "patientName" to patientName,
            "currentStartAt" to appointment.startAt.toString(),
            "currentStatus" to appointment.status.name,
            "currentStartLocal" to currentLocal.format(timeFmt),
            "currentDateLabel" to currentLocal.format(dateFmt),
            "date" to date.toString(),
            "dateLabel" to date.format(dateFmt),
            "speakableDateLabel" to speakableDayDate(date),
            "requestedTime" to preferredTime?.format(timeFmt),
            "preferredSlot" to preferredSlot,
            "autoConfirm" to false,
            "slots" to slots,
            "spokenHint" to when {
                allSlots.isEmpty() ->
                    "No free slots on ${speakableDayDate(date)} to move $patientName. Please choose another day."
                preferredTime != null && preferredSlot == null ->
                    "${preferredTime.format(timeFmt)} is not free on ${speakableDayDate(date)}. Here are other open times."
                preferredSlot != null ->
                    "I can move $patientName to ${preferredSlot["startLocal"]} on ${speakableDayDate(date)}. Please confirm."
                else ->
                    "Moving $patientName to ${speakableDayDate(date)}. Which time?"
            },
        )
    }

    private fun clarifyRescheduleReferences(
        matches: List<com.shifa.intelligence.appointment.TrustedAppointmentReference>,
    ): Map<String, Any?> = mapOf(
        "cardType" to "propose_reschedule",
        "workflowStatus" to "AWAITING_CLARIFICATION",
        "appointments" to matches.map {
            mapOf(
                "appointmentId" to it.appointmentId,
                "patientId" to it.patientId,
                "patientName" to it.patientName,
                "date" to it.localDate.toString(),
                "startLocal" to it.localTime.format(timeFmt),
                "status" to it.status,
            )
        },
        "spokenHint" to "I found more than one matching visit. Which date and time should I move?",
    )

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
        val today = LocalDate.now(clock.withZone(zone))
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
        return ClinicCalendarDay.parse(raw.trim(), today)?.takeUnless { it.isBefore(today) }
    }

    /** Inclusive start / exclusive end for patient appointment listing. */
    private data class DateRangeWindow(
        val start: LocalDate,
        val endExclusive: LocalDate,
        val cardLabel: String,
        val spokenLabel: String,
    )

    private fun resolveDateRange(raw: String?, zone: ZoneId): DateRangeWindow {
        val today = LocalDate.now(clock.withZone(zone))
        val t = raw?.trim()?.lowercase()
            ?.replace(',', ' ')
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()
        openEndedRange(t, today, zone)?.let { return it }
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

    /**
     * "In the future", "upcoming", "from tomorrow onwards", "from Monday" — everything booked
     * from a start day forward, not a single day or one calendar week.
     */
    private fun openEndedRange(t: String, today: LocalDate, zone: ZoneId): DateRangeWindow? {
        val hasFromPrefix = Regex("""^(?:from|starting|starting from|after|since)\b""").containsMatchIn(t)
        val hasOnwardsSuffix = Regex("""\b(?:onwards?|forward|ahead)$""").containsMatchIn(t)
        val isUpcoming = Regex("""\b(upcoming|future|from now|now on|coming up|going forward)\b""").containsMatchIn(t)
        if (!hasFromPrefix && !hasOnwardsSuffix && !isUpcoming) return null

        // "from tomorrow onwards" → "tomorrow"; "monday onwards" → "monday"; "upcoming" → "".
        val anchor = t
            .replace(Regex("""^(?:from|starting|starting from|after|since)\s+"""), "")
            .replace(Regex("""\s*\b(?:onwards?|on|forward|ahead)$"""), "")
            .replace(Regex("""\b(upcoming|future|now|coming up|going forward)\b"""), "")
            .trim()
        val start = if (anchor.isEmpty()) today else resolveDateArg(anchor, zone) ?: today
        // "after today" means tomorrow onwards.
        val effectiveStart = if (t.startsWith("after") && start == today) today.plusDays(1) else start
        val spoken = when {
            effectiveStart == today -> "coming up"
            effectiveStart == today.plusDays(1) -> "from tomorrow onwards"
            else -> "from ${speakableDayDate(effectiveStart)} onwards"
        }
        return DateRangeWindow(
            start = effectiveStart,
            endExclusive = effectiveStart.plusYears(1),
            cardLabel = "Upcoming (from ${effectiveStart.format(dateFmt)})",
            spokenLabel = spoken,
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
        // Bare weekdays are ambiguous once the range is longer than a week.
        val first = appointments.first().startAt.atZone(zone).toLocalDate()
        val last = appointments.last().startAt.atZone(zone).toLocalDate()
        val spansWeeks = java.time.temporal.ChronoUnit.DAYS.between(first, last) > 6 ||
            rangeSpoken == "coming up" || rangeSpoken.endsWith("onwards")
        val details = appointments.take(4).joinToString("; ") { a ->
            val local = a.startAt.atZone(zone)
            val day = if (spansWeeks) {
                speakableDayDate(local.toLocalDate())
            } else {
                local.dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }
            }
            "$day at ${local.format(timeFmt)}"
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

    private fun toLocalSlot(
        slot: PatientDaySlotsService.AvailableSlotDto,
        zone: ZoneId,
        requestedDate: LocalDate,
    ): Map<String, Any?>? {
        val startInstant = Instant.parse(slot.startAt)
        if (startInstant.isBefore(clock.instant())) return null
        val startZ = startInstant.atZone(zone)
        if (startZ.toLocalDate() != requestedDate) return null
        return mapOf(
            "startAt" to slot.startAt,
            "endAt" to slot.endAt,
            "slotMinutes" to slot.slotMinutes,
            "startLocal" to startZ.format(timeFmt),
            "locationId" to slot.locationId,
            "locationLabel" to slot.locationLabel,
        )
    }

    private fun presentSlots(
        allSlots: List<Map<String, Any?>>,
        preferredTime: LocalTime?,
        preferredSlot: Map<String, Any?>?,
    ): List<Map<String, Any?>> {
        val ranked = rankSlotsByRequestedTime(allSlots, preferredTime)
        if (preferredSlot == null) return ranked.take(12)
        return listOf(preferredSlot) + ranked.filter { it["startAt"] != preferredSlot["startAt"] }.take(11)
    }

    private fun rankSlotsByRequestedTime(
        slots: List<Map<String, Any?>>,
        preferredTime: LocalTime?,
    ): List<Map<String, Any?>> {
        if (preferredTime == null) return slots
        val want = preferredTime.toSecondOfDay()
        return slots.sortedBy { slot ->
            val local = runCatching { LocalTime.parse(slot["startLocal"] as String) }.getOrNull()
                ?: return@sortedBy Int.MAX_VALUE
            kotlin.math.abs(local.toSecondOfDay() - want)
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(DoctorMorningToolService::class.java)
        /** Minimum name similarity for fuzzy "Did you mean?" suggestions. */
        private const val FUZZY_NAME_THRESHOLD = 0.72
        private val RESCHEDULABLE_STATUSES = setOf(
            Appointment.Status.REQUESTED,
            Appointment.Status.CONFIRMED,
        )

        private val CARD_TOOLS = setOf(
            "get_today_schedule",
            "list_free_slots",
            "list_patient_appointments",
            "search_patients",
            "manage_booking_workflow",
            "propose_appointment",
            "propose_reschedule",
            "list_draft_visit_facts",
            "get_most_visited_patients",
            "get_clinic_patterns",
            "query_appointment_intelligence",
            "preview_appointment_cancellation",
            "confirm_appointment_cancellation",
            "open_appointment",
            "start_appointment",
            "complete_appointment",
            "request_appointment_signature",
            "append_consultation_draft",
            "brief_patient",
            "list_patient_documents",
            "open_patient_document",
            "open_dental_chart",
        )
    }
}
