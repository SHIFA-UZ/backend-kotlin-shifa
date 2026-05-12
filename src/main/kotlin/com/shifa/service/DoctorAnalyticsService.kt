package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.domain.DoctorProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientDocumentRepository
import com.shifa.web.dto.AppointmentTrendPointDto
import com.shifa.web.dto.ConsultationTypesDto
import com.shifa.web.dto.DoctorAnalyticsOverviewDto
import com.shifa.web.dto.DoctorEngagementDto
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Aggregated analytics for the logged-in doctor. No PII returned; doctor-scoped only.
 * "Today" and date grouping use doctor.timeZone (practice timezone).
 */
@Service
class DoctorAnalyticsService(
    private val appts: AppointmentRepository,
    private val patientDocs: PatientDocumentRepository
) {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun overview(doctor: DoctorProfile): DoctorAnalyticsOverviewDto {
        val doctorId = doctor.id
        val (dayStart, dayEnd) = todayRange(doctor)
        val appointmentsToday = appts.countByDoctor_IdAndStartAtBetweenAndStatus(
            doctorId, dayStart, dayEnd, Appointment.Status.REQUESTED
        ) + appts.countByDoctor_IdAndStartAtBetweenAndStatus(
            doctorId, dayStart, dayEnd, Appointment.Status.CONFIRMED
        ) + appts.countByDoctor_IdAndStartAtBetweenAndStatus(
            doctorId, dayStart, dayEnd, Appointment.Status.IN_PROGRESS
        ) + appts.countByDoctor_IdAndStartAtBetweenAndStatus(
            doctorId, dayStart, dayEnd, Appointment.Status.COMPLETED
        )
        val completedToday = appts.countByDoctor_IdAndStartAtBetweenAndStatus(
            doctorId, dayStart, dayEnd, Appointment.Status.COMPLETED
        ).toInt()
        val cancelledToday = appts.countByDoctor_IdAndStartAtBetweenAndStatus(
            doctorId, dayStart, dayEnd, Appointment.Status.CANCELLED
        ).toInt()
        val newPatientsToday = appts.countNewPatientsToday(doctorId, dayStart, dayEnd).toInt()
        return DoctorAnalyticsOverviewDto(
            appointmentsToday = appointmentsToday.toInt(),
            completedToday = completedToday,
            cancelledToday = cancelledToday,
            newPatientsToday = newPatientsToday
        )
    }

    /** Last N days (inclusive of today); returns one point per day, sorted by date. */
    fun appointmentsTrend(doctor: DoctorProfile, days: Int): List<AppointmentTrendPointDto> {
        val doctorId = doctor.id
        val zone = java.time.ZoneId.of(doctor.timeZone)
        val today = LocalDate.now(zone)
        val rangeStart = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()
        val rangeEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
        val list = appts.findByDoctorIdAndStartAtBetween(doctorId, rangeStart, rangeEnd)
        val byDate = list.groupBy { it.startAt.atZone(zone).toLocalDate() }
            .mapValues { it.value.size }
        return (0 until days).map { i ->
            val d = LocalDate.now(zone).minusDays((days - 1 - i).toLong())
            AppointmentTrendPointDto(
                date = d.format(dateFormatter),
                count = byDate[d] ?: 0
            )
        }
    }

    /** Last 30 days: video vs in-person counts. */
    fun consultationTypes(doctor: DoctorProfile): ConsultationTypesDto {
        val doctorId = doctor.id
        val (start, end) = last30DaysRange(doctor)
        val video = appts.countVideoByDoctorIdAndStartAtBetween(doctorId, start, end).toInt()
        val inPerson = appts.countInPersonByDoctorIdAndStartAtBetween(doctorId, start, end).toInt()
        return ConsultationTypesDto(video = video, inPerson = inPerson)
    }

    /** Active patients (distinct in last 30 days) and documents received (last 30 days) for those patients. */
    fun engagement(doctor: DoctorProfile): DoctorEngagementDto {
        val doctorId = doctor.id
        val (start, end) = last30DaysRange(doctor)
        val zone = java.time.ZoneId.of(doctor.timeZone)
        val activePatients = appts.countDistinctPatientsByDoctorIdAndStartAtBetween(doctorId, start, end).toInt()
        val docStart = start.atZone(zone).toLocalDate()
        val docEnd = end.atZone(zone).toLocalDate().plusDays(1)
        val documentsReceived = patientDocs.countDocumentsByDoctorPatientsInDateRange(
            doctorId, start, end, docStart, docEnd
        ).toInt()
        return DoctorEngagementDto(
            activePatients = activePatients,
            documentsReceived = documentsReceived
        )
    }

    private fun todayRange(doctor: DoctorProfile): Pair<Instant, Instant> {
        val zone = java.time.ZoneId.of(doctor.timeZone)
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
        return Pair(dayStart, dayEnd)
    }

    private fun last30DaysRange(doctor: DoctorProfile): Pair<Instant, Instant> {
        val zone = java.time.ZoneId.of(doctor.timeZone)
        val now = ZonedDateTime.now(zone)
        val start = now.minusDays(30).toInstant()
        val end = now.plusSeconds(1).toInstant()
        return Pair(start, end)
    }
}
