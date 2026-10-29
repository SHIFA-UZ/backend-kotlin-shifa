package com.shifa.appointments.domain

/**
 * The phrases that confirm a pending calendar action, and the hidden tokens the assistant leaves in its replies.
 * Pure rules, no Spring: the intents in this package and the application service both use them.
 */
object AppointmentActionPhrases {
    fun isExplicitConfirmation(text: String): Boolean {
        val normalized = text.trim().lowercase()
            .replace(Regex("""[''ʻ’`]"""), "'")
            .replace(Regex("""[,.!?]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return normalized in CONFIRMATIONS
    }

    fun actionToken(messages: List<String>): String? =
        messages.asReversed().firstNotNullOfOrNull { message ->
            ACTION.find(message)?.groupValues?.getOrNull(1)
        }

    fun focusAppointmentId(messages: List<String>): Long? =
        messages.asReversed().firstNotNullOfOrNull { message ->
            FOCUS.find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }

    private val CONFIRMATIONS = setOf(
        "yes",
        "yes cancel",
        "yes cancel them",
        "yes cancel those",
        "confirm",
        "confirm cancellation",
        "go ahead",
        "do it",
        "cancel them",
        "cancel those",
        "proceed",
        "ha",
        "haa",
        "ha bekor qil",
        "ha bekor qiling",
        "xo'p",
        "xop",
        "mayli",
        "да",
        "да отмени",
        "отмени",
        "подтверждаю",
    )
    private val ACTION = Regex("""<appointment_action>([0-9a-fA-F-]{36})</appointment_action>""")
    private val FOCUS = Regex("""<appointment_focus>(\d+)</appointment_focus>""")
}
