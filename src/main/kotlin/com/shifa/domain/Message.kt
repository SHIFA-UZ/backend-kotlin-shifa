package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "messages")
open class Message(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    open var conversation: Conversation? = null,

    // Sender is always a doctor (from User with DOCTOR role)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_user_id", nullable = false)
    open var senderUser: User? = null,

    // If recipient is a doctor
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_doctor_id")
    open var recipientDoctor: User? = null,

    // If recipient is a patient
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_patient_id")
    open var recipientPatient: PatientProfile? = null,

    @Column(name = "text", columnDefinition = "TEXT")
    open var text: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    open var messageType: MessageType = MessageType.TEXT,

    @Column(name = "attachment_url")
    open var attachmentUrl: String? = null,

    @Column(name = "attachment_name")
    open var attachmentName: String? = null,

    @Column(name = "thumbnail_url")
    open var thumbnailUrl: String? = null,

    @Column(name = "file_size")
    open var fileSize: Long? = null,

    @Column(name = "duration")
    open var duration: Int? = null, // For voice messages in seconds

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    open var status: MessageStatus = MessageStatus.SENT,

    @Column(name = "is_read", nullable = false)
    open var isRead: Boolean = false,

    @Column(name = "created_at", nullable = false)
    open var createdAt: OffsetDateTime = OffsetDateTime.now()
) {
    enum class MessageType {
        TEXT,
        IMAGE,
        VOICE,
        DOCUMENT,
        SYSTEM
    }

    enum class MessageStatus {
        SENDING,
        SENT,
        DELIVERED,
        READ
    }
    protected constructor() : this(
        id = null,
        conversation = null,
        senderUser = null,
        recipientDoctor = null,
        recipientPatient = null,
        text = null,
        messageType = MessageType.TEXT,
        attachmentUrl = null,
        attachmentName = null,
        thumbnailUrl = null,
        fileSize = null,
        duration = null,
        status = MessageStatus.SENT,
        isRead = false,
        createdAt = OffsetDateTime.now()
    )
}
