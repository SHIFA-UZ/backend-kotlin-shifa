package com.shifa.repo

import com.shifa.domain.Appointment
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional

interface AppointmentRepository : JpaRepository<Appointment, Long> {

    /**
     * Load appointment with doctor and patient eagerly to avoid LazyInitializationException
     * when building DTOs (e.g. patient appointment details / signing).
     */
    @Query("SELECT a FROM Appointment a LEFT JOIN FETCH a.doctor LEFT JOIN FETCH a.patient WHERE a.id = :id")
    fun findByIdWithDoctorAndPatient(@Param("id") id: Long): Optional<Appointment>

    /**
     * Hard delete all appointments for a doctor (admin calendar reset).
     */
    @Modifying
    @Query("DELETE FROM Appointment a WHERE a.doctor.id = :doctorId")
    fun deleteByDoctorId(@Param("doctorId") doctorId: Long): Int

    /**
     * Hard delete all appointments for a patient (user deletion).
     */
    @Modifying
    @Query("DELETE FROM Appointment a WHERE a.patient.id = :patientId")
    fun deleteByPatientId(@Param("patientId") patientId: Long): Int

    /**
     * Returns appointments for a doctor overlapping [start, end).
     * Used by the calendar day feed to hide overlapping free slots.
     * Excludes cancelled appointments.
     * Eager-fetches doctor and patient so calendar API always returns patientId.
     */
    @Query("""
        SELECT DISTINCT a FROM Appointment a
        LEFT JOIN FETCH a.doctor
        LEFT JOIN FETCH a.patient
        WHERE a.doctor.id = :doctorId
          AND a.startAt < :end
          AND a.endAt   > :start
          AND a.status != 'CANCELLED'
    """)
    fun findOverlapping(doctorId: Long, start: Instant, end: Instant): List<Appointment>

