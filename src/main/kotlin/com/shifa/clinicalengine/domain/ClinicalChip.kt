package com.shifa.clinicalengine.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "clinical_chip")
class ClinicalChip(
    @Id
    @Column(name = "chip_id", length = 64)
    var chipId: String = "",

    @Column(name = "field_name", nullable = false, length = 32)
    var fieldName: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false, columnDefinition = "jsonb")
    var variables: List<String> = emptyList(),

    @Column(name = "priority", nullable = false)
    var priority: Int = 50,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,
) {
    protected constructor() : this(chipId = "")
}

@Entity
@Table(name = "clinical_chip_i18n")
class ClinicalChipI18n(
    @jakarta.persistence.EmbeddedId
    var id: ClinicalChipI18nId = ClinicalChipI18nId(),

    @Column(name = "label", nullable = false, columnDefinition = "TEXT")
    var label: String = "",
) {
    protected constructor() : this(id = ClinicalChipI18nId())
}

@jakarta.persistence.Embeddable
data class ClinicalChipI18nId(
    @Column(name = "chip_id", length = 64)
    var chipId: String = "",

    @Column(name = "locale", length = 5)
    var locale: String = "",
) : java.io.Serializable

@Entity
@Table(name = "clinical_chip_synthesis_template")
class ClinicalChipSynthesisTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "chip_id", nullable = false, length = 64)
    var chipId: String = "",

    @Column(name = "locale", nullable = false, length = 5)
    var locale: String = "",

    @Column(name = "field_name", nullable = false, length = 32)
    var fieldName: String = "",

    @Column(name = "sentence_template", nullable = false, columnDefinition = "TEXT")
    var sentenceTemplate: String = "",

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) {
    protected constructor() : this(chipId = "")
}

@Entity
@Table(name = "clinical_disease_chip")
class ClinicalDiseaseChip(
    @jakarta.persistence.EmbeddedId
    var id: ClinicalDiseaseChipId = ClinicalDiseaseChipId(),

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) {
    protected constructor() : this(id = ClinicalDiseaseChipId())
}

@jakarta.persistence.Embeddable
data class ClinicalDiseaseChipId(
    @Column(name = "disease_id", length = 32)
    var diseaseId: String = "",

    @Column(name = "chip_id", length = 64)
    var chipId: String = "",
) : java.io.Serializable
