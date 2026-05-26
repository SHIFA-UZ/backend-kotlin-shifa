package com.shifa.payment.click

import com.fasterxml.jackson.annotation.JsonProperty

data class ClickPrepareRequest(
    @JsonProperty("click_trans_id") val clickTransId: Long? = null,
    @JsonProperty("service_id") val serviceId: Long? = null,
    @JsonProperty("click_paydoc_id") val clickPaydocId: Long? = null,
    @JsonProperty("merchant_trans_id") val merchantTransId: String? = null,
    @JsonProperty("amount") val amount: Double? = null,
    @JsonProperty("action") val action: Int? = null,
    @JsonProperty("error") val error: Int? = null,
    @JsonProperty("error_note") val errorNote: String? = null,
    @JsonProperty("sign_time") val signTime: String? = null,
    @JsonProperty("sign_string") val signString: String? = null
)

data class ClickPrepareResponse(
    @JsonProperty("click_trans_id") val clickTransId: Long? = null,
    @JsonProperty("merchant_trans_id") val merchantTransId: String? = null,
    @JsonProperty("merchant_prepare_id") val merchantPrepareId: Long? = null,
    @JsonProperty("error") val error: Int? = null,
    @JsonProperty("error_note") val errorNote: String? = null
)

data class ClickCompleteRequest(
    @JsonProperty("click_trans_id") val clickTransId: Long? = null,
    @JsonProperty("service_id") val serviceId: Long? = null,
    @JsonProperty("click_paydoc_id") val clickPaydocId: Long? = null,
    @JsonProperty("merchant_trans_id") val merchantTransId: String? = null,
    @JsonProperty("merchant_prepare_id") val merchantPrepareId: Long? = null,
    @JsonProperty("amount") val amount: Double? = null,
    @JsonProperty("action") val action: Int? = null,
    @JsonProperty("error") val error: Int? = null,
    @JsonProperty("error_note") val errorNote: String? = null,
    @JsonProperty("sign_time") val signTime: String? = null,
    @JsonProperty("sign_string") val signString: String? = null
)

data class ClickCompleteResponse(
    @JsonProperty("click_trans_id") val clickTransId: Long? = null,
    @JsonProperty("merchant_trans_id") val merchantTransId: String? = null,
    @JsonProperty("merchant_prepare_id") val merchantPrepareId: Long? = null,
    @JsonProperty("merchant_confirm_id") val merchantConfirmId: Long? = null,
    @JsonProperty("error") val error: Int? = null,
    @JsonProperty("error_note") val errorNote: String? = null
)
