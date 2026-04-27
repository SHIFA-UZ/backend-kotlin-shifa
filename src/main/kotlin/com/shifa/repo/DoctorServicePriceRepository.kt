package com.shifa.repo

import com.shifa.domain.DoctorServicePrice
import org.springframework.data.jpa.repository.JpaRepository

interface DoctorServicePriceRepository : JpaRepository<DoctorServicePrice, Long> {
    fun findByService_IdOrderByCurrencyAsc(serviceId: Long): List<DoctorServicePrice>
    fun deleteByService_Id(serviceId: Long): Long
}
