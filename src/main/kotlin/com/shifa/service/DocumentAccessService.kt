package com.shifa.service

import com.shifa.domain.DocumentAccessGrant
import com.shifa.domain.DocumentAccessRequest
import com.shifa.domain.Notification
import com.shifa.repo.DocumentAccessGrantRepository
import com.shifa.repo.DocumentAccessRequestRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.PatientDocumentRepository
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

    /**
     * Creates a pending access request and notifies the document owner.
     * If a pending request already exists for this document+doctor, returns it without creating a duplicate.
     */
    @Transactional
    fun requestAccess(documentId: Long, requestingDoctorId: Long): DocumentAccessRequest {
        val existing = requestRepo.findByDocument_IdAndRequestingDoctor_IdAndStatus(
            documentId, requestingDoctorId, DocumentAccessRequest.Status.pending
        )
        if (existing != null) return existing

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

        val patientName = document.patient?.fullName ?: "Patient"
        val docTitle = document.title
        val doctorName = "${requestingDoctor.firstName} ${requestingDoctor.lastName}".trim()
        val title = "Document access request"
        val message = "$doctorName requested access to \"$docTitle\" for $patientName."

        val notification = when (ownerType) {
            DocumentAccessRequest.OwnerType.doctor -> Notification(
                patient = null,
                doctor = doctorProfiles.findById(ownerId).orElseThrow { IllegalStateException("Owner doctor not found: $ownerId") },
                title = title,
                message = message,
                type = Notification.Type.DOCUMENT_ACCESS_REQUEST,
                documentAccessRequestId = saved.id,
                documentId = document.id,
                documentPatientId = document.patient?.id,
                documentTitle = docTitle
            )
            DocumentAccessRequest.OwnerType.patient -> Notification(
                patient = document.patient ?: throw IllegalStateException("Document has no patient"),
                doctor = null,
                title = title,
                message = message,
                type = Notification.Type.DOCUMENT_ACCESS_REQUEST,
                documentAccessRequestId = saved.id,
                documentId = document.id,
                documentPatientId = document.patient?.id,
                documentTitle = docTitle
            )
        }
        val savedNotif = notificationRepo.save(notification)
        notification.patient?.fcmToken?.let { fcmService.sendPatientNotification(it, savedNotif) }
        notification.doctor?.fcmToken?.let { fcmService.sendDoctorNotification(it, savedNotif) }

        return saved
    }

    @Transactional
    fun approve(requestId: Long, approverOwnerType: DocumentAccessRequest.OwnerType, approverOwnerId: Long): DocumentAccessRequest {
        val request = requestRepo.findById(requestId)
            .orElseThrow { IllegalArgumentException("Request not found: $requestId") }
        if (request.status != DocumentAccessRequest.Status.pending) {
            throw IllegalArgumentException("Request is not pending")
        }
        if (request.ownerType != approverOwnerType || request.ownerId != approverOwnerId) {
            throw IllegalArgumentException("Not authorized to approve this request")
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
        if (request.status != DocumentAccessRequest.Status.pending) {
            throw IllegalArgumentException("Request is not pending")
        }
        if (request.ownerType != rejectorOwnerType || request.ownerId != rejectorOwnerId) {
            throw IllegalArgumentException("Not authorized to reject this request")
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
