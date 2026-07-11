package com.shifa.clinicalengine.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "clinical_disease")
class ClinicalDisease(
    @Id
    @Column(name = "disease_id", length = 32)
    var diseaseId: String = "",

    @Column(name = "group_id", nullable = false, length = 32)
    var groupId: String = "",

    @Column(name = "number_val", nullable = false)
    var numberVal: Int = 0,

    @Column(name = "slug", nullable = false, length = 64)
    var slug: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "icd_codes", nullable = false, columnDefinition = "jsonb")
    var icdCodes: List<String> = emptyList(),

    @Column(name = "name_ru", nullable = false, columnDefinition = "TEXT")
    var nameRu: String = "",

    @Column(name = "name_uz", nullable = false, columnDefinition = "TEXT")
    var nameUz: String = "",

    @Column(name = "name_en", nullable = false, columnDefinition = "TEXT")
    var nameEn: String = "",

    @Column(name = "active", nullable = false)
    var active: Boolean = true,
) {
    protected constructor() : this(diseaseId = "")
}
