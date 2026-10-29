package com.shifa.doctors.api.dto

data class PriceDto(
    val id: Long? = null,
    val amountMinor: Long,
    val currency: String,
    /** When null, this price applies to all locations unless a location-specific row exists. */
    val locationId: Long? = null
)

data class UpsertServiceRequest(
    val title: String,
    val description: String? = null,
    val isActive: Boolean = true,
    val isFreeConsultation: Boolean = false,
    val paymentType: String? = null,
    val groupId: Long? = null,
    val prices: List<PriceDto> = emptyList()
)
