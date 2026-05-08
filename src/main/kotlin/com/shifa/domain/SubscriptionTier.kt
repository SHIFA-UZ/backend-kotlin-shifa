package com.shifa.domain

/**
 * Admin-managed subscription tier that controls feature availability across the doctor and
 * patient apps. The tier is independent of the paid subscription gateway data; an admin can set
 * the tier directly from the admin panel.
 *
 * Doctor tiers:
 *   - BASIC:   calendar, document management, chat, patient management, basic analytics
 *   - PRO:     BASIC + EHR, video consultations, AI scribe / speech-to-text, patient briefing,
 *              "Ask Shifa AI"
 *   - PREMIUM: PRO + remote care tasks, advanced analytics, differential diagnosis assistant
 *
 * Patient tiers:
 *   - PRO:     everything except patient-side Shifa AI co-pilot
 *   - PREMIUM: everything including patient-side Shifa AI co-pilot
 *
 * BASIC is invalid for PATIENT users (validated server-side).
 */
enum class SubscriptionTier {
    BASIC,
    PRO,
    PREMIUM;

    fun atLeast(other: SubscriptionTier): Boolean = this.ordinal >= other.ordinal

    fun allows(feature: SubscriptionFeature): Boolean = feature.minTier.let { atLeast(it) }
}

/**
 * Feature codes used by the backend to gate sensitive endpoints. Each feature declares the
 * minimum tier required to access it. Frontend gating mirrors this list but the backend is the
 * source of truth.
 */
enum class SubscriptionFeature(val minTier: SubscriptionTier) {
    // Available to every doctor regardless of tier (no gating).
    CALENDAR(SubscriptionTier.BASIC),
    DOCUMENTS(SubscriptionTier.BASIC),
    CHAT(SubscriptionTier.BASIC),
    PATIENT_MANAGEMENT(SubscriptionTier.BASIC),
    BASIC_ANALYTICS(SubscriptionTier.BASIC),

    // PRO+
    VIDEO_CONSULTATION(SubscriptionTier.PRO),
    AI_NOTES(SubscriptionTier.PRO),
    SPEECH_TO_TEXT(SubscriptionTier.PRO),
    PATIENT_BRIEFING(SubscriptionTier.PRO),
    ASK_SHIFA_AI(SubscriptionTier.PRO),

    // PREMIUM only
    REMOTE_CARE_TASKS(SubscriptionTier.PREMIUM),
    ADVANCED_ANALYTICS(SubscriptionTier.PREMIUM),
    DIFFERENTIAL_DIAGNOSIS(SubscriptionTier.PREMIUM),

    // Patient-side: requires PREMIUM patient tier.
    PATIENT_SHIFA_AI(SubscriptionTier.PREMIUM)
}
