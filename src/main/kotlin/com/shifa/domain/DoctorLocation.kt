package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

/**
 * One practice location for a doctor. A doctor can have multiple locations (e.g. "Main Clinic"
 * in the morning and "Downtown Office" in the afternoon). Each [WeeklyScheduleRule],
 * [DateSpecificScheduleRule], and [Appointment] can reference a location so patients can
 * filter available slots per location and the calendar can show which place the doctor is at.
 *
 * Matches V57__doctor_locations.sql.
 */
@Entity
@Table(name = "doctor_locations")
class DoctorLocation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    val doctor: DoctorProfile,

    /** Short patient-facing label, e.g. "Main Clinic" or "Downtown Office". */
    @Column(nullable = false, length = 120)
    var label: String,

    @Column(columnDefinition = "TEXT")
    var clinic: String? = null,

    @Column(columnDefinition = "TEXT")
    var address: String? = null,

    var latitude: Double? = null,
    var longitude: Double? = null,

    @Column(name = "location_country", length = 100)
    var locationCountry: String? = null,

    @Column(name = "location_region", length = 100)
    var locationRegion: String? = null,

    @Column(name = "location_district", length = 100)
    var locationDistrict: String? = null,

    @Column(name = "location_city", length = 100)
    var locationCity: String? = null,

    @Column(name = "location_postal_code", length = 20)
    var locationPostalCode: String? = null,

    @Column(name = "location_street_address", length = 255)
    var locationStreetAddress: String? = null,

    /**
     * Exactly one location per doctor may be primary (unique partial index enforces it).
     * The primary location is the fallback when a rule/appointment has no explicit location.
     */
    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
