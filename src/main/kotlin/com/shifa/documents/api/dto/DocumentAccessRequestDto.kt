package com.shifa.documents.api.dto

import com.shifa.documents.domain.DocumentAccessRequest

data class DocumentAccessRequestDto(
    val id: Long,
    val documentId: Long,
    val requestingDoctorId: Long,
    val ownerType: String,
    val ownerId: Long,
    val status: String,
    val createdAt: String,
) {
    companion object {
        fun from(r: DocumentAccessRequest): DocumentAccessRequestDto = DocumentAccessRequestDto(
            id = r.id,
            documentId = checkNotNull(r.document.id) { "document without id" },
            requestingDoctorId = r.requestingDoctor.id,
            ownerType = r.ownerType.name,
            ownerId = r.ownerId,
            status = r.status.name,
            createdAt = r.createdAt.toString(),
        )
    }
}
