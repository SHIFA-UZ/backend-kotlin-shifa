package com.shifa.service

import com.shifa.domain.ClinicVisitPattern
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientVisitFact
import com.shifa.domain.RemoteCareTask
import com.shifa.repo.ClinicVisitPatternRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.PatientVisitFactRepository
import com.shifa.repo.RemoteCareTaskRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

@Service
class VisitFactService(
    private val factRepository: PatientVisitFactRepository,
    private val patternRepository: ClinicVisitPatternRepository,
    private val extractionService: VisitFactExtractionService,
    private val taskRepository: RemoteCareTaskRepository,
    private val patientProfileRepository: PatientProfileRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class VisitFactDto(
        val id: Long,
        val appointmentId: Long?,
        val patientId: Long,
        val factType: String,
        val text: String,
        val quote: String?,
        val scheduleHint: String?,
        val status: String,
        val reflectionKept: Boolean,
        val dropReason: String?,
        val remoteTaskId: Long?,
        val createdAt: String,
        val committedAt: String?,
    )

    data class ClinicPatternDto(
        val key: String,
        val label: String,
        val factType: String,
        val count: Int,
    )

    data class CommitResult(
        val committedFacts: List<VisitFactDto>,
        val createdTaskIds: List<Long>,
        val patterns: List<ClinicPatternDto>,
    )

    @Transactional
    fun extractAndStoreDrafts(
        doctorId: Long,
        patientId: Long,
        appointmentId: Long,
        transcript: String,
        language: com.shifa.ai.OutputLanguage,
    ): List<VisitFactDto> {
        // Replace prior drafts for this appointment so re-scribe stays idempotent.
        factRepository.findByAppointmentIdAndStatus(appointmentId, PatientVisitFact.Status.DRAFT)
            .forEach { factRepository.delete(it) }
        factRepository.findByAppointmentIdAndStatus(appointmentId, PatientVisitFact.Status.DROPPED)
            .forEach { factRepository.delete(it) }

        val result = extractionService.extract(transcript, language)
        val saved = mutableListOf<PatientVisitFact>()
        for (item in result.kept) {
            saved += factRepository.save(
                PatientVisitFact(
                    doctorId = doctorId,
                    patientId = patientId,
                    appointmentId = appointmentId,
                    factType = item.factType,
                    text = item.text,
                    quote = item.quote,
                    scheduleHint = item.scheduleHint,
                    status = PatientVisitFact.Status.DRAFT,
                    reflectionKept = true,
                )
            )
        }
        for (item in result.dropped) {
            saved += factRepository.save(
                PatientVisitFact(
                    doctorId = doctorId,
                    patientId = patientId,
                    appointmentId = appointmentId,
                    factType = item.factType,
                    text = item.text,
                    quote = item.quote,
                    scheduleHint = item.scheduleHint,
                    status = PatientVisitFact.Status.DROPPED,
                    reflectionKept = false,
                    dropReason = item.dropReason,
                )
            )
        }
        log.info(
            "Visit facts for appointment {}: kept={}, dropped={}",
            appointmentId,
            result.kept.size,
            result.dropped.size,
        )
        return saved.map { toDto(it) }
    }

    @Transactional(readOnly = true)
    fun listForAppointment(appointmentId: Long, doctorId: Long): List<VisitFactDto> {
        return factRepository.findByAppointmentIdOrderByCreatedAtAsc(appointmentId)
            .filter { it.doctorId == doctorId }
            .map { toDto(it) }
    }

    @Transactional(readOnly = true)
    fun patientTimeline(patientId: Long, doctorId: Long): List<VisitFactDto> {
        return factRepository.findCommittedTimeline(doctorId, patientId).map { toDto(it) }
    }

    @Transactional(readOnly = true)
    fun clinicPatterns(doctorId: Long): List<ClinicPatternDto> {
        return patternRepository.findByDoctorIdOrderByOccurrenceCountDesc(doctorId).map {
            ClinicPatternDto(
                key = it.patternKey,
                label = it.patternLabel,
                factType = it.factType.name,
                count = it.occurrenceCount,
            )
        }
    }

    /**
     * Doctor said "document everything": commit kept drafts, create medication remote-care drafts,
     * and bump anonymized clinic patterns.
     */
    @Transactional
    fun commitForAppointment(
        appointmentId: Long,
        doctor: DoctorProfile,
        acceptFactIds: Set<Long>? = null,
        rejectFactIds: Set<Long>? = null,
    ): CommitResult {
        val all = factRepository.findByAppointmentIdOrderByCreatedAtAsc(appointmentId)
            .filter { it.doctorId == doctor.id }
        if (all.isEmpty()) {
            return CommitResult(emptyList(), emptyList(), clinicPatterns(doctor.id))
        }

        rejectFactIds.orEmpty().forEach { id ->
            all.find { it.id == id && it.status == PatientVisitFact.Status.DRAFT }?.let {
                it.status = PatientVisitFact.Status.REJECTED
                factRepository.save(it)
            }
        }

        val toCommit = all.filter { fact ->
            fact.status == PatientVisitFact.Status.DRAFT &&
                fact.reflectionKept &&
                (acceptFactIds == null || acceptFactIds.contains(fact.id))
        }

        val createdTasks = mutableListOf<Long>()
        val committed = mutableListOf<PatientVisitFact>()
        val now = Instant.now()

        for (fact in toCommit) {
            if (fact.factType == PatientVisitFact.FactType.MEDICATION) {
                val taskId = createMedicationDraftTask(doctor, fact)
                if (taskId != null) {
                    fact.remoteTaskId = taskId
                    createdTasks += taskId
                }
            }
            fact.status = PatientVisitFact.Status.COMMITTED
            fact.committedAt = now
            committed += factRepository.save(fact)
            incrementPattern(doctor.id, fact)
        }

        // Remaining drafts that were not accepted become rejected when an explicit accept set was provided.
        if (acceptFactIds != null) {
            all.filter {
                it.status == PatientVisitFact.Status.DRAFT && !acceptFactIds.contains(it.id)
            }.forEach {
                it.status = PatientVisitFact.Status.REJECTED
                factRepository.save(it)
            }
        }

        return CommitResult(
            committedFacts = committed.map { toDto(it) },
            createdTaskIds = createdTasks,
            patterns = clinicPatterns(doctor.id),
        )
    }

    private fun createMedicationDraftTask(doctor: DoctorProfile, fact: PatientVisitFact): Long? {
        val patient = patientProfileRepository.findById(fact.patientId).orElse(null) ?: return null
        val description = buildString {
            append("From visit transcript")
            fact.quote?.let { append(": \"").append(it.take(240)).append("\"") }
            fact.scheduleHint?.let { append(". Schedule: ").append(it) }
        }
        val task = RemoteCareTask(
            doctor = doctor,
            patient = patient,
            taskName = fact.text.take(120),
            description = description,
            category = RemoteCareTask.Category.MEDICATION,
            status = RemoteCareTask.Status.DRAFT,
            timesPerDay = 1,
            startDate = LocalDate.now(),
            durationDays = 7,
            inputType = RemoteCareTask.InputType.BOOLEAN,
            inputLabel = "Taken",
            notesRequired = false,
        )
        return taskRepository.save(task).id
    }

    private fun incrementPattern(doctorId: Long, fact: PatientVisitFact) {
        val key = normalizePatternKey(fact.factType, fact.text)
        val label = when (fact.factType) {
            PatientVisitFact.FactType.MEDICATION ->
                "Spoken medication \"${fact.text}\" followed by a remote-care medication task"
            PatientVisitFact.FactType.DIAGNOSIS ->
                "Stated diagnosis \"${fact.text}\" added to patient timeline"
            PatientVisitFact.FactType.THERAPY ->
                "Therapy plan \"${fact.text}\" added to patient timeline"
            PatientVisitFact.FactType.SYMPTOM ->
                "Symptom \"${fact.text}\" recorded on patient timeline"
        }
        val existing = patternRepository.findByDoctorIdAndPatternKey(doctorId, key).orElse(null)
        if (existing != null) {
            existing.occurrenceCount += 1
            existing.patternLabel = label
            existing.updatedAt = Instant.now()
            patternRepository.save(existing)
        } else {
            patternRepository.save(
                ClinicVisitPattern(
                    doctorId = doctorId,
                    patternKey = key,
                    patternLabel = label,
                    factType = fact.factType,
                    occurrenceCount = 1,
                )
            )
        }
    }

    private fun normalizePatternKey(type: PatientVisitFact.FactType, text: String): String {
        val normalized = text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9а-яёўқғҳ\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .take(80)
        return "${type.name.lowercase()}:$normalized"
    }

    fun toDto(fact: PatientVisitFact) = VisitFactDto(
        id = fact.id,
        appointmentId = fact.appointmentId,
        patientId = fact.patientId,
        factType = fact.factType.name,
        text = fact.text,
        quote = fact.quote,
        scheduleHint = fact.scheduleHint,
        status = fact.status.name,
        reflectionKept = fact.reflectionKept,
        dropReason = fact.dropReason,
        remoteTaskId = fact.remoteTaskId,
        createdAt = fact.createdAt.toString(),
        committedAt = fact.committedAt?.toString(),
    )
}
