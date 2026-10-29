package com.shifa.doctors.infrastructure

import com.shifa.doctors.domain.DoctorServicePrice
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface DoctorServicePriceRepository : JpaRepository<DoctorServicePrice, Long> {
    @EntityGraph(attributePaths = ["location"])
    fun findByService_IdOrderByCurrencyAsc(serviceId: Long): List<DoctorServicePrice>

    fun deleteByService_Id(serviceId: Long): Long

    /** Prices of several services in one query (public listing), by currency like the per-service variant. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p FROM DoctorServicePrice p LEFT JOIN FETCH p.location WHERE p.service.id IN :serviceIds ORDER BY p.currency ASC, p.id ASC",
    )
    fun findByServiceIdsOrderByCurrencyAsc(
        @org.springframework.data.repository.query.Param("serviceIds") serviceIds: Collection<Long>,
    ): List<DoctorServicePrice>
}
