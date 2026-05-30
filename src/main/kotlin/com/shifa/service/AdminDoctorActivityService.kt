package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.domain.DoctorProfile
import com.shifa.domain.Role
import com.shifa.repo.AiDraftNoteRepository
import com.shifa.repo.AiUsageCounterRepository
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ConsultationNoteRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.PatientDocumentRepository
import com.shifa.repo.PatientFormRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.RemoteCareTaskRepository
import com.shifa.repo.TreatmentPlanRepository
import com.shifa.web.dto.DoctorActivityDailyPointDto
import com.shifa.web.dto.DoctorActivityDetailDto
import com.shifa.web.dto.DoctorActivityRowDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Comparator
import java.util.Locale
import kotlin.math.min

@Service
class AdminDoctorActivityService(
    private val earlyPartnerContractService: EarlyPartnerContractService,
    private val doctorProfileRepository: DoctorProfileRepository,
    private val appointmentRepository: AppointmentRepository,
    private val patientProfileRepository: PatientProfileRepository,
    private val patientDocumentRepository: PatientDocumentRepository,
    private val treatmentPlanRepository: TreatmentPlanRepository,
    private val remoteCareTaskRepository: RemoteCareTaskRepository,
    private val consultationNoteRepository: ConsultationNoteRepository,
    private val patientFormRepository: PatientFormRepository,
    private val aiUsageCounterRepository: AiUsageCounterRepository,
    private val aiDraftNoteRepository: AiDraftNoteRepository,
) {

    companion object {
        private val UTC = ZoneOffset.UTC
        private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE
        private const val MAX_CHART_DAYS = 366L

        fun toWindow(fromDate: LocalDate, toInclusive: LocalDate): BoundedWindow {
            require(!toInclusive.isBefore(fromDate)) { "Invalid date range" }
            val startInstant = fromDate.atStartOfDay(UTC).toInstant()
            val endExclusive = toInclusive.plusDays(1).atStartOfDay(UTC).toInstant()
            val odtStart = fromDate.atStartOfDay(UTC).toOffsetDateTime()
            val odtEndExclusive = toInclusive.plusDays(1).atStartOfDay(UTC).toOffsetDateTime()
            return BoundedWindow(
                startInstant = startInstant,
                endExclusiveInstant = endExclusive,
                docFrom = fromDate,
                docToInclusive = toInclusive,
                odtStart = odtStart,
                odtEndExclusive = odtEndExclusive,
                usageFrom = fromDate,
                usageToInclusive = toInclusive,
            )
        }
    }

    data class BoundedWindow(
        val startInstant: Instant,
        val endExclusiveInstant: Instant,
        val docFrom: LocalDate,
        val docToInclusive: LocalDate,
        val odtStart: java.time.OffsetDateTime,
        val odtEndExclusive: java.time.OffsetDateTime,
        val usageFrom: LocalDate,
        val usageToInclusive: LocalDate,
    )

    fun parseOptionalWindow(from: LocalDate?, toInclusive: LocalDate?): BoundedWindow? =
        when {
            from == null && toInclusive == null -> null
            from != null && toInclusive != null -> toWindow(from, toInclusive)
            else -> throw IllegalArgumentException("Both 'from' and 'to' must be set together, or both omitted.")
        }

    @Transactional(readOnly = true)
    fun listPaged(
        from: LocalDate?,
        toInclusive: LocalDate?,
        searchTrimmed: String?,
        sortOneOf: String,
        dirDescending: Boolean,
        pageZeroBased: Int,
        pageSize: Int,
    ): Page<DoctorActivityRowDto> {
        val requestedWindow = parseOptionalWindow(from, toInclusive)
        val doctors = doctorProfileRepository.findAllForAdminActivitySearch(
            searchTrimmed?.takeIf { it.isNotBlank() },
        ).toList()

        val contractByDoctor =
            earlyPartnerContractService.contractNumbersByDoctorIds(doctors.map { it.id })
        val rows = doctors.map { d ->
            buildRow(d, requestedWindow, contractByDoctor[d.id])
        }
        val comparator = comparatorFor(sortOneOf)
        val sorted =
            if (dirDescending) rows.sortedWith(comparator.reversed())
            else rows.sortedWith(comparator)

        val p = pageZeroBased.coerceAtLeast(0)
        val s = pageSize.coerceAtLeast(1).coerceAtMost(500)
        val slice = sorted.drop(p * s).take(s)

        val pageReq = PageRequest.of(p, s)
        return PageImpl(slice, pageReq, sorted.size.toLong())
    }

    @Transactional(readOnly = true)
    fun doctorDetail(doctorId: Long, from: LocalDate?, toInclusive: LocalDate?): DoctorActivityDetailDto {
        val doctor = doctorProfileRepository.findById(doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }
        val requestedWindow = parseOptionalWindow(from, toInclusive)
        val contractNumber =
            earlyPartnerContractService.contractNumbersByDoctorIds(listOf(doctor.id))[doctor.id]
        val row = buildRow(doctor, requestedWindow, contractNumber)
        val chartWindow = computeChartWindow(requestedWindow)
        val daily = buildDailySeries(doctor.id, doctor.user.id, chartWindow)
        return DoctorActivityDetailDto(row = row, dailySeries = daily)
    }

    private fun comparatorFor(sort: String): Comparator<DoctorActivityRowDto> {
        return when (sort.lowercase(Locale.ROOT)) {
            "name" ->
                Comparator.comparing { row: DoctorActivityRowDto -> row.doctorName.lowercase(Locale.ROOT) }
            "appointments" ->
                Comparator.comparingInt(DoctorActivityRowDto::appointmentsBooked)
            "completed" ->
                Comparator.comparingInt(DoctorActivityRowDto::appointmentsCompleted)
            "airequests" ->
                Comparator.comparingLong(DoctorActivityRowDto::aiRequests)
            "patientscreated" ->
                Comparator.comparingInt(DoctorActivityRowDto::patientsCreated)
            "lastactive" ->
                Comparator.comparing<DoctorActivityRowDto, Instant?>(
                    { dr -> dr.lastActiveAt?.let { Instant.parse(it) } },
                    Comparator.nullsLast(Comparator.naturalOrder<Instant>()),
                )
            else ->
                Comparator.comparingInt(DoctorActivityRowDto::appointmentsBooked)
        }
    }

    private fun computeChartWindow(rowWindow: BoundedWindow?): BoundedWindow {
        if (rowWindow == null) {
            val to = LocalDate.now(UTC)
            return toWindow(to.minusDays(89), to)
        }
        val span = ChronoUnit.DAYS.between(rowWindow.usageFrom, rowWindow.usageToInclusive) + 1
        if (span <= MAX_CHART_DAYS) return rowWindow
        val clippedFrom = rowWindow.usageToInclusive.minusDays(MAX_CHART_DAYS - 1)
        return toWindow(clippedFrom, rowWindow.usageToInclusive)
    }

    private fun buildDailySeries(doctorId: Long, doctorUserId: Long, window: BoundedWindow): Map<String, List<DoctorActivityDailyPointDto>> {
        val dayList = iterateDaysInclusive(window.usageFrom, window.usageToInclusive)

        val appts =
            appointmentRepository.findByDoctorIdAndStartAtBetween(doctorId, window.startInstant, window.endExclusiveInstant)
        val byDayTotal = dayList.associateWith { 0 }.toMutableMap()
        val byDayCompleted = dayList.associateWith { 0 }.toMutableMap()

        for (a in appts) {
            val d = a.startAt.atZone(UTC).toLocalDate()
            if (d in byDayTotal) {
                byDayTotal[d] = byDayTotal.getValue(d) + 1
            }
            if (a.status == Appointment.Status.COMPLETED && d in byDayCompleted) {
                byDayCompleted[d] = byDayCompleted.getValue(d) + 1
            }
        }

        val docCountsByDay =
            patientDocumentRepository.listUploadedByDoctorInDocumentDateRange(doctorId, window.docFrom, window.docToInclusive)
                .groupingBy { it.date }
                .eachCount()

        val planByDay =
            treatmentPlanRepository.listByAttendingDoctorCreatedBetween(doctorId, window.odtStart, window.odtEndExclusive)
                .groupingBy { it.createdAt.toInstant().atZone(UTC).toLocalDate() }
                .eachCount()

        val remoteByDay =
            remoteCareTaskRepository.listByDoctorCreatedBetween(doctorId, window.startInstant, window.endExclusiveInstant)
                .groupingBy { it.createdAt.atZone(UTC).toLocalDate() }
                .eachCount()

        val consultByDay =
            consultationNoteRepository.listByDoctorCreatedBetween(doctorId, window.startInstant, window.endExclusiveInstant)
                .groupingBy { it.createdAt.atZone(UTC).toLocalDate() }
                .eachCount()

        val aiRows = aiUsageCounterRepository.sumRequestCountGroupedByUsageDate(
            doctorUserId,
            Role.DOCTOR,
            window.usageFrom,
            window.usageToInclusive,
        )
        val aiRequestByDay = mutableMapOf<LocalDate, Long>()
        for (row in aiRows) {
            val d = row[0] as LocalDate
            val n = row[1] as Number
            aiRequestByDay[d] = n.toLong()
        }

        fun fill(map: Map<LocalDate, Int>): List<DoctorActivityDailyPointDto> =
            dayList.map { d -> DoctorActivityDailyPointDto(d.format(ISO_DATE), map[d] ?: 0) }

        fun fillAi(map: Map<LocalDate, Long>): List<DoctorActivityDailyPointDto> =
            dayList.map { d ->
                val v = map[d] ?: 0L
                DoctorActivityDailyPointDto(d.format(ISO_DATE), min(Int.MAX_VALUE.toLong(), v).toInt())
            }

        return mapOf(
            "appointments" to fill(byDayTotal),
            "completed" to fill(byDayCompleted),
            "aiRequests" to fillAi(aiRequestByDay),
            "documents" to fill(docCountsByDay.mapValues { it.value }),
            "treatmentPlans" to fill(planByDay.mapValues { it.value }),
            "remoteTasks" to fill(remoteByDay.mapValues { it.value }),
            "consultationNotes" to fill(consultByDay.mapValues { it.value }),
        )
    }

    private fun iterateDaysInclusive(from: LocalDate, toInclusive: LocalDate): List<LocalDate> =
        sequence {
            var d = from
            while (!d.isAfter(toInclusive)) {
                yield(d)
                d = d.plusDays(1)
            }
        }.toList()

    private fun countAppointmentStatus(doctorId: Long, st: Appointment.Status, w: BoundedWindow?): Long =
        if (w != null) appointmentRepository.countByDoctor_IdAndStartAtBetweenAndStatus(
            doctorId,
            w.startInstant,
            w.endExclusiveInstant,
            st,
        )
        else appointmentRepository.countByDoctor_IdAndStatus(doctorId, st)

    internal fun buildRow(
        doctor: DoctorProfile,
        rowWindow: BoundedWindow?,
        earlyPartnerContractNumber: String? = null,
    ): DoctorActivityRowDto {
        val id = doctor.id
        val userId = doctor.user.id
        val w = rowWindow

        val appointmentsBooked =
            if (w != null) appointmentRepository.countByDoctorIdAndStartAtBetween(id, w.startInstant, w.endExclusiveInstant)
            else appointmentRepository.countByDoctor_Id(id)

        val completed = countAppointmentStatus(id, Appointment.Status.COMPLETED, w)
        val cancelled = countAppointmentStatus(id, Appointment.Status.CANCELLED, w)
        val inProgress = countAppointmentStatus(id, Appointment.Status.IN_PROGRESS, w)

        val video =
            if (w != null) appointmentRepository.countVideoByDoctorIdAndStartAtBetween(id, w.startInstant, w.endExclusiveInstant)
            else appointmentRepository.countVideoAllTime(id)

        val activePatients =
            if (w != null) appointmentRepository.countDistinctPatientsByDoctorIdAndStartAtBetween(id, w.startInstant, w.endExclusiveInstant)
            else appointmentRepository.countDistinctPatientsByDoctor(id)

        val patientsCreated =
            if (w != null) patientProfileRepository.countCreatedByDoctorInDateRange(id, w.odtStart, w.odtEndExclusive)
            else patientProfileRepository.countByCreatedByDoctor_Id(id)

        val documentsUploaded =
            if (w != null) patientDocumentRepository.countByUploadedDoctorAndDocumentDateBetween(id, w.docFrom, w.docToInclusive)
            else patientDocumentRepository.countUploadedByDoctorAllTime(id)

        val treatmentPlans =
            if (w != null) treatmentPlanRepository.countByAttendingDoctorInDateRange(id, w.odtStart, w.odtEndExclusive)
            else treatmentPlanRepository.countByAttendingDoctorAllTime(id)

        val remoteTasks =
            if (w != null) remoteCareTaskRepository.countByDoctorInDateRange(id, w.startInstant, w.endExclusiveInstant)
            else remoteCareTaskRepository.countByDoctorAllTime(id)

        val consultationNotes =
            if (w != null) consultationNoteRepository.countByDoctorInDateRange(id, w.startInstant, w.endExclusiveInstant)
            else consultationNoteRepository.countByDoctorAllTime(id)

        val patientForms =
            if (w != null) patientFormRepository.countByCreatedByDoctorInDateRange(id, w.odtStart, w.odtEndExclusive)
            else patientFormRepository.countByCreatedByDoctorAllTime(id)

        val aiDraftNotes =
            if (w != null) aiDraftNoteRepository.countByDoctorInDateRange(id, w.startInstant, w.endExclusiveInstant)
            else aiDraftNoteRepository.countByDoctorAllTime(id)

        val aiRequests =
            if (w != null) aiUsageCounterRepository.sumRequestCountByUserAndUsageDateBetween(
                userId,
                Role.DOCTOR,
                w.usageFrom,
                w.usageToInclusive,
            )
            else aiUsageCounterRepository.sumRequestCountAllTimeForUser(userId, Role.DOCTOR)

        val denom = appointmentsBooked - inProgress
        val cancellationRate = if (denom <= 0) 0.0 else cancelled.toDouble() / denom.toDouble()

        val practice = doctor.practiceClinic
        val clinicId = practice?.id
        val clinicName = practice?.name?.takeUnless { it.isBlank() }
            ?: doctor.clinic?.trim()?.takeUnless { it.isBlank() }

        val email = doctor.user.email?.takeUnless { it.isBlank() }

        val lastInstant = computeLastActiveAllTime(doctor.id, doctor.user.id)

        return DoctorActivityRowDto(
            doctorId = id,
            doctorName = "${doctor.firstName.trim()} ${doctor.lastName.trim()}".trim(),
            email = email,
            clinicId = clinicId,
            clinicName = clinicName,
            appointmentsBooked = appointmentsBooked.toInt(),
            appointmentsCompleted = completed.toInt(),
            appointmentsCancelled = cancelled.toInt(),
            cancellationRate = cancellationRate,
            videoAppointments = video.toInt(),
            activePatients = activePatients.toInt(),
            patientsCreated = patientsCreated.toInt(),
            documentsUploaded = documentsUploaded.toInt(),
            treatmentPlans = treatmentPlans.toInt(),
            remoteTasks = remoteTasks.toInt(),
            consultationNotes = consultationNotes.toInt(),
            patientForms = patientForms.toInt(),
            aiRequests = aiRequests,
            aiDraftNotes = aiDraftNotes.toInt(),
            lastActiveAt = lastInstant?.toString(),
            earlyPartnerContractNumber = earlyPartnerContractNumber,
        )
    }

    private fun computeLastActiveAllTime(doctorId: Long, userId: Long): Instant? {
        val dates = mutableListOf<Instant?>()
        dates.add(appointmentRepository.findMaxStartAtByDoctorId(doctorId))
        dates.add(treatmentPlanRepository.findMaxUpdatedAtByAttendingDoctorId(doctorId)?.toInstant())
        dates.add(remoteCareTaskRepository.findMaxUpdatedAtByDoctorId(doctorId))
        dates.add(consultationNoteRepository.findMaxCreatedAtByDoctorId(doctorId))
        dates.add(aiDraftNoteRepository.findMaxCreatedAtByDoctorId(doctorId))
        dates.add(patientFormRepository.findMaxCreatedAtByDoctor(doctorId)?.toInstant())
        aiUsageCounterRepository.findMaxUsageDate(userId, Role.DOCTOR)?.let {
            dates.add(it.atStartOfDay(UTC).toInstant())
        }
        return dates.filterNotNull().maxOrNull()
    }
}
