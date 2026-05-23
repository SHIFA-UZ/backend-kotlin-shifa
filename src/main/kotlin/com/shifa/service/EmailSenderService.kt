package com.shifa.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import org.springframework.web.util.HtmlUtils

@Service
class EmailSenderService(
    private val mailSender: JavaMailSender,
    @Value("\${spring.mail.from:}") private val fromEmail: String,
    @Value("\${spring.mail.from-name:Shifa Health}") private val fromName: String,
    @Value("\${spring.mail.username:}") private val smtpUsername: String
) {
    private val log = LoggerFactory.getLogger(EmailSenderService::class.java)

    fun isConfigured(): Boolean = smtpUsername.isNotBlank()

    /**
     * Resolve sender: use explicit MAIL_FROM if set, otherwise fall back to MAIL_USERNAME
     * (Brevo requires the sender to be the account email or a verified domain).
     */
    private fun resolvedFrom(): String =
        fromEmail.takeIf { it.isNotBlank() && it.contains("@") } ?: smtpUsername

    fun sendOtpEmail(toEmail: String, code: String, purpose: String) {
        if (!isConfigured()) {
            log.warn("SMTP not configured — OTP for {} [{}]: {} (set MAIL_USERNAME/MAIL_PASSWORD for real delivery)",
                toEmail.take(3) + "***", purpose, code)
            return
        }
        val sender = resolvedFrom()
        log.info("Sending OTP email from={} to={} purpose={}", sender, toEmail.take(3) + "***", purpose)
        try {
            val subject = when (purpose) {
                "REGISTRATION" -> "Shifa – Verify your email"
                "LOGIN" -> "Shifa – Your sign-in code"
                "ADMIN_LOGIN" -> "Shifa – Admin sign-in code"
                "FORGOT_PASSWORD" -> "Shifa – Password reset code"
                "ACCOUNT_DELETION" -> "Shifa – Confirm account deletion"
                else -> "Shifa – Verification code"
            }
            val body = buildEmailHtml(code, purpose)
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, "UTF-8")
            helper.setFrom(sender, fromName)
            helper.setTo(toEmail)
            helper.setSubject(subject)
            helper.setText(body, true)
            mailSender.send(message)
            log.info("OTP email sent successfully to {} [{}]", toEmail.take(3) + "***", purpose)
        } catch (e: Exception) {
            log.error("Failed to send OTP email to {} from {}: {} - {}", toEmail.take(3) + "***", sender, e.javaClass.simpleName, e.message)
            throw RuntimeException("Failed to send verification email. Please try again later.")
        }
    }

    private fun buildEmailHtml(code: String, purpose: String): String {
        val heading = when (purpose) {
            "REGISTRATION" -> "Verify your email"
            "LOGIN" -> "Sign-in verification"
            "ADMIN_LOGIN" -> "Admin panel sign-in"
            "FORGOT_PASSWORD" -> "Password reset"
            "ACCOUNT_DELETION" -> "Confirm account deletion"
            else -> "Verification code"
        }
        val instruction = when (purpose) {
            "REGISTRATION" -> "Use this code to complete your registration:"
            "LOGIN" -> "Use this code to sign in to your account:"
            "ADMIN_LOGIN" -> "Use this code to finish signing in to the admin panel:"
            "FORGOT_PASSWORD" -> "Use this code to reset your password:"
            "ACCOUNT_DELETION" -> "Use this code to confirm your account deletion:"
            else -> "Your verification code is:"
        }
        return """
        <!DOCTYPE html>
        <html><head><meta charset="UTF-8"></head>
        <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 480px; margin: 0 auto; padding: 32px 24px; color: #1a1a1a;">
            <div style="text-align: center; margin-bottom: 24px;">
                <h2 style="color: #0D9488; margin: 0;">Shifa Health</h2>
            </div>
            <h3 style="margin: 0 0 8px;">$heading</h3>
            <p style="margin: 0 0 24px; color: #555;">$instruction</p>
            <div style="background: #F0FDFA; border: 2px solid #0D9488; border-radius: 12px; padding: 20px; text-align: center; margin-bottom: 24px;">
                <span style="font-size: 32px; font-weight: 700; letter-spacing: 8px; color: #0D9488;">$code</span>
            </div>
            <p style="margin: 0 0 8px; color: #888; font-size: 13px;">This code expires in 10 minutes.</p>
            <p style="margin: 0; color: #888; font-size: 13px;">If you didn't request this, please ignore this email.</p>
        </body></html>
        """.trimIndent()
    }

    /**
     * Invite a receptionist to join a clinic via the doctor app Verify Key screen.
     */
    fun sendClinicStaffInvitationEmail(
        toEmail: String,
        code: String,
        clinicName: String,
        inviterName: String,
    ) {
        if (!isConfigured()) {
            log.warn(
                "SMTP not configured — clinic invite for {} [{}] code={} clinic={}",
                toEmail.take(3) + "***",
                clinicName,
                code,
                clinicName
            )
            return
        }
        val sender = resolvedFrom()
        log.info(
            "Sending clinic staff invitation email from={} to={} clinic={}",
            sender,
            toEmail.take(3) + "***",
            clinicName
        )
        try {
            val subject = "Shifa – Invitation to join $clinicName"
            val safeClinic = HtmlUtils.htmlEscape(clinicName)
            val safeInviter = HtmlUtils.htmlEscape(inviterName)
            val body = """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"></head>
                <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 480px; margin: 0 auto; padding: 32px 24px; color: #1a1a1a;">
                    <div style="text-align: center; margin-bottom: 24px;">
                        <h2 style="color: #0D9488; margin: 0;">Shifa Health</h2>
                    </div>
                    <h3 style="margin: 0 0 8px;">Clinic invitation</h3>
                    <p style="margin: 0 0 16px; color: #555;">$safeInviter invited you to join <strong>$safeClinic</strong> as a receptionist. Open the <strong>Shifa Doctor</strong> app, choose verification with your invitation code, then complete signup.</p>
                    <div style="background: #F0FDFA; border: 2px solid #0D9488; border-radius: 12px; padding: 20px; text-align: center; margin-bottom: 24px;">
                        <span style="font-size: 28px; font-weight: 700; letter-spacing: 6px; color: #0D9488;">$code</span>
                    </div>
                    <p style="margin: 0 0 8px; color: #888; font-size: 13px;">This code expires on the date set by your clinic.</p>
                    <p style="margin: 0; color: #888; font-size: 13px;">If you were not expecting this, you can ignore this email.</p>
                </body></html>
            """.trimIndent()
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, "UTF-8")
            helper.setFrom(sender, fromName)
            helper.setTo(toEmail)
            helper.setSubject(subject)
            helper.setText(body, true)
            mailSender.send(message)
            log.info("Clinic invitation email sent to {} [{}]", toEmail.take(3) + "***", clinicName)
        } catch (e: Exception) {
            log.error(
                "Failed to send clinic invitation to {} from {}: {} - {}",
                toEmail.take(3) + "***",
                sender,
                e.javaClass.simpleName,
                e.message
            )
            throw RuntimeException("Failed to send invitation email. Please try again later.")
        }
    }
}
