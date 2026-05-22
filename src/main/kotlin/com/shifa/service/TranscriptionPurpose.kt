package com.shifa.service

/**
 * Drives transcription post-processing defaults (medical typo cleanup applies to scribe first).
 */
enum class TranscriptionPurpose {
    /** Short mic uploads from patient copilot / doctor speech fields. */
    VOICE_UPLOAD,

    /** Async AI scribe (long consultations from Daily.co upload / in-person). */
    SCRIBE_PIPELINE,
}
