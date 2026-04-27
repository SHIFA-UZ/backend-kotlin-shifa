package com.shifa.domain

import jakarta.persistence.*

@Entity @Table(name = "doctor_profiles")
class DoctorProfile(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne @JoinColumn(name = "user_id", nullable = false, unique = true)
    val user: User,

    @Column(name = "first_name", nullable = false) var firstName: String,
    @Column(name = "last_name",  nullable = false) var lastName: String,

    var dob: java.time.LocalDate? = null,
    var gender: String? = null,
    var address: String? = null,
    var clinic: String? = null,
    var profession: String? = null,
    var avatarUrl: String? = null,
    
    // Location fields - Coordinates (for distance calculations)
    @Column(name = "latitude")
    var latitude: Double? = null,
    
    @Column(name = "longitude")
    var longitude: Double? = null,
    
    // Structured location fields (for search and filtering)
    @Column(name = "location_country")
    var locationCountry: String? = null,
    
    @Column(name = "location_region")
    var locationRegion: String? = null, // Viloyat
    
    @Column(name = "location_district")
    var locationDistrict: String? = null, // Tuman
    
    @Column(name = "location_city")
    var locationCity: String? = null,
    
    @Column(name = "location_postal_code")
    var locationPostalCode: String? = null,
    
    @Column(name = "location_street_address")
    var locationStreetAddress: String? = null, // Editable street address

    /** From Step 2: start date for weekly schedule validity (from when) */
    @Column(name = "schedule_valid_from")
    var scheduleValidFrom: java.time.LocalDate? = null,

    /** From Step 2: end date for weekly schedule validity (until when) */
    var scheduleValidUntil: java.time.LocalDate? = null,

    /** Additional profile fields */
    @Column(columnDefinition = "TEXT")
    var biography: String? = null,
    
    @Column(columnDefinition = "TEXT")
    var services: String? = null, // JSON array of service names
    
    @Column(columnDefinition = "TEXT")
    var certificates: String? = null, // JSON array of certificate URLs
    
    var telegram: String? = null,
    var instagram: String? = null,

    /** FCM token for doctor web/app push notifications. Set by doctor app on login, cleared on logout. */
    @Column(name = "fcm_token")
    var fcmToken: String? = null,

    /** IANA timezone for practice (e.g. Europe/Berlin). Used for scheduling and "today"; storage remains UTC. */
    @Column(name = "time_zone", nullable = false, length = 64)
    var timeZone: String = "Asia/Tashkent",

    @Column(name = "consultation_price_minor")
    var consultationPriceMinor: Long? = null,

    @Column(name = "consultation_currency", length = 3)
    var consultationCurrency: String? = null
)
