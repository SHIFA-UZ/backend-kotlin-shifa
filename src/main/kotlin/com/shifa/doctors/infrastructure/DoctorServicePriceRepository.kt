package com.shifa.doctors.infrastructure

import com.shifa.doctors.domain.DoctorServicePrice
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface DoctorServicePriceRepository : JpaRepository<DoctorServicePrice, Long> {
    @EntityGraph(attributePaths = ["location"])
    fun findByService_IdOrderByCurrencyAsc(serviceId: Long): List<DoctorServicePrice>

    fun deleteByService_Id(serviceId: Long): Long
}
