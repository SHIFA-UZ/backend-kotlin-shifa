package com.shifa.web.dto

import java.time.LocalDate

data class PatientFormFollowupDto(
    val date: LocalDate,
    val clinicalFindings: String,
    val doctorName: String? = null
)

data class PatientFormDto(
    val id: Long? = null,
    val patientId: Long,
    val templateId: String,
    val date: LocalDate,
    val fullName: String,
    val gender: String? = null,
    val address: String? = null,
    val age: Int? = null,
    val job: String? = null,
    val diagnosis: String? = null,
    val diagnosisCode: String? = null,
    val diagnosisDisplay: String? = null,
    val diagnosisSystem: String? = null,
    val complaints: String? = null,
    val otherIllnesses: String? = null,
    val moreDetails: String? = null,
    val visualCheckup: String? = null,

    // New 025-2 fields
    val occlusion: String? = null,
    val oralCavityCondition: String? = null,
    val xrayLabData: String? = null,
    val treatment: String? = null,
    val treatmentResult: String? = null,
    val recommendations: String? = null,
    val doctorName: String? = null,
    val doctorClinic: String? = null,
    val formNumber: Int? = null,

    val dentalChart: Map<String, String> = emptyMap(),
    val followups: List<PatientFormFollowupDto> = emptyList(),

    val documentId: Long? = null
)