    /**
     * Today's appointments (UTC) for the Home screen.
     * Excludes cancelled appointments.
     */
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.doctor.id = :doctorId
          AND a.startAt >= :start
          AND a.endAt   <  :end
          AND a.status != 'CANCELLED'
        ORDER BY a.startAt ASC
    """)
    fun findForDay(doctorId: Long, start: Instant, end: Instant): List<Appointment>

    /**
     * Find appointments by patient ID.
     */
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.patient.id = :patientId
        ORDER BY a.startAt DESC
    """)
    fun findByPatientId(patientId: Long): List<Appointment>

    /**
     * Deleted-patient legal export: load doctor (+ doctor.user) eagerly so DTO mapping never lazy-loads.
     */
    @Query(
        """
        SELECT a FROM Appointment a
        JOIN FETCH a.doctor d
        JOIN FETCH d.user
        WHERE a.patient.id = :patientId
        ORDER BY a.startAt DESC
        """
    )
    fun findByPatientIdWithDoctorForExport(@Param("patientId") patientId: Long): List<Appointment>

    /**
     * Find appointments for this patient with this doctor only (for AI briefing).
     * Excludes cancelled. Newest first.
     */
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.patient.id = :patientId
          AND a.doctor.id = :doctorId
          AND a.status != 'CANCELLED'
        ORDER BY a.startAt DESC
    """)
    fun findByPatientIdAndDoctorIdOrderByStartAtDesc(
        @Param("patientId") patientId: Long,
        @Param("doctorId") doctorId: Long
    ): List<Appointment>

    /**
     * Find appointments by patient ID within date range.
     */
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.patient.id = :patientId
          AND a.startAt >= :start
          AND a.endAt < :end
        ORDER BY a.startAt ASC
    """)
    fun findByPatientIdAndDateRange(patientId: Long, start: Instant, end: Instant): List<Appointment>

    /**
     * Returns appointments for a patient overlapping [start, end).
     * Used to prevent double booking - patient cannot have two appointments at the same time.
     * Excludes cancelled appointments.
     */
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.patient.id = :patientId
          AND a.startAt < :end
          AND a.endAt > :start
          AND a.status != 'CANCELLED'
    """)
    fun findOverlappingForPatient(patientId: Long, start: Instant, end: Instant): List<Appointment>

    // ---------- Analytics (doctor-scoped, aggregate only; no PII) ----------

    fun countByDoctor_IdAndStartAtBetweenAndStatus(
        doctor_Id: Long,
        startAt: Instant,
        endAt: Instant,
        status: Appointment.Status
    ): Long

    /** All appointments for doctor in range (for grouping by date in service). */
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.doctor.id = :doctorId
          AND a.startAt >= :start
          AND a.startAt < :end
        ORDER BY a.startAt
    """)
    fun findByDoctorIdAndStartAtBetween(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: Instant,
        @Param("end") end: Instant
    ): List<Appointment>

    /** New patients today: distinct patients with an appointment today whose first appointment with this doctor is today. */
    @Query(value = """
        SELECT COUNT(DISTINCT a.patient_id) FROM appointments a
        WHERE a.doctor_id = :doctorId
          AND a.start_at >= :dayStart AND a.start_at < :dayEnd
          AND NOT EXISTS (
            SELECT 1 FROM appointments a2
            WHERE a2.doctor_id = :doctorId AND a2.patient_id = a.patient_id AND a2.start_at < :dayStart
          )
    """, nativeQuery = true)
    fun countNewPatientsToday(
        @Param("doctorId") doctorId: Long,
        @Param("dayStart") dayStart: Instant,
        @Param("dayEnd") dayEnd: Instant
    ): Long

    /** True if the patient has a non-cancelled visit with any of the given clinic doctors (clinic roster link). */
    @Query(
        """
        SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END FROM Appointment a
        WHERE a.patient.id = :patientId AND a.status != 'CANCELLED' AND a.doctor.id IN :doctorIds
        """
    )
    fun existsNonCancelledByPatientIdAndDoctorIds(
        @Param("patientId") patientId: Long,
        @Param("doctorIds") doctorIds: Collection<Long>,
    ): Boolean

    /** True if the doctor has at least one appointment (past or future) with this patient. */
    fun existsByDoctorIdAndPatientId(doctorId: Long, patientId: Long): Boolean =
        countByDoctor_IdAndPatient_Id(doctorId, patientId) > 0

    fun countByDoctor_IdAndPatient_Id(doctorId: Long, patientId: Long): Long

    /** Distinct patient count in range (active patients). */
    @Query("SELECT COUNT(DISTINCT a.patient.id) FROM Appointment a WHERE a.doctor.id = :doctorId AND a.startAt >= :start AND a.startAt < :end")
    fun countDistinctPatientsByDoctorIdAndStartAtBetween(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: Instant,
        @Param("end") end: Instant
    ): Long

    /** Count appointments where location indicates video (e.g. contains "Video"). */
    @Query("""
        SELECT COUNT(a) FROM Appointment a
        WHERE a.doctor.id = :doctorId
          AND a.startAt >= :start AND a.startAt < :end
          AND LOWER(a.location) LIKE '%video%'
    """)
    fun countVideoByDoctorIdAndStartAtBetween(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: Instant,
        @Param("end") end: Instant
    ): Long

    /** Count appointments where location is not video (in-person). */
    @Query("""
        SELECT COUNT(a) FROM Appointment a
        WHERE a.doctor.id = :doctorId
          AND a.startAt >= :start AND a.startAt < :end
          AND (LOWER(a.location) NOT LIKE '%video%' OR a.location IS NULL)
    """)
    fun countInPersonByDoctorIdAndStartAtBetween(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: Instant,
        @Param("end") end: Instant
    ): Long

    /**
     * Count future (non-cancelled) appointments for a doctor at a specific location. Used
     * to prevent deleting a location that still has upcoming bookings.
     */
    @Query("""
        SELECT COUNT(a) FROM Appointment a
        WHERE a.doctor.id = :doctorId
          AND a.locationRef.id = :locationId
          AND a.status != 'CANCELLED'
          AND a.endAt > :now
    """)
    fun countFutureByDoctorAndLocation(
        @Param("doctorId") doctorId: Long,
        @Param("locationId") locationId: Long,
        @Param("now") now: Instant
    ): Long

    /** Appointments starting in [start, end) for reminder notifications (e.g. 1 hour before). */
    @Query("""
        SELECT a FROM Appointment a
        JOIN FETCH a.patient
        JOIN FETCH a.doctor
        WHERE a.startAt >= :start AND a.startAt < :end
          AND a.status != 'CANCELLED'
    """)
    fun findAppointmentsStartingBetween(
        @Param("start") start: Instant,
        @Param("end") end: Instant
    ): List<Appointment>

    /**
     * Video consultations with payment still pending, starting in [start, end) (UTC).
     * Used for scheduled payment nudges at 24h / 6h / 1h before start.
     */
    @Query(
        """
        SELECT DISTINCT a FROM Appointment a
        JOIN FETCH a.patient
        JOIN FETCH a.doctor
        WHERE a.startAt >= :start AND a.startAt < :end
          AND a.status IN ('REQUESTED', 'CONFIRMED', 'IN_PROGRESS')
          AND a.paymentStatus = 'PENDING'
          AND LOWER(a.location) LIKE '%video%'
        """
    )
    fun findPendingPaymentVideoAppointmentsStartingBetween(
        @Param("start") start: Instant,
        @Param("end") end: Instant
    ): List<Appointment>

    /** Latest completed visits with doctors in roster (for prophylaxis anchor). */
    @Query(
        """
        SELECT a FROM Appointment a
        WHERE a.patient.id = :patientId
          AND a.status = 'COMPLETED'
          AND a.doctor.id IN :doctorIds
        ORDER BY a.endAt DESC
        """
    )
    fun findCompletedForPatientAmongDoctors(
        @Param("patientId") patientId: Long,
        @Param("doctorIds") doctorIds: Collection<Long>,
        pageable: Pageable
    ): List<Appointment>

    @Query(
        """
        SELECT COUNT(a) FROM Appointment a
        WHERE a.doctor.id IN :doctorIds
          AND a.startAt >= :start
          AND a.startAt < :end
          AND a.status != 'CANCELLED'
        """
    )
    fun countByDoctorIdsAndStartAtBetween(
        @Param("doctorIds") doctorIds: Collection<Long>,
        @Param("start") start: Instant,
        @Param("end") end: Instant
    ): Long

    @Query(
        """
        SELECT COUNT(DISTINCT a.patient.id) FROM Appointment a
        WHERE a.doctor.id IN :doctorIds
          AND a.startAt >= :start
          AND a.startAt < :end
          AND a.status != 'CANCELLED'
        """
    )
    fun countDistinctPatientsByDoctorIdsAndStartAtBetween(
        @Param("doctorIds") doctorIds: Collection<Long>,
        @Param("start") start: Instant,
        @Param("end") end: Instant
    ): Long
}
