package com.shifa.appointments.application

import com.shifa.appointments.domain.Appointment
import com.shifa.appointments.infrastructure.AppointmentRepository
import java.time.Instant
import java.util.Optional
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

/**
 * The feature's door to Appointment records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [AppointmentRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class Appointments(private val repo: AppointmentRepository) {
    fun findByIdWithDoctorAndPatient(id: Long): Optional<Appointment> = repo.findByIdWithDoctorAndPatient(id)
    fun findByIdForUpdate(id: Long): Optional<Appointment> = repo.findByIdForUpdate(id)
    fun deleteByDoctorId(doctorId: Long): Int = repo.deleteByDoctorId(doctorId)
    fun deleteByPatientId(patientId: Long): Int = repo.deleteByPatientId(patientId)
    fun findOverlapping(doctorId: Long, start: Instant, end: Instant): List<Appointment> = repo.findOverlapping(doctorId, start, end)
    fun findForDay(doctorId: Long, start: Instant, end: Instant): List<Appointment> = repo.findForDay(doctorId, start, end)
    fun findByPatientId(patientId: Long): List<Appointment> = repo.findByPatientId(patientId)
    fun findByPatientIdWithDoctorForExport(patientId: Long): List<Appointment> = repo.findByPatientIdWithDoctorForExport(patientId)
    fun findByPatientIdAndDoctorIdOrderByStartAtDesc(patientId: Long, doctorId: Long): List<Appointment> = repo.findByPatientIdAndDoctorIdOrderByStartAtDesc(patientId, doctorId)
    fun findFirstByPatient_IdAndDoctor_IdAndStartAtAfterAndStatusNotOrderByStartAtAsc(patientId: Long, doctorId: Long, startAt: Instant, status: com.shifa.appointments.domain.Appointment.Status): Appointment? = repo.findFirstByPatient_IdAndDoctor_IdAndStartAtAfterAndStatusNotOrderByStartAtAsc(patientId, doctorId, startAt, status)
    fun findByPatientIdAndDateRange(patientId: Long, start: Instant, end: Instant): List<Appointment> = repo.findByPatientIdAndDateRange(patientId, start, end)
    fun findOverlappingForPatient(patientId: Long, start: Instant, end: Instant): List<Appointment> = repo.findOverlappingForPatient(patientId, start, end)
    fun countByDoctor_IdAndStartAtBetweenAndStatus(doctorid: Long, startAt: Instant, endAt: Instant, status: Appointment.Status): Long = repo.countByDoctor_IdAndStartAtBetweenAndStatus(doctorid, startAt, endAt, status)
    fun findByDoctorIdAndStartAtBetween(doctorId: Long, start: Instant, end: Instant): List<Appointment> = repo.findByDoctorIdAndStartAtBetween(doctorId, start, end)
    fun countNewPatientsToday(doctorId: Long, dayStart: Instant, dayEnd: Instant): Long = repo.countNewPatientsToday(doctorId, dayStart, dayEnd)
    fun existsNonCancelledByPatientIdAndDoctorIds(patientId: Long, doctorIds: Collection<Long>): Boolean = repo.existsNonCancelledByPatientIdAndDoctorIds(patientId, doctorIds)
    fun existsByDoctorIdAndPatientId(doctorId: Long, patientId: Long): Boolean = repo.existsByDoctorIdAndPatientId(doctorId, patientId)
    fun countDistinctPatientsByDoctorIdAndStartAtBetween(doctorId: Long, start: Instant, end: Instant): Long = repo.countDistinctPatientsByDoctorIdAndStartAtBetween(doctorId, start, end)
    fun countVideoByDoctorIdAndStartAtBetween(doctorId: Long, start: Instant, end: Instant): Long = repo.countVideoByDoctorIdAndStartAtBetween(doctorId, start, end)
    fun countInPersonByDoctorIdAndStartAtBetween(doctorId: Long, start: Instant, end: Instant): Long = repo.countInPersonByDoctorIdAndStartAtBetween(doctorId, start, end)
    fun countFutureByDoctorAndLocation(doctorId: Long, locationId: Long, now: Instant): Long = repo.countFutureByDoctorAndLocation(doctorId, locationId, now)
    fun findAppointmentsStartingBetween(start: Instant, end: Instant): List<Appointment> = repo.findAppointmentsStartingBetween(start, end)
    fun findAppointmentsForSmsReminder(now: Instant, horizonEnd: Instant, hoursBefore: Int): List<Appointment> = repo.findAppointmentsForSmsReminder(now, horizonEnd, hoursBefore)
    fun findPendingPaymentVideoAppointmentsStartingBetween(start: Instant, end: Instant): List<Appointment> = repo.findPendingPaymentVideoAppointmentsStartingBetween(start, end)
    fun findCompletedForPatientAmongDoctors(patientId: Long, doctorIds: Collection<Long>, pageable: Pageable): List<Appointment> = repo.findCompletedForPatientAmongDoctors(patientId, doctorIds, pageable)
    fun countByDoctorIdsAndStartAtBetween(doctorIds: Collection<Long>, start: Instant, end: Instant): Long = repo.countByDoctorIdsAndStartAtBetween(doctorIds, start, end)
    fun countDistinctPatientsByDoctorIdsAndStartAtBetween(doctorIds: Collection<Long>, start: Instant, end: Instant): Long = repo.countDistinctPatientsByDoctorIdsAndStartAtBetween(doctorIds, start, end)
    fun countDistinctPatientsByDoctor(doctorId: Long): Long = repo.countDistinctPatientsByDoctor(doctorId)
    fun countByDoctorIdAndStartAtBetween(doctorId: Long, start: Instant, end: Instant): Long = repo.countByDoctorIdAndStartAtBetween(doctorId, start, end)
    fun countVideoAllTime(doctorId: Long): Long = repo.countVideoAllTime(doctorId)
    fun countByDoctor_Id(doctorId: Long): Long = repo.countByDoctor_Id(doctorId)
    fun countByDoctor_IdAndStatus(doctorid: Long, status: Appointment.Status): Long = repo.countByDoctor_IdAndStatus(doctorid, status)
    fun findExpiredPaymentHolds(now: Instant): List<Appointment> = repo.findExpiredPaymentHolds(now)
    fun findMaxStartAtByDoctorId(doctorId: Long): Instant? = repo.findMaxStartAtByDoctorId(doctorId)
    fun findMostVisitedPatients(doctorId: Long, since: Instant, pageable: org.springframework.data.domain.Pageable): List<Array<Any>> = repo.findMostVisitedPatients(doctorId, since, pageable)
    fun findById(id: Long): Optional<Appointment> = repo.findById(id)
    fun flush() = repo.flush()
    fun save(entity: Appointment): Appointment = repo.save(entity)
}
