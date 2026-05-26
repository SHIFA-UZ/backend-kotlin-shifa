package com.shifa.payment.web

import com.shifa.payment.click.ClickCompleteRequest
import com.shifa.payment.click.ClickCompleteResponse
import com.shifa.payment.click.ClickPrepareRequest
import com.shifa.payment.click.ClickPrepareResponse
import com.shifa.payment.service.ClickShopCallbackService
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
