package com.shifa.payment.click

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Mirrors [integration.payment.clickintegration.util.ClickSignatureUtil] from the official starter template
 * ([Davlatov284/click-integration-spring-boot-starter](https://github.com/Davlatov284/click-integration-spring-boot-starter)).
 */
object ClickSignatureUtil {
    fun generatePrepareSignString(
        clickTransId: Long,
        serviceId: Long,
        secretKey: String,
        merchantTransId: String?,
        amount: Double,
        action: Int,
        signTime: String?
    ): String {
        val data = "${clickTransId}${serviceId}${secretKey}${merchantTransId ?: ""}${amount}${action}${signTime ?: ""}"
        return md5Hex(data)
    }

    fun generateCompleteSignString(
        clickTransId: Long,
        serviceId: Long,
        secretKey: String,
        merchantTransId: String?,
        merchantPrepareId: Long,
        amount: Double,
        action: Int,
        signTime: String?
    ): String {
        val data =
            "${clickTransId}${serviceId}${secretKey}${merchantTransId ?: ""}$merchantPrepareId${amount}${action}${signTime ?: ""}"
        return md5Hex(data)
    }

    fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
