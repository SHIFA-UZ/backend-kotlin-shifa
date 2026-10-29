package com.shifa.doctors.application

import com.shifa.doctors.domain.DoctorServicePrice
import com.shifa.doctors.infrastructure.DoctorServicePriceRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to DoctorServicePrice records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [DoctorServicePriceRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class DoctorServicePrices(private val repo: DoctorServicePriceRepository) {
    fun findByService_IdOrderByCurrencyAsc(serviceId: Long): List<DoctorServicePrice> = repo.findByService_IdOrderByCurrencyAsc(serviceId)
    fun deleteByService_Id(serviceId: Long): Long = repo.deleteByService_Id(serviceId)
    fun flush() = repo.flush()
    fun save(entity: DoctorServicePrice): DoctorServicePrice = repo.save(entity)
}
