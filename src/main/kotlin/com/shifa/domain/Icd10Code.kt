package com.shifa.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "icd10_codes",
    indexes = [
        Index(name = "idx_icd10_code", columnList = "code"),
        Index(name = "idx_icd10_title", columnList = "title")
    ]
)
class Icd10Code(
    @Id
    @Column(name = "code", length = 16, nullable = false)
    var code: String = "",

    @Column(name = "title", columnDefinition = "TEXT", nullable = false)
    var title: String = "",

    /**
     * Optional Russian title to make searches usable for RU-speaking doctors.
     * Not required for Phase 1; can be backfilled later.
     */
    @Column(name = "title_ru", columnDefinition = "TEXT")
    var titleRu: String? = null,

    /** Optional synonyms/keywords (can include RU). */
    @Column(name = "keywords", columnDefinition = "TEXT")
    var keywords: String? = null,

    /** Optional hierarchy (future-ready). */
    @Column(name = "parent_code", length = 16)
    var parentCode: String? = null
)

