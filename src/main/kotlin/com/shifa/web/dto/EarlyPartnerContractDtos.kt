package com.shifa.web.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class EarlyPartnerContractIssueDto(
    @JsonProperty("contractNumber") val contractNumber: String,
    @JsonProperty("contractSeq") val contractSeq: Int,
    @JsonProperty("effectiveDate") val effectiveDate: String,
    @JsonProperty("termMonths") val termMonths: Int,
    @JsonProperty("partnerFullName") val partnerFullName: String,
    @JsonProperty("partnerClinic") val partnerClinic: String?,
    @JsonProperty("partnerPhone") val partnerPhone: String?,
    @JsonProperty("partnerEmail") val partnerEmail: String?,
    @JsonProperty("roleDoctor") val roleDoctor: Boolean = true,
    @JsonProperty("rolePatient") val rolePatient: Boolean = false,
    @JsonProperty("roleBoth") val roleBoth: Boolean = false,
    @JsonProperty("newAllocation") val newAllocation: Boolean,
    @JsonProperty("issuedAt") val issuedAt: String,
)
