package com.shifa.notifications.api.dto

import com.shifa.notifications.application.NotificationDetails

data class NotificationDto(
    val id: Long,
    val title: String,
    val message: String,
    val type: String,
    val appointmentId: Long?,
    val patientFormId: Long? = null,
    val documentAccessRequestId: Long?,
    val taskId: Long? = null,
    val documentId: Long? = null,
    val patientId: Long? = null,
    val documentAccessRequestStatus: String? = null,
    val patientName: String? = null,
    val documentTitle: String? = null,
    val requestingDoctorName: String? = null,
    val createdAt: String,
    val readAt: String?,
    val treatmentPlanId: Long? = null,
    val appointmentStartAt: String? = null,
) {
    companion object {
        fun from(d: NotificationDetails): NotificationDto {
            val n = d.notification
            return NotificationDto(
                id = n.id,
                title = n.title,
                message = n.message,
                type = n.type.name,
                appointmentId = n.appointmentId,
                patientFormId = n.patientFormId,
                documentAccessRequestId = n.documentAccessRequestId,
                taskId = n.taskId,
                documentId = n.documentId,
                patientId = n.documentPatientId,
                documentAccessRequestStatus = d.documentAccessRequestStatus,
                patientName = d.patientName,
                documentTitle = d.documentTitle,
                requestingDoctorName = d.requestingDoctorName,
                createdAt = n.createdAt.toString(),
                readAt = n.readAt?.toString(),
                treatmentPlanId = n.treatmentPlanId,
                appointmentStartAt = d.appointmentStartAt?.toString(),
            )
        }
    }
}
