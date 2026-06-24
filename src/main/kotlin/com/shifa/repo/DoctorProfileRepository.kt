package com.shifa.repo

import com.shifa.domain.DoctorProfile
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import java.util.*

interface DoctorProfileRepository : JpaRepository<DoctorProfile, Long> {
    fun findAllByPracticeClinic_Id(clinicId: Long): List<DoctorProfile>

    fun findByUserId(userId: Long): Optional<DoctorProfile>

    @org.springframework.data.jpa.repository.Query(
        "SELECT d FROM DoctorProfile d LEFT JOIN FETCH d.practiceClinic WHERE d.user.id = :userId"
    )
    fun findByUserIdWithPracticeClinic(
        @org.springframework.data.repository.query.Param("userId") userId: Long,
    ): Optional<DoctorProfile>
    
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

    /** Admin doctor-activity screen: doctors matching name / legacy clinic / structured clinic name */
    @org.springframework.data.jpa.repository.Query(
        """
        SELECT DISTINCT d FROM DoctorProfile d
        JOIN FETCH d.user u
        LEFT JOIN FETCH d.practiceClinic pc
        WHERE (:search IS NULL OR :search = ''
            OR LOWER(CONCAT(TRIM(d.firstName), ' ', TRIM(d.lastName))) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(TRIM(COALESCE(d.clinic,''))) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(TRIM(COALESCE(pc.name,''))) LIKE LOWER(CONCAT('%', :search, '%')))
        """
    )
    fun findAllForAdminActivitySearch(
        @Param("search") search: String?,
    ): List<DoctorProfile>

    /** Admin doctor-activity list: profiles for a known set of user ids (avoids loading every profile). */
    @org.springframework.data.jpa.repository.Query(
        """
        SELECT DISTINCT d FROM DoctorProfile d
        JOIN FETCH d.user u
        LEFT JOIN FETCH d.practiceClinic pc
        WHERE d.user.id IN :userIds
        """
    )
    fun findByUserIdInWithPracticeClinic(@Param("userIds") userIds: Collection<Long>): List<DoctorProfile>
}
