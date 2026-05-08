package com.shifa.service

import com.shifa.domain.DocumentAccessGrant
import com.shifa.domain.DocumentAccessRequest
import com.shifa.domain.Notification
import com.shifa.repo.DocumentAccessGrantRepository
import com.shifa.repo.DocumentAccessRequestRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.PatientDocumentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DocumentAccessService(
    private val requestRepo: DocumentAccessRequestRepository,
    private val grantRepo: DocumentAccessGrantRepository,
    private val documentRepo: PatientDocumentRepository,
    private val doctorProfiles: DoctorProfileRepository,
    private val notificationRepo: NotificationRepository,
    private val fcmService: FcmService
) {
    private val log = LoggerFactory.getLogger(DocumentAccessService::class.java)

    /**
     * Creates (or refreshes) a pending access request and pushes a
     * notification to the document owner.
     *
     * If a pending request already exists for this document+doctor we keep
     * that request (so we don't lose its createdAt/id), but we still create
     * a fresh in-app notification and re-send the FCM push so the owner is
     * reminded. This handles the common case where the owner missed the
     * first push (no token yet, app uninstalled and reinstalled, push
     * dismissed without action, ...) and the doctor taps "Request access"
     * again.
     */
    @Transactional
    fun requestAccess(documentId: Long, requestingDoctorId: Long): DocumentAccessRequest {
        log.info(
            "DOC_ACCESS_REQUEST: doctor={} document={}",
            requestingDoctorId,
            documentId
        )
        val existing = requestRepo.findByDocument_IdAndRequestingDoctor_IdAndStatus(
            documentId, requestingDoctorId, DocumentAccessRequest.Status.pending
        )
        if (existing != null) {
            log.info(
                "DOC_ACCESS_REQUEST: existing pending request id={} found, refreshing notification",
                existing.id
            )
            sendOwnerRequestNotification(existing)
            return existing
        }

        val document = documentRepo.findById(documentId)
            .orElseThrow { IllegalArgumentException("Document not found: $documentId") }
        val requestingDoctor = doctorProfiles.findById(requestingDoctorId)
            .orElseThrow { IllegalArgumentException("Doctor not found: $requestingDoctorId") }

        val (ownerType, ownerId) = when {
            document.uploadedByDoctor != null -> DocumentAccessRequest.OwnerType.doctor to document.uploadedByDoctor!!.id
            document.uploadedByPatientProfile != null -> DocumentAccessRequest.OwnerType.patient to document.uploadedByPatientProfile!!.id!!
            else -> throw IllegalArgumentException("Document has no known owner")
        }

        val request = DocumentAccessRequest(
            document = document,
            requestingDoctor = requestingDoctor,
            ownerType = ownerType,
            ownerId = ownerId,
            status = DocumentAccessRequest.Status.pending
        )
        val saved = requestRepo.save(request)
        log.info(
            "DOC_ACCESS_REQUEST: saved new request id={} ownerType={} ownerId={}",
            saved.id,
            ownerType,
            ownerId
        )
        sendOwnerRequestNotification(saved)
        return saved
    }

    /**
     * Build + persist the in-app notification for a pending access request
     * and push it to the owner's FCM token (if any). Safe to call multiple
     * times — each call creates a fresh notification entry and re-sends the
     * push so the owner is reminded of the still-pending request.
     */
    private fun sendOwnerRequestNotification(request: DocumentAccessRequest) {
        val document = request.document
        val docTitle = document.title
        val patientName = document.patient?.fullName ?: "Patient"
        val requestingDoctor = request.requestingDoctor
        val doctorName = "${requestingDoctor.firstName} ${requestingDoctor.lastName}".trim()
        val title = "Document access request"
        val message = "$doctorName requested access to \"$docTitle\" for $patientName."

        val notification = when (request.ownerType) {
            DocumentAccessRequest.OwnerType.doctor -> Notification(
                patient = null,
                doctor = doctorProfiles.findById(request.ownerId)
                    .orElseThrow { IllegalStateException("Owner doctor not found: ${request.ownerId}") },
                title = title,
                message = message,
                type = Notification.Type.DOCUMENT_ACCESS_REQUEST,
                documentAccessRequestId = request.id,
                documentId = document.id,
                documentPatientId = document.patient?.id,
                documentTitle = docTitle
            )
            DocumentAccessRequest.OwnerType.patient -> Notification(
                patient = document.patient
                    ?: throw IllegalStateException("Document has no patient"),
                doctor = null,
                title = title,
                message = message,
                type = Notification.Type.DOCUMENT_ACCESS_REQUEST,
                documentAccessRequestId = request.id,
                documentId = document.id,
                documentPatientId = document.patient?.id,
                documentTitle = docTitle
            )
        }
        val savedNotif = notificationRepo.save(notification)

        val patient = notification.patient
        val doctor = notification.doctor
        if (patient != null) {
            val token = patient.fcmToken
            if (token.isNullOrBlank()) {
                log.warn(
                    "DOC_ACCESS_REQUEST: patient {} has no FCM token; in-app notification {} stored but no push sent",
                    patient.id,
                    savedNotif.id
                )
            } else {
                fcmService.sendPatientNotification(token, savedNotif)
            }
        }
        if (doctor != null) {
            val token = doctor.fcmToken
            if (token.isNullOrBlank()) {
                log.warn(
                    "DOC_ACCESS_REQUEST: owner doctor {} has no FCM token; in-app notification {} stored but no push sent",
                    doctor.id,
                    savedNotif.id
                )
            } else {
                fcmService.sendDoctorNotification(token, savedNotif)
            }
        }
    }

    @Transactional
    fun approve(requestId: Long, approverOwnerType: DocumentAccessRequest.OwnerType, approverOwnerId: Long): DocumentAccessRequest {
        val request = requestRepo.findById(requestId)
            .orElseThrow { IllegalArgumentException("Request not found: $requestId") }
        if (request.ownerType != approverOwnerType || request.ownerId != approverOwnerId) {
            throw IllegalArgumentException("Not authorized to approve this request")
        }
        // Idempotency: if the same owner re-approves an already-approved
        // request (e.g. they tapped "Approve" twice from a stale UI), return
        // the request unchanged instead of throwing 400. We still reject the
        // illegal transition rejected → approved.
        when (request.status) {
            DocumentAccessRequest.Status.approved -> {
                log.info("DOC_ACCESS_APPROVE: request {} already approved, returning idempotently", requestId)
                return request
            }
            DocumentAccessRequest.Status.rejected ->
                throw IllegalArgumentException("Request was already rejected and cannot be approved")
            DocumentAccessRequest.Status.pending -> {
                // proceed with approval below
            }
        }

        request.status = DocumentAccessRequest.Status.approved
        requestRepo.save(request)

        val grantedBy = when (approverOwnerType) {
            DocumentAccessRequest.OwnerType.doctor -> DocumentAccessGrant.GrantedByType.doctor
            DocumentAccessRequest.OwnerType.patient -> DocumentAccessGrant.GrantedByType.patient
        }
        val doctor = request.requestingDoctor
        val grant = DocumentAccessGrant(
            document = request.document,
            doctor = doctor,
            grantedBy = grantedBy
        )
        grantRepo.save(grant)

        val doc = request.document
        val docTitle = doc.title
        val patientName = doc.patient?.fullName ?: "Patient"
        val notifForRequester = Notification(
            patient = null,
            doctor = doctor,
            title = "Document access granted",
            message = "Your request for access to \"$docTitle\" for $patientName was approved.",
            type = Notification.Type.DOCUMENT_ACCESS_APPROVED,
            documentAccessRequestId = request.id,
            documentId = doc.id,
            documentPatientId = doc.patient?.id,
            documentTitle = docTitle
        )
        val savedNotif = notificationRepo.save(notifForRequester)
        doctor.fcmToken?.let { fcmService.sendDoctorNotification(it, savedNotif) }

        return request
    }

    @Transactional
    fun reject(requestId: Long, rejectorOwnerType: DocumentAccessRequest.OwnerType, rejectorOwnerId: Long): DocumentAccessRequest {
        val request = requestRepo.findById(requestId)
            .orElseThrow { IllegalArgumentException("Request not found: $requestId") }
        if (request.ownerType != rejectorOwnerType || request.ownerId != rejectorOwnerId) {
            throw IllegalArgumentException("Not authorized to reject this request")
        }
        // Idempotency: re-rejecting an already-rejected request is a no-op.
        // Disallow approved → rejected (would silently revoke an active grant).
        when (request.status) {
            DocumentAccessRequest.Status.rejected -> {
                log.info("DOC_ACCESS_REJECT: request {} already rejected, returning idempotently", requestId)
                return request
            }
            DocumentAccessRequest.Status.approved ->
                throw IllegalArgumentException("Request was already approved and cannot be rejected")
            DocumentAccessRequest.Status.pending -> {
                // proceed with rejection below
            }
        }
        request.status = DocumentAccessRequest.Status.rejected
        val saved = requestRepo.save(request)

        val doc = request.document
        val docTitle = doc.title
        val patientName = doc.patient?.fullName ?: "Patient"
        val notifForRequester = Notification(
            patient = null,
            doctor = request.requestingDoctor,
            title = "Document access denied",
            message = "Your request for access to \"$docTitle\" for $patientName was rejected.",
            type = Notification.Type.DOCUMENT_ACCESS_REJECTED,
            documentAccessRequestId = request.id,
            documentId = doc.id,
            documentPatientId = doc.patient?.id,
            documentTitle = docTitle
        )
        val savedNotif = notificationRepo.save(notifForRequester)
        request.requestingDoctor.fcmToken?.let { fcmService.sendDoctorNotification(it, savedNotif) }

        return saved
    }
}
