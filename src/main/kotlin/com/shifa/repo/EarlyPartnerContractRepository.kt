package com.shifa.repo

import com.shifa.domain.EarlyPartnerContract
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface EarlyPartnerContractRepository : JpaRepository<EarlyPartnerContract, Long> {
    fun findByDoctorProfileId(doctorProfileId: Long): Optional<EarlyPartnerContract>

    fun findByDoctorProfileIdIn(doctorProfileIds: Collection<Long>): List<EarlyPartnerContract>
}
