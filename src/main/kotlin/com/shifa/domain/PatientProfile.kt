// src/main/kotlin/com/shifa/domain/PatientProfile.kt
package com.shifa.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.OffsetDateTime

@Entity
@Table(name = "patient_profiles")
open class PatientProfile(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

    @Column(name = "full_name", nullable = false)
    open var fullName: String = "",

    @Column(name = "phone")
    open var phone: String? = null,

    @Column(name = "phone_normalized")
    open var phoneNormalized: String? = null,

    @Column(name = "email")
    open var email: String? = null,

    @Column(name = "address")
    open var address: String? = null,
    
    // Location fields - Coordinates (for distance calculations)
    @Column(name = "latitude")
    open var latitude: Double? = null,
    
    @Column(name = "longitude")
    open var longitude: Double? = null,
    
    // Structured location fields (for search and filtering)
    @Column(name = "location_country")
    open var locationCountry: String? = null,
    
    @Column(name = "location_region")
    open var locationRegion: String? = null, // Viloyat
    
    @Column(name = "location_district")
    open var locationDistrict: String? = null, // Tuman
    
    @Column(name = "location_city")
    open var locationCity: String? = null,
    
    @Column(name = "location_postal_code")
    open var locationPostalCode: String? = null,
    
    @Column(name = "location_street_address")
    open var locationStreetAddress: String? = null, // Editable street address

    @Column(name = "birth_date")
    open var birthDate: LocalDate? = null,

    @Column(name = "language")
    open var language: String? = null,

    // ✅ NEW: photo URL column
    @Column(name = "photo_url")
    open var photoUrl: String? = null,

    @Column(name = "chronic_disease")
    open var chronicDisease: String? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    open var user: User? = null,

    /** When set, this patient was created by this doctor and appears in their "my patients" list even before any appointment. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_doctor_id")
    open var createdByDoctor: DoctorProfile? = null,

    /** FCM token for patient app push notifications (set by patient app on login, cleared on logout). */
    @Column(name = "fcm_token")
    open var fcmToken: String? = null,

    /** IANA timezone (e.g. Europe/Berlin). Remote task schedule and reminders use this; null → UTC in code. */
    @Column(name = "time_zone", length = 64)
    open var timeZone: String? = null,

    @Column(name = "created_at", insertable = false, updatable = false)
    open var createdAt: OffsetDateTime? = null,

    @OneToMany(
        mappedBy = "patient",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    open var documents: MutableList<PatientDocument> = mutableListOf()
) {
    protected constructor() : this(
        id = null,
        fullName = "",
        phone = null,
        phoneNormalized = null,
        email = null,
        address = null,
        latitude = null,
        longitude = null,
        locationCountry = null,
        locationRegion = null,
        locationDistrict = null,
        locationCity = null,
        locationPostalCode = null,
        locationStreetAddress = null,
        birthDate = null,
        language = null,
        photoUrl = null,
        chronicDisease = null,
        user = null,
        createdByDoctor = null,
        fcmToken = null,
        timeZone = null,
        createdAt = null,
        documents = mutableListOf()
    )

    fun addDocument(doc: PatientDocument) {
        documents.add(doc)
        doc.patient = this
    }

    fun removeDocument(doc: PatientDocument) {
        documents.remove(doc)
        doc.patient = null
    }
}
