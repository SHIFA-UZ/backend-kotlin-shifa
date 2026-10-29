package com.shifa.doctors.application

import com.shifa.doctors.domain.DoctorLocation
import com.shifa.doctors.domain.DoctorService
import com.shifa.doctors.domain.DoctorServicePrice
import com.shifa.doctors.infrastructure.DoctorLocationRepository
import com.shifa.doctors.infrastructure.DoctorReviewRepository
import com.shifa.doctors.infrastructure.DoctorServicePriceRepository
import com.shifa.doctors.infrastructure.DoctorServiceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * What the public doctor listing shows next to each doctor (services, prices, rating, "has a clinic", "has a schedule"),
 * loaded for a whole candidate set with one query per concern instead of several per doctor (roadmap P5).
 */
@Service
class PublicDoctorCatalog(
    private val services: DoctorServiceRepository,
    private val prices: DoctorServicePriceRepository,
    private val locations: DoctorLocationRepository,
    private val reviews: DoctorReviewRepository,
    private val weeklyRules: com.shifa.appointments.application.WeeklyScheduleRules,
    private val dateRules: com.shifa.appointments.application.DateSpecificScheduleRules,
) {
    data class Rating(val average: Double?, val count: Long)

    /** Read-only lookups for the doctors a [listing] was loaded for; missing ids read as "nothing". */
    class Listing(
        private val servicesByDoctor: Map<Long, List<DoctorService>>,
        private val pricesByService: Map<Long, List<DoctorServicePrice>>,
        private val locationsByDoctor: Map<Long, List<DoctorLocation>>,
        private val doctorsWithSchedule: Set<Long>,
        private val ratings: Map<Long, Rating>,
    ) {
        fun activeServices(doctorId: Long): List<DoctorService> = servicesByDoctor[doctorId] ?: emptyList()
        fun prices(serviceId: Long): List<DoctorServicePrice> = pricesByService[serviceId] ?: emptyList()
        fun locations(doctorId: Long): List<DoctorLocation> = locationsByDoctor[doctorId] ?: emptyList()
        fun hasSchedule(doctorId: Long): Boolean = doctorId in doctorsWithSchedule
        fun averageRating(doctorId: Long): Double? = ratings[doctorId]?.average
        fun reviewCount(doctorId: Long): Long = ratings[doctorId]?.count ?: 0L
    }

    @Transactional(readOnly = true)
    fun listing(doctorIds: Collection<Long>): Listing {
        if (doctorIds.isEmpty()) return Listing(emptyMap(), emptyMap(), emptyMap(), emptySet(), emptyMap())
        val activeServices = services.findActiveByDoctorIdsOrderByCreatedAtAsc(doctorIds)
        val servicePrices = if (activeServices.isEmpty()) emptyList() else prices.findByServiceIdsOrderByCurrencyAsc(activeServices.map { it.id })
        return Listing(
            servicesByDoctor = activeServices.groupBy { it.doctor.id ?: 0L },
            pricesByService = servicePrices.groupBy { it.service.id },
            locationsByDoctor = locations.findByDoctor_IdInOrderByIsPrimaryDescIdAsc(doctorIds).groupBy { it.doctor.id ?: 0L },
            doctorsWithSchedule = (weeklyRules.findDoctorIdsWithRules(doctorIds) + dateRules.findDoctorIdsWithRules(doctorIds)).toSet(),
            ratings = reviews.summarizeByDoctorIds(doctorIds).associate { it.doctorId to Rating(it.averageRating, it.reviewCount) },
        )
    }
}
