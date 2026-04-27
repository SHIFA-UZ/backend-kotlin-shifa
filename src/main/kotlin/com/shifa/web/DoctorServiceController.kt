package com.shifa.web

import com.shifa.domain.DoctorService
import com.shifa.domain.DoctorServicePrice
import com.shifa.repo.DoctorServicePriceRepository
import com.shifa.repo.DoctorServiceRepository
import com.shifa.security.DoctorPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/api/doctors/me/services")
class DoctorServiceController(
    private val services: DoctorServiceRepository,
    private val prices: DoctorServicePriceRepository
) {
    data class PriceDto(
        val id: Long? = null,
        val amountMinor: Long,
        val currency: String
    )

    data class ServiceDto(
        val id: Long? = null,
        val title: String,
        val description: String?,
        val isActive: Boolean,
        val prices: List<PriceDto> = emptyList()
    )

    data class UpsertServiceRequest(
        val title: String,
        val description: String? = null,
        val isActive: Boolean = true,
        val prices: List<PriceDto> = emptyList()
    )

    @GetMapping
    fun list(@AuthenticationPrincipal principal: DoctorPrincipal): List<ServiceDto> {
        val doctorId = principal.profile.id ?: return emptyList()
        return services.findByDoctorIdOrderByCreatedAtAsc(doctorId).map { it.toDto() }
    }

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: UpsertServiceRequest
    ): ServiceDto {
        val doctor = principal.profile
        val saved = services.save(
            DoctorService(
                doctor = doctor,
                title = body.title.trim(),
                description = body.description?.trim(),
                isActive = body.isActive
            )
        )
        syncPrices(saved, body.prices)
        return saved.toDto()
    }

    @PatchMapping("/{serviceId}")
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
        service.title = body.title.trim()
        service.description = body.description?.trim()
        service.isActive = body.isActive
        service.updatedAt = Instant.now()
        services.save(service)
        syncPrices(service, body.prices)
        return service.toDto()
    }

    @DeleteMapping("/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
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
        services.delete(service)
    }

    private fun syncPrices(service: DoctorService, input: List<PriceDto>) {
        prices.deleteByService_Id(service.id)
        input
            .filter { it.amountMinor > 0 && it.currency.isNotBlank() }
            .forEach {
                prices.save(
                    DoctorServicePrice(
                        service = service,
                        amountMinor = it.amountMinor,
                        currency = it.currency.trim().uppercase()
                    )
                )
            }
    }

    private fun DoctorService.toDto(): ServiceDto {
        val servicePrices = prices.findByService_IdOrderByCurrencyAsc(this.id).map {
            PriceDto(id = it.id, amountMinor = it.amountMinor, currency = it.currency)
        }
        return ServiceDto(
            id = this.id,
            title = this.title,
            description = this.description,
            isActive = this.isActive,
            prices = servicePrices
        )
    }
}
