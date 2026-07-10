// src/main/kotlin/com/shifa/web/PublicDoctorController.kt
package com.shifa.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.config.AppProperties
import com.shifa.domain.DoctorProfile
import com.shifa.domain.DoctorService
import com.shifa.repo.DateSpecificScheduleRuleRepository
import com.shifa.repo.DoctorServicePriceRepository
import com.shifa.repo.DoctorServiceRepository
import com.shifa.repo.DoctorLocationRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.DoctorReviewRepository
import com.shifa.repo.WeeklyScheduleRuleRepository
import com.shifa.security.PatientPrincipal
import com.shifa.service.PatientDaySlotsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.ZoneId

@RestController
@RequestMapping("/api/public/doctors")
class PublicDoctorController(
    private val doctorProfiles: DoctorProfileRepository,
    private val reviewRepository: DoctorReviewRepository,
    private val doctorLocations: DoctorLocationRepository,
    private val doctorServices: DoctorServiceRepository,
    private val doctorServicePrices: DoctorServicePriceRepository,
    private val weeklyScheduleRules: WeeklyScheduleRuleRepository,
    private val dateSpecificScheduleRules: DateSpecificScheduleRuleRepository,
    private val daySlotsService: PatientDaySlotsService,
    private val appProps: AppProperties,
    private val objectMapper: ObjectMapper
) {
    data class DoctorListResponse(
        val totalCount: Long,
        val page: Int,
        val pageSize: Int,
        val doctors: List<DoctorDto>
    )

    private data class PriceSummary(
        val minPriceMinor: Long?,
        val minPriceCurrency: String?,
        val onlineMinPriceMinor: Long?,
        val clinicMinPriceMinor: Long?
    )

    private data class DoctorEnrichment(
        val supportsOnline: Boolean,
        val supportsInPerson: Boolean,
        val priceSummary: PriceSummary,
        val nextAvailableStartAt: Instant?
    )
    data class ServicePriceDto(
        val amountMinor: Long,
        val currency: String,
        /** Null means the default price row for all locations (unless overridden per location). */
        val locationId: Long? = null
    )

    data class ServiceDto(
        val id: Long,
        val title: String,
        val description: String?,
        /** True when this video-bookable service is free (no patient payment). */
        val isFreeConsultation: Boolean = false,
        val groupId: Long? = null,
        val groupName: String? = null,
        val groupSortOrder: Int? = null,
        val prices: List<ServicePriceDto>
    )


    data class PublicLocationDto(
        val id: Long,
        val label: String,
        val clinic: String?,
        val address: String?,
        val latitude: Double?,
        val longitude: Double?,
        val locationCountry: String?,
        val locationRegion: String?,
        val locationCity: String?,
        val locationStreetAddress: String?,
        val isPrimary: Boolean
    )

    data class DoctorDto(
        val id: Long?,
        val firstName: String,
        val lastName: String,
        val fullName: String,
        val profession: String?,
        val clinic: String?,
        val address: String?,
        val phone: String?,
        val email: String?,
        val photoUrl: String?,
        val averageRating: Double?,
        val reviewCount: Long,
        val biography: String?,
        val services: List<String>?,
        val serviceItems: List<ServiceDto>?,
        val certificates: List<String>?,
        val telegram: String?,
        val instagram: String?,
        val specializations: List<String>?,
        val furtherInformation: String?,
        val latitude: Double?,
        val longitude: Double?,
        val distanceKm: Double? = null, // Distance in kilometers if user location provided
        val locationRegion: String? = null,
        val locationCity: String? = null,
        val locationStreetAddress: String? = null,
        val locationCountry: String? = null,
        val nextAvailableStartAt: String? = null,
        val supportsOnline: Boolean = false,
        val supportsInPerson: Boolean = false,
        val minPriceMinor: Long? = null,
        val minPriceCurrency: String? = null,
        val onlineMinPriceMinor: Long? = null,
        val clinicMinPriceMinor: Long? = null
    )

    private fun normalizeAvatarUrl(avatarUrl: String?): String? {
        val trimmed = avatarUrl?.trim() ?: return null
        val isAbs = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        return if (isAbs) trimmed
        else "${appProps.publicBaseUrl.removeSuffix("/")}/${trimmed.removePrefix("/")}"
    }

    private fun parseJsonList(jsonString: String?): List<String>? {
        if (jsonString.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(jsonString, Array<String>::class.java).toList()
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeCertificateUrl(certUrl: String?): String? {
        if (certUrl.isNullOrBlank()) return null
        val trimmed = certUrl.trim()
        val isAbs = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        return if (isAbs) trimmed
        else "${appProps.publicBaseUrl.removeSuffix("/")}/${trimmed.removePrefix("/")}"
    }

    /**
     * Calculate distance between two coordinates using Haversine formula
     * Returns distance in kilometers
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadiusKm * c
    }

    /**
     * GET /api/public/doctors
     * List all available doctors (for patients to search/browse)
     */
    @GetMapping
    fun listDoctors(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) profession: String?,
        @RequestParam(required = false) clinic: String?,
        @RequestParam(required = false) region: String?,
        @RequestParam(required = false) country: String?,
        @RequestParam(required = false) consultationType: String?,
        @RequestParam(required = false) availableWithin: String?,
        @RequestParam(required = false) minRating: Double?,
        @RequestParam(required = false) minPriceMinor: Long?,
        @RequestParam(required = false) maxPriceMinor: Long?,
        @RequestParam(required = false) verifiedOnly: Boolean?,
        @RequestParam(required = false) latitude: Double?,
        @RequestParam(required = false) longitude: Double?,
        @RequestParam(required = false, defaultValue = "50") radiusKm: Double?,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false, defaultValue = "false") includeNextAvailable: Boolean?,
        @RequestParam(required = false, defaultValue = "1") page: Int?,
        @RequestParam(required = false, defaultValue = "20") pageSize: Int?
    ): DoctorListResponse {
        val pageNum = (page ?: 1).coerceAtLeast(1)
        val size = (pageSize ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)

        var doctors = if (!search.isNullOrBlank() || !profession.isNullOrBlank()) {
            doctorProfiles.searchWithFilters(search, profession)
        } else {
            doctorProfiles.findAllByUserEnabled()
        }

        if (!clinic.isNullOrBlank()) {
            doctors = doctors.filter { it.clinic?.equals(clinic, ignoreCase = true) == true }
        }

        if (!region.isNullOrBlank()) {
            doctors = doctors.filter { doc -> matchesRegionFilter(doc, region) }
        }

        if (!country.isNullOrBlank()) {
            doctors = doctors.filter { doc -> matchesCountryFilter(doc, country) }
        }

        if (verifiedOnly == true) {
            doctors = doctors.filter { doc ->
                !parseJsonList(doc.certificates).isNullOrEmpty()
            }
        }

        val now = Instant.now()
        val enriched = doctors.map { doc ->
            val distance = if (latitude != null && longitude != null &&
                doc.latitude != null && doc.longitude != null
            ) {
                calculateDistance(latitude, longitude, doc.latitude!!, doc.longitude!!)
            } else {
                null
            }
            val enrichment = enrichDoctorLight(doc)
            Triple(doc, distance, enrichment)
        }

        var filtered = enriched

        if (latitude != null && longitude != null && radiusKm != null) {
            filtered = filtered.filter { (_, dist, _) -> dist == null || dist <= radiusKm }
        }

        if (minRating != null) {
            filtered = filtered.filter { (doc, _, _) ->
                (reviewRepository.findAverageRatingByDoctorId(doc.id!!) ?: 0.0) >= minRating
            }
        }

        if (minPriceMinor != null || maxPriceMinor != null) {
            filtered = filtered.filter { (_, _, enrichment) ->
                val price = enrichment.priceSummary.minPriceMinor ?: return@filter false
                if (minPriceMinor != null && price < minPriceMinor) return@filter false
                if (maxPriceMinor != null && price > maxPriceMinor) return@filter false
                true
            }
        }

        if (!consultationType.isNullOrBlank()) {
            filtered = filtered.filter { (_, _, enrichment) ->
                when (consultationType.lowercase()) {
                    "online" -> enrichment.supportsOnline
                    "in_person" -> enrichment.supportsInPerson
                    "both" -> enrichment.supportsOnline && enrichment.supportsInPerson
                    else -> true
                }
            }
        }

        val shouldComputeAvailability =
            includeNextAvailable == true || !availableWithin.isNullOrBlank()

        var working = filtered

        if (!availableWithin.isNullOrBlank()) {
            working = working.map { (doc, dist, enrichment) ->
                val next = daySlotsService.nextAvailableStartAt(
                    doc,
                    now,
                    LIST_AVAILABILITY_LOOKAHEAD_DAYS
                )
                Triple(doc, dist, enrichment.copy(nextAvailableStartAt = next))
            }.filter { (doc, _, enrichment) ->
                matchesAvailableWithin(enrichment.nextAvailableStartAt, availableWithin, doc)
            }
        }

        val sorted = when (sortBy?.lowercase()) {
            "distance" -> {
                if (latitude != null && longitude != null) {
                    working.sortedBy { (_, dist, _) -> dist ?: Double.MAX_VALUE }
                } else {
                    working
                }
            }
            "rating" -> {
                working.sortedByDescending { (doc, _, _) ->
                    reviewRepository.findAverageRatingByDoctorId(doc.id!!) ?: 0.0
                }
            }
            "availability" -> {
                val withSlots = if (availableWithin.isNullOrBlank() && !shouldComputeAvailability) {
                    working.map { (doc, dist, enrichment) ->
                        val next = daySlotsService.nextAvailableStartAt(
                            doc,
                            now,
                            LIST_AVAILABILITY_LOOKAHEAD_DAYS
                        )
                        Triple(doc, dist, enrichment.copy(nextAvailableStartAt = next))
                    }
                } else {
                    working
                }
                withSlots.sortedBy { (_, _, enrichment) ->
                    enrichment.nextAvailableStartAt ?: Instant.MAX
                }
            }
            "reviews" -> {
                working.sortedByDescending { (doc, _, _) ->
                    reviewRepository.countByDoctorId(doc.id!!)
                }
            }
            else -> working
        }

        val totalCount = sorted.size.toLong()
        val fromIndex = (pageNum - 1) * size
        val pageItems = sorted.drop(fromIndex).take(size)

        val pageWithAvailability = if (shouldComputeAvailability && availableWithin.isNullOrBlank()) {
            pageItems.map { (doc, dist, enrichment) ->
                val next = enrichment.nextAvailableStartAt
                    ?: daySlotsService.nextAvailableStartAt(
                        doc,
                        now,
                        LIST_AVAILABILITY_LOOKAHEAD_DAYS
                    )
                Triple(doc, dist, enrichment.copy(nextAvailableStartAt = next))
            }
        } else {
            pageItems
        }

        val doctorDtos = pageWithAvailability.map { (doc, distance, enrichment) ->
            toDoctorDto(doc, distance, enrichment)
        }

        return DoctorListResponse(
            totalCount = totalCount,
            page = pageNum,
            pageSize = size,
            doctors = doctorDtos
        )
    }

    private fun matchesCountryFilter(doc: DoctorProfile, country: String): Boolean {
        val filter = country.trim().lowercase()
        if (filter.isEmpty()) return true

        fun fieldMatches(value: String?, keys: Set<String>): Boolean {
            if (value.isNullOrBlank()) return false
            val v = value.trim().lowercase()
            return keys.any { k -> v == k || v.contains(k) }
        }

        val germanyKeys = setOf("germany", "deutschland", "de")
        val uzbekistanKeys = setOf("uzbekistan", "o'zbekiston", "ozbekiston", "uz")
        val germanCities = setOf(
            "berlin", "munich", "münchen", "hamburg", "cologne", "köln",
            "frankfurt", "stuttgart", "düsseldorf", "dortmund", "essen",
            "leipzig", "bremen", "dresden", "hannover", "nuremberg", "nürnberg"
        )

        if (filter == "germany" || filter == "deutschland" || filter == "de") {
            if (fieldMatches(doc.locationCountry, germanyKeys)) return true
            if (fieldMatches(doc.locationCity, germanCities)) return true
            if (fieldMatches(doc.locationRegion, setOf("berlin", "bavaria", "bayern"))) return true
            val locs = doctorLocations.findByDoctorIdOrderByIsPrimaryDescIdAsc(doc.id!!)
            return locs.any { loc ->
                fieldMatches(loc.locationCountry, germanyKeys) ||
                    fieldMatches(loc.locationCity, germanCities)
            }
        }

        if (uzbekistanKeys.contains(filter) || filter.contains("uzbek")) {
            if (fieldMatches(doc.locationCountry, uzbekistanKeys)) return true
            // Legacy rows without country are treated as Uzbekistan unless clearly German.
            val explicitForeign = fieldMatches(doc.locationCountry, germanyKeys) ||
                fieldMatches(doc.locationCity, germanCities)
            if (!explicitForeign && doc.locationCountry.isNullOrBlank()) return true
            return false
        }

        return fieldMatches(doc.locationCountry, setOf(filter)) ||
            fieldMatches(doc.locationCity, setOf(filter)) ||
            fieldMatches(doc.locationRegion, setOf(filter))
    }

    private fun matchesRegionFilter(doc: DoctorProfile, region: String): Boolean {
        val needles = regionMatchKeys(region)
        if (needles.isEmpty()) return true

        fun fieldMatches(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            val key = normalizeRegionKey(value)
            return needles.any { key == it || key.contains(it) || it.contains(key) }
        }

        if (fieldMatches(doc.locationRegion) || fieldMatches(doc.locationCity)) return true

        val locations = doctorLocations.findByDoctorIdOrderByIsPrimaryDescIdAsc(doc.id!!)
        return locations.any { loc ->
            fieldMatches(loc.locationRegion) || fieldMatches(loc.locationCity)
        }
    }

    private fun regionMatchKeys(region: String): Set<String> {
        val canonical = normalizeRegionKey(region)
        if (canonical.isEmpty()) return emptySet()
        return setOf(canonical)
    }

    /**
     * Loose region matching for filter chips: "Samarqand viloyati" matches city "Samarqand", etc.
     */
    private fun normalizeRegionKey(value: String): String {
        return value.trim().lowercase()
            .replace("tashkent", "toshkent")
            .replace("samarkand", "samarqand")
            .replace("bukhara", "buxoro")
            .replace("fergana", "fargona")
            .replace("jizzakh", "jizzax")
            .replace("khorezm", "xorazm")
            .replace("navoi", "navoiy")
            .replace("kashkadarya", "qashqadaryo")
            .replace("syrdarya", "sirdaryo")
            .replace("surkhandarya", "surxondaryo")
            .replace("andijan", "andijon")
            .replace("karakalpakstan", "qoraqalpogiston")
            .replace(" republic", "")
            .replace("respublikasi", "")
            .replace(" viloyati", "")
            .replace(" region", "")
            .replace(" shahri", "")
            .replace(" city", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 20
        private const val MAX_PAGE_SIZE = 50
        private const val LIST_AVAILABILITY_LOOKAHEAD_DAYS = 21
    }

    private fun enrichDoctorLight(doc: DoctorProfile): DoctorEnrichment {
        val doctorId = doc.id!!
        val activeServices = doctorServices.findByDoctorIdAndIsActiveTrueOrderByCreatedAtAsc(doctorId)
        val supportsOnline = activeServices.isNotEmpty()
        val supportsInPerson = hasPhysicalPresence(doc) && hasSchedule(doctorId)
        val priceSummary = computePriceSummary(activeServices)
        return DoctorEnrichment(
            supportsOnline = supportsOnline,
            supportsInPerson = supportsInPerson,
            priceSummary = priceSummary,
            nextAvailableStartAt = null
        )
    }

    private fun enrichDoctor(doc: DoctorProfile, fromInstant: Instant): DoctorEnrichment {
        val light = enrichDoctorLight(doc)
        val nextAvailable = daySlotsService.nextAvailableStartAt(
            doc,
            fromInstant,
            lookaheadDays = 30
        )
        return light.copy(nextAvailableStartAt = nextAvailable)
    }

    private fun hasPhysicalPresence(doc: DoctorProfile): Boolean {
        val doctorId = doc.id ?: return false
        if (doctorLocations.findByDoctorIdOrderByIsPrimaryDescIdAsc(doctorId).isNotEmpty()) return true
        return doc.latitude != null ||
            doc.longitude != null ||
            !doc.locationCity.isNullOrBlank() ||
            !doc.clinic.isNullOrBlank() ||
            !doc.address.isNullOrBlank()
    }

    private fun hasSchedule(doctorId: Long): Boolean {
        return weeklyScheduleRules.findByDoctorId(doctorId).isNotEmpty() ||
            dateSpecificScheduleRules.findByDoctorId(doctorId).isNotEmpty()
    }

    private fun computePriceSummary(activeServices: List<DoctorService>): PriceSummary {
        var minPrice: Long? = null
        var minCurrency: String? = null
        var onlineMin: Long? = null
        var clinicMin: Long? = null

        for (service in activeServices) {
            if (service.isFreeConsultation) continue
            val prices = doctorServicePrices.findByService_IdOrderByCurrencyAsc(service.id)
            for (price in prices) {
                val amount = price.amountMinor
                if (minPrice == null || amount < minPrice) {
                    minPrice = amount
                    minCurrency = price.currency
                }
                if (price.location?.id == null) {
                    if (onlineMin == null || amount < onlineMin) onlineMin = amount
                } else {
                    if (clinicMin == null || amount < clinicMin) clinicMin = amount
                }
            }
        }

        return PriceSummary(
            minPriceMinor = minPrice,
            minPriceCurrency = minCurrency,
            onlineMinPriceMinor = onlineMin,
            clinicMinPriceMinor = clinicMin
        )
    }

    private fun matchesAvailableWithin(
        nextSlot: Instant?,
        availableWithin: String,
        doctor: DoctorProfile
    ): Boolean {
        if (nextSlot == null) return false
        val zone = ZoneId.of(doctor.timeZone)
        val today = Instant.now().atZone(zone).toLocalDate()
        val slotDate = nextSlot.atZone(zone).toLocalDate()
        return when (availableWithin.lowercase()) {
            "today" -> slotDate == today
            "tomorrow" -> slotDate == today.plusDays(1)
            "week" -> !slotDate.isAfter(today.plusDays(7))
            else -> true
        }
    }

    private fun toDoctorDto(
        doc: DoctorProfile,
        distanceKm: Double?,
        enrichment: DoctorEnrichment
    ): DoctorDto {
        val doctorId = doc.id!!
        val avgRating = reviewRepository.findAverageRatingByDoctorId(doctorId)
        val reviewCount = reviewRepository.countByDoctorId(doctorId)
        val certList = parseJsonList(doc.certificates)
        val normalizedCerts: List<String>? = certList?.mapNotNull { normalizeCertificateUrl(it) }
        val prices = enrichment.priceSummary

        return DoctorDto(
            id = doc.id,
            firstName = doc.firstName,
            lastName = doc.lastName,
            fullName = "${doc.firstName} ${doc.lastName}".trim(),
            profession = doc.profession,
            clinic = doc.clinic,
            address = doc.address,
            phone = doc.user.phone,
            email = doc.user.email,
            photoUrl = normalizeAvatarUrl(doc.avatarUrl),
            averageRating = avgRating,
            reviewCount = reviewCount,
            biography = doc.biography,
            services = parseJsonList(doc.services),
            serviceItems = mapServiceItems(doctorId),
            certificates = normalizedCerts,
            telegram = doc.telegram,
            instagram = doc.instagram,
            specializations = null,
            furtherInformation = doc.biography,
            latitude = doc.latitude,
            longitude = doc.longitude,
            distanceKm = distanceKm,
            locationRegion = doc.locationRegion,
            locationCity = doc.locationCity,
            locationStreetAddress = doc.locationStreetAddress,
            locationCountry = doc.locationCountry,
            nextAvailableStartAt = enrichment.nextAvailableStartAt?.toString(),
            supportsOnline = enrichment.supportsOnline,
            supportsInPerson = enrichment.supportsInPerson,
            minPriceMinor = prices.minPriceMinor,
            minPriceCurrency = prices.minPriceCurrency,
            onlineMinPriceMinor = prices.onlineMinPriceMinor,
            clinicMinPriceMinor = prices.clinicMinPriceMinor
        )
    }

    /**
     * GET /api/public/doctors/{id}
     * Get doctor details by ID
     */
    @GetMapping("/{id}")
    fun getDoctor(@PathVariable id: Long): DoctorDto {
        val doctor = doctorProfiles.findById(id)
            .orElseThrow { org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Doctor not found"
            ) }
        // Do not expose disabled doctors to patients
        if (!doctor.user.enabled) {
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Doctor not found"
            )
        }

        val enrichment = enrichDoctor(doctor, Instant.now())
        return toDoctorDto(doctor, distanceKm = null, enrichment = enrichment)
    }

    private fun mapServiceItems(doctorId: Long): List<ServiceDto> {
        val list = doctorServices.findByDoctorIdAndIsActiveTrueOrderByCreatedAtAsc(doctorId)
        return list.sortedWith(
            compareBy<DoctorService> { it.group?.sortOrder ?: Int.MAX_VALUE }
                .thenBy { it.group?.id ?: Long.MAX_VALUE }
                .thenBy { it.createdAt }
        ).map { s ->
            val priceItems = doctorServicePrices.findByService_IdOrderByCurrencyAsc(s.id).map {
                ServicePriceDto(
                    amountMinor = it.amountMinor,
                    currency = it.currency,
                    locationId = it.location?.id
                )
            }
            ServiceDto(
                id = s.id,
                title = s.title,
                description = s.description,
                isFreeConsultation = s.isFreeConsultation,
                groupId = s.group?.id,
                groupName = s.group?.name,
                groupSortOrder = s.group?.sortOrder,
                prices = priceItems
            )
        }
    }

    /**
     * GET /api/public/doctors/{id}/locations
     *
     * Returns the list of practice locations for a doctor. Used by the patient app to let the
     * patient pick which clinic they want to book before seeing time slots for that location.
     * For doctors who haven't set up multi-location yet, we synthesize a single entry from the
     * legacy profile fields so clients can uniformly handle the one-location case.
     */
    @GetMapping("/{id}/locations")
    fun getDoctorLocations(@PathVariable id: Long): List<PublicLocationDto> {
        val doctor = doctorProfiles.findById(id)
            .orElseThrow { org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Doctor not found"
            ) }
        if (!doctor.user.enabled) {
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Doctor not found"
            )
        }

        val locations = doctorLocations.findByDoctorIdOrderByIsPrimaryDescIdAsc(doctor.id!!)
        if (locations.isNotEmpty()) {
            return locations.map { loc ->
                PublicLocationDto(
                    id = loc.id!!,
                    label = loc.label,
                    clinic = loc.clinic,
                    address = loc.address,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    locationCountry = loc.locationCountry,
                    locationRegion = loc.locationRegion,
                    locationCity = loc.locationCity,
                    locationStreetAddress = loc.locationStreetAddress,
                    isPrimary = loc.isPrimary
                )
            }
        }

        // Legacy fallback: synthesize a single pseudo-location from the profile.
        val hasAnyLocationData = !doctor.clinic.isNullOrBlank() ||
            !doctor.address.isNullOrBlank() ||
            doctor.latitude != null ||
            doctor.longitude != null ||
            !doctor.locationCity.isNullOrBlank()
        if (!hasAnyLocationData) return emptyList()

        return listOf(
            PublicLocationDto(
                id = -1L, // sentinel; clients should treat <=0 as "doctor has no structured location"
                label = doctor.clinic ?: "Main Clinic",
                clinic = doctor.clinic,
                address = doctor.address,
                latitude = doctor.latitude,
                longitude = doctor.longitude,
                locationCountry = doctor.locationCountry,
                locationRegion = doctor.locationRegion,
                locationCity = doctor.locationCity,
                locationStreetAddress = doctor.locationStreetAddress,
                isPrimary = true
            )
        )
    }
}
