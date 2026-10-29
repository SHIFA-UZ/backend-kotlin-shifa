package com.shifa.patients.infrastructure

import com.shifa.patients.domain.CarePartnershipProgressUpdate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CarePartnershipProgressUpdateRepository : JpaRepository<CarePartnershipProgressUpdate, Long> {

    @Query(
        """
        SELECT u FROM CarePartnershipProgressUpdate u
        WHERE u.partnership.id = :partnershipId
        ORDER BY u.createdAt ASC
        """
    )
    fun findByPartnershipIdOrderByCreatedAtAsc(
        @Param("partnershipId") partnershipId: Long,
    ): List<CarePartnershipProgressUpdate>
}
