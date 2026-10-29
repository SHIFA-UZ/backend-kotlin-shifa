package com.shifa.auth.application

import com.shifa.auth.infrastructure.EmailSenderService
import org.springframework.stereotype.Service

/**
 * The auth feature's door to outgoing e-mail for other features (roadmap Phase 5 step 4): nothing outside this
 * layer touches [EmailSenderService]. Method names match the sender so the callers only changed a type.
 */
@Service
class Emails(private val sender: EmailSenderService) {
    fun sendClinicStaffInvitationEmail(toEmail: String, code: String, clinicName: String, inviterName: String) =
        sender.sendClinicStaffInvitationEmail(toEmail = toEmail, code = code, clinicName = clinicName, inviterName = inviterName)
}
