package com.shifa.repo

import com.shifa.domain.ClinicMembership
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ClinicMembershipRepository : JpaRepository<ClinicMembership, Long> {

    fun findByUserIdAndActiveTrue(userId: Long): List<ClinicMembership>

    @Query(
        """
        SELECT m FROM ClinicMembership m
        JOIN FETCH m.clinic
        WHERE m.user.id = :userId AND m.active = true
        """
    )
    fun findByUserIdAndActiveTrueWithClinic(@Param("userId") userId: Long): List<ClinicMembership>

    fun findByClinicIdAndActiveTrue(clinicId: Long): List<ClinicMembership>

    fun findByUserIdAndClinicIdAndActiveTrue(userId: Long, clinicId: Long): ClinicMembership?

    fun findByDoctorProfile_IdAndActiveTrue(doctorProfileId: Long): List<ClinicMembership>

    fun findByClinic_IdAndUser_Id(clinicId: Long, userId: Long): ClinicMembership?
}
