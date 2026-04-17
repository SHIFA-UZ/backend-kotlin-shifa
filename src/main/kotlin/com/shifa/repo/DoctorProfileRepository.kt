package com.shifa.repo

import com.shifa.domain.DoctorProfile
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface DoctorProfileRepository : JpaRepository<DoctorProfile, Long> {
    fun findByUserId(userId: Long): Optional<DoctorProfile>
    
    // Search doctors by query string (name, email, phone, profession, clinic) - only enabled (non-disabled) doctors
    @org.springframework.data.jpa.repository.Query(
        "SELECT d FROM DoctorProfile d WHERE d.user.enabled = true AND (" +
        "LOWER(CONCAT(d.firstName, ' ', d.lastName)) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
        "LOWER(d.user.email) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
        "LOWER(d.user.phone) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
        "LOWER(d.profession) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
        "LOWER(d.clinic) LIKE LOWER(CONCAT('%', :query, '%')))"
    )
    fun searchByQuery(@org.springframework.data.repository.query.Param("query") query: String): List<DoctorProfile>

    // All doctors with enabled user only (for public listing when no search/profession filter)
    @org.springframework.data.jpa.repository.Query("SELECT d FROM DoctorProfile d WHERE d.user.enabled = true")
    fun findAllByUserEnabled(): List<DoctorProfile>
    
    // Search doctors with filters - only enabled doctors
    @org.springframework.data.jpa.repository.Query(
        "SELECT d FROM DoctorProfile d WHERE d.user.enabled = true AND " +
        "(:search IS NULL OR :search = '' OR " +
        " LOWER(CONCAT(d.firstName, ' ', d.lastName)) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
        " LOWER(d.profession) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
        " LOWER(d.clinic) LIKE LOWER(CONCAT('%', :search, '%'))) AND " +
        "(:profession IS NULL OR :profession = '' OR LOWER(d.profession) = LOWER(:profession))"
    )
    fun searchWithFilters(
        @org.springframework.data.repository.query.Param("search") search: String?,
        @org.springframework.data.repository.query.Param("profession") profession: String?
    ): List<DoctorProfile>

    /** Admin user search: user IDs of doctors whose first or last name contains q (JPQL). */
    @org.springframework.data.jpa.repository.Query(
        "SELECT d.user.id FROM DoctorProfile d WHERE " +
        "LOWER(d.firstName) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(d.lastName) LIKE LOWER(CONCAT('%', :q, '%'))"
    )
    fun findUserIdsBySearch(@org.springframework.data.repository.query.Param("q") q: String): List<Long>
}
