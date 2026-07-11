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
@Table(name = "clinical_occlusion_chip")
class ClinicalOcclusionChip(
    @Id
    @Column(name = "chip_id", length = 32)
    var chipId: String = "",

    @Column(name = "angle_class", length = 8)
    var angleClass: String? = null,

    @Column(name = "icd_hint", length = 16)
    var icdHint: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false, columnDefinition = "jsonb")
    var variables: List<String> = emptyList(),

    @Column(name = "priority", nullable = false)
    var priority: Int = 50,

    @Column(name = "label_ru", nullable = false, columnDefinition = "TEXT")
    var labelRu: String = "",

    @Column(name = "label_uz", nullable = false, columnDefinition = "TEXT")
    var labelUz: String = "",

    @Column(name = "label_en", nullable = false, columnDefinition = "TEXT")
    var labelEn: String = "",
) {
    protected constructor() : this(chipId = "")
}

@Entity
@Table(name = "clinical_shared_template")
class ClinicalSharedTemplate(
    @Id
    @Column(name = "template_id", length = 64)
    var templateId: String = "",

    @Column(name = "template_type", nullable = false, length = 32)
    var templateType: String = "",

    @Column(name = "field_name", nullable = false, length = 32)
    var fieldName: String = "",

    @Column(name = "priority", nullable = false)
    var priority: Int = 50,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "correlates", nullable = false, columnDefinition = "jsonb")
    var correlates: List<String> = emptyList(),

    @Column(name = "label_ru", nullable = false, columnDefinition = "TEXT")
    var labelRu: String = "",

    @Column(name = "label_uz", nullable = false, columnDefinition = "TEXT")
    var labelUz: String = "",

    @Column(name = "label_en", nullable = false, columnDefinition = "TEXT")
    var labelEn: String = "",
) {
    protected constructor() : this(templateId = "")
}

@Entity
@Table(name = "clinical_dental_tooth_key")
class ClinicalDentalToothKey(
    @Id
    @Column(name = "fdi_key", length = 4)
    var fdiKey: String = "",

    @Column(name = "dentition", nullable = false, length = 16)
    var dentition: String = "",

    @Column(name = "quadrant", nullable = false, length = 16)
    var quadrant: String = "",

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) {
    protected constructor() : this(fdiKey = "")
}

@Entity
@Table(name = "clinical_doctor_disease_usage")
class ClinicalDoctorDiseaseUsage(
    @jakarta.persistence.EmbeddedId
    var id: ClinicalDoctorDiseaseUsageId = ClinicalDoctorDiseaseUsageId(),

    @Column(name = "use_count", nullable = false)
    var useCount: Int = 0,

    @Column(name = "last_used_at", nullable = false)
    var lastUsedAt: java.time.OffsetDateTime = java.time.OffsetDateTime.now(),
) {
    protected constructor() : this(id = ClinicalDoctorDiseaseUsageId())
}

@jakarta.persistence.Embeddable
data class ClinicalDoctorDiseaseUsageId(
    @Column(name = "doctor_id")
    var doctorId: Long = 0,

    @Column(name = "disease_id", length = 32)
    var diseaseId: String = "",
) : java.io.Serializable

@Entity
@Table(name = "clinical_doctor_disease_recent")
class ClinicalDoctorDiseaseRecent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "doctor_id", nullable = false)
    var doctorId: Long = 0,

    @Column(name = "disease_id", nullable = false, length = 32)
    var diseaseId: String = "",

    @Column(name = "used_at", nullable = false)
    var usedAt: java.time.OffsetDateTime = java.time.OffsetDateTime.now(),
) {
    protected constructor() : this(doctorId = 0)
}
