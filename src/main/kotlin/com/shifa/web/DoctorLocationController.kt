// src/main/kotlin/com/shifa/web/DoctorLocationController.kt
package com.shifa.web

import com.shifa.domain.DoctorLocation
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DateSpecificScheduleRuleRepository
import com.shifa.repo.DoctorLocationRepository
import com.shifa.repo.WeeklyScheduleRuleRepository
import com.shifa.security.DoctorPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.OffsetDateTime

/**
 * CRUD for the doctor's practice locations. A doctor can have one or more locations and
 * assign schedule rules / appointments per location. When only one location exists it is
 * implicitly primary.
 */
@RestController
@RequestMapping("/api/doctors/me/locations")
class DoctorLocationController(
    private val locations: DoctorLocationRepository,
    private val weeklyRules: WeeklyScheduleRuleRepository,
    private val dateRules: DateSpecificScheduleRuleRepository,
    private val appointments: AppointmentRepository
) {

    data class LocationDto(
        val id: Long?,
        val label: String,
        val clinic: String? = null,
        val address: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val locationCountry: String? = null,
        val locationRegion: String? = null,
        val locationDistrict: String? = null,
        val locationCity: String? = null,
        val locationPostalCode: String? = null,
        val locationStreetAddress: String? = null,
        val isPrimary: Boolean = false
    )

    @GetMapping
    @Transactional(readOnly = true)
    fun list(@AuthenticationPrincipal principal: DoctorPrincipal): List<LocationDto> {
        val d = principal.profile
        return locations.findByDoctorIdOrderByIsPrimaryDescIdAsc(d.id).map(::toDto)
    }

    @PostMapping
    @Transactional
    fun create(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: LocationDto
    ): LocationDto {
        val d = principal.profile
        val label = body.label.trim()
        if (label.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Location label is required")
        }

        val existing = locations.findByDoctorIdOrderByIsPrimaryDescIdAsc(d.id)
        val shouldBePrimary = body.isPrimary || existing.isEmpty()
        if (shouldBePrimary) {
            // Un-primary any previously primary row to keep the unique index happy.
            existing.filter { it.isPrimary }.forEach {
                it.isPrimary = false
                it.updatedAt = OffsetDateTime.now()
                locations.save(it)
            }
        }

        val saved = locations.save(
            DoctorLocation(
                doctor = d,
                label = label,
                clinic = body.clinic?.takeIf { it.isNotBlank() },
                address = body.address?.takeIf { it.isNotBlank() },
                latitude = body.latitude,
                longitude = body.longitude,
                locationCountry = body.locationCountry?.takeIf { it.isNotBlank() },
                locationRegion = body.locationRegion?.takeIf { it.isNotBlank() },
                locationDistrict = body.locationDistrict?.takeIf { it.isNotBlank() },
                locationCity = body.locationCity?.takeIf { it.isNotBlank() },
                locationPostalCode = body.locationPostalCode?.takeIf { it.isNotBlank() },
                locationStreetAddress = body.locationStreetAddress?.takeIf { it.isNotBlank() },
                isPrimary = shouldBePrimary
            )
        )
        return toDto(saved)
    }

    @PatchMapping("/{id}")
    @Transactional
    fun update(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long,
        @RequestBody body: LocationDto
    ): LocationDto {
        val d = principal.profile
        val loc = locations.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found") }
        if (loc.doctor.id != d.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not your location")
        }

        body.label.trim().takeIf { it.isNotEmpty() }?.let { loc.label = it }
        body.clinic?.let { loc.clinic = it.takeIf { s -> s.isNotBlank() } }
        body.address?.let { loc.address = it.takeIf { s -> s.isNotBlank() } }
        loc.latitude = body.latitude ?: loc.latitude
        loc.longitude = body.longitude ?: loc.longitude
        body.locationCountry?.let { loc.locationCountry = it.takeIf { s -> s.isNotBlank() } }
        body.locationRegion?.let { loc.locationRegion = it.takeIf { s -> s.isNotBlank() } }
        body.locationDistrict?.let { loc.locationDistrict = it.takeIf { s -> s.isNotBlank() } }
        body.locationCity?.let { loc.locationCity = it.takeIf { s -> s.isNotBlank() } }
        body.locationPostalCode?.let { loc.locationPostalCode = it.takeIf { s -> s.isNotBlank() } }
        body.locationStreetAddress?.let { loc.locationStreetAddress = it.takeIf { s -> s.isNotBlank() } }

        if (body.isPrimary && !loc.isPrimary) {
            locations.findByDoctorIdOrderByIsPrimaryDescIdAsc(d.id)
                .filter { it.isPrimary && it.id != loc.id }
                .forEach {
                    it.isPrimary = false
                    it.updatedAt = OffsetDateTime.now()
                    locations.save(it)
                }
            loc.isPrimary = true
        }

        loc.updatedAt = OffsetDateTime.now()
        return toDto(locations.save(loc))
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long
    ) {
        val d = principal.profile
        val loc = locations.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found") }
        if (loc.doctor.id != d.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not your location")
        }

        val allLocations = locations.findByDoctorIdOrderByIsPrimaryDescIdAsc(d.id)
        if (allLocations.size <= 1) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "You must keep at least one location. Add another location before removing this one."
            )
        }

        val weekly = weeklyRules.findByDoctorIdAndLocationId(d.id, loc.id)
        val dateSpecific = dateRules.findByDoctorIdAndLocationId(d.id, loc.id)
        if (weekly.isNotEmpty() || dateSpecific.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cannot delete a location with active schedule rules. Remove the rules for this location first."
            )
        }

        val futureAppts = appointments.countFutureByDoctorAndLocation(d.id, loc.id, Instant.now())
        if (futureAppts > 0) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cannot delete a location with upcoming appointments ($futureAppts). Cancel or move them first."
            )
        }

        val wasPrimary = loc.isPrimary
        locations.delete(loc)

        // Ensure another location becomes primary if we just removed the primary one.
        if (wasPrimary) {
            val remaining = locations.findByDoctorIdOrderByIsPrimaryDescIdAsc(d.id)
            remaining.firstOrNull()?.let {
                it.isPrimary = true
                it.updatedAt = OffsetDateTime.now()
                locations.save(it)
            }
        }
    }

    private fun toDto(l: DoctorLocation): LocationDto = LocationDto(
        id = l.id,
        label = l.label,
        clinic = l.clinic,
        address = l.address,
        latitude = l.latitude,
        longitude = l.longitude,
        locationCountry = l.locationCountry,
        locationRegion = l.locationRegion,
        locationDistrict = l.locationDistrict,
        locationCity = l.locationCity,
        locationPostalCode = l.locationPostalCode,
        locationStreetAddress = l.locationStreetAddress,
        isPrimary = l.isPrimary
    )
}
