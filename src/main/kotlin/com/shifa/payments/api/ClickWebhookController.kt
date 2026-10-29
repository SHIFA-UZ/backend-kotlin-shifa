package com.shifa.payments.api

import com.shifa.payments.infrastructure.ClickCompleteRequest
import com.shifa.payments.infrastructure.ClickCompleteResponse
import com.shifa.payments.infrastructure.ClickPrepareRequest
import com.shifa.payments.infrastructure.ClickPrepareResponse
import com.shifa.payments.application.ClickShopCallbackService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/webhooks/click")
class ClickWebhookController(
    private val clickShopCallbackService: ClickShopCallbackService
) {
    @PostMapping("/prepare")
    fun prepare(@RequestBody request: ClickPrepareRequest): ClickPrepareResponse =
        clickShopCallbackService.handlePrepare(request)

    @PostMapping("/complete")
    fun complete(@RequestBody request: ClickCompleteRequest): ClickCompleteResponse =
        clickShopCallbackService.handleComplete(request)
}
