package com.shifa.service

import com.shifa.domain.DoctorServicePrice

/**
 * Resolves which price rows apply for a booking: location-specific overrides per currency,
 * falling back to global rows ([DoctorServicePrice.location] is null).
 */
object DoctorServicePricing {

    fun effectivePricesForLocation(rows: List<DoctorServicePrice>, locationId: Long?): List<DoctorServicePrice> {
        val relevant = if (locationId == null) {
            rows.filter { it.location == null }
        } else {
            rows
        }
        val byCurrency = relevant.groupBy { it.currency.uppercase() }
        return byCurrency.mapNotNull { (_, list) ->
            if (locationId == null) {
                list.find { it.location == null }
            } else {
                list.find { it.location?.id == locationId } ?: list.find { it.location == null }
            }
        }
    }

    /** Mirrors legacy behaviour: first currency when sorted lexicographically. */
    fun pickPaymentPrice(rows: List<DoctorServicePrice>, locationId: Long?): DoctorServicePrice? {
        val effective = effectivePricesForLocation(rows, locationId)
        if (effective.isEmpty()) return null
        return effective.sortedBy { it.currency.uppercase() }.firstOrNull()
    }
}
