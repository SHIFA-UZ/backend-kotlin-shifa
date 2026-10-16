package com.shifa.intelligence.appointment

/**
 * Deterministic routing for visit actions so a dictated note is not treated as
 * permission to start, complete, or request a signature.
 */
object MorningVisitIntent {
    enum class Action { OPEN, START, COMPLETE, NOTE, SIGNATURE }

    fun explicit(text: String): Set<Action> {
        val actions = mutableSetOf<Action>()
        if (START.containsMatchIn(text)) actions += Action.START
        if (OPEN.containsMatchIn(text)) actions += Action.OPEN
        if (isNote(text)) actions += Action.NOTE
        if (isComplete(text)) actions += Action.COMPLETE
        if (SIGNATURE.containsMatchIn(text)) actions += Action.SIGNATURE
        return actions
    }

    fun nudge(text: String): String? {
        val actions = explicit(text)
        if (actions.isEmpty()) {
            return if (AMBIGUOUS_CLOSE.containsMatchIn(text)) {
                "AMBIGUOUS VISIT CLOSE: ask whether to complete the visit, save a note, or request a signature. Do not call those tools."
            } else {
                null
            }
        }
        val steps = mutableListOf<String>()
        when {
            Action.START in actions -> steps += "call start_appointment. Do not complete or request a signature unless they also asked."
            Action.OPEN in actions -> steps += "call open_appointment. Do not change the appointment status."
        }
        if (Action.NOTE in actions) {
            steps += "call append_consultation_draft with only the clinical words. Saving a note does not complete the visit and does not request a signature."
        }
        if (Action.COMPLETE in actions) steps += "call complete_appointment."
        if (Action.SIGNATURE in actions) steps += "call request_appointment_signature."
        val withheld = mutableListOf<String>()
        if (Action.COMPLETE !in actions) withheld += "do not complete the visit"
        if (Action.SIGNATURE !in actions) withheld += "do not request a signature"
        if (Action.START !in actions && Action.OPEN !in actions) withheld += "do not start or open a visit unless they asked"
        return "VISIT INTENT: ${steps.joinToString(" ")} ${withheld.joinToString("; ")}."
    }

    private fun isNote(text: String): Boolean =
        NOTE.containsMatchIn(text) || NOTE_AS_COMPLETE.containsMatchIn(text)

    private fun isComplete(text: String): Boolean =
        !NOTE_AS_COMPLETE.containsMatchIn(text) &&
            (COMPLETE.containsMatchIn(text) || DONE_WITH_VISIT.containsMatchIn(text))

    private val START = Regex(
        """\b(start|begin) (my |the |this )?(next )?(appointment|consultation|visit)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val OPEN = Regex(
        """\b(open|show) (my )?(next |the |this )?(appointment|consultation|visit)\b|\bopen the documentation\b""",
        RegexOption.IGNORE_CASE,
    )
    private val COMPLETE = Regex(
        """\b(complete|finish|end) (the |this |my )?(appointment|consultation|visit)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DONE_WITH_VISIT = Regex(
        """\b(i'm|i am) done with (the |this |my )?(appointment|visit|consultation)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NOTE = Regex(
        """\b(add that|the patient reports|save the draft|read the note|dictate|consultation notes?|save (these |the |my )?(notes|consultation))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NOTE_AS_COMPLETE = Regex(
        """\b(complete|finish|end) (the |this |my )?(appointment |consultation |visit )?notes?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SIGNATURE = Regex(
        """\b(request|ask for|get) (the |a )?(patient's |patient )?signature\b""",
        RegexOption.IGNORE_CASE,
    )
    private val AMBIGUOUS_CLOSE = Regex(
        """\b(finish up|wrap up|wrap this up)\b""",
        RegexOption.IGNORE_CASE,
    )
}
