package com.shifa.repo

import com.shifa.domain.ClinicMembership
import org.springframework.data.jpa.repository.JpaRepository

interface ClinicMembershipRepository : JpaRepository<ClinicMembership, Long> {

    fun findByUserIdAndActiveTrue(userId: Long): List<ClinicMembership>

    fun findByClinicIdAndActiveTrue(clinicId: Long): List<ClinicMembership>

    fun findByUserIdAndClinicIdAndActiveTrue(userId: Long, clinicId: Long): ClinicMembership?

    fun findByDoctorProfile_IdAndActiveTrue(doctorProfileId: Long): List<ClinicMembership>

    fun findByClinic_IdAndUser_Id(clinicId: Long, userId: Long): ClinicMembership?
}
