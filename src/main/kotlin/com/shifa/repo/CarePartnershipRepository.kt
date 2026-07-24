package com.shifa.repo

import com.shifa.domain.CarePartnership
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CarePartnershipRepository : JpaRepository<CarePartnership, Long> {

    @Query(
        """
        SELECT p FROM CarePartnership p
        WHERE p.initiatingDoctor.id = :doctorId OR p.partnerDoctor.id = :doctorId
        ORDER BY p.updatedAt DESC
        """
    )
    fun findForDoctor(@Param("doctorId") doctorId: Long): List<CarePartnership>

    @Query(
        """
        SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM CarePartnership p
        WHERE p.patient.id = :patientId
          AND p.status = com.shifa.domain.CarePartnership.Status.ACTIVE
          AND (p.initiatingDoctor.id = :doctorId OR p.partnerDoctor.id = :doctorId)
        """
    )
    fun existsActiveForDoctorAndPatient(
        @Param("doctorId") doctorId: Long,
        @Param("patientId") patientId: Long,
    ): Boolean

    @Query(
        """
        SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM CarePartnership p
        WHERE p.patient.id = :patientId
          AND p.status = com.shifa.domain.CarePartnership.Status.PENDING
          AND p.initiatingDoctor.id = :initiatingDoctorId
          AND p.partnerDoctor.id = :partnerDoctorId
        """
    )
    fun existsPendingInvite(
        @Param("initiatingDoctorId") initiatingDoctorId: Long,
        @Param("partnerDoctorId") partnerDoctorId: Long,
        @Param("patientId") patientId: Long,
    ): Boolean
}
