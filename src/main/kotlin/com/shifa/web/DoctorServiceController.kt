package com.shifa.web

import com.shifa.domain.DoctorService
import com.shifa.domain.DoctorServiceGroup
import com.shifa.domain.DoctorServicePrice
import com.shifa.repo.DoctorLocationRepository
import com.shifa.repo.DoctorServiceGroupRepository
import com.shifa.repo.DoctorServicePriceRepository
import com.shifa.repo.DoctorServiceRepository
import com.shifa.security.DoctorPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/api/doctors/me/services")
class DoctorServiceController(
    private val services: DoctorServiceRepository,
    private val prices: DoctorServicePriceRepository,
    private val locations: DoctorLocationRepository,
    private val groups: DoctorServiceGroupRepository
) {
    data class PriceDto(
        val id: Long? = null,
        val amountMinor: Long,
        val currency: String,
        /** When null, this price applies to all locations unless a location-specific row exists. */
        val locationId: Long? = null
    )

    data class ServiceDto(
        val id: Long? = null,
        val title: String,
        val description: String?,
        val isActive: Boolean,
        /** When true, video bookings with this service skip payment (free consultation). */
        val isFreeConsultation: Boolean = false,
        /** Managed via clinic workspace catalog; cannot be edited here. */
        val clinicManaged: Boolean = false,
        val groupId: Long? = null,
        val groupName: String? = null,
        val groupSortOrder: Int? = null,
        val prices: List<PriceDto> = emptyList()
    )

    data class UpsertServiceRequest(
        val title: String,
        val description: String? = null,
        val isActive: Boolean = true,
        val isFreeConsultation: Boolean = false,
        val groupId: Long? = null,
        val prices: List<PriceDto> = emptyList()
    )

    @GetMapping
    @Transactional(readOnly = true)
    fun list(@AuthenticationPrincipal principal: DoctorPrincipal): List<ServiceDto> {
        val doctorId = principal.profile.id ?: return emptyList()
        return services.findByDoctorIdOrderByCreatedAtAsc(doctorId)
            .sortedWith(serviceDisplayOrder())
            .map { it.toDto() }
    }

    @PostMapping
    @Transactional
    fun create(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: UpsertServiceRequest
    ): ServiceDto {
        val doctor = principal.profile
        validateUpsert(body, doctor.id!!)
        val group = resolveGroup(doctor.id!!, body.groupId)
        val saved = services.save(
            DoctorService(
                doctor = doctor,
                title = body.title.trim(),
                description = body.description?.trim(),
                isActive = body.isActive,
                isFreeConsultation = body.isFreeConsultation,
                group = group
            )
        )
        syncPrices(saved, body.prices, body.isFreeConsultation)
        return saved.toDto()
    }

    @PatchMapping("/{serviceId}")
    @Transactional
    fun update(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable serviceId: Long,
        @RequestBody body: UpsertServiceRequest
    ): ServiceDto {
        val doctorId = principal.profile.id ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val service = services.findById(serviceId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found")
        }
        if (service.doctor.id != doctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Service does not belong to current doctor")
        }
        if (service.sourceCatalogItem != null) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "This service is managed by the clinic catalog. Edit it under Clinic → Services.",
            )
        }
        validateUpsert(body, doctorId)
        service.title = body.title.trim()
        service.description = body.description?.trim()
        service.isActive = body.isActive
        service.isFreeConsultation = body.isFreeConsultation
        service.group = resolveGroup(doctorId, body.groupId)
        service.updatedAt = Instant.now()
        services.save(service)
        syncPrices(service, body.prices, body.isFreeConsultation)
        return service.toDto()
    }

    @DeleteMapping("/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun delete(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable serviceId: Long
    ) {
        val doctorId = principal.profile.id ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val service = services.findById(serviceId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found")
        }
        if (service.doctor.id != doctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Service does not belong to current doctor")
        }
        if (service.sourceCatalogItem != null) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "This service is managed by the clinic catalog. Edit it under Clinic → Services.",
            )
        }
        services.delete(service)
    }

    private fun resolveGroup(doctorId: Long, groupId: Long?): DoctorServiceGroup? {
        if (groupId == null) return null
        val g = groups.findById(groupId).orElseThrow {
            ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown service group")
        }
        if (g.doctor.id != doctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Group does not belong to current doctor")
        }
        return g
    }

    private fun validateUpsert(body: UpsertServiceRequest, doctorId: Long) {
        if (!body.isFreeConsultation) {
            val valid = body.prices.any { it.amountMinor > 0 && it.currency.isNotBlank() }
            if (!valid) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Paid services must include at least one price greater than zero"
                )
            }
        }
        if (body.prices.isNotEmpty()) {
            val keys = body.prices.map {
                it.currency.trim().uppercase() to it.locationId
            }
            if (keys.size != keys.distinct().size) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Duplicate price for the same currency and location scope"
                )
            }
            body.prices.forEach { p ->
                val locId = p.locationId ?: return@forEach
                val loc = locations.findById(locId).orElseThrow {
                    ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown location for price row")
                }
                if (loc.doctor.id != doctorId) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "Location does not belong to current doctor")
                }
            }
        }
    }

    private fun syncPrices(service: DoctorService, input: List<PriceDto>, isFreeConsultation: Boolean) {
        prices.deleteByService_Id(service.id)
        if (isFreeConsultation) return
        input
            .filter { it.amountMinor > 0 && it.currency.isNotBlank() }
            .forEach { dto ->
                val loc = dto.locationId?.let { lid ->
                    locations.findById(lid).orElseThrow {
                        ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown location for price row")
                    }.also {
                        if (it.doctor.id != service.doctor.id) {
                            throw ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "Location does not belong to current doctor"
                            )
                        }
                    }
                }
                prices.save(
                    DoctorServicePrice(
                        service = service,
                        location = loc,
                        amountMinor = dto.amountMinor,
                        currency = dto.currency.trim().uppercase()
                    )
                )
            }
    }

    private fun DoctorService.toDto(): ServiceDto {
        val servicePrices = prices.findByService_IdOrderByCurrencyAsc(this.id).map {
            PriceDto(
                id = it.id,
                amountMinor = it.amountMinor,
                currency = it.currency,
                locationId = it.location?.id
            )
        }
        return ServiceDto(
            id = this.id,
            title = this.title,
            description = this.description,
            isActive = this.isActive,
            isFreeConsultation = this.isFreeConsultation,
            clinicManaged = this.sourceCatalogItem != null,
            groupId = this.group?.id,
            groupName = this.group?.name,
            groupSortOrder = this.group?.sortOrder,
            prices = servicePrices
        )
    }

    private fun serviceDisplayOrder(): Comparator<DoctorService> =
        compareBy<DoctorService> { it.group?.sortOrder ?: Int.MAX_VALUE }
            .thenBy { it.group?.id ?: Long.MAX_VALUE }
            .thenBy { it.createdAt }
}
