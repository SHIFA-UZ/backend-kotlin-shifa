// src/main/kotlin/com/shifa/repo/PatientProfileRepository.kt
package com.shifa.repo

import com.shifa.domain.PatientProfile
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

// ✅ JpaRepository requires two type parameters: <Entity, ID>
interface PatientProfileRepository : JpaRepository<PatientProfile, Long> {

    @Modifying
    @Query("update PatientProfile p set p.createdByDoctor = null where p.createdByDoctor.id = :doctorId")
    fun clearCreatedByDoctor(@Param("doctorId") doctorId: Long): Int

    /**
     * Patients visible to a doctor: those with at least one appointment with this doctor,
     * or those created by this doctor (e.g. via "New patient" on the patients screen).
     * Distinct, paginated.
     */
    @Query("""
        SELECT p FROM PatientProfile p
        WHERE p.id IN (
            SELECT DISTINCT a.patient.id FROM Appointment a
            WHERE a.doctor.id = :doctorId
        )
        OR p.createdByDoctor.id = :doctorId
        ORDER BY p.fullName
    """)
    fun findDistinctByDoctorAppointments(
        @Param("doctorId") doctorId: Long,
        pageable: Pageable
    ): Page<PatientProfile>

    /**
     * Patients visible to any doctor in [doctorIds] (same roster / clinic sharing).
     */
    @Query("""
        SELECT DISTINCT p FROM PatientProfile p
        WHERE p.id IN (
            SELECT DISTINCT a.patient.id FROM Appointment a
            WHERE a.doctor.id IN :doctorIds
        )
        OR (p.createdByDoctor IS NOT NULL AND p.createdByDoctor.id IN :doctorIds)
        ORDER BY p.fullName
    """)
    fun findDistinctVisibleToDoctors(
        @Param("doctorIds") doctorIds: Collection<Long>,
        pageable: Pageable
    ): Page<PatientProfile>
    // Use native query with LIMIT to handle duplicate phone/email
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT * FROM patient_profiles WHERE phone = :phone ORDER BY id DESC LIMIT 1",
        nativeQuery = true
    )
    fun findByPhone(@org.springframework.data.repository.query.Param("phone") phone: String): Optional<PatientProfile>
    
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT * FROM patient_profiles WHERE email = :email ORDER BY id DESC LIMIT 1",
        nativeQuery = true
    )
    fun findByEmail(@org.springframework.data.repository.query.Param("email") email: String): Optional<PatientProfile>

    /** Used for uniqueness: exact match on normalized phone (E.164-like). */
    fun findByPhoneNormalized(phoneNormalized: String): Optional<PatientProfile>

    /**
     * Find patient profile by user_id (for multi-role support: same user can be doctor and patient)
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p FROM PatientProfile p WHERE p.user.id = :userId"
    )
    fun findByUserId(@org.springframework.data.repository.query.Param("userId") userId: Long): Optional<PatientProfile>
    
    // Search patients by query string (name, email, or phone) - filters at database level
    @org.springframework.data.jpa.repository.Query(
        "SELECT p FROM PatientProfile p WHERE " +
        "LOWER(p.fullName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
        "LOWER(p.email) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
        "LOWER(p.phone) LIKE LOWER(CONCAT('%', :query, '%'))"
    )
    fun searchByQuery(@org.springframework.data.repository.query.Param("query") query: String): List<PatientProfile>

    /** Admin user search: user IDs of patients whose fullName contains q (JPQL). Only where user is set. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p.user.id FROM PatientProfile p WHERE p.user IS NOT NULL AND " +
        "LOWER(p.fullName) LIKE LOWER(CONCAT('%', :q, '%'))"
    )
    fun findUserIdsBySearch(@org.springframework.data.repository.query.Param("q") q: String): List<Long>

    /** Eager-load user for admin deleted-patient export (avoids lazy-init issues). */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p FROM PatientProfile p JOIN FETCH p.user WHERE p.id = :id"
    )
    fun findByIdWithUser(@Param("id") id: Long): Optional<PatientProfile>

    /** Full clinic roster: patients with any non-cancelled appointment with a clinic doctor. */
    @Query(
        """
        SELECT DISTINCT p FROM PatientProfile p
        WHERE p.id IN (
            SELECT DISTINCT a.patient.id FROM Appointment a
            WHERE a.doctor.id IN :clinicDoctorIds AND a.status != 'CANCELLED'
        )
        AND (
            :q IS NULL OR :q = '' OR
            LOWER(p.fullName) LIKE LOWER(CONCAT('%', :q, '%')) OR
            LOWER(COALESCE(p.phone, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
            LOWER(COALESCE(p.email, '')) LIKE LOWER(CONCAT('%', :q, '%'))
        )
        ORDER BY p.fullName
        """
    )
    fun findClinicRosterForDoctors(
        @Param("clinicDoctorIds") clinicDoctorIds: Collection<Long>,
        @Param("q") q: String?,
        pageable: Pageable
    ): Page<PatientProfile>

    /**
     * Doctor-scoped clinic roster: patients who saw this doctor at the clinic, or were created by this doctor.
     * NURSE/DOCTOR product rules default to scoped visibility.
     */
    @Query(
        """
        SELECT DISTINCT p FROM PatientProfile p
        WHERE (
            p.id IN (
                SELECT DISTINCT a.patient.id FROM Appointment a
                WHERE a.doctor.id = :actorDoctorId AND a.status != 'CANCELLED'
            )
            OR p.createdByDoctor.id = :actorDoctorId
        )
        AND (
            :q IS NULL OR :q = '' OR
            LOWER(p.fullName) LIKE LOWER(CONCAT('%', :q, '%')) OR
            LOWER(COALESCE(p.phone, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
            LOWER(COALESCE(p.email, '')) LIKE LOWER(CONCAT('%', :q, '%'))
        )
        ORDER BY p.fullName
        """
    )
    fun findClinicRosterScopedToDoctor(
        @Param("actorDoctorId") actorDoctorId: Long,
        @Param("q") q: String?,
        pageable: Pageable
    ): Page<PatientProfile>
}

