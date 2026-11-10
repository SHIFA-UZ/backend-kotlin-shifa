package com.shifa.clinics.infrastructure

import com.shifa.clinics.domain.Clinic
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ClinicRepository : JpaRepository<Clinic, Long> {
    /** Admin list search; [pattern] is a lower-case LIKE pattern over name, phone, e-mail and address. */
    @Query(
        """SELECT c FROM Clinic c WHERE LOWER(c.name) LIKE :pattern OR LOWER(COALESCE(c.phone, '')) LIKE :pattern
           OR LOWER(COALESCE(c.email, '')) LIKE :pattern OR LOWER(COALESCE(c.address, '')) LIKE :pattern"""
    )
    fun searchForAdmin(@Param("pattern") pattern: String, pageable: Pageable): Page<Clinic>
}
