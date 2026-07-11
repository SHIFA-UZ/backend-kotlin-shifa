package com.shifa.clinicalengine.web.dto

data class ClinicalLocalizedNameDto(
    val ru: String,
    val uz: String,
    val en: String,
)

data class ClinicalGroupDto(
    val groupId: String,
    val sortOrder: Int,
    val names: ClinicalLocalizedNameDto,
)

data class ClinicalDiseaseSummaryDto(
    val diseaseId: String,
    val number: Int,
    val groupId: String,
    val slug: String,
    val icdCodes: List<String>,
    val names: ClinicalLocalizedNameDto,
)

data class ClinicalChipDto(
    val chipId: String,
    val field: String,
    val labels: ClinicalLocalizedNameDto,
    val variables: List<String>,
    val priority: Int,
)

data class ClinicalDiseaseDetailDto(
    val diseaseId: String,
    val number: Int,
    val groupId: String,
    val slug: String,
    val icdCodes: List<String>,
    val names: ClinicalLocalizedNameDto,
    val chips: List<ClinicalChipDto>,
)

data class ClinicalOcclusionChipDto(
    val chipId: String,
    val angleClass: String?,
    val icdHint: String?,
    val labels: ClinicalLocalizedNameDto,
    val variables: List<String>,
    val priority: Int,
)

data class ClinicalSharedTemplateDto(
    val templateId: String,
    val type: String,
    val field: String,
    val labels: ClinicalLocalizedNameDto,
    val priority: Int,
    val correlates: List<String>,
)

data class ClinicalTopDiagnosisDto(
    val diseaseId: String,
    val names: ClinicalLocalizedNameDto,
    val icdCodes: List<String>,
    val useCount: Int,
    val lastUsedAt: String?,
)

data class ClinicalChipSelectionDto(
    val chipId: String,
    val variables: Map<String, String> = emptyMap(),
)

data class ClinicalSynthesizeRequestDto(
    val diseaseId: String? = null,
    val locale: String,
    val selections: List<ClinicalChipSelectionDto>,
)

data class ClinicalSynthesizeFieldResultDto(
    val field: String,
    val text: String,
)

data class ClinicalSynthesizeResponseDto(
    val fields: Map<String, String>,
)

data class ClinicalRecordUsageRequestDto(
    val diseaseId: String,
)
