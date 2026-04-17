package com.shifa.domain

import jakarta.persistence.*

/**
 * Medical profession/specialty entity
 * Stores both English and Uzbek translations for each profession
 */
@Entity
@Table(name = "professions", indexes = [
    Index(name = "idx_profession_english", columnList = "english"),
    Index(name = "idx_profession_uzbek", columnList = "uzbek")
])
class Profession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "english", nullable = false, unique = true, length = 255)
    val english: String,

    @Column(name = "uzbek", nullable = false, length = 255)
    val uzbek: String,

    @Column(name = "category", length = 100)
    val category: String? = null, // e.g., "General & Primary Care", "Surgical Specialties"

    @Column(name = "display_order")
    val displayOrder: Int = 0, // For sorting within categories

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true
) {
    // Helper method to get display name based on language
    fun getDisplayName(language: String?): String {
        return when (language?.lowercase()) {
            "uz", "uz_uz" -> uzbek
            else -> english
        }
    }
}